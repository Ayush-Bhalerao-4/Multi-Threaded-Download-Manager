package com.downloadmanager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Represents a single completed/cancelled/error entry in the download history database.
 */
public class DownloadHistory {

    private final int    id;
    private final String fileName;
    private final String url;
    private final long   fileSize;
    private final String saveDirectory;
    private final String status;       // "Completed", "Cancelled", "Error"
    private final long   completedAt;  // epoch millis

    public DownloadHistory(int id, String fileName, String url,
                           long fileSize, String saveDirectory,
                           String status, long completedAt) {
        this.id            = id;
        this.fileName      = fileName;
        this.url           = url;
        this.fileSize      = fileSize;
        this.saveDirectory = saveDirectory;
        this.status        = status;
        this.completedAt   = completedAt;
    }

    public int    getId()           { return id; }
    public String getFileName()     { return fileName; }
    public String getUrl()          { return url; }
    public long   getFileSize()     { return fileSize; }
    public String getSaveDirectory(){ return saveDirectory; }
    public String getStatus()       { return status; }
    public long   getCompletedAt()  { return completedAt; }

    /** Full path where the file should reside on disk. */
    public String getFullPath() {
        return saveDirectory + java.io.File.separator + fileName;
    }

    /** Human-readable completion timestamp. */
    public String getFormattedDate() {
        LocalDateTime ldt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(completedAt), ZoneId.systemDefault());
        return ldt.format(DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm"));
    }
}
