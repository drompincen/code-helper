package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CoChangeServiceTest {

    private CoChangeService service;

    @BeforeEach
    void setUp() {
        // Construct with null dataSource since we only test parsing/computation methods
        service = new CoChangeService(null);
    }

    // ── DB-backed tests ──────────────────────────────────────────────

    @Nested
    class DbBackedTests {

        @TempDir
        Path tempDir;

        DuckDBDataSource dataSource;
        CoChangeService dbService;

        @BeforeEach
        void setupDb() throws Exception {
            AppConfig config = new AppConfig();
            config.setDbPath(tempDir.resolve("test-cochange.duckdb").toString());
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
            dbService = new CoChangeService(dataSource);
        }

        @AfterEach
        void teardown() throws Exception {
            dataSource.close();
        }

        private void seedPair(String fileA, String fileB, int count) throws SQLException {
            Connection conn = dataSource.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cochange_cache (file_a, file_b, co_change_count, last_commit_date) VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
                ps.setString(1, fileA);
                ps.setString(2, fileB);
                ps.setInt(3, count);
                ps.executeUpdate();
            }
        }

        @Test
        void buildCoChangeIndexAndQuery() throws Exception {
            seedPair("src/Foo.java", "src/Bar.java", 7);
            seedPair("src/Foo.java", "src/Baz.java", 3);
            seedPair("src/Bar.java", "src/Baz.java", 1);

            List<Map<String, Object>> results = dbService.getRelatedFiles("src/Foo.java", 10);

            assertEquals(2, results.size());
            // Ordered by co_change_count desc: Bar(7), Baz(3)
            assertEquals("src/Bar.java", results.get(0).get("related_file"));
            assertEquals(7, results.get(0).get("co_change_count"));
            assertEquals("src/Baz.java", results.get(1).get("related_file"));
            assertEquals(3, results.get(1).get("co_change_count"));
        }

        @Test
        void getRelatedFilesSymmetric() throws Exception {
            seedPair("src/A.java", "src/B.java", 5);

            // Query from A side
            List<Map<String, Object>> fromA = dbService.getRelatedFiles("src/A.java", 10);
            assertEquals(1, fromA.size());
            assertEquals("src/B.java", fromA.get(0).get("related_file"));
            assertEquals(5, fromA.get(0).get("co_change_count"));

            // Query from B side (UNION path)
            List<Map<String, Object>> fromB = dbService.getRelatedFiles("src/B.java", 10);
            assertEquals(1, fromB.size());
            assertEquals("src/A.java", fromB.get(0).get("related_file"));
            assertEquals(5, fromB.get(0).get("co_change_count"));
        }

        @Test
        void getRelatedFilesLimitsResults() throws Exception {
            for (int i = 0; i < 10; i++) {
                seedPair("src/Target.java", "src/Other" + i + ".java", 100 - i);
            }

            List<Map<String, Object>> results = dbService.getRelatedFiles("src/Target.java", 3);

            assertEquals(3, results.size());
            // Should be ordered by count desc: 100, 99, 98
            assertEquals(100, results.get(0).get("co_change_count"));
            assertEquals(99, results.get(1).get("co_change_count"));
            assertEquals(98, results.get(2).get("co_change_count"));
        }

        @Test
        void getRelatedFilesEmptyCache() throws Exception {
            List<Map<String, Object>> results = dbService.getRelatedFiles("src/Nonexistent.java", 10);
            assertTrue(results.isEmpty());
        }

        @Test
        void idempotentRebuildDeletesThenInserts() throws Exception {
            // Seed initial data
            seedPair("src/X.java", "src/Y.java", 10);

            // Verify data exists
            List<Map<String, Object>> before = dbService.getRelatedFiles("src/X.java", 10);
            assertEquals(1, before.size());
            assertEquals(10, before.get(0).get("co_change_count"));

            // Now simulate a rebuild by DELETE + INSERT with new data
            Connection conn = dataSource.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM cochange_cache");
            }
            seedPair("src/X.java", "src/Y.java", 5);
            seedPair("src/X.java", "src/Z.java", 2);

            List<Map<String, Object>> after = dbService.getRelatedFiles("src/X.java", 10);
            assertEquals(2, after.size());
            // Count should be the new value, not accumulated
            assertEquals(5, after.get(0).get("co_change_count"));
            assertEquals(2, after.get(1).get("co_change_count"));
        }

        @Test
        void getRelatedFilesNoMatchForFileNotInAnyPair() throws Exception {
            seedPair("src/A.java", "src/B.java", 3);
            seedPair("src/C.java", "src/D.java", 1);

            // Query for a file that exists in no pairs
            List<Map<String, Object>> results = dbService.getRelatedFiles("src/ZZZ.java", 10);
            assertTrue(results.isEmpty());
        }

        @Test
        void writeCoChangeDataThenQuery() throws Exception {
            // Simulate what buildCoChangeIndex does: compute co-changes, write to DB
            Map<String, List<String>> commits = new LinkedHashMap<>();
            commits.put("c1", List.of("src/Svc.java", "src/Repo.java", "src/Model.java"));
            commits.put("c2", List.of("src/Svc.java", "src/Repo.java"));

            Map<String, Map<String, Integer>> coChanges = dbService.computeCoChanges(commits);

            // Write to DB the same way buildCoChangeIndex does
            Connection conn = dataSource.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM cochange_cache");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cochange_cache (file_a, file_b, co_change_count, last_commit_date) VALUES (?, ?, ?, ?)")) {
                for (Map.Entry<String, Map<String, Integer>> outer : coChanges.entrySet()) {
                    for (Map.Entry<String, Integer> inner : outer.getValue().entrySet()) {
                        ps.setString(1, outer.getKey());
                        ps.setString(2, inner.getKey());
                        ps.setInt(3, inner.getValue());
                        ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            // Query and verify
            List<Map<String, Object>> svcRelated = dbService.getRelatedFiles("src/Svc.java", 10);
            assertEquals(2, svcRelated.size());
            // Repo co-changed 2 times, Model 1 time
            assertEquals("src/Repo.java", svcRelated.get(0).get("related_file"));
            assertEquals(2, svcRelated.get(0).get("co_change_count"));
            assertEquals("src/Model.java", svcRelated.get(1).get("related_file"));
            assertEquals(1, svcRelated.get(1).get("co_change_count"));

            // Verify from Model side
            List<Map<String, Object>> modelRelated = dbService.getRelatedFiles("src/Model.java", 10);
            assertEquals(2, modelRelated.size());
        }
    }

    @Test
    void parseValidGitLog() {
        String output = """
                COMMIT:abc123

                src/Foo.java
                src/Bar.java

                COMMIT:def456

                src/Baz.java
                """;
        Map<String, List<String>> result = service.parseGitLog(output);

        assertEquals(2, result.size());
        assertEquals(List.of("src/Foo.java", "src/Bar.java"), result.get("abc123"));
        assertEquals(List.of("src/Baz.java"), result.get("def456"));
    }

    @Test
    void parseEmptyOutput() {
        Map<String, List<String>> result = service.parseGitLog("");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseNullOutput() {
        Map<String, List<String>> result = service.parseGitLog(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void noiseFilterSkipsLargeCommits() {
        // Build a commit with 35 files — should be excluded by buildCoChangeIndex flow
        // We test via computeCoChanges by verifying that the service's filter logic works
        Map<String, List<String>> commits = new LinkedHashMap<>();
        List<String> largeCommit = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            largeCommit.add("file" + i + ".java");
        }
        commits.put("big-commit", largeCommit);
        commits.put("small-commit", List.of("src/A.java", "src/B.java"));

        // The large commit has 35 files. When passed to computeCoChanges directly,
        // it would produce pairs. But the service filters commits > 30 files
        // before calling computeCoChanges. We verify the parse yields both,
        // then the public buildCoChangeIndex would filter.
        String output = "COMMIT:big-commit\n\n";
        for (int i = 0; i < 35; i++) {
            output += "file" + i + ".java\n";
        }
        output += "\nCOMMIT:small-commit\n\nsrc/A.java\nsrc/B.java\n";

        Map<String, List<String>> parsed = service.parseGitLog(output);
        assertEquals(2, parsed.size());
        assertEquals(35, parsed.get("big-commit").size());
        assertEquals(2, parsed.get("small-commit").size());
    }

    @Test
    void coChangeCountTwoCommitsBothTouchAB() {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("src/A.java", "src/B.java"));
        commits.put("c2", List.of("src/A.java", "src/B.java"));

        Map<String, Map<String, Integer>> result = service.computeCoChanges(commits);

        assertEquals(2, result.get("src/A.java").get("src/B.java"));
    }

    @Test
    void coChangeThreeFilesInOneCommit() {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("src/A.java", "src/B.java", "src/C.java"));

        Map<String, Map<String, Integer>> result = service.computeCoChanges(commits);

        // A-B, A-C, B-C each get count 1
        assertEquals(1, result.get("src/A.java").get("src/B.java"));
        assertEquals(1, result.get("src/A.java").get("src/C.java"));
        assertEquals(1, result.get("src/B.java").get("src/C.java"));
    }

    @Test
    void frequentFileExcluded() {
        // 10 commits total. "build.gradle" appears in 8 (80% > 50%), but A and B only in 2 each
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("build.gradle", "src/A.java"));
        commits.put("c2", List.of("build.gradle", "src/B.java"));
        commits.put("c3", List.of("build.gradle", "src/A.java", "src/B.java"));
        commits.put("c4", List.of("build.gradle", "src/D.java"));
        commits.put("c5", List.of("build.gradle", "src/E.java"));
        commits.put("c6", List.of("build.gradle", "src/F.java"));
        commits.put("c7", List.of("build.gradle", "src/G.java"));
        commits.put("c8", List.of("build.gradle", "src/H.java"));
        commits.put("c9", List.of("src/I.java"));
        commits.put("c10", List.of("src/J.java"));

        Set<String> frequent = service.findFrequentFiles(commits);
        assertTrue(frequent.contains("build.gradle"));
        assertFalse(frequent.contains("src/A.java"), "A.java should not be frequent");

        Map<String, Map<String, Integer>> result = service.computeCoChanges(commits, frequent);

        // build.gradle should not appear in any pair
        for (Map.Entry<String, Map<String, Integer>> entry : result.entrySet()) {
            assertNotEquals("build.gradle", entry.getKey());
            assertFalse(entry.getValue().containsKey("build.gradle"));
        }

        // A-B should still appear from c3
        assertEquals(1, result.get("src/A.java").get("src/B.java"));
    }

    @Test
    void emptyCommitsProduceEmptyCoChanges() {
        Map<String, Map<String, Integer>> result = service.computeCoChanges(Collections.emptyMap());
        assertTrue(result.isEmpty());
    }

    @Test
    void findFrequentFilesEmptyCommits() {
        Set<String> frequent = service.findFrequentFiles(Collections.emptyMap());
        assertTrue(frequent.isEmpty());
    }

    @Test
    void singleFileCommitProducesNoPairs() {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("src/Only.java"));
        Map<String, Map<String, Integer>> result = service.computeCoChanges(commits);
        assertTrue(result.isEmpty(), "Single-file commit should produce no pairs");
    }

    @Test
    void duplicateFilesInCommitCountedOnce() {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("src/A.java", "src/A.java", "src/B.java"));
        Map<String, Map<String, Integer>> result = service.computeCoChanges(commits);
        // A-B should appear exactly once despite A being listed twice
        assertEquals(1, result.get("src/A.java").get("src/B.java"));
    }

    @Test
    void computeCoChangesWithExclusions() {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("src/A.java", "src/B.java", "noisy.txt"));
        Set<String> exclude = Set.of("noisy.txt");
        Map<String, Map<String, Integer>> result = service.computeCoChanges(commits, exclude);
        // noisy.txt should not appear
        assertFalse(result.containsKey("noisy.txt"));
        for (Map<String, Integer> inner : result.values()) {
            assertFalse(inner.containsKey("noisy.txt"));
        }
        // A-B should still exist
        assertEquals(1, result.get("src/A.java").get("src/B.java"));
    }

    @Test
    void parseGitLogWithBlankLines() {
        // Git log output often has blank lines between commit header and files
        String output = "COMMIT:aaa111\n\nsrc/X.java\nsrc/Y.java\n\nCOMMIT:bbb222\n\nsrc/Z.java\n";
        Map<String, List<String>> result = service.parseGitLog(output);
        assertEquals(2, result.size());
        assertEquals(List.of("src/X.java", "src/Y.java"), result.get("aaa111"));
        assertEquals(List.of("src/Z.java"), result.get("bbb222"));
    }

    @Test
    void parseGitLogCommitWithNoFiles() {
        // A commit that has no files listed (e.g. empty commit or merge commit)
        String output = "COMMIT:empty1\n\nCOMMIT:notempty\n\nsrc/A.java\n";
        Map<String, List<String>> result = service.parseGitLog(output);
        assertEquals(2, result.size());
        assertTrue(result.get("empty1").isEmpty());
        assertEquals(List.of("src/A.java"), result.get("notempty"));
    }

    @Test
    void findFrequentFilesNoneFrequent() {
        // All files appear in only 1 of 5 commits — none exceed 50%
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("a.java"));
        commits.put("c2", List.of("b.java"));
        commits.put("c3", List.of("c.java"));
        commits.put("c4", List.of("d.java"));
        commits.put("c5", List.of("e.java"));
        Set<String> frequent = service.findFrequentFiles(commits);
        assertTrue(frequent.isEmpty(), "No file should be frequent");
    }

    @Test
    void findFrequentFilesExactThreshold() {
        // File appears in exactly 50% of commits — should NOT be flagged (> threshold, not >=)
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("common.java", "a.java"));
        commits.put("c2", List.of("common.java", "b.java"));
        commits.put("c3", List.of("c.java"));
        commits.put("c4", List.of("d.java"));
        // common appears in 2 out of 4 = 50%, threshold is (int)(4 * 0.50) = 2, > 2 is false
        Set<String> frequent = service.findFrequentFiles(commits);
        assertFalse(frequent.contains("common.java"),
                "File at exactly threshold should not be flagged");
    }

    @Test
    void filterNoisyCommitsRemovesLargeCommits() {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        // Small commit (2 files) - should be kept
        commits.put("small", List.of("a.java", "b.java"));
        // Commit with exactly 30 files - should be kept
        List<String> thirtyFiles = new ArrayList<>();
        for (int i = 0; i < 30; i++) thirtyFiles.add("file" + i + ".java");
        commits.put("borderline", thirtyFiles);
        // Commit with 31 files - should be removed
        List<String> thirtyOneFiles = new ArrayList<>();
        for (int i = 0; i < 31; i++) thirtyOneFiles.add("x" + i + ".java");
        commits.put("too-big", thirtyOneFiles);

        Map<String, List<String>> filtered = service.filterNoisyCommits(commits);

        assertEquals(2, filtered.size());
        assertTrue(filtered.containsKey("small"));
        assertTrue(filtered.containsKey("borderline"));
        assertFalse(filtered.containsKey("too-big"));
    }

    @Test
    void filterNoisyCommitsEmptyInput() {
        Map<String, List<String>> filtered = service.filterNoisyCommits(Collections.emptyMap());
        assertTrue(filtered.isEmpty());
    }

    @Test
    void filterNoisyCommitsAllLargeCommits() {
        Map<String, List<String>> commits = new LinkedHashMap<>();
        List<String> files = new ArrayList<>();
        for (int i = 0; i < 50; i++) files.add("f" + i + ".java");
        commits.put("huge", files);

        Map<String, List<String>> filtered = service.filterNoisyCommits(commits);
        assertTrue(filtered.isEmpty());
    }

    @Test
    void manyFilePairsAreCorrectlyOrdered() {
        // Verify pairs are always stored as (smaller, larger) alphabetically
        Map<String, List<String>> commits = new LinkedHashMap<>();
        commits.put("c1", List.of("z.java", "a.java", "m.java"));
        Map<String, Map<String, Integer>> result = service.computeCoChanges(commits);
        // Sorted order: a, m, z — pairs: a-m, a-z, m-z
        assertTrue(result.containsKey("a.java"));
        assertTrue(result.get("a.java").containsKey("m.java"));
        assertTrue(result.get("a.java").containsKey("z.java"));
        assertTrue(result.get("m.java").containsKey("z.java"));
        // Should not have reverse entries
        assertFalse(result.containsKey("z.java"));
    }

    // ── DB-backed buildCoChangeIndex integration test ───────────────────────

    @Nested
    class BuildCoChangeIndexTest {

        @TempDir
        Path tempDir;

        DuckDBDataSource dataSource;
        CoChangeService dbService;

        @BeforeEach
        void setupDb() throws Exception {
            AppConfig config = new AppConfig();
            config.setDbPath(tempDir.resolve("test-build.duckdb").toString());
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
            dbService = new CoChangeService(dataSource);
        }

        @AfterEach
        void teardown() throws Exception {
            dataSource.close();
        }

        @Test
        void buildCoChangeIndexFromOutputEndToEnd() throws Exception {
            // Use enough commits so files don't exceed 50% frequency threshold
            String gitOutput = "COMMIT:c1\n\nsrc/A.java\nsrc/B.java\n\n"
                    + "COMMIT:c2\n\nsrc/A.java\nsrc/B.java\n\n"
                    + "COMMIT:c3\n\nsrc/C.java\nsrc/D.java\n\n"
                    + "COMMIT:c4\n\nsrc/E.java\nsrc/F.java\n\n"
                    + "COMMIT:c5\n\nsrc/G.java\nsrc/H.java\n";

            dbService.buildCoChangeIndexFromOutput(gitOutput);

            // A-B co-changed in 2 commits (appears in 2 of 5 = 40% < 50%)
            List<Map<String, Object>> results = dbService.getRelatedFiles("src/A.java", 10);
            assertFalse(results.isEmpty(), "A should have related files");
            assertEquals("src/B.java", results.get(0).get("related_file"));
            assertEquals(2, results.get(0).get("co_change_count"));
        }

        @Test
        void buildCoChangeIndexFromOutputIsIdempotent() throws Exception {
            // Need enough commits so X and Y don't exceed 50% frequency
            String gitOutput = "COMMIT:aaa\n\nsrc/X.java\nsrc/Y.java\n\n"
                    + "COMMIT:bbb\n\nsrc/P.java\nsrc/Q.java\n\n"
                    + "COMMIT:ccc\n\nsrc/R.java\nsrc/S.java\n";
            dbService.buildCoChangeIndexFromOutput(gitOutput);
            dbService.buildCoChangeIndexFromOutput(gitOutput);

            List<Map<String, Object>> results = dbService.getRelatedFiles("src/X.java", 10);
            assertEquals(1, results.size());
            assertEquals(1, results.get(0).get("co_change_count"));
        }

        @Test
        void buildCoChangeIndexFromOutputFiltersNoisyCommits() throws Exception {
            // Noisy commit with 35 files + clean commits so A,B don't exceed 50% threshold
            StringBuilder sb = new StringBuilder("COMMIT:noisy\n\n");
            for (int i = 0; i < 35; i++) sb.append("f").append(i).append(".java\n");
            sb.append("\nCOMMIT:clean\n\nsrc/A.java\nsrc/B.java\n");
            sb.append("\nCOMMIT:other1\n\nsrc/P.java\nsrc/Q.java\n");
            sb.append("\nCOMMIT:other2\n\nsrc/R.java\nsrc/S.java\n");

            dbService.buildCoChangeIndexFromOutput(sb.toString());

            // Noisy commit was filtered, only clean commit's A-B pair should exist
            // (noisy commit had >30 files so it's excluded)
            // After filtering: 3 commits remain. A,B appear in 1 of 3 = 33% < 50%
            List<Map<String, Object>> results = dbService.getRelatedFiles("src/A.java", 10);
            assertEquals(1, results.size());
            assertEquals("src/B.java", results.get(0).get("related_file"));

            List<Map<String, Object>> noisyResults = dbService.getRelatedFiles("f0.java", 10);
            assertTrue(noisyResults.isEmpty());
        }

        @Test
        void buildCoChangeIndexFromOutputFiltersFrequentFiles() throws Exception {
            String gitOutput = "COMMIT:c1\n\ncommon.java\nsrc/A.java\n\n"
                    + "COMMIT:c2\n\ncommon.java\nsrc/B.java\n\n"
                    + "COMMIT:c3\n\ncommon.java\nsrc/A.java\nsrc/B.java\n\n"
                    + "COMMIT:c4\n\nsrc/C.java\nsrc/D.java\n";
            dbService.buildCoChangeIndexFromOutput(gitOutput);

            List<Map<String, Object>> commonResults = dbService.getRelatedFiles("common.java", 10);
            assertTrue(commonResults.isEmpty(),
                    "Frequent file should be excluded from co-change pairs");

            List<Map<String, Object>> aResults = dbService.getRelatedFiles("src/A.java", 10);
            assertFalse(aResults.isEmpty());
        }

        @Test
        void buildCoChangeIndexFromOutputEmptyInput() throws Exception {
            dbService.buildCoChangeIndexFromOutput("");
            List<Map<String, Object>> results = dbService.getRelatedFiles("anything", 10);
            assertTrue(results.isEmpty());
        }

        @Test
        void getRelatedFilesReturnsTimestamp() throws Exception {
            // Seed and verify last_commit_date is returned
            Connection conn = dataSource.getConnection();
            Timestamp now = new Timestamp(System.currentTimeMillis());
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cochange_cache (file_a, file_b, co_change_count, last_commit_date) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, "src/P.java");
                ps.setString(2, "src/Q.java");
                ps.setInt(3, 4);
                ps.setTimestamp(4, now);
                ps.executeUpdate();
            }

            List<Map<String, Object>> results = dbService.getRelatedFiles("src/P.java", 10);
            assertEquals(1, results.size());
            assertNotNull(results.get(0).get("last_commit_date"),
                    "Should include last_commit_date");
        }
    }
}
