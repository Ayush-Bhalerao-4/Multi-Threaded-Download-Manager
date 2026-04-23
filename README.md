# Multi-Threaded Download Manager 

A professional multi-threaded download manager with chunk-based parallel downloading simulation.

## Features
- **Multi-URL Input**: Add multiple URLs (one per line or comma-separated)
- **Paste from Clipboard**: Quick paste button for URLs
- **Individual Controls**: Pause, Resume, Retry, Cancel, Copy URL, Remove per download
- **Bulk Controls**: Pause All, Resume All, Clear Done, Clear All
- **Network Detection**: Auto-pauses all downloads when network disconnects
- **Resume Support**: Downloads resume from interruption point (not from zero)
- **Progress Tracking**: Real-time speed, ETA, percentage, and progress bar
- **Chunk Display**: Shows number of parallel chunks per download
- **Dark Professional UI**: Styled with custom CSS

## Requirements
- Java 17+
- Maven 3.8+

## How to Run

```bash
mvn javafx:run
```

Or compile and run:
```bash
mvn clean package
java --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls,javafx.fxml -jar target/download-manager-pro-2.0.jar
```

## Project Structure
```
src/main/java/com/downloadmanager/
├── App.java                 # Main JavaFX application & UI layout
├── DownloadItem.java        # Download data model with JavaFX properties
├── DownloadManager.java     # Core logic: add, pause, resume, retry, cancel
├── DownloadSimulator.java   # Simulates download progress
├── DownloadCardFactory.java # Builds UI cards for each download
├── FormatUtils.java         # Byte/speed/ETA formatting utilities
└── NetworkMonitor.java      # Monitors network connectivity
src/main/resources/css/
└── style.css                # Dark theme stylesheet
```
