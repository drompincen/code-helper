# Plan: JavaDucker Interactive CLI UI

## Vision

A rich terminal UI similar to Claude Code — launches an interactive REPL shell with slash commands,
live progress bars during indexing, colored search results, tab-completion, and command history.
Two modes: **interactive** (no args = REPL loop) and **one-shot** (args passed = single command, existing behavior).

## Technology Stack

| Library | Version | Purpose |
|---------|---------|---------|
| JLine3 | 3.26.3 | Readline-style input: history, tab-complete, ANSI colors, terminal sizing |
| picocli | 4.7.5 (already in pom.xml) | Command parsing in both modes |
| Jackson | (already via spring-boot) | JSON from REST API |

JLine3 is the de-facto standard for Java interactive CLIs (used by Maven, Groovy Shell, Spring Shell, Micronaut CLI). It handles: arrow-key history, Ctrl+R search, tab-complete, ANSI codes on Windows/Linux/macOS, and terminal resize.

## UX Design

### Startup
```
╔══════════════════════════════════════════════════════╗
║         JavaDucker  v2.0.0  — Code Search Engine     ║
╚══════════════════════════════════════════════════════╝
  Connected to http://localhost:8080  ✓
  Index: 1,243 files  |  18,492 chunks  |  3 pending

Type /help for commands, Tab to autocomplete, Ctrl+C to exit.
jd> _
```

### Slash commands
```
/help                    Show all commands
/index <path>            Index a file or directory (with live progress)
/search <phrase>         Search (hybrid mode by default)
/search --mode exact <phrase>
/search --mode semantic <phrase>
/stats                   Show live stats panel
/status <artifact_id>    Check indexing status of one artifact
/cat <artifact_id>       Show full extracted text
/watch                   Live-refresh stats every 2s until Ctrl+C
/clear                   Clear screen
/exit  (or Ctrl+D)       Quit
```

### /index live progress
```
jd> /index /home/user/my-project

  Scanning directory...  found 1,406 source files

  Uploading  [████████████████████░░░░░░░] 1,124 / 1,406  (79%)   42 files/s
  Indexing   [████████████░░░░░░░░░░░░░░░]   891 / 1,124  (79%)   queued: 233  failed: 0

  Elapsed: 0:32   ETA: ~0:08
```

### /search output
```
jd> /search withConnection

  6 results for "withConnection"  [hybrid]

  #1  HYBRID  score=0.94
  ┌─ DuckDBDataSource.java  chunk 2
  │  ...public synchronized <T> T withConnection(ConnectionWork<T> work)
  │  throws SQLException { if (sharedConnection == null || ...
  └─ artifact: a3b1f2d4   /open a3b1f2d4

  #2  EXACT  score=0.87
  ┌─ IngestionWorker.java  chunk 5
  │  ...dataSource.withConnection(conn -> { try (PreparedStatement ps...
  └─ artifact: 9f2c1e88   /open 9f2c1e88
```

### /stats panel
```
jd> /stats

  ┌─────────────────────────────────────────────────┐
  │  JavaDucker Index Stats                          │
  ├─────────────────────────────────────────────────┤
  │  Total artifacts   1,243                         │
  │  Indexed           1,198  ████████████████░  96% │
  │  Pending              42  ░░░░░░░░░░░░░░░░░   3% │
  │  Failed                3  ░░░░░░░░░░░░░░░░░  <1% │
  │  Total chunks     18,492                         │
  │  Total bytes      124 MB                         │
  └─────────────────────────────────────────────────┘
```

---

## Architecture

```
src/main/java/com/javaducker/cli/
  InteractiveCli.java          — main entry point, terminal setup, REPL loop
  CommandDispatcher.java       — routes /commands to handlers
  ApiClient.java               — thin HTTP client (GET/POST/upload) shared by all handlers
  commands/
    IndexCommand.java          — /index with live upload + polling progress
    SearchCommand.java         — /search with formatted table output
    StatsCommand.java          — /stats panel + /watch live refresh
    StatusCommand.java         — /status single artifact
    CatCommand.java            — /cat full text
  display/
    ProgressBar.java           — reusable progress bar renderer (uses JLine3 terminal width)
    StatsPanel.java            — formatted stats table
    ResultsFormatter.java      — search result cards with box-drawing chars
    Theme.java                 — ANSI color constants (cyan, green, yellow, red, bold, reset)
```

