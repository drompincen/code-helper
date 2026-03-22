# Plan: Replace gRPC with REST

## Motivation

gRPC channel drops under rapid sequential upload load (observed at ~1400 files: "UNAVAILABLE io exception"). REST over plain HTTP is simpler, more resilient, and removes the ARM64/protoc compilation complexity. All service logic stays intact — only the transport layer changes.

## REST API Design

```
GET    /api/health
POST   /api/upload          (multipart/form-data: file + metadata fields)
GET    /api/status/{id}
GET    /api/text/{id}
POST   /api/search          (JSON body)
GET    /api/stats
```

Port: `8080` (Spring Boot default, replaces gRPC port `9090`)

---

## Parallel Agent Assignments

Each agent works on independent files. Agents 1–3 can run in parallel.
Agent 4 runs after 1–3 complete (it verifies the build and updates docs).

---

### Agent 1 — Server side
**Files:** `pom.xml`, `application.yml`, `JavaDuckerGrpcService.java` (replace), `JavaDuckerServerApp.java` (review)

#### pom.xml changes
- Remove properties: `grpc.version`, `protobuf.version`, `grpc-spring-boot.version`
- Remove dependencies:
  - `grpc-server-spring-boot-starter`
  - `grpc-netty-shaded`
  - `grpc-protobuf`
  - `grpc-stub`
  - `protobuf-java`
  - `javax.annotation-api`
- Add dependency (already in spring-boot-starter-parent, just needs web):
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  ```
- Remove from `<build><extensions>`: `os-maven-plugin`
- Remove `<plugin>`: `protobuf-maven-plugin` entirely
- Remove the `windows-aarch64` profile (no longer needed without protoc)
- Keep: picocli, pdfbox, duckdb, spring-boot-starter-test, spring-boot-maven-plugin, maven-dependency-plugin

#### application.yml changes
Replace:
```yaml
grpc:
  server:
    port: 9090
```
With:
```yaml
server:
  port: 8080
```

#### New file: `src/main/java/com/javaducker/server/rest/JavaDuckerRestController.java`
Replace `JavaDuckerGrpcService.java` with this REST controller.
The class name changes; the package changes from `grpc` to `rest`.

```java
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
```

#### Delete `JavaDuckerGrpcService.java`
File to delete: `src/main/java/com/javaducker/server/grpc/JavaDuckerGrpcService.java`
The `grpc/` package directory becomes empty and can also be removed.

#### Delete proto file
File to delete: `src/main/proto/javaducker.proto`
The `src/main/proto/` directory becomes empty and can be removed.

#### `JavaDuckerServerApp.java` — review only
Likely no changes needed. It is a plain `@SpringBootApplication` with no gRPC imports. Verify and leave as-is.

---

### Agent 2 — MCP server
**File:** `JavaDuckerMcpServer.java` (root of repo)

Replace all gRPC/protobuf usage with `java.net.http.HttpClient` calls against `http://HOST:PORT/api/...`.

#### Header changes
Remove `//DEPS` for:
- `io.modelcontextprotocol.sdk:mcp` — keep this
- `io.grpc:grpc-netty-shaded` — remove
- `io.grpc:grpc-protobuf` — remove
- `io.grpc:grpc-stub` — remove
- `com.google.protobuf:protobuf-java` — remove
- `javax.annotation:javax.annotation-api` — remove
- `//CP target/classes` — remove (no more generated proto classes)

Keep:
```
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS io.modelcontextprotocol.sdk:mcp:1.1.0
//DEPS org.slf4j:slf4j-nop:2.0.16
```

#### Field changes
Replace:
```java
static final int PORT = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "9090"));
static ManagedChannel channel;
static JavaDuckerGrpc.JavaDuckerBlockingStub stub;
```
With:
```java
static final int PORT = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8080"));
static final String BASE_URL = "http://" + HOST + ":" + PORT + "/api";
static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newHttpClient();
static final ObjectMapper MAPPER = new ObjectMapper();
```

#### `main()` changes
Remove channel creation and shutdown hook. The `ensureServerRunning()` call stays (it checks `isHealthy()` via socket). Replace `McpServer.sync(...)` build — the tool wiring stays the same; only implementations change.

