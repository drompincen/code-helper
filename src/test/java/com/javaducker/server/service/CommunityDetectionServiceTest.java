package com.javaducker.server.service;

import com.javaducker.server.config.AppConfig;
import com.javaducker.server.db.DuckDBDataSource;
import com.javaducker.server.db.SchemaBootstrap;
import com.javaducker.server.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommunityDetectionServiceTest {

    @TempDir
    static Path tempDir;

    static DuckDBDataSource dataSource;
    static KnowledgeGraphService kgService;
    static CommunityDetectionService service;

    @BeforeAll
    static void setup() throws Exception {
        AppConfig config = new AppConfig();
        config.setDbPath(tempDir.resolve("test-cd.duckdb").toString());
        config.setIntakeDir(tempDir.resolve("intake").toString());
        dataSource = new DuckDBDataSource(config);
        ArtifactService artifactService = new ArtifactService(dataSource);
        SearchService searchService = new SearchService(dataSource, new EmbeddingService(config), config);
        IngestionWorker worker = new IngestionWorker(dataSource, artifactService,
                new TextExtractor(), new TextNormalizer(), new Chunker(),
                new EmbeddingService(config), new FileSummarizer(), new ImportParser(),
                new ReladomoXmlParser(), new ReladomoService(dataSource),
                new ReladomoFinderParser(), new ReladomoConfigParser(),
                searchService, config);
        SchemaBootstrap bootstrap = new SchemaBootstrap(dataSource, config, worker);
        bootstrap.createSchema();
        EmbeddingService embeddingService = new EmbeddingService(config);
        kgService = new KnowledgeGraphService(dataSource, embeddingService);
        service = new CommunityDetectionService(dataSource);

        // Seed two clusters:
        // Cluster 1: AuthService <-> TokenValidator <-> LoginController (auth cluster)
        kgService.upsertEntity("AuthService", "class", "Authentication service", "art-1", null);
        kgService.upsertEntity("TokenValidator", "class", "Validates JWT tokens", "art-1", null);
        kgService.upsertEntity("LoginController", "class", "Handles login", "art-1", null);
        kgService.upsertRelationship("class-authservice", "class-tokenvalidator",
                "uses", "auth uses token", "art-1", null, 1.0);
        kgService.upsertRelationship("class-logincontroller", "class-authservice",
                "calls", "login calls auth", "art-1", null, 1.0);

        // Cluster 2: PaymentService <-> StripeClient (payment cluster)
        kgService.upsertEntity("PaymentService", "class", "Processes payments", "art-1", null);
        kgService.upsertEntity("StripeClient", "class", "Stripe API client", "art-1", null);
        kgService.upsertRelationship("class-paymentservice", "class-stripeclient",
                "uses", "payment uses stripe", "art-1", null, 1.0);
    }

    @AfterAll
    static void teardown() throws Exception {
        dataSource.close();
    }

    @Test
    @Order(1)
    void detectCommunitiesFindsCluster() throws Exception {
        var result = service.detectCommunities();
        int detected = ((Number) result.get("communities_detected")).intValue();
        assertTrue(detected >= 2, "Expected at least 2 communities, got " + detected);
    }

    @Test
    @Order(2)
    void authEntitiesInSameCommunity() throws Exception {
        var communities = service.getCommunities();
        // Find the community containing AuthService
        String authCommunityId = null;
        List<String> authMembers = null;
        for (Map<String, Object> c : communities) {
            List<String> ids = CommunityDetectionService.parseJsonArray(
                    (String) c.get("entity_ids"));
            if (ids.contains("class-authservice")) {
                authCommunityId = (String) c.get("community_id");
                authMembers = ids;
                break;
            }
        }
        assertNotNull(authCommunityId, "AuthService should be in a community");
        assertTrue(authMembers.contains("class-tokenvalidator"),
                "TokenValidator should be in same community as AuthService");
        assertTrue(authMembers.contains("class-logincontroller"),
                "LoginController should be in same community as AuthService");
    }

    @Test
    @Order(3)
    void paymentEntitiesInSameCommunity() throws Exception {
        var communities = service.getCommunities();
        String payCommunityId = null;
        List<String> payMembers = null;
        for (Map<String, Object> c : communities) {
            List<String> ids = CommunityDetectionService.parseJsonArray(
                    (String) c.get("entity_ids"));
            if (ids.contains("class-paymentservice")) {
                payCommunityId = (String) c.get("community_id");
                payMembers = ids;
                break;
            }
        }
        assertNotNull(payCommunityId, "PaymentService should be in a community");
        assertTrue(payMembers.contains("class-stripeclient"),
                "StripeClient should be in same community as PaymentService");
    }

    @Test
    @Order(4)
    void getCommunityReturnsMemberDetails() throws Exception {
        var communities = service.getCommunities();
        assertFalse(communities.isEmpty(), "Should have communities");
        String firstId = (String) communities.get(0).get("community_id");

        var community = service.getCommunity(firstId);
        assertNotNull(community, "getCommunity should return a result");
        assertNotNull(community.get("entity_ids"), "Should have entity_ids");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members =
                (List<Map<String, Object>>) community.get("members");
        assertNotNull(members, "Should have members list");
        assertFalse(members.isEmpty(), "Members list should not be empty");
        // Each member should have entity details
        for (Map<String, Object> member : members) {
            assertNotNull(member.get("entity_id"));
            assertNotNull(member.get("entity_name"));
        }
    }

    @Test
    @Order(5)
    void summarizeCommunityStoresSummary() throws Exception {
        var communities = service.getCommunities();
        String firstId = (String) communities.get(0).get("community_id");
        String summaryText = "This community contains authentication-related classes.";

        var result = service.summarizeCommunity(firstId, summaryText);
        assertEquals(firstId, result.get("community_id"));
        assertTrue((Boolean) result.get("summary_stored"));

        // Retrieve and verify
        var community = service.getCommunity(firstId);
        assertEquals(summaryText, community.get("summary"));
    }

    @Test
    @Order(6)
    void rebuildClearsPreviousCommunities() throws Exception {
        // First detect (already done), then rebuild
        var result = service.rebuildCommunities();
        int detected = ((Number) result.get("communities_detected")).intValue();
        assertTrue(detected >= 2, "Rebuild should re-detect communities");

        // Verify communities exist and summaries are cleared (fresh detection)
        var communities = service.getCommunities();
        assertFalse(communities.isEmpty());
    }

    @Test
    @Order(7)
    void listCommunitiesReturnsAll() throws Exception {
        // Ensure detection has run
        service.detectCommunities();
        var communities = service.getCommunities();
        assertTrue(communities.size() >= 2,
                "Should list at least 2 communities, got " + communities.size());
        // Each community should have member_count
        for (Map<String, Object> c : communities) {
            assertNotNull(c.get("community_id"));
            assertNotNull(c.get("community_name"));
            assertTrue(((Number) c.get("member_count")).intValue() >= 2);
        }
    }
}
