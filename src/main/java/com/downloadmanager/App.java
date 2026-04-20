package com.downloadmanager;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import java.io.File;

public class App extends Application {

    private NetworkMonitor networkMonitor;
    private DownloadManager downloadManager;

    @Override
    public void start(Stage stage) {
        networkMonitor = new NetworkMonitor();
        downloadManager = new DownloadManager(networkMonitor);
        networkMonitor.start();

        VBox root = new VBox();
        root.getStyleClass().add("root-container");

        // ========== HEADER ==========
        HBox header = new HBox(10);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 20, 14, 20));

        Label title = new Label("\u2B07  Download Manager Pro");
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

        header.getChildren().addAll(title, netLabel);

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
            if (selected != null) {
                downloadManager.setSaveDirectory(selected.getAbsolutePath());
            }
        });

        saveDirSection.getChildren().addAll(saveDirIcon, saveDirLabel, changeDirBtn);

        // ========== URL INPUT ==========
        VBox inputSection = new VBox(8);
        inputSection.getStyleClass().add("input-section");
        inputSection.setPadding(new Insets(16, 20, 16, 20));

        Label addLabel = new Label("\uD83D\uDD17 ADD DOWNLOADS");
        addLabel.getStyleClass().add("section-title");

        TextArea urlArea = new TextArea();
        urlArea.setPromptText("Enter download URLs (one per line or comma-separated)\nhttps://example.com/file1.zip\nhttps://example.com/file2.tar.gz");
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
            public void pause(DownloadItem item) { downloadManager.pause(item); updateStatsLabel(statsLabel); }
            public void resume(DownloadItem item) {
                if (!networkMonitor.isOnline()) { showAlert("No network connection \u2014 cannot resume"); return; }
                downloadManager.resume(item); updateStatsLabel(statsLabel);
            }
            public void cancel(DownloadItem item) { downloadManager.cancel(item); updateStatsLabel(statsLabel); }
            public void retry(DownloadItem item) {
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
                for (DownloadItem item : downloadManager.getDownloads()) {
                    listContainer.getChildren().add(DownloadCardFactory.createCard(item, cardActions));
                }
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
        Label footerLabel = new Label("Multi-threaded Download Manager v2.0 \u2014 Chunk-based parallel downloading with SJF scheduling");
        footerLabel.getStyleClass().add("footer-label");
        footer.getChildren().add(footerLabel);

        root.getChildren().addAll(header, saveDirSection, inputSection, toolbar, scrollPane, footer);

        Scene scene = new Scene(root, 900, 700);
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

        stage.setTitle("Download Manager Pro v2.0");
        stage.setScene(scene);
        stage.setMinWidth(700);
        stage.setMinHeight(500);
        stage.show();
    }

    private void updateStatsLabel(Label label) {
        var list = downloadManager.getDownloads();
        long total = list.size();
        long dl = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.DOWNLOADING).count();
        long paused = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.PAUSED).count();
        long done = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.COMPLETED).count();
        long err = list.stream().filter(d -> d.getStatus() == DownloadItem.Status.ERROR).count();
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
