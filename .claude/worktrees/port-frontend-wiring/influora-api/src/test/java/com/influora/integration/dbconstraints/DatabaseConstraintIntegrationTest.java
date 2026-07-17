package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influora.common.Ulids;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AudienceDemographics;
import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.AudienceDemographicsRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.influora.testsupport.AbstractIntegrationTest;

/**
 * REAL {@code @SpringBootTest} + Testcontainers-MySQL proof-of-concept -- Wave E task E3
 * (wiki/tech/REMAINING_WORK_PLAN.md).
 *
 * <p>Boots the actual Spring context, runs every {@code V*} Flyway migration for real against a
 * containerized MySQL 8.0.40, then exercises two constraints this session's MANUAL live-schema
 * checks previously verified by hand (throwaway schema + standalone FlywayRunner, no automated
 * test, no CI enforcement):
 *
 * <ul>
 *   <li>{@link #doubleCreditOnSameRedemptionIsRejectedByUniqueConstraint()} -- re-verifies, as a
 *       real automated test, the {@code UNIQUE KEY uq_affiliate_earning_redemption (redemption_id)}
 *       structural double-credit guard (V27) that Wave D task D4's QA/security review
 *       (wiki/errors/wave-d-task-d4-affiliate-earnings-qa-review.md:117-134,
 *       wiki/errors/wave-d-task-d4-affiliate-earnings-security-review.md:69-74) confirmed only via
 *       manual review of the migration SQL and Mockito-mocked concurrency reasoning -- never against
 *       a real MySQL engine actually enforcing the constraint.
 *   <li>{@link #deletingReferencedCreatorProfileIsRestrictedNotCascaded()} -- re-verifies, as a real
 *       automated test, the FK {@code ON DELETE} behavior that
 *       wiki/processes/schema-changes.md:291-295 (V25/B4) confirmed via a one-off manual probe
 *       against live local MySQL ("FK is RESTRICT not CASCADE (1451 raised,
 *       REFERENTIAL_CONSTRAINTS.DELETE_RULE=NO ACTION)") -- that probe was never turned into a
 *       regression test, so nothing stops a future migration from silently changing this to CASCADE.
 * </ul>
 *
 * <p>A third smoke test ({@link #contextLoadsAndFlywayMigrationsRunAgainstRealMysql()}) proves the
 * infra itself is wired correctly: real Spring context + real Flyway history table on a real engine,
 * independent of the two constraint-specific tests above.
 */
