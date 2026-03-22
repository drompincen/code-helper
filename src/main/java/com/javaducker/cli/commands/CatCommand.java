package com.javaducker.cli.commands;

import com.javaducker.cli.ApiClient;
import com.javaducker.cli.display.Theme;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles: /cat <artifact_id>
 * Shows the full extracted text with pagination (40 lines per page).
 */
public class CatCommand {

    private static final int PAGE_SIZE = 40;

    public void execute(String[] args, Terminal terminal, ApiClient client) {
        PrintWriter out = terminal.writer();

        if (args.length == 0) {
            out.println(Theme.yellow("  Usage: /cat <artifact_id>"));
            return;
        }

        String artifactId = args[0];
        try {
            Map<String, Object> r = client.get("/text/" + artifactId);
            if (r == null) {
                out.println(Theme.red("  Artifact not found: " + artifactId));
                return;
            }

            String text = str(r.get("extracted_text"));
            long textLength = r.get("text_length") instanceof Number n ? n.longValue() : text.length();

            out.println();
            out.println("  Artifact:  " + Theme.bold(str(r.get("artifact_id"))));
            out.println("  Method:    " + Theme.dim(str(r.get("extraction_method"))));
            out.println("  Length:    " + Theme.dim(String.format("%,d", textLength) + " chars"));
            out.println("  " + Theme.DIM + repeat('─', Math.min(terminal.getWidth() - 6, 60)) + Theme.RESET);
            out.println();

            // Split into lines and paginate
            String[] rawLines = text.split("\n", -1);
            List<String> lines = new ArrayList<>();
            for (String line : rawLines) {
                // Word-wrap long lines to terminal width
                int maxW = terminal.getWidth() - 4;
                if (line.length() <= maxW) {
                    lines.add(line);
                } else {
                    while (line.length() > maxW) {
                        lines.add(line.substring(0, maxW));
                        line = line.substring(maxW);
                    }
                    if (!line.isEmpty()) lines.add(line);
                }
            }

            int page = 0;
            while (page * PAGE_SIZE < lines.size()) {
                int from = page * PAGE_SIZE;
                int to   = Math.min(from + PAGE_SIZE, lines.size());
                for (int i = from; i < to; i++) {
                    out.println("  " + lines.get(i));
                }

                if (to < lines.size()) {
                    out.print(Theme.dim("  ─── " + to + "/" + lines.size() +
                                        " lines — Enter for more, q to quit ─── "));
                    out.flush();
                    try {
                        int ch = terminal.reader().read();
                        out.println();
                        if (ch == 'q' || ch == 'Q') break;
                    } catch (Exception e) {
                        break;
                    }
                }
                page++;
            }
            out.println();
        } catch (Exception e) {
            out.println(Theme.red("  Error: " + e.getMessage()));
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] a = new char[n]; java.util.Arrays.fill(a, c); return new String(a);
    }
}
