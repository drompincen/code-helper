package com.javaducker.server.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {

    public record Chunk(int index, String text, long charStart, long charEnd) {}

    public List<Chunk> chunk(String text, int chunkSize, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        int effectiveSize = Math.max(chunkSize, 1);
        int effectiveOverlap = Math.min(overlap, effectiveSize - 1);
        int step = effectiveSize - effectiveOverlap;
        if (step <= 0) step = 1;

        int index = 0;
        int pos = 0;

        while (pos < text.length()) {
            int end = Math.min(pos + effectiveSize, text.length());
            String chunkText = text.substring(pos, end);
            chunks.add(new Chunk(index, chunkText, pos, end));
            index++;
            pos += step;
            if (end == text.length()) break;
        }

        return chunks;
    }
}
