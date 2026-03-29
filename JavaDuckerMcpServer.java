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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    static final boolean STALENESS_CHECK_ENABLED =
        !"false".equalsIgnoreCase(System.getenv("JAVADUCKER_STALENESS_CHECK"));

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
            .tool(tool("javaducker_index_file",
                    "Upload and index a single file. Returns artifact_id. Async — use javaducker_wait_for_indexed to confirm.",
                    schema(props("file_path", str("Absolute path to the file to index")), "file_path")),
                (ex, a) -> call(() -> indexFile((String) a.get("file_path"))))
            .tool(tool("javaducker_index_directory",
                    "Recursively index all source files in a directory. Async — use javaducker_stats to monitor.",
                    schema(props("directory", str("Absolute path to the root directory to index"),
                        "extensions", str("Comma-separated extensions, e.g. .java,.xml,.md (optional)")), "directory")),
                (ex, a) -> call(() -> indexDirectory((String) a.get("directory"), (String) a.getOrDefault("extensions", ""))))
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
                (ex, a) -> call(() -> {
                    Map<String, Object> result = search(
                        (String) a.get("phrase"),
                        (String) a.getOrDefault("mode", "hybrid"),
                        a.containsKey("max_results") ? ((Number) a.get("max_results")).intValue() : 20);
                    try {
                        if (STALENESS_CHECK_ENABLED && result.containsKey("staleness_warning")) {
                            result.put("_footer", "\n⚠️ " + result.get("staleness_warning")
                                + " Use javaducker_index_file to refresh.");
                        }
                    } catch (Exception ignored) { }
                    return result;
                }))
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
            .tool(
                tool("javaducker_summarize",
                    "Get a structural summary of an indexed file: class names, method names, imports, " +
                    "line count. One-call overview without reading the full text.",
                    schema(props(
                        "artifact_id", str("Artifact ID to summarize")),
                        "artifact_id")),
                (ex, a) -> call(() -> {
                    String artifactId = (String) a.get("artifact_id");
                    Map<String, Object> summary = summarize(artifactId);
                    if (STALENESS_CHECK_ENABLED) {
                        try {
                            Map<String, Object> status = getArtifactStatus(artifactId);
                            String path = (String) status.get("original_client_path");
                            if (path != null && !path.isBlank()) {
                                Map<String, Object> staleness = httpPost("/stale", Map.of("file_paths", List.of(path)));
                                List<?> staleList = (List<?>) staleness.get("stale");
                                if (staleList != null && !staleList.isEmpty()) {
                                    summary.put("_warning", "⚠️ This file has changed since indexing — summary may be outdated.");
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                    return summary;
                }))
            .tool(
                tool("javaducker_map",
                    "Get a project map showing directory structure, file counts, largest files, and " +
                    "recently indexed files. Use for codebase orientation.",
                    "{}"),
                (ex, a) -> call(JavaDuckerMcpServer::projectMap))
            .tool(
                tool("javaducker_stale",
                    "Check which indexed files are stale (modified on disk since last indexing). " +
                    "Accepts file_paths (list of absolute paths) or git_diff_ref (e.g. HEAD~3) to auto-detect changed files.",
                    schema(props(
                        "file_paths",   str("JSON array of absolute file paths to check (optional if git_diff_ref given)"),
                        "git_diff_ref", str("Git ref for diff, e.g. HEAD~3 or main (optional if file_paths given)")))),
                (ex, a) -> call(() -> checkStale(
                    (String) a.getOrDefault("file_paths", ""),
                    (String) a.getOrDefault("git_diff_ref", ""))))
            .tool(
                tool("javaducker_dependencies",
                    "Get the import/dependency list for an indexed file. Shows what this file imports " +
                    "and which indexed artifacts those imports resolve to.",
                    schema(props(
                        "artifact_id", str("Artifact ID to get dependencies for")),
                        "artifact_id")),
                (ex, a) -> call(() -> dependencies((String) a.get("artifact_id"))))
            .tool(
                tool("javaducker_dependents",
                    "Find which indexed files import/depend on this file. Useful for impact analysis.",
                    schema(props(
                        "artifact_id", str("Artifact ID to find dependents of")),
                        "artifact_id")),
                (ex, a) -> call(() -> dependents((String) a.get("artifact_id"))))
            .tool(
                tool("javaducker_watch",
                    "Start or stop auto-indexing a directory. When watching, file changes are " +
                    "automatically detected and re-indexed. Use action=start with a directory, or action=stop.",
                    schema(props(
                        "action",     str("start or stop"),
                        "directory",  str("Absolute path to watch (required for start)"),
                        "extensions", str("Comma-separated extensions, e.g. .java,.xml,.md (optional)")),
                        "action")),
                (ex, a) -> call(() -> watch(
                    (String) a.get("action"),
                    (String) a.getOrDefault("directory", ""),
                    (String) a.getOrDefault("extensions", ""))))
            // ── Explain tool ─────────────────────────────────────────────
            .tool(tool("javaducker_explain",
                    "Get everything JavaDucker knows about a file: summary, dependencies, dependents, tags, " +
                    "classification, related plans, blame highlights, and co-change partners. One call for full context.",
                    schema(props("file_path", str("Absolute path to the file to explain")), "file_path")),
                (ex, a) -> call(() -> httpPost("/explain", Map.of("filePath", a.get("file_path")))))
            // ── Git Blame tool ───────────────────────────────────────────
            .tool(tool("javaducker_blame",
                    "Show who last changed each line of a file, with commit info. Groups consecutive lines by same commit. Optionally narrow to a line range.",
                    schema(props(
                        "file_path", str("Absolute path to the file"),
                        "start_line", intParam("Start line number (optional)"),
                        "end_line", intParam("End line number (optional)")),
                        "file_path")),
                (ex, a) -> call(() -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("filePath", a.get("file_path"));
                    if (a.containsKey("start_line")) body.put("startLine", ((Number) a.get("start_line")).intValue());
                    if (a.containsKey("end_line")) body.put("endLine", ((Number) a.get("end_line")).intValue());
                    return httpPost("/blame", body);
                }))
            // ── Co-Change / Related Files tool ─────────────────────────
            .tool(tool("javaducker_related",
                    "Find files commonly edited together with this file, based on git co-change history. " +
                    "Helps identify related files you might need to update.",
                    schema(props(
                        "file_path", str("Absolute path to the file"),
                        "max_results", intParam("Max results (default 10)")),
                        "file_path")),
                (ex, a) -> call(() -> httpPost("/related", Map.of(
                    "filePath", a.get("file_path"),
                    "maxResults", ((Number) a.getOrDefault("max_results", 10)).intValue()))))
            // ── Content Intelligence: write tools ────────────────────────
            .tool(tool("javaducker_classify",
                    "Classify an artifact by doc type (ADR, DESIGN_DOC, PLAN, MEETING_NOTES, THREAD, SCRATCH, CODE, REFERENCE, TICKET).",
                    schema(props("artifact_id", str("Artifact ID"), "doc_type", str("Document type"),
                        "confidence", intParam("Confidence 0-1 (default 1)"), "method", str("Classification method (default llm)")),
                        "artifact_id", "doc_type")),
                (ex, a) -> call(() -> httpPost("/classify", Map.of(
                    "artifactId", a.get("artifact_id"), "docType", a.get("doc_type"),
                    "confidence", a.getOrDefault("confidence", 1.0), "method", a.getOrDefault("method", "llm")))))
            .tool(tool("javaducker_tag",
                    "Add tags to an artifact. Replaces existing tags.",
                    schema(props("artifact_id", str("Artifact ID"), "tags", str("JSON array of {tag, tag_type, source} objects")),
                        "artifact_id", "tags")),
                (ex, a) -> call(() -> {
                    List<Map<String, String>> tags = MAPPER.readValue((String) a.get("tags"), new TypeReference<>() {});
                    return httpPost("/tag", Map.of("artifactId", a.get("artifact_id"), "tags", tags));
                }))
            .tool(tool("javaducker_extract_points",
                    "Write salient points for an artifact: DECISION, IDEA, QUESTION, ACTION, RISK, INSIGHT, CONSTRAINT, STATUS.",
                    schema(props("artifact_id", str("Artifact ID"), "points", str("JSON array of {point_type, point_text} objects")),
                        "artifact_id", "points")),
                (ex, a) -> call(() -> {
                    List<Map<String, String>> points = MAPPER.readValue((String) a.get("points"), new TypeReference<>() {});
                    return httpPost("/salient-points", Map.of("artifactId", a.get("artifact_id"), "points", points));
                }))
            .tool(tool("javaducker_set_freshness",
                    "Mark an artifact as current, stale, or superseded.",
                    schema(props("artifact_id", str("Artifact ID"), "freshness", str("current, stale, or superseded"),
                        "superseded_by", str("Artifact ID that supersedes this one (optional)")),
                        "artifact_id", "freshness")),
                (ex, a) -> call(() -> httpPost("/freshness", Map.of(
                    "artifactId", a.get("artifact_id"), "freshness", a.get("freshness"),
                    "supersededBy", a.getOrDefault("superseded_by", "")))))
            .tool(tool("javaducker_synthesize",
                    "Write a synthesis record and prune full text/embeddings. Only works on stale/superseded artifacts.",
                    schema(props("artifact_id", str("Artifact ID"), "summary_text", str("Compact summary"),
                        "tags", str("Comma-separated tags"), "key_points", str("Key points"),
                        "outcome", str("Outcome/resolution"), "original_file_path", str("Path to original file on disk")),
                        "artifact_id", "summary_text")),
                (ex, a) -> call(() -> httpPost("/synthesize", Map.of(
                    "artifactId", a.get("artifact_id"), "summaryText", a.get("summary_text"),
                    "tags", a.getOrDefault("tags", ""), "keyPoints", a.getOrDefault("key_points", ""),
                    "outcome", a.getOrDefault("outcome", ""), "originalFilePath", a.getOrDefault("original_file_path", "")))))
            .tool(tool("javaducker_link_concepts",
                    "Create cross-document concept links.",
                    schema(props("links", str("JSON array of {concept, artifact_a, artifact_b, strength} objects")),
                        "links")),
                (ex, a) -> call(() -> {
                    List<Map<String, Object>> links = MAPPER.readValue((String) a.get("links"), new TypeReference<>() {});
                    return httpPost("/link-concepts", Map.of("links", links));
                }))
            .tool(tool("javaducker_enrich_queue",
                    "List artifacts queued for enrichment (INDEXED but not yet ENRICHED).",
                    schema(props("limit", intParam("Max results (default 50)")))),
                (ex, a) -> call(() -> httpGet("/enrich-queue?limit=" + ((Number) a.getOrDefault("limit", 50)).intValue())))
            .tool(tool("javaducker_mark_enriched",
                    "Mark an artifact as ENRICHED after post-processing is complete.",
                    schema(props("artifact_id", str("Artifact ID")), "artifact_id")),
                (ex, a) -> call(() -> httpPost("/mark-enriched", Map.of("artifactId", a.get("artifact_id")))))
            // ── Content Intelligence: read tools ─────────────────────────
            .tool(tool("javaducker_latest",
                    "Get the most recent, non-superseded artifact on a topic — the 'current truth'.",
                    schema(props("topic", str("Topic to search for")), "topic")),
                (ex, a) -> call(() -> httpGet("/latest?topic=" + encode((String) a.get("topic")))))
            .tool(tool("javaducker_find_by_type",
                    "Find artifacts by document type (ADR, PLAN, DESIGN_DOC, etc.).",
                    schema(props("doc_type", str("Document type to search for")), "doc_type")),
                (ex, a) -> call(() -> httpGet("/find-by-type?docType=" + encode((String) a.get("doc_type")))))
            .tool(tool("javaducker_find_by_tag",
                    "Find artifacts matching a tag.",
                    schema(props("tag", str("Tag to search for")), "tag")),
                (ex, a) -> call(() -> httpGet("/find-by-tag?tag=" + encode((String) a.get("tag")))))
            .tool(tool("javaducker_find_points",
                    "Search salient points by type (DECISION, RISK, ACTION, etc.) across all documents.",
                    schema(props("point_type", str("Point type: DECISION, IDEA, QUESTION, ACTION, RISK, INSIGHT, CONSTRAINT, STATUS"),
                        "tag", str("Optional tag filter")), "point_type")),
                (ex, a) -> call(() -> httpGet("/find-points?pointType=" + encode((String) a.get("point_type"))
                    + (a.containsKey("tag") ? "&tag=" + encode((String) a.get("tag")) : ""))))
            .tool(tool("javaducker_concepts",
                    "List all concepts across the corpus with mention counts and doc counts.",
                    "{}"),
                (ex, a) -> call(() -> httpGet("/concepts")))
            .tool(tool("javaducker_concept_timeline",
                    "Show the evolution of a concept: all related docs ordered by time with freshness status.",
                    schema(props("concept", str("Concept name")), "concept")),
                (ex, a) -> call(() -> httpGet("/concept-timeline/" + encode((String) a.get("concept")))))
            .tool(tool("javaducker_stale_content",
                    "List artifacts flagged as stale or superseded, with what replaced them.",
                    "{}"),
                (ex, a) -> call(() -> httpGet("/stale-content")))
            .tool(tool("javaducker_synthesis",
                    "Retrieve synthesis records for pruned artifacts (summary + file path). Provide artifact_id for a specific record or keyword to search.",
                    schema(props("artifact_id", str("Artifact ID (optional)"),
                        "keyword", str("Search keyword (optional)")))),
                (ex, a) -> call(() -> {
                    if (a.containsKey("artifact_id")) return httpGet("/synthesis/" + a.get("artifact_id"));
                    if (a.containsKey("keyword")) return httpGet("/synthesis/search?keyword=" + encode((String) a.get("keyword")));
                    return Map.of("error", "Provide artifact_id or keyword");
                }))
            .tool(tool("javaducker_concept_health",
                    "Health report for all concepts: active/stale doc counts, trend (active/fading/cold).",
                    "{}"),
                (ex, a) -> call(() -> httpGet("/concept-health")))
            // ── Index Health tool ────────────────────────────────────────
            .tool(tool("javaducker_index_health",
                    "Check index health: how many files are current vs stale. Returns actionable recommendation. " +
                    "No parameters required — scans all indexed files.",
                    "{}"),
                (ex, a) -> call(JavaDuckerMcpServer::indexHealth))
            // ── Reladomo tools ───────────────────────────────────────────
            .tool(tool("javaducker_reladomo_relationships",
                    "Get a Reladomo object's attributes, relationships, and metadata in one call.",
                    schema(props("object_name", str("Reladomo object name, e.g. Order")), "object_name")),
                (ex, a) -> call(() -> httpGet("/reladomo/relationships/" + a.get("object_name"))))
            .tool(tool("javaducker_reladomo_graph",
                    "Traverse the Reladomo relationship graph from a root object up to N hops.",
                    schema(props("object_name", str("Root object name"), "depth", intParam("Max depth (default 3)")), "object_name")),
                (ex, a) -> call(() -> httpGet("/reladomo/graph/" + a.get("object_name") + "?depth=" + ((Number) a.getOrDefault("depth", 3)).intValue())))
            .tool(tool("javaducker_reladomo_path",
                    "Find the shortest relationship path between two Reladomo objects.",
                    schema(props("from_object", str("Source object"), "to_object", str("Target object")), "from_object", "to_object")),
                (ex, a) -> call(() -> httpGet("/reladomo/path?from=" + a.get("from_object") + "&to=" + a.get("to_object"))))
            .tool(tool("javaducker_reladomo_schema",
                    "Derive SQL DDL from a Reladomo object: column types, PK, temporal columns, indices.",
                    schema(props("object_name", str("Reladomo object name")), "object_name")),
                (ex, a) -> call(() -> httpGet("/reladomo/schema/" + a.get("object_name"))))
            .tool(tool("javaducker_reladomo_object_files",
                    "List all files for a Reladomo object grouped by type (generated, hand-written, xml, config).",
                    schema(props("object_name", str("Reladomo object name")), "object_name")),
                (ex, a) -> call(() -> httpGet("/reladomo/files/" + a.get("object_name"))))
            .tool(tool("javaducker_reladomo_finders",
                    "Show Finder query patterns for a Reladomo object, ranked by frequency with locations.",
                    schema(props("object_name", str("Reladomo object name")), "object_name")),
                (ex, a) -> call(() -> httpGet("/reladomo/finders/" + a.get("object_name"))))
            .tool(tool("javaducker_reladomo_deepfetch",
                    "Show deep fetch profiles — which relationships are eagerly loaded together.",
                    schema(props("object_name", str("Reladomo object name")), "object_name")),
                (ex, a) -> call(() -> httpGet("/reladomo/deepfetch/" + a.get("object_name"))))
            .tool(tool("javaducker_reladomo_temporal",
                    "Temporal classification of all Reladomo objects with column info and query patterns.", "{}"),
                (ex, a) -> call(() -> httpGet("/reladomo/temporal")))
            .tool(tool("javaducker_reladomo_config",
                    "Runtime config for a Reladomo object: DB connection, cache strategy. Omit name for full topology.",
                    schema(props("object_name", str("Object name (optional)")))),
                (ex, a) -> call(() -> httpGet("/reladomo/config" + (a.containsKey("object_name") ? "?objectName=" + a.get("object_name") : ""))))
            // ── Session Transcript tools ─────────────────────────────────
            .tool(tool("javaducker_index_sessions",
                    "Index Claude Code session transcripts from a project directory. Makes past conversations searchable.",
                    schema(props(
                        "project_path", str("Path to project sessions directory (e.g. ~/.claude/projects/<hash>/)"),
                        "max_sessions", intParam("Max sessions to index (default: all)"),
                        "incremental", str("true to skip unchanged files (default: false)")),
                        "project_path")),
                (ex, a) -> call(() -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("projectPath", a.get("project_path"));
                    if (a.containsKey("max_sessions")) body.put("maxSessions", ((Number) a.get("max_sessions")).intValue());
                    if ("true".equals(a.get("incremental"))) body.put("incremental", true);
                    return httpPost("/index-sessions", body);
                }))
            .tool(tool("javaducker_search_sessions",
                    "Search past Claude Code conversations. Returns matching excerpts with session ID and role.",
                    schema(props(
                        "phrase", str("Search phrase"),
                        "max_results", intParam("Max results (default 20)")),
                        "phrase")),
                (ex, a) -> call(() -> httpPost("/search-sessions", Map.of(
                    "phrase", a.get("phrase"),
                    "max_results", ((Number) a.getOrDefault("max_results", 20)).intValue()))))
            .tool(tool("javaducker_session_context",
                    "Get full historical context for a topic: session excerpts + related artifacts. One call for complete history.",
                    schema(props("topic", str("Topic or query to search for")), "topic")),
                (ex, a) -> call(() -> sessionContext((String) a.get("topic"))))
            // ── Session Decision tools ──────────────────────────────────
            .tool(tool("javaducker_extract_decisions",
                    "Store decisions extracted from a session. Claude calls this after reading a session to record key decisions.",
                    schema(props(
                        "session_id", str("Session ID"),
                        "decisions", str("JSON array of {text, context?, tags?} objects")),
                        "session_id", "decisions")),
                (ex, a) -> call(() -> {
                    List<Map<String, String>> decisions = MAPPER.readValue((String) a.get("decisions"), new TypeReference<>() {});
                    return httpPost("/extract-session-decisions", Map.of("sessionId", a.get("session_id"), "decisions", decisions));
                }))
            .tool(tool("javaducker_recent_decisions",
                    "List recent decisions from past sessions, optionally filtered by tag.",
                    schema(props(
                        "max_sessions", intParam("Max sessions to look back (default 5)"),
                        "tag", str("Optional tag filter")))),
                (ex, a) -> call(() -> httpGet("/session-decisions?maxSessions=" +
                    ((Number) a.getOrDefault("max_sessions", 5)).intValue() +
                    (a.containsKey("tag") ? "&tag=" + encode((String) a.get("tag")) : ""))))
            .build();
    }

    // ── Tool implementations ──────────────────────────────────────────────────

    static Map<String, Object> sessionContext(String topic) throws Exception {
        Map<String, Object> sessionHits = httpPost("/search-sessions", Map.of("phrase", topic, "max_results", 5));
        Map<String, Object> artifactHits = search(topic, "hybrid", 5);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", topic);
        result.put("session_excerpts", sessionHits.get("results"));
        result.put("related_artifacts", artifactHits.get("results"));
        return result;
    }

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

    static Map<String, Object> summarize(String artifactId) throws Exception {
        Map<String, Object> r = httpGet("/summary/" + artifactId);
        if (r == null) throw new RuntimeException("Artifact not found or no summary available: " + artifactId);
        return r;
    }

    static Map<String, Object> projectMap() throws Exception {
        return httpGet("/map");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> checkStale(String filePathsJson, String gitDiffRef) throws Exception {
        List<String> paths = new ArrayList<>();

        // If git_diff_ref is given, run git diff to get file paths
        if (gitDiffRef != null && !gitDiffRef.isBlank()) {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", gitDiffRef);
            pb.directory(Path.of(PROJECT_ROOT).toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (!output.isEmpty()) {
                Path root = Path.of(PROJECT_ROOT).toAbsolutePath();
                for (String line : output.split("\n")) {
                    paths.add(root.resolve(line.trim()).toString());
                }
            }
        }

        // If file_paths is given, parse it
        if (filePathsJson != null && !filePathsJson.isBlank()) {
            try {
                List<String> parsed = MAPPER.readValue(filePathsJson, List.class);
                paths.addAll(parsed);
            } catch (Exception e) {
                // Try as comma-separated
                for (String p : filePathsJson.split(",")) {
                    if (!p.isBlank()) paths.add(p.trim());
                }
            }
        }

        if (paths.isEmpty()) {
            throw new RuntimeException("Provide file_paths or git_diff_ref");
        }

        return httpPost("/stale", Map.of("file_paths", paths));
    }

    static Map<String, Object> watch(String action, String directory, String extensions) throws Exception {
        if ("stop".equalsIgnoreCase(action)) {
            return httpPost("/watch/stop", Map.of());
        }
        if ("start".equalsIgnoreCase(action)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("directory", directory);
            if (extensions != null && !extensions.isBlank()) body.put("extensions", extensions);
            return httpPost("/watch/start", body);
        }
        if ("status".equalsIgnoreCase(action)) {
            return httpGet("/watch/status");
        }
        throw new RuntimeException("Unknown action: " + action + ". Use start, stop, or status.");
    }

    static Map<String, Object> dependencies(String artifactId) throws Exception {
        Map<String, Object> r = httpGet("/dependencies/" + artifactId);
        if (r == null) throw new RuntimeException("Artifact not found: " + artifactId);
        return r;
    }

    static Map<String, Object> dependents(String artifactId) throws Exception {
        Map<String, Object> r = httpGet("/dependents/" + artifactId);
        if (r == null) throw new RuntimeException("Artifact not found: " + artifactId);
        return r;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> indexHealth() throws Exception {
        Map<String, Object> summary = httpGet("/stale/summary");
        int staleCount = ((Number) summary.getOrDefault("stale_count", 0)).intValue();
        double stalePercent = ((Number) summary.getOrDefault("stale_percentage", 0.0)).doubleValue();
        long total = ((Number) summary.getOrDefault("total_checked", 0)).longValue();

        String recommendation;
        if (staleCount == 0) {
            recommendation = "All " + total + " indexed files are current. No action needed.";
        } else if (stalePercent > 10) {
            recommendation = "More than 10% of indexed files are stale (" + staleCount + "/" + total
                    + "). Consider running a full re-index with javaducker_index_directory.";
        } else {
            List<Map<String, Object>> staleFiles = (List<Map<String, Object>>) summary.get("stale");
            List<String> paths = staleFiles != null
                    ? staleFiles.stream().limit(5)
                        .map(f -> (String) f.get("original_client_path"))
                        .filter(Objects::nonNull).toList()
                    : List.of();
            recommendation = staleCount + " file(s) are stale. Re-index them with javaducker_index_file: " + paths;
        }
        summary.put("recommendation", recommendation);
        summary.put("health_status", stalePercent > 10 ? "degraded" : "healthy");
        return summary;
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

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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
