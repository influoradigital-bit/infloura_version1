package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.CampaignStatus;
import com.influora.repository.CampaignRepository;
import com.influora.testsupport.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-0848 T3-DB (wiki/tech/BUILD-PLAN-F0848-MEMORY-0917.md 1.5): the mock tests in {@link
 * CampaignActivationGuardTest} prove CALL counts. A PAUSED -&gt; ACTIVE resume calls {@code
 * chargeOnPublish} a second time, so "the brand pays the platform fee once" rests on the real
 * ledger's unique idempotency key {@code brand-fee-publish:<campaignId>}. This drives the REAL
 * {@link CampaignActivationGuard} + {@code BrandCampaignFeeService} + {@code WalletLedgerService}
 * against Testcontainers MySQL and counts the PLATFORM_FEE rows.
 *
 * <p>Requires Docker. {@code DockerAvailableCondition} SKIPS this class without it, and a skip is
 * NOT a pass: report T3-DB as NOT PROVEN unless this ran (CI or a machine with Docker).
 *
 * <p>{@code spring.cache.type=none}: same reason as {@code EscrowReleaseGateIntegrationTest} (the
 * fee path creates a commission invoice that reaches a {@code @Cacheable} HSN/SAC lookup).
 */
@Transactional
@TestPropertySource(properties = {"spring.cache.type=none"})
class CampaignActivationLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CampaignActivationGuard guard;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName(
            "T3-DB activate DRAFT->ACTIVE, pause, resume PAUSED->ACTIVE -> exactly ONE PLATFORM_FEE"
                    + " debit on the ledger for the campaign")
    void resumeDoesNotPostASecondPlatformFee() {
        String workspaceId = Ulids.newUlid();
        String campaignId = seed(workspaceId);

        Campaign campaign = campaignRepository.findById(campaignId).orElseThrow();
        guard.activate(campaign, workspaceId);
        campaignRepository.saveAndFlush(campaign);
        entityManager.flush();

        assertThat(platformFeeDebits(campaignId))
                .as("first activation must actually post the fee, or the resume check below is vacuous")
                .isEqualTo(1);

        campaign.setStatus(CampaignStatus.PAUSED);
        campaignRepository.saveAndFlush(campaign);

        guard.activate(campaign, workspaceId);
        campaignRepository.saveAndFlush(campaign);
        entityManager.flush();

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(platformFeeDebits(campaignId)).as("PLATFORM_FEE debits after resume").isEqualTo(1);
    }

    private int platformFeeDebits(String campaignId) {
        Integer n =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM wallet_transactions WHERE type = 'PLATFORM_FEE'"
                                + " AND direction = 'DEBIT' AND reference_type = 'CAMPAIGN' AND reference_id = ?",
                        Integer.class,
                        campaignId);
        return n == null ? 0 : n;
    }

    private String seed(String workspaceId) {
        String brandUserId = Ulids.newUlid();
        String campaignId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'BRAND', 'ACTIVE')", brandUserId);
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Ledger Brand', ?)",
                workspaceId,
                "ledger-brand-" + workspaceId.toLowerCase());
        jdbcTemplate.update(
                "INSERT INTO campaigns (id, workspace_id, title, status, budget_max, currency, created_by)"
                        + " VALUES (?, ?, 'Ledger Campaign', 'DRAFT', 10000.00, 'INR', ?)",
                campaignId,
                workspaceId,
                brandUserId);
        jdbcTemplate.update(
                "INSERT INTO wallets (id, owner_id, owner_type, balance, currency)"
                        + " VALUES (?, ?, 'WORKSPACE', 100000.00, 'INR')",
                Ulids.newUlid(),
                workspaceId);
        jdbcTemplate.update(
                "INSERT INTO escrow_holds (id, workspace_id, campaign_id, amount, currency, status, idempotency_key)"
                        + " VALUES (?, ?, ?, 10000.00, 'INR', 'FUNDED', ?)",
                Ulids.newUlid(),
                workspaceId,
                campaignId,
                "fund-idem:" + campaignId);
        return campaignId;
    }
}
