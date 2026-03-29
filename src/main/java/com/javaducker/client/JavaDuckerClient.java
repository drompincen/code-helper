package com.javaducker.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Command(name = "javaducker", mixinStandardHelpOptions = true, version = "2.0.0",
        description = "JavaDucker CLI client",
        subcommands = {
                JavaDuckerClient.HealthCmd.class,
                JavaDuckerClient.UploadFileCmd.class,
                JavaDuckerClient.UploadDirCmd.class,
                JavaDuckerClient.FindCmd.class,
                JavaDuckerClient.CatCmd.class,
                JavaDuckerClient.StatusCmd.class,
                JavaDuckerClient.StatsCmd.class,
                JavaDuckerClient.EnrichQueueCmd.class,
                JavaDuckerClient.ClassifyCmd.class,
                JavaDuckerClient.TagCmd.class,
                JavaDuckerClient.LatestCmd.class,
                JavaDuckerClient.FindByTypeCmd.class,
                JavaDuckerClient.ConceptsCmd.class,
                JavaDuckerClient.ConceptTimelineCmd.class,
                JavaDuckerClient.StaleContentCmd.class,
                JavaDuckerClient.ConceptHealthCmd.class,
                JavaDuckerClient.IndexHealthCmd.class,
        })
public class JavaDuckerClient implements Runnable {

    @Option(names = {"--host"}, defaultValue = "localhost", description = "Server host")
    String host;

    @Option(names = {"--port"}, defaultValue = "8080", description = "Server HTTP port")
    int port;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JavaDuckerClient()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    static String baseUrl(JavaDuckerClient p) {
        return "http://" + p.host + ":" + p.port + "/api";
    }

    static HttpClient http() {
        return HttpClient.newHttpClient();
    }

    static Map<String, Object> get(String url) throws Exception {
        var resp = http().send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) throw new RuntimeException("Not found");
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    static Map<String, Object> post(String url, Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        var resp = http().send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    static Map<String, Object> upload(String url, Path path) throws Exception {
        byte[] content = Files.readAllBytes(path);
        String mediaType = Files.probeContentType(path);
        if (mediaType == null) mediaType = "application/octet-stream";
        String fileName = path.getFileName().toString();
        String boundary = "----JavaDuckerBoundary" + System.currentTimeMillis();

        var baos = new ByteArrayOutputStream();
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
        baos.write(("Content-Type: " + mediaType + "\r\n\r\n").getBytes());
        baos.write(content);
        baos.write("\r\n".getBytes());
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"originalClientPath\"\r\n\r\n".getBytes());
        baos.write(path.toAbsolutePath().toString().getBytes());
        baos.write("\r\n".getBytes());
        baos.write(("--" + boundary + "--\r\n").getBytes());

        var resp = http().send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    // ── health ──────────────────────────────────────────────
    @Command(name = "health", description = "Check server health")
    static class HealthCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/health");
                System.out.println("Status:  " + resp.get("status"));
                System.out.println("Version: " + resp.get("version"));
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ── upload-file ─────────────────────────────────────────
    @Command(name = "upload-file", description = "Upload a single file")
    static class UploadFileCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--file"}, required = true, description = "Path to file")
        String filePath;

        @Override
        public void run() {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + filePath);
                System.exit(1);
            }
            try {
                Map<String, Object> resp = upload(baseUrl(parent) + "/upload", path);
                System.out.println("Artifact ID: " + resp.get("artifact_id"));
                System.out.println("Status:      " + resp.get("status"));
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ── upload-dir ──────────────────────────────────────────
    @Command(name = "upload-dir", description = "Upload all matching files from a directory")
    static class UploadDirCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--root"}, required = true, description = "Root directory to scan")
        String rootDir;

        @Option(names = {"--ext"}, defaultValue = ".java,.xml,.md,.yml,.json,.txt,.pdf,.docx,.pptx,.xlsx,.doc,.ppt,.xls,.odt,.odp,.ods,.html,.htm,.epub,.rtf,.eml",
                description = "Comma-separated file extensions to include")
        String extensions;

        @Override
        public void run() {
            Path root = Path.of(rootDir);
            if (!Files.isDirectory(root)) {
                System.err.println("Not a directory: " + rootDir);
                System.exit(1);
            }

            Set<String> exts = Set.of(extensions.toLowerCase().split(","));
            int uploaded = 0, skipped = 0, failed = 0;

            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    String name = file.getFileName().toString().toLowerCase();
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                    if (!exts.contains(ext)) {
                        skipped++;
                        continue;
                    }
                    try {
                        Map<String, Object> resp = upload(baseUrl(parent) + "/upload", file);
                        System.out.println("  Uploaded: " + file + " -> " + resp.get("artifact_id"));
                        uploaded++;
                    } catch (Exception e) {
                        System.err.println("  Failed:   " + file + " (" + e.getMessage() + ")");
                        failed++;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error scanning directory: " + e.getMessage());
                System.exit(1);
            }

            System.out.println("\nSummary:");
            System.out.println("  Uploaded: " + uploaded);
            System.out.println("  Skipped:  " + skipped);
            System.out.println("  Failed:   " + failed);
        }
    }