@Transactional
class DatabaseConstraintIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private AffiliateEarningRepository affiliateEarningRepository;
    @Autowired private AudienceDemographicsRepository audienceDemographicsRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "smoke test: Spring context boots against real containerized MySQL and every V* Flyway migration is applied")
    void contextLoadsAndFlywayMigrationsRunAgainstRealMysql() {
        Integer appliedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(appliedCount).isNotNull();
        // V1..V30 at time of writing (see influora-api/src/main/resources/db/migration) -- assert a
        // floor, not an exact count, so this doesn't need editing every time a new V* file lands.
        assertThat(appliedCount).isGreaterThanOrEqualTo(30);

        Integer affiliateEarningsTableExists =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'affiliate_earnings'",
                        Integer.class);
        assertThat(affiliateEarningsTableExists).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "affiliate_earnings.UNIQUE(redemption_id) (V27): a second earning for the same redemption is rejected by the DB, not just application logic")
    void doubleCreditOnSameRedemptionIsRejectedByUniqueConstraint() {
        Fixture fx = seedCampaignCreatorRedemption();

        AffiliateEarning first =
                AffiliateEarning.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(fx.workspaceId)
                        .campaignId(fx.campaignId)
                        .creatorId(fx.creatorProfileId)
                        .redemptionId(fx.redemptionId)
                        .commissionAmount(new BigDecimal("150.00"))
                        .currency("INR")
                        .idempotencyKey("earning:" + fx.redemptionId)
                        .build();
        affiliateEarningRepository.saveAndFlush(first);

        // Second earning for the SAME redemption_id, deliberately with a DIFFERENT idempotency_key
        // and a different id -- isolates that it's specifically UNIQUE(redemption_id) doing the
        // rejecting, not the also-unique idempotency_key column.
        AffiliateEarning duplicate =
                AffiliateEarning.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(fx.workspaceId)
                        .campaignId(fx.campaignId)
                        .creatorId(fx.creatorProfileId)
                        .redemptionId(fx.redemptionId)
                        .commissionAmount(new BigDecimal("150.00"))
                        .currency("INR")
                        .idempotencyKey("earning-retry:" + fx.redemptionId)
                        .build();

        assertThatThrownBy(() -> affiliateEarningRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        entityManager.clear();
        long countForRedemption =
                affiliateEarningRepository.findByRedemptionId(fx.redemptionId).stream().count();
        assertThat(countForRedemption).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "audience_demographics FK -> creator_profiles (V25): deleting a referenced creator is RESTRICTed, not silently cascaded")
    void deletingReferencedCreatorProfileIsRestrictedNotCascaded() {
        String userId = Ulids.newUlid();
        String creatorProfileId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'CREATOR', 'ACTIVE')", userId);
        jdbcTemplate.update(
                "INSERT INTO creator_profiles (id, user_id, display_name, currency, is_verified, is_discoverable, total_followers, created_at, updated_at)"
                        + " VALUES (?, ?, 'Test Creator', 'INR', false, true, 0, NOW(6), NOW(6))",
                creatorProfileId,
                userId);

        AudienceDemographics snapshot =
                AudienceDemographics.builder()
                        .id(Ulids.newUlid())
                        .creatorProfileId(creatorProfileId)
                        .time(Instant.now())
                        .build();
        audienceDemographicsRepository.saveAndFlush(snapshot);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "DELETE FROM creator_profiles WHERE id = ?", creatorProfileId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The row must still exist -- RESTRICT means the delete never happened, not that it happened
        // and something separately re-created it.
        Integer stillThere =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM creator_profiles WHERE id = ?", Integer.class, creatorProfileId);
        assertThat(stillThere).isEqualTo(1);
    }

    /** Builds the minimal valid FK chain (users -> workspaces -> campaigns -> creator_profiles -> coupon_codes -> coupon_redemptions) a real affiliate_earnings row requires. */
    private Fixture seedCampaignCreatorRedemption() {
        String userId = Ulids.newUlid();
        String workspaceId = Ulids.newUlid();
        String creatorUserId = Ulids.newUlid();
        String creatorProfileId = Ulids.newUlid();
        String campaignId = Ulids.newUlid();
        String couponId = Ulids.newUlid();
        String redemptionId = Ulids.newUlid();

        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'BRAND', 'ACTIVE')", userId);
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Test Brand', ?)",
                workspaceId,
                "test-brand-" + workspaceId.toLowerCase());
        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'CREATOR', 'ACTIVE')", creatorUserId);
        jdbcTemplate.update(
                "INSERT INTO creator_profiles (id, user_id, display_name, currency, is_verified, is_discoverable, total_followers, created_at, updated_at)"
                        + " VALUES (?, ?, 'Test Creator', 'INR', false, true, 0, NOW(6), NOW(6))",
                creatorProfileId,
                creatorUserId);

        // Insert campaign via JDBC (same connection as coupon insert) so fk_coupon_campaign sees the row.
        // Hibernate persist+no-flush left campaigns invisible to JdbcTemplate and failed the FK.
        jdbcTemplate.update(
                "INSERT INTO campaigns (id, workspace_id, title, status, currency, created_by)"
                        + " VALUES (?, ?, 'Test Campaign', 'ACTIVE', 'INR', ?)",
                campaignId,
                workspaceId,
                userId);

        jdbcTemplate.update(
                "INSERT INTO coupon_codes (id, workspace_id, campaign_id, creator_id, code, discount_type, discount_value, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'percentage', 10.00, NOW(6))",
                couponId,
                workspaceId,
                campaignId,
                creatorProfileId,
                "TESTCODE" + couponId.substring(0, 6));

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id(redemptionId)
                        .couponId(couponId)
                        .orderId("order-" + redemptionId)
                        .orderAmount(new BigDecimal("1000.00"))
                        .discountApplied(new BigDecimal("100.00"))
                        .idempotencyKey("redemption:" + redemptionId)
                        .build();
        entityManager.persist(redemption);
        entityManager.flush();

        return new Fixture(workspaceId, campaignId, creatorProfileId, redemptionId);
    }

    private record Fixture(
            String workspaceId, String campaignId, String creatorProfileId, String redemptionId) {}
}
