package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitBlameServiceTest {

    private final GitBlameService service = new GitBlameService(null);

    @Test
    void parseValidPorcelainOutput() {
        String output = """
                abc1234567890123456789012345678901234abcd 1 1 1
                author John Doe
                author-mail <john@example.com>
                author-time 1711900000
                author-tz +0000
                committer John Doe
                committer-mail <john@example.com>
                committer-time 1711900000
                committer-tz +0000
                summary Fix the auth bug
                filename src/Main.java
                \tactual line content here
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);

        assertEquals(1, entries.size());
        GitBlameService.BlameEntry entry = entries.get(0);
        assertEquals("abc1234567890123456789012345678901234abcd", entry.commitHash());
        assertEquals("John Doe", entry.author());
        assertEquals(Instant.ofEpochSecond(1711900000), entry.authorDate());
        assertEquals("Fix the auth bug", entry.commitMessage());
        assertEquals("actual line content here", entry.content());
        assertEquals(1, entry.lineStart());
        assertEquals(1, entry.lineEnd());
    }

    @Test
    void consecutiveLinesSameCommitGroupedIntoRange() {
        String output = """
                abc1234567890123456789012345678901234abcd 1 1 3
                author Alice
                author-time 1700000000
                summary Initial commit
                filename src/App.java
                \tline one
                abc1234567890123456789012345678901234abcd 2 2
                \tline two
                abc1234567890123456789012345678901234abcd 3 3
                \tline three
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);

        assertEquals(1, entries.size());
        GitBlameService.BlameEntry entry = entries.get(0);
        assertEquals(1, entry.lineStart());
        assertEquals(3, entry.lineEnd());
        assertEquals("Alice", entry.author());
        assertEquals("Initial commit", entry.commitMessage());
        assertTrue(entry.content().contains("line one"));
        assertTrue(entry.content().contains("line three"));
    }

    @Test
    void differentCommitsSplitIntoSeparateEntries() {
        String output = """
                aaaa234567890123456789012345678901234aaaaa 1 1 1
                author Alice
                author-time 1700000000
                summary First commit
                filename src/App.java
                \tfirst line
                bbbb234567890123456789012345678901234bbbbb 2 2 1
                author Bob
                author-time 1700001000
                summary Second commit
                filename src/App.java
                \tsecond line
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);

        assertEquals(2, entries.size());
        assertEquals("Alice", entries.get(0).author());
        assertEquals(1, entries.get(0).lineStart());
        assertEquals("Bob", entries.get(1).author());
        assertEquals(2, entries.get(1).lineStart());
    }

    @Test
    void emptyOutputReturnsEmptyList() {
        assertEquals(List.of(), service.parseBlameOutput(""));
        assertEquals(List.of(), service.parseBlameOutput(null));
        assertEquals(List.of(), service.parseBlameOutput("   "));
    }

    @Test
    void nullOrBlankFilePathThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> service.blame(null));
        assertThrows(IllegalArgumentException.class, () -> service.blame(""));
        assertThrows(IllegalArgumentException.class, () -> service.blame("   "));
    }

    // ── Path validation tests ─────────────────────────────────────────

    @Test
    void pathOutsideProjectRootThrows() {
        // The service resolves relative to projectRoot; "../../../etc/passwd" should be rejected
        assertThrows(IllegalArgumentException.class, () -> service.blame("../../../etc/passwd"));
    }

    @Test
    void pathTraversalWithDotDotThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.blame("src/../../etc/shadow"));
    }

    // ── Live git blame tests (runs real git on the repo) ────────────────
    // These tests require 'git' to be available on the system PATH.

    @Nested
    class LiveGitBlameTests {

        private static boolean isGitAvailable() {
            try {
                Process p = new ProcessBuilder("git", "--version").start();
                int exit = p.waitFor();
                return exit == 0;
            } catch (Exception e) {
                return false;
            }
        }

        @Test
        void blameOnRealFileReturnsEntries() throws Exception {
            Assumptions.assumeTrue(isGitAvailable(), "git not available, skipping live blame test");
            GitBlameService svc = createServiceWithProjectRoot();
            List<GitBlameService.BlameEntry> entries = svc.blame(
                    "src/main/java/com/javaducker/server/JavaDuckerApplication.java");

            assertFalse(entries.isEmpty(), "Blame should return entries for a tracked file");
            for (GitBlameService.BlameEntry entry : entries) {
                assertNotNull(entry.commitHash());
                assertEquals(40, entry.commitHash().length(), "Commit hash should be 40 chars");
                assertNotNull(entry.author());
                assertTrue(entry.lineStart() > 0);
                assertTrue(entry.lineEnd() >= entry.lineStart());
            }
        }

        @Test
        void blameForLinesOnRealFile() throws Exception {
            Assumptions.assumeTrue(isGitAvailable(), "git not available, skipping live blame test");
            GitBlameService svc = createServiceWithProjectRoot();
            List<GitBlameService.BlameEntry> entries = svc.blameForLines(
                    "src/main/java/com/javaducker/server/JavaDuckerApplication.java", 1, 3);

            assertFalse(entries.isEmpty(), "blameForLines should return entries");
            for (GitBlameService.BlameEntry entry : entries) {
                assertTrue(entry.lineStart() >= 1);
                assertTrue(entry.lineEnd() <= 3);
            }
        }

        @Test
        void blameCacheReturnsSameResult() throws Exception {
            Assumptions.assumeTrue(isGitAvailable(), "git not available, skipping live blame test");
            GitBlameService svc = createServiceWithProjectRoot();
            String file = "src/main/java/com/javaducker/server/JavaDuckerApplication.java";
            List<GitBlameService.BlameEntry> first = svc.blame(file);
            List<GitBlameService.BlameEntry> second = svc.blame(file);

            // Should be the exact same cached list reference
            assertSame(first, second, "Second call should return cached result");
        }

        @Test
        void blameOnNonexistentFileThrowsIOException() throws Exception {
            Assumptions.assumeTrue(isGitAvailable(), "git not available, skipping live blame test");
            GitBlameService svc = createServiceWithProjectRoot();
            assertThrows(java.io.IOException.class,
                    () -> svc.blame("src/main/java/DoesNotExist.java"));
        }

        private GitBlameService createServiceWithProjectRoot() throws Exception {
            GitBlameService svc = new GitBlameService(null);
            // Use reflection to set projectRoot to the actual project directory
            java.io.File root = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile();
            java.lang.reflect.Field field = GitBlameService.class.getDeclaredField("projectRoot");
            field.setAccessible(true);
            field.set(svc, root);
            return svc;
        }
    }

    // ── Parse edge cases ──────────────────────────────────────────────

    @Test
    void parseInvalidAuthorTimeLogsWarning() {
        String output = """
                abc1234567890123456789012345678901234abcd 1 1 1
                author Dev
                author-time not-a-number
                summary Some commit
                filename src/App.java
                \tcode here
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);

        assertEquals(1, entries.size());
        assertNull(entries.get(0).authorDate(), "Invalid author-time should produce null date");
        assertEquals("Dev", entries.get(0).author());
    }

    @Test
    void parseAuthorWithSpecialCharacters() {
        String output = """
                abc1234567890123456789012345678901234abcd 1 1 1
                author José María García-López
                author-time 1711900000
                summary Update: add i18n support for múltiple languages!
                filename src/I18n.java
                \tString greeting = "Hola";
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);

        assertEquals(1, entries.size());
        assertEquals("José María García-López", entries.get(0).author());
        assertEquals("Update: add i18n support for múltiple languages!", entries.get(0).commitMessage());
    }

    @Test
    void parseMultiWordSummaryWithPunctuation() {
        String output = """
                abc1234567890123456789012345678901234abcd 1 1 1
                author Dev
                author-time 1711900000
                summary fix(auth): handle OAuth2 redirect — closes #1234
                filename src/Auth.java
                \tcode here
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);
        assertEquals("fix(auth): handle OAuth2 redirect — closes #1234", entries.get(0).commitMessage());
    }

    @Test
    void parseMissingAuthorTimeFallsBackGracefully() {
        // No author-time line — should result in null authorDate
        String output = """
                abc1234567890123456789012345678901234abcd 1 1 1
                author Ghost
                summary No timestamp commit
                filename src/Ghost.java
                \tghost code
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);

        assertEquals(1, entries.size());
        assertEquals("Ghost", entries.get(0).author());
        assertNull(entries.get(0).authorDate());
    }

    @Test
    void parseNonConsecutiveLinesFromSameCommitCreateSeparateEntries() {
        // Lines 1-2 from commit A, line 3 from commit B, line 4 from commit A again
        String output = """
                aaaa234567890123456789012345678901234aaaaa 1 1 2
                author Alice
                author-time 1700000000
                summary First
                filename src/App.java
                \tline one
                aaaa234567890123456789012345678901234aaaaa 2 2
                \tline two
                bbbb234567890123456789012345678901234bbbbb 3 3 1
                author Bob
                author-time 1700001000
                summary Second
                filename src/App.java
                \tline three
                aaaa234567890123456789012345678901234aaaaa 4 4 1
                \tline four
                """;

        List<GitBlameService.BlameEntry> entries = service.parseBlameOutput(output);

        assertEquals(3, entries.size());
        // First range: lines 1-2 from Alice
        assertEquals(1, entries.get(0).lineStart());
        assertEquals(2, entries.get(0).lineEnd());
        assertEquals("Alice", entries.get(0).author());
        // Second: line 3 from Bob
        assertEquals(3, entries.get(1).lineStart());
        assertEquals(3, entries.get(1).lineEnd());
        assertEquals("Bob", entries.get(1).author());
        // Third: line 4 from Alice again (separate entry since non-consecutive)
        assertEquals(4, entries.get(2).lineStart());
        assertEquals(4, entries.get(2).lineEnd());
        assertEquals("Alice", entries.get(2).author());
    }

    // ── DB-backed tests ───────────────────────────────────────────────

    @Nested
    class DbBackedTests {

        @TempDir
        Path tempDir;

        DuckDBDataSource dataSource;
        GitBlameService dbService;

        @BeforeEach
        void setupDb() throws Exception {
            AppConfig config = new AppConfig();
            config.setDbPath(tempDir.resolve("test-blame.duckdb").toString());
            config.setIntakeDir(tempDir.resolve("intake").toString());
            dataSource = new DuckDBDataSource(config);
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
            dbService = new GitBlameService(dataSource);
        }

        @AfterEach
        void teardown() throws Exception {
            dataSource.close();
        }

        @Test
        void blameForArtifactLooksUpPathFromDb() throws Exception {
            // Seed an artifact with original_client_path
            Connection conn = dataSource.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    INSERT INTO artifacts (artifact_id, file_name, original_client_path, status, created_at, updated_at)
                    VALUES ('art-blame-1', 'Svc.java', 'src/main/Svc.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
            }

            // blameForArtifact looks up the path from DB then calls blame().
            // In test env, the resolved path may be outside PROJECT_ROOT (defaults to "."),
            // so we expect either an IllegalArgumentException (path validation) or IOException (git fails).
            // The key assertion: it does NOT throw "Artifact not found", proving DB lookup succeeded.
            try {
                dbService.blameForArtifact("art-blame-1");
                fail("Expected an exception from blame in test environment");
            } catch (IllegalArgumentException e) {
                // Path validation rejected it -- but NOT "Artifact not found"
                assertFalse(e.getMessage().contains("Artifact not found"),
                        "DB lookup should succeed; path validation may reject: " + e.getMessage());
            } catch (java.io.IOException e) {
                // git blame failed -- also acceptable, DB lookup still worked
                assertTrue(e.getMessage().contains("git blame failed"));
            }
        }

        @Test
        void blameForArtifactNotFoundThrows() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> dbService.blameForArtifact("nonexistent-artifact-id"));
            assertTrue(ex.getMessage().contains("Artifact not found"));
        }

        @Test
        void blameForArtifactWithNullClientPathThrows() throws Exception {
            // Artifact exists but has null original_client_path
            Connection conn = dataSource.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    INSERT INTO artifacts (artifact_id, file_name, status, created_at, updated_at)
                    VALUES ('art-no-path', 'NoPath.java', 'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
            }

            // original_client_path is null, so DB returns null => "Artifact not found"
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> dbService.blameForArtifact("art-no-path"));
            assertTrue(ex.getMessage().contains("Artifact not found"));
        }
    }
}
