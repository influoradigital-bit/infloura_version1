package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.CampaignStatus;
import com.influora.service.CampaignMapper.CampaignMetrics;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Priya gate review defect 5 — a pre-V72 {@link Campaign} row (created before the {@code
 * end_brand_name}/{@code end_brand_category} columns existed, V72__meera_creator_deal_terms.sql)
 * has both fields NULL (the migration is additive/nullable, no backfill — see that migration's own
 * comment). Proves {@link CampaignMapper#toResponse} maps such a row without an NPE, and that
 * {@code end_brand_name}/{@code end_brand_category} are OMITTED from the wire JSON entirely (never
 * rendered as a literal {@code null}) — {@link CampaignResponse} is {@code
 * @JsonInclude(NON_NULL)} at the class level.
 */
class CampaignMapperTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGNLEGACY00001";
    private static final String WORKSPACE_ID = "01HWORKSPACELEGACY0001";

    @Test
    @DisplayName(
            "toResponse: a pre-V72 campaign (endBrandName/endBrandCategory both null) maps without"
                    + " an NPE")
    void toResponse_legacyCampaignWithNoEndBrandFields_doesNotThrow() {
        Campaign legacyCampaign = legacyCampaign();

        CampaignResponse response =
                assertDoesNotThrow(() -> CampaignMapper.toResponse(legacyCampaign, CampaignMetrics.empty()));

        assertNull(response.endBrandName());
        assertNull(response.endBrandCategory());
    }

    @Test
    @DisplayName(
            "toResponse: end_brand_name/end_brand_category are OMITTED from the JSON (never a"
                    + " literal null) for a pre-V72 campaign")
    void toResponse_legacyCampaign_omitsEndBrandFieldsFromJson() throws Exception {
        Campaign legacyCampaign = legacyCampaign();
        CampaignResponse response = CampaignMapper.toResponse(legacyCampaign, CampaignMetrics.empty());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertFalse(json.contains("endBrandName"), "expected endBrandName to be omitted, got: " + json);
        assertFalse(json.contains("endBrandCategory"), "expected endBrandCategory to be omitted, got: " + json);
    }

    /**
     * Deliberately does NOT call {@code .endBrandName(...)}/{@code .endBrandCategory(...)} on the
     * builder — same shape a row created before V72 has after that migration's additive, nullable
     * ALTER TABLE (no backfill), and everything else at whatever a minimal, otherwise-valid
     * campaign needs.
     */
    private static Campaign legacyCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Legacy Pre-V72 Campaign")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("10000"))
                .budgetMax(new BigDecimal("50000"))
                .currency("INR")
                .createdBy("01HBRANDUSERLEGACY001")
                .build();
    }
}
