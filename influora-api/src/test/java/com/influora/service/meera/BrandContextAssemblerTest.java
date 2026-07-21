package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AnalysisStatus;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import com.influora.web.dto.meera.MeeraContextDtos.PastCampaignEntry;
import com.influora.web.dto.meera.MeeraContextDtos.TemplateDigestEntry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Platform-AI Phase 1, Wave 1a (Priya A1/A2) — {@link BrandContextAssembler#assembleBrandContext}
 * is the ONLY place the BRAND field allow-list + the product_catalog name/price/currency filter
 * are enforced for the new {@code POST /internal/meera/context} endpoint.
 */
@ExtendWith(MockitoExtension.class)
class BrandContextAssemblerTest {

    @Mock private Workspace workspace;

    private final BrandContextAssembler assembler = new BrandContextAssembler();

    @Test
    @DisplayName("product_catalog entries are filtered to name/price/currency only — extra scraped fields dropped")
    void testProductCatalogFilteredToAllowedFields() {
        when(workspace.getId()).thenReturn("ws1");
        when(workspace.getName()).thenReturn("Acme");

        BrandProfile profile =
                BrandProfile.builder().id("bp1").workspaceId("ws1").analysisStatus(AnalysisStatus.READY).build();
        profile.applyAnalysisResult(
                "[{\"name\":\"Widget\",\"price\":499,\"currency\":\"INR\",\"sku\":\"secret-internal-sku\","
                        + "\"supplierCost\":50}]",
                null,
                null,
                "[]",
                null);

        ContextResponse response =
                assembler.assembleBrandContext(workspace, profile, List.of(), List.of(), "metered", 42);

        assertEquals(1, response.productCatalog().size());
        var entry = response.productCatalog().get(0);
        assertEquals(3, entry.size());
        assertTrue(entry.containsKey("name"));
        assertTrue(entry.containsKey("price"));
        assertTrue(entry.containsKey("currency"));
        assertTrue(!entry.containsKey("sku"));
        assertTrue(!entry.containsKey("supplierCost"));
    }

    @Test
    @DisplayName("no BrandProfile yet -> analysis_status PENDING, no product_catalog/niche_tags/tone fields")
    void testNoBrandProfileYieldsPendingStatus() {
        when(workspace.getId()).thenReturn("ws1");
        when(workspace.getName()).thenReturn("Acme");

        ContextResponse response =
                assembler.assembleBrandContext(workspace, null, List.of(), List.of(), "unlimited", 0);

        assertEquals("PENDING", response.analysisStatus());
        assertNull(response.productCatalog());
        assertNull(response.nicheTags());
    }

    @Test
    @DisplayName("template_digest: one line per template — name, campaign_type (incl. STANDARD), budget band, key requirements")
    void testTemplateDigestFormatting() {
        when(workspace.getId()).thenReturn("ws1");
        when(workspace.getName()).thenReturn("Acme");

        CampaignTemplate ugc =
                CampaignTemplate.builder()
                        .id("t1")
                        .name("UGC Starter")
                        .category(CampaignTemplateCategory.UGC)
                        .scope(CampaignTemplateScope.SYSTEM)
                        .campaignType(CampaignIntentType.STANDARD)
                        .budgetMin(new BigDecimal("5000"))
                        .budgetMax(new BigDecimal("20000"))
                        .requirementsJson("[\"Include tracked link\",\"Disclose partnership\",\"Post within 7 days\",\"Tag brand\"]")
                        .build();

        ContextResponse response =
                assembler.assembleBrandContext(workspace, null, List.of(ugc), List.of(), "metered", 10);

        assertEquals(1, response.templateDigest().size());
        TemplateDigestEntry entry = response.templateDigest().get(0);
        assertEquals("UGC Starter", entry.name());
        assertEquals("STANDARD", entry.campaignType());
        assertEquals("₹5000–₹20000", entry.budgetBand());
        // Capped at 3 requirements, even though the template has 4.
        assertEquals("Include tracked link, Disclose partnership, Post within 7 days", entry.keyRequirements());
    }

    @Test
    @DisplayName("brand_color is extracted from brand_aesthetic.accent_color (Ash's seam ruling canonical key)")
    void testBrandColorExtractedFromAccentColor() {
        when(workspace.getId()).thenReturn("ws1");
        when(workspace.getName()).thenReturn("Acme");

        BrandProfile profile =
                BrandProfile.builder().id("bp1").workspaceId("ws1").analysisStatus(AnalysisStatus.READY).build();
        profile.applyAnalysisResult(null, "{\"accent_color\":\"#FF5733\"}", null, "[]", null);

        ContextResponse response =
                assembler.assembleBrandContext(workspace, profile, List.of(), List.of(), "metered", 42);

        assertEquals("#FF5733", response.brandColor());
    }

    @Test
    @DisplayName("past_campaign_summary and credit_state pass through as given by the caller")
    void testPastCampaignSummaryAndCreditStatePassThrough() {
        when(workspace.getId()).thenReturn("ws1");
        when(workspace.getName()).thenReturn("Acme");

        List<PastCampaignEntry> pastCampaigns =
                List.of(new PastCampaignEntry("HYPE", 3, true), new PastCampaignEntry("DIRECT", 0, false));

        ContextResponse response =
                assembler.assembleBrandContext(workspace, null, List.of(), pastCampaigns, "unlimited", 7);

        assertEquals(pastCampaigns, response.pastCampaignSummary());
        assertEquals("unlimited", response.creditState().mode());
        assertEquals(7, response.creditState().creditsRemaining());
    }
}
