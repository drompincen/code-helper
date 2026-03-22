package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.model.ArtifactStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);
    private final DuckDBDataSource dataSource;

    public ArtifactService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, String> getStatus(String artifactId) throws SQLException {
        return dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artifact_id, file_name, status, error_message, created_at, updated_at, indexed_at FROM artifacts WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, String> result = new HashMap<>();
                        result.put("artifact_id", rs.getString("artifact_id"));
                        result.put("file_name", rs.getString("file_name"));
                        result.put("status", rs.getString("status"));
                        result.put("error_message", rs.getString("error_message") != null ? rs.getString("error_message") : "");
                        result.put("created_at", rs.getString("created_at") != null ? rs.getString("created_at") : "");
                        result.put("updated_at", rs.getString("updated_at") != null ? rs.getString("updated_at") : "");
                        result.put("indexed_at", rs.getString("indexed_at") != null ? rs.getString("indexed_at") : "");
                        return result;
                    }
                }
            }
            return null;
        });
    }

    public Map<String, String> getText(String artifactId) throws SQLException {
        return dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artifact_id, extracted_text, text_length, extraction_method FROM artifact_text WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, String> result = new HashMap<>();
                        result.put("artifact_id", rs.getString("artifact_id"));
                        result.put("extracted_text", rs.getString("extracted_text"));
                        result.put("text_length", String.valueOf(rs.getLong("text_length")));
                        result.put("extraction_method", rs.getString("extraction_method"));
                        return result;
                    }
                }
            }
            return null;
        });
    }

    public void updateStatus(String artifactId, ArtifactStatus status, String errorMessage) throws SQLException {
        dataSource.withConnection(conn -> {
            String sql;
            if (status == ArtifactStatus.INDEXED) {
                sql = "UPDATE artifacts SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP, indexed_at = CURRENT_TIMESTAMP WHERE artifact_id = ?";
            } else {
                sql = "UPDATE artifacts SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE artifact_id = ?";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status.name());
                ps.setString(2, errorMessage);
                ps.setString(3, artifactId);
                ps.executeUpdate();
            }
            return null;
        });
        logIngestionEvent(artifactId, status.name(), errorMessage);
    }

    private void logIngestionEvent(String artifactId, String state, String message) throws SQLException {
        dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ingestion_events (event_id, artifact_id, state, message) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, artifactId);
                ps.setString(3, state);
                ps.setString(4, message);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
