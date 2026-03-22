package com.javaducker.cli.commands;

import com.javaducker.cli.ApiClient;
import com.javaducker.cli.display.Theme;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.Map;

/**
 * Handles: /status <artifact_id>
 */
public class StatusCommand {

    public void execute(String[] args, Terminal terminal, ApiClient client) {
        PrintWriter out = terminal.writer();

        if (args.length == 0) {
            out.println(Theme.yellow("  Usage: /status <artifact_id>"));
            return;
        }

        String artifactId = args[0];
        try {
            Map<String, Object> r = client.get("/status/" + artifactId);
            if (r == null) {
                out.println(Theme.red("  Artifact not found: " + artifactId));
                return;
            }

            String status = str(r.get("status"));
            String statusColored = switch (status) {
                case "INDEXED" -> Theme.green(status);
                case "FAILED"  -> Theme.red(status);
                default        -> Theme.yellow(status);
            };

            out.println();
            out.println("  Artifact:  " + Theme.bold(str(r.get("artifact_id"))));
            out.println("  File:      " + Theme.cyan(str(r.get("file_name"))));
            out.println("  Status:    " + statusColored);
            String err = str(r.get("error_message"));
            if (!err.isBlank()) {
                out.println("  Error:     " + Theme.red(err));
            }
            out.println("  Created:   " + Theme.dim(str(r.get("created_at"))));
            out.println("  Updated:   " + Theme.dim(str(r.get("updated_at"))));
            String indexedAt = str(r.get("indexed_at"));
            if (!indexedAt.isBlank()) {
                out.println("  Indexed:   " + Theme.dim(indexedAt));
            }
            out.println();
        } catch (Exception e) {
            out.println(Theme.red("  Error: " + e.getMessage()));
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
