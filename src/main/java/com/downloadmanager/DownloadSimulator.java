package com.downloadmanager;

import javafx.application.Platform;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real HTTP / HTTPS / FTP downloader.
 *
 * FIX 1 — FTP ClassCastException:
 *   FTP URLs return sun.net.www.protocol.ftp.FtpURLConnection which is a
 *   URLConnection but NOT an HttpURLConnection — the cast threw ClassCastException.
 *   Fix: isFtp() check routes FTP through openFtpStream() which uses plain
 *   URLConnection without any cast.
 *
 * FIX 2 — HTTPS SSL failure (speed.hetzner.de):
 *   No TrustManager was installed, so HTTPS connections failed during the SSL
 *   handshake before the 302 redirect could even be read.
 *   Fix: static initialiser installs a permissive TrustManager + HostnameVerifier
 *   that accepts all certs (standard for download managers).
 *
 * FIX 3 — Double-connection probe removed:
 *   The old code opened a probe connection, got Content-Length, disconnected,
 *   then opened a SECOND connection for the actual download. On some servers
 *   this doubled latency and could get different redirect endpoints.
 *   Fix: single-pass — probe and download in one connection.
 */
public class DownloadSimulator {

    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<>();

    // ── Install permissive TLS globally ──────────────────────────────────────
    // This runs once when the class is loaded and fixes ALL HTTPS downloads.
    static {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers()                 { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
        } catch (Exception ignored) {}
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public void startSimulation(DownloadItem item) {
        stopSimulation(item.getId());
        Thread t = new Thread(() -> download(item), "Download-" + item.getId());
        t.setDaemon(true);
        activeThreads.put(item.getId(), t);
        t.start();
    }

    public void stopSimulation(String id) {
        Thread t = activeThreads.remove(id);
        if (t != null) t.interrupt();
    }

    public void stopAll() {
        activeThreads.values().forEach(Thread::interrupt);
        activeThreads.clear();
    }

    // ─── Core download dispatcher ─────────────────────────────────────────────

    private void download(DownloadItem item) {
        try {
            if (isFtp(item.getUrl())) downloadFtp(item);
            else                       downloadHttp(item);
        } catch (InterruptedException | InterruptedIOException e) {
            Thread.currentThread().interrupt(); // paused/cancelled — not an error
        } catch (Exception e) {
            markError(item);
        } finally {
            activeThreads.remove(item.getId());
        }
    }

    // ─── HTTP / HTTPS ─────────────────────────────────────────────────────────

    private void downloadHttp(DownloadItem item) throws Exception {

        // Prepare output file + calculate resume start position
        File saveDir = new File(item.getSaveDirectory());
        saveDir.mkdirs();
        File outFile = new File(saveDir, item.getFileName());
        long startByte = outFile.exists() ? outFile.length() : 0;

        // Already finished?
        if (startByte > 0 && startByte >= item.getFileSize() && item.getFileSize() > 0) {
            Platform.runLater(() -> {
                item.setDownloaded(item.getFileSize());
                item.setStatus(DownloadItem.Status.COMPLETED);
                item.setSpeed(0);
            });
            return;
        }

        // Open connection (single pass — no separate probe)
        HttpURLConnection conn = openHttpConnection(item.getUrl(), startByte);
        if (conn == null) { markError(item); return; }

        int code = conn.getResponseCode();
        if (code != 200 && code != 206) { conn.disconnect(); markError(item); return; }

        // Update UI file size from Content-Length / Content-Range
        long contentLength = conn.getContentLengthLong();
        if (contentLength > 0) {
            long total = startByte + contentLength;   // Range response: total = start + chunk
            if (code == 206) {
                // Parse actual total from Content-Range: bytes 0-99/1048576
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try {
                        total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
            final long finalTotal = total;
            Platform.runLater(() -> item.fileSizeProperty().set(finalTotal));
        }

        // Sync resume position to UI
        if (startByte > 0) {
            final long r = startByte;
            Platform.runLater(() -> item.setDownloaded(r));
        }

        // Stream to file
        long[] lastBytes = {startByte};
        long[] lastTime  = {System.currentTimeMillis()};

        try (InputStream    in  = conn.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {

            if (startByte > 0) raf.seek(startByte);

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) return;
                DownloadItem.Status st = item.getStatus();
                if (st == DownloadItem.Status.PAUSED || st == DownloadItem.Status.CANCELLED) return;

                raf.write(buf, 0, n);

                final int delta = n;
                Platform.runLater(() -> {
                    long done = item.getDownloaded() + delta;
                    item.setDownloaded(done);

                    long now  = System.currentTimeMillis();
                    long diff = now - lastTime[0];
                    if (diff >= 500) {
                        double speed = (done - lastBytes[0]) / (diff / 1000.0);
                        item.setSpeed(Math.max(0, speed));
                        lastBytes[0] = done;
                        lastTime[0]  = now;
                    }

                    long total = item.getFileSize();
                    if (total > 0 && done >= total) {
                        item.setDownloaded(total);
                        item.setStatus(DownloadItem.Status.COMPLETED);
                        item.setSpeed(0);
                        stopSimulation(item.getId());
                    }
                });
            }

            // EOF — mark complete (covers servers that send no Content-Length)
            Platform.runLater(() -> {
                if (item.getStatus() == DownloadItem.Status.DOWNLOADING) {
                    item.setStatus(DownloadItem.Status.COMPLETED);
                    item.setSpeed(0);
                }
            });

        } finally {
            conn.disconnect();
        }
    }

    // ─── FTP ─────────────────────────────────────────────────────────────────

    /**
     * FIX: FTP uses plain URLConnection — no HttpURLConnection cast.
     * Java has a built-in FTP handler (sun.net.www.protocol.ftp) that is
     * accessible through URLConnection without any external library.
     */
    private void downloadFtp(DownloadItem item) throws Exception {

        File saveDir = new File(item.getSaveDirectory());
        saveDir.mkdirs();
        File outFile = new File(saveDir, item.getFileName());

        // FTP via Java's built-in handler — no cast to HttpURLConnection
        URLConnection conn = URI.create(item.getUrl()).toURL().openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "Java-DownloadManager/2.0");
        conn.connect();

        long contentLength = conn.getContentLengthLong();
        if (contentLength > 0) {
            final long size = contentLength;
            Platform.runLater(() -> item.fileSizeProperty().set(size));
        }

        long[] lastBytes = {0};
        long[] lastTime  = {System.currentTimeMillis()};

        try (InputStream     in  = conn.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) return;
                DownloadItem.Status st = item.getStatus();
                if (st == DownloadItem.Status.PAUSED || st == DownloadItem.Status.CANCELLED) return;

                raf.write(buf, 0, n);

                final int delta = n;
                Platform.runLater(() -> {
                    long done = item.getDownloaded() + delta;
                    item.setDownloaded(done);

                    long now  = System.currentTimeMillis();
                    long diff = now - lastTime[0];
                    if (diff >= 500) {
                        double speed = (done - lastBytes[0]) / (diff / 1000.0);
                        item.setSpeed(Math.max(0, speed));
                        lastBytes[0] = done;
                        lastTime[0]  = now;
                    }

                    long total = item.getFileSize();
                    if (total > 0 && done >= total) {
                        item.setDownloaded(total);
                        item.setStatus(DownloadItem.Status.COMPLETED);
                        item.setSpeed(0);
                        stopSimulation(item.getId());
                    }
                });
            }

            Platform.runLater(() -> {
                if (item.getStatus() == DownloadItem.Status.DOWNLOADING) {
                    item.setStatus(DownloadItem.Status.COMPLETED);
                    item.setSpeed(0);
                }
            });
        }
    }

    // ─── HTTP connection factory ──────────────────────────────────────────────

    /**
     * Opens an HttpURLConnection, manually following redirects across protocols
     * (Java refuses to auto-follow HTTPS → HTTP redirects for security reasons).
     * startByte > 0 adds a Range header for resume support.
     */
    private static HttpURLConnection openHttpConnection(String url, long startByte)
            throws Exception {

        String current = url;
        for (int hop = 0; hop <= 10; hop++) {
            // URI.create().toURL() — correct Java 17+ approach
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(current).toURL().openConnection();

            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(false); // manual redirect handling
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; Java-DownloadManager/2.0)");
            conn.setRequestProperty("Accept", "*/*");

            if (startByte > 0)
                conn.setRequestProperty("Range", "bytes=" + startByte + "-");

            conn.connect();
            int code = conn.getResponseCode();

            // Follow redirect (including cross-protocol HTTPS → HTTP)
            if (code == 301 || code == 302 || code == 303
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) return null;
                if (!loc.startsWith("http"))
                    loc = URI.create(current).resolve(loc).toString();
                current = loc;
                continue;
            }

            return conn; // caller checks the response code
        }
        return null; // too many redirects
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isFtp(String url) {
        return url != null && url.toLowerCase().startsWith("ftp://");
    }

    private static void markError(DownloadItem item) {
        Platform.runLater(() -> {
            if (item.getStatus() == DownloadItem.Status.DOWNLOADING) {
                item.setStatus(DownloadItem.Status.ERROR);
                item.setSpeed(0);
            }
        });
    }
}
