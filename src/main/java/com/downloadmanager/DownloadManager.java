package com.downloadmanager;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DownloadManager {

    // ─── BLOCKED EXTENSIONS ───────────────────────────────────────────────────
    // Add or remove extensions here (code-only — NOT exposed in the UI).
    // Extensions must be lowercase, including the leading dot.
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".zip"
            // Examples of other types you could block:
            // ".torrent", ".bat", ".cmd"
    );

    private final ObservableList<DownloadItem> downloads = FXCollections.observableArrayList();
    private final DownloadSimulator simulator;
    private final NetworkMonitor networkMonitor;
    private final HistoryDatabase historyDb;
    private final Random random = new Random();

    private final StringProperty saveDirectory = new SimpleStringProperty(
            System.getProperty("user.home") + java.io.File.separator + "Downloads");

    /** Bandwidth cap in bytes/second. 0 means unlimited. */
    private volatile long bandwidthLimitBytesPerSec = 0;

    public DownloadManager(NetworkMonitor networkMonitor) {
        this.networkMonitor = networkMonitor;
        this.historyDb      = new HistoryDatabase();
        this.simulator      = new DownloadSimulator();
        historyDb.init();
        networkMonitor.addListener(new NetworkMonitor.Listener() {
            @Override public void onOffline() { pauseAllForNetwork(); }
            @Override public void onOnline()  {}
        });
    }

    // ─── Bandwidth ────────────────────────────────────────────────────────────

    public void setBandwidthLimit(long bytesPerSec) {
        this.bandwidthLimitBytesPerSec = Math.max(0, bytesPerSec);
        simulator.setBandwidthLimit(bandwidthLimitBytesPerSec);
    }

    public long getBandwidthLimit() { return bandwidthLimitBytesPerSec; }

    // ─── Accessors ────────────────────────────────────────────────────────────

    public ObservableList<DownloadItem> getDownloads() { return downloads; }
    public StringProperty saveDirectoryProperty()     { return saveDirectory; }
    public String getSaveDirectory()                  { return saveDirectory.get(); }
    public void   setSaveDirectory(String dir)        { saveDirectory.set(dir); }
    public HistoryDatabase getHistoryDb()             { return historyDb; }

    // ─── Add ──────────────────────────────────────────────────────────────────

    public AddResult addDownloads(String urlText) {
        String[] parts = urlText.split("[\\n,]+");
        List<String> errors = new ArrayList<>();
        int added = 0;

        for (String raw : parts) {
            String url = raw.trim();
            if (url.isEmpty()) continue;

            String err = validateUrl(url);
            if (err != null) { errors.add(url + ": " + err); continue; }

            String fileName  = extractFileName(url);
            String lowerName = fileName.toLowerCase();
            String blocked   = BLOCKED_EXTENSIONS.stream()
                    .filter(lowerName::endsWith).findFirst().orElse(null);
            if (blocked != null) {
                errors.add(url + ": file type \"" + blocked + "\" is not allowed");
                continue;
            }

            long fileSize = parseSizeFromName(fileName);
            if (fileSize <= 0) fileSize = random.nextInt(500_000_000) + 1_000_000;
            int chunks = fileSize < 1_000_000 ? 1
                       : fileSize < 10_000_000 ? 2
                       : fileSize < 100_000_000 ? 4 : 8;

            String id   = UUID.randomUUID().toString().substring(0, 8);
            DownloadItem item = new DownloadItem(id, url, fileName, fileSize, chunks, saveDirectory.get());
            downloads.add(0, item);

            item.statusProperty().addListener((obs, old, val) -> {
                if (val == DownloadItem.Status.COMPLETED
                        || val == DownloadItem.Status.CANCELLED
                        || val == DownloadItem.Status.ERROR) {
                    historyDb.insert(item);
                }
            });

            simulator.startSimulation(item);
            added++;
        }
        return new AddResult(added, errors);
    }

    // ─── Controls ─────────────────────────────────────────────────────────────

    public void pause(DownloadItem item) {
        simulator.stopSimulation(item.getId());
        item.setStatus(DownloadItem.Status.PAUSED);
        item.setSpeed(0);
    }

    public void resume(DownloadItem item) {
        if (!networkMonitor.isOnline()) return;
        item.setStatus(DownloadItem.Status.DOWNLOADING);
        simulator.startSimulation(item);
    }

    public void cancel(DownloadItem item) {
        simulator.stopSimulation(item.getId());
        item.setStatus(DownloadItem.Status.CANCELLED);
        item.setSpeed(0);
        deletePartialFile(item);
    }

    public void retry(DownloadItem item) {
        if (!networkMonitor.isOnline()) return;
        item.setRetryCount(item.getRetryCount() + 1);
        item.setError(null);
        item.setStatus(DownloadItem.Status.DOWNLOADING);
        simulator.startSimulation(item);
    }

    public void remove(DownloadItem item) {
        boolean wasActive = item.getStatus() == DownloadItem.Status.DOWNLOADING
                || item.getStatus() == DownloadItem.Status.PAUSED
                || item.getStatus() == DownloadItem.Status.INTERRUPTED
                || item.getStatus() == DownloadItem.Status.QUEUED;
        simulator.stopSimulation(item.getId());
        if (wasActive) deletePartialFile(item);
        downloads.remove(item);
    }

    public void pauseAll() {
        downloads.stream().filter(d -> d.getStatus() == DownloadItem.Status.DOWNLOADING)
                .forEach(this::pause);
    }

    public void resumeAll() {
        if (!networkMonitor.isOnline()) return;
        downloads.stream().filter(d -> d.getStatus() == DownloadItem.Status.PAUSED
                || d.getStatus() == DownloadItem.Status.INTERRUPTED)
                .forEach(this::resume);
    }

    public void clearCompleted() {
        downloads.removeIf(d -> d.getStatus() == DownloadItem.Status.COMPLETED
                || d.getStatus() == DownloadItem.Status.CANCELLED);
    }

    public void clearAll() {
        downloads.stream().filter(d -> d.getStatus() != DownloadItem.Status.COMPLETED)
                .forEach(this::deletePartialFile);
        simulator.stopAll();
        downloads.clear();
    }

    public void shutdown() { simulator.stopAll(); }

    // ─── Internals ────────────────────────────────────────────────────────────

    private void pauseAllForNetwork() {
        downloads.stream().filter(d -> d.getStatus() == DownloadItem.Status.DOWNLOADING)
                .forEach(d -> {
                    simulator.stopSimulation(d.getId());
                    d.setStatus(DownloadItem.Status.INTERRUPTED);
                    d.setSpeed(0);
                    d.setError("Network disconnected");
                });
    }

    /** Deletes an incomplete (partial) download file from disk. */
    private void deletePartialFile(DownloadItem item) {
        if (item.getStatus() == DownloadItem.Status.COMPLETED) return;
        java.io.File f = new java.io.File(item.getSaveDirectory(), item.getFileName());
        if (f.exists()) {
            boolean deleted = f.delete();
            if (!deleted) System.err.println("[DM] Could not delete partial: " + f.getAbsolutePath());
        }
    }

    private long parseSizeFromName(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d+)\\s*(KB|MB|GB)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name);
        if (m.find()) {
            long val = Long.parseLong(m.group(1));
            return switch (m.group(2).toUpperCase()) {
                case "KB" -> val * 1024;
                case "MB" -> val * 1024 * 1024;
                case "GB" -> val * 1024L * 1024 * 1024;
                default   -> 0;
            };
        }
        return 0;
    }

    private String validateUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "URL cannot be empty";
        url = url.trim().replace(" ", "%20");
        try {
            java.net.URI uri = new java.net.URI(url);
            java.net.URL u   = uri.toURL();
            String protocol  = u.getProtocol().toLowerCase();
            if (!protocol.equals("http") && !protocol.equals("https") && !protocol.equals("ftp"))
                return "Only http, https, and ftp protocols are supported";
            if (u.getHost() == null || u.getHost().isEmpty()) return "Invalid URL: Missing host";
        } catch (Exception e) { return "Invalid URL format"; }
        return null;
    }

    private String extractFileName(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                String name = URLDecoder.decode(path.substring(lastSlash + 1), StandardCharsets.UTF_8);
                if (!name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return "download";
    }

    public static class AddResult {
        public final int added;
        public final List<String> errors;
        public AddResult(int added, List<String> errors) { this.added = added; this.errors = errors; }
    }
}
