package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.domain.enums.CampaignStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0872 (Priya ruling c, option a) — entity-level half of "only one path to ACTIVE." {@code
 * CampaignActivationPathTest} (bytecode scan, {@code influora-api/src/test/java/com/influora/
 * architecture/}) is the OTHER half, and it disclaims exactly the two blind spots this test
 * reproduces directly against the entity:
 *
 * <ul>
 *   <li><b>B5</b> — Meera's falsification: {@code ConfirmLaunchExecutor} setting ACTIVE through a
 *       VARIABLE (not a literal constant adjacent to the {@code setStatus} call), bypassing {@link
 *       CampaignActivationGuard} entirely. The bytecode scan's own javadoc says it "does NOT see
 *       ACTIVE arriving through a variable." {@link #b5ActiveArrivingThroughAVariableIsRejected()}
 *       proves the ENTITY itself refuses this regardless of how the value arrived — a runtime check
 *       on the actual enum value, not a bytecode-shape heuristic.
 *   <li><b>B6</b> — {@code FestivalSponsorProvisioningService} minting a brand-new campaign already
 *       ACTIVE via {@code Campaign.builder().status(CampaignStatus.valueOf("ACTIVE")).build()}. A
 *       Builder-level entity throw was tried for this and reverted (see {@code Campaign.Builder
 *       #build}'s javadoc) — it broke real {@code @DataJpaTest} fixtures ({@code
 *       BrandDeliverableServiceApprovalRollbackIsolationTest}, {@code
 *       CreatorCampaignServiceApplyHistoryFkRaceTest}) and 15+ Mockito-fixture tests across this
 *       module that legitimately construct an already-ACTIVE {@code Campaign} as a POJO/DB row to
 *       test UNRELATED behavior. {@link #b6DynamicStatusStillBuildsButIsCaughtByTheProductionScan()}
 *       documents that choice explicitly: this Builder call still succeeds today (by design), and
 *       B6 is instead closed at the PRODUCTION-code layer by {@code CampaignActivationPathTest}'s T6
 *       rule, which scans only {@code target/classes} (main sources) and therefore never sees these
 *       test fixtures at all. Falsifying B6 for real (mutating {@code
 *       FestivalSponsorProvisioningService} itself to use a dynamic status and showing T6 go red) is
 *       done as a one-off recompile-and-revert exercise, not as permanent test code — see the F-0848
 *       fix-round falsification notes.
 * </ul>
 */
class CampaignActivationInvariantTest {

    @Test
    @DisplayName(
            "B5: Campaign.setStatus() refuses ACTIVE even when it arrives through a variable/method"
                    + " return, not a literal — the exact shape that evaded the bytecode scan")
    void b5ActiveArrivingThroughAVariableIsRejected() {
        Campaign campaign = draftCampaign();

        // Simulates ConfirmLaunchExecutor's B5 mutation: no literal `CampaignStatus.ACTIVE` token is
        // adjacent to the setStatus call anywhere in this method — the value is read back out of a
        // collection, exactly the "arrives through a variable" shape the bytecode scan (T5) cannot
        // see. The entity-level check in Campaign.setStatus() does not care: it inspects the actual
        // runtime enum value, not the bytecode shape that produced it.
        CampaignStatus statusFromElsewhere = statusResolvedDynamically();

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> campaign.setStatus(statusFromElsewhere));
        assertEquals(true, ex.getMessage().contains("CampaignActivationGuard"));
        assertEquals(CampaignStatus.DRAFT, campaign.getStatus()); // unchanged
    }

    @Test
    @DisplayName("B5 (activation edge): a PAUSED campaign resolved to ACTIVE via a ternary is also rejected")
    void b5TernaryResolvedActiveIsAlsoRejected() {
        Campaign campaign =
                Campaign.builder()
                        .id("01HCAMPAIGNB5TERNARY1")
                        .workspaceId("01HWORKSPACEB5000000A")
                        .title("Ternary mutation")
                        .status(CampaignStatus.PAUSED)
                        .budgetMax(BigDecimal.valueOf(10_000))
                        .build();
        boolean resume = true;
        CampaignStatus target = resume ? CampaignStatus.ACTIVE : CampaignStatus.PAUSED;

        assertThrows(IllegalStateException.class, () -> campaign.setStatus(target));
    }

    @Test
    @DisplayName(
            "activateForPublish() is the one legal way to reach ACTIVE, and setStatus() can never"
                    + " produce it — proves the two methods are not equivalent")
    void activateForPublishIsTheOnlyLegalActivationPath() {
        Campaign campaign = draftCampaign();

        campaign.activateForPublish();

        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
        // Once ACTIVE, even a legitimate-looking re-send of ACTIVE through setStatus is refused —
        // this entity never accepts CampaignStatus.ACTIVE through setStatus, full stop.
        assertThrows(IllegalStateException.class, () -> campaign.setStatus(CampaignStatus.ACTIVE));
    }

    @Test
    @DisplayName(
            "B6 note: Campaign.Builder.status(CampaignStatus.valueOf(\"ACTIVE\")) still BUILDS —"
                    + " deliberately not blocked at the entity, see class javadoc for why; B6 is closed"
                    + " by CampaignActivationPathTest's T6 production-bytecode scan instead")
    void b6DynamicStatusStillBuildsButIsCaughtByTheProductionScan() {
        // This intentionally documents current (accepted) entity behavior rather than asserting a
        // throw — see this class's javadoc for the fixture-breakage reason a Builder-level guard was
        // reverted. It is what makes T6 necessary at all: if the entity already refused this, T6
        // would be redundant.
        CampaignStatus dynamic = CampaignStatus.valueOf("ACTIVE");
        Campaign campaign =
                Campaign.builder()
                        .id("01HCAMPAIGNB6VALUEOF1")
                        .workspaceId("01HWORKSPACEB6000000A")
                        .title("B6 fixture-shape mutation")
                        .status(dynamic)
                        .build();

        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
    }

    @Test
    @DisplayName("EV-005: applyPatch() refuses ACTIVE too, status unchanged -- a PATCH cannot bypass the guard")
    void applyPatchRefusesActive() {
        Campaign campaign = draftCampaign();

        assertThrows(
                IllegalStateException.class,
                () ->
                        campaign.applyPatch(
                                null, null, CampaignStatus.ACTIVE, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(CampaignStatus.DRAFT, campaign.getStatus());
    }

    private static CampaignStatus statusResolvedDynamically() {
        List<CampaignStatus> candidates = List.of(CampaignStatus.DRAFT, CampaignStatus.ACTIVE);
        return candidates.get(1);
    }

    private static Campaign draftCampaign() {
        return Campaign.builder()
                .id("01HCAMPAIGNB5VARIABLE1")
                .workspaceId("01HWORKSPACEB5000000A")
                .title("B5 fixture-shape mutation")
                .status(CampaignStatus.DRAFT)
                .budgetMax(BigDecimal.valueOf(10_000))
                .build();
    }
}
