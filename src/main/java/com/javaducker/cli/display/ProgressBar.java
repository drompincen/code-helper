package com.javaducker.cli.display;

import java.io.PrintWriter;

/**
 * Renders an in-place updating progress bar using ANSI \r overwrite.
 * Call render() repeatedly; it overwrites the current line each time.
 * Call complete() to finalize with a newline.
 */
public class ProgressBar {

    private static final char FILL  = '█';
    private static final char EMPTY = '░';
    private static final int  BAR_WIDTH = 28;

    private final PrintWriter out;
    private final int termWidth;

    public ProgressBar(PrintWriter out, int termWidth) {
        this.out = out;
        this.termWidth = Math.max(termWidth, 60);
    }

    /**
     * Render an updating progress line (overwrites current line with \r).
     *
     * @param label    Left label, e.g. "  Uploading"
     * @param done     Items completed
     * @param total    Total items (0 = indeterminate, show spinner)
     * @param extra    Right-side annotation, e.g. "42 files/s" or ""
     */
    public void render(String label, long done, long total, String extra) {
        String bar;
        String counts;

        if (total <= 0) {
            // Indeterminate — show spinning dots
            bar = "[" + Theme.CYAN + repeat(FILL, BAR_WIDTH / 2) + Theme.RESET +
                  repeat(EMPTY, BAR_WIDTH - BAR_WIDTH / 2) + "]";
            counts = done + " processed";
        } else {
            double pct = Math.min(1.0, (double) done / total);
            int filled = (int) Math.round(pct * BAR_WIDTH);
            bar = "[" + Theme.CYAN + repeat(FILL, filled) + Theme.RESET +
                  repeat(EMPTY, BAR_WIDTH - filled) + "]";
            counts = String.format("%,d / %,d  (%d%%)", done, total, (int) (pct * 100));
        }

        String extraPart = extra.isBlank() ? "" : "  " + Theme.DIM + extra + Theme.RESET;
        String line = label + "  " + bar + "  " + counts + extraPart;

        // Truncate to terminal width - 2 to avoid wrapping
        if (visibleLength(line) > termWidth - 2) {
            line = line.substring(0, termWidth - 5) + "...";
        }

        out.print("\r" + line);
        out.flush();
    }

    /**
     * Print a final "done" line with checkmark and a newline.
     */
    public void complete(String label, long count, String unit) {
        String line = label + "  " + Theme.GREEN + "✓" + Theme.RESET +
                      "  " + String.format("%,d", count) + " " + unit + " done";
        out.print("\r" + line);
        out.println();
        out.flush();
    }

    /** Erase the current line (useful when aborting). */
    public void clear() {
        out.print("\r\033[K");
        out.flush();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    /** Approximate visible length — ignores ANSI escape sequences. */
    private static int visibleLength(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "").length();
    }
}
