package com.javaducker.cli.display;

import java.io.PrintWriter;
import java.util.Map;

/**
 * Renders a bordered stats panel with inline mini progress bars.
 * Returns the number of lines printed (for /watch cursor-up redraws).
 */
public class StatsPanel {

    private static final int PANEL_WIDTH = 52;
    private static final int MINI_BAR    = 16;

    public static int print(Map<String, Object> stats, int termWidth, PrintWriter out) {
        int lineCount = 0;

        long total   = toLong(stats.get("total_artifacts"));
        long indexed = toLong(stats.get("indexed_artifacts"));
        long pending = toLong(stats.get("pending_artifacts"));
        long failed  = toLong(stats.get("failed_artifacts"));
        long chunks  = toLong(stats.get("total_chunks"));
        long bytes   = toLong(stats.get("total_bytes"));

        int w = Math.min(PANEL_WIDTH, termWidth - 4);

        out.println("  " + Theme.CYAN + "┌" + repeat('─', w - 2) + "┐" + Theme.RESET);
        lineCount++;

        out.println("  " + Theme.CYAN + "│" + Theme.RESET +
                    Theme.BOLD + center("JavaDucker Index Stats", w - 2) + Theme.RESET +
                    Theme.CYAN + "│" + Theme.RESET);
        lineCount++;

        out.println("  " + Theme.CYAN + "├" + repeat('─', w - 2) + "┤" + Theme.RESET);
        lineCount++;

        lineCount += statRow(out, "Total artifacts", String.format("%,d", total), null, 0, w);
        lineCount += statRow(out, "Indexed",  String.format("%,d", indexed),  Theme.GREEN,  ratio(indexed, total), w);
        lineCount += statRow(out, "Pending",  String.format("%,d", pending),  Theme.YELLOW, ratio(pending, total), w);
        lineCount += statRow(out, "Failed",   String.format("%,d", failed),   Theme.RED,    ratio(failed,  total), w);
        lineCount += statRow(out, "Total chunks", String.format("%,d", chunks), null, 0, w);
        lineCount += statRow(out, "Total bytes",  formatBytes(bytes), null, 0, w);

        out.println("  " + Theme.CYAN + "└" + repeat('─', w - 2) + "┘" + Theme.RESET);
        lineCount++;

        out.flush();
        return lineCount;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static int statRow(PrintWriter out, String label, String value,
                                String color, double pct, int w) {
        String bar = "";
        String pctStr = "";
        if (color != null) {
            int filled = (int) Math.round(pct * MINI_BAR);
            bar = "  " + color + repeat('█', filled) + Theme.RESET +
                  Theme.DIM + repeat('░', MINI_BAR - filled) + Theme.RESET;
            pctStr = "  " + String.format("%3.0f%%", pct * 100);
        }

        // Assemble: │  label       value  [bar]  pct│
        String inner = "  " + padRight(label, 18) + padLeft(value, 8) + bar + pctStr;
        // Pad to panel width
        int visible = visibleLength(inner);
        int pad = Math.max(0, w - 2 - visible);
        inner = inner + repeat(' ', pad);

        out.println("  " + Theme.CYAN + "│" + Theme.RESET + inner + Theme.CYAN + "│" + Theme.RESET);
        return 1;
    }

    private static double ratio(long part, long total) {
        return total == 0 ? 0.0 : Math.min(1.0, (double) part / total);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static long toLong(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] a = new char[n]; java.util.Arrays.fill(a, c); return new String(a);
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s;
        int pad = width - s.length();
        return repeat(' ', pad / 2) + s + repeat(' ', pad - pad / 2);
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + repeat(' ', width - s.length());
    }

    private static String padLeft(String s, int width) {
        return s.length() >= width ? s : repeat(' ', width - s.length()) + s;
    }

    private static int visibleLength(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "").length();
    }
}
