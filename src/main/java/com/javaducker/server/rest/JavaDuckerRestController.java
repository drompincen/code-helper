package com.javaducker.server.rest;

import com.javaducker.server.ingestion.FileWatcher;
import com.javaducker.server.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class JavaDuckerRestController {

    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final SearchService searchService;
    private final StatsService statsService;
    private final ProjectMapService projectMapService;
    private final StalenessService stalenessService;
    private final DependencyService dependencyService;
    private final FileWatcher fileWatcher;

    public JavaDuckerRestController(UploadService uploadService, ArtifactService artifactService,
                                     SearchService searchService, StatsService statsService,
                                     ProjectMapService projectMapService, StalenessService stalenessService,
                                     DependencyService dependencyService, FileWatcher fileWatcher) {
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.searchService = searchService;
        this.statsService = statsService;
        this.projectMapService = projectMapService;
        this.stalenessService = stalenessService;
        this.dependencyService = dependencyService;
        this.fileWatcher = fileWatcher;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "OK", "version", "2.0.0");
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() throws Exception {
        return ResponseEntity.ok(statsService.getStats());
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "originalClientPath", defaultValue = "") String originalClientPath,
            @RequestParam(value = "mediaType", defaultValue = "") String mediaType) throws Exception {
        String effectiveMediaType = mediaType.isBlank() ? file.getContentType() : mediaType;
        if (effectiveMediaType == null) effectiveMediaType = "application/octet-stream";
        String artifactId = uploadService.upload(
                file.getOriginalFilename(),
                originalClientPath,
                effectiveMediaType,
                file.getSize(),
                file.getBytes());
        return ResponseEntity.ok(Map.of("artifact_id", artifactId, "status", "STORED_IN_INTAKE"));
    }

    @GetMapping("/status/{artifactId}")
    public ResponseEntity<?> getStatus(@PathVariable String artifactId) throws Exception {
        Map<String, String> status = artifactService.getStatus(artifactId);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/text/{artifactId}")
    public ResponseEntity<?> getText(@PathVariable String artifactId) throws Exception {
        Map<String, String> text = artifactService.getText(artifactId);
        if (text == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(text);
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> body) throws Exception {
        String phrase = (String) body.get("phrase");
        String mode = (String) body.getOrDefault("mode", "hybrid");
        int maxResults = body.containsKey("max_results")
                ? ((Number) body.get("max_results")).intValue() : 20;

        List<Map<String, Object>> results = switch (mode.toLowerCase()) {
            case "exact"    -> searchService.exactSearch(phrase, maxResults);
            case "semantic" -> searchService.semanticSearch(phrase, maxResults);
            default         -> searchService.hybridSearch(phrase, maxResults);
        };
        return ResponseEntity.ok(Map.of("total_results", results.size(), "results", results));
    }

    @GetMapping("/summary/{artifactId}")
    public ResponseEntity<?> getSummary(@PathVariable String artifactId) throws Exception {
        Map<String, Object> summary = artifactService.getSummary(artifactId);
        if (summary == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/map")
    public ResponseEntity<Map<String, Object>> getProjectMap() throws Exception {
        return ResponseEntity.ok(projectMapService.getProjectMap());
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/stale")
    public ResponseEntity<Map<String, Object>> checkStale(@RequestBody Map<String, Object> body) throws Exception {
        List<String> filePaths = (List<String>) body.get("file_paths");
        if (filePaths == null || filePaths.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file_paths is required"));
        }
        return ResponseEntity.ok(stalenessService.checkStaleness(filePaths));
    }

    @GetMapping("/dependencies/{artifactId}")
    public ResponseEntity<?> getDependencies(@PathVariable String artifactId) throws Exception {
        return ResponseEntity.ok(Map.of("artifact_id", artifactId,
                "dependencies", dependencyService.getDependencies(artifactId)));
    }

    @GetMapping("/dependents/{artifactId}")
    public ResponseEntity<?> getDependents(@PathVariable String artifactId) throws Exception {
        return ResponseEntity.ok(Map.of("artifact_id", artifactId,
                "dependents", dependencyService.getDependents(artifactId)));
    }

    @PostMapping("/watch/start")
    public ResponseEntity<Map<String, Object>> startWatch(@RequestBody Map<String, Object> body) throws Exception {
        String directory = (String) body.get("directory");
        String extensions = (String) body.getOrDefault("extensions", "");
        Set<String> extSet = extensions.isBlank()
                ? Set.of()
                : Arrays.stream(extensions.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
        fileWatcher.startWatching(Path.of(directory), extSet);
        return ResponseEntity.ok(Map.of(
                "status", "watching",
                "directory", directory,
                "extensions", extSet));
    }

    @PostMapping("/watch/stop")
    public ResponseEntity<Map<String, Object>> stopWatch() {
        fileWatcher.stopWatching();
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    @GetMapping("/watch/status")
    public ResponseEntity<Map<String, Object>> watchStatus() {
        return ResponseEntity.ok(Map.of(
                "watching", fileWatcher.isWatching(),
                "directory", fileWatcher.getWatchedDirectory() != null
                        ? fileWatcher.getWatchedDirectory().toString() : ""));
    }
}
