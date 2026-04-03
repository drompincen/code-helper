package com.javaducker.server.mcp;

import com.javaducker.server.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoreToolsTest {

    @Mock UploadService uploadService;
    @Mock ArtifactService artifactService;
    @Mock SearchService searchService;
    @Mock StatsService statsService;
    @Mock StalenessService stalenessService;
    @Mock GraphSearchService graphSearchService;

    @InjectMocks CoreTools coreTools;

    // ── health ──────────────────────────────────────────────────────────

    @Test
    void health_returnsStatusOkWithStats() throws Exception {
        Map<String, Object> stats = Map.of("total_artifacts", 42L);
        when(statsService.getStats()).thenReturn(new LinkedHashMap<>(stats));

        Map<String, Object> result = coreTools.health();

        assertEquals("ok", result.get("status"));
        assertEquals(42L, result.get("total_artifacts"));
    }

    @Test
    void health_returnsErrorOnException() throws Exception {
        when(statsService.getStats()).thenThrow(new SQLException("db down"));

        Map<String, Object> result = coreTools.health();

        assertEquals("db down", result.get("error"));
    }

    // ── indexFile ────────────────────────────────────────────────────────

    @Test
    void indexFile_delegatesToUploadService(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Hello.java");
        Files.writeString(file, "public class Hello {}");

        when(uploadService.upload(eq("Hello.java"), eq(file.toString()), anyString(),
                anyLong(), any(byte[].class))).thenReturn("abc-123");

        Map<String, Object> result = coreTools.indexFile(file.toString());

        assertEquals("abc-123", result.get("artifact_id"));
        assertEquals("Hello.java", result.get("file_name"));
        verify(uploadService).upload(eq("Hello.java"), eq(file.toString()), anyString(),
                eq((long) "public class Hello {}".getBytes().length), any(byte[].class));
    }

    @Test
    void indexFile_returnsErrorForMissingFile() {
        Map<String, Object> result = coreTools.indexFile("/nonexistent/path/File.java");

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("File not found"));
    }

    // ── indexDirectory ──────────────────────────────────────────────────

    @Test
    void indexDirectory_indexesMatchingFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "class A {}");
        Files.writeString(tempDir.resolve("B.java"), "class B {}");
        Files.writeString(tempDir.resolve("readme.txt"), "hello");

        when(uploadService.upload(anyString(), anyString(), anyString(),
                anyLong(), any(byte[].class))).thenReturn("id");

        Map<String, Object> result = coreTools.indexDirectory(tempDir.toString(), "java");

        assertEquals(2, result.get("indexed_count"));
        assertEquals(0, result.get("error_count"));
    }

    @Test
    void indexDirectory_indexesAllFilesWhenNoExtensionFilter(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "class A {}");
        Files.writeString(tempDir.resolve("readme.txt"), "hello");

        when(uploadService.upload(anyString(), anyString(), anyString(),
                anyLong(), any(byte[].class))).thenReturn("id");

        Map<String, Object> result = coreTools.indexDirectory(tempDir.toString(), null);

        assertEquals(2, result.get("indexed_count"));
    }

    @Test
    void indexDirectory_returnsErrorForNonDirectory() {
        Map<String, Object> result = coreTools.indexDirectory("/nonexistent/dir", null);

        assertTrue(result.containsKey("error"));
    }

    // ── search ──────────────────────────────────────────────────────────

    @Test
    void search_usesHybridByDefault() throws Exception {
        List<Map<String, Object>> hits = List.of(Map.of("file_name", "A.java"));
        when(searchService.hybridSearch("test", 20)).thenReturn(hits);

        Map<String, Object> result = coreTools.search("test", null, null);

        assertEquals("hybrid", result.get("mode"));
        assertEquals(1, result.get("count"));
        verify(searchService).hybridSearch("test", 20);
    }

    @Test
    void search_exactModeDelegatesCorrectly() throws Exception {
        when(searchService.exactSearch("foo", 10)).thenReturn(List.of());

        Map<String, Object> result = coreTools.search("foo", "exact", 10);

        assertEquals("exact", result.get("mode"));
        verify(searchService).exactSearch("foo", 10);
        verify(searchService, never()).hybridSearch(anyString(), anyInt());
    }

    @Test
    void search_semanticModeDelegatesCorrectly() throws Exception {
        when(searchService.semanticSearch("bar", 5)).thenReturn(List.of());

        Map<String, Object> result = coreTools.search("bar", "semantic", 5);

        assertEquals("semantic", result.get("mode"));
        verify(searchService).semanticSearch("bar", 5);
    }

    @Test
    void search_hybridModeExplicit() throws Exception {
        when(searchService.hybridSearch("baz", 20)).thenReturn(List.of());

        Map<String, Object> result = coreTools.search("baz", "hybrid", null);

        assertEquals("hybrid", result.get("mode"));
        verify(searchService).hybridSearch("baz", 20);
    }

    @Test
    void search_addsStalenessWarning() throws Exception {
        List<Map<String, Object>> hits = List.of(
                Map.of("file_name", "A.java", "original_client_path", "/src/A.java"));
        when(searchService.hybridSearch("test", 20)).thenReturn(hits);
        when(stalenessService.checkStaleness(List.of("/src/A.java")))
                .thenReturn(Map.of("stale", List.of(Map.of("path", "/src/A.java"))));

        Map<String, Object> result = coreTools.search("test", null, null);

        assertTrue(result.containsKey("staleness_warning"));
        assertTrue(result.containsKey("stale_files"));
    }

    @Test
    void search_returnsErrorOnException() throws Exception {
        when(searchService.hybridSearch(anyString(), anyInt()))
                .thenThrow(new SQLException("search failed"));

        Map<String, Object> result = coreTools.search("test", null, null);

        assertEquals("search failed", result.get("error"));
    }

    @Test
    void search_unknownModeReturnsError() {
        Map<String, Object> result = coreTools.search("test", "magic", null);

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Unknown search mode"));
    }

    @Test
    void search_localModeDelegatesToGraphSearchService() throws Exception {
        when(graphSearchService.localSearch("entities", 10)).thenReturn(List.of());

        Map<String, Object> result = coreTools.search("entities", "local", 10);

        assertEquals("local", result.get("mode"));
        verify(graphSearchService).localSearch("entities", 10);
    }

    @Test
    void search_globalModeDelegatesToGraphSearchService() throws Exception {
        when(graphSearchService.globalSearch("rels", 5)).thenReturn(List.of());

        Map<String, Object> result = coreTools.search("rels", "global", 5);

        assertEquals("global", result.get("mode"));
        verify(graphSearchService).globalSearch("rels", 5);
    }

    @Test
    void search_graphHybridModeDelegatesToGraphSearchService() throws Exception {
        when(graphSearchService.hybridGraphSearch("query", 20)).thenReturn(List.of());

        Map<String, Object> result = coreTools.search("query", "graph_hybrid", null);

        assertEquals("graph_hybrid", result.get("mode"));
        verify(graphSearchService).hybridGraphSearch("query", 20);
    }

    @Test
    void search_mixModeDelegatesToGraphSearchService() throws Exception {
        when(graphSearchService.mixSearch("query", 20)).thenReturn(List.of());

        Map<String, Object> result = coreTools.search("query", "mix", null);

        assertEquals("mix", result.get("mode"));
        verify(graphSearchService).mixSearch("query", 20);
    }

    // ── getFileText ─────────────────────────────────────────────────────

    @Test
    void getFileText_delegatesCorrectly() throws Exception {
        Map<String, String> text = Map.of("text", "public class Foo {}", "artifact_id", "abc");
        when(artifactService.getText("abc")).thenReturn(text);

        Map<String, Object> result = coreTools.getFileText("abc");

        assertEquals("public class Foo {}", result.get("text"));
        assertEquals("abc", result.get("artifact_id"));
    }

    @Test
    void getFileText_returnsErrorOnException() throws Exception {
        when(artifactService.getText("bad")).thenThrow(new SQLException("not found"));

        Map<String, Object> result = coreTools.getFileText("bad");

        assertEquals("not found", result.get("error"));
    }

    // ── getArtifactStatus ───────────────────────────────────────────────

    @Test
    void getArtifactStatus_delegatesCorrectly() throws Exception {
        Map<String, String> status = Map.of("status", "INDEXED", "artifact_id", "abc");
        when(artifactService.getStatus("abc")).thenReturn(status);

        Map<String, Object> result = coreTools.getArtifactStatus("abc");

        assertEquals("INDEXED", result.get("status"));
        assertEquals("abc", result.get("artifact_id"));
    }

    @Test
    void getArtifactStatus_returnsErrorOnException() throws Exception {
        when(artifactService.getStatus("bad")).thenThrow(new SQLException("db error"));

        Map<String, Object> result = coreTools.getArtifactStatus("bad");

        assertEquals("db error", result.get("error"));
    }

    // ── waitForIndexed ──────────────────────────────────────────────────

    @Test
    void waitForIndexed_returnsOnIndexedStatus() throws Exception {
        Map<String, String> status = Map.of("status", "INDEXED", "artifact_id", "abc");
        when(artifactService.getStatus("abc")).thenReturn(status);

        Map<String, Object> result = coreTools.waitForIndexed("abc", 10);

        assertEquals("INDEXED", result.get("status"));
        verify(artifactService, times(1)).getStatus("abc");
    }

    @Test
    void waitForIndexed_returnsOnFailedStatus() throws Exception {
        Map<String, String> status = Map.of("status", "FAILED", "artifact_id", "abc",
                "error_message", "parse error");
        when(artifactService.getStatus("abc")).thenReturn(status);

        Map<String, Object> result = coreTools.waitForIndexed("abc", 10);

        assertEquals("FAILED", result.get("status"));
    }

    @Test
    void waitForIndexed_pollsUntilIndexed() throws Exception {
        Map<String, String> pending = Map.of("status", "PENDING", "artifact_id", "abc");
        Map<String, String> indexed = Map.of("status", "INDEXED", "artifact_id", "abc");
        when(artifactService.getStatus("abc"))
                .thenReturn(pending)
                .thenReturn(pending)
                .thenReturn(indexed);

        Map<String, Object> result = coreTools.waitForIndexed("abc", 30);

        assertEquals("INDEXED", result.get("status"));
        verify(artifactService, times(3)).getStatus("abc");
    }

    @Test
    void waitForIndexed_returnsErrorOnTimeout() throws Exception {
        Map<String, String> pending = Map.of("status", "PENDING", "artifact_id", "abc");
        when(artifactService.getStatus("abc")).thenReturn(pending);

        // Use a very short timeout to avoid slow test
        Map<String, Object> result = coreTools.waitForIndexed("abc", 1);

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Timeout"));
    }

    // ── stats ───────────────────────────────────────────────────────────

    @Test
    void stats_delegatesCorrectly() throws Exception {
        Map<String, Object> stats = Map.of("total_artifacts", 100L, "indexed", 95L);
        when(statsService.getStats()).thenReturn(stats);

        Map<String, Object> result = coreTools.stats();

        assertEquals(100L, result.get("total_artifacts"));
        assertEquals(95L, result.get("indexed"));
    }

    @Test
    void stats_returnsErrorOnException() throws Exception {
        when(statsService.getStats()).thenThrow(new SQLException("db error"));

        Map<String, Object> result = coreTools.stats();

        assertEquals("db error", result.get("error"));
    }
}
