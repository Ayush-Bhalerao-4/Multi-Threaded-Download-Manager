package com.downloadmanager;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DownloadManager {

    private final ObservableList<DownloadItem> downloads = FXCollections.observableArrayList();
    private final DownloadSimulator simulator = new DownloadSimulator();
    private final NetworkMonitor networkMonitor;
    private final Random random = new Random();
    private final StringProperty saveDirectory = new SimpleStringProperty(
            System.getProperty("user.home") + java.io.File.separator + "Downloads");

    public DownloadManager(NetworkMonitor networkMonitor) {
        this.networkMonitor = networkMonitor;
        networkMonitor.addListener(new NetworkMonitor.Listener() {
            @Override public void onOffline() { pauseAllForNetwork(); }
            @Override public void onOnline() {}
        });
    }

    public ObservableList<DownloadItem> getDownloads() { return downloads; }

    public StringProperty saveDirectoryProperty() { return saveDirectory; }
    public String getSaveDirectory() { return saveDirectory.get(); }
    public void setSaveDirectory(String dir) { saveDirectory.set(dir); }

    public AddResult addDownloads(String urlText) {
        String[] parts = urlText.split("[\\n,]+");
        List<String> errors = new ArrayList<>();
        int added = 0;

        for (String raw : parts) {
            String url = raw.trim();
            if (url.isEmpty()) continue;

            String err = validateUrl(url);
            if (err != null) { errors.add(url + ": " + err); continue; }

            String fileName = extractFileName(url);
            long fileSize = parseSizeFromName(fileName);
            if (fileSize <= 0) fileSize = random.nextInt(500_000_000) + 1_000_000;
            int chunks = fileSize < 1_000_000 ? 1 : fileSize < 10_000_000 ? 2 : fileSize < 100_000_000 ? 4 : 8;

            String id = UUID.randomUUID().toString().substring(0, 8);
            DownloadItem item = new DownloadItem(id, url, fileName, fileSize, chunks, saveDirectory.get());
            downloads.add(0, item);
            simulator.startSimulation(item);
            added++;
        }
        return new AddResult(added, errors);
    }

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
    }

    public void retry(DownloadItem item) {
        if (!networkMonitor.isOnline()) return;
        item.setRetryCount(item.getRetryCount() + 1);
        item.setError(null);
        item.setStatus(DownloadItem.Status.DOWNLOADING);
        simulator.startSimulation(item);
    }

    public void remove(DownloadItem item) {
        simulator.stopSimulation(item.getId());
        downloads.remove(item);
    }

    public void pauseAll() {
        downloads.stream()
            .filter(d -> d.getStatus() == DownloadItem.Status.DOWNLOADING)
            .forEach(this::pause);
    }

    public void resumeAll() {
        if (!networkMonitor.isOnline()) return;
        downloads.stream()
            .filter(d -> d.getStatus() == DownloadItem.Status.PAUSED || d.getStatus() == DownloadItem.Status.INTERRUPTED)
            .forEach(this::resume);
    }

    public void clearCompleted() {
        downloads.removeIf(d ->
            d.getStatus() == DownloadItem.Status.COMPLETED ||
            d.getStatus() == DownloadItem.Status.CANCELLED);
    }

    public void clearAll() { simulator.stopAll(); downloads.clear(); }
    public void shutdown() { simulator.stopAll(); }

    private void pauseAllForNetwork() {
        downloads.stream()
            .filter(d -> d.getStatus() == DownloadItem.Status.DOWNLOADING)
            .forEach(d -> {
                simulator.stopSimulation(d.getId());
                d.setStatus(DownloadItem.Status.INTERRUPTED);
                d.setSpeed(0);
                d.setError("Network disconnected");
            });
    }

    private long parseSizeFromName(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d+)\\s*(KB|MB|GB)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(name);
        if (m.find()) {
            long val = Long.parseLong(m.group(1));
            String unit = m.group(2).toUpperCase();
            return switch (unit) {
                case "KB" -> val * 1024;
                case "MB" -> val * 1024 * 1024;
                case "GB" -> val * 1024L * 1024 * 1024;
                default -> 0;
            };
        }
        return 0;
    }

    private String validateUrl(String url) {
        if (!url.matches("(?i)^(https?|ftp)://.+")) return "URL must start with http://, https://, or ftp://";
        try { new URI(url); } catch (Exception e) { return "Invalid URL format"; }
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
