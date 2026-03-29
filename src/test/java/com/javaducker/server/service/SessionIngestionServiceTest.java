package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import com.javaducker.server.model.SessionTranscript;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionIngestionServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static SessionIngestionService service;
    static SessionTranscriptParser parser;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-session.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        dataSource = new DuckDBDataSource(config);
        parser = new SessionTranscriptParser();

        // Bootstrap schema
        ArtifactService artifactService = new ArtifactService(dataSource);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);
        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.createSchema();

        service = new SessionIngestionService(dataSource, parser);
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    @Test
    @Order(1)
    void storeAndRetrieveTranscripts() throws Exception {
        List<SessionTranscript> transcripts = List.of(
                new SessionTranscript("sess-001", "/project/a", 0, "user",
                        "How do I fix the auth bug?", null, null, 7),
                new SessionTranscript("sess-001", "/project/a", 1, "assistant",
                        "You need to update the token validation logic.", null, null, 10),
                new SessionTranscript("sess-001", "/project/a", 2, "assistant",
                        "Here is the fix.", "Read", null, 4)
        );

        service.storeTranscripts(transcripts);

        List<Map<String, Object>> retrieved = service.getSession("sess-001");
        assertEquals(3, retrieved.size());
        assertEquals("user", retrieved.get(0).get("role"));
        assertEquals("How do I fix the auth bug?", retrieved.get(0).get("content"));
        assertEquals("assistant", retrieved.get(1).get("role"));
        assertEquals("Read", retrieved.get(2).get("tool_name"));
    }

    @Test
    @Order(2)
    void storeTranscriptsTwiceDeduplicates() throws Exception {
        // Store updated version of same session
        List<SessionTranscript> updated = List.of(
                new SessionTranscript("sess-001", "/project/a", 0, "user",
                        "Updated question", null, null, 5),
                new SessionTranscript("sess-001", "/project/a", 1, "assistant",
                        "Updated answer", null, null, 5)
        );

        service.storeTranscripts(updated);

        List<Map<String, Object>> retrieved = service.getSession("sess-001");
        assertEquals(2, retrieved.size(), "Should only have the latest version");
        assertEquals("Updated question", retrieved.get(0).get("content"));
        assertEquals("Updated answer", retrieved.get(1).get("content"));
    }

    @Test
    @Order(3)
    void getSessionListReturnsSummary() throws Exception {
        // Add a second session
        List<SessionTranscript> sess2 = List.of(
                new SessionTranscript("sess-002", "/project/b", 0, "user",
                        "Tell me about DuckDB", null, null, 6),
                new SessionTranscript("sess-002", "/project/b", 1, "assistant",
                        "DuckDB is an in-process analytical database.", null, null, 10)
        );
        service.storeTranscripts(sess2);

        List<Map<String, Object>> sessions = service.getSessionList();
        assertTrue(sessions.size() >= 2, "Should have at least 2 sessions");

        // Verify summary fields are present
        Map<String, Object> first = sessions.get(0);
        assertNotNull(first.get("session_id"));
        assertNotNull(first.get("message_count"));
        assertNotNull(first.get("total_tokens"));
    }

    @Test
    @Order(4)
    void getSessionForUnknownIdReturnsEmpty() throws Exception {
        List<Map<String, Object>> result = service.getSession("nonexistent-session");
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(5)
    void emptyDatabaseSessionList() throws Exception {
        // Clean up all data
        dataSource.withConnection(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        List<Map<String, Object>> sessions = service.getSessionList();
        assertTrue(sessions.isEmpty());
    }

    @Test
    @Order(6)
    void indexSessionsFromDirectory() throws Exception {
        // Create a temp directory with a sample JSONL session file
        Path sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(sessionsDir);

        String jsonl = """
                {"type":"human","message":{"role":"user","content":"Hello"}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hi there!"}]}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"How can I help?"}]}}
                """;
        Files.writeString(sessionsDir.resolve("test-session-abc.jsonl"), jsonl);

        Map<String, Object> summary = service.indexSessions(sessionsDir.toString(), 0);
        assertEquals(1, summary.get("sessions_indexed"));
        assertEquals(0, summary.get("sessions_skipped"));
        assertTrue((int) summary.get("total_messages") >= 2);

        // Verify session is retrievable
        List<Map<String, Object>> messages = service.getSession("test-session-abc");
        assertFalse(messages.isEmpty());
    }

    @Test
    @Order(7)
    void incrementalIngestionSkipsUnchangedFiles() throws Exception {
        // Create a session file
        Path sessionsDir = tempDir.resolve("sessions-inc");
        Files.createDirectories(sessionsDir);

        String jsonl = """
                {"type":"human","message":{"role":"user","content":"First message"}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"First reply"}]}}
                """;
        Path sessionFile = sessionsDir.resolve("inc-session.jsonl");
        Files.writeString(sessionFile, jsonl);

        // First indexing
        Map<String, Object> first = service.indexSessionsIncremental(sessionsDir.toString(), 0);
        assertEquals(1, first.get("sessions_indexed"));
        assertEquals(0, first.get("sessions_skipped"));

        // Second indexing without file change should skip
        Map<String, Object> second = service.indexSessionsIncremental(sessionsDir.toString(), 0);
        assertEquals(0, second.get("sessions_indexed"));
        assertEquals(1, second.get("sessions_skipped"));
    }

    @Test
    @Order(8)
    void incrementalIngestionReindexesChangedFiles() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions-reindex");
        Files.createDirectories(sessionsDir);

        String jsonl1 = """
                {"type":"human","message":{"role":"user","content":"Original"}}
                """;
        Path sessionFile = sessionsDir.resolve("reindex-session.jsonl");
        Files.writeString(sessionFile, jsonl1);

        // First index
        service.indexSessionsIncremental(sessionsDir.toString(), 0);

        // Modify file (append a message)
        Thread.sleep(1100); // ensure mtime changes (filesystem resolution can be 1s)
        String jsonl2 = """
                {"type":"human","message":{"role":"user","content":"Original"}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"New reply"}]}}
                """;
        Files.writeString(sessionFile, jsonl2);

        // Second index should detect the change
        Map<String, Object> result = service.indexSessionsIncremental(sessionsDir.toString(), 0);
        assertEquals(1, result.get("sessions_indexed"));
        assertEquals(0, result.get("sessions_skipped"));

        // Verify new content
        List<Map<String, Object>> messages = service.getSession("reindex-session");
        // message_index -1 is the sentinel, so filter it out
        long realMessages = messages.stream()
                .filter(m -> (int) m.get("message_index") >= 0)
                .count();
        assertEquals(2, realMessages);
    }
}
