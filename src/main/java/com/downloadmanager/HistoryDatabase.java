package com.downloadmanager;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight SQLite-backed store for download history.
 *
 * The database file is placed in:
 *   ~/Downloads/.dm_pro_history.db
 *
 * Schema:
 *   history(id INTEGER PK, file_name TEXT, url TEXT, file_size INTEGER,
 *           save_directory TEXT, status TEXT, completed_at INTEGER)
 *
 * Only SQLite's built-in JDBC (org.xerial:sqlite-jdbc) is needed — already a
 * standard dependency for desktop Java apps.  We use plain JDBC so no ORM is
 * required.
 */
public class HistoryDatabase {

    private static final String DB_NAME   = ".dm_pro_history.db";
    private static final String TABLE_DDL =
            "CREATE TABLE IF NOT EXISTS history (" +
            "  id             INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  file_name      TEXT    NOT NULL," +
            "  url            TEXT    NOT NULL," +
            "  file_size      INTEGER NOT NULL DEFAULT 0," +
            "  save_directory TEXT    NOT NULL," +
            "  status         TEXT    NOT NULL," +
            "  completed_at   INTEGER NOT NULL" +
            ")";

    private final String dbPath;

    public HistoryDatabase() {
        // Store alongside the user's default downloads folder so it persists
        String home = System.getProperty("user.home");
        dbPath = home + File.separator + "Downloads" + File.separator + DB_NAME;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /** Creates table if it doesn't exist. Safe to call on every start-up. */
    public void init() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(TABLE_DDL);
        } catch (Exception e) {
            System.err.println("[HistoryDB] init failed: " + e.getMessage());
        }
    }

    // ─── Write ───────────────────────────────────────────────────────────────

    /**
     * Records a finished DownloadItem into history.
     * Called whenever a download reaches COMPLETED, CANCELLED, or ERROR state.
     */
    public void insert(DownloadItem item) {
        String sql = "INSERT INTO history(file_name, url, file_size, save_directory, status, completed_at) " +
                     "VALUES(?,?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, item.getFileName());
            ps.setString(2, item.getUrl());
            ps.setLong(3, item.getFileSize());
            ps.setString(4, item.getSaveDirectory());
            ps.setString(5, item.getStatus().toString());
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[HistoryDB] insert failed: " + e.getMessage());
        }
    }

    /** Deletes a single history row by id. */
    public void delete(int id) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM history WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[HistoryDB] delete failed: " + e.getMessage());
        }
    }

    /** Wipes all history. */
    public void deleteAll() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM history");
        } catch (Exception e) {
            System.err.println("[HistoryDB] deleteAll failed: " + e.getMessage());
        }
    }

    // ─── Read ────────────────────────────────────────────────────────────────

    /** Returns all history rows, newest first. */
    public List<DownloadHistory> getAll() {
        List<DownloadHistory> list = new ArrayList<>();
        String sql = "SELECT id,file_name,url,file_size,save_directory,status,completed_at " +
                     "FROM history ORDER BY completed_at DESC";
        try (Connection c = connect();
             Statement s  = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new DownloadHistory(
                        rs.getInt("id"),
                        rs.getString("file_name"),
                        rs.getString("url"),
                        rs.getLong("file_size"),
                        rs.getString("save_directory"),
                        rs.getString("status"),
                        rs.getLong("completed_at")
                ));
            }
        } catch (Exception e) {
            System.err.println("[HistoryDB] getAll failed: " + e.getMessage());
        }
        return list;
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private Connection connect() throws SQLException {
        // Ensure the parent directory exists (e.g., ~/Downloads)
        new File(dbPath).getParentFile().mkdirs();
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}
