package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.common.Ulids;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.testsupport.AbstractIntegrationTest;
import com.influora.web.dto.deal.DealDtos.CreateDealRequest;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The application-history lock stall, on real MySQL 8 through the real services.
 *
 * <p><b>The defect.</b> {@code ApplicationHistoryService#record} used to be {@code
 * @Transactional(REQUIRES_NEW)}: it inserted into {@code application_history_events} on a second
 * connection while the caller's transaction still held the {@code collaborations} row under {@code
 * X,REC_NOT_GAP}. The FK check ({@code fk_app_history_application}) needs {@code S,REC_NOT_GAP} on
 * that row, so the insert waited on its own caller until {@code innodb_lock_wait_timeout}, then
 * failed. On a real boot of 2d143c6 (MySQL 8.0.40, default 50 s timeout) the three calls below took
 * 50.44 s, 50.48 s and 50.84 s, and wrote 0 history rows.
 *
 * <p><b>What this asserts, per call:</b> it returns well under the lock timeout, AND the history
 * row it owes exists afterwards. The pool's sessions run with {@code innodb_lock_wait_timeout = 5}
 * (via Hikari's {@code connection-init-sql}; a SESSION variable, so no SUPER privilege is needed),
 * so a regression fails in about 5 s per call instead of 50 s, and fails both assertions.
 *
 * <p>No {@code @Transactional} on this class, on purpose. Each service call must open and commit
 * its own transaction, exactly as it does behind an HTTP request. A test-managed transaction around
 * the calls would make them participate in one outer transaction, defer every history write past
 * the end of the test, and prove nothing.
 *
 * <p><b>Runs only where Docker is available</b> ({@link AbstractIntegrationTest}); it skips on a
 * machine without Docker and is meant to run in CI. Fixtures are seeded the same way {@code
 * EscrowReleaseGateIntegrationTest} seeds them: plain JDBC for the user, workspace, membership,
 * campaign and creator-profile rows, then the real services for everything under test.
 */
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 5",
            // Same reason as EscrowReleaseGateIntegrationTest: @Cacheable methods on these paths
            // would otherwise open a Redis socket, which is not what this test is about.
            "spring.cache.type=none"
        })
class ApplicationHistoryLockStallIntegrationTest extends AbstractIntegrationTest {

    /** Below the 5 s session lock timeout, so a single stalled FK wait fails this bound. */
    private static final Duration MAX_CALL = Duration.ofSeconds(4);

    @Autowired private DealService dealService;
    @Autowired private ContractService contractService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private record Fixture(
            String brandUserId,
            String creatorUserId,
            String workspaceId,
            String campaignId,
            String creatorProfileId) {}

    private record Timed<T>(T value, Duration elapsed) {}

    private static <T> Timed<T> time(Supplier<T> call) {
        long start = System.nanoTime();
        T value = call.get();
        return new Timed<>(value, Duration.ofNanos(System.nanoTime() - start));
    }

