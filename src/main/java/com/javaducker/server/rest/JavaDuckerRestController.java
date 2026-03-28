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
    private final ReladomoQueryService reladomoQueryService;
    private final ContentIntelligenceService contentIntelligenceService;

    public JavaDuckerRestController(UploadService uploadService, ArtifactService artifactService,
                                     SearchService searchService, StatsService statsService,
                                     ProjectMapService projectMapService, StalenessService stalenessService,
                                     DependencyService dependencyService, FileWatcher fileWatcher,
                                     ReladomoQueryService reladomoQueryService,
                                     ContentIntelligenceService contentIntelligenceService) {
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.searchService = searchService;
        this.statsService = statsService;
        this.projectMapService = projectMapService;
        this.stalenessService = stalenessService;
        this.dependencyService = dependencyService;
        this.fileWatcher = fileWatcher;
        this.reladomoQueryService = reladomoQueryService;
        this.contentIntelligenceService = contentIntelligenceService;
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

    // ── Content Intelligence: write endpoints ──────────────────────────────

    @SuppressWarnings("unchecked")
    @PostMapping("/classify")
    public ResponseEntity<Map<String, Object>> classify(@RequestBody Map<String, Object> body) throws Exception {
        String artifactId = (String) body.get("artifactId");
        String docType = (String) body.get("docType");
        double confidence = body.containsKey("confidence") ? ((Number) body.get("confidence")).doubleValue() : 1.0;
        String method = (String) body.getOrDefault("method", "llm");
        return ResponseEntity.ok(contentIntelligenceService.classify(artifactId, docType, confidence, method));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/tag")
    public ResponseEntity<Map<String, Object>> tagArtifact(@RequestBody Map<String, Object> body) throws Exception {
        String artifactId = (String) body.get("artifactId");
        List<Map<String, String>> tags = (List<Map<String, String>>) body.get("tags");
        return ResponseEntity.ok(contentIntelligenceService.tag(artifactId, tags));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/salient-points")
    public ResponseEntity<Map<String, Object>> salientPoints(@RequestBody Map<String, Object> body) throws Exception {
        String artifactId = (String) body.get("artifactId");
        List<Map<String, String>> points = (List<Map<String, String>>) body.get("points");
        return ResponseEntity.ok(contentIntelligenceService.extractPoints(artifactId, points));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/concepts")
    public ResponseEntity<Map<String, Object>> saveConcepts(@RequestBody Map<String, Object> body) throws Exception {
        String artifactId = (String) body.get("artifactId");
        List<Map<String, Object>> concepts = (List<Map<String, Object>>) body.get("concepts");
        return ResponseEntity.ok(contentIntelligenceService.saveConcepts(artifactId, concepts));
    }

    @PostMapping("/freshness")
    public ResponseEntity<?> setFreshness(@RequestBody Map<String, Object> body) throws Exception {
        String artifactId = (String) body.get("artifactId");
        String freshness = (String) body.get("freshness");
        String supersededBy = (String) body.getOrDefault("supersededBy", null);
        Map<String, Object> result = contentIntelligenceService.setFreshness(artifactId, freshness, supersededBy);
        if (result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/synthesize")
    public ResponseEntity<?> synthesize(@RequestBody Map<String, Object> body) throws Exception {
        String artifactId = (String) body.get("artifactId");
        Map<String, Object> result = contentIntelligenceService.synthesize(
                artifactId,
                (String) body.get("summaryText"),
                (String) body.get("tags"),
                (String) body.get("keyPoints"),
                (String) body.get("outcome"),
                (String) body.get("originalFilePath"));
        if (result == null) return ResponseEntity.notFound().build();
        if (result.containsKey("error")) return ResponseEntity.status(409).body(result);
        return ResponseEntity.ok(result);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/link-concepts")
    public ResponseEntity<Map<String, Object>> linkConcepts(@RequestBody Map<String, Object> body) throws Exception {
        List<Map<String, Object>> links = (List<Map<String, Object>>) body.get("links");
        return ResponseEntity.ok(contentIntelligenceService.linkConcepts(links));
    }

    @GetMapping("/enrich-queue")
    public ResponseEntity<Map<String, Object>> enrichQueue(
            @RequestParam(defaultValue = "50") int limit) throws Exception {
        var queue = contentIntelligenceService.getEnrichQueue(limit);
        return ResponseEntity.ok(Map.of("queue", queue, "count", queue.size()));
    }

    @PostMapping("/mark-enriched")
    public ResponseEntity<?> markEnriched(@RequestBody Map<String, Object> body) throws Exception {
        String artifactId = (String) body.get("artifactId");
        Map<String, Object> result = contentIntelligenceService.markEnriched(artifactId);
        if (result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(result);
    }

    // ── Content Intelligence: read endpoints ─────────────────────────────

    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest(@RequestParam String topic) throws Exception {
        return ResponseEntity.ok(contentIntelligenceService.getLatest(topic));
    }

    @GetMapping("/find-by-type")
    public ResponseEntity<Map<String, Object>> findByType(@RequestParam String docType) throws Exception {
        var results = contentIntelligenceService.findByType(docType);
        return ResponseEntity.ok(Map.of("doc_type", docType, "results", results, "count", results.size()));
    }

    @GetMapping("/find-by-tag")
    public ResponseEntity<Map<String, Object>> findByTag(@RequestParam String tag) throws Exception {
        var results = contentIntelligenceService.findByTag(tag);
        return ResponseEntity.ok(Map.of("tag", tag, "results", results, "count", results.size()));
    }

    @GetMapping("/find-points")
    public ResponseEntity<Map<String, Object>> findPoints(
            @RequestParam String pointType,
            @RequestParam(required = false) String tag) throws Exception {
        var results = contentIntelligenceService.findPoints(pointType, tag);
        return ResponseEntity.ok(Map.of("point_type", pointType, "results", results, "count", results.size()));
    }

    @GetMapping("/concepts")
    public ResponseEntity<Map<String, Object>> listConcepts() throws Exception {
        var results = contentIntelligenceService.listConcepts();
        return ResponseEntity.ok(Map.of("concepts", results, "count", results.size()));
    }

    @GetMapping("/concept-timeline/{concept}")
    public ResponseEntity<Map<String, Object>> conceptTimeline(@PathVariable String concept) throws Exception {
        return ResponseEntity.ok(contentIntelligenceService.getConceptTimeline(concept));
    }

    @GetMapping("/stale-content")
    public ResponseEntity<Map<String, Object>> staleContent() throws Exception {
        var results = contentIntelligenceService.getStaleContent();
        return ResponseEntity.ok(Map.of("stale", results, "count", results.size()));
    }

    @GetMapping("/synthesis/{artifactId}")
    public ResponseEntity<?> getSynthesis(@PathVariable String artifactId) throws Exception {
        Map<String, Object> result = contentIntelligenceService.getSynthesis(artifactId);
        if (result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/related-by-concept/{artifactId}")
    public ResponseEntity<Map<String, Object>> relatedByConcept(@PathVariable String artifactId) throws Exception {
        var results = contentIntelligenceService.getRelatedByConcept(artifactId);
        return ResponseEntity.ok(Map.of("artifact_id", artifactId, "related", results, "count", results.size()));
    }

    @GetMapping("/concept-health")
    public ResponseEntity<Map<String, Object>> conceptHealth() throws Exception {
        return ResponseEntity.ok(contentIntelligenceService.getConceptHealth());
    }

    @GetMapping("/synthesis/search")
    public ResponseEntity<Map<String, Object>> searchSynthesis(@RequestParam String keyword) throws Exception {
        var results = contentIntelligenceService.searchSynthesis(keyword);
        return ResponseEntity.ok(Map.of("keyword", keyword, "results", results, "count", results.size()));
    }

    // ── Reladomo endpoints ───────────────────────────────────────────────

    @GetMapping("/reladomo/relationships/{objectName}")
    public ResponseEntity<Map<String, Object>> reladomoRelationships(
            @PathVariable String objectName) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getRelationships(objectName));
    }

    @GetMapping("/reladomo/graph/{objectName}")
    public ResponseEntity<Map<String, Object>> reladomoGraph(
            @PathVariable String objectName,
            @RequestParam(defaultValue = "3") int depth) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getGraph(objectName, depth));
    }

    @GetMapping("/reladomo/path")
    public ResponseEntity<Map<String, Object>> reladomoPath(
            @RequestParam String from, @RequestParam String to) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getPath(from, to));
    }

    @GetMapping("/reladomo/schema/{objectName}")
    public ResponseEntity<Map<String, Object>> reladomoSchema(
            @PathVariable String objectName) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getSchema(objectName));
    }

    @GetMapping("/reladomo/files/{objectName}")
    public ResponseEntity<Map<String, Object>> reladomoFiles(
            @PathVariable String objectName) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getObjectFiles(objectName));
    }

    @GetMapping("/reladomo/finders/{objectName}")
    public ResponseEntity<Map<String, Object>> reladomoFinders(
            @PathVariable String objectName) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getFinderPatterns(objectName));
    }

    @GetMapping("/reladomo/deepfetch/{objectName}")
    public ResponseEntity<Map<String, Object>> reladomoDeepFetch(
            @PathVariable String objectName) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getDeepFetchProfiles(objectName));
    }

    @GetMapping("/reladomo/temporal")
    public ResponseEntity<Map<String, Object>> reladomoTemporal() throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getTemporalInfo());
    }

    @GetMapping("/reladomo/config")
    public ResponseEntity<Map<String, Object>> reladomoConfig(
            @RequestParam(required = false) String objectName) throws Exception {
        return ResponseEntity.ok(reladomoQueryService.getConfig(objectName));
    }
}