#### Helper: HTTP GET/POST
Add two private helpers:
```java
static Map<String, Object> httpGet(String path) throws Exception {
    var req = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(BASE_URL + path))
        .GET().build();
    var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 404) return null;
    if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
    return MAPPER.readValue(resp.body(), new com.fasterxml.jackson.core.type.TypeReference<>(){});
}

static Map<String, Object> httpPost(String path, Object body) throws Exception {
    String json = MAPPER.writeValueAsString(body);
    var req = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(BASE_URL + path))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
        .build();
    var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
    return MAPPER.readValue(resp.body(), new com.fasterxml.jackson.core.type.TypeReference<>(){});
}

static Map<String, Object> httpUpload(Path path) throws Exception {
    byte[] content = java.nio.file.Files.readAllBytes(path);
    String boundary = "----JavaDuckerBoundary" + System.currentTimeMillis();
    String fileName = path.getFileName().toString();
    String mediaType = java.nio.file.Files.probeContentType(path);
    if (mediaType == null) mediaType = "application/octet-stream";

    // Build multipart body manually
    byte[] CRLF = "\r\n".getBytes();
    var baos = new java.io.ByteArrayOutputStream();
    // file part
    baos.write(("--" + boundary + "\r\n").getBytes());
    baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
    baos.write(("Content-Type: " + mediaType + "\r\n\r\n").getBytes());
    baos.write(content);
    baos.write(CRLF);
    // originalClientPath part
    baos.write(("--" + boundary + "\r\n").getBytes());
    baos.write("Content-Disposition: form-data; name=\"originalClientPath\"\r\n\r\n".getBytes());
    baos.write(path.toAbsolutePath().toString().getBytes());
    baos.write(CRLF);
    // closing boundary
    baos.write(("--" + boundary + "--\r\n").getBytes());

    var req = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(BASE_URL + "/upload"))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
        .build();
    var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
    return MAPPER.readValue(resp.body(), new com.fasterxml.jackson.core.type.TypeReference<>(){});
}
```

#### Tool implementation rewrites

```java
static Map<String, Object> health() throws Exception {
    return httpGet("/health");
}

static Map<String, Object> indexFile(String filePath) throws Exception {
    return httpUpload(Path.of(filePath));
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

static Map<String, Object> stats() throws Exception {
    return httpGet("/stats");
}
```

`indexDirectory()` — calls `httpUpload()` per file instead of the gRPC stub. Same exclusion logic (EXCLUDED_DIRS) stays.

`waitForIndexed()` — calls `getArtifactStatus()` in loop; same logic, no gRPC.

#### `isHealthy()` — no change needed
Already uses raw TCP socket; works for HTTP port too.

#### run-server script reference
Update port env var name in the `ensureServerRunning()` script path reference:
`WINDOWS ? "run-server.cmd" : "run-server.sh"` — no change, but the script itself must be updated (Agent 4).

---

### Agent 3 — CLI client
**File:** `src/main/java/com/javaducker/client/JavaDuckerClient.java`

Replace all gRPC imports and stub usage with `java.net.http.HttpClient`.

#### Import changes
Remove:
```java
import com.javaducker.proto.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
```
Add:
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
```

#### Top-level class changes
- Change `--port` default from `9090` to `8080`, description from "gRPC port" to "HTTP port"
- Replace `createStub()` / `shutdownChannel()` with:
```java
static final ObjectMapper MAPPER = new ObjectMapper();

static String baseUrl(JavaDuckerClient p) {
    return "http://" + p.host + ":" + p.port + "/api";
}

static HttpClient http() { return HttpClient.newHttpClient(); }

static Map<String, Object> get(String url) throws Exception {
    var resp = http().send(
        HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 404) throw new RuntimeException("Not found");
    if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
    return MAPPER.readValue(resp.body(), new TypeReference<>(){});
}

