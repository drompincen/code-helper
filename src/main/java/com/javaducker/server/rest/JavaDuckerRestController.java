package com.javaducker.server.rest;

import com.javaducker.server.service.ArtifactService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.StatsService;
import com.javaducker.server.service.UploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class JavaDuckerRestController {

    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final SearchService searchService;
    private final StatsService statsService;

    public JavaDuckerRestController(UploadService uploadService, ArtifactService artifactService,
                                     SearchService searchService, StatsService statsService) {
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.searchService = searchService;
        this.statsService = statsService;
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
}
