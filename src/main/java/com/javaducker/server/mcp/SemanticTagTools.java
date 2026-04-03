package com.javaducker.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.SemanticTagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SemanticTagTools {

    private static final Logger log = LoggerFactory.getLogger(SemanticTagTools.class);

    private final SemanticTagService service;
    private final ObjectMapper objectMapper;

    public SemanticTagTools(SemanticTagService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "javaducker_synthesize_tags",
            description = "Store 4-10 semantic tags for an artifact. Categories: functional, architectural, domain, pattern, concern. Each tag needs: tag, category, confidence (0-1), rationale")
    public Map<String, Object> synthesizeTags(
            @ToolParam(description = "Artifact ID", required = true) String artifact_id,
            @ToolParam(description = "JSON array of tag objects [{tag, category, confidence, rationale}]", required = true) String tags) {
        try {
            List<Map<String, Object>> tagList = objectMapper.readValue(tags, new TypeReference<>() {});
            return service.writeTags(artifact_id, tagList);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("error", "Invalid JSON: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            log.error("synthesizeTags failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_search_by_tags",
            description = "Search artifacts by semantic tags. Returns files matching the given tags")
    public Map<String, Object> searchByTags(
            @ToolParam(description = "JSON array of tag strings", required = true) String tags,
            @ToolParam(description = "Match mode: 'any' or 'all'", required = false) String match_mode,
            @ToolParam(description = "Filter by category", required = false) String category) {
        try {
            List<String> tagList = objectMapper.readValue(tags, new TypeReference<>() {});
            boolean matchAll = "all".equalsIgnoreCase(match_mode);
            List<Map<String, Object>> results;

            if (category != null && !category.isBlank()) {
                // Filter by category: get artifacts matching tags, then filter
                results = service.searchByTags(tagList, matchAll);
                // Post-filter: only keep artifacts that have at least one tag in the given category
                List<Map<String, Object>> byCategory = service.findByCategory(category);
                var categoryArtifacts = new java.util.HashSet<String>();
                for (Map<String, Object> row : byCategory) {
                    categoryArtifacts.add((String) row.get("artifact_id"));
                }
                results = results.stream()
                        .filter(r -> categoryArtifacts.contains(r.get("artifact_id")))
                        .toList();
            } else {
                results = service.searchByTags(tagList, matchAll);
            }
            return Map.of("results", results, "count", results.size());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("error", "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("searchByTags failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_tag_cloud",
            description = "Get all semantic tags grouped by category with artifact counts")
    public Map<String, Object> tagCloud() {
        try {
            return service.getTagCloud();
        } catch (Exception e) {
            log.error("tagCloud failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_suggest_tags",
            description = "Suggest semantic tags for an artifact based on similar files")
    public Map<String, Object> suggestTags(
            @ToolParam(description = "Artifact ID", required = true) String artifact_id) {
        try {
            List<Map<String, Object>> suggestions = service.suggestTags(artifact_id);
            return Map.of("artifact_id", artifact_id, "suggestions", suggestions, "count", suggestions.size());
        } catch (Exception e) {
            log.error("suggestTags failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }
}
