package com.javaducker.server.service;

import org.junit.jupiter.api.Test;

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
}
