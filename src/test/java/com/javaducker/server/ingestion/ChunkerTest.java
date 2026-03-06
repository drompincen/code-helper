package com.javaducker.server.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {

    private final Chunker chunker = new Chunker();

    @Test
    void emptyTextProducesNoChunks() {
        List<Chunker.Chunk> chunks = chunker.chunk("", 100, 20);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void nullTextProducesNoChunks() {
        List<Chunker.Chunk> chunks = chunker.chunk(null, 100, 20);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void shortTextProducesOneChunk() {
        List<Chunker.Chunk> chunks = chunker.chunk("hello", 100, 20);
        assertEquals(1, chunks.size());
        assertEquals("hello", chunks.get(0).text());
        assertEquals(0, chunks.get(0).charStart());
        assertEquals(5, chunks.get(0).charEnd());
    }

    @Test
    void textSplitsIntoMultipleChunks() {
        String text = "a".repeat(250);
        List<Chunker.Chunk> chunks = chunker.chunk(text, 100, 20);
        assertTrue(chunks.size() > 1);
        // Verify ordering
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    void chunkOrderIsStable() {
        String text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet";
        List<Chunker.Chunk> first = chunker.chunk(text, 20, 5);
        List<Chunker.Chunk> second = chunker.chunk(text, 20, 5);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).text(), second.get(i).text());
            assertEquals(first.get(i).charStart(), second.get(i).charStart());
        }
    }

    @Test
    void overlapWorks() {
        String text = "0123456789abcdefghij";
        List<Chunker.Chunk> chunks = chunker.chunk(text, 10, 3);
        // With size=10, overlap=3, step=7
        assertTrue(chunks.size() >= 2);
        // Second chunk should start at position 7
        assertEquals(7, chunks.get(1).charStart());
    }

    @Test
    void charStartAndEndAreCorrect() {
        String text = "Hello World! This is a test.";
        List<Chunker.Chunk> chunks = chunker.chunk(text, 15, 5);
        for (Chunker.Chunk chunk : chunks) {
            assertEquals(chunk.text(), text.substring((int) chunk.charStart(), (int) chunk.charEnd()));
        }
    }
}
