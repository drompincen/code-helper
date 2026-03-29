package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.ingestion.SessionTranscriptParser;
import com.javaducker.server.model.SessionTranscript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SessionIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SessionIngestionService.class);

    private final DuckDBDataSource dataSource;
    private final SessionTranscriptParser parser;

    public SessionIngestionService(DuckDBDataSource dataSource, SessionTranscriptParser parser) {
        this.dataSource = dataSource;
        this.parser = parser;
    }

    /**
     * Index session files from a project directory.
     * Sorts by last modified descending, skips already-indexed files whose mtime hasn't changed.
     *
     * @param projectPath path to the directory containing .jsonl session files
     * @param maxSessions maximum number of sessions to process (all if <= 0)
     * @return summary map with sessions_indexed, sessions_skipped, total_messages, project_path
     */
    public Map<String, Object> indexSessions(String projectPath, int maxSessions) throws SQLException {
        List<Path> sessionFiles = parser.findSessionFiles(Path.of(projectPath));

        // Sort by last modified descending (newest first)
        sessionFiles.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });

        if (maxSessions > 0 && sessionFiles.size() > maxSessions) {
            sessionFiles = sessionFiles.subList(0, maxSessions);
        }

        int sessionsIndexed = 0;
        int sessionsSkipped = 0;
        int totalMessages = 0;

        for (Path file : sessionFiles) {
            String sessionId = extractSessionId(file);

            if (isAlreadyIndexed(sessionId, file)) {
                sessionsSkipped++;
                log.debug("Skipping already-indexed session: {}", sessionId);
                continue;
            }

            List<SessionTranscript> transcripts = parser.parseSessionFile(file);
            if (!transcripts.isEmpty()) {
                storeTranscripts(transcripts);
                totalMessages += transcripts.size();
                sessionsIndexed++;
                log.info("Indexed session {} with {} messages", sessionId, transcripts.size());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessions_indexed", sessionsIndexed);
        summary.put("sessions_skipped", sessionsSkipped);
        summary.put("total_messages", totalMessages);
        summary.put("project_path", projectPath);
        return summary;
    }

    /**
     * Store transcripts for a session. Deletes existing records first for idempotent re-indexing.
     */
    public void storeTranscripts(List<SessionTranscript> transcripts) throws SQLException {
        if (transcripts.isEmpty()) {
            return;
        }

        String sessionId = transcripts.get(0).sessionId();

        dataSource.withConnection(conn -> {
            // Delete existing records for this session (idempotent re-index)
            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM session_transcripts WHERE session_id = ?")) {
                delete.setString(1, sessionId);
                delete.executeUpdate();
            }

            // Batch insert all transcripts
            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO session_transcripts
                        (session_id, project_path, message_index, role, content, tool_name, timestamp, token_estimate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (SessionTranscript t : transcripts) {
                    insert.setString(1, t.sessionId());
                    insert.setString(2, t.projectPath());
                    insert.setInt(3, t.messageIndex());
                    insert.setString(4, t.role());
                    insert.setString(5, t.content());
                    insert.setString(6, t.toolName());
                    if (t.timestamp() != null) {
                        try {
                            insert.setTimestamp(7, Timestamp.valueOf(t.timestamp()));
                        } catch (IllegalArgumentException e) {
                            insert.setNull(7, Types.TIMESTAMP);
                        }
                    } else {
                        insert.setNull(7, Types.TIMESTAMP);
                    }
                    insert.setInt(8, t.tokenEstimate());
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            return null;
        });
    }

    /**
     * List all indexed sessions with message counts and token totals.
     */
    public List<Map<String, Object>> getSessionList() throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("""
                         SELECT session_id, project_path,
                                COUNT(*) as message_count,
                                MIN(message_index) as first_msg,
                                MAX(message_index) as last_msg,
                                SUM(token_estimate) as total_tokens
                         FROM session_transcripts
                         GROUP BY session_id, project_path
                         ORDER BY session_id DESC
                         """)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("session_id", rs.getString("session_id"));
                    row.put("project_path", rs.getString("project_path"));
                    row.put("message_count", rs.getInt("message_count"));
                    row.put("first_msg", rs.getInt("first_msg"));
                    row.put("last_msg", rs.getInt("last_msg"));
                    row.put("total_tokens", rs.getLong("total_tokens"));
                    results.add(row);
                }
            }
            return results;
        });
    }

    /**
     * Retrieve all messages for a specific session, ordered by message index.
     */
    public List<Map<String, Object>> getSession(String sessionId) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT session_id, project_path, message_index, role, content,
                           tool_name, timestamp, token_estimate
                    FROM session_transcripts
                    WHERE session_id = ?
                    ORDER BY message_index
                    """)) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("session_id", rs.getString("session_id"));
                        row.put("project_path", rs.getString("project_path"));
                        row.put("message_index", rs.getInt("message_index"));
                        row.put("role", rs.getString("role"));
                        row.put("content", rs.getString("content"));
                        row.put("tool_name", rs.getString("tool_name"));
                        row.put("timestamp", rs.getTimestamp("timestamp"));
                        row.put("token_estimate", rs.getInt("token_estimate"));
                        results.add(row);
                    }
                }
            }
            return results;
        });
    }

    /**
     * Check if a session file is already indexed and up-to-date.
     * Compares the file's last modified time against the max timestamp stored for that session.
     */
    boolean isAlreadyIndexed(String sessionId, Path file) throws SQLException {
        long fileMtime;
        try {
            fileMtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return false;
        }

        return dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) as cnt FROM session_transcripts WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt("cnt") > 0) {
                        // Session exists; check if file has been modified since last index
                        // Use message count as a proxy: if file mtime is newer than
                        // we'd need to re-index. Store mtime as token_estimate of a sentinel?
                        // Simpler: check max timestamp in the records
                        return isFileUnchanged(conn, sessionId, fileMtime);
                    }
                }
            }
            return false;
        });
    }

    /**
     * Check if the file mtime is older than or equal to when we last indexed.
     * We store the file mtime as a metadata marker in the first record's token_estimate field
     * would be too hacky. Instead, we track indexed mtime in a simple approach:
     * if records exist for this session, we consider it indexed. The caller can force re-index
     * by deleting records first. For incremental: compare file mtime to a stored value.
     *
     * For now, we use a pragmatic approach: store the file's mtime (epoch millis)
     * as a negative token_estimate on a sentinel row with message_index = -1.
     */
    private boolean isFileUnchanged(Connection conn, String sessionId, long fileMtime) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT content FROM session_transcripts WHERE session_id = ? AND message_index = -1")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String stored = rs.getString("content");
                    if (stored != null) {
                        long storedMtime = Long.parseLong(stored);
                        return fileMtime <= storedMtime;
                    }
                }
            }
        }
        // No sentinel row means we need to re-index (legacy data or first time)
        return false;
    }

    /**
     * Store transcripts with an mtime sentinel for incremental ingestion tracking.
     */
    public void storeTranscriptsWithMtime(List<SessionTranscript> transcripts, long fileMtime)
            throws SQLException {
        if (transcripts.isEmpty()) {
            return;
        }

        String sessionId = transcripts.get(0).sessionId();
        String projectPath = transcripts.get(0).projectPath();

        dataSource.withConnection(conn -> {
            // Delete existing records for this session
            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM session_transcripts WHERE session_id = ?")) {
                delete.setString(1, sessionId);
                delete.executeUpdate();
            }

            // Insert mtime sentinel row (message_index = -1)
            try (PreparedStatement sentinel = conn.prepareStatement("""
                    INSERT INTO session_transcripts
                        (session_id, project_path, message_index, role, content, tool_name, timestamp, token_estimate)
                    VALUES (?, ?, -1, '_meta', ?, NULL, NULL, 0)
                    """)) {
                sentinel.setString(1, sessionId);
                sentinel.setString(2, projectPath);
                sentinel.setString(3, String.valueOf(fileMtime));
                sentinel.executeUpdate();
            }

            // Batch insert all transcripts
            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO session_transcripts
                        (session_id, project_path, message_index, role, content, tool_name, timestamp, token_estimate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (SessionTranscript t : transcripts) {
                    insert.setString(1, t.sessionId());
                    insert.setString(2, t.projectPath());
                    insert.setInt(3, t.messageIndex());
                    insert.setString(4, t.role());
                    insert.setString(5, t.content());
                    insert.setString(6, t.toolName());
                    if (t.timestamp() != null) {
                        try {
                            insert.setTimestamp(7, Timestamp.valueOf(t.timestamp()));
                        } catch (IllegalArgumentException e) {
                            insert.setNull(7, Types.TIMESTAMP);
                        }
                    } else {
                        insert.setNull(7, Types.TIMESTAMP);
                    }
                    insert.setInt(8, t.tokenEstimate());
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            return null;
        });
    }

    /**
     * Full indexSessions flow using mtime tracking for incremental ingestion.
     */
    public Map<String, Object> indexSessionsIncremental(String projectPath, int maxSessions)
            throws SQLException {
        List<Path> sessionFiles = parser.findSessionFiles(Path.of(projectPath));

        // Sort by last modified descending (newest first)
        sessionFiles.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });

        if (maxSessions > 0 && sessionFiles.size() > maxSessions) {
            sessionFiles = sessionFiles.subList(0, maxSessions);
        }

        int sessionsIndexed = 0;
        int sessionsSkipped = 0;
        int totalMessages = 0;

        for (Path file : sessionFiles) {
            String sessionId = extractSessionId(file);

            if (isAlreadyIndexed(sessionId, file)) {
                sessionsSkipped++;
                continue;
            }

            List<SessionTranscript> transcripts = parser.parseSessionFile(file);
            if (!transcripts.isEmpty()) {
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    mtime = System.currentTimeMillis();
                }
                storeTranscriptsWithMtime(transcripts, mtime);
                totalMessages += transcripts.size();
                sessionsIndexed++;
                log.info("Indexed session {} with {} messages", sessionId, transcripts.size());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessions_indexed", sessionsIndexed);
        summary.put("sessions_skipped", sessionsSkipped);
        summary.put("total_messages", totalMessages);
        summary.put("project_path", projectPath);
        return summary;
    }

    private String extractSessionId(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.endsWith(".jsonl")
                ? fileName.substring(0, fileName.length() - ".jsonl".length())
                : fileName;
    }
}
