package com.javaducker.cli.display;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * Formats search results as box-drawing "cards".
 */
public class ResultsFormatter {

    public static void format(List<Map<String, Object>> results, int termWidth, PrintWriter out) {
        int w = Math.max(termWidth - 4, 60);

        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> r = results.get(i);

            String matchType = str(r.get("match_type"));
            String fileName  = str(r.get("file_name"));
            String artifactId= str(r.get("artifact_id"));
            double score     = r.get("score") instanceof Number n ? n.doubleValue() : 0.0;
            int chunkIdx     = r.get("chunk_index") instanceof Number n ? n.intValue() : 0;
            String preview   = str(r.get("preview"));

            String typeColor = switch (matchType) {
                case "EXACT"    -> Theme.GREEN;
                case "SEMANTIC" -> Theme.BLUE;
                default         -> Theme.YELLOW;   // HYBRID
            };

            // Header line
            out.printf("  #%d  %s%s%s  score=%.4f%n",
                i + 1,
                typeColor, matchType, Theme.RESET,
                score);

            // Box top
            out.println("  " + Theme.DIM + "┌─ " + Theme.RESET +
                         Theme.BOLD + Theme.CYAN + fileName + Theme.RESET +
                         Theme.DIM + "  chunk " + chunkIdx + Theme.RESET);

            // Preview lines — word-wrap to terminal width - 7
            int lineWidth = w - 7;
            for (String line : wrapText(preview, lineWidth)) {
                out.println("  " + Theme.DIM + "│  " + Theme.RESET + line);
            }

            // Box bottom with artifact id hint
            out.println("  " + Theme.DIM + "└─ artifact: " + Theme.RESET +
                         Theme.DIM + artifactId + Theme.RESET);
            out.println();
        }
        out.flush();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static List<String> wrapText(String text, int lineWidth) {
        if (text == null || text.isBlank()) return List.of("");
        // Replace newlines with space for wrapping
        text = text.replace('\n', ' ').replace('\r', ' ');
        if (text.length() <= lineWidth) return List.of(text);

        List<String> lines = new java.util.ArrayList<>();
        while (text.length() > lineWidth) {
            int cut = text.lastIndexOf(' ', lineWidth);
            if (cut <= 0) cut = lineWidth;
            lines.add(text.substring(0, cut).stripTrailing());
            text = text.substring(cut).stripLeading();
        }
        if (!text.isBlank()) lines.add(text);
        return lines;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
