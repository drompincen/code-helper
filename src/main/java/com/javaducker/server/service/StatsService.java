package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

@Service
public class StatsService {

    private final DuckDBDataSource dataSource;

    public StatsService(DuckDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> getStats() throws SQLException {
        Connection conn = dataSource.getConnection();
        Map<String, Object> stats = new HashMap<>();

        try (Statement stmt = conn.createStatement()) {
            // Total artifacts
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM artifacts")) {
                rs.next();
                stats.put("total_artifacts", rs.getLong(1));
            }

            // Indexed artifacts
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM artifacts WHERE status = 'INDEXED'")) {
                rs.next();
                stats.put("indexed_artifacts", rs.getLong(1));
            }

            // Failed artifacts
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM artifacts WHERE status = 'FAILED'")) {
                rs.next();
                stats.put("failed_artifacts", rs.getLong(1));
            }

            // Pending artifacts (not yet indexed or failed)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM artifacts WHERE status NOT IN ('INDEXED', 'FAILED')")) {
                rs.next();
                stats.put("pending_artifacts", rs.getLong(1));
            }

            // Total chunks
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM artifact_chunks")) {
                rs.next();
                stats.put("total_chunks", rs.getLong(1));
            }

            // Total bytes
            try (ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(size_bytes), 0) FROM artifacts")) {
                rs.next();
                stats.put("total_bytes", rs.getLong(1));
            }

            // Artifacts by status
            Map<String, Long> byStatus = new HashMap<>();
            try (ResultSet rs = stmt.executeQuery("SELECT status, COUNT(*) as cnt FROM artifacts GROUP BY status")) {
                while (rs.next()) {
                    byStatus.put(rs.getString("status"), rs.getLong("cnt"));
                }
            }
            stats.put("artifacts_by_status", byStatus);

            // Enrichment status breakdown
            Map<String, Long> byEnrichment = new HashMap<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT enrichment_status, COUNT(*) as cnt FROM artifacts GROUP BY enrichment_status")) {
                while (rs.next()) {
                    String es = rs.getString("enrichment_status");
                    byEnrichment.put(es != null ? es : "unknown", rs.getLong("cnt"));
                }
            }
            stats.put("enrichment_status", byEnrichment);
        }

        return stats;
    }
}
