package com.javaducker.cli.commands;

import com.javaducker.cli.ApiClient;
import com.javaducker.cli.display.StatsPanel;
import com.javaducker.cli.display.Theme;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.Map;

/**
 * Handles: /stats  and  /watch (live-refresh stats every 2s)
 */
public class StatsCommand {

    /** Single stats snapshot. */
    public void execute(Terminal terminal, ApiClient client) {
        PrintWriter out = terminal.writer();
        try {
            Map<String, Object> stats = client.get("/stats");
            out.println();
            StatsPanel.print(stats, terminal.getWidth(), out);
            out.println();
        } catch (Exception e) {
            out.println(Theme.red("  Stats error: " + e.getMessage()));
        }
    }

    /** Live-refresh loop (for /watch). Press Ctrl+C to exit. */
    public void watch(Terminal terminal, ApiClient client) {
        PrintWriter out = terminal.writer();
        out.println(Theme.dim("  Watching stats — press Ctrl+C to stop"));
        out.println();

        int lastLines = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Move cursor up to overwrite previous panel
                if (lastLines > 0) {
                    out.print("\033[" + lastLines + "A\033[J");
                }
                Map<String, Object> stats = client.get("/stats");
                lastLines = StatsPanel.print(stats, terminal.getWidth(), out);
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                out.println(Theme.red("  Error: " + e.getMessage()));
                break;
            }
        }
        out.println();
        out.println(Theme.dim("  Watch stopped."));
        out.flush();
    }
}
