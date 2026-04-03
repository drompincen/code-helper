package com.javaducker.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.CommunityDetectionService;
import com.javaducker.server.service.GraphSearchService;
import com.javaducker.server.service.GraphUpdateService;
import com.javaducker.server.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KnowledgeGraphTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphTools.class);

    private final KnowledgeGraphService service;
    private final GraphSearchService graphSearchService;
    private final GraphUpdateService graphUpdateService;
    private final CommunityDetectionService communityDetectionService;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphTools(KnowledgeGraphService service,
                               GraphSearchService graphSearchService,
                               GraphUpdateService graphUpdateService,
                               CommunityDetectionService communityDetectionService,
                               ObjectMapper objectMapper) {
        this.service = service;
        this.graphSearchService = graphSearchService;
        this.graphUpdateService = graphUpdateService;
        this.communityDetectionService = communityDetectionService;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "javaducker_extract_entities",
            description = "Extract entities and relationships from an indexed artifact. " +
                    "Entities: [{name, type, description}]. Types: class, interface, method, function, module, " +
                    "endpoint, table, config-key, event, exception, concept, service, pattern, enum, annotation. " +
                    "Relationships: [{sourceName, targetName, type, description}]. " +
                    "Rel types: uses, extends, implements, calls, depends-on, configures, tests, creates, contains, references")
    public Map<String, Object> extractEntities(
            @ToolParam(description = "Artifact ID the entities were extracted from", required = true) String artifact_id,
            @ToolParam(description = "JSON array of entity objects [{name, type, description}]", required = true) String entities,
            @ToolParam(description = "JSON array of relationship objects [{sourceName, targetName, type, description}]", required = false) String relationships) {
        try {
            List<Map<String, String>> entityList = objectMapper.readValue(entities, new TypeReference<>() {});
            if (entityList.isEmpty()) {
                return Map.of("error", "At least one entity required");
            }

            int created = 0, merged = 0;
            for (Map<String, String> e : entityList) {
                String name = e.get("name");
                String type = e.get("type");
                String desc = e.getOrDefault("description", null);
                if (name == null || type == null) continue;
                Map<String, Object> result = service.upsertEntity(name, type, desc, artifact_id, null);
                if ("created".equals(result.get("action"))) created++;
                else merged++;
            }

            int relCreated = 0, relMerged = 0;
            if (relationships != null && !relationships.isBlank()) {
                List<Map<String, String>> relList = objectMapper.readValue(relationships, new TypeReference<>() {});
                for (Map<String, String> r : relList) {
                    String sourceName = r.get("sourceName");
                    String targetName = r.get("targetName");
                    String relType = r.get("type");
                    String desc = r.getOrDefault("description", null);
                    if (sourceName == null || targetName == null || relType == null) continue;

                    // Resolve entity IDs by name lookup
                    String sourceId = resolveEntityId(sourceName);
                    String targetId = resolveEntityId(targetName);
                    if (sourceId == null || targetId == null) continue;

                    Map<String, Object> result = service.upsertRelationship(
                            sourceId, targetId, relType, desc, artifact_id, null, 1.0);
                    if ("created".equals(result.get("action"))) relCreated++;
                    else relMerged++;
                }
            }

            return Map.of(
                    "artifact_id", artifact_id,
                    "entities_created", created,
                    "entities_merged", merged,
                    "relationships_created", relCreated,
                    "relationships_merged", relMerged);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("error", "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("extractEntities failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_get_entities",
            description = "Get entities from the knowledge graph. Filter by artifact, type, or name pattern")
    public Map<String, Object> getEntities(
            @ToolParam(description = "Filter by entity type (e.g. class, method, service)", required = false) String entity_type,
            @ToolParam(description = "Filter by name pattern (case-insensitive substring match)", required = false) String name_pattern) {
        try {
            List<Map<String, Object>> results;
            if (name_pattern != null && !name_pattern.isBlank()) {
                results = service.findEntitiesByName(name_pattern);
                if (entity_type != null && !entity_type.isBlank()) {
                    results = results.stream()
                            .filter(e -> entity_type.equals(e.get("entity_type")))
                            .toList();
                }
            } else if (entity_type != null && !entity_type.isBlank()) {
                results = service.findEntitiesByType(entity_type);
            } else {
                results = service.findEntitiesByName("");
            }
            return Map.of("entities", results, "count", results.size());
        } catch (Exception e) {
            log.error("getEntities failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_merge_entities",
            description = "Merge two entities into one. The source entity is absorbed into the target. " +
                    "All relationships are rewired, mention counts are combined, source is deleted")
    public Map<String, Object> mergeEntities(
            @ToolParam(description = "Entity ID to merge FROM (will be deleted)", required = true) String source_entity_id,
            @ToolParam(description = "Entity ID to merge INTO (will be kept)", required = true) String target_entity_id,
            @ToolParam(description = "Merged description combining both entities", required = false) String merged_description) {
        try {
            return service.mergeEntities(source_entity_id, target_entity_id, merged_description);
        } catch (Exception e) {
            log.error("mergeEntities failed {} -> {}", source_entity_id, target_entity_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_delete_entities",
            description = "Remove all entities and relationships sourced solely from a given artifact. " +
                    "Entities shared with other artifacts survive with decremented mention count")
    public Map<String, Object> deleteEntities(
            @ToolParam(description = "Artifact ID whose entities should be removed", required = true) String artifact_id) {
        try {
            return service.deleteEntitiesForArtifact(artifact_id);
        } catch (Exception e) {
            log.error("deleteEntities failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_graph_stats",
            description = "Get knowledge graph statistics: entity count, relationship count, top types, most connected entities")
    public Map<String, Object> graphStats() {
        try {
            return service.getStats();
        } catch (Exception e) {
            log.error("graphStats failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_graph_neighborhood",
            description = "Get the neighborhood of an entity: all connected entities within N hops")
    public Map<String, Object> graphNeighborhood(
            @ToolParam(description = "Entity ID to explore from", required = true) String entity_id,
            @ToolParam(description = "Number of hops (default 2, max 5)", required = false) Integer depth) {
        try {
            int d = depth != null ? Math.min(depth, 5) : 2;
            return service.getNeighborhood(entity_id, d);
        } catch (Exception e) {
            log.error("graphNeighborhood failed for {}", entity_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_graph_path",
            description = "Find the shortest path between two entities in the knowledge graph")
    public Map<String, Object> graphPath(
            @ToolParam(description = "Starting entity ID", required = true) String from_entity_id,
            @ToolParam(description = "Target entity ID", required = true) String to_entity_id) {
        try {
            return service.getPath(from_entity_id, to_entity_id);
        } catch (Exception e) {
            log.error("graphPath failed {} -> {}", from_entity_id, to_entity_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_graph_search",
            description = "Search the knowledge graph. Modes: local (entity-centric), global (relationship-centric), " +
                    "graph_hybrid (combined local+global), mix (graph+vector combined - recommended)")
    public Map<String, Object> graphSearch(
            @ToolParam(description = "Search query", required = true) String query,
            @ToolParam(description = "Search mode: local, global, graph_hybrid, mix", required = false) String mode,
            @ToolParam(description = "Max results (default 10)", required = false) Integer top_k,
            @ToolParam(description = "Filter by entity types (comma-separated)", required = false) String entity_types) {
        try {
            String effectiveMode = (mode == null || mode.isBlank()) ? "mix" : mode.toLowerCase();
            int effectiveTopK = (top_k == null || top_k <= 0) ? 10 : top_k;

            List<Map<String, Object>> results = switch (effectiveMode) {
                case "local" -> graphSearchService.localSearch(query, effectiveTopK);
                case "global" -> graphSearchService.globalSearch(query, effectiveTopK);
                case "graph_hybrid" -> graphSearchService.hybridGraphSearch(query, effectiveTopK);
                case "mix" -> graphSearchService.mixSearch(query, effectiveTopK);
                default -> throw new IllegalArgumentException(
                        "Unknown mode: " + effectiveMode + ". Use local, global, graph_hybrid, or mix.");
            };

            // Filter by entity types if specified (applies to local/graph_hybrid modes)
            if (entity_types != null && !entity_types.isBlank()) {
                Set<String> types = Set.of(entity_types.split(","));
                results = results.stream()
                        .filter(r -> r.get("entity_type") == null || types.contains(r.get("entity_type")))
                        .toList();
            }

            return Map.of("mode", effectiveMode, "results", results, "count", results.size());
        } catch (Exception e) {
            log.error("graphSearch failed for query: {}", query, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_merge_candidates",
            description = "Find entities that may be duplicates based on name similarity and embedding similarity. " +
                    "Returns pairs with confidence scores for Claude to review and confirm merges")
    public Map<String, Object> mergeCandidates(
            @ToolParam(description = "Optional entity ID to find merge candidates for", required = false) String entity_id) {
        try {
            if (entity_id != null && !entity_id.isBlank()) {
                List<Map<String, Object>> candidates = service.findMergeCandidates(entity_id);
                return Map.of("entity_id", entity_id, "candidates", candidates, "count", candidates.size());
            } else {
                List<Map<String, Object>> candidates = service.findDuplicateCandidates();
                return Map.of("candidates", candidates, "count", candidates.size());
            }
        } catch (Exception e) {
            log.error("mergeCandidates failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_confirm_merge",
            description = "Confirm and execute an entity merge after reviewing candidates. " +
                    "Provide the merged description combining key information from both entities")
    public Map<String, Object> confirmMerge(
            @ToolParam(description = "Entity ID to merge FROM (will be deleted)", required = true) String source_entity_id,
            @ToolParam(description = "Entity ID to merge INTO (will be kept)", required = true) String target_entity_id,
            @ToolParam(description = "Merged description combining info from both entities", required = true) String merged_description) {
        try {
            return service.mergeEntities(source_entity_id, target_entity_id, merged_description);
        } catch (Exception e) {
            log.error("confirmMerge failed {} -> {}", source_entity_id, target_entity_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_reindex_graph",
            description = "Clean graph data for an artifact that was re-indexed. Removes entities/relationships " +
                    "sourced solely from this artifact. Shared entities survive with decremented counts. " +
                    "Call javaducker_extract_entities afterward to re-extract from the updated file")
    public Map<String, Object> reindexGraph(
            @ToolParam(description = "Artifact ID that was re-indexed", required = true) String artifact_id) {
        try {
            return graphUpdateService.onArtifactReindexed(artifact_id);
        } catch (Exception e) {
            log.error("reindexGraph failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_graph_stale",
            description = "Find entities and relationships that may be stale because their source artifacts " +
                    "have been re-indexed since the entities were extracted")
    public Map<String, Object> graphStale() {
        try {
            var stale = graphUpdateService.findStaleGraphEntries();
            return Map.of("stale_entries", stale, "count", stale.size());
        } catch (Exception e) {
            log.error("graphStale failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_detect_communities",
            description = "Run community detection on the knowledge graph. Groups related entities " +
                    "into communities using label propagation")
    public Map<String, Object> detectCommunities() {
        try {
            return communityDetectionService.detectCommunities();
        } catch (Exception e) {
            log.error("detectCommunities failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_summarize_community",
            description = "Store a summary for a community. Claude should generate the summary " +
                    "after reviewing community members")
    public Map<String, Object> summarizeCommunity(
            @ToolParam(description = "Community ID", required = true) String community_id,
            @ToolParam(description = "Community summary text", required = true) String summary) {
        try {
            return communityDetectionService.summarizeCommunity(community_id, summary);
        } catch (Exception e) {
            log.error("summarizeCommunity failed for {}", community_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_communities",
            description = "List all detected communities with member counts")
    public Map<String, Object> listCommunities() {
        try {
            var communities = communityDetectionService.getCommunities();
            return Map.of("communities", communities, "count", communities.size());
        } catch (Exception e) {
            log.error("listCommunities failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    private String resolveEntityId(String entityName) throws Exception {
        List<Map<String, Object>> matches = service.findEntitiesByName(entityName);
        // Prefer exact name match
        for (Map<String, Object> m : matches) {
            if (entityName.equals(m.get("entity_name"))) {
                return (String) m.get("entity_id");
            }
        }
        return matches.isEmpty() ? null : (String) matches.get(0).get("entity_id");
    }
}
