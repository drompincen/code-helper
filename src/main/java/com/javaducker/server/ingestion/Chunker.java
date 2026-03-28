package com.javaducker.server.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {

    public record Chunk(int index, String text, long charStart, long charEnd,
                        int lineStart, int lineEnd) {}

    public List<Chunk> chunk(String text, int chunkSize, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        int effectiveSize = Math.max(chunkSize, 1);
        int effectiveOverlap = Math.min(overlap, effectiveSize - 1);
        int step = effectiveSize - effectiveOverlap;
        if (step <= 0) step = 1;

        int index = 0;
        int pos = 0;
        int currentLine = 1;
        int scannedUpTo = 0;

        while (pos < text.length()) {
            int end = Math.min(pos + effectiveSize, text.length());

            // Advance line count up to pos (for lineStart)
            while (scannedUpTo < pos) {
                if (text.charAt(scannedUpTo) == '\n') {
                    currentLine++;
                }
                scannedUpTo++;
            }
            int lineStart = currentLine;

            // Count additional newlines from pos to end (for lineEnd)
            int lineEnd = lineStart;
            for (int i = pos; i < end; i++) {
                if (text.charAt(i) == '\n') {
                    lineEnd++;
                }
            }

            String chunkText = text.substring(pos, end);
            chunks.add(new Chunk(index, chunkText, pos, end, lineStart, lineEnd));
            index++;
            pos += step;
            if (end == text.length()) break;
        }

        return chunks;
    }
}
