package com.javaducker.server.mcp;

import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.service.CommunityDetectionService;
import com.javaducker.server.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

@Component
public class EnrichmentTools {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentTools.class);

    private final DuckDBDataSource dataSource;
    private final KnowledgeGraphService knowledgeGraphService;
    private final CommunityDetectionService communityDetectionService;

    public EnrichmentTools(DuckDBDataSource dataSource,
                           KnowledgeGraphService knowledgeGraphService,
                           CommunityDetectionService communityDetectionService) {
        this.dataSource = dataSource;
        this.knowledgeGraphService = knowledgeGraphService;
        this.communityDetectionService = communityDetectionService;
    }

    @Tool(name = "javaducker_enrichment_pipeline",
            description = "Get a structured enrichment work plan. Returns pending files and the steps " +
                    "Claude should follow for each file: read text, synthesize tags, extract entities, " +
                    "classify, and mark enriched")
    public Map<String, Object> enrichmentPipeline(
            @ToolParam(description = "Batch size (default 10)", required = false) Integer batch_size) {
        try {
            int size = batch_size != null && batch_size > 0 ? Math.min(batch_size, 50) : 10;
            Connection conn = dataSource.getConnection();

            List<Map<String, Object>> pending = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT artifact_id, file_name, status FROM artifacts " +
                    "WHERE COALESCE(enrichment_status, 'pending') = 'pending' " +
                    "AND status = 'INDEXED' ORDER BY created_at DESC LIMIT ?")) {
                ps.setInt(1, size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pending.add(Map.of(
                                "artifact_id", rs.getString("artifact_id"),
                                "file_name", rs.getString("file_name")));
                    }
                }
            }

            Map<String, Object> graphStats = knowledgeGraphService.getStats();
            var communities = communityDetectionService.getCommunities();

            List<String> steps = List.of(
                    "1. Read file text via javaducker_get_file_text",
                    "2. Call javaducker_synthesize_tags with 4-10 semantic tags",
                    "3. Call javaducker_extract_entities with entities and relationships",
                    "4. Call javaducker_classify if not yet classified",
                    "5. Call javaducker_mark_enriched when done"
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pending_files", pending);
            result.put("pending_count", pending.size());
            result.put("steps_per_file", steps);
            result.put("batch_size", size);
            result.put("graph_stats", graphStats);
            result.put("community_count", communities.size());
            return result;
        } catch (Exception e) {
            log.error("enrichmentPipeline failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_enrichment_status",
            description = "Get enrichment progress: total files, enriched count, pending count, graph stats")
    public Map<String, Object> enrichmentStatus() {
        try {
            Connection conn = dataSource.getConnection();
            Map<String, Object> result = new LinkedHashMap<>();

            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) AS total, " +
                        "SUM(CASE WHEN COALESCE(enrichment_status, 'pending') = 'enriched' THEN 1 ELSE 0 END) AS enriched, " +
                        "SUM(CASE WHEN COALESCE(enrichment_status, 'pending') = 'pending' THEN 1 ELSE 0 END) AS pending " +
                        "FROM artifacts WHERE status = 'INDEXED'")) {
                    rs.next();
                    result.put("total_indexed", rs.getLong("total"));
                    result.put("enriched", rs.getLong("enriched"));
                    result.put("pending", rs.getLong("pending"));
                }
            }

            result.put("graph_stats", knowledgeGraphService.getStats());
            result.put("community_count", communityDetectionService.getCommunities().size());
            return result;
        } catch (Exception e) {
            log.error("enrichmentStatus failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_rebuild_graph",
            description = "Nuclear option: clear all entities, relationships, and communities. " +
                    "Returns list of all indexed artifacts for full re-extraction")
    public Map<String, Object> rebuildGraph() {
        try {
            Connection conn = dataSource.getConnection();

            int deletedEntities, deletedRels, deletedCommunities;
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entities")) {
                    rs.next(); deletedEntities = rs.getInt(1);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entity_relationships")) {
                    rs.next(); deletedRels = rs.getInt(1);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entity_communities")) {
                    rs.next(); deletedCommunities = rs.getInt(1);
                }
                stmt.execute("DELETE FROM entity_relationships");
                stmt.execute("DELETE FROM entities");
                stmt.execute("DELETE FROM entity_communities");
            }

            List<Map<String, Object>> artifacts = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT artifact_id, file_name FROM artifacts WHERE status = 'INDEXED'")) {
                while (rs.next()) {
                    artifacts.add(Map.of(
                            "artifact_id", rs.getString("artifact_id"),
                            "file_name", rs.getString("file_name")));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted_entities", deletedEntities);
            result.put("deleted_relationships", deletedRels);
            result.put("deleted_communities", deletedCommunities);
            result.put("artifacts_to_reprocess", artifacts);
            result.put("artifact_count", artifacts.size());
            return result;
        } catch (Exception e) {
            log.error("rebuildGraph failed", e);
            return Map.of("error", e.getMessage());
        }
    }
}
