package com.javaducker.server.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void extractTextFile() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertEquals("Hello World", result.text());
        assertEquals("TEXT_DECODE", result.method());
    }

    @Test
    void extractJavaFile() throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "public class Sample { @Transactional void run() {} }");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertTrue(result.text().contains("@Transactional"));
        assertEquals("TEXT_DECODE", result.method());
    }

    @Test
    void extractMarkdown() throws IOException {
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, "# Title\nSome content here");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertTrue(result.text().contains("# Title"));
    }

    @Test
    void extractYaml() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "server:\n  port: 8080");
        TextExtractor.ExtractionResult result = extractor.extract(file);
        assertTrue(result.text().contains("port: 8080"));
    }

    @Test
    void unsupportedFileThrows() {
        Path file = tempDir.resolve("image.png");
        assertThrows(IOException.class, () -> extractor.extract(file));
    }

    @Test
    void getExtension() {
        assertEquals(".java", TextExtractor.getExtension("Sample.java"));
        assertEquals(".txt", TextExtractor.getExtension("readme.txt"));
        assertEquals("", TextExtractor.getExtension("Makefile"));
    }

    @Test
    void isSupportedExtension() {
        assertTrue(TextExtractor.isSupportedExtension(".java"));
        assertTrue(TextExtractor.isSupportedExtension(".md"));
        assertTrue(TextExtractor.isSupportedExtension(".pdf"));
        assertFalse(TextExtractor.isSupportedExtension(".png"));
        assertFalse(TextExtractor.isSupportedExtension(".exe"));
    }
}