static Map<String, Object> post(String url, Object body) throws Exception {
    String json = MAPPER.writeValueAsString(body);
    var resp = http().send(
        HttpRequest.newBuilder().uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
    return MAPPER.readValue(resp.body(), new TypeReference<>(){});
}

static Map<String, Object> upload(String url, Path path) throws Exception {
    byte[] content = Files.readAllBytes(path);
    String mediaType = Files.probeContentType(path);
    if (mediaType == null) mediaType = "application/octet-stream";
    String fileName = path.getFileName().toString();
    String boundary = "----JavaDuckerBoundary" + System.currentTimeMillis();

    var baos = new java.io.ByteArrayOutputStream();
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
    if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
    return MAPPER.readValue(resp.body(), new TypeReference<>(){});
}
```

#### Subcommand rewrites

Each subcommand's `run()` method changes from `stub.xxx()` to the appropriate HTTP helper.
The printed output format stays the same — only the data source changes.

**HealthCmd:**
```java
Map<String, Object> resp = get(baseUrl(parent) + "/health");
System.out.println("Status:  " + resp.get("status"));
System.out.println("Version: " + resp.get("version"));
```

**UploadFileCmd:**
```java
Map<String, Object> resp = upload(baseUrl(parent) + "/upload", path);
System.out.println("Artifact ID: " + resp.get("artifact_id"));
System.out.println("Status:      " + resp.get("status"));
```

**UploadDirCmd:** call `upload(baseUrl(parent) + "/upload", file)` per file. Remove `shutdownChannel`.

**FindCmd:**
```java
Map<String, Object> resp = post(baseUrl(parent) + "/search",
    Map.of("phrase", phrase, "mode", mode, "max_results", maxResults));
// resp has "total_results" (int) and "results" (List<Map>)
// each result: artifact_id, file_name, chunk_index, preview, score, match_type
```
Note: the server's `SearchService` returns `file_name` as the key (not `fileName`). Print using those keys.

**CatCmd:**
```java
Map<String, Object> resp = get(baseUrl(parent) + "/text/" + artifactId);
System.out.println("Artifact:   " + resp.get("artifact_id"));
System.out.println("Method:     " + resp.get("extraction_method"));
System.out.println("Length:     " + resp.get("text_length"));
System.out.println("---");
System.out.println(resp.get("extracted_text"));
```

**StatusCmd:**
```java
Map<String, Object> resp = get(baseUrl(parent) + "/status/" + artifactId);
// print artifact_id, file_name, status, error_message, created_at, updated_at, indexed_at
```

**StatsCmd:**
```java
Map<String, Object> resp = get(baseUrl(parent) + "/stats");
// keys: total_artifacts, indexed_artifacts, failed_artifacts, pending_artifacts, total_chunks, total_bytes, artifacts_by_status
```

---

### Agent 4 — Tests, scripts, docs (runs after 1–3 complete)

#### Tests — verify no gRPC imports remain
Run `grep -r "grpc\|proto\|GrpcService\|StreamObserver" src/test/` — should return nothing.

The existing test files test service classes directly (no gRPC layer):
- `FullFlowIntegrationTest.java` — uses `UploadService`, `ArtifactService`, etc. directly. No changes expected.
- `SchemaBootstrapTest.java` — no gRPC. No changes.
- `IngestionWorkerParallelTest.java` — no gRPC. No changes.
- `TextExtractorTest.java`, `ChunkerTest.java`, `EmbeddingServiceTest.java`, `TextNormalizerTest.java`, `SearchServiceTest.java` — no gRPC. No changes.

If any test imports `com.javaducker.proto.*` — remove those imports and update accordingly.

#### Add REST controller smoke test
New file: `src/test/java/com/javaducker/server/rest/JavaDuckerRestControllerTest.java`

Use `@WebMvcTest(JavaDuckerRestController.class)` with mocked services to verify:
- `GET /api/health` → 200 with `{"status":"OK","version":"2.0.0"}`
- `GET /api/status/{id}` with null return → 404
- `GET /api/status/{id}` with valid return → 200
- `POST /api/search` → 200 with results list

#### run-server.cmd
Change:
```cmd
if "%GRPC_PORT%"=="" set GRPC_PORT=9090
...
--grpc.server.port=%GRPC_PORT% ^
```
To:
```cmd
if "%HTTP_PORT%"=="" set HTTP_PORT=8080
...
--server.port=%HTTP_PORT% ^
```

#### run-server.sh
Change:
```sh
GRPC_PORT="${GRPC_PORT:-9090}"
...
--grpc.server.port=$GRPC_PORT
```
To:
```sh
HTTP_PORT="${HTTP_PORT:-8080}"
...
--server.port=$HTTP_PORT
```

#### README.md
Update all references:
- Port: `9090` → `8080`, "gRPC port" → "HTTP port"
- MCP setup: remove `GRPC_PORT` env var, add `HTTP_PORT` env var (optional, defaults to 8080)
- Configuration table: remove `grpc.server.port`, add `server.port`
- Add REST API section listing the 6 endpoints
- Update "How it works" diagram if present (gRPC → REST)

#### Run `mvn test`
After all edits, run `mvn test` and verify all tests pass. Fix any compilation errors.

Expected: tests that previously needed generated proto classes (`target/classes` on classpath) now compile cleanly without them.

---

## File Change Summary

| File | Agent | Action |
|------|-------|--------|
| `pom.xml` | 1 | Remove gRPC/protobuf deps + plugins; add spring-boot-starter-web |
| `src/main/resources/application.yml` | 1 | Replace grpc port config with server.port |
| `src/main/java/.../grpc/JavaDuckerGrpcService.java` | 1 | Delete |
| `src/main/java/.../rest/JavaDuckerRestController.java` | 1 | Create new |
| `src/main/proto/javaducker.proto` | 1 | Delete |
| `JavaDuckerMcpServer.java` | 2 | Replace gRPC with HttpClient |
| `src/main/java/.../client/JavaDuckerClient.java` | 3 | Replace gRPC with HttpClient |
| `run-server.cmd` | 4 | GRPC_PORT → HTTP_PORT, 9090 → 8080 |
| `run-server.sh` | 4 | GRPC_PORT → HTTP_PORT, 9090 → 8080 |
| `README.md` | 4 | Update ports, add REST API section |
| `src/test/.../rest/JavaDuckerRestControllerTest.java` | 4 | Create new smoke test |

## Verification Checklist

- [ ] `mvn test` passes with zero failures
- [ ] No `import com.javaducker.proto` anywhere in `src/`
- [ ] No `import io.grpc` anywhere in `src/`
- [ ] `GET http://localhost:8080/api/health` returns `{"status":"OK","version":"2.0.0"}`
- [ ] MCP tool `javaducker_health` returns OK via the new HTTP transport
- [ ] MCP tool `javaducker_index_directory` completes without channel drops
- [ ] CLI `javaducker health` works against port 8080
- [ ] `run-server.cmd` starts the server with `--server.port=8080`
