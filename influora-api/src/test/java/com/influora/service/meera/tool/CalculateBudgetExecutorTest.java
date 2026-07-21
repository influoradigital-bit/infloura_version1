package com.influora.service.meera.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.BrandProfile;
import com.influora.repository.BrandProfileRepository;
import com.influora.service.AuditLogService;
import com.influora.web.dto.meera.MeeraToolDtos.CalculateBudgetResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * C1 re-confirm (Kabir P1-B re-audit, 2026-07-21) — {@code calculate_budget} must be
 * provenance-aware WITHOUT trusting the model's tool-call input for that provenance. Kabir's
 * original C1 audit found the executor gated the estimate caveat on a model-supplied
 * {@code price_source} field, which let the model relabel an inferred (guessed) price as
 * "scraped" and suppress the hedge. The fix: {@code price_source} is no longer read from tool
 * input at all (schemas.py no longer declares it) — the executor re-derives it by matching
 * {@code product_price} against the workspace's persisted {@link BrandProfile#getProductCatalogJson()}.
 */
class CalculateBudgetExecutorTest {

    private AuditLogService auditLogService;
    private BrandProfileRepository brandProfileRepository;
    private CalculateBudgetExecutor executor;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        brandProfileRepository = mock(BrandProfileRepository.class);
        executor = new CalculateBudgetExecutor(auditLogService, brandProfileRepository);
        when(brandProfileRepository.findByWorkspaceId(any())).thenReturn(Optional.empty());
    }

    private void withCatalog(String catalogJson) {
        BrandProfile profile = Mockito.mock(BrandProfile.class);
        when(profile.getProductCatalogJson()).thenReturn(catalogJson);
        when(brandProfileRepository.findByWorkspaceId("ws1")).thenReturn(Optional.of(profile));
    }

    @Test
    @DisplayName(
            "DECISIVE: model tool-call would have claimed \"scraped\" (field simply doesn't exist in"
                    + " schema anymore) but persisted catalog says \"inferred\" -> priceConfidence"
                    + " \"inferred\" with caveat; the model has zero influence")
    void testServerCatalogInferredWinsRegardlessOfModelIntent() {
        withCatalog(
                "[{\"name\":\"Widget\",\"price\":500,\"currency\":\"INR\",\"price_source\":\"inferred\"}]");
        // The tool input carries no price_source at all -- it is not part of the schema. Even if a
        // legacy/malicious caller stuffed a "price_source":"scraped" key into the input map (as if
        // trying to exploit the old defeat), the executor must never read it.
        Map<String, Object> input =
                Map.of("product_price", "500", "goal", "launch", "price_source", "scraped");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
        assertTrue(result.rationale().contains("ESTIMATE"));
    }

    @Test
    @DisplayName("persisted catalog says scraped -> priceConfidence \"scraped\", no estimate caveat")
    void testServerCatalogScrapedYieldsScrapedConfidence() {
        withCatalog(
                "[{\"name\":\"Widget\",\"price\":500,\"currency\":\"INR\",\"price_source\":\"scraped\"}]");
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("scraped", result.priceConfidence());
        assertTrue(!result.rationale().contains("ESTIMATE"));
    }

    @Test
    @DisplayName("fail-safe: product price not found in persisted catalog -> priceConfidence defaults"
            + " to \"inferred\", never \"scraped\"")
    void testProductNotInCatalogDefaultsToInferred() {
        withCatalog(
                "[{\"name\":\"Other Widget\",\"price\":999,\"currency\":\"INR\",\"price_source\":\"scraped\"}]");
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName("fail-safe: no BrandProfile for workspace at all -> priceConfidence defaults to"
            + " \"inferred\"")
    void testNoBrandProfileDefaultsToInferred() {
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName("fail-safe: BrandProfile exists but catalog JSON is blank/null -> priceConfidence"
            + " defaults to \"inferred\"")
    void testBlankCatalogDefaultsToInferred() {
        withCatalog(null);
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName("fail-safe: catalog entry missing price_source entirely -> priceConfidence defaults"
            + " to \"inferred\"")
    void testCatalogEntryMissingPriceSourceDefaultsToInferred() {
        withCatalog("[{\"name\":\"Widget\",\"price\":500,\"currency\":\"INR\"}]");
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName("price_confidence is audit-logged alongside the advisory flag")
    void testPriceConfidenceIsAuditLogged() {
        withCatalog(
                "[{\"name\":\"Widget\",\"price\":500,\"currency\":\"INR\",\"price_source\":\"scraped\"}]");
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        executor.execute("ws1", input);

        verify(auditLogService)
                .recordToolCall(
                        "ws1",
                        "calculate_budget",
                        "R",
                        AuditLogService.OUTCOME_ALLOWED,
                        null,
                        null,
                        null,
                        Map.of("advisory", true, "price_confidence", "scraped"));
    }

    @Test
    @DisplayName("no product_price supplied -> still resolves priceConfidence, but rationale is the"
            + " placeholder message")
    void testNoProductPriceStillResolvesPriceConfidence() {
        Map<String, Object> input = Map.of("goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
        assertEquals(java.math.BigDecimal.ZERO, result.suggestedPerCreatorRate());
        assertTrue(result.rationale().contains("No product price supplied"));
    }
}
