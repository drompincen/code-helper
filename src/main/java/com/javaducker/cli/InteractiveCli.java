package com.javaducker.cli;

import com.javaducker.cli.display.StatsPanel;
import com.javaducker.cli.display.Theme;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * JavaDucker Interactive CLI.
 *
 * Launch with no subcommand args for REPL mode.
 * Pass --host / --port to connect to a non-default server.
 */
public class InteractiveCli {

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int    port = 8080;

        // Parse --host and --port flags
        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) host = args[i + 1];
            if ("--port".equals(args[i])) {
                try { port = Integer.parseInt(args[i + 1]); } catch (NumberFormatException ignored) {}
            }
        }

        ApiClient client = new ApiClient(host, port);

        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(false)
                .build();

        Path historyFile = Paths.get(System.getProperty("user.home"), ".javaducker_history");

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(
                        "/help", "/index", "/search", "/stats", "/watch",
                        "/status", "/cat", "/clear", "/exit"))
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, historyFile)
                .variable(LineReader.HISTORY_SIZE, 500)
                .parser(new DefaultParser())
                .build();

        printBanner(terminal, client, host, port);

        CommandDispatcher dispatcher = new CommandDispatcher(client);

        // REPL loop
        while (true) {
            String line;
            try {
                line = reader.readLine(Theme.CYAN + "jd" + Theme.RESET + Theme.DIM + "> " + Theme.RESET);
            } catch (UserInterruptException e) {
                // Ctrl+C — clear line, continue
                terminal.writer().println();
                continue;
            } catch (EndOfFileException e) {
                // Ctrl+D — exit
                break;
            }

            if (line == null || line.isBlank()) continue;

            boolean keepGoing = dispatcher.dispatch(line.trim(), terminal);
            if (!keepGoing) break;
        }

        terminal.writer().println(Theme.dim("  Bye."));
        terminal.writer().flush();
        terminal.close();
    }

    // ── banner ────────────────────────────────────────────────────────────────

    private static void printBanner(Terminal terminal, ApiClient client, String host, int port) {
        var out = terminal.writer();
        int w = Math.min(terminal.getWidth() - 4, 56);
        String line = "─".repeat(w);

        out.println();
        out.println("  " + Theme.CYAN + "╔" + line + "╗" + Theme.RESET);
        out.println("  " + Theme.CYAN + "║" + Theme.RESET +
                    Theme.BOLD + center("JavaDucker  v2.0.0  —  Code Search Engine", w) + Theme.RESET +
                    Theme.CYAN + "║" + Theme.RESET);
        out.println("  " + Theme.CYAN + "╚" + line + "╝" + Theme.RESET);
        out.println();

        // Server connection status
        boolean up = client.isReachable();
        String connStatus = up
            ? Theme.GREEN + "✓  Connected" + Theme.RESET
            : Theme.RED   + "✗  Unreachable" + Theme.RESET;
        out.println("  " + connStatus + Theme.DIM + "  http://" + host + ":" + port + Theme.RESET);

        // Quick stats if server is up
        if (up) {
            try {
                Map<String, Object> stats = client.get("/stats");
                long total   = toLong(stats.get("total_artifacts"));
                long indexed = toLong(stats.get("indexed_artifacts"));
                long pending = toLong(stats.get("pending_artifacts"));
                out.println("  " + Theme.DIM + "Index: " + Theme.RESET +
                            Theme.bold(String.format("%,d", total)) + " files  │  " +
                            Theme.green(String.format("%,d", indexed)) + " indexed  │  " +
                            Theme.yellow(String.format("%,d", pending)) + " pending");
            } catch (Exception ignored) {}
        }

        out.println();
        out.println("  " + Theme.DIM + "Type /help for commands, Tab to autocomplete, Ctrl+C to cancel, Ctrl+D to exit." + Theme.RESET);
        out.println();
        out.flush();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String center(String s, int w) {
        if (s.length() >= w) return s;
        int pad = w - s.length();
        return " ".repeat(pad / 2) + s + " ".repeat(pad - pad / 2);
    }

    private static long toLong(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
