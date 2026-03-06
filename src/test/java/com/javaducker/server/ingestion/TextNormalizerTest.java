package com.javaducker.server.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void removeNullCharacters() {
        assertEquals("hello world", normalizer.normalize("hello\0 world\0"));
    }

    @Test
    void normalizeLineEndings() {
        assertEquals("line1\nline2\nline3", normalizer.normalize("line1\r\nline2\rline3"));
    }

    @Test
    void collapseExcessiveBlankLines() {
        String input = "a\n\n\n\n\n\nb";
        String result = normalizer.normalize(input);
        assertFalse(result.contains("\n\n\n\n"), "Should collapse excessive blank lines");
    }

    @Test
    void trimTrailingWhitespace() {
        String result = normalizer.normalize("hello   \nworld   ");
        assertEquals("hello\nworld", result);
    }

    @Test
    void nullInputReturnsEmpty() {
        assertEquals("", normalizer.normalize(null));
    }

    @Test
    void normalTextUnchanged() {
        String input = "public class Foo {\n    void bar() {}\n}";
        assertEquals(input, normalizer.normalize(input));
    }
}
