package com.javaducker.cli.commands;

import com.javaducker.cli.ApiClient;
import com.javaducker.cli.display.ProgressBar;
import com.javaducker.cli.display.Theme;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Handles the /index command.
 *
 * Usage:
 *   /index <path>
 *   /index --ext .java,.xml <path>
 *
 * For a single file: uploads immediately, prints artifact_id.
 * For a directory: walks, uploads all matching files with a live progress bar,
 * then polls /api/stats until pending_artifacts reaches 0.
 */
public class IndexCommand {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "node_modules", ".git", ".svn", ".hg",
        "target", "build", "dist", "out", ".gradle",
        "__pycache__", ".pytest_cache", ".mypy_cache",
        "vendor", ".idea", ".vscode", "coverage",
        "temp", "test-corpus"
    );

    private static final String DEFAULT_EXTENSIONS = ".java,.xml,.md,.yml,.json,.txt,.pdf,.docx,.pptx,.xlsx,.doc,.ppt,.xls,.odt,.odp,.ods,.html,.htm,.epub,.rtf,.eml";

    public void execute(String[] args, Terminal terminal, ApiClient client) {
        PrintWriter out = terminal.writer();

        if (args.length == 0) {
            out.println(Theme.yellow("  Usage: /index <path>  or  /index --ext .java,.xml <path>"));
            return;
        }

        // Parse optional --ext flag
        Set<String> exts = Set.of(DEFAULT_EXTENSIONS.split(","));
        int pathArgIdx = 0;
        if (args.length >= 2 && "--ext".equals(args[0])) {
            exts = Set.of(args[1].toLowerCase().split(","));
            pathArgIdx = 2;
        }
        if (pathArgIdx >= args.length) {
            out.println(Theme.yellow("  Usage: /index [--ext .java,.xml] <path>"));
            return;
        }

        Path path = Path.of(String.join(" ", Arrays.copyOfRange(args, pathArgIdx, args.length)));

        if (!Files.exists(path)) {
            out.println(Theme.red("  Path not found: " + path));
            return;
        }

        try {
            if (Files.isRegularFile(path)) {
                uploadSingleFile(path, out, client);
            } else if (Files.isDirectory(path)) {
                uploadDirectory(path, exts, terminal, client);
            } else {
                out.println(Theme.red("  Not a file or directory: " + path));
            }
        } catch (Exception e) {
            out.println(Theme.red("  Error: " + e.getMessage()));
        }
    }

    // ── single file ───────────────────────────────────────────────────────────

    private void uploadSingleFile(Path path, PrintWriter out, ApiClient client) throws Exception {
        out.print("  Uploading " + Theme.cyan(path.getFileName().toString()) + "...");
        out.flush();
        Map<String, Object> r = client.upload(path);
        out.println("  " + Theme.GREEN + "✓" + Theme.RESET +
                    "  artifact_id: " + Theme.dim((String) r.get("artifact_id")));
    }

    // ── directory ─────────────────────────────────────────────────────────────

    private void uploadDirectory(Path root, Set<String> exts, Terminal terminal, ApiClient client)
            throws Exception {
        PrintWriter out = terminal.writer();
        int termWidth = terminal.getWidth();

        // Collect eligible files
        out.println("  Scanning directory...");
        out.flush();
        List<Path> files = collectFiles(root, exts);
        out.println("  Found " + Theme.bold(String.format("%,d", files.size())) + " source files");
        out.println();

        if (files.isEmpty()) {
            out.println(Theme.yellow("  No matching files found. Use --ext to change file types."));
            return;
        }

        // Upload phase
        ProgressBar uploadBar = new ProgressBar(out, termWidth);
        long startMs = System.currentTimeMillis();
        long[] uploaded = {0};
        long[] failed   = {0};
        List<String> artifactIds = new ArrayList<>(files.size());

        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            long elapsedSec = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
            double rate = (uploaded[0] + failed[0]) / (double) elapsedSec;
            String extra = String.format("%.0f files/s", rate) +
                           (failed[0] > 0 ? "  " + Theme.red(failed[0] + " failed") : "");
            uploadBar.render("  Uploading", i, files.size(), extra);

            try {
                Map<String, Object> r = client.upload(file);
                artifactIds.add((String) r.get("artifact_id"));
                uploaded[0]++;
            } catch (Exception e) {
                failed[0]++;
            }
        }
        uploadBar.complete("  Uploading", uploaded[0], "files");
        if (failed[0] > 0) {
            out.println(Theme.yellow("  (" + failed[0] + " files could not be uploaded)"));
        }

        // Indexing poll phase
        out.println();
        out.println("  Waiting for server to index...");
        pollUntilIndexed(artifactIds.size(), terminal, client);

        long totalSec = (System.currentTimeMillis() - startMs) / 1000;
        out.println();
        out.println("  " + Theme.GREEN + "✓" + Theme.RESET +
                    "  Indexed " + Theme.bold(String.format("%,d", uploaded[0])) +
                    " files in " + formatDuration(totalSec));
    }

    private void pollUntilIndexed(int expectedCount, Terminal terminal, ApiClient client)
            throws Exception {
        PrintWriter out = terminal.writer();
        int termWidth = terminal.getWidth();
        ProgressBar indexBar = new ProgressBar(out, termWidth);

        long startMs = System.currentTimeMillis();
        long prevIndexed = 0;
        long prevMs = startMs;

        while (true) {
            Map<String, Object> stats = client.get("/stats");
            long total   = toLong(stats.get("total_artifacts"));
            long indexed = toLong(stats.get("indexed_artifacts"));
            long pending = toLong(stats.get("pending_artifacts"));
            long failed2 = toLong(stats.get("failed_artifacts"));

            long nowMs = System.currentTimeMillis();
            double rate = (nowMs - prevMs) > 0
                ? (indexed - prevIndexed) * 60_000.0 / (nowMs - prevMs) : 0;
            prevIndexed = indexed;
            prevMs = nowMs;

            long elapsedSec = (nowMs - startMs) / 1000;
            String eta = (rate > 0 && pending > 0)
                ? "  ETA ~" + formatDuration((long)(pending * 60 / rate)) : "";
            String extra = String.format("%.0f files/min  queued: %,d  failed: %,d", rate, pending, failed2) + eta;

            indexBar.render("  Indexing", indexed, total, extra);

            if (pending == 0) {
                indexBar.complete("  Indexing", indexed, "artifacts");
                break;
            }
            Thread.sleep(2000);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Path> collectFiles(Path root, Set<String> exts) throws Exception {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (inExcludedDir(p)) continue;
                String name = p.getFileName().toString().toLowerCase();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                if (exts.contains(ext)) result.add(p);
            }
        }
        return result;
    }

    private boolean inExcludedDir(Path p) {
        for (Path part : p) {
            if (EXCLUDED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private static long toLong(Object o) {
        if (o == null) return 0;
        return ((Number) o).longValue();
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
