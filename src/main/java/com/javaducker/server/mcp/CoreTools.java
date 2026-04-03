package com.javaducker.server.mcp;

import com.javaducker.server.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class CoreTools {

    private static final Logger log = LoggerFactory.getLogger(CoreTools.class);

    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final SearchService searchService;
    private final StatsService statsService;
    private final StalenessService stalenessService;
    private final GraphSearchService graphSearchService;

    public CoreTools(UploadService uploadService,
                     ArtifactService artifactService,
                     SearchService searchService,
                     StatsService statsService,
                     StalenessService stalenessService,
                     GraphSearchService graphSearchService) {
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.searchService = searchService;
        this.statsService = statsService;
        this.stalenessService = stalenessService;
        this.graphSearchService = graphSearchService;
    }

    @Tool(name = "javaducker_health", description = "Check JavaDucker server health and return basic stats")
    public Map<String, Object> health() {
        try {
            Map<String, Object> stats = statsService.getStats();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.putAll(stats);
            return result;
        } catch (Exception e) {
            log.error("Health check failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_index_file", description = "Index a single file from disk into JavaDucker")
    public Map<String, Object> indexFile(
            @ToolParam(description = "Absolute path to the file to index", required = true) String file_path) {
        try {
            Path path = Path.of(file_path);
            if (!Files.exists(path)) {
                return Map.of("error", "File not found: " + file_path);
            }
            if (!Files.isRegularFile(path)) {
                return Map.of("error", "Not a regular file: " + file_path);
            }

            String fileName = path.getFileName().toString();
            String mediaType = Files.probeContentType(path);
            if (mediaType == null) {
                mediaType = "application/octet-stream";
            }
            byte[] content = Files.readAllBytes(path);
            long size = content.length;

            String artifactId = uploadService.upload(fileName, file_path, mediaType, size, content);
            return Map.of("artifact_id", artifactId, "file_name", fileName, "size_bytes", size);
        } catch (Exception e) {
            log.error("Failed to index file: {}", file_path, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_index_directory",
            description = "Index all matching files in a directory into JavaDucker")
    public Map<String, Object> indexDirectory(
            @ToolParam(description = "Absolute path to the directory to index", required = true) String directory,
            @ToolParam(description = "Comma-separated file extensions to include (e.g. java,xml,md). If omitted, all files are indexed.", required = false) String extensions) {
        try {
            Path dirPath = Path.of(directory);
            if (!Files.isDirectory(dirPath)) {
                return Map.of("error", "Not a directory: " + directory);
            }

            Set<String> extFilter = parseExtensions(extensions);
            List<String> indexed = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!Files.isRegularFile(file)) return FileVisitResult.CONTINUE;
                    if (!extFilter.isEmpty() && !matchesExtension(file, extFilter)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        String fileName = file.getFileName().toString();
                        String mediaType = Files.probeContentType(file);
                        if (mediaType == null) mediaType = "application/octet-stream";
                        byte[] content = Files.readAllBytes(file);
                        uploadService.upload(fileName, file.toAbsolutePath().toString(),
                                mediaType, content.length, content);
                        indexed.add(file.toAbsolutePath().toString());
                    } catch (Exception e) {
                        errors.add(file.toAbsolutePath() + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("indexed_count", indexed.size());
            result.put("error_count", errors.size());
            result.put("directory", directory);
            if (!errors.isEmpty()) {
                result.put("errors", errors);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to index directory: {}", directory, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_search",
            description = "Search indexed content. Modes: exact (string match), semantic (vector similarity), " +
                    "hybrid (default: 0.3 exact + 0.7 semantic), local (entity-centric graph search), " +
                    "global (relationship-centric graph search), graph_hybrid (local+global combined), " +
                    "mix (graph+vector combined — recommended when graph is populated)")
    public Map<String, Object> search(
            @ToolParam(description = "Search phrase or query", required = true) String phrase,
            @ToolParam(description = "Search mode: exact, semantic, hybrid, local, global, graph_hybrid, or mix (default: hybrid)", required = false) String mode,
            @ToolParam(description = "Maximum number of results (default: 20)", required = false) Integer max_results) {
        try {
            String effectiveMode = (mode == null || mode.isBlank()) ? "hybrid" : mode.toLowerCase();
            int effectiveMax = (max_results == null || max_results <= 0) ? 20 : max_results;

            List<Map<String, Object>> results = switch (effectiveMode) {
                case "exact" -> searchService.exactSearch(phrase, effectiveMax);
                case "semantic" -> searchService.semanticSearch(phrase, effectiveMax);
                case "hybrid" -> searchService.hybridSearch(phrase, effectiveMax);
                case "local" -> graphSearchService.localSearch(phrase, effectiveMax);
                case "global" -> graphSearchService.globalSearch(phrase, effectiveMax);
                case "graph_hybrid" -> graphSearchService.hybridGraphSearch(phrase, effectiveMax);
                case "mix" -> graphSearchService.mixSearch(phrase, effectiveMax);
                default -> throw new IllegalArgumentException(
                        "Unknown search mode: " + effectiveMode +
                        ". Use exact, semantic, hybrid, local, global, graph_hybrid, or mix.");
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", results.size());
            response.put("mode", effectiveMode);
            response.put("results", results);

            addStalenessWarning(results, response);

            return response;
        } catch (Exception e) {
            log.error("Search failed for phrase: {}", phrase, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_get_file_text",
            description = "Retrieve the extracted text content of an indexed artifact")
    public Map<String, Object> getFileText(
            @ToolParam(description = "The artifact ID to retrieve text for", required = true) String artifact_id) {
        try {
            Map<String, String> text = artifactService.getText(artifact_id);
            return new LinkedHashMap<>(text);
        } catch (Exception e) {
            log.error("Failed to get text for artifact: {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_get_artifact_status",
            description = "Get the current status of an indexed artifact")
    public Map<String, Object> getArtifactStatus(
            @ToolParam(description = "The artifact ID to check", required = true) String artifact_id) {
        try {
            Map<String, String> status = artifactService.getStatus(artifact_id);
            return new LinkedHashMap<>(status);
        } catch (Exception e) {
            log.error("Failed to get status for artifact: {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_wait_for_indexed",
            description = "Poll an artifact until it reaches INDEXED or FAILED status, or timeout")
    public Map<String, Object> waitForIndexed(
            @ToolParam(description = "The artifact ID to wait for", required = true) String artifact_id,
            @ToolParam(description = "Timeout in seconds (default: 120)", required = false) Integer timeout_seconds) {
        try {
            int timeout = (timeout_seconds == null || timeout_seconds <= 0) ? 120 : timeout_seconds;
            long deadline = System.currentTimeMillis() + (timeout * 1000L);

            while (System.currentTimeMillis() < deadline) {
                Map<String, String> status = artifactService.getStatus(artifact_id);
                String currentStatus = status.getOrDefault("status", "UNKNOWN");

                if ("INDEXED".equalsIgnoreCase(currentStatus) || "FAILED".equalsIgnoreCase(currentStatus)) {
                    return new LinkedHashMap<>(status);
                }

                Thread.sleep(2000);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Timeout after " + timeout + " seconds waiting for artifact " + artifact_id);
            result.put("artifact_id", artifact_id);
            return result;
        } catch (Exception e) {
            log.error("Wait for indexed failed for artifact: {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_stats", description = "Get JavaDucker indexing statistics")
    public Map<String, Object> stats() {
        try {
            return statsService.getStats();
        } catch (Exception e) {
            log.error("Failed to get stats", e);
            return Map.of("error", e.getMessage());
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private Set<String> parseExtensions(String extensions) {
        if (extensions == null || extensions.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(extensions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s : "." + s)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private boolean matchesExtension(Path file, Set<String> extFilter) {
        String name = file.getFileName().toString().toLowerCase();
        return extFilter.stream().anyMatch(name::endsWith);
    }

    @SuppressWarnings("unchecked")
    private void addStalenessWarning(List<Map<String, Object>> results, Map<String, Object> response) {
        try {
            List<String> paths = results.stream()
                    .map(r -> (String) r.get("original_client_path"))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            if (!paths.isEmpty()) {
                Map<String, Object> staleness = stalenessService.checkStaleness(paths);
                Object staleList = staleness.get("stale");
                if (staleList instanceof List<?> list && !list.isEmpty()) {
                    response.put("staleness_warning", "Some results may be stale (files changed since indexing)");
                    response.put("stale_files", staleList);
                }
            }
        } catch (Exception e) {
            log.warn("Staleness check failed, skipping warning", e);
        }
    }
}
