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

    @Test
    void parseSessionFileWithNonexistentPath() {
        List<SessionTranscript> results = parser.parseSessionFile(Path.of("/does/not/exist.jsonl"));
        assertTrue(results.isEmpty());
    }

    @Test
    void findSessionFilesWithNonDirectoryPath(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("not-a-dir.txt");
        Files.writeString(file, "text");
        List<Path> files = parser.findSessionFiles(file);
        assertTrue(files.isEmpty());
    }

    // ── Content array processing ─────────────────────────────────────

    @Test
    void parseToolResultContentBlock() {
        // tool_result block with nested text content
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_result","content":[{"type":"text","text":"result output"}]}]}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("result output", result.content());
    }

    @Test
    void parseToolResultWithStringContent() {
        // tool_result block with string content (not array)
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_result","content":"plain result text"}]}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("plain result text", result.content());
    }

    @Test
    void parseMixedContentBlocks() {
        // Mix of text and tool_use blocks
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Before tool"},{"type":"tool_use","name":"Bash","id":"1","input":{}},{"type":"text","text":"After tool"}]}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertTrue(result.content().contains("Before tool"));
        assertTrue(result.content().contains("[tool_use: Bash]"));
        assertTrue(result.content().contains("After tool"));
        assertEquals("Bash", result.toolName());
    }

    @Test
    void parseContentArrayWithOnlyImages() {
        // Content array with only image blocks = no text = null
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"image","source":{"data":"abc"}},{"type":"image","source":{"data":"def"}}]}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);
        assertNull(result, "Image-only content should produce null");
    }

    @Test
    void parseToolResultWithEmptyNestedContent() {
        // tool_result with nested content that produces empty text
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_result","content":[{"type":"image","source":{}}]}]}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);
        assertNull(result, "tool_result with only image nested content produces null");
    }

    // ── Role extraction edge cases ───────────────────────────────────

    @Test
    void parseRoleFromTypeFallback() {
        // No message.role, only type field
        String line = """
                {"type":"tool_result","content":"some result text"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("tool", result.role());
    }

    @Test
    void parseRoleFromUnknownType() {
        // Unknown type falls through to default
        String line = """
                {"type":"system_notice","content":"system text"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("system_notice", result.role());
    }

    @Test
    void parseRoleUnknownWhenNoTypeOrRole() {
        // No type field and no message.role
        String line = """
                {"content":"orphan content"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("unknown", result.role());
    }

    // ── Tool name extraction from type field ─────────────────────────

    @Test
    void toolNameExtractedFromToolUseType() {
        String line = """
                {"type":"tool_use","name":"Read","content":"reading file"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("Read", result.toolName());
    }

    // ── Top-level content (no message wrapper) ───────────────────────

    @Test
    void parseTopLevelStringContent() {
        String line = """
                {"type":"human","content":"direct content without message wrapper","timestamp":"2026-03-28T10:00:00Z"}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("direct content without message wrapper", result.content());
    }

    @Test
    void parseTopLevelArrayContent() {
        String line = """
                {"type":"assistant","content":[{"type":"text","text":"top-level array text"}]}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);

        assertNotNull(result);
        assertEquals("top-level array text", result.content());
    }

    // ── Null content field ───────────────────────────────────────────

    @Test
    void parseMessageWithNullContentField() {
        // JSON with explicit null content
        String line = """
                {"type":"human","message":{"role":"user","content":null}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);
        assertNull(result, "Null content should result in null transcript");
    }

    @Test
    void parseMessageWithNoContentField() {
        String line = """
                {"type":"human","message":{"role":"user"}}""";

        SessionTranscript result = parser.parseLine(line, "session-1", "/projects/test", 0);
        assertNull(result, "Missing content field should result in null transcript");
    }

    // ── File name without .jsonl extension ───────────────────────────

    @Test
    void parseSessionFileWithoutJsonlExtension(@TempDir Path tempDir) throws IOException {
        String line = """
                {"type":"human","message":{"role":"user","content":"test msg"}}""";
        Path sessionFile = tempDir.resolve("session-no-ext");
        Files.writeString(sessionFile, line + "\n");

        List<SessionTranscript> results = parser.parseSessionFile(sessionFile);

        assertEquals(1, results.size());
        assertEquals("session-no-ext", results.get(0).sessionId());
    }
}
