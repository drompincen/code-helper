///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS io.modelcontextprotocol.sdk:mcp:1.1.0
//DEPS io.grpc:grpc-netty-shaded:1.63.0
//DEPS io.grpc:grpc-protobuf:1.63.0
//DEPS io.grpc:grpc-stub:1.63.0
//DEPS com.google.protobuf:protobuf-java:3.25.3
//DEPS javax.annotation:javax.annotation-api:1.3.2
//DEPS org.slf4j:slf4j-nop:2.0.16
//CP target/classes

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.javaducker.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.McpServer;
import io.modelcontextprotocol.sdk.server.transport.StdioServerTransportProvider;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class JavaDuckerMcpServer {

    static final String HOST = System.getenv().getOrDefault("GRPC_HOST", "localhost");
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "9090"));
    static final String PROJECT_ROOT = System.getenv().getOrDefault("PROJECT_ROOT", ".");
    static final ObjectMapper MAPPER = new ObjectMapper();

    static ManagedChannel channel;
    static JavaDuckerGrpc.JavaDuckerBlockingStub stub;

    public static void main(String[] args) throws Exception {
        ensureServerRunning();

        channel = ManagedChannelBuilder.forAddress(HOST, PORT).usePlaintext().build();
        stub = JavaDuckerGrpc.newBlockingStub(channel);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { channel.shutdown().awaitTermination(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }));

        McpServer.sync(new StdioServerTransportProvider(MAPPER))
            .serverInfo("javaducker", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tool(
                tool("javaducker_health",
                    "Check if the JavaDucker server is running. Returns status and version.",
                    "{}"),
                (ex, a) -> call(JavaDuckerMcpServer::health))
            .tool(
                tool("javaducker_index_file",
                    "Upload and index a single file (.java,.xml,.md,.yml,.json,.txt,.pdf). " +
                    "Returns artifact_id. Indexing is async — use javaducker_wait_for_indexed to confirm.",
                    schema(props(
                        "file_path", str("Absolute path to the file to index")),
                        "file_path")),
                (ex, a) -> call(() -> indexFile((String) a.get("file_path"))))
            .tool(
                tool("javaducker_index_directory",
                    "Recursively index all source files in a directory. This is the primary way to " +
                    "ingest an entire codebase. Async — use javaducker_stats to monitor progress. " +
                    "extensions defaults to .java,.xml,.md,.yml,.json,.txt,.pdf",
                    schema(props(
                        "directory", str("Absolute path to the root directory to index"),
                        "extensions", str("Comma-separated file extensions, e.g. .java,.xml,.md (optional)")),
                        "directory")),
                (ex, a) -> call(() -> indexDirectory(
                    (String) a.get("directory"),
                    (String) a.getOrDefault("extensions", ""))))
            .tool(
                tool("javaducker_search",
                    "Search the indexed codebase. Modes: " +
                    "exact=literal substring (best for @Annotations, class names, constants), " +
                    "semantic=concept/intent matching, " +
                    "hybrid=weighted combination (default, best general use). " +
                    "Returns ranked results with file, score, chunk index, and text preview.",
                    schema(props(
                        "phrase", str("Search query or phrase"),
                        "mode",   str("exact, semantic, or hybrid (default)"),
                        "max_results", intParam("Max results to return (default 20)")),
                        "phrase")),
                (ex, a) -> call(() -> search(
                    (String) a.get("phrase"),
                    (String) a.getOrDefault("mode", "hybrid"),
                    a.containsKey("max_results") ? ((Number) a.get("max_results")).intValue() : 20)))
            .tool(
                tool("javaducker_get_file_text",
                    "Retrieve the full extracted text of an indexed file by artifact_id. " +
                    "Use after a search to read the complete file content rather than just a chunk preview.",
                    schema(props(
                        "artifact_id", str("Artifact ID from search or index results")),
                        "artifact_id")),
                (ex, a) -> call(() -> getFileText((String) a.get("artifact_id"))))
            .tool(
                tool("javaducker_get_artifact_status",
                    "Check the ingestion status of a specific artifact. " +
                    "Lifecycle: RECEIVED→STORED_IN_INTAKE→PARSING→CHUNKED→EMBEDDED→INDEXED (or FAILED). " +
                    "Returns status, timestamps, and any error message.",
                    schema(props(
                        "artifact_id", str("Artifact ID to check")),
                        "artifact_id")),
                (ex, a) -> call(() -> getArtifactStatus((String) a.get("artifact_id"))))
            .tool(
                tool("javaducker_wait_for_indexed",
                    "Block and poll until an artifact reaches INDEXED or FAILED status. " +
                    "Use after javaducker_index_file to confirm a file is searchable before querying.",
                    schema(props(
                        "artifact_id",    str("Artifact ID to poll"),
                        "timeout_seconds", intParam("Max seconds to wait (default 120)")),
                        "artifact_id")),
                (ex, a) -> call(() -> waitForIndexed(
                    (String) a.get("artifact_id"),
                    a.containsKey("timeout_seconds") ? ((Number) a.get("timeout_seconds")).intValue() : 120)))
            .tool(
                tool("javaducker_stats",
                    "Return aggregate indexing statistics: total artifacts, how many are indexed vs " +
                    "pending vs failed, total chunks, and total bytes. Use after javaducker_index_directory " +
                    "to monitor bulk ingestion progress.",
                    "{}"),
                (ex, a) -> call(JavaDuckerMcpServer::stats))
            .build();
    }

    // ── Tool implementations ──────────────────────────────────────────────────

    static Map<String, Object> health() {
        HealthResponse r = stub.health(HealthRequest.getDefaultInstance());
        return Map.of("status", r.getStatus(), "version", r.getVersion());
    }

    static Map<String, Object> indexFile(String filePath) throws Exception {
        Path path = Path.of(filePath);
        byte[] content = Files.readAllBytes(path);
        String mediaType = Files.probeContentType(path);
        if (mediaType == null) mediaType = "application/octet-stream";

        UploadFileResponse r = stub.uploadFile(UploadFileRequest.newBuilder()
            .setFileName(path.getFileName().toString())
            .setOriginalClientPath(path.toAbsolutePath().toString())
            .setMediaType(mediaType)
            .setSizeBytes(content.length)
            .setContent(ByteString.copyFrom(content))
            .build());
        return Map.of("artifact_id", r.getArtifactId(), "status", r.getStatus());
    }

    static final Set<String> EXCLUDED_DIRS = Set.of(
        "node_modules", ".git", ".svn", ".hg",
        "target", "build", "dist", "out", ".gradle",
        "__pycache__", ".pytest_cache", ".mypy_cache",
        "vendor", ".idea", ".vscode", "coverage"
    );

    static Map<String, Object> indexDirectory(String directory, String extensions) throws Exception {
        Path root = Path.of(directory);
        Set<String> exts = Set.of((extensions.isBlank()
            ? ".java,.xml,.md,.yml,.json,.txt,.pdf" : extensions)
            .toLowerCase().split(","));

        List<Map<String, String>> uploaded = new ArrayList<>();
        int[] skipped = {0}, failed = {0};

        try (Stream<Path> walk = Files.walk(root).filter(p ->
                !p.equals(root) &&
                (Files.isRegularFile(p) || EXCLUDED_DIRS.stream().noneMatch(
                    ex -> p.getFileName() != null && p.getFileName().toString().equals(ex))))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                // Skip files inside excluded directories anywhere in the path
                boolean inExcluded = false;
                for (Path part : file) {
                    if (EXCLUDED_DIRS.contains(part.toString())) { inExcluded = true; break; }
                }
                if (inExcluded) { skipped[0]++; continue; }

                String name = file.getFileName().toString().toLowerCase();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                if (!exts.contains(ext)) { skipped[0]++; continue; }
                try {
                    byte[] content = Files.readAllBytes(file);
                    String mediaType = Files.probeContentType(file);
                    if (mediaType == null) mediaType = "application/octet-stream";
                    UploadFileResponse r = stub.uploadFile(UploadFileRequest.newBuilder()
                        .setFileName(file.getFileName().toString())
                        .setOriginalClientPath(file.toAbsolutePath().toString())
                        .setMediaType(mediaType)
                        .setSizeBytes(content.length)
                        .setContent(ByteString.copyFrom(content))
                        .build());
                    uploaded.add(Map.of("file", file.toString(), "artifact_id", r.getArtifactId()));
                } catch (Exception e) { failed[0]++; }
            }
        }
        return Map.of(
            "uploaded", uploaded,
            "summary", Map.of("uploaded", uploaded.size(), "skipped", skipped[0], "failed", failed[0]));
    }

    static Map<String, Object> search(String phrase, String mode, int maxResults) {
        SearchMode searchMode = switch (mode.toLowerCase()) {
            case "exact"    -> SearchMode.EXACT;
            case "semantic" -> SearchMode.SEMANTIC;
            default         -> SearchMode.HYBRID;
        };
        FindResponse r = stub.find(FindRequest.newBuilder()
            .setPhrase(phrase).setMode(searchMode).setMaxResults(maxResults).build());

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < r.getResultsCount(); i++) {
            SearchResult sr = r.getResults(i);
            results.add(Map.of(
                "rank",        i + 1,
                "match_type",  sr.getMatchType(),
                "score",       sr.getScore(),
                "file",        sr.getFileName(),
                "artifact_id", sr.getArtifactId(),
                "chunk_index", sr.getChunkIndex(),
                "preview",     sr.getPreview()));
        }
        return Map.of("total_results", r.getTotalResults(), "results", results);
    }

    static Map<String, Object> getFileText(String artifactId) {
        GetArtifactTextResponse r = stub.getArtifactText(
            GetArtifactTextRequest.newBuilder().setArtifactId(artifactId).build());
        return Map.of(
            "artifact_id",       r.getArtifactId(),
            "extraction_method", r.getExtractionMethod(),
            "text_length",       r.getTextLength(),
            "text",              r.getExtractedText());
    }

    static Map<String, Object> getArtifactStatus(String artifactId) {
        GetArtifactStatusResponse r = stub.getArtifactStatus(
            GetArtifactStatusRequest.newBuilder().setArtifactId(artifactId).build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifact_id", r.getArtifactId());
        result.put("file",        r.getFileName());
        result.put("status",      r.getStatus());
        result.put("error",       r.getErrorMessage());
        result.put("created_at",  r.getCreatedAt());
        result.put("updated_at",  r.getUpdatedAt());
        result.put("indexed_at",  r.getIndexedAt());
        return result;
    }

    static Map<String, Object> waitForIndexed(String artifactId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            GetArtifactStatusResponse r = stub.getArtifactStatus(
                GetArtifactStatusRequest.newBuilder().setArtifactId(artifactId).build());
            String status = r.getStatus();
            if ("INDEXED".equals(status) || "FAILED".equals(status)) {
                return Map.of(
                    "artifact_id",    artifactId,
                    "final_status",   status,
                    "elapsed_seconds", (System.currentTimeMillis() - start) / 1000.0);
            }
            Thread.sleep(3000);
        }
        throw new RuntimeException(
            "Artifact " + artifactId + " did not reach INDEXED within " + timeoutSeconds + "s");
    }

    static Map<String, Object> stats() {
        StatsResponse r = stub.stats(StatsRequest.getDefaultInstance());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_artifacts", r.getTotalArtifacts());
        result.put("indexed",         r.getIndexedArtifacts());
        result.put("failed",          r.getFailedArtifacts());
        result.put("pending",         r.getPendingArtifacts());
        result.put("total_chunks",    r.getTotalChunks());
        result.put("total_bytes",     r.getTotalBytes());
        result.put("by_status",       r.getArtifactsByStatusMap());
        return result;
    }

    // ── Server lifecycle ──────────────────────────────────────────────────────

    static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    static void ensureServerRunning() throws Exception {
        if (isHealthy()) return;
        System.err.println("[javaducker-mcp] Starting JavaDucker server...");
        Path script = Path.of(PROJECT_ROOT)
            .resolve(WINDOWS ? "run-server.cmd" : "run-server.sh");
        ProcessBuilder pb = WINDOWS
            ? new ProcessBuilder("cmd", "/c", script.toString())
            : new ProcessBuilder(script.toString());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2000);
            if (isHealthy()) {
                System.err.println("[javaducker-mcp] Server ready.");
                return;
            }
        }
        throw new RuntimeException(
            "JavaDucker server did not start within 60s. Build first: mvn package -DskipTests");
    }

    static boolean isHealthy() {
        try (var s = new Socket(HOST, PORT)) { return true; } catch (Exception e) { return false; }
    }

    // ── MCP helpers ───────────────────────────────────────────────────────────

    static McpSchema.Tool tool(String name, String description, String schemaJson) {
        return new McpSchema.Tool(name, description, schemaJson);
    }

    static McpSchema.CallToolResult call(ThrowingSupplier fn) {
        try {
            String json = MAPPER.writeValueAsString(fn.get());
            return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(false)
                .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Error: " + e.getMessage())))
                .isError(true)
                .build();
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier { Object get() throws Exception; }

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> intParam(String description) {
        return Map.of("type", "integer", "description", description);
    }

    /** Build a properties map alternating key, value pairs. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> props(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static String schema(Map<String, Object> properties, String... required) {
        try {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", properties);
            if (required.length > 0) s.put("required", List.of(required));
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
