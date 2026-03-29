package com.javaducker.server.ingestion;

import com.javaducker.server.model.SessionTranscript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTranscriptParserTest {

    private final SessionTranscriptParser parser = new SessionTranscriptParser();

    @Test
    void parseHumanMessage() {
        String line = """
                {"type":"human","message":{"role":"user","content":"Hello world"},"timestamp":"2026-03-28T10:00:00Z"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("session-1", result.sessionId());
        assertEquals("/projects/test", result.projectPath());
        assertEquals(0, result.messageIndex());
        assertEquals("user", result.role());
        assertEquals("Hello world", result.content());
        assertNull(result.toolName());
        assertEquals("2026-03-28T10:00:00Z", result.timestamp());
        assertEquals("Hello world".length() / 4, result.tokenEstimate());
    }

    @Test
    void parseAssistantMessage() {
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"I can help with that."}]},"timestamp":"2026-03-28T10:01:00Z"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 1);

        assertNotNull(result);
        assertEquals("assistant", result.role());
        assertEquals("I can help with that.", result.content());
    }

    @Test
    void malformedJsonLineReturnsNull() {
        String line = "this is not valid json {{{";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNull(result);
    }

    @Test
    void emptyLineReturnsNull() {
        assertNull(parser.parseLine("", "session-1", "/projects/test", 0));
        assertNull(parser.parseLine("   ", "session-1", "/projects/test", 0));
        assertNull(parser.parseLine(null, "session-1", "/projects/test", 0));
    }

    @Test
    void emptyFileReturnsEmptyList(@TempDir Path tempDir) throws IOException {
        Path emptyFile = tempDir.resolve("empty.jsonl");
        Files.writeString(emptyFile, "");

        List<SessionTranscript> results = parser.parseSessionFile(emptyFile);

        assertTrue(results.isEmpty());
    }

    @Test
    void imageContentBlockIsSkipped() {
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"image","source":{"data":"base64data"}}]},"timestamp":"2026-03-28T10:02:00Z"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        // Image-only content produces null (no text content)
        assertNull(result);
    }

    @Test
    void veryLongContentIsTruncated() {
        String longText = "x".repeat(60_000);
        String line = String.format(
                "{\"type\":\"human\",\"message\":{\"role\":\"user\",\"content\":\"%s\"}}", longText);

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals(50_000, result.content().length());
    }

    @Test
    void findSessionFilesWithNoJsonl(@TempDir Path tempDir) throws IOException {
        // Create a non-jsonl file
        Files.writeString(tempDir.resolve("notes.txt"), "just a text file");

        List<Path> files = parser.findSessionFiles(tempDir);

        assertTrue(files.isEmpty());
    }

    @Test
    void findSessionFilesFindsJsonl(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("session-abc.jsonl"), "{}\n");
        Files.writeString(tempDir.resolve("session-def.jsonl"), "{}\n");
        Files.writeString(tempDir.resolve("notes.txt"), "not a session");

        List<Path> files = parser.findSessionFiles(tempDir);

        assertEquals(2, files.size());
    }

    @Test
    void parseSessionFileExtractsSessionIdFromFileName(@TempDir Path tempDir) throws IOException {
        String line = """
                {"type":"human","message":{"role":"user","content":"test message"}}""";
        Path sessionFile = tempDir.resolve("my-session-id.jsonl");
        Files.writeString(sessionFile, line + "\n");

        List<SessionTranscript> results = parser.parseSessionFile(sessionFile);

        assertEquals(1, results.size());
        assertEquals("my-session-id", results.get(0).sessionId());
        assertEquals(tempDir.toString(), results.get(0).projectPath());
    }

    @Test
    void parseSessionFileSkipsMalformedLines(@TempDir Path tempDir) throws IOException {
        String content = """
                {"type":"human","message":{"role":"user","content":"good line"}}
                not valid json
                {"type":"assistant","message":{"role":"assistant","content":"another good line"}}
                """;
        Path sessionFile = tempDir.resolve("mixed.jsonl");
        Files.writeString(sessionFile, content);

        List<SessionTranscript> results = parser.parseSessionFile(sessionFile);

        assertEquals(2, results.size());
        assertEquals("good line", results.get(0).content());
        assertEquals("another good line", results.get(1).content());
    }

    @Test
    void toolUseBlockExtractsToolName() {
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","name":"Read","id":"123","input":{}}]}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("Read", result.toolName());
    }

    @Test
    void findSessionFilesWithNullDir() {
        List<Path> files = parser.findSessionFiles(null);
        assertTrue(files.isEmpty());
    }

    @Test
    void parseSessionFileWithNullPath() {
        List<SessionTranscript> results = parser.parseSessionFile(null);
        assertTrue(results.isEmpty());
    }
}
