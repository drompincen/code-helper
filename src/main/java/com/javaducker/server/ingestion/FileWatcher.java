package com.javaducker.server.ingestion;

import com.javaducker.server.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component("javaDuckerFileWatcher")
public class FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);
    private static final long DEBOUNCE_MS = 500;
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", ".git", "target", "build", "__pycache__", ".idea", "temp");

    private final UploadService uploadService;
    private volatile WatchService watchService;
    private volatile Path watchedDirectory;
    private volatile boolean watching;
    private Thread watchThread;
    private Set<String> allowedExtensions;
    private final Map<Path, Long> lastModified = new ConcurrentHashMap<>();

    public FileWatcher(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    public void startWatching(Path directory, Set<String> extensions) throws IOException {
        stopWatching();
        this.allowedExtensions = extensions;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerRecursive(directory);
        this.watchedDirectory = directory;
        this.watching = true;

        watchThread = new Thread(this::watchLoop, "file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("Started watching directory: {} for extensions: {}", directory, extensions);
    }

    public void stopWatching() {
        watching = false;
        if (watchService != null) {
            try { watchService.close(); } catch (IOException e) { log.warn("Error closing WatchService", e); }
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        watchedDirectory = null;
        lastModified.clear();
        log.info("Stopped watching");
    }

    public boolean isWatching() { return watching; }

    public Path getWatchedDirectory() { return watchedDirectory; }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (EXCLUDED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    dir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                } catch (IOException e) {
                    log.warn("Failed to register: {}", dir, e);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop() {
        while (watching) {
            try {
                WatchKey key = watchService.take();
                Path dir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path changed = dir.resolve((Path) event.context());
                    handleEvent(changed);
                }
                key.reset();
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        watching = false;
    }

    private void handleEvent(Path path) {
        try {
            if (Files.isDirectory(path)) {
                if (!EXCLUDED_DIRS.contains(path.getFileName().toString())) {
                    registerRecursive(path);
                }
                return;
            }
            String fileName = path.getFileName().toString();
            if (!hasAllowedExtension(fileName)) return;
            for (Path part : path) {
                if (EXCLUDED_DIRS.contains(part.toString())) return;
            }

            long now = System.currentTimeMillis();
            Long prev = lastModified.put(path, now);
            if (prev != null && (now - prev) < DEBOUNCE_MS) return;

            byte[] content = Files.readAllBytes(path);
            String mediaType = Files.probeContentType(path);
            if (mediaType == null) mediaType = "application/octet-stream";

            String artifactId = uploadService.upload(
                    fileName, path.toAbsolutePath().toString(), mediaType, content.length, content);
            log.info("Auto-indexed file: {} -> {}", path, artifactId);
        } catch (Exception e) {
            log.error("Error processing file event for {}: {}", path, e.getMessage());
        }
    }

    private boolean hasAllowedExtension(String fileName) {
        if (allowedExtensions == null || allowedExtensions.isEmpty()) return true;
        return allowedExtensions.stream().anyMatch(fileName::endsWith);
    }
}
