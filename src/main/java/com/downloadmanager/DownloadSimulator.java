package com.downloadmanager;

import javafx.application.Platform;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real HTTP / HTTPS / FTP downloader with bandwidth throttling support.
 *
 * Bandwidth throttle is applied globally across all active downloads via a
 * token-bucket-style sleep: each download thread sleeps after writing a chunk
 * to stay within the configured bytes/sec limit.
 *
 * FIX 1 — FTP ClassCastException (original)
 * FIX 2 — HTTPS SSL failure (original)
 * FIX 3 — Double-connection probe removed (original)
 * NEW   — Bandwidth throttling (setBandwidthLimit)
 * NEW   — Partial file deletion on cancel/remove (handled by DownloadManager)
 */
public class DownloadSimulator {

    /** 0 = unlimited. Shared across all download threads. */
    private volatile long bandwidthLimitBytesPerSec = 0;

    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<>();

    // ── Install permissive TLS globally ─────────────────────────────────────
    static {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers()                 { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
        } catch (Exception ignored) {}
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public void setBandwidthLimit(long bytesPerSec) {
        this.bandwidthLimitBytesPerSec = Math.max(0, bytesPerSec);
    }

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

    // ─── Core dispatcher ─────────────────────────────────────────────────────

    private void download(DownloadItem item) {
        try {
            if (isFtp(item.getUrl())) downloadFtp(item);
            else                       downloadHttp(item);
        } catch (InterruptedException | InterruptedIOException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            markError(item);
        } finally {
            activeThreads.remove(item.getId());
        }
    }

    // ─── HTTP / HTTPS ─────────────────────────────────────────────────────────

    private void downloadHttp(DownloadItem item) throws Exception {

        File saveDir = new File(item.getSaveDirectory());
        saveDir.mkdirs();
        File outFile = new File(saveDir, item.getFileName());

        // Always connect first so we get the real Content-Length from the server.
        // We use startByte=0 for the initial probe to get a 200 with the full size,
        // then seek the RAF to any existing partial bytes for resume.
        long existingBytes = outFile.exists() ? outFile.length() : 0;

        // Open with Range header only if there is a partial file to resume.
        HttpURLConnection conn = openHttpConnection(item.getUrl(), existingBytes);
        if (conn == null) { markError(item); return; }

        int code = conn.getResponseCode();
        if (code != 200 && code != 206) { conn.disconnect(); markError(item); return; }

        // Determine the true total file size from the server response.
        long serverTotal = 0;
        long contentLength = conn.getContentLengthLong();
        if (code == 206) {
            // Prefer Content-Range header: "bytes start-end/total"
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                try { serverTotal = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim()); }
                catch (NumberFormatException ignored) {}
            }
            if (serverTotal <= 0 && contentLength > 0) {
                serverTotal = existingBytes + contentLength;
            }
        } else { // 200
            serverTotal = contentLength; // full file size
        }

        // Update UI with the real file size from the server (overrides any random placeholder).
        if (serverTotal > 0) {
            final long realTotal = serverTotal;
            Platform.runLater(() -> item.fileSizeProperty().set(realTotal));
        }

        // Early-exit: server says 206 and existing partial == full size (truly complete resume).
        final long confirmedTotal = serverTotal;
        if (code == 206 && confirmedTotal > 0 && existingBytes >= confirmedTotal) {
            conn.disconnect();
            Platform.runLater(() -> {
                item.setDownloaded(confirmedTotal);
                item.setStatus(DownloadItem.Status.COMPLETED);
                item.setSpeed(0);
            });
            return;
        }

        // Sync resume start position into the UI.
        long startByte = (code == 206) ? existingBytes : 0;
        if (startByte > 0) {
            final long r = startByte;
            Platform.runLater(() -> item.setDownloaded(r));
        }

        long[] lastBytes = {startByte};
        long[] lastTime  = {System.currentTimeMillis()};

        // Token-bucket throttle state
        long[] bucketTime = {System.nanoTime()};
        long[] bucketBytes = {0};

        try (InputStream     in  = conn.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {

            if (startByte > 0) raf.seek(startByte);

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) return;
                DownloadItem.Status st = item.getStatus();
                if (st == DownloadItem.Status.PAUSED || st == DownloadItem.Status.CANCELLED) return;

                raf.write(buf, 0, n);

                // ── Bandwidth throttle ────────────────────────────────────
                throttle(n, bucketTime, bucketBytes);

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

        } finally {
            conn.disconnect();
        }
    }

    // ─── FTP ─────────────────────────────────────────────────────────────────

    private void downloadFtp(DownloadItem item) throws Exception {

        File saveDir = new File(item.getSaveDirectory());
        saveDir.mkdirs();
        File outFile = new File(saveDir, item.getFileName());

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
        long[] bucketTime  = {System.nanoTime()};
        long[] bucketBytes = {0};

        try (InputStream     in  = conn.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) return;
                DownloadItem.Status st = item.getStatus();
                if (st == DownloadItem.Status.PAUSED || st == DownloadItem.Status.CANCELLED) return;

                raf.write(buf, 0, n);
                throttle(n, bucketTime, bucketBytes);

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

    // ─── Throttle (token-bucket style) ───────────────────────────────────────

    /**
     * Sleeps the calling thread if the current download rate exceeds the global
     * bandwidth cap.  bucketTime[0] and bucketBytes[0] track per-thread state.
     */
    private void throttle(int bytesJustWritten, long[] bucketTime, long[] bucketBytes)
            throws InterruptedException {
        long limit = bandwidthLimitBytesPerSec;
        if (limit <= 0) return; // unlimited

        bucketBytes[0] += bytesJustWritten;
        long elapsed = System.nanoTime() - bucketTime[0];   // nanoseconds

        // How many bytes are allowed in the elapsed time?
        long allowed = (long) (limit * (elapsed / 1_000_000_000.0));

        if (bucketBytes[0] > allowed) {
            // Compute how long we need to wait for the bucket to refill
            long waitMillis = (long) ((bucketBytes[0] - allowed) * 1000.0 / limit);
            if (waitMillis > 0) {
                Thread.sleep(waitMillis);
                // Reset bucket after sleeping
                bucketTime[0]  = System.nanoTime();
                bucketBytes[0] = 0;
            }
        }

        // Reset bucket periodically to avoid drift
        if (elapsed > 1_000_000_000L) {
            bucketTime[0]  = System.nanoTime();
            bucketBytes[0] = 0;
        }
    }

    // ─── HTTP connection factory ──────────────────────────────────────────────

    private static HttpURLConnection openHttpConnection(String url, long startByte)
            throws Exception {
        String current = url;
        for (int hop = 0; hop <= 10; hop++) {
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(current).toURL().openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Java-DownloadManager/2.0)");
            conn.setRequestProperty("Accept", "*/*");
            if (startByte > 0)
                conn.setRequestProperty("Range", "bytes=" + startByte + "-");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) return null;
                if (!loc.startsWith("http")) loc = URI.create(current).resolve(loc).toString();
                current = loc;
                continue;
            }
            return conn;
        }
        return null;
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