`InteractiveCli.java` also serves as a drop-in replacement entry point for the existing `JavaDuckerClient` one-shot mode (pass `--host`, `--port`, and a subcommand → delegate to picocli, no REPL).

---

## Parallel Agent Assignments

Agents 1–3 work on independent files simultaneously. Agent 4 integrates and tests.

---

### Agent 1 — Core: InteractiveCli + ApiClient + CommandDispatcher

**Files to create:**
- `src/main/java/com/javaducker/cli/InteractiveCli.java`
- `src/main/java/com/javaducker/cli/ApiClient.java`
- `src/main/java/com/javaducker/cli/CommandDispatcher.java`
- `src/main/java/com/javaducker/cli/display/Theme.java`

**pom.xml** — add JLine3 dependency:
```xml
<dependency>
    <groupId>org.jline</groupId>
    <artifactId>jline</artifactId>
    <version>3.26.3</version>
</dependency>
```

#### `Theme.java`
ANSI color constants used by all display classes:
```java
package com.javaducker.cli.display;
public class Theme {
    public static final String RESET  = "\u001B[0m";
    public static final String BOLD   = "\u001B[1m";
    public static final String DIM    = "\u001B[2m";
    public static final String CYAN   = "\u001B[36m";
    public static final String GREEN  = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String RED    = "\u001B[31m";
    public static final String BLUE   = "\u001B[34m";
    public static final String MAGENTA= "\u001B[35m";
    public static final String WHITE  = "\u001B[97m";

    public static String cyan(String s)   { return CYAN   + s + RESET; }
    public static String green(String s)  { return GREEN  + s + RESET; }
    public static String yellow(String s) { return YELLOW + s + RESET; }
    public static String red(String s)    { return RED    + s + RESET; }
    public static String bold(String s)   { return BOLD   + s + RESET; }
    public static String dim(String s)    { return DIM    + s + RESET; }
}
```

#### `ApiClient.java`
Thin wrapper around `java.net.http.HttpClient`. Mirrors the helpers in `JavaDuckerMcpServer` but as a proper class:
```java
package com.javaducker.cli;

public class ApiClient {
    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port + "/api";
    }

    public Map<String, Object> get(String path) throws Exception { ... }
    public Map<String, Object> post(String path, Object body) throws Exception { ... }
    public Map<String, Object> upload(Path path) throws Exception { ... }  // multipart
    public boolean isReachable() { ... }  // socket check
}
```

#### `CommandDispatcher.java`
Parses a raw input line, dispatches to the appropriate command handler:
```java
public class CommandDispatcher {
    public void dispatch(String line, PrintWriter out) {
        // tokenize: /cmd [args...]
        // delegate to IndexCommand, SearchCommand, etc.
        // unknown command → print error with /help hint
    }
}
```

Supported commands: `/help`, `/index`, `/search`, `/stats`, `/watch`, `/status`, `/cat`, `/clear`, `/exit`

#### `InteractiveCli.java`
```java
public class InteractiveCli {
    public static void main(String[] args) throws Exception {
        // Parse --host / --port from args
        // If extra subcommand args present → one-shot mode (delegate to JavaDuckerClient)
        // Otherwise → interactive REPL

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(new CommandCompleter())   // tab-complete
            .history(new DefaultHistory())
            .variable(LineReader.HISTORY_FILE, Paths.get(System.getProperty("user.home"), ".javaducker_history"))
            .build();

        // Print banner + server status
        printBanner(terminal, client);

        // REPL loop
        while (true) {
            String line;
            try {
                line = reader.readLine(Theme.cyan("jd") + Theme.dim("> ") + " ");
            } catch (UserInterruptException e) { break; }
             catch (EndOfFileException e)      { break; }
            if (line == null || line.isBlank()) continue;
            dispatcher.dispatch(line.trim(), terminal.writer());
        }
    }

    static void printBanner(Terminal t, ApiClient client) {
        // box-drawing banner + connection status + current stats
    }
}
```

