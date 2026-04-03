package com.javaducker.integration;

import com.javaducker.server.mcp.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based test that verifies all MCP tool classes declare the expected
 * {@link Tool} annotations with correct names and non-empty descriptions.
 * No Spring context is loaded — this purely checks annotation metadata.
 */
class McpToolRegistrationTest {

    private static final List<Class<?>> TOOL_CLASSES = List.of(
            CoreTools.class,
            AnalysisTools.class,
            WatchTools.class,
            ContentIntelligenceTools.class,
            ReladomoTools.class,
            SessionTools.class,
            SemanticTagTools.class,
            KnowledgeGraphTools.class,
            EnrichmentTools.class
    );

    private static final Set<String> EXPECTED_TOOL_NAMES = Set.of(
            // CoreTools (8)
            "javaducker_health", "javaducker_index_file", "javaducker_index_directory",
            "javaducker_search", "javaducker_get_file_text", "javaducker_get_artifact_status",
            "javaducker_wait_for_indexed", "javaducker_stats",
            // AnalysisTools (10)
            "javaducker_explain", "javaducker_blame", "javaducker_related",
            "javaducker_dependencies", "javaducker_dependents", "javaducker_map",
            "javaducker_stale", "javaducker_index_health", "javaducker_summarize",
            "javaducker_find_related",
            // WatchTools (1)
            "javaducker_watch",
            // ContentIntelligenceTools (17)
            "javaducker_classify", "javaducker_tag", "javaducker_extract_points",
            "javaducker_set_freshness", "javaducker_synthesize", "javaducker_link_concepts",
            "javaducker_enrich_queue", "javaducker_mark_enriched", "javaducker_latest",
            "javaducker_find_by_type", "javaducker_find_by_tag", "javaducker_find_points",
            "javaducker_concepts", "javaducker_concept_timeline", "javaducker_concept_health",
            "javaducker_stale_content", "javaducker_synthesis",
            // ReladomoTools (9)
            "javaducker_reladomo_relationships", "javaducker_reladomo_graph",
            "javaducker_reladomo_path", "javaducker_reladomo_schema",
            "javaducker_reladomo_object_files", "javaducker_reladomo_finders",
            "javaducker_reladomo_deepfetch", "javaducker_reladomo_temporal",
            "javaducker_reladomo_config",
            // SessionTools (5)
            "javaducker_index_sessions", "javaducker_search_sessions",
            "javaducker_session_context", "javaducker_extract_decisions",
            "javaducker_recent_decisions",
            // SemanticTagTools (4)
            "javaducker_synthesize_tags", "javaducker_search_by_tags",
            "javaducker_tag_cloud", "javaducker_suggest_tags",
            // KnowledgeGraphTools (15)
            "javaducker_extract_entities", "javaducker_get_entities",
            "javaducker_merge_entities", "javaducker_delete_entities",
            "javaducker_graph_stats", "javaducker_graph_neighborhood",
            "javaducker_graph_path", "javaducker_graph_search",
            "javaducker_merge_candidates", "javaducker_confirm_merge",
            "javaducker_reindex_graph", "javaducker_graph_stale",
            "javaducker_detect_communities", "javaducker_summarize_community",
            "javaducker_communities",
            // EnrichmentTools (3)
            "javaducker_enrichment_pipeline", "javaducker_enrichment_status",
            "javaducker_rebuild_graph"
    );

    private static final int EXPECTED_TOTAL = 72;

    // ---- helpers ----

    private List<Method> findToolMethods(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .collect(Collectors.toList());
    }

    private String toolName(Method m) {
        return m.getAnnotation(Tool.class).name();
    }

    private String toolDescription(Method m) {
        return m.getAnnotation(Tool.class).description();
    }

    // ---- tests ----

    @Test
    void totalToolCountIs49() {
        long total = TOOL_CLASSES.stream()
                .mapToLong(c -> findToolMethods(c).size())
                .sum();

        assertEquals(EXPECTED_TOTAL, total,
                "Expected " + EXPECTED_TOTAL + " @Tool methods across all tool classes but found " + total);
    }

    @Test
    void perClassToolCounts() {
        Map<String, Integer> expectedCounts = Map.of(
                "CoreTools", 8,
                "AnalysisTools", 10,
                "WatchTools", 1,
                "ContentIntelligenceTools", 17,
                "ReladomoTools", 9,
                "SessionTools", 5,
                "SemanticTagTools", 4,
                "KnowledgeGraphTools", 15,
                "EnrichmentTools", 3
        );

        for (Class<?> clazz : TOOL_CLASSES) {
            int actual = findToolMethods(clazz).size();
            int expected = expectedCounts.get(clazz.getSimpleName());
            assertEquals(expected, actual,
                    clazz.getSimpleName() + " should have " + expected + " @Tool methods but has " + actual);
        }
    }

    @Test
    void allExpectedToolNamesAreRegistered() {
        Set<String> actualNames = TOOL_CLASSES.stream()
                .flatMap(c -> findToolMethods(c).stream())
                .map(this::toolName)
                .collect(Collectors.toSet());

        Set<String> missing = new TreeSet<>(EXPECTED_TOOL_NAMES);
        missing.removeAll(actualNames);
        assertTrue(missing.isEmpty(),
                "Missing expected tool names: " + missing);

        Set<String> unexpected = new TreeSet<>(actualNames);
        unexpected.removeAll(EXPECTED_TOOL_NAMES);
        assertTrue(unexpected.isEmpty(),
                "Unexpected tool names found: " + unexpected);
    }

    @Test
    void everyToolHasNonEmptyDescription() {
        List<String> blanks = TOOL_CLASSES.stream()
                .flatMap(c -> findToolMethods(c).stream())
                .filter(m -> toolDescription(m) == null || toolDescription(m).isBlank())
                .map(m -> m.getDeclaringClass().getSimpleName() + "." + m.getName())
                .collect(Collectors.toList());

        assertTrue(blanks.isEmpty(),
                "@Tool methods with blank description: " + blanks);
    }

    @Test
    void noToolNameDuplicates() {
        List<String> allNames = TOOL_CLASSES.stream()
                .flatMap(c -> findToolMethods(c).stream())
                .map(this::toolName)
                .collect(Collectors.toList());

        Set<String> seen = new HashSet<>();
        List<String> duplicates = allNames.stream()
                .filter(n -> !seen.add(n))
                .collect(Collectors.toList());

        assertTrue(duplicates.isEmpty(),
                "Duplicate tool names found: " + duplicates);
    }

    @Test
    void allToolNamesFollowNamingConvention() {
        List<String> violations = TOOL_CLASSES.stream()
                .flatMap(c -> findToolMethods(c).stream())
                .map(this::toolName)
                .filter(name -> !name.startsWith("javaducker_"))
                .collect(Collectors.toList());

        assertTrue(violations.isEmpty(),
                "Tool names not following 'javaducker_' prefix convention: " + violations);
    }
}
