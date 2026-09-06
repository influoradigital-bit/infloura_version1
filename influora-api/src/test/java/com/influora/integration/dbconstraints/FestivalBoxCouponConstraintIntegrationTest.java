package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influora.common.Ulids;
import com.influora.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * REAL {@code @SpringBootTest} + Testcontainers-MySQL proof for T-FESTIVALBOX-0905 phase 4 --
 * {@code coupon_codes.creator_id} nullable + the generated-column brand-level uniqueness
 * constraint added by V20260905160000. Same infrastructure/rationale as {@link
 * DatabaseConstraintIntegrationTest} (see that class's javadoc): this is the ONLY way to actually
 * prove a MySQL 8 generated-STORED-column + composite-UNIQUE-with-NULL interaction works, since
 * neither a plain Mockito unit test nor H2 can reproduce this engine-specific behavior faithfully
 * (see the V20260905160000 migration header's "VERIFICATION NOTE").
 *
 * <p><b>[READ BEFORE TRUSTING A GREEN OR SKIPPED RUN LOCALLY]</b> Exactly like {@link
 * DatabaseConstraintIntegrationTest}, this class is blocked in the current sandbox by the
 * documented Docker-daemon-unreachable limitation ({@code DockerAvailableCondition} skips it
 * rather than erroring — see {@code AbstractIntegrationTest} javadoc). This task's brief says so
 * explicitly: "Docker's daemon is DOWN. No real MySQL. You cannot prove the migration applies." —
 * this class has been written and compiled, NOT run, in this environment. It will actually
 * exercise real MySQL 8.0.40 (and thereby prove or disprove the migration) the next time it runs
 * somewhere with a reachable Docker daemon (e.g. CI).
 */
@Transactional
class FestivalBoxCouponConstraintIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "coupon_codes UNIQUE(campaign_id, brand_level_marker) (V20260905160000): a second"
                    + " brand-level (creator_id IS NULL) coupon for the same campaign is rejected by"
                    + " the DB, not just application logic")
    void secondBrandLevelCouponForSameCampaignIsRejectedByUniqueConstraint() {
        Campaign campaign = seedCampaign();

        insertBrandLevelCoupon(campaign.workspaceId(), campaign.campaignId(), "FIRST_EXCLUSIVE");

        assertThatThrownBy(
                        () ->
                                insertBrandLevelCoupon(
                                        campaign.workspaceId(), campaign.campaignId(), "SECOND_EXCLUSIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer brandLevelRowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_codes WHERE campaign_id = ? AND creator_id IS NULL",
                        Integer.class,
                        campaign.campaignId());
        assertThat(brandLevelRowCount).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "coupon_codes: a brand-level coupon and a per-creator coupon coexist on the same"
                    + " campaign -- the new UNIQUE(campaign_id, brand_level_marker) does not interfere"
                    + " with the existing UNIQUE(campaign_id, creator_id)")
    void brandLevelAndPerCreatorCouponsCoexistOnSameCampaign() {
        Campaign campaign = seedCampaign();
        String creatorProfileId = seedCreatorProfile();

        insertBrandLevelCoupon(campaign.workspaceId(), campaign.campaignId(), "PAGE_EXCLUSIVE");
        jdbcTemplate.update(
                "INSERT INTO coupon_codes (id, workspace_id, campaign_id, creator_id, code, discount_type,"
                        + " discount_value, created_at) VALUES (?, ?, ?, ?, 'CREATOR_CODE', 'percentage', 10.00,"
                        + " NOW(6))",
                Ulids.newUlid(),
                campaign.workspaceId(),
                campaign.campaignId(),
                creatorProfileId);

        Integer totalRowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_codes WHERE campaign_id = ?",
                        Integer.class,
                        campaign.campaignId());
        assertThat(totalRowCount).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "coupon_codes fk_coupon_creator FK: a NULL creator_id (brand-level) inserts successfully"
                    + " with no matching creator_profiles row -- MySQL's MATCH SIMPLE FK semantics skip"
                    + " the check for NULL, exactly as the V20260905160000 migration header claims")
    void brandLevelCouponWithNullCreatorIdSkipsForeignKeyCheck() {
        Campaign campaign = seedCampaign();

        // No creator_profiles row is ever inserted in this test -- if the FK were still evaluated
        // for a NULL creator_id, this insert would throw. It must not.
        insertBrandLevelCoupon(campaign.workspaceId(), campaign.campaignId(), "NO_CREATOR_NEEDED");

        Integer rowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_codes WHERE campaign_id = ? AND creator_id IS NULL",
                        Integer.class,
                        campaign.campaignId());
        assertThat(rowCount).isEqualTo(1);
    }

    private void insertBrandLevelCoupon(String workspaceId, String campaignId, String code) {
        jdbcTemplate.update(
                "INSERT INTO coupon_codes (id, workspace_id, campaign_id, creator_id, code, discount_type,"
                        + " discount_value, created_at) VALUES (?, ?, ?, NULL, ?, 'percentage', 15.00, NOW(6))",
                Ulids.newUlid(),
                workspaceId,
                campaignId,
                code);
    }

    /** Minimal valid FK chain (users -> workspaces -> campaigns) coupon_codes requires. */
    private Campaign seedCampaign() {
        String userId = Ulids.newUlid();
        String workspaceId = Ulids.newUlid();
        String campaignId = Ulids.newUlid();

        jdbcTemplate.update("INSERT INTO users (id, user_type, status) VALUES (?, 'BRAND', 'ACTIVE')", userId);
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Test Brand', ?)",
                workspaceId,
                "test-brand-" + workspaceId.toLowerCase());
        jdbcTemplate.update(
                "INSERT INTO campaigns (id, workspace_id, title, status, currency, created_by)"
                        + " VALUES (?, ?, 'Test Campaign', 'ACTIVE', 'INR', ?)",
                campaignId,
                workspaceId,
                userId);

        return new Campaign(workspaceId, campaignId);
    }

    private String seedCreatorProfile() {
        String creatorUserId = Ulids.newUlid();
        String creatorProfileId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'CREATOR', 'ACTIVE')", creatorUserId);
        jdbcTemplate.update(
                "INSERT INTO creator_profiles (id, user_id, display_name, currency, is_verified,"
                        + " is_discoverable, total_followers, created_at, updated_at) VALUES (?, ?, 'Test"
                        + " Creator', 'INR', false, true, 0, NOW(6), NOW(6))",
                creatorProfileId,
                creatorUserId);
        return creatorProfileId;
    }

    private record Campaign(String workspaceId, String campaignId) {}
}
