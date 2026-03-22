package com.javaducker.cli;

import com.javaducker.cli.commands.*;
import com.javaducker.cli.display.Theme;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;

/**
 * Parses a raw input line and dispatches to the appropriate command handler.
 */
public class CommandDispatcher {

    private final ApiClient client;
    private final IndexCommand  indexCmd  = new IndexCommand();
    private final SearchCommand searchCmd = new SearchCommand();
    private final StatsCommand  statsCmd  = new StatsCommand();
    private final StatusCommand statusCmd = new StatusCommand();
    private final CatCommand    catCmd    = new CatCommand();

    public CommandDispatcher(ApiClient client) {
        this.client = client;
    }

    /**
     * Dispatch a raw input line. Returns false if the user typed /exit.
     */
    public boolean dispatch(String line, Terminal terminal) {
        PrintWriter out = terminal.writer();

        if (line == null || line.isBlank()) return true;

        // Tokenize
        String[] tokens = line.trim().split("\\s+");
        String cmd = tokens[0].toLowerCase();
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);

        switch (cmd) {
            case "/help", "help" -> printHelp(out);
            case "/index"        -> indexCmd.execute(args, terminal, client);
            case "/search"       -> searchCmd.execute(args, terminal, client);
            case "/stats"        -> statsCmd.execute(terminal, client);
            case "/watch"        -> statsCmd.watch(terminal, client);
            case "/status"       -> statusCmd.execute(args, terminal, client);
            case "/cat"          -> catCmd.execute(args, terminal, client);
            case "/clear"        -> {
                out.print("\033[H\033[2J");
                out.flush();
            }
            case "/exit", "/quit", "exit", "quit" -> { return false; }
            default -> out.println(Theme.yellow("  Unknown command: " + cmd +
                                   "  (type /help for commands)"));
        }
        return true;
    }

    // ── help ──────────────────────────────────────────────────────────────────

    private static void printHelp(PrintWriter out) {
        String cyan  = Theme.CYAN;
        String dim   = Theme.DIM;
        String reset = Theme.RESET;
        String bold  = Theme.BOLD;

        out.println();
        out.println("  " + cyan + "┌───────────────────────────────────────────────────────────────────┐" + reset);
        out.println("  " + cyan + "│" + reset + bold + center("JavaDucker Commands", 67) + reset + cyan + "│" + reset);
        out.println("  " + cyan + "├────────────────────┬──────────────────────────────────────────────┤" + reset);
        helpRow(out,  "/index <path>",   "Index a file or directory (with live progress)");
        helpRow(out,  "/search <query>", "Search (default: hybrid; --mode exact|semantic)");
        helpRow(out,  "/stats",          "Show indexing statistics panel");
        helpRow(out,  "/watch",          "Live-refresh stats every 2 seconds (Ctrl+C stops)");
        helpRow(out,  "/status <id>",    "Check ingestion status of one artifact");
        helpRow(out,  "/cat <id>",       "Print full extracted text (paginated)");
        helpRow(out,  "/clear",          "Clear the screen");
        helpRow(out,  "/exit",           "Quit (also Ctrl+D)");
        out.println("  " + cyan + "└────────────────────┴──────────────────────────────────────────────┘" + reset);
        out.println();
        out.flush();
    }

    private static void helpRow(PrintWriter out, String cmd, String desc) {
        String cyan  = Theme.CYAN;
        String reset = Theme.RESET;
        String cmdPad  = padRight(cmd, 20);
        String descPad = padRight(desc, 44);
        out.println("  " + cyan + "│" + reset + " " + Theme.bold(cmdPad) +
                    cyan + "│" + reset + " " + descPad + " " + cyan + "│" + reset);
    }

    private static String center(String s, int w) {
        if (s.length() >= w) return s;
        int pad = w - s.length();
        return " ".repeat(pad / 2) + s + " ".repeat(pad - pad / 2);
    }

    private static String padRight(String s, int w) {
        if (s.length() >= w) return s.substring(0, w);
        return s + " ".repeat(w - s.length());
    }
}