    // ── find ────────────────────────────────────────────────
    @Command(name = "find", description = "Search indexed content")
    static class FindCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--phrase"}, required = true, description = "Search phrase")
        String phrase;

        @Option(names = {"--mode"}, defaultValue = "hybrid",
                description = "Search mode: exact, semantic, hybrid")
        String mode;

        @Option(names = {"--max"}, defaultValue = "20", description = "Max results")
        int maxResults;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = post(baseUrl(parent) + "/search",
                        Map.of("phrase", phrase, "mode", mode, "max_results", maxResults));

                System.out.println("Results: " + resp.get("total_results"));
                System.out.println();
                List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
                if (results != null) {
                    for (int i = 0; i < results.size(); i++) {
                        Map<String, Object> r = results.get(i);
                        System.out.printf("#%d [%s] score=%.4f  file=%s  chunk=%s%n",
                                i + 1, r.get("match_type"),
                                ((Number) r.get("score")).doubleValue(),
                                r.get("file_name"), r.get("chunk_index"));
                        String preview = (String) r.get("preview");
                        if (preview != null) {
                            System.out.println("    " + preview.replace("\n", "\n    "));
                        }
                        System.out.println();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ── cat ─────────────────────────────────────────────────
    @Command(name = "cat", description = "Retrieve extracted text for an artifact")
    static class CatCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--id"}, required = true, description = "Artifact ID")
        String artifactId;

        @Override
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/text/" + artifactId);
                System.out.println("Artifact:   " + resp.get("artifact_id"));
                System.out.println("Method:     " + resp.get("extraction_method"));
                System.out.println("Length:     " + resp.get("text_length"));
                System.out.println("---");
                System.out.println(resp.get("extracted_text"));
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ── status ──────────────────────────────────────────────
    @Command(name = "status", description = "Check artifact ingestion status")
    static class StatusCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Option(names = {"--id"}, required = true, description = "Artifact ID")
        String artifactId;

        @Override
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/status/" + artifactId);
                System.out.println("Artifact: " + resp.get("artifact_id"));
                System.out.println("File:     " + resp.get("file_name"));
                System.out.println("Status:   " + resp.get("status"));
                Object err = resp.get("error_message");
                if (err != null && !err.toString().isEmpty()) {
                    System.out.println("Error:    " + err);
                }
                System.out.println("Created:  " + resp.get("created_at"));
                System.out.println("Updated:  " + resp.get("updated_at"));
                Object indexedAt = resp.get("indexed_at");
                if (indexedAt != null && !indexedAt.toString().isEmpty()) {
                    System.out.println("Indexed:  " + indexedAt);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ── stats ───────────────────────────────────────────────
    @Command(name = "stats", description = "View server statistics")
    static class StatsCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/stats");
                System.out.println("Total artifacts:   " + resp.get("total_artifacts"));
                System.out.println("Indexed:           " + resp.get("indexed_artifacts"));
                System.out.println("Failed:            " + resp.get("failed_artifacts"));
                System.out.println("Pending:           " + resp.get("pending_artifacts"));
                System.out.println("Total chunks:      " + resp.get("total_chunks"));
                System.out.println("Total bytes:       " + resp.get("total_bytes"));
                Map<String, Object> byStatus = (Map<String, Object>) resp.get("artifacts_by_status");
                if (byStatus != null && !byStatus.isEmpty()) {
                    System.out.println("By status:");
                    byStatus.forEach((k, v) -> System.out.println("  " + k + ": " + v));
                }
                Map<String, Object> byEnrichment = (Map<String, Object>) resp.get("enrichment_status");
                if (byEnrichment != null && !byEnrichment.isEmpty()) {
                    System.out.println("Enrichment:");
                    byEnrichment.forEach((k, v) -> System.out.println("  " + k + ": " + v));
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ── Content Intelligence CLI commands ────────────────────

    @Command(name = "enrich-queue", description = "List artifacts pending enrichment")
    static class EnrichQueueCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;
        @Option(names = {"--limit"}, defaultValue = "50") int limit;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/enrich-queue?limit=" + limit);
                System.out.println("Pending enrichment: " + resp.get("count"));
                List<Map<String, Object>> queue = (List<Map<String, Object>>) resp.get("queue");
                if (queue != null) {
                    for (Map<String, Object> item : queue) {
                        System.out.println("  " + item.get("artifact_id") + " — " + item.get("file_name"));
                    }
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "classify", description = "Classify an artifact by document type")
    static class ClassifyCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;
        @Option(names = {"--id"}, required = true) String artifactId;
        @Option(names = {"--doc-type"}, required = true) String docType;
        @Option(names = {"--confidence"}, defaultValue = "1.0") double confidence;
        @Option(names = {"--method"}, defaultValue = "manual") String method;

        @Override
        public void run() {
            try {
                Map<String, Object> resp = post(baseUrl(parent) + "/classify",
                        Map.of("artifactId", artifactId, "docType", docType,
                                "confidence", confidence, "method", method));
                System.out.println("Classified: " + resp.get("artifact_id") + " as " + resp.get("doc_type"));
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "tag", description = "Add tags to an artifact")
    static class TagCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;
        @Option(names = {"--id"}, required = true) String artifactId;
        @Option(names = {"--tags"}, required = true, description = "Comma-separated tags") String tags;

        @Override
        public void run() {
            try {
                List<Map<String, String>> tagList = new java.util.ArrayList<>();
                for (String t : tags.split(",")) {
                    tagList.add(Map.of("tag", t.trim(), "tag_type", "topic", "source", "manual"));
                }
                Map<String, Object> resp = post(baseUrl(parent) + "/tag",
                        Map.of("artifactId", artifactId, "tags", tagList));
                System.out.println("Tagged: " + resp.get("artifact_id") + " (" + resp.get("tags_count") + " tags)");
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "latest", description = "Get the latest current artifact on a topic")
    static class LatestCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;
        @Option(names = {"--topic"}, required = true) String topic;

        @Override
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/latest?topic=" +
                        java.net.URLEncoder.encode(topic, "UTF-8"));
                if (Boolean.TRUE.equals(resp.get("found"))) {
                    System.out.println("Artifact: " + resp.get("artifact_id"));
                    System.out.println("File:     " + resp.get("file_name"));
                    System.out.println("Type:     " + resp.get("doc_type"));
                    System.out.println("Fresh:    " + resp.get("freshness"));
                } else {
                    System.out.println("No current artifact found for topic: " + topic);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "find-by-type", description = "Find artifacts by document type")
    static class FindByTypeCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;
        @Option(names = {"--doc-type"}, required = true) String docType;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/find-by-type?docType=" +
                        java.net.URLEncoder.encode(docType, "UTF-8"));
                System.out.println("Found " + resp.get("count") + " artifact(s) of type " + docType);
                List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
                if (results != null) {
                    for (Map<String, Object> r : results) {
                        System.out.println("  " + r.get("artifact_id") + " — " + r.get("file_name") + " [" + r.get("freshness") + "]");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "concepts", description = "List all concepts across the corpus")
    static class ConceptsCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/concepts");
                System.out.println("Concepts: " + resp.get("count"));
                List<Map<String, Object>> concepts = (List<Map<String, Object>>) resp.get("concepts");
                if (concepts != null) {
                    for (Map<String, Object> c : concepts) {
                        System.out.println("  " + c.get("concept") + " (" + c.get("doc_count") + " docs, " + c.get("total_mentions") + " mentions)");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "concept-timeline", description = "Show evolution of a concept over time")
    static class ConceptTimelineCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;
        @Option(names = {"--concept"}, required = true) String concept;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/concept-timeline/" +
                        java.net.URLEncoder.encode(concept, "UTF-8"));
                System.out.println("Concept: " + resp.get("concept") + " (" + resp.get("total_docs") + " docs)");
                List<Map<String, Object>> timeline = (List<Map<String, Object>>) resp.get("timeline");
                if (timeline != null) {
                    for (Map<String, Object> entry : timeline) {
                        System.out.println("  " + entry.get("created_at") + " — " +
                                entry.get("file_name") + " [" + entry.get("doc_type") + "] " +
                                entry.get("freshness"));
                    }
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "stale-content", description = "List stale and superseded artifacts")
    static class StaleContentCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/stale-content");
                System.out.println("Stale/superseded: " + resp.get("count"));
                List<Map<String, Object>> stale = (List<Map<String, Object>>) resp.get("stale");
                if (stale != null) {
                    for (Map<String, Object> s : stale) {
                        System.out.println("  " + s.get("artifact_id") + " — " + s.get("file_name") +
                                " [" + s.get("freshness") + "]" +
                                (s.get("superseded_by") != null ? " superseded by " + s.get("superseded_by") : ""));
                    }
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Command(name = "concept-health", description = "Health report for all concepts")
    static class ConceptHealthCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/concept-health");
                System.out.println("Concepts: " + resp.get("total"));
                List<Map<String, Object>> concepts = (List<Map<String, Object>>) resp.get("concepts");
                if (concepts != null) {
                    for (Map<String, Object> c : concepts) {
                        System.out.println("  " + c.get("concept") + " — " +
                                c.get("active_docs") + " active, " + c.get("stale_docs") + " stale [" +
                                c.get("trend") + "]");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ── Index Health CLI command ─────────────────────────────
    @Command(name = "index-health", description = "Check index health: current vs stale files with recommendation")
    static class IndexHealthCmd implements Runnable {
        @CommandLine.ParentCommand JavaDuckerClient parent;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Map<String, Object> resp = get(baseUrl(parent) + "/stale/summary");
                int staleCount = resp.get("stale_count") != null
                        ? ((Number) resp.get("stale_count")).intValue() : 0;
                double stalePercent = resp.get("stale_percentage") != null
                        ? ((Number) resp.get("stale_percentage")).doubleValue() : 0.0;
                long total = resp.get("total_checked") != null
                        ? ((Number) resp.get("total_checked")).longValue() : 0;
                int currentCount = resp.get("current") != null
                        ? ((Number) resp.get("current")).intValue() : 0;

                String status = stalePercent > 10 ? "DEGRADED" : "HEALTHY";
                System.out.println("Index Health: " + status);
                System.out.println("  Total files:   " + total);
                System.out.println("  Current:       " + currentCount);
                System.out.println("  Stale:         " + staleCount
                        + " (" + String.format("%.1f", stalePercent) + "%)");

                if (staleCount > 0) {
                    List<Map<String, Object>> staleFiles =
                            (List<Map<String, Object>>) resp.get("stale");
                    if (staleFiles != null) {
                        System.out.println("  Stale files:");
                        for (Map<String, Object> f : staleFiles) {
                            System.out.println("    " + f.get("original_client_path"));
                        }
                    }
                    if (stalePercent > 10) {
                        System.out.println("\nRecommendation: >10% stale — run a full re-index.");
                    } else {
                        System.out.println("\nRecommendation: Re-index the listed stale files.");
                    }
                } else {
                    System.out.println("\nAll indexed files are current. No action needed.");
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }
}
