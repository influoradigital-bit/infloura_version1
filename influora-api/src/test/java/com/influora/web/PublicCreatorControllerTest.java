package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.service.PublicCreatorService;
import com.influora.web.dto.creator.PublicCreatorDtos.VerifiedMetrics;
import com.influora.web.dto.creator.PublicCreatorDtos.VerifiedProfileResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Gate fix round 1 (Priya Q10, T-MEERA-CREATOR-PHASE-A) — this is a fully unauthenticated,
 * possibly-cache-fronted endpoint, so two things have to hold that nothing previously proved:
 * (1) the response never carries a caching header that would let an intermediary (CDN, shared
 * proxy) replay a snapshot after a creator opts out or is suspended, and (2) the wire JSON never
 * grows a field beyond the seven-key allow-list SPEC.md 2.8 promises — a future field added to
 * {@link VerifiedProfileResponse}/{@link VerifiedMetrics} should fail this test loudly rather than
 * silently leak onto a public, indexable page. Plain unit test against a mocked {@link
 * PublicCreatorService}, same convention as {@code AnalyticsControllerTest} (no
 * MockMvc/spring-security-test harness in this codebase).
 */
@ExtendWith(MockitoExtension.class)
class PublicCreatorControllerTest {

    private static final String USERNAME = "priya-shah";

    @Mock private PublicCreatorService publicCreatorService;
    @Mock private MeeraCreatorFeatureProperties featureProperties;

    private PublicCreatorController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicCreatorController(publicCreatorService, featureProperties);
    }

    @Test
    @DisplayName("GET /public/creators/{username}/verified sets Cache-Control: no-store, private")
    void getVerifiedMetricsSetsNoStoreCacheControl() {
        when(featureProperties.isCreatorEnabled()).thenReturn(true);
        VerifiedProfileResponse response = sampleResponse();
        when(publicCreatorService.getVerifiedMetrics(USERNAME)).thenReturn(response);

        ResponseEntity<ApiResponse<VerifiedProfileResponse>> result = controller.getVerifiedMetrics(USERNAME);

        String cacheControl = result.getHeaders().getCacheControl();
        assertTrue(cacheControl != null && cacheControl.contains("no-store"), "expected no-store, got: " + cacheControl);
        assertTrue(cacheControl.contains("private"), "expected private, got: " + cacheControl);
        assertEquals(response, result.getBody().data());
    }

    @Test
    @DisplayName(
            "GET /public/creators/{username}/verified serializes to EXACTLY the seven allow-listed"
                    + " top-level keys plus the four verified_metrics keys -- SPEC.md 2.8, no floors, no"
                    + " rates, no PAN/GSTIN, no ids")
    void serializesToExactlyTheAllowListedKeys() throws Exception {
        VerifiedProfileResponse response = sampleResponse();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        JsonNode root = mapper.valueToTree(response);

        Set<String> expectedTopLevel =
                Set.of(
                        "username",
                        "display_name",
                        "city",
                        "categories",
                        "verified_metrics",
                        "platform_deal_count",
                        "snapshot_date");
        assertEquals(expectedTopLevel, fieldNames(root));

        Set<String> expectedMetricKeys = Set.of("followers", "reach_30d", "engagement_rate", "verified_at");
        assertEquals(expectedMetricKeys, fieldNames(root.get("verified_metrics")));
    }

    @Test
    @DisplayName(
            "Priya gate review defect 4 -- GET /public/creators/{username}/verified returns 404"
                    + " FEATURE_DISABLED and never touches PublicCreatorService when the rollback flag"
                    + " is off")
    void getVerifiedMetrics_flagOff_returns404WithoutTouchingService() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.getVerifiedMetrics(USERNAME));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(publicCreatorService);
    }

    private static Set<String> fieldNames(JsonNode node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static VerifiedProfileResponse sampleResponse() {
        return new VerifiedProfileResponse(
                USERNAME,
                "Priya Shah",
                "Pune",
                List.of("Beauty", "Lifestyle"),
                new VerifiedMetrics(12_400L, 45_000L, new BigDecimal("3.2"), Instant.parse("2026-09-01T00:00:00Z")),
                7L,
                Instant.parse("2026-09-03T00:00:00Z"));
    }
}
