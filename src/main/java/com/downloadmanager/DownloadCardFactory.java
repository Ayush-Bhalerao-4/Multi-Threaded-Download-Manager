package com.downloadmanager;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

public class DownloadCardFactory {

    public interface Actions {
        void pause(DownloadItem item);
        void resume(DownloadItem item);
        void cancel(DownloadItem item);
        void retry(DownloadItem item);
        void remove(DownloadItem item);
    }

    public static VBox createCard(DownloadItem item, Actions actions) {
        VBox card = new VBox(6);
        card.getStyleClass().add("download-card");
        card.setPadding(new Insets(14, 16, 14, 16));

        // --- Row 1: fileName + status badge + chunks ---
        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label fileLabel = new Label();
        fileLabel.textProperty().bind(item.fileNameProperty());
        fileLabel.getStyleClass().add("file-name");
        HBox.setHgrow(fileLabel, Priority.ALWAYS);
        fileLabel.setMaxWidth(Double.MAX_VALUE);

        Label chunksLabel = new Label();
        chunksLabel.textProperty().bind(Bindings.concat(item.chunksProperty().asString(), " chunk",
                Bindings.when(item.chunksProperty().greaterThan(1)).then("s").otherwise("")));
        chunksLabel.getStyleClass().add("chunks-badge");

        Label statusLabel = new Label();
        statusLabel.textProperty().bind(Bindings.createStringBinding(
                () -> item.getStatus().toString(), item.statusProperty()));
        item.statusProperty().addListener((obs, old, val) -> {
            statusLabel.getStyleClass().removeAll("status-downloading", "status-paused",
                    "status-completed", "status-error", "status-cancelled", "status-queued", "status-interrupted");
            statusLabel.getStyleClass().add("status-" + val.name().toLowerCase());
        });
        statusLabel.getStyleClass().addAll("status-badge", "status-" + item.getStatus().name().toLowerCase());

        topRow.getChildren().addAll(fileLabel, chunksLabel, statusLabel);

        // --- Row 2: URL ---
        Label urlLabel = new Label();
        urlLabel.textProperty().bind(item.urlProperty());
        urlLabel.getStyleClass().add("url-label");

        // --- Row 2.5: Save location ---
        Label saveLabel = new Label();
        saveLabel.textProperty().bind(Bindings.concat("\uD83D\uDCC2 ", item.saveDirectoryProperty()));
        saveLabel.getStyleClass().add("save-dir-label");

        // --- Row 3: Progress bar ---
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.getStyleClass().add("download-progress");
        progressBar.progressProperty().bind(Bindings.createDoubleBinding(
                item::getProgress, item.downloadedProperty(), item.fileSizeProperty()));

        // --- Row 4: Stats ---
        HBox statsRow = new HBox(16);
        statsRow.setAlignment(Pos.CENTER_LEFT);

        Label sizeLabel = new Label();
        sizeLabel.textProperty().bind(Bindings.createStringBinding(
                () -> FormatUtils.formatBytes(item.getDownloaded()) + " / " + FormatUtils.formatBytes(item.getFileSize()),
                item.downloadedProperty(), item.fileSizeProperty()));
        sizeLabel.getStyleClass().add("stat-label");

        Label speedLabel = new Label();
        speedLabel.textProperty().bind(Bindings.createStringBinding(
                () -> FormatUtils.formatSpeed(item.getSpeed()), item.speedProperty()));
        speedLabel.getStyleClass().addAll("stat-label", "speed-label");

        Label etaLabel = new Label();
        etaLabel.textProperty().bind(Bindings.createStringBinding(
                () -> "ETA: " + FormatUtils.formatEta(item.getFileSize() - item.getDownloaded(), item.getSpeed()),
                item.downloadedProperty(), item.speedProperty()));
        etaLabel.getStyleClass().add("stat-label");

        Label percentLabel = new Label();
        percentLabel.textProperty().bind(Bindings.createStringBinding(
                () -> FormatUtils.formatProgress(item.getProgress()),
                item.downloadedProperty(), item.fileSizeProperty()));
        percentLabel.getStyleClass().addAll("stat-label", "percent-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statsRow.getChildren().addAll(sizeLabel, speedLabel, etaLabel, spacer, percentLabel);

        // --- Row 5: Action buttons ---
        HBox btnRow = new HBox(6);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        Button pauseBtn = new Button("\u23F8 Pause");
        pauseBtn.getStyleClass().add("btn-warning");
        pauseBtn.setOnAction(e -> actions.pause(item));

        Button resumeBtn = new Button("\u25B6 Resume");
        resumeBtn.getStyleClass().add("btn-success");
        resumeBtn.setOnAction(e -> actions.resume(item));

        Button retryBtn = new Button("\u21BB Retry");
        retryBtn.getStyleClass().add("btn-info");
        retryBtn.setOnAction(e -> actions.retry(item));

        Button cancelBtn = new Button("\u2715 Cancel");
        cancelBtn.getStyleClass().add("btn-danger");
        cancelBtn.setOnAction(e -> actions.cancel(item));

        Button copyBtn = new Button("\uD83D\uDCCB Copy URL");
        copyBtn.getStyleClass().add("btn-ghost");
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(item.getUrl());
            Clipboard.getSystemClipboard().setContent(cc);
        });

        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);

        Button removeBtn = new Button("\uD83D\uDDD1 Remove");
        removeBtn.getStyleClass().add("btn-remove");
        removeBtn.setOnAction(e -> actions.remove(item));

        pauseBtn.visibleProperty().bind(item.statusProperty().isEqualTo(DownloadItem.Status.DOWNLOADING));
        pauseBtn.managedProperty().bind(pauseBtn.visibleProperty());

        resumeBtn.visibleProperty().bind(item.statusProperty().isEqualTo(DownloadItem.Status.PAUSED)
                .or(item.statusProperty().isEqualTo(DownloadItem.Status.INTERRUPTED)));
        resumeBtn.managedProperty().bind(resumeBtn.visibleProperty());

        retryBtn.visibleProperty().bind(item.statusProperty().isEqualTo(DownloadItem.Status.ERROR)
                .or(item.statusProperty().isEqualTo(DownloadItem.Status.CANCELLED)));
        retryBtn.managedProperty().bind(retryBtn.visibleProperty());

        cancelBtn.visibleProperty().bind(item.statusProperty().isEqualTo(DownloadItem.Status.DOWNLOADING)
                .or(item.statusProperty().isEqualTo(DownloadItem.Status.PAUSED))
                .or(item.statusProperty().isEqualTo(DownloadItem.Status.INTERRUPTED))
                .or(item.statusProperty().isEqualTo(DownloadItem.Status.QUEUED)));
        cancelBtn.managedProperty().bind(cancelBtn.visibleProperty());

        btnRow.getChildren().addAll(pauseBtn, resumeBtn, retryBtn, cancelBtn, copyBtn, btnSpacer, removeBtn);

        card.getChildren().addAll(topRow, urlLabel, saveLabel, progressBar, statsRow, btnRow);
        return card;
    }
}
