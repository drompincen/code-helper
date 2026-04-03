package com.javaducker.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaducker.server.service.ContentIntelligenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ContentIntelligenceTools {

    private static final Logger log = LoggerFactory.getLogger(ContentIntelligenceTools.class);

    private final ContentIntelligenceService service;
    private final ObjectMapper objectMapper;

    public ContentIntelligenceTools(ContentIntelligenceService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    // ── Write tools ────────────────────────────────────────────────────────────

    @Tool(name = "javaducker_classify",
            description = "Classify an artifact by document type (e.g. code, config, doc, test)")
    public Map<String, Object> classify(
            @ToolParam(description = "Artifact ID to classify", required = true) String artifact_id,
            @ToolParam(description = "Document type (e.g. code, config, doc, test)", required = true) String doc_type,
            @ToolParam(description = "Confidence score 0.0-1.0", required = false) Double confidence,
            @ToolParam(description = "Classification method (e.g. llm, rule)", required = false) String method) {
        try {
            double conf = confidence != null ? confidence : 1.0;
            String meth = method != null ? method : "llm";
            return service.classify(artifact_id, doc_type, conf, meth);
        } catch (Exception e) {
            log.error("classify failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_tag",
            description = "Add tags to an artifact. Tags is a JSON array of {tag, tag_type, source} objects")
    public Map<String, Object> tag(
            @ToolParam(description = "Artifact ID to tag", required = true) String artifact_id,
            @ToolParam(description = "JSON array of tag objects [{tag, tag_type, source}]", required = true) String tags) {
        try {
            List<Map<String, String>> tagList = objectMapper.readValue(tags, new TypeReference<>() {});
            return service.tag(artifact_id, tagList);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("error", "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("tag failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_extract_points",
            description = "Extract key points from an artifact. Points is a JSON array of {point_type, point_text}")
    public Map<String, Object> extractPoints(
            @ToolParam(description = "Artifact ID", required = true) String artifact_id,
            @ToolParam(description = "JSON array of point objects [{point_type, point_text}]", required = true) String points) {
        try {
            List<Map<String, String>> pointList = objectMapper.readValue(points, new TypeReference<>() {});
            return service.extractPoints(artifact_id, pointList);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("error", "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("extractPoints failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_set_freshness",
            description = "Set freshness status for an artifact: current, stale, or superseded")
    public Map<String, Object> setFreshness(
            @ToolParam(description = "Artifact ID", required = true) String artifact_id,
            @ToolParam(description = "Freshness: current, stale, or superseded", required = true) String freshness,
            @ToolParam(description = "Artifact ID that supersedes this one (optional)", required = false) String superseded_by) {
        try {
            return service.setFreshness(artifact_id, freshness, superseded_by);
        } catch (Exception e) {
            log.error("setFreshness failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_synthesize",
            description = "Store a synthesis (summary, tags, key points, outcome) for an artifact")
    public Map<String, Object> synthesize(
            @ToolParam(description = "Artifact ID", required = true) String artifact_id,
            @ToolParam(description = "Summary text", required = true) String summary_text,
            @ToolParam(description = "Comma-separated tags", required = false) String tags,
            @ToolParam(description = "Key points text", required = false) String key_points,
            @ToolParam(description = "Outcome or conclusion", required = false) String outcome,
            @ToolParam(description = "Original file path", required = false) String original_file_path) {
        try {
            return service.synthesize(artifact_id, summary_text, tags, key_points, outcome, original_file_path);
        } catch (Exception e) {
            log.error("synthesize failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_link_concepts",
            description = "Link concepts across artifacts. Links is a JSON array of {concept, artifact_a, artifact_b, strength}")
    public Map<String, Object> linkConcepts(
            @ToolParam(description = "JSON array of link objects [{concept, artifact_a, artifact_b, strength}]", required = true) String links) {
        try {
            List<Map<String, Object>> linkList = objectMapper.readValue(links, new TypeReference<>() {});
            return service.linkConcepts(linkList);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("error", "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("linkConcepts failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_enrich_queue",
            description = "Get the queue of artifacts awaiting enrichment")
    public Map<String, Object> enrichQueue(
            @ToolParam(description = "Max items to return (default 50)", required = false) Integer limit) {
        try {
            int lim = limit != null ? limit : 50;
            List<Map<String, Object>> queue = service.getEnrichQueue(lim);
            return Map.of("results", queue, "count", queue.size());
        } catch (Exception e) {
            log.error("enrichQueue failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_mark_enriched",
            description = "Mark an artifact as enriched (remove from enrich queue)")
    public Map<String, Object> markEnriched(
            @ToolParam(description = "Artifact ID to mark as enriched", required = true) String artifact_id) {
        try {
            return service.markEnriched(artifact_id);
        } catch (Exception e) {
            log.error("markEnriched failed for {}", artifact_id, e);
            return Map.of("error", e.getMessage());
        }
    }

    // ── Read tools ─────────────────────────────────────────────────────────────

    @Tool(name = "javaducker_latest",
            description = "Get the latest artifact for a given topic")
    public Map<String, Object> latest(
            @ToolParam(description = "Topic to search for", required = true) String topic) {
        try {
            return service.getLatest(topic);
        } catch (Exception e) {
            log.error("latest failed for topic {}", topic, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_find_by_type",
            description = "Find all artifacts of a given document type")
    public Map<String, Object> findByType(
            @ToolParam(description = "Document type to filter by", required = true) String doc_type) {
        try {
            List<Map<String, Object>> results = service.findByType(doc_type);
            return Map.of("results", results, "count", results.size());
        } catch (Exception e) {
            log.error("findByType failed for {}", doc_type, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_find_by_tag",
            description = "Find all artifacts with a given tag")
    public Map<String, Object> findByTag(
            @ToolParam(description = "Tag to search for", required = true) String tag) {
        try {
            List<Map<String, Object>> results = service.findByTag(tag);
            return Map.of("results", results, "count", results.size());
        } catch (Exception e) {
            log.error("findByTag failed for {}", tag, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_find_points",
            description = "Find extracted points by type, optionally filtered by tag")
    public Map<String, Object> findPoints(
            @ToolParam(description = "Point type (e.g. decision, action, question)", required = true) String point_type,
            @ToolParam(description = "Optional tag to filter by", required = false) String tag) {
        try {
            List<Map<String, Object>> results = service.findPoints(point_type, tag);
            return Map.of("results", results, "count", results.size());
        } catch (Exception e) {
            log.error("findPoints failed for type={} tag={}", point_type, tag, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_concepts",
            description = "List all known concepts and their linked artifacts")
    public Map<String, Object> concepts() {
        try {
            List<Map<String, Object>> results = service.listConcepts();
            return Map.of("results", results, "count", results.size());
        } catch (Exception e) {
            log.error("concepts failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_concept_timeline",
            description = "Get the timeline of artifacts for a specific concept")
    public Map<String, Object> conceptTimeline(
            @ToolParam(description = "Concept name", required = true) String concept) {
        try {
            return service.getConceptTimeline(concept);
        } catch (Exception e) {
            log.error("conceptTimeline failed for {}", concept, e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_concept_health",
            description = "Get health metrics for all concepts (coverage, staleness, etc.)")
    public Map<String, Object> conceptHealth() {
        try {
            return service.getConceptHealth();
        } catch (Exception e) {
            log.error("conceptHealth failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_stale_content",
            description = "List all stale or superseded content")
    public Map<String, Object> staleContent() {
        try {
            List<Map<String, Object>> results = service.getStaleContent();
            return Map.of("results", results, "count", results.size());
        } catch (Exception e) {
            log.error("staleContent failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "javaducker_synthesis",
            description = "Get synthesis for an artifact by ID, or search syntheses by keyword")
    public Map<String, Object> synthesis(
            @ToolParam(description = "Artifact ID to get synthesis for", required = false) String artifact_id,
            @ToolParam(description = "Keyword to search syntheses", required = false) String keyword) {
        try {
            if (artifact_id != null && !artifact_id.isBlank()) {
                return service.getSynthesis(artifact_id);
            }
            if (keyword != null && !keyword.isBlank()) {
                List<Map<String, Object>> results = service.searchSynthesis(keyword);
                return Map.of("results", results, "count", results.size());
            }
            return Map.of("error", "Either artifact_id or keyword must be provided");
        } catch (Exception e) {
            log.error("synthesis failed artifact_id={} keyword={}", artifact_id, keyword, e);
            return Map.of("error", e.getMessage());
        }
    }
}
