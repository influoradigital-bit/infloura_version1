package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.ReleaseCondition;
import com.influora.domain.enums.UserType;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import com.influora.testsupport.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Kabir HIGH-1 / Priya Option B ruling, CR-51] Proves the release-condition gate
 * ({@code EscrowService#assertReleaseConditionSatisfied}) actually fires against a REAL {@code
 * deliverableRepository} on a real database — no Mockito mock of that repository anywhere in this
 * class. That mock is exactly what let HIGH-1 ship undetected: {@code EscrowServiceTest} and
 * {@code EscrowServiceReleaseTest} stub {@code deliverableRepository.findBy...(...)} directly, so
 * they could never have caught "the production code queries a key that materialization never
 * populates" — the mock cheerfully answers a query that, against a real schema, would always
 * return empty.
 *
 * <p>This class seeds the full FK chain (users -> workspace -> campaign -> creator profile ->
 * collaboration -> contract -> payment milestone -> escrow hold -> deliverables) and persists
 * {@link Deliverable} rows via the real {@link DeliverableRepository}, deliberately leaving {@code
 * milestone_id} NULL (exactly what {@code ContractService#materializeDeliverables} does — see its
 * javadoc and CR-51 step 1) and {@code collaboration_id} set. If the gate were still keyed on
 * {@code findByMilestoneId} (the HIGH-1 bug), every test below that expects a BLOCK (B, D, or F)
 * would instead silently fail open on account of a real database genuinely returning zero rows for
 * that query — i.e. this class would go green even with the bug present using mocks, but only
 * THESE assertions (real repository, real FK-linked rows) actually
 * distinguish the fixed behavior from the bug.
 *
 * <p>Extends {@link AbstractIntegrationTest} (Testcontainers MySQL) per this repo's only precedent
 * for real-DB JPA coverage ({@code DatabaseConstraintIntegrationTest}) — see that class's javadoc
 * and {@code BrandAiCreditRepositoryQueryTest}'s for why a plain {@code @DataJpaTest} isn't an
 * option here (no H2 dependency in this pom; real MySQL JSON/ENUM/FK behavior is the point). Per
 * the same precedent, this requires a working Docker daemon; {@code DockerAvailableCondition}
 * SKIPS (not fails) this class when Docker is unavailable, exactly as it already does for {@code
 * DatabaseConstraintIntegrationTest} in this sandbox.
 */
@Transactional
@TestPropertySource(
        properties = {
            "influora.escrow.release-gate.cutover-instant=2000-01-01T00:00:00Z",
            // C/D/E drive escrowService.release(...) to a SUCCESS outcome, which walks into
            // PlatformFeeService -> CommissionInvoiceService -> HsnSacCodeService#resolveCode, a
            // @Cacheable("hsnSacCodes") method. With no spring.cache.type set, Boot auto-detects
            // RedisCacheManager (RedisCacheConfig enables caching; a RedisConnectionFactory is on
            // the classpath), so the FIRST @Cacheable invocation opens a real loopback socket to
            // Redis -- which throws RedisConnectionFailureException in this sandbox (documented
            // Windows Netty loopback-socket bug, see AbstractIntegrationTest javadoc ~L47-60).
            // That failure is orthogonal to what this class proves (the release-condition gate
            // querying real, collaboration-keyed Deliverable rows); it is not a defect in the gate
            // or in deliverableRepository. Forcing spring.cache.type=none here swaps in Boot's
            // NoOpCacheManager for THIS TEST CLASS ONLY -- @Cacheable becomes a pass-through and
            // HsnSacCodeService#resolveCode falls straight to its non-cache path (a real
            // HsnSacCodeRepository query against the real Testcontainers MySQL, still no mocks),
            // so no code under test changes and no Redis socket is ever opened.
            "spring.cache.type=none"
        })
class EscrowReleaseGateIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EscrowService escrowService;
    @Autowired private CollaborationRepository collaborationRepository;
    @Autowired private ContractRepository contractRepository;
    @Autowired private PaymentMilestoneRepository milestoneRepository;
    @Autowired private EscrowHoldRepository escrowHoldRepository;
    @Autowired private DeliverableRepository deliverableRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final Instant CUTOVER = Instant.parse("2000-01-01T00:00:00Z");

    // --------------------------------------------------------------------------------------
    // Assert A — real link, no mocks: the gate's lookup key (collaborationId) actually finds the
    // rows materialization actually writes, and milestone_id is (and stays) NULL on them.
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "A: findByCollaborationIdOrderBySlotIndexAsc returns real deliverable rows with"
                    + " milestoneId==null — proves the gate's key, not the (unpopulated) milestone link")
    void findByCollaborationIdFindsRealDeliverables_milestoneIdStaysNull() {
        Fixture fx = seedFixture();
        seedDeliverables(fx, DeliverableStatus.DRAFT, DeliverableStatus.DRAFT);

        List<Deliverable> found =
                deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(fx.collaborationId());

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getSlotIndex()).isEqualTo(0);
        assertThat(found.get(1).getSlotIndex()).isEqualTo(1);
        for (Deliverable d : found) {
            assertThat(d.getMilestoneId()).isNull();
            assertThat(d.getCollaborationId()).isEqualTo(fx.collaborationId());
        }
    }

    // --------------------------------------------------------------------------------------
    // Assert B — BLOCKED: unsatisfied real deliverables must block release, and no money moves.
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "B: post-cutover release is BLOCKED (409 RELEASE_CONDITION_NOT_MET) when real"
                    + " collaboration-linked deliverables have not reached ON_POSTED — hold stays FUNDED")
    void releaseBlockedWhenRealDeliverablesUnsatisfied() {
        Fixture fx = seedFixture();
        PaymentMilestone milestone = seedFundedMilestone(fx, ReleaseCondition.ON_POSTED, null);
        seedDeliverables(fx, DeliverableStatus.DRAFT, DeliverableStatus.DRAFT);

        AuthPrincipal principal = brandPrincipal(fx);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> escrowService.release(principal, fx.workspaceId(), milestone.getId()));
        assertEquals("RELEASE_CONDITION_NOT_MET", ex.getCode());

        EscrowHold reloaded =
                escrowHoldRepository.findById(milestone.getEscrowHoldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EscrowStatus.FUNDED);
    }

    // --------------------------------------------------------------------------------------
    // Assert C — RELEASED: advancing every real deliverable to POSTED unblocks the release.
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "C: post-cutover release SUCCEEDS once every real collaboration-linked deliverable"
                    + " reaches POSTED — hold moves FUNDED -> RELEASED")
    void releaseSucceedsWhenRealDeliverablesAllPosted() {
        Fixture fx = seedFixture();
        PaymentMilestone milestone = seedFundedMilestone(fx, ReleaseCondition.ON_POSTED, null);
        List<Deliverable> deliverables =
                seedDeliverables(fx, DeliverableStatus.DRAFT, DeliverableStatus.DRAFT);
        for (Deliverable d : deliverables) {
            d.applyMarkPosted("https://instagram.test/p/" + d.getId());
            deliverableRepository.saveAndFlush(d);
        }

        AuthPrincipal principal = brandPrincipal(fx);

        assertDoesNotThrow(() -> escrowService.release(principal, fx.workspaceId(), milestone.getId()));

        EscrowHold reloaded =
                escrowHoldRepository.findById(milestone.getEscrowHoldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EscrowStatus.RELEASED);
    }

    // --------------------------------------------------------------------------------------
    // Assert D — CR-51 step 3 (DECIDED, Swapnil: forbid): post-cutover collaboration with ZERO
    // deliverables is BLOCKED, not fail-open. Supersedes the old "D" fail-open expectation —
    // Priya's step-3 ruling changed this branch from log.warn+return to a throw.
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "D: post-cutover release is BLOCKED (409 RELEASE_CONDITION_NOT_MET) when the"
                    + " collaboration has ZERO deliverables — CR-51 step 3, no money moves, hold stays"
                    + " FUNDED")
    void releaseBlockedWhenCollaborationHasZeroDeliverables() {
        Fixture fx = seedFixture();
        PaymentMilestone milestone = seedFundedMilestone(fx, ReleaseCondition.ON_POSTED, null);
        // Deliberately no seedDeliverables(...) call — zero rows for this collaboration.

        AuthPrincipal principal = brandPrincipal(fx);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> escrowService.release(principal, fx.workspaceId(), milestone.getId()));
        assertEquals("RELEASE_CONDITION_NOT_MET", ex.getCode());

        EscrowHold reloaded =
                escrowHoldRepository.findById(milestone.getEscrowHoldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EscrowStatus.FUNDED);
    }

    // --------------------------------------------------------------------------------------
    // Assert D2 — companion: same zero-deliverable seed, but with the gate DISABLED (blank
    // cutover) — proves CR-51 step 3 ships disabled by default. A class-level @TestPropertySource
    // can't vary per-test (and a second @SpringBootTest/Testcontainers context per test would be
    // needlessly expensive here), so this reaches into the EscrowService bean via
    // ReflectionTestUtils and nulls out the already-parsed `releaseGateCutoverInstant` field for
    // the duration of the call — exactly what a blank/unset
    // influora.escrow.release-gate.cutover-instant produces at boot (see the field's
    // @PostConstruct parse in EscrowService), and exactly what isPostCutover(...) checks. Restored
    // in a finally so later tests in this class keep the real (enabled) cutover.
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "D2: gate DISABLED (blank cutover) — same zero-deliverable seed instead releases"
                    + " (fail-open preserved) — proves CR-51 step 3 ships disabled by default")
    void releaseSucceedsWhenGateDisabledEvenWithZeroDeliverables() {
        Fixture fx = seedFixture();
        PaymentMilestone milestone = seedFundedMilestone(fx, ReleaseCondition.ON_POSTED, null);
        // Deliberately no seedDeliverables(...) call — zero rows for this collaboration.

        AuthPrincipal principal = brandPrincipal(fx);

        Object originalCutover = ReflectionTestUtils.getField(escrowService, "releaseGateCutoverInstant");
        ReflectionTestUtils.setField(escrowService, "releaseGateCutoverInstant", null);
        try {
            assertDoesNotThrow(
                    () -> escrowService.release(principal, fx.workspaceId(), milestone.getId()));
        } finally {
            ReflectionTestUtils.setField(escrowService, "releaseGateCutoverInstant", originalCutover);
        }

        EscrowHold reloaded =
                escrowHoldRepository.findById(milestone.getEscrowHoldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EscrowStatus.RELEASED);
    }

    // --------------------------------------------------------------------------------------
    // Assert F — tryReleaseOnApproval on the same zero-deliverable post-cutover milestone skips
    // gracefully (RELEASE_CONDITION_NOT_MET is whitelisted by isExpectedReleaseSkip) instead of
    // propagating — approval must not blow up the approve() transaction. Hold stays FUNDED.
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "F: tryReleaseOnApproval on a zero-deliverable post-cutover milestone returns false"
                    + " (graceful skip, not a thrown exception) — hold stays FUNDED")
    void tryReleaseOnApprovalReturnsFalseWhenCollaborationHasZeroDeliverables() {
        Fixture fx = seedFixture();
        PaymentMilestone milestone = seedFundedMilestone(fx, ReleaseCondition.ON_POSTED, null);
        // Deliberately no seedDeliverables(...) call — zero rows for this collaboration.

        boolean released =
                assertDoesNotThrow(
                                () -> escrowService.tryReleaseOnApproval(fx.workspaceId(), milestone.getId()))
                        .released();

        assertFalse(released);

        EscrowHold reloaded =
                escrowHoldRepository.findById(milestone.getEscrowHoldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EscrowStatus.FUNDED);
    }

    // --------------------------------------------------------------------------------------
    // Assert E — PRE-cutover milestone fails open regardless of deliverable state (legacy deals).
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "E: PRE-cutover milestone releases (fail-open) even with unsatisfied real deliverables —"
                    + " legacy deals in flight must not start throwing RELEASE_CONDITION_NOT_MET")
    void releaseSucceedsForPreCutoverMilestoneRegardlessOfDeliverableState() {
        Fixture fx = seedFixture();
        // createdAt forced strictly BEFORE the configured cutover (2000-01-01T00:00:00Z).
        PaymentMilestone milestone =
                seedFundedMilestone(
                        fx, ReleaseCondition.ON_POSTED, CUTOVER.minusSeconds(3600));
        seedDeliverables(fx, DeliverableStatus.DRAFT, DeliverableStatus.DRAFT);

        AuthPrincipal principal = brandPrincipal(fx);

        assertDoesNotThrow(() -> escrowService.release(principal, fx.workspaceId(), milestone.getId()));

        EscrowHold reloaded =
                escrowHoldRepository.findById(milestone.getEscrowHoldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EscrowStatus.RELEASED);
    }

    // ========================================================================================
    // Fixture plumbing
    // ========================================================================================

    private record Fixture(
            String workspaceId,
            String brandUserId,
            String creatorUserId,
            String creatorProfileId,
            String campaignId,
            String collaborationId,
            String contractId) {}

    private AuthPrincipal brandPrincipal(Fixture fx) {
        return new AuthPrincipal(fx.brandUserId(), "brand@test.influora", UserType.BRAND, fx.workspaceId());
    }

    /** Minimal valid FK chain: users -> workspace(+member) -> campaign -> creator_profile -> collaboration -> contract. */
    private Fixture seedFixture() {
        String brandUserId = Ulids.newUlid();
        String creatorUserId = Ulids.newUlid();
        String workspaceId = Ulids.newUlid();
        String campaignId = Ulids.newUlid();
        String creatorProfileId = Ulids.newUlid();
        String collaborationId = Ulids.newUlid();

        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'BRAND', 'ACTIVE')", brandUserId);
        jdbcTemplate.update(
                "INSERT INTO users (id, user_type, status) VALUES (?, 'CREATOR', 'ACTIVE')",
                creatorUserId);
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Test Brand', ?)",
                workspaceId,
                "test-brand-" + workspaceId.toLowerCase());
        jdbcTemplate.update(
                "INSERT INTO workspace_members (id, workspace_id, user_id, role, is_active)"
                        + " VALUES (?, ?, ?, 'OWNER', TRUE)",
                Ulids.newUlid(),
                workspaceId,
                brandUserId);
        jdbcTemplate.update(
                "INSERT INTO campaigns (id, workspace_id, title, status, currency, created_by)"
                        + " VALUES (?, ?, 'Test Campaign', 'ACTIVE', 'INR', ?)",
                campaignId,
                workspaceId,
                brandUserId);
        jdbcTemplate.update(
                "INSERT INTO creator_profiles (id, user_id, display_name, currency, is_verified,"
                        + " is_discoverable, total_followers, created_at, updated_at)"
                        + " VALUES (?, ?, 'Test Creator', 'INR', false, true, 0, NOW(6), NOW(6))",
                creatorProfileId,
                creatorUserId);

        Collaboration collaboration =
                Collaboration.invite(collaborationId, campaignId, creatorUserId, null, "INR");
        collaborationRepository.saveAndFlush(collaboration);

        Contract contract =
                Contract.builder()
                        .id(Ulids.newUlid())
                        .collaborationId(collaborationId)
                        .workspaceId(workspaceId)
                        .totalAmount(new BigDecimal("5000.00"))
                        .build();
        contractRepository.saveAndFlush(contract);

        return new Fixture(
                workspaceId,
                brandUserId,
                creatorUserId,
                creatorProfileId,
                campaignId,
                collaborationId,
                contract.getId());
    }

    /**
     * Persists a real, FUNDED {@link PaymentMilestone} + {@link EscrowHold} pair. {@code
     * createdAtOverride}, when non-null, is force-set on the milestone entity BEFORE its first
     * flush (the {@code created_at} column is {@code updatable = false}, so this only works
     * pre-INSERT) — used by the pre-cutover test (E) to simulate a legacy milestone without
     * waiting for real wall-clock time to pass the configured cutover.
     */
    private PaymentMilestone seedFundedMilestone(
            Fixture fx, ReleaseCondition condition, Instant createdAtOverride) {
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id(Ulids.newUlid())
                        .contractId(fx.contractId())
                        .collaborationId(fx.collaborationId())
                        .sequenceNo(1)
                        .amount(new BigDecimal("5000.00"))
                        .releaseCondition(condition)
                        .build();
        if (createdAtOverride != null) {
            ReflectionTestUtils.setField(milestone, "createdAt", createdAtOverride);
        }
        milestoneRepository.saveAndFlush(milestone);

        EscrowHold hold =
                EscrowHold.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(fx.workspaceId())
                        .collaborationId(fx.collaborationId())
                        .campaignId(fx.campaignId())
                        .milestoneId(milestone.getId())
                        .amount(milestone.getAmount())
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("fund-idem:" + milestone.getId())
                        .build();
        escrowHoldRepository.saveAndFlush(hold);

        milestone.markFunded(hold.getId());
        milestoneRepository.saveAndFlush(milestone);

        return milestone;
    }

    /**
     * Persists real {@link Deliverable} rows the exact way {@code
     * ContractService#materializeDeliverables} does: {@code collaborationId} set, {@code
     * milestoneId} left NULL, sequential {@code slotIndex}. This is the "persist via the real repo"
     * alternative CR-51's spec explicitly allows in place of driving the full proposal ->
     * ContractService#generate() path, which would additionally require a persisted DealMessage
     * proposal with structured {@code deliverables} metadata — orthogonal to what this class is
     * proving.
     */
    private List<Deliverable> seedDeliverables(Fixture fx, DeliverableStatus... statuses) {
        List<Deliverable> saved = new ArrayList<>();
        int slotIndex = 0;
        for (DeliverableStatus status : statuses) {
            Deliverable d =
                    Deliverable.builder()
                            .id(Ulids.newUlid())
                            .collaborationId(fx.collaborationId())
                            .creatorProfileId(fx.creatorProfileId())
                            .slotIndex(slotIndex++)
                            .type(DeliverableType.INSTAGRAM_REEL)
                            .title("Reel #" + slotIndex)
                            .status(status)
                            .build();
            saved.add(deliverableRepository.saveAndFlush(d));
        }
        return saved;
    }
}
