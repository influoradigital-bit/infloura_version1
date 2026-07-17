package com.influora.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.CreatorProfileRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 4 UTM/Coupon Tracking: unit tests for CouponCodeService -- code generation (uniqueness
 * check, collision handling via random suffix), workspace-authorization rejection (same {@code
 * verify(..., never())} rigor as CampaignLinkServiceTest), and idempotent duplicate-creator
 * handling under the {@code UNIQUE(campaign_id, creator_id)} constraint
 * (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §10).
 */
@ExtendWith(MockitoExtension.class)
class CouponCodeServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HOTHERWORKSPACE1234";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String CREATOR_ID = "01HCREATORPROFILE1234";
    private static final String COUPON_ID = "01HCOUPON1234567890AB";

    @Mock private CouponCodeRepository couponCodeRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;

    private CouponCodeService service;

    @BeforeEach
    void setUp() {
        service = new CouponCodeService(couponCodeRepository, campaignRepository, creatorProfileRepository);
    }

    // ------------------------------------------------------------------
    // generateCreatorCoupon: code generation + collision handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateCreatorCoupon: builds CREATOR_CAMPAIGN pattern from slugified, upper-cased fields")
    void testGeneratesCreatorCampaignPattern() {
        when(couponCodeRepository.existsByWorkspaceIdAndCode(WORKSPACE_ID, "PRIYA-SHARMA_SUMMER-SALE-2026"))
                .thenReturn(false);

        String code = service.generateCreatorCoupon(campaign(), creator());

        assertEquals("PRIYA-SHARMA_SUMMER-SALE-2026", code);
    }

    @Test
    @DisplayName("generateCreatorCoupon: appends a random 4-char suffix on collision, re-checking the suffixed code")
    void testAppendsRandomSuffixOnCollision() {
        when(couponCodeRepository.existsByWorkspaceIdAndCode(WORKSPACE_ID, "PRIYA-SHARMA_SUMMER-SALE-2026"))
                .thenReturn(true);
        // Any suffixed candidate is accepted as unique -- asserts format only, not a specific suffix.
        when(couponCodeRepository.existsByWorkspaceIdAndCode(
                        org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                        org.mockito.ArgumentMatchers.startsWith("PRIYA-SHARMA_SUMMER-SALE-2026_")))
                .thenReturn(false);

        String code = service.generateCreatorCoupon(campaign(), creator());

        assertTrue(code.startsWith("PRIYA-SHARMA_SUMMER-SALE-2026_"));
        String suffix = code.substring("PRIYA-SHARMA_SUMMER-SALE-2026_".length());
        assertEquals(4, suffix.length());
        assertTrue(suffix.chars().allMatch(ch -> Character.isLetterOrDigit(ch) && Character.isUpperCase((char) ch) || Character.isDigit(ch)));
    }

    @Test
    @DisplayName(
            "generateCreatorCoupon: retries past a colliding suffix and returns the candidate that"
                    + " actually passes the uniqueness check, not just a blind single append")
    void testRetriesCollisionUntilUniqueSuffixFound() {
        String baseCode = "PRIYA-SHARMA_SUMMER-SALE-2026";

        // Base code collides. The first TWO distinct suffixed candidates the loop generates also
        // collide. The third distinct suffixed candidate is unique and must be the one returned.
        // We can't force SecureRandom's exact output, so key off call order instead: 1st call =
        // base code (collides), 2nd distinct candidate seen = collides, 3rd distinct candidate
        // seen = collides, 4th distinct candidate seen = unique. This forces the loop to actually
        // iterate at least twice before succeeding, proving it isn't a single blind append.
        java.util.List<String> distinctCandidatesInOrder = new java.util.ArrayList<>();
        when(couponCodeRepository.existsByWorkspaceIdAndCode(
                        org.mockito.ArgumentMatchers.eq(WORKSPACE_ID), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        inv -> {
                            String candidate = inv.getArgument(1);
                            if (candidate.equals(baseCode)) {
                                return true;
                            }
                            if (!distinctCandidatesInOrder.contains(candidate)) {
                                distinctCandidatesInOrder.add(candidate);
                            }
                            int index = distinctCandidatesInOrder.indexOf(candidate);
                            // First two distinct suffixed candidates collide (index 0, 1); third+ unique.
                            return index < 2;
                        });

        String code = service.generateCreatorCoupon(campaign(), creator());

        assertTrue(code.startsWith(baseCode + "_"));
        assertEquals(3, distinctCandidatesInOrder.size());
        // The returned code must be the third distinct candidate -- the one that actually passed
        // the uniqueness check -- not the first or second (both of which collided).
        assertEquals(distinctCandidatesInOrder.get(2), code);
        assertTrue(!code.equals(distinctCandidatesInOrder.get(0)));
        assertTrue(!code.equals(distinctCandidatesInOrder.get(1)));
        // Base code + 2 rejected suffix attempts + 1 accepted attempt = 4 total calls.
        verify(couponCodeRepository, times(4))
                .existsByWorkspaceIdAndCode(org.mockito.ArgumentMatchers.eq(WORKSPACE_ID), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName(
            "generateCreatorCoupon: throws COUPON_CODE_GENERATION_FAILED if every retry attempt collides")
    void testThrowsWhenCollisionRetriesExhausted() {
        when(couponCodeRepository.existsByWorkspaceIdAndCode(
                        org.mockito.ArgumentMatchers.eq(WORKSPACE_ID), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.generateCreatorCoupon(campaign(), creator()));

        assertEquals("COUPON_CODE_GENERATION_FAILED", ex.getCode());
        assertEquals(500, ex.getStatus().value());
    }

    @Test
    @DisplayName("generateCreatorCoupon: uniqueness check is scoped by workspaceId, not global")
    void testUniquenessCheckIsWorkspaceScoped() {
        when(couponCodeRepository.existsByWorkspaceIdAndCode(WORKSPACE_ID, "PRIYA-SHARMA_SUMMER-SALE-2026"))
                .thenReturn(false);

        service.generateCreatorCoupon(campaign(), creator());

        verify(couponCodeRepository).existsByWorkspaceIdAndCode(WORKSPACE_ID, "PRIYA-SHARMA_SUMMER-SALE-2026");
    }

    // ------------------------------------------------------------------
    // addCreatorToCampaign: workspace authorization
    // ------------------------------------------------------------------

    @Test
    @DisplayName("addCreatorToCampaign: rejects when the caller's workspace does not own the campaign")
    void testRejectsWhenWorkspaceDoesNotOwnCampaign() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, OTHER_WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.addCreatorToCampaign(
                                        OTHER_WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        CREATOR_ID,
                                        "percentage",
                                        BigDecimal.valueOf(15),
                                        null,
                                        null));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        // Never even looks up the creator once workspace ownership fails.
        verify(creatorProfileRepository, never()).findById(any());
        verify(couponCodeRepository, never()).save(any());
        // Full proof: couponCodeRepository is never touched at all once the workspace-ownership
        // check fails -- not the idempotent-lookup finder, not save(), nothing (same rigor as
        // CampaignLinkServiceTest.testRejectsWhenWorkspaceDoesNotOwnCampaign's
        // verifyNoInteractions(utmCampaignRepository)).
        org.mockito.Mockito.verifyNoInteractions(couponCodeRepository);
    }

    @Test
    @DisplayName("addCreatorToCampaign: CREATOR_NOT_FOUND when creator id doesn't exist")
    void testCreatorNotFound() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(creatorProfileRepository.findById(CREATOR_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.addCreatorToCampaign(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        CREATOR_ID,
                                        "percentage",
                                        BigDecimal.valueOf(15),
                                        null,
                                        null));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(couponCodeRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // addCreatorToCampaign: happy path + idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("addCreatorToCampaign: creates and persists a coupon with the correct fields")
    void testCreatesCouponWithCorrectFields() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(creatorProfileRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator()));
        when(couponCodeRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_ID))
                .thenReturn(Optional.empty());
        when(couponCodeRepository.existsByWorkspaceIdAndCode(WORKSPACE_ID, "PRIYA-SHARMA_SUMMER-SALE-2026"))
                .thenReturn(false);
        when(couponCodeRepository.save(any(CouponCode.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant expiry = Instant.parse("2026-12-31T00:00:00Z");
        CouponCode result =
                service.addCreatorToCampaign(
                        WORKSPACE_ID, CAMPAIGN_ID, CREATOR_ID, "percentage", BigDecimal.valueOf(15), 100, expiry);

        assertEquals(WORKSPACE_ID, result.getWorkspaceId());
        assertEquals(CAMPAIGN_ID, result.getCampaignId());
        assertEquals(CREATOR_ID, result.getCreatorId());
        assertEquals("PRIYA-SHARMA_SUMMER-SALE-2026", result.getCode());
        assertEquals("percentage", result.getDiscountType());
        assertEquals(BigDecimal.valueOf(15), result.getDiscountValue());
        assertEquals(100, result.getUsageLimit());
        assertEquals(expiry, result.getExpiresAt());
        assertEquals(0, result.getUsageCount());

        ArgumentCaptor<CouponCode> captor = ArgumentCaptor.forClass(CouponCode.class);
        verify(couponCodeRepository, times(1)).save(captor.capture());
        assertEquals("PRIYA-SHARMA_SUMMER-SALE-2026", captor.getValue().getCode());
    }

    @Test
    @DisplayName("addCreatorToCampaign: returns the existing coupon instead of creating a duplicate (idempotent)")
    void testReturnsExistingCouponWithoutDuplicating() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(creatorProfileRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator()));
        CouponCode existing =
                CouponCode.builder()
                        .id(COUPON_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .code("PRIYA-SHARMA_SUMMER-SALE-2026")
                        .discountType("percentage")
                        .discountValue(BigDecimal.valueOf(15))
                        .build();
        when(couponCodeRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_ID))
                .thenReturn(Optional.of(existing));

        CouponCode result =
                service.addCreatorToCampaign(
                        WORKSPACE_ID, CAMPAIGN_ID, CREATOR_ID, "percentage", BigDecimal.valueOf(15), null, null);

        assertEquals(COUPON_ID, result.getId());
        verify(couponCodeRepository, never()).save(any());
        verify(couponCodeRepository, never()).existsByWorkspaceIdAndCode(any(), any());
    }

    @Test
    @DisplayName(
            "addCreatorToCampaign: idempotent existing-coupon path does NOT bypass workspace"
                    + " authorization -- a different workspace is still rejected even though a coupon"
                    + " row already exists for this (campaignId, creatorId) pair")
    void testIdempotentPathDoesNotSkipAuthorizationCheck() {
        // Workspace A owns the campaign and already has a coupon for this creator.
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(creatorProfileRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator()));
        CouponCode existing =
                CouponCode.builder()
                        .id(COUPON_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .code("PRIYA-SHARMA_SUMMER-SALE-2026")
                        .discountType("percentage")
                        .discountValue(BigDecimal.valueOf(15))
                        .build();
        when(couponCodeRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_ID))
                .thenReturn(Optional.of(existing));

        CouponCode firstCall =
                service.addCreatorToCampaign(
                        WORKSPACE_ID, CAMPAIGN_ID, CREATOR_ID, "percentage", BigDecimal.valueOf(15), null, null);
        assertEquals(COUPON_ID, firstCall.getId());

        // Same campaignId/creatorId, but a DIFFERENT workspace (B) that does not own the campaign.
        // findByIdAndWorkspaceId(CAMPAIGN_ID, OTHER_WORKSPACE_ID) is not stubbed above, so Mockito
        // returns its default Optional.empty() -- workspace B genuinely does not own this campaign.
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.addCreatorToCampaign(
                                        OTHER_WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        CREATOR_ID,
                                        "percentage",
                                        BigDecimal.valueOf(15),
                                        null,
                                        null));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        // Proves the idempotent-return path did not short-circuit straight to
        // findByCampaignIdAndCreatorId for the second (workspace B) call -- if it had, it would
        // have found workspace A's existing row and returned it regardless of which workspace
        // asked. Exactly ONE call total (from the first, legitimate workspace-A invocation) means
        // the second call's authorization check ran first and rejected workspace B before the
        // existing-coupon lookup was ever reached.
        verify(couponCodeRepository, times(1)).findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_ID);
        // Likewise, the creator lookup only ran once (for the first, authorized call) -- workspace
        // B's call never got past the campaign-ownership check to look up the creator at all.
        verify(creatorProfileRepository, times(1)).findById(any());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Campaign campaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Sale 2026")
                .createdBy("brandUser")
                .build();
    }

    private static CreatorProfile creator() {
        return new CreatorProfile() {
            @Override
            public String getId() {
                return CREATOR_ID;
            }

            @Override
            public String getDisplayName() {
                return "Priya Sharma";
            }
        };
    }
}
