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
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }
}
