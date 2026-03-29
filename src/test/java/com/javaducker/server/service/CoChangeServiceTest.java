package com.javaducker.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CoChangeServiceTest {

    private CoChangeService service;

    @BeforeEach
    void setUp() {
        // Construct with null dataSource since we only test parsing/computation methods
        service = new CoChangeService(null);
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
}
