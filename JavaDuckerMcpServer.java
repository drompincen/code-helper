///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS io.modelcontextprotocol.sdk:mcp:1.1.0
//DEPS org.slf4j:slf4j-nop:2.0.16

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.McpServer;
import io.modelcontextprotocol.sdk.server.transport.StdioServerTransportProvider;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class JavaDuckerMcpServer {

    static final String HOST = System.getenv().getOrDefault("JAVADUCKER_HOST", "localhost");
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8080"));
    static final String PROJECT_ROOT = System.getenv().getOrDefault("PROJECT_ROOT", ".");
    static final String BASE_URL = "http://" + HOST + ":" + PORT + "/api";
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        ensureServerRunning();

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
                    "Upload and index a single file (.java,.xml,.md,.yml,.json,.txt,.pdf,.docx,.pptx,.xlsx,.doc,.ppt,.xls,.odt,.odp,.ods,.html,.htm,.epub,.rtf,.eml). " +
                    "Returns artifact_id. Indexing is async — use javaducker_wait_for_indexed to confirm.",
                    schema(props(
                        "file_path", str("Absolute path to the file to index")),
                        "file_path")),
                (ex, a) -> call(() -> indexFile((String) a.get("file_path"))))
            .tool(
                tool("javaducker_index_directory",
                    "Recursively index all source files in a directory. This is the primary way to " +
                    "ingest an entire codebase. Async — use javaducker_stats to monitor progress. " +
                    "extensions defaults to .java,.xml,.md,.yml,.json,.txt,.pdf,.docx,.pptx,.xlsx,.doc,.ppt,.xls,.odt,.odp,.ods,.html,.htm,.epub,.rtf,.eml",
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

    static Map<String, Object> health() throws Exception {
        return httpGet("/health");
    }

    static Map<String, Object> indexFile(String filePath) throws Exception {
        return httpUpload(Path.of(filePath));
    }

    static final Set<String> EXCLUDED_DIRS = Set.of(
        "node_modules", ".git", ".svn", ".hg",
        "target", "build", "dist", "out", ".gradle",
        "__pycache__", ".pytest_cache", ".mypy_cache",
        "vendor", ".idea", ".vscode", "coverage",
        "temp", "test-corpus"
    );

    static Map<String, Object> indexDirectory(String directory, String extensions) throws Exception {
        Path root = Path.of(directory);
        Set<String> exts = Set.of((extensions.isBlank()
            ? ".java,.xml,.md,.yml,.json,.txt,.pdf,.docx,.pptx,.xlsx,.doc,.ppt,.xls,.odt,.odp,.ods,.html,.htm,.epub,.rtf,.eml" : extensions)
            .toLowerCase().split(","));

        List<Map<String, String>> uploaded = new ArrayList<>();
        int[] skipped = {0}, failed = {0};

        try (Stream<Path> walk = Files.walk(root).filter(p ->
                !p.equals(root) &&
                (Files.isRegularFile(p) || EXCLUDED_DIRS.stream().noneMatch(
                    ex -> p.getFileName() != null && p.getFileName().toString().equals(ex))))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                boolean inExcluded = false;
                for (Path part : file) {
                    if (EXCLUDED_DIRS.contains(part.toString())) { inExcluded = true; break; }
                }
                if (inExcluded) { skipped[0]++; continue; }

                String name = file.getFileName().toString().toLowerCase();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                if (!exts.contains(ext)) { skipped[0]++; continue; }
                try {
                    Map<String, Object> r = httpUpload(file);
                    uploaded.add(Map.of("file", file.toString(), "artifact_id", (String) r.get("artifact_id")));
                } catch (Exception e) { failed[0]++; }
            }
        }
        return Map.of(
            "uploaded", uploaded,
            "summary", Map.of("uploaded", uploaded.size(), "skipped", skipped[0], "failed", failed[0]));
    }

    static Map<String, Object> search(String phrase, String mode, int maxResults) throws Exception {
        return httpPost("/search", Map.of("phrase", phrase, "mode", mode, "max_results", maxResults));
    }

    static Map<String, Object> getFileText(String artifactId) throws Exception {
        Map<String, Object> r = httpGet("/text/" + artifactId);
        if (r == null) throw new RuntimeException("Artifact not found: " + artifactId);
        return r;
    }

    static Map<String, Object> getArtifactStatus(String artifactId) throws Exception {
        Map<String, Object> r = httpGet("/status/" + artifactId);
        if (r == null) throw new RuntimeException("Artifact not found: " + artifactId);
        return r;
    }

    static Map<String, Object> waitForIndexed(String artifactId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> r = getArtifactStatus(artifactId);
            String status = (String) r.get("status");
            if ("INDEXED".equals(status) || "FAILED".equals(status)) {
                return Map.of(
                    "artifact_id",     artifactId,
                    "final_status",    status,
                    "elapsed_seconds", (System.currentTimeMillis() - start) / 1000.0);
            }
            Thread.sleep(3000);
        }
        throw new RuntimeException(
            "Artifact " + artifactId + " did not reach INDEXED within " + timeoutSeconds + "s");
    }

    static Map<String, Object> stats() throws Exception {
        return httpGet("/stats");
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    static Map<String, Object> httpGet(String path) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .GET().build();
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return null;
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    static Map<String, Object> httpPost(String path, Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    static Map<String, Object> httpUpload(Path path) throws Exception {
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

        var req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/upload"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
            .build();
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
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
