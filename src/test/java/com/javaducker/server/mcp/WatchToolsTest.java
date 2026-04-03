package com.javaducker.server.mcp;

import com.javaducker.server.ingestion.FileWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchToolsTest {

    @Mock FileWatcher fileWatcher;

    @InjectMocks WatchTools tools;

    // ── start ────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void watch_startCallsStartWatching() throws Exception {
        Map<String, Object> result = tools.watch("start", "/project/src", ".java,.xml");

        assertEquals("start", result.get("action"));
        assertEquals(true, result.get("watching"));

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<Set> extCaptor = ArgumentCaptor.forClass(Set.class);
        verify(fileWatcher).startWatching(pathCaptor.capture(), extCaptor.capture());

        assertEquals(Path.of("/project/src"), pathCaptor.getValue());
        Set<String> exts = extCaptor.getValue();
        assertTrue(exts.contains(".java"));
        assertTrue(exts.contains(".xml"));
    }

    @Test
    void watch_startRequiresDirectory() {
        Map<String, Object> result = tools.watch("start", null, null);

        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("directory"));
    }

    @Test
    void watch_startWithNoExtensions() throws Exception {
        Map<String, Object> result = tools.watch("start", "/project", null);

        assertEquals(true, result.get("watching"));
        verify(fileWatcher).startWatching(eq(Path.of("/project")), eq(Set.of()));
    }

    // ── stop ─────────────────────────────────────────────────────────────

    @Test
    void watch_stopCallsStopWatching() {
        Map<String, Object> result = tools.watch("stop", null, null);

        assertEquals("stop", result.get("action"));
        assertEquals(false, result.get("watching"));
        verify(fileWatcher).stopWatching();
    }

    // ── status ───────────────────────────────────────────────────────────

    @Test
    void watch_statusReturnsWatchingState() {
        when(fileWatcher.isWatching()).thenReturn(true);
        when(fileWatcher.getWatchedDirectory()).thenReturn(Path.of("/project/src"));

        Map<String, Object> result = tools.watch("status", null, null);

        assertEquals("status", result.get("action"));
        assertEquals(true, result.get("watching"));
        assertEquals(Path.of("/project/src").toString(), result.get("directory"));
    }

    @Test
    void watch_statusWhenNotWatching() {
        when(fileWatcher.isWatching()).thenReturn(false);
        when(fileWatcher.getWatchedDirectory()).thenReturn(null);

        Map<String, Object> result = tools.watch("status", null, null);

        assertEquals(false, result.get("watching"));
        assertNull(result.get("directory"));
    }

    // ── unknown action ───────────────────────────────────────────────────

    @Test
    void watch_unknownActionReturnsError() {
        Map<String, Object> result = tools.watch("restart", null, null);

        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("restart"));
    }

    // ── exception handling ───────────────────────────────────────────────

    @Test
    void watch_startReturnsErrorOnException() throws Exception {
        doThrow(new RuntimeException("IO error")).when(fileWatcher).startWatching(any(), any());

        Map<String, Object> result = tools.watch("start", "/bad/dir", null);

        assertEquals("IO error", result.get("error"));
    }
}
