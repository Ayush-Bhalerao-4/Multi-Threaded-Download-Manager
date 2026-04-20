package com.downloadmanager;

import javafx.beans.property.*;

public class DownloadItem {

    public enum Status {
        QUEUED, DOWNLOADING, PAUSED, INTERRUPTED, COMPLETED, ERROR, CANCELLED;

        @Override
        public String toString() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    private final String id;
    private final StringProperty url;
    private final StringProperty fileName;
    private final LongProperty fileSize;
    private final LongProperty downloaded;
    private final ObjectProperty<Status> status;
    private final DoubleProperty speed;
    private final IntegerProperty chunks;
    private final StringProperty error;
    private final long addedAt;
    private final IntegerProperty retryCount;
    private final StringProperty saveDirectory;

    public DownloadItem(String id, String url, String fileName, long fileSize, int chunks, String saveDirectory) {
        this.id = id;
        this.url = new SimpleStringProperty(url);
        this.fileName = new SimpleStringProperty(fileName);
        this.fileSize = new SimpleLongProperty(fileSize);
        this.downloaded = new SimpleLongProperty(0);
        this.status = new SimpleObjectProperty<>(Status.DOWNLOADING);
        this.speed = new SimpleDoubleProperty(0);
        this.chunks = new SimpleIntegerProperty(chunks);
        this.error = new SimpleStringProperty(null);
        this.addedAt = System.currentTimeMillis();
        this.retryCount = new SimpleIntegerProperty(0);
        this.saveDirectory = new SimpleStringProperty(saveDirectory);
    }

    public String getId() { return id; }

    public StringProperty urlProperty() { return url; }
    public String getUrl() { return url.get(); }

    public StringProperty fileNameProperty() { return fileName; }
    public String getFileName() { return fileName.get(); }

    public LongProperty fileSizeProperty() { return fileSize; }
    public long getFileSize() { return fileSize.get(); }

    public LongProperty downloadedProperty() { return downloaded; }
    public long getDownloaded() { return downloaded.get(); }
    public void setDownloaded(long val) { downloaded.set(val); }

    public ObjectProperty<Status> statusProperty() { return status; }
    public Status getStatus() { return status.get(); }
    public void setStatus(Status val) { status.set(val); }

    public DoubleProperty speedProperty() { return speed; }
    public double getSpeed() { return speed.get(); }
    public void setSpeed(double val) { speed.set(val); }

    public IntegerProperty chunksProperty() { return chunks; }
    public int getChunks() { return chunks.get(); }

    public StringProperty errorProperty() { return error; }
    public String getError() { return error.get(); }
    public void setError(String val) { error.set(val); }

    public long getAddedAt() { return addedAt; }

    public IntegerProperty retryCountProperty() { return retryCount; }
    public int getRetryCount() { return retryCount.get(); }
    public void setRetryCount(int val) { retryCount.set(val); }

    public StringProperty saveDirectoryProperty() { return saveDirectory; }
    public String getSaveDirectory() { return saveDirectory.get(); }
    public void setSaveDirectory(String val) { saveDirectory.set(val); }

    public double getProgress() {
        return fileSize.get() > 0 ? (double) downloaded.get() / fileSize.get() : 0;
    }
}
