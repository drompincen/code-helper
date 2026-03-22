package com.javaducker.cli.commands;

import com.javaducker.cli.ApiClient;
import com.javaducker.cli.display.ResultsFormatter;
import com.javaducker.cli.display.Theme;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles: /search [--mode exact|semantic|hybrid] <phrase>
 */
public class SearchCommand {

    public void execute(String[] args, Terminal terminal, ApiClient client) {
        PrintWriter out = terminal.writer();

        if (args.length == 0) {
            out.println(Theme.yellow("  Usage: /search [--mode exact|semantic|hybrid] <phrase>"));
            return;
        }

        String mode = "hybrid";
        int phraseStart = 0;

        if (args.length >= 2 && "--mode".equals(args[0])) {
            mode = args[1].toLowerCase();
            phraseStart = 2;
        }

        if (phraseStart >= args.length) {
            out.println(Theme.yellow("  Usage: /search [--mode exact|semantic|hybrid] <phrase>"));
            return;
        }

        String phrase = String.join(" ", Arrays.copyOfRange(args, phraseStart, args.length));

        try {
            Map<String, Object> resp = client.post("/search",
                    Map.of("phrase", phrase, "mode", mode, "max_results", 20));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");

            out.println();
            if (results == null || results.isEmpty()) {
                out.println(Theme.yellow("  No results found for \"" + phrase + "\""));
                out.println();
                return;
            }

            out.println("  " + Theme.bold(results.size() + " results") +
                        " for " + Theme.bold("\"" + phrase + "\"") +
                        "  " + Theme.dim("[" + mode + "]"));
            out.println();
            ResultsFormatter.format(results, terminal.getWidth(), out);
        } catch (Exception e) {
            out.println(Theme.red("  Search error: " + e.getMessage()));
        }
    }
}
