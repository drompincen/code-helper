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
import java.util.*;

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

    @Test
    @Order(9)
    void storeAndRetrieveDecisions() throws Exception {
        List<Map<String, String>> decisions = List.of(
                Map.of("text", "Use JWT for auth", "context", "Security discussion", "tags", "auth,security"),
                Map.of("text", "DuckDB for analytics", "context", "DB choice", "tags", "database")
        );

        Map<String, Object> result = service.storeDecisions("sess-dec-001", decisions);
        assertEquals("sess-dec-001", result.get("session_id"));
        assertEquals(2, result.get("decisions_stored"));

        // Retrieve all decisions (no filter)
        List<Map<String, Object>> all = service.getRecentDecisions(5, null);
        assertTrue(all.size() >= 2, "Should have at least 2 decisions");
        assertTrue(all.stream().anyMatch(d -> "Use JWT for auth".equals(d.get("decision_text"))));
        assertTrue(all.stream().anyMatch(d -> "DuckDB for analytics".equals(d.get("decision_text"))));
    }

    @Test
    @Order(10)
    void getRecentDecisionsWithTagFilter() throws Exception {
        // Ensure decisions from previous test exist, then filter by tag
        List<Map<String, Object>> authDecisions = service.getRecentDecisions(5, "auth");
        assertFalse(authDecisions.isEmpty(), "Should find decisions tagged with 'auth'");
        assertTrue(authDecisions.stream().allMatch(d -> {
            String tags = (String) d.get("tags");
            return tags != null && tags.toLowerCase().contains("auth");
        }));

        // Filter by a tag that doesn't exist
        List<Map<String, Object>> noMatch = service.getRecentDecisions(5, "nonexistent-tag-xyz");
        assertTrue(noMatch.isEmpty(), "Should find no decisions for nonexistent tag");
    }

    @Test
    @Order(11)
    void getRecentDecisionsNoFilter() throws Exception {
        // Add decisions for a second session
        List<Map<String, String>> moreDecisions = List.of(
                Map.of("text", "Use React for frontend", "tags", "frontend")
        );
        service.storeDecisions("sess-dec-002", moreDecisions);

        // Retrieve all without filter
        List<Map<String, Object>> all = service.getRecentDecisions(10, null);
        assertTrue(all.size() >= 3, "Should have at least 3 decisions across sessions");

        // Verify decisions from both sessions are present
        Set<String> sessionIds = new HashSet<>();
        for (Map<String, Object> d : all) {
            sessionIds.add((String) d.get("session_id"));
        }
        assertTrue(sessionIds.contains("sess-dec-001"));
        assertTrue(sessionIds.contains("sess-dec-002"));
    }

    @Test
    @Order(12)
    void searchSessionsFindsMatchingContent() throws Exception {
        // Clear and insert known data
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        List<SessionTranscript> transcripts = List.of(
                new SessionTranscript("search-sess-1", "/project", 0, "user",
                        "How do I configure DuckDB connection pooling?", null, null, 10),
                new SessionTranscript("search-sess-1", "/project", 1, "assistant",
                        "You can configure DuckDB connection pooling by setting the pool size parameter.", null, null, 15),
                new SessionTranscript("search-sess-2", "/project", 0, "user",
                        "Tell me about Spring Boot actuator endpoints.", null, null, 8)
        );
        service.storeTranscripts(List.of(transcripts.get(0), transcripts.get(1)));
        // Store second session separately
        service.storeTranscripts(List.of(transcripts.get(2)));

        // Search for "DuckDB"
        List<Map<String, Object>> results = service.searchSessions("DuckDB", 10);
        assertFalse(results.isEmpty(), "Should find results matching 'DuckDB'");
        assertTrue(results.stream().allMatch(r ->
                ((String) r.get("preview")).toLowerCase().contains("duckdb")),
                "All results should contain the search phrase");

        // Verify preview field is present
        for (Map<String, Object> r : results) {
            assertNotNull(r.get("preview"));
            assertNotNull(r.get("session_id"));
            assertNotNull(r.get("role"));
        }
    }

    @Test
    @Order(13)
    void searchSessionsPreviewTruncation() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        // Create content longer than 300 chars containing the search phrase
        String longContent = "x".repeat(200) + "FINDME" + "y".repeat(200);
        assertTrue(longContent.length() > 300, "Content should be > 300 chars");

        List<SessionTranscript> transcripts = List.of(
                new SessionTranscript("trunc-sess", "/project", 0, "user",
                        longContent, null, null, 100)
        );
        service.storeTranscripts(transcripts);

        List<Map<String, Object>> results = service.searchSessions("FINDME", 10);
        assertFalse(results.isEmpty());
        String preview = (String) results.get(0).get("preview");
        assertTrue(preview.endsWith("..."),
                "Preview should be truncated with '...' for content > 300 chars");
        assertTrue(preview.length() <= 304,
                "Preview should be at most 303 chars + '...', got: " + preview.length());
    }

    @Test
    @Order(14)
    void searchSessionsNoMatch() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        List<SessionTranscript> transcripts = List.of(
                new SessionTranscript("nomatch-sess", "/project", 0, "user",
                        "Regular content about Java programming", null, null, 7)
        );
        service.storeTranscripts(transcripts);

        List<Map<String, Object>> results = service.searchSessions("xyznonexistent123", 10);
        assertTrue(results.isEmpty(), "Should return empty for non-matching phrase");
    }

    @Test
    @Order(15)
    void indexSessionsWithMaxSessionsLimit() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        Path sessionsDir = tempDir.resolve("sessions-limit");
        Files.createDirectories(sessionsDir);

        // Create 5 session files
        for (int i = 1; i <= 5; i++) {
            String jsonl = String.format(
                    "{\"type\":\"human\",\"message\":{\"role\":\"user\",\"content\":\"Session %d message\"}}%n", i);
            Files.writeString(sessionsDir.resolve("limit-session-" + i + ".jsonl"), jsonl);
            // Ensure different mtimes
            Thread.sleep(100);
        }

        // Index with maxSessions=2 — should only index the 2 newest
        Map<String, Object> summary = service.indexSessions(sessionsDir.toString(), 2);
        assertEquals(2, summary.get("sessions_indexed"),
                "Should only index 2 sessions when maxSessions=2");
    }

    @Test
    @Order(16)
    void storeTranscriptsWithMtimeSentinel() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        long fakeMtime = 1700000000000L;
        List<SessionTranscript> transcripts = List.of(
                new SessionTranscript("mtime-sess", "/project", 0, "user",
                        "Hello mtime test", null, null, 5),
                new SessionTranscript("mtime-sess", "/project", 1, "assistant",
                        "Mtime test reply", null, null, 5)
        );
        service.storeTranscriptsWithMtime(transcripts, fakeMtime);

        // Verify sentinel row with message_index = -1
        String storedMtime = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT content FROM session_transcripts WHERE session_id = ? AND message_index = -1")) {
                ps.setString(1, "mtime-sess");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Sentinel row should exist");
                    return rs.getString("content");
                }
            }
        });
        assertEquals(String.valueOf(fakeMtime), storedMtime,
                "Sentinel row should contain the file mtime");

        // Verify actual transcripts are also stored
        List<Map<String, Object>> messages = service.getSession("mtime-sess");
        long realMessages = messages.stream()
                .filter(m -> (int) m.get("message_index") >= 0)
                .count();
        assertEquals(2, realMessages, "Should have 2 real transcript messages");
    }

    @Test
    @Order(17)
    void storeTranscriptsWithMtimeReplacesOnReindex() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        // First store
        List<SessionTranscript> first = List.of(
                new SessionTranscript("reindex-mtime", "/project", 0, "user", "First", null, null, 3)
        );
        service.storeTranscriptsWithMtime(first, 1000L);

        // Second store with different mtime — should replace
        List<SessionTranscript> second = List.of(
                new SessionTranscript("reindex-mtime", "/project", 0, "user", "Second", null, null, 4),
                new SessionTranscript("reindex-mtime", "/project", 1, "assistant", "Reply", null, null, 3)
        );
        service.storeTranscriptsWithMtime(second, 2000L);

        // Verify only second batch exists
        List<Map<String, Object>> messages = service.getSession("reindex-mtime");
        long realMessages = messages.stream()
                .filter(m -> (int) m.get("message_index") >= 0)
                .count();
        assertEquals(2, realMessages, "Should have 2 messages from second store");
        assertEquals("Second",
                messages.stream()
                        .filter(m -> (int) m.get("message_index") == 0)
                        .findFirst().get().get("content"));

        // Verify sentinel has updated mtime
        String mtime = dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT content FROM session_transcripts WHERE session_id = ? AND message_index = -1")) {
                ps.setString(1, "reindex-mtime");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString("content");
                }
            }
        });
        assertEquals("2000", mtime);
    }

    @Test
    @Order(18)
    void getSessionListWithMultipleSessions() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        // Store 3 sessions with different message counts
        service.storeTranscripts(List.of(
                new SessionTranscript("multi-1", "/project/a", 0, "user", "Q1", null, null, 5),
                new SessionTranscript("multi-1", "/project/a", 1, "assistant", "A1", null, null, 10)
        ));
        service.storeTranscripts(List.of(
                new SessionTranscript("multi-2", "/project/b", 0, "user", "Q2", null, null, 3),
                new SessionTranscript("multi-2", "/project/b", 1, "assistant", "A2", null, null, 7),
                new SessionTranscript("multi-2", "/project/b", 2, "user", "Q3", null, null, 4)
        ));
        service.storeTranscripts(List.of(
                new SessionTranscript("multi-3", "/project/c", 0, "user", "Single", null, null, 6)
        ));

        List<Map<String, Object>> sessions = service.getSessionList();
        assertEquals(3, sessions.size(), "Should have exactly 3 sessions");

        // Verify grouping and counts
        Map<String, Integer> countBySession = new HashMap<>();
        for (Map<String, Object> s : sessions) {
            countBySession.put((String) s.get("session_id"), (int) s.get("message_count"));
        }
        assertEquals(2, countBySession.get("multi-1"));
        assertEquals(3, countBySession.get("multi-2"));
        assertEquals(1, countBySession.get("multi-3"));

        // Verify total_tokens field is summed correctly
        for (Map<String, Object> s : sessions) {
            long tokens = (long) s.get("total_tokens");
            assertTrue(tokens > 0, "total_tokens should be positive for session " + s.get("session_id"));
        }
    }

    @Test
    @Order(19)
    void storeTranscriptsWithTimestamp() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        // Valid timestamp
        List<SessionTranscript> transcripts = List.of(
                new SessionTranscript("ts-sess", "/project", 0, "user",
                        "Hello", null, "2025-01-15 10:30:00", 5),
                // Invalid timestamp string — should store null timestamp
                new SessionTranscript("ts-sess", "/project", 1, "assistant",
                        "Reply", null, "not-a-timestamp", 5),
                // Null timestamp
                new SessionTranscript("ts-sess", "/project", 2, "user",
                        "Follow up", null, null, 4)
        );
        service.storeTranscripts(transcripts);

        List<Map<String, Object>> messages = service.getSession("ts-sess");
        assertEquals(3, messages.size());
        // First message should have a valid timestamp
        assertNotNull(messages.get(0).get("timestamp"),
                "Valid timestamp should be stored");
        // Invalid timestamp should be null
        assertNull(messages.get(1).get("timestamp"),
                "Invalid timestamp should be stored as null");
        // Null timestamp should be null
        assertNull(messages.get(2).get("timestamp"),
                "Null timestamp should remain null");
    }

    @Test
    @Order(20)
    void storeEmptyTranscriptsIsNoOp() throws Exception {
        // Should not throw and should not insert anything
        assertDoesNotThrow(() -> service.storeTranscripts(List.of()));
        assertDoesNotThrow(() -> service.storeTranscriptsWithMtime(List.of(), 1000L));
    }

    @Test
    @Order(21)
    void searchSessionsExcludesSentinelRows() throws Exception {
        dataSource.withConnection(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM session_transcripts");
            }
            return null;
        });

        // Store with mtime (creates sentinel row with message_index = -1)
        List<SessionTranscript> transcripts = List.of(
                new SessionTranscript("sentinel-test", "/project", 0, "user",
                        "Search for this content", null, null, 5)
        );
        service.storeTranscriptsWithMtime(transcripts, 999L);

        // The sentinel row content is "999" — search for it should NOT match
        // because searchSessions filters message_index >= 0
        List<Map<String, Object>> results = service.searchSessions("999", 10);
        assertTrue(results.isEmpty(),
                "Sentinel rows (message_index = -1) should be excluded from search");

        // But real content should be searchable
        List<Map<String, Object>> realResults = service.searchSessions("Search for this", 10);
        assertFalse(realResults.isEmpty(), "Real content should be searchable");
    }
}
