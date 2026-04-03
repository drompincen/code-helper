package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class GraphUpdateService {
    private static final Logger log = LoggerFactory.getLogger(GraphUpdateService.class);
    private final DuckDBDataSource dataSource;
    private final KnowledgeGraphService knowledgeGraphService;

    public GraphUpdateService(DuckDBDataSource dataSource, KnowledgeGraphService knowledgeGraphService) {
        this.dataSource = dataSource;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * Called after a file is re-indexed. Removes graph data sourced solely from this artifact.
     * Entities shared with other artifacts survive with decremented counts.
     * Returns stats about what was removed/updated.
     */
    public Map<String, Object> onArtifactReindexed(String artifactId) throws SQLException {
        return knowledgeGraphService.deleteEntitiesForArtifact(artifactId);
    }

    /**
     * Called when a file is deleted. Same as reindexed but permanent.
     */
    public Map<String, Object> onArtifactDeleted(String artifactId) throws SQLException {
        return knowledgeGraphService.deleteEntitiesForArtifact(artifactId);
    }

    /**
     * Find entities/relationships that reference artifacts which have been
     * re-indexed since the entity was last updated.
     * Compare entities.updated_at with artifacts.indexed_at.
     */
    public List<Map<String, Object>> findStaleGraphEntries() throws SQLException {
        Connection conn = dataSource.getConnection();
        List<Map<String, Object>> stale = new ArrayList<>();

        // Find entities whose source artifacts have been re-indexed after the entity was created
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                SELECT e.entity_id, e.entity_name, e.entity_type, e.source_artifact_ids, e.updated_at
                FROM entities e
                WHERE EXISTS (
                    SELECT 1 FROM artifacts a
                    WHERE e.source_artifact_ids LIKE '%' || a.artifact_id || '%'
                    AND a.indexed_at > e.updated_at
                )
                """)) {
            while (rs.next()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("entity_id", rs.getString("entity_id"));
                entry.put("entity_name", rs.getString("entity_name"));
                entry.put("entity_type", rs.getString("entity_type"));
                entry.put("source_artifact_ids", rs.getString("source_artifact_ids"));
                entry.put("reason", "source artifact re-indexed after entity creation");
                stale.add(entry);
            }
        }
        return stale;
    }
}