#### Tab completion (`CommandCompleter`)
Implements JLine3's `Completer`. Returns:
- `/` as a trigger → complete to all slash commands
- After `/index ` → file path completion (use JLine3's `FileNameCompleter`)
- After `/search ` → no completion (free text)
- After `/status `, `/cat ` → no completion (artifact IDs not feasible to enumerate)

---

### Agent 2 — Index command with live progress

**Files to create:**
- `src/main/java/com/javaducker/cli/commands/IndexCommand.java`
- `src/main/java//com/javaducker/cli/display/ProgressBar.java`

#### `ProgressBar.java`
Renders a single-line updating progress bar using ANSI cursor control.
Does NOT require JLine's full-screen mode — just prints `\r` to update in-place.

```java
public class ProgressBar {
    private final PrintWriter out;
    private final int termWidth;   // from Terminal.getWidth()

    public void render(String label, long done, long total, String extra) {
        // label:  "  Uploading"
        // bar:    [████████████░░░░░░░░░░░░░░░]
        // counts: 1,124 / 1,406 (79%)
        // extra:  "42 files/s"
        // prints with \r at start so it overwrites the previous line
    }

    public void complete(String label, long count) {
        // print final line with checkmark ✓ and newline
    }

    public void clear() { out.print("\r\033[K"); out.flush(); }
}
```

Bar fill character: `█` (U+2588), empty: `░` (U+2591). Width adapts to terminal width.

#### `IndexCommand.java`
Handles `/index <path>` and `/index --ext .java,.xml <path>`.

Algorithm:
1. If path is a file → `client.upload(path)` → print artifact_id → done.
2. If path is a directory:
   a. Walk directory (same EXCLUDED_DIRS logic as MCP server) → collect eligible files.
   b. Print `  Scanning directory... found N source files`
   c. **Upload phase**: iterate files, call `client.upload(file)` per file, collect artifact_ids.
      Track: uploaded count, failed count, start time.
      After each upload, render upload `ProgressBar` with `\r`.
   d. **Index polling phase**: once all uploads done, poll `GET /api/stats` every 2s.
      Track initial pending count vs current. Render two progress bars: upload (complete) + indexing (live).
      Stop when `pending_artifacts == 0` or user presses Ctrl+C.
   e. Print elapsed time + summary line.

```java
public class IndexCommand {
    public void execute(String[] args, Terminal terminal, ApiClient client) {
        // parse: optional --ext flag, required path positional
        // dispatch to uploadFile or uploadDirectory
    }

    private void uploadDirectory(Path root, Set<String> exts, Terminal terminal, ApiClient client) {
        // walk, upload, poll
    }
}
```

---

### Agent 3 — Search, Stats, Status, Cat commands + formatters

**Files to create:**
- `src/main/java/com/javaducker/cli/commands/SearchCommand.java`
- `src/main/java/com/javaducker/cli/commands/StatsCommand.java`
- `src/main/java/com/javaducker/cli/commands/StatusCommand.java`
- `src/main/java/com/javaducker/cli/commands/CatCommand.java`
- `src/main/java/com/javaducker/cli/display/ResultsFormatter.java`
- `src/main/java/com/javaducker/cli/display/StatsPanel.java`

#### `ResultsFormatter.java`
Formats a search result list as "cards" with box-drawing characters.
Each card:
```
  #1  HYBRID  score=0.94
  ┌─ DuckDBDataSource.java  chunk 2
  │  ...preview text here, wrapped to terminal width...
  │  ...continued...
  └─ artifact: a3b1f2d4
```
- `file_name` in bold cyan
- `match_type` colored: EXACT=green, SEMANTIC=blue, HYBRID=yellow
- Score as fixed-width float
- Preview text word-wrapped to `termWidth - 5` chars

#### `StatsPanel.java`
Formats stats as a bordered table with inline mini progress bars:
```
  ┌─────────────────────────────────────────────────┐
  │  JavaDucker Index Stats                          │
  ├─────────────────────────────────────────────────┤
  │  Total artifacts   1,243                         │
  │  Indexed           1,198  ████████████████░  96% │
  │  Pending              42  █░░░░░░░░░░░░░░░░   3% │
  │  Failed                3  ░░░░░░░░░░░░░░░░░  <1% │
  │  Total chunks     18,492                         │
  │  Total bytes      124 MB                         │
  └─────────────────────────────────────────────────┘
```
Mini bar width: 16 chars fixed.
Byte formatting: auto-scale to KB/MB/GB.

#### `SearchCommand.java`
Handles `/search [--mode exact|semantic|hybrid] <phrase>`.
Default mode: `hybrid`.
```java
public void execute(String[] args, Terminal terminal, ApiClient client) throws Exception {
    // parse --mode flag and phrase (rest of args joined)
    Map<String, Object> resp = client.post("/search", Map.of(...));
    List<Map<String,Object>> results = (List) resp.get("results");
    if (results.isEmpty()) {
        out.println(Theme.yellow("  No results found."));
        return;
    }
    out.println();
    out.println("  " + results.size() + " results for " + Theme.bold("\"" + phrase + "\"") +
                "  " + Theme.dim("[" + mode + "]"));
    out.println();
    ResultsFormatter.format(results, terminal.getWidth(), out);
}
```

#### `StatsCommand.java`
- `/stats` → single fetch + render `StatsPanel`
- `/watch` → loop: fetch stats, render panel, sleep 2s, move cursor up N lines to overwrite.
  Uses ANSI escape `\033[{N}A` to move cursor up before each redraw. Exits on Ctrl+C (catch `InterruptedException`).

```java
public void executeWatch(Terminal terminal, ApiClient client) throws Exception {
    int lines = 0;
    while (!Thread.currentThread().isInterrupted()) {
        if (lines > 0) out.print("\033[" + lines + "A\033[J"); // move up + clear to end
        lines = StatsPanel.print(client.get("/stats"), terminal.getWidth(), out);
        Thread.sleep(2000);
    }
}
```

#### `StatusCommand.java`
Handles `/status <artifact_id>`. Fetches `GET /api/status/{id}`, prints formatted key-value pairs.
Color the status value: INDEXED=green, FAILED=red, others=yellow.

#### `CatCommand.java`
Handles `/cat <artifact_id>`. Fetches `GET /api/text/{id}`, prints:
```
  Artifact:   <id>
  File:       <file_name>
  Method:     <extraction_method>
  Length:     <N> chars
  ─────────────────────────────────
  <extracted text, paginated if > 40 lines>
  ─ Press Enter for more, q to quit ─
```
Pagination: print 40 lines at a time, prompt for more.

---

### Agent 4 — Integration, help text, wiring, compile check

**Depends on Agents 1–3 completing first.**

#### Wire CommandDispatcher
Update `CommandDispatcher.java` to import and call all the concrete command classes created by Agents 2 and 3.

#### Help command
`/help` prints a formatted command reference table:
```
  ┌──────────────────────────────────────────────────────────────────┐
  │  JavaDucker Commands                                              │
  ├───────────────────┬──────────────────────────────────────────────┤
  │  /index <path>    │  Index a file or directory (with progress)   │
  │  /search <query>  │  Search indexed code (hybrid/exact/semantic) │
  │  /stats           │  Show indexing statistics                     │
  │  /watch           │  Live-refresh stats every 2s                  │
  │  /status <id>     │  Check status of one artifact                 │
  │  /cat <id>        │  Print full extracted text of an artifact     │
  │  /clear           │  Clear the screen                             │
  │  /exit            │  Quit (also Ctrl+D)                           │
  └───────────────────┴──────────────────────────────────────────────┘
```

#### Update `run-mcp.cmd` / `run-mcp.sh`
These don't need changes — the CLI UI is a separate entry point.

#### Add CLI launcher scripts
New file: `run-cli.cmd`
```cmd
@echo off
cd /d "%~dp0"
set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if "%HTTP_PORT%"=="" set HTTP_PORT=8080
if "%HOST%"=="" set HOST=localhost
java -cp "target\javaducker-1.0.0.jar;target\dependency\*" ^
    com.javaducker.cli.InteractiveCli --host %HOST% --port %HTTP_PORT% %*
```

New file: `run-cli.sh`
```sh
#!/bin/sh
cd "$(dirname "$0")"
HTTP_PORT="${HTTP_PORT:-8080}"
HOST="${HOST:-localhost}"
java -cp "target/javaducker-1.0.0.jar:target/dependency/*" \
    com.javaducker.cli.InteractiveCli --host "$HOST" --port "$HTTP_PORT" "$@"
```

#### Update `JavaDuckerServerApp.java` manifest
Ensure `spring-boot-maven-plugin` still sets main class to `com.javaducker.server.JavaDuckerServerApp`.
The CLI is NOT a Spring Boot app — it runs standalone via `-cp`.

#### Compile check
Run `mvn compile -DskipTests` and fix any import or type errors. Do not run full test suite (Agent 4 in the prior migration plan already ran that).

---

## pom.xml dependency additions (Agent 1 task)

```xml
<!-- JLine3 — interactive terminal (history, tab-complete, ANSI) -->
<dependency>
    <groupId>org.jline</groupId>
    <artifactId>jline</artifactId>
    <version>3.26.3</version>
</dependency>
```

Jackson is already provided transitively by `spring-boot-starter-web` (added in the gRPC→REST migration).
For the CLI fat-jar use case, Jackson's `com.fasterxml.jackson.core:jackson-databind` will be on the classpath via `target/dependency/` copy.

---

## File Creation Summary

| File | Agent |
|------|-------|
| `pom.xml` (add JLine3) | 1 |
| `src/main/java/com/javaducker/cli/InteractiveCli.java` | 1 |
| `src/main/java/com/javaducker/cli/ApiClient.java` | 1 |
| `src/main/java/com/javaducker/cli/CommandDispatcher.java` | 1 |
| `src/main/java/com/javaducker/cli/display/Theme.java` | 1 |
| `src/main/java/com/javaducker/cli/commands/IndexCommand.java` | 2 |
| `src/main/java/com/javaducker/cli/display/ProgressBar.java` | 2 |
| `src/main/java/com/javaducker/cli/commands/SearchCommand.java` | 3 |
| `src/main/java/com/javaducker/cli/commands/StatsCommand.java` | 3 |
| `src/main/java/com/javaducker/cli/commands/StatusCommand.java` | 3 |
| `src/main/java/com/javaducker/cli/commands/CatCommand.java` | 3 |
| `src/main/java/com/javaducker/cli/display/ResultsFormatter.java` | 3 |
| `src/main/java/com/javaducker/cli/display/StatsPanel.java` | 3 |
| `run-cli.cmd` | 4 |
| `run-cli.sh` | 4 |

No existing files are deleted or modified (except pom.xml for the JLine3 dep).
The existing `JavaDuckerClient.java` picocli one-shot CLI remains untouched and fully functional.

---

## Verification Checklist

- [ ] `mvn compile -DskipTests` passes with no errors
- [ ] `run-cli.cmd` (Windows) starts the REPL and shows the banner
- [ ] `/help` prints the command table
- [ ] `/index <single file>` uploads and prints artifact_id
- [ ] `/index <directory>` shows live upload + indexing progress bars
- [ ] `/search withConnection` returns results with colored output
- [ ] `/stats` renders the bordered stats panel
- [ ] `/watch` refreshes stats in-place every 2s; Ctrl+C exits cleanly
- [ ] `/status <id>` shows colored status
- [ ] `/cat <id>` shows paginated text
- [ ] Tab completion works for slash commands
- [ ] Up/down arrow recalls history
- [ ] `~/.javaducker_history` is written and persists across sessions
- [ ] Works on Windows (via cmd/PowerShell with ANSI enabled) and Linux/WSL
