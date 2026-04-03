package com.javaducker.server.mcp;

import com.javaducker.server.ingestion.FileWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class WatchTools {

    private static final Logger log = LoggerFactory.getLogger(WatchTools.class);

    private final FileWatcher fileWatcher;

    public WatchTools(FileWatcher fileWatcher) {
        this.fileWatcher = fileWatcher;
    }

    @Tool(name = "javaducker_watch",
            description = "Control the file watcher: start watching a directory, stop watching, or check status")
    public Map<String, Object> watch(
            @ToolParam(description = "Action to perform: start, stop, or status", required = true) String action,
            @ToolParam(description = "Absolute path to the directory to watch (required for start)", required = false) String directory,
            @ToolParam(description = "Comma-separated file extensions to watch, e.g. .java,.xml,.md (optional, defaults to all files)", required = false) String extensions) {
        try {
            return switch (action.toLowerCase()) {
                case "start" -> startWatching(directory, extensions);
                case "stop" -> stopWatching();
                case "status" -> getStatus();
                default -> Map.of("error", "Unknown action: " + action + ". Use start, stop, or status.");
            };
        } catch (Exception e) {
            log.error("watch {} failed", action, e);
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> startWatching(String directory, String extensions) throws Exception {
        if (directory == null || directory.isBlank()) {
            return Map.of("error", "directory is required for the start action");
        }

        Set<String> extSet = parseExtensions(extensions);
        Path dirPath = Path.of(directory);

        fileWatcher.startWatching(dirPath, extSet);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "start");
        result.put("directory", directory);
        result.put("extensions", extSet);
        result.put("watching", true);
        return result;
    }

    private Map<String, Object> stopWatching() {
        fileWatcher.stopWatching();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "stop");
        result.put("watching", false);
        return result;
    }

    private Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "status");
        result.put("watching", fileWatcher.isWatching());
        Path dir = fileWatcher.getWatchedDirectory();
        result.put("directory", dir != null ? dir.toString() : null);
        return result;
    }

    private Set<String> parseExtensions(String extensions) {
        if (extensions == null || extensions.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(extensions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s : "." + s)
                .collect(Collectors.toSet());
    }
}
