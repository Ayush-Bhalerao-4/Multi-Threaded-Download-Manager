package com.downloadmanager;

public class FormatUtils {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        int i = (int) (Math.log(bytes) / Math.log(1024));
        i = Math.min(i, UNITS.length - 1);
        double val = bytes / Math.pow(1024, i);
        return (i == 0 ? String.valueOf((long) val) : String.format("%.1f", val)) + " " + UNITS[i];
    }

    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec <= 0) return "—";
        return formatBytes((long) bytesPerSec) + "/s";
    }

    public static String formatEta(long remaining, double speed) {
        if (speed <= 0 || remaining <= 0) return "—";
        long seconds = (long) Math.ceil(remaining / speed);
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "h " + m + "m";
    }

    public static String formatProgress(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }
}
