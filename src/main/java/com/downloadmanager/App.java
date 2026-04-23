package com.downloadmanager;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.util.List;

public class App extends Application {

    private NetworkMonitor networkMonitor;
    private DownloadManager downloadManager;

    @Override
    public void start(Stage stage) {
        networkMonitor  = new NetworkMonitor();
        downloadManager = new DownloadManager(networkMonitor);
        networkMonitor.start();

        VBox root = new VBox();
        root.getStyleClass().add("root-container");

        // ========== HEADER ==========
        HBox header = new HBox(10);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 20, 14, 20));

        Label title = new Label("\u2B07  Download Manager");
        title.getStyleClass().add("header-title");
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);

        Label netLabel = new Label();
        netLabel.getStyleClass().add("net-label");
        netLabel.textProperty().bind(Bindings.when(networkMonitor.onlineProperty())
                .then("\uD83D\uDFE2 Online").otherwise("\uD83D\uDD34 Offline"));
        networkMonitor.onlineProperty().addListener((obs, old, val) -> {
            netLabel.getStyleClass().removeAll("net-online", "net-offline");
            netLabel.getStyleClass().add(val ? "net-online" : "net-offline");
        });
        netLabel.getStyleClass().add(networkMonitor.isOnline() ? "net-online" : "net-offline");

        // ── Bandwidth button ──
        Button bandwidthBtn = new Button("\uD83D\uDCF6 Bandwidth");
        bandwidthBtn.getStyleClass().add("btn-outline");
        bandwidthBtn.setOnAction(e -> showBandwidthDialog(stage));

        // ── History button ──
        Button historyBtn = new Button("\uD83D\uDCDC History");
        historyBtn.getStyleClass().add("btn-outline");
        historyBtn.setOnAction(e -> showHistoryWindow(stage));

        header.getChildren().addAll(title, bandwidthBtn, historyBtn, netLabel);

        // ========== SAVE DIRECTORY ==========
        HBox saveDirSection = new HBox(8);
        saveDirSection.getStyleClass().add("save-dir-section");
        saveDirSection.setAlignment(Pos.CENTER_LEFT);
        saveDirSection.setPadding(new Insets(10, 20, 10, 20));

        Label saveDirIcon = new Label("\uD83D\uDCC1 SAVE TO:");
        saveDirIcon.getStyleClass().add("section-title");

        Label saveDirLabel = new Label();
        saveDirLabel.textProperty().bind(downloadManager.saveDirectoryProperty());
        saveDirLabel.getStyleClass().add("save-dir-path");
        HBox.setHgrow(saveDirLabel, Priority.ALWAYS);
        saveDirLabel.setMaxWidth(Double.MAX_VALUE);

        Button changeDirBtn = new Button("\uD83D\uDCC2 Browse...");
        changeDirBtn.getStyleClass().add("btn-outline");
        changeDirBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose Download Directory");
            File current = new File(downloadManager.getSaveDirectory());
            if (current.isDirectory()) chooser.setInitialDirectory(current);
            File selected = chooser.showDialog(stage);
            if (selected != null) downloadManager.setSaveDirectory(selected.getAbsolutePath());
        });

        saveDirSection.getChildren().addAll(saveDirIcon, saveDirLabel, changeDirBtn);

        // ========== URL INPUT ==========
        VBox inputSection = new VBox(8);
        inputSection.getStyleClass().add("input-section");
        inputSection.setPadding(new Insets(16, 20, 16, 20));

        Label addLabel = new Label("\uD83D\uDD17 ADD DOWNLOADS");
        addLabel.getStyleClass().add("section-title");

        TextArea urlArea = new TextArea();
        urlArea.setPromptText("Enter download URLs (one per line or comma-separated)\nhttps://example.com/file1.mp4\nhttps://example.com/file2.tar.gz");
        urlArea.setPrefRowCount(3);
        urlArea.getStyleClass().add("url-textarea");

        HBox inputBtns = new HBox(8);
        inputBtns.setAlignment(Pos.CENTER_LEFT);

        Button addBtn = new Button("\u2795 Add Downloads");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> {
            String text = urlArea.getText();
            if (text == null || text.trim().isEmpty()) { showAlert("Please enter at least one URL"); return; }
            DownloadManager.AddResult result = downloadManager.addDownloads(text);
            if (result.added > 0) {
                urlArea.clear();
                showInfo("Added " + result.added + " download" + (result.added > 1 ? "s" : "")
                        + " \u2192 " + downloadManager.getSaveDirectory());
            }
            result.errors.forEach(this::showAlert);
        });

        Button pasteBtn = new Button("\uD83D\uDCCB Paste from Clipboard");
        pasteBtn.getStyleClass().add("btn-outline");
        pasteBtn.setOnAction(e -> {
            String clip = Clipboard.getSystemClipboard().getString();
            if (clip != null && !clip.isEmpty()) {
                String existing = urlArea.getText();
                urlArea.setText((existing != null && !existing.isEmpty()) ? existing + "\n" + clip : clip);
            }
        });

        Label hint = new Label("Ctrl+Enter to add");
        hint.getStyleClass().add("hint-label");
        Region hintSpacer = new Region();
        HBox.setHgrow(hintSpacer, Priority.ALWAYS);

        inputBtns.getChildren().addAll(addBtn, pasteBtn, hintSpacer, hint);

        urlArea.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && e.isControlDown()) {
                addBtn.fire(); e.consume();
            }
        });

        inputSection.getChildren().addAll(addLabel, urlArea, inputBtns);

        // ========== TOOLBAR ==========
        HBox toolbar = new HBox(8);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(10, 20, 10, 20));

        Label statsLabel = new Label("Total: 0");
        statsLabel.getStyleClass().add("stats-label");
        HBox.setHgrow(statsLabel, Priority.ALWAYS);
        statsLabel.setMaxWidth(Double.MAX_VALUE);

        downloadManager.getDownloads().addListener((ListChangeListener<DownloadItem>) c -> updateStatsLabel(statsLabel));

        Button pauseAllBtn = new Button("\u23F8 Pause All");
        pauseAllBtn.getStyleClass().add("btn-outline");
        pauseAllBtn.setOnAction(e -> downloadManager.pauseAll());

        Button resumeAllBtn = new Button("\u25B6 Resume All");
        resumeAllBtn.getStyleClass().add("btn-outline");
        resumeAllBtn.setOnAction(e -> downloadManager.resumeAll());

        Button clearDoneBtn = new Button("\uD83D\uDDD1 Clear Done");
        clearDoneBtn.getStyleClass().add("btn-outline");
        clearDoneBtn.setOnAction(e -> downloadManager.clearCompleted());

        Button clearAllBtn = new Button("\u2715 Clear All");
        clearAllBtn.getStyleClass().add("btn-danger");
        clearAllBtn.setOnAction(e -> downloadManager.clearAll());

        toolbar.getChildren().addAll(statsLabel, pauseAllBtn, resumeAllBtn, clearDoneBtn, clearAllBtn);

        // ========== DOWNLOAD LIST ==========
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("download-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox listContainer = new VBox(10);
        listContainer.setPadding(new Insets(16, 20, 16, 20));
        listContainer.getStyleClass().add("list-container");

        Label emptyLabel = new Label("\u2B07  No downloads yet\nAdd URLs above to start downloading");
        emptyLabel.getStyleClass().add("empty-label");
        emptyLabel.setAlignment(Pos.CENTER);
        emptyLabel.setMaxWidth(Double.MAX_VALUE);
        emptyLabel.setMaxHeight(Double.MAX_VALUE);

        DownloadCardFactory.Actions cardActions = new DownloadCardFactory.Actions() {
            public void pause(DownloadItem item)  { downloadManager.pause(item); updateStatsLabel(statsLabel); }
            public void resume(DownloadItem item) {
                if (!networkMonitor.isOnline()) { showAlert("No network connection \u2014 cannot resume"); return; }
                downloadManager.resume(item); updateStatsLabel(statsLabel);
            }
            public void cancel(DownloadItem item) { downloadManager.cancel(item); updateStatsLabel(statsLabel); }
            public void retry(DownloadItem item)  {
                if (!networkMonitor.isOnline()) { showAlert("No network connection \u2014 cannot retry"); return; }
                downloadManager.retry(item); updateStatsLabel(statsLabel);
            }
            public void remove(DownloadItem item) { downloadManager.remove(item); updateStatsLabel(statsLabel); }
        };

        downloadManager.getDownloads().addListener((ListChangeListener<DownloadItem>) c -> {
            listContainer.getChildren().clear();
            if (downloadManager.getDownloads().isEmpty()) {
                listContainer.getChildren().add(emptyLabel);
            } else {
                for (DownloadItem item : downloadManager.getDownloads())
                    listContainer.getChildren().add(DownloadCardFactory.createCard(item, cardActions));
            }
            updateStatsLabel(statsLabel);
        });

        listContainer.getChildren().add(emptyLabel);
        scrollPane.setContent(listContainer);

        // ========== FOOTER ==========
        HBox footer = new HBox();
        footer.getStyleClass().add("footer");
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(8, 20, 8, 20));
        Label footerLabel = new Label("Download Manager Pro v3.0  \u2014  Bandwidth throttling \u00B7 History \u00B7 Blocked types \u00B7 Chunk-based parallel downloading");
        footerLabel.getStyleClass().add("footer-label");
        footer.getChildren().add(footerLabel);

        root.getChildren().addAll(header, saveDirSection, inputSection, toolbar, scrollPane, footer);

        Scene scene = new Scene(root, 920, 720);
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

        stage.setTitle("Download Manager Pro v3.0");
        stage.setScene(scene);
        stage.setMinWidth(700);
        stage.setMinHeight(500);
        stage.show();
    }

    // ─── Bandwidth Dialog ─────────────────────────────────────────────────────

    private void showBandwidthDialog(Stage owner) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Bandwidth Limit");
        dialog.setResizable(false);

        VBox root = new VBox(14);
        root.setPadding(new Insets(22, 26, 22, 26));
        root.getStyleClass().add("root-container");

        Label heading = new Label("\uD83D\uDCF6  Set Bandwidth Limit");
        heading.getStyleClass().add("section-title");
        heading.setStyle("-fx-font-size: 14px; -fx-text-fill: #c8cdd7;");

        Label desc = new Label("Limits the total download speed across all active downloads.\nSet to 0 for unlimited.");
        desc.getStyleClass().add("hint-label");
        desc.setWrapText(true);

        HBox inputRow = new HBox(8);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        // Show current value
        long currentLimit = downloadManager.getBandwidthLimit();
        String currentDisplay = "0";
        String currentUnit = "MB";
        if (currentLimit > 0) {
            if (currentLimit >= 1024 * 1024) {
                currentDisplay = String.valueOf(currentLimit / (1024 * 1024));
                currentUnit = "MB";
            } else {
                currentDisplay = String.valueOf(currentLimit / 1024);
                currentUnit = "KB";
            }
        }

        TextField valueField = new TextField(currentDisplay);
        valueField.getStyleClass().add("url-textarea");
        valueField.setStyle("-fx-pref-width: 100px; -fx-font-size: 13px; -fx-padding: 6;");
        valueField.setPromptText("e.g. 10");

        ComboBox<String> unitBox = new ComboBox<>();
        unitBox.getItems().addAll("KB/s", "MB/s");
        unitBox.setValue(currentUnit.equals("KB") ? "KB/s" : "MB/s");
        unitBox.setStyle("-fx-background-color: #1e2130; -fx-text-fill: #c8cdd7; -fx-font-size: 12px;");

        Label perSec = new Label("per second");
        perSec.getStyleClass().add("hint-label");

        inputRow.getChildren().addAll(valueField, unitBox, perSec);

        Label status = new Label();
        status.getStyleClass().add("hint-label");
        status.setStyle("-fx-text-fill: #66bb6a;");
        if (currentLimit > 0)
            status.setText("Current: " + FormatUtils.formatSpeed(currentLimit));
        else
            status.setText("Current: Unlimited");

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-outline");
        cancelBtn.setOnAction(e -> dialog.close());

        Button applyBtn = new Button("Apply");
        applyBtn.getStyleClass().add("btn-primary");
        applyBtn.setDefaultButton(true);
        applyBtn.setOnAction(e -> {
            String txt = valueField.getText().trim();
            long val;
            try {
                val = Long.parseLong(txt);
                if (val < 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                status.setStyle("-fx-text-fill: #ef5350;");
                status.setText("Please enter a valid non-negative number.");
                return;
            }
            long bytes = val;
            if (bytes > 0) {
                String unit = unitBox.getValue();
                bytes = unit.startsWith("MB") ? val * 1024 * 1024 : val * 1024;
            }
            downloadManager.setBandwidthLimit(bytes);
            status.setStyle("-fx-text-fill: #66bb6a;");
            status.setText(bytes == 0 ? "Set to: Unlimited" : "Set to: " + FormatUtils.formatSpeed(bytes));
        });

        Button unlimitedBtn = new Button("Remove Limit");
        unlimitedBtn.getStyleClass().add("btn-warning");
        unlimitedBtn.setOnAction(e -> {
            downloadManager.setBandwidthLimit(0);
            valueField.setText("0");
            status.setStyle("-fx-text-fill: #66bb6a;");
            status.setText("Set to: Unlimited");
        });

        btnRow.getChildren().addAll(unlimitedBtn, cancelBtn, applyBtn);

        root.getChildren().addAll(heading, desc, inputRow, status, btnRow);

        Scene scene = new Scene(root, 360, 240);
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    // ─── History Window ───────────────────────────────────────────────────────

    private void showHistoryWindow(Stage owner) {
        Stage win = new Stage();
        win.initOwner(owner);
        win.initModality(Modality.APPLICATION_MODAL);
        win.setTitle("Download History");

        VBox root = new VBox(0);
        root.getStyleClass().add("root-container");

        // Toolbar
        HBox toolbar = new HBox(8);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(10, 16, 10, 16));

        Label heading = new Label("\uD83D\uDCDC  Download History");
        heading.setStyle("-fx-text-fill: #c8cdd7; -fx-font-size: 14px; -fx-font-weight: 700;");
        HBox.setHgrow(heading, Priority.ALWAYS);
        heading.setMaxWidth(Double.MAX_VALUE);

        Button clearAllHistBtn = new Button("\uD83D\uDDD1 Clear All");
        clearAllHistBtn.getStyleClass().add("btn-danger");

        toolbar.getChildren().addAll(heading, clearAllHistBtn);

        // List container
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("download-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox listBox = new VBox(8);
        listBox.setPadding(new Insets(14, 16, 14, 16));
        listBox.getStyleClass().add("list-container");

        // Load and render
        Runnable reload = () -> {
            listBox.getChildren().clear();
            List<DownloadHistory> entries = downloadManager.getHistoryDb().getAll();
            if (entries.isEmpty()) {
                Label empty = new Label("No download history yet.");
                empty.getStyleClass().add("empty-label");
                empty.setMaxWidth(Double.MAX_VALUE);
                listBox.getChildren().add(empty);
                return;
            }
            for (DownloadHistory h : entries) {
                listBox.getChildren().add(buildHistoryCard(h, listBox, win));
            }
        };

        clearAllHistBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Clear all download history?", ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES) {
                    downloadManager.getHistoryDb().deleteAll();
                    reload.run();
                }
            });
        });

        reload.run();
        scroll.setContent(listBox);
        root.getChildren().addAll(toolbar, scroll);

        Scene scene = new Scene(root, 700, 500);
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        win.setScene(scene);
        win.show();
    }

    /** Builds a single history entry card. */
    private VBox buildHistoryCard(DownloadHistory h, VBox listBox, Stage win) {
        VBox card = new VBox(6);
        card.getStyleClass().add("download-card");
        card.setPadding(new Insets(12, 14, 12, 14));

        // Row 1: filename + status badge
        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label fileLabel = new Label(h.getFileName());
        fileLabel.getStyleClass().add("file-name");
        HBox.setHgrow(fileLabel, Priority.ALWAYS);
        fileLabel.setMaxWidth(Double.MAX_VALUE);

        Label statusBadge = new Label(h.getStatus());
        statusBadge.getStyleClass().addAll("status-badge", "status-" + h.getStatus().toLowerCase());

        topRow.getChildren().addAll(fileLabel, statusBadge);

        // Row 2: URL
        Label urlLabel = new Label(h.getUrl());
        urlLabel.getStyleClass().add("url-label");

        // Row 3: meta info
        HBox metaRow = new HBox(16);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        Label sizeLabel = new Label(FormatUtils.formatBytes(h.getFileSize()));
        sizeLabel.getStyleClass().add("stat-label");

        Label dateLabel = new Label("\uD83D\uDD52 " + h.getFormattedDate());
        dateLabel.getStyleClass().add("stat-label");

        Label pathLabel = new Label("\uD83D\uDCC2 " + h.getSaveDirectory());
        pathLabel.getStyleClass().add("save-dir-label");
        HBox.setHgrow(pathLabel, Priority.ALWAYS);
        pathLabel.setMaxWidth(Double.MAX_VALUE);

        metaRow.getChildren().addAll(sizeLabel, dateLabel, pathLabel);

        // Row 4: action buttons
        HBox btnRow = new HBox(8);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        File file = new File(h.getFullPath());
        boolean fileExists = file.exists();

        // Open / Install button (only for completed + file exists)
        if ("Completed".equals(h.getStatus()) && fileExists) {
            Button openBtn = new Button("\u25B6 Open / Install");
            openBtn.getStyleClass().add("btn-success");
            openBtn.setOnAction(e -> {
                try {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file);
                } catch (Exception ex) {
                    showAlert("Cannot open file: " + ex.getMessage());
                }
            });
            btnRow.getChildren().add(openBtn);
        }

        // File-deleted badge + re-download prompt
        if ("Completed".equals(h.getStatus()) && !fileExists) {
            Label deletedBadge = new Label("\u26A0 File deleted from device");
            deletedBadge.setStyle("-fx-text-fill: #ff9800; -fx-font-size: 11px; -fx-font-weight: 600;");

            Button redownloadBtn = new Button("\u21BB Re-download");
            redownloadBtn.getStyleClass().add("btn-info");
            redownloadBtn.setOnAction(e -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Re-download \"" + h.getFileName() + "\"?", ButtonType.YES, ButtonType.NO);
                confirm.setHeaderText(null);
                confirm.showAndWait().ifPresent(bt -> {
                    if (bt == ButtonType.YES) {
                        downloadManager.getHistoryDb().delete(h.getId());
                        downloadManager.addDownloads(h.getUrl());
                        win.close();
                    }
                });
            });

            Button removeDeletedBtn = new Button("\uD83D\uDDD1 Remove from History");
            removeDeletedBtn.getStyleClass().add("btn-remove");
            removeDeletedBtn.setOnAction(e -> {
                downloadManager.getHistoryDb().delete(h.getId());
                listBox.getChildren().removeIf(n -> n == card);
            });

            btnRow.getChildren().addAll(deletedBadge, redownloadBtn, removeDeletedBtn);
        }

        // Delete single history entry (always available)
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button deleteBtn = new Button("\uD83D\uDDD1 Delete");
        deleteBtn.getStyleClass().add("btn-remove");
        deleteBtn.setOnAction(e -> {
            downloadManager.getHistoryDb().delete(h.getId());
            listBox.getChildren().removeIf(n -> n == card);
        });

        btnRow.getChildren().addAll(spacer, deleteBtn);

        card.getChildren().addAll(topRow, urlLabel, metaRow, btnRow);
        return card;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void updateStatsLabel(Label label) {
        var list = downloadManager.getDownloads();
        long total   = list.size();
        long dl      = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.DOWNLOADING).count();
        long paused  = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.PAUSED).count();
        long done    = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.COMPLETED).count();
        long err     = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.ERROR).count();
        label.setText(String.format("Total: %d  |  Downloading: %d  |  Paused: %d  |  Completed: %d%s",
                total, dl, paused, done, err > 0 ? "  |  Errors: " + err : ""));
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        downloadManager.shutdown();
        networkMonitor.stop();
    }

    public static void main(String[] args) { launch(args); }
}