    private long historyRows(String collaborationId, ApplicationHistoryEventType type) {
        Long n =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM application_history_events"
                                + " WHERE application_id = ? AND event_type = ?",
                        Long.class,
                        collaborationId,
                        type.name());
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName(
            "accept, contract generation and the final signature each return well under the lock"
                    + " timeout AND leave their application-history rows behind")
    void acceptGenerateAndSignDoNotStallAndDoRecordHistory() {
        Integer sessionTimeout =
                jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
        assertThat(sessionTimeout)
                .as("the pool must run with the short lock timeout, or a regression takes 50 s")
                .isEqualTo(5);

        Fixture fx = seedFixture();
        AuthPrincipal brand =
                new AuthPrincipal(fx.brandUserId(), "brand-" + fx.brandUserId() + "@test.influora", UserType.BRAND, fx.workspaceId());
        AuthPrincipal creator =
                new AuthPrincipal(fx.creatorUserId(), "creator-" + fx.creatorUserId() + "@test.influora", UserType.CREATOR, null);

        String dealId =
                dealService
                        .createProposal(
                                brand,
                                new CreateDealRequest(
                                        fx.campaignId(),
                                        fx.creatorProfileId(),
                                        new BigDecimal("10000.00"),
                                        List.of(new DeliverableSlot("INSTAGRAM_REEL", 1)),
                                        null,
                                        null,
                                        "Lock-stall integration test proposal",
                                        null))
                        .id();

        // 1. POST /deals/{id}/accept (creator). The X lock comes from the flushed status UPDATE.
        Timed<?> accept = time(() -> dealService.accept(creator, dealId, null));
        assertThat(accept.elapsed()).as("accept duration").isLessThan(MAX_CALL);
        assertThat(historyRows(dealId, ApplicationHistoryEventType.APPLICATION_ACCEPTED))
                .as("APPLICATION_ACCEPTED row after accept")
                .isEqualTo(1);

        // 2. POST /contracts (brand). The X lock comes from findByIdForUpdate on the collaboration.
        Timed<ContractResponse> generate =
                time(
                        () ->
                                contractService.generate(
                                        brand,
                                        fx.workspaceId(),
                                        new ContractGenerateRequest(
                                                dealId,
                                                List.of(
                                                        new MilestoneWriteRequest(
                                                                1, "Full payment", new BigDecimal("10000.00"), null)))));
        assertThat(generate.elapsed()).as("contract generation duration").isLessThan(MAX_CALL);
        assertThat(historyRows(dealId, ApplicationHistoryEventType.CONTRACT_GENERATED))
                .as("CONTRACT_GENERATED row after generation")
                .isEqualTo(1);
        assertThat(historyRows(dealId, ApplicationHistoryEventType.DEAL_ROOM_ACTIVATED))
                .as("DEAL_ROOM_ACTIVATED row after generation")
                .isEqualTo(1);
        String contractId = generate.value().id();

        // 3a. Brand signs first (PENDING_SIGNATURES). No history event on this step.
        contractService.recordSignature(brand, fx.workspaceId(), contractId, "BRAND", "Brand Owner");

        // 3b. Creator signs second: the contract goes ACTIVE and CONTRACT_SIGNED is recorded while
        // the collaboration row is held FOR UPDATE.
        Timed<?> sign =
                time(() -> contractService.recordSignatureForCreator(creator, contractId, "Creator Name"));
        assertThat(sign.elapsed()).as("final signature duration").isLessThan(MAX_CALL);
        assertThat(historyRows(dealId, ApplicationHistoryEventType.CONTRACT_SIGNED))
                .as("CONTRACT_SIGNED row after the final signature")
                .isEqualTo(1);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM contracts WHERE id = ?", String.class, contractId))
                .as("the business write landed too")
                .isEqualTo("ACTIVE");
    }

    /** users -> workspace (+OWNER member) -> campaign -> discoverable creator profile. Committed. */
    private Fixture seedFixture() {
        String brandUserId = Ulids.newUlid();
        String creatorUserId = Ulids.newUlid();
        String workspaceId = Ulids.newUlid();
        String campaignId = Ulids.newUlid();
        String creatorProfileId = Ulids.newUlid();

        jdbcTemplate.update(
                "INSERT INTO users (id, email, user_type, status) VALUES (?, ?, 'BRAND', 'ACTIVE')",
                brandUserId,
                "brand-" + brandUserId.toLowerCase() + "@test.influora");
        jdbcTemplate.update(
                "INSERT INTO users (id, email, user_type, status) VALUES (?, ?, 'CREATOR', 'ACTIVE')",
                creatorUserId,
                "creator-" + creatorUserId.toLowerCase() + "@test.influora");
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Lock Stall Brand', ?)",
                workspaceId,
                "lock-stall-" + workspaceId.toLowerCase());
        jdbcTemplate.update(
                "INSERT INTO workspace_members (id, workspace_id, user_id, role, is_active)"
                        + " VALUES (?, ?, ?, 'OWNER', TRUE)",
                Ulids.newUlid(),
                workspaceId,
                brandUserId);
        jdbcTemplate.update(
                "INSERT INTO campaigns (id, workspace_id, title, status, currency, created_by)"
                        + " VALUES (?, ?, 'Lock Stall Campaign', 'DRAFT', 'INR', ?)",
                campaignId,
                workspaceId,
                brandUserId);
        jdbcTemplate.update(
                "INSERT INTO creator_profiles (id, user_id, display_name, currency, is_verified,"
                        + " is_discoverable, total_followers, created_at, updated_at)"
                        + " VALUES (?, ?, 'Lock Stall Creator', 'INR', false, true, 0, NOW(6), NOW(6))",
                creatorProfileId,
                creatorUserId);
        return new Fixture(brandUserId, creatorUserId, workspaceId, campaignId, creatorProfileId);
    }
}
