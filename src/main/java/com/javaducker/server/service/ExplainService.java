package com.javaducker.server.service;

import com.javaducker.server.db.DuckDBDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Read-only aggregation service that collects everything JavaDucker knows
 * about a file into a single response. Each section is optional — if a
 * backing service throws or returns null, that section is simply omitted.
 */
@Service
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    private final ArtifactService artifactService;
    private final DependencyService dependencyService;
    private final ContentIntelligenceService contentIntelligenceService;
    private final DuckDBDataSource dataSource;
    private final SemanticTagService semanticTagService;
    private final KnowledgeGraphService knowledgeGraphService;

    // Optional services — may not be built yet
    private final Object gitBlameService;
    private final Object coChangeService;

    @Autowired
    public ExplainService(ArtifactService artifactService,
                          DependencyService dependencyService,
                          ContentIntelligenceService contentIntelligenceService,
                          DuckDBDataSource dataSource,
                          SemanticTagService semanticTagService,
                          KnowledgeGraphService knowledgeGraphService,
                          @Autowired(required = false) @SuppressWarnings("unused")
                          Object gitBlameServicePlaceholder,
                          @Autowired(required = false) @SuppressWarnings("unused")
                          Object coChangeServicePlaceholder) {
        this.artifactService = artifactService;
        this.dependencyService = dependencyService;
        this.contentIntelligenceService = contentIntelligenceService;
        this.dataSource = dataSource;
        this.semanticTagService = semanticTagService;
        this.knowledgeGraphService = knowledgeGraphService;
        // Will be replaced with real types when GitBlameService / CoChangeService exist
        this.gitBlameService = null;
        this.coChangeService = null;
    }

    /**
     * Aggregates all known information about an artifact into a single map.
     * Each section is independently fetched — failures in one section do not
     * affect the others.
     */
    public Map<String, Object> explain(String artifactId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. file
        try {
            Map<String, String> status = artifactService.getStatus(artifactId);
            if (status == null) {
                return null;
            }
            result.put("file", status);
        } catch (Exception e) {
            log.warn("explain: failed to get file status for {}", artifactId, e);
            return null;
        }

        // 2. summary
        addSection(result, "summary", () -> artifactService.getSummary(artifactId));

        // 3. dependencies (limit 5)
        addSection(result, "dependencies", () ->
                limitList(dependencyService.getDependencies(artifactId), 5));

        // 4. dependents (limit 5)
        addSection(result, "dependents", () ->
                limitList(dependencyService.getDependents(artifactId), 5));

        // 5. classification
        addSection(result, "classification", () -> queryClassification(artifactId));

        // 6. tags
        addSection(result, "tags", () -> queryTags(artifactId));

        // 7. salient_points
        addSection(result, "salient_points", () -> querySalientPoints(artifactId));

        // 8. related_artifacts (limit 5)
        addSection(result, "related_artifacts", () ->
                limitList(contentIntelligenceService.getRelatedByConcept(artifactId), 5));

        // 8b. semantic_tags
        addSection(result, "semantic_tags", () -> {
            List<Map<String, Object>> tags = semanticTagService.getTagsForArtifact(artifactId);
            return tags.isEmpty() ? null : tags;
        });

        // 8c. graph_entities
        addSection(result, "graph_entities", () -> {
            List<Map<String, Object>> entities = knowledgeGraphService.getEntitiesForArtifact(artifactId);
            return entities.isEmpty() ? null : entities;
        });

        // 9. blame_highlights (optional — service may not exist)
        if (gitBlameService != null) {
            addSection(result, "blame_highlights", () -> getBlameHighlights(artifactId));
        }

        // 10. co_changes (optional — service may not exist)
        if (coChangeService != null) {
            addSection(result, "co_changes", () -> getCoChanges(artifactId));
        }

        return result;
    }

    /**
     * Resolve a file path to an artifact and return explain data.
     * If the file is not indexed, returns a minimal response.
     */
    public Map<String, Object> explainByPath(String filePath) {
        try {
            String artifactId = dataSource.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT artifact_id FROM artifacts WHERE original_client_path = ? AND status = 'INDEXED' ORDER BY indexed_at DESC LIMIT 1")) {
                    ps.setString(1, filePath);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString("artifact_id");
                        }
                    }
                }
                return null;
            });

            if (artifactId != null) {
                return explain(artifactId);
            }
        } catch (Exception e) {
            log.warn("explainByPath: failed to resolve path {}", filePath, e);
        }

        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("file_path", filePath);
        minimal.put("indexed", false);
        return minimal;
    }

    // ── Section query helpers ──────────────────────────────────────────────

    private Map<String, Object> queryClassification(String artifactId) throws SQLException {
        return dataSource.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT doc_type, confidence, method, classified_at FROM artifact_classifications WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("doc_type", rs.getString("doc_type"));
                        row.put("confidence", rs.getDouble("confidence"));
                        row.put("method", rs.getString("method"));
                        row.put("classified_at", rs.getString("classified_at"));
                        return row;
                    }
                }
            }
            return null;
        });
    }

    private List<Map<String, Object>> queryTags(String artifactId) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> tags = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tag, tag_type, source FROM artifact_tags WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("tag", rs.getString("tag"));
                        row.put("tag_type", rs.getString("tag_type"));
                        row.put("source", rs.getString("source"));
                        tags.add(row);
                    }
                }
            }
            return tags.isEmpty() ? null : tags;
        });
    }

    private List<Map<String, Object>> querySalientPoints(String artifactId) throws SQLException {
        return dataSource.withConnection(conn -> {
            List<Map<String, Object>> points = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT point_type, point_text, source FROM artifact_salient_points WHERE artifact_id = ?")) {
                ps.setString(1, artifactId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("point_type", rs.getString("point_type"));
                        row.put("point_text", rs.getString("point_text"));
                        row.put("source", rs.getString("source"));
                        points.add(row);
                    }
                }
            }
            return points.isEmpty() ? null : points;
        });
    }

    // Placeholder — will delegate to GitBlameService when it exists
    private Object getBlameHighlights(String artifactId) {
        return null;
    }

    // Placeholder — will delegate to CoChangeService when it exists
    private Object getCoChanges(String artifactId) {
        return null;
    }

    // ── Utility helpers (package-private for testing) ──────────────────────

    @FunctionalInterface
    interface SectionSupplier<T> {
        T get() throws Exception;
    }

    private <T> void addSection(Map<String, Object> result, String key, SectionSupplier<T> supplier) {
        try {
            T value = supplier.get();
            if (value != null) {
                result.put(key, value);
            }
        } catch (Exception e) {
            log.warn("explain: failed to build section '{}': {}", key, e.getMessage());
        }
    }

    static <T> List<T> limitList(List<T> list, int max) {
        if (list == null) {
            return Collections.emptyList();
        }
        if (list.size() <= max) {
            return list;
        }
        return list.subList(0, max);
    }

    static Map<String, Object> buildExplainResult(Map<String, Object> sections) {
        if (sections == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : sections.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
