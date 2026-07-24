package com.influora.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.UtmCampaignRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 4 UTM/Coupon Tracking: unit tests for CampaignLinkService (the pure-Java, no-external-
 * dependency foundation slice — VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §5.1).
 *
 * <p>Priority: URL generation correctness (encoding, separator logic, slug generation), not-found
 * handling for each resolved entity, counter increments via recordClick, and — the workspace-
 * isolation concern Kabir flagged on the metrics repos — a caller cannot mint a tracking link for a
 * campaign owned by another workspace.
 */
@ExtendWith(MockitoExtension.class)
class CampaignLinkServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HOTHERWORKSPACE1234";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String UTM_ID = "01HUTM1234567890ABCDE";

    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;

    private CampaignLinkService service;

    @BeforeEach
    void setUp() {
        service =
                new CampaignLinkService(
                        utmCampaignRepository, campaignRepository, collaborationRepository, creatorProfileRepository);
    }

    // ------------------------------------------------------------------
    // Workspace-authorization
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createTrackingLink: rejects when the caller's workspace does not own the campaign")
    void testRejectsWhenWorkspaceDoesNotOwnCampaign() {
        // Campaign lookup scoped by (campaignId, workspaceId) — a different workspace gets empty.
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, OTHER_WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        OTHER_WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "https://brand.example.com/landing",
                                        "instagram"));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        // Never even looks up the collaboration/creator once workspace ownership fails.
        verify(collaborationRepository, never()).findById(any());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: rejects when the collaboration belongs to a different campaign")
    void testRejectsCollaborationCampaignMismatch() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        Collaboration mismatched =
                Collaboration.invite("01HOTHERCOLLAB123456", "01HOTHERCAMPAIGN12345", CREATOR_USER_ID, null, "INR");
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(mismatched));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "https://brand.example.com/landing",
                                        "instagram"));

        assertEquals("COLLABORATION_CAMPAIGN_MISMATCH", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: rejects when the creator profile does not match the collaboration's creator")
    void testRejectsCreatorCollaborationMismatch() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));
        CreatorProfile wrongCreator = creatorProfile("01HWRONGUSERID123456", "Someone Else");
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.of(wrongCreator));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "https://brand.example.com/landing",
                                        "instagram"));

        assertEquals("CREATOR_COLLABORATION_MISMATCH", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Not-found handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createTrackingLink: COLLABORATION_NOT_FOUND when collaboration id doesn't exist")
    void testCollaborationNotFound() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "https://brand.example.com/landing",
                                        "instagram"));

        assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName("createTrackingLink: CREATOR_NOT_FOUND when creator profile id doesn't exist")
    void testCreatorNotFound() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "https://brand.example.com/landing",
                                        "instagram"));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    // ------------------------------------------------------------------
    // URL generation correctness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createTrackingLink: builds a correctly encoded URL, appending '?' when base URL has no query string")
    void testBuildsUrlWithQuestionMarkSeparator() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(creatorProfile(CREATOR_USER_ID, "Priya Sharma")));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
        when(utmCampaignRepository.save(any(UtmCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        UtmCampaign result =
                service.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "https://brand.example.com/landing",
                        "Instagram");

        assertEquals("instagram", result.getUtmSource());
        assertEquals("influencer", result.getUtmMedium());
        assertEquals("summer-sale-2026", result.getUtmCampaign());
        assertEquals("priya-sharma", result.getUtmContent());
        assertEquals(
                "https://brand.example.com/landing?utm_source=instagram&utm_medium=influencer"
                        + "&utm_campaign=summer-sale-2026&utm_content=priya-sharma",
                result.getFullTrackingUrl());
    }

    @Test
    @DisplayName("createTrackingLink: appends '&' instead of '?' when base URL already has a query string")
    void testBuildsUrlWithAmpersandSeparator() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(creatorProfile(CREATOR_USER_ID, "Priya Sharma")));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
        when(utmCampaignRepository.save(any(UtmCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        UtmCampaign result =
                service.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "https://brand.example.com/landing?ref=homepage",
                        "instagram");

        assertEquals(
                "https://brand.example.com/landing?ref=homepage&utm_source=instagram"
                        + "&utm_medium=influencer&utm_campaign=summer-sale-2026&utm_content=priya-sharma",
                result.getFullTrackingUrl());
    }

    @Test
    @DisplayName("createTrackingLink: URL-encodes UTM values containing spaces/special characters")
    void testUrlEncodesSpecialCharacters() {
        Campaign specialCampaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Diwali & New Year Sale!")
                        .createdBy("brandUser")
                        .build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(specialCampaign));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(creatorProfile(CREATOR_USER_ID, "Priya Sharma")));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
        when(utmCampaignRepository.save(any(UtmCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        UtmCampaign result =
                service.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "https://brand.example.com/landing",
                        "instagram");

        // SlugUtils strips non-word characters before URL-encoding even applies, so the slug itself
        // is already URL-safe ("diwali-new-year-sale") — encode() must not introduce '+' or leave
        // spaces from an unslugified value.
        assertEquals("diwali-new-year-sale", result.getUtmCampaign());
        assertEquals("https://brand.example.com/landing?utm_source=instagram&utm_medium=influencer"
                        + "&utm_campaign=diwali-new-year-sale&utm_content=priya-sharma",
                result.getFullTrackingUrl());
    }

    @Test
    @DisplayName("createTrackingLink: returns the existing link instead of creating a duplicate")
    void testReturnsExistingLinkWithoutDuplicating() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(creatorProfile(CREATOR_USER_ID, "Priya Sharma")));
        UtmCampaign existing =
                UtmCampaign.builder()
                        .id(UTM_ID)
                        .campaignId(CAMPAIGN_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .baseUrl("https://brand.example.com/landing")
                        .utmSource("instagram")
                        .utmMedium("influencer")
                        .utmCampaign("summer-sale-2026")
                        .utmContent("priya-sharma")
                        .fullTrackingUrl("https://brand.example.com/landing?utm_source=instagram")
                        .build();
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(existing));

        UtmCampaign result =
                service.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "https://brand.example.com/landing",
                        "instagram");

        assertEquals(UTM_ID, result.getId());
        verify(utmCampaignRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Security: baseUrl scheme validation (Kabir red-team gate, validateBaseUrl)
    //
    // [ZERO-COVERAGE FIX, Kavya QA] validateBaseUrl (the http/https allowlist added to close the
    // stored open-redirect / stored-XSS gap) had no regression coverage — a revert of the gate
    // would not fail CI. validateBaseUrl is private, so it is exercised end-to-end through the
    // public createTrackingLink path, exactly like every other test in this class. Each negative
    // case resolves campaign/collaboration/creator successfully (mockValidResolutionChain) so the
    // rejection is proven to come from validateBaseUrl itself, not an earlier not-found/mismatch
    // check.
    // ------------------------------------------------------------------

    private void mockValidResolutionChain() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(creatorProfile(CREATOR_USER_ID, "Priya Sharma")));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("createTrackingLink: rejects javascript: scheme as INVALID_TRACKING_URL")
    void testRejectsJavascriptScheme() {
        mockValidResolutionChain();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "javascript:alert(1)",
                                        "instagram"));

        assertEquals("INVALID_TRACKING_URL", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: rejects data: scheme as INVALID_TRACKING_URL")
    void testRejectsDataScheme() {
        mockValidResolutionChain();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "data:text/html,x",
                                        "instagram"));

        assertEquals("INVALID_TRACKING_URL", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: rejects ftp: scheme as INVALID_TRACKING_URL")
    void testRejectsFtpScheme() {
        mockValidResolutionChain();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "ftp://example.com",
                                        "instagram"));

        assertEquals("INVALID_TRACKING_URL", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: rejects protocol-relative/schemeless baseUrl as INVALID_TRACKING_URL")
    void testRejectsProtocolRelativeUrl() {
        mockValidResolutionChain();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "//example.com",
                                        "instagram"));

        assertEquals("INVALID_TRACKING_URL", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: rejects a blank/whitespace baseUrl as INVALID_TRACKING_URL")
    void testRejectsBlankBaseUrl() {
        mockValidResolutionChain();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "   ",
                                        "instagram"));

        assertEquals("INVALID_TRACKING_URL", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: rejects an unparseable baseUrl as INVALID_TRACKING_URL")
    void testRejectsUnparseableBaseUrl() {
        mockValidResolutionChain();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_PROFILE_ID,
                                        "http://[bad",
                                        "instagram"));

        assertEquals("INVALID_TRACKING_URL", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrackingLink: accepts a case-insensitive https scheme (hTTpS://)")
    void testAcceptsCaseInsensitiveHttpsScheme() {
        mockValidResolutionChain();
        when(utmCampaignRepository.save(any(UtmCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        UtmCampaign result =
                service.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "hTTpS://Example.com",
                        "instagram");

        assertEquals(
                "hTTpS://Example.com?utm_source=instagram&utm_medium=influencer"
                        + "&utm_campaign=summer-sale-2026&utm_content=priya-sharma",
                result.getFullTrackingUrl());
    }

    @Test
    @DisplayName("createTrackingLink: accepts an http baseUrl with a port, query string, and fragment")
    void testAcceptsUrlWithPortQueryAndFragment() {
        mockValidResolutionChain();
        when(utmCampaignRepository.save(any(UtmCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        UtmCampaign result =
                service.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "http://localhost:8080/path?a=1&b=2#frag",
                        "instagram");

        assertEquals(
                "http://localhost:8080/path?a=1&b=2#frag&utm_source=instagram&utm_medium=influencer"
                        + "&utm_campaign=summer-sale-2026&utm_content=priya-sharma",
                result.getFullTrackingUrl());
    }

    @Test
    @DisplayName("createTrackingLink: accepts an https baseUrl that already has query params")
    void testAcceptsHttpsUrlWithExistingQueryParams() {
        mockValidResolutionChain();
        when(utmCampaignRepository.save(any(UtmCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        UtmCampaign result =
                service.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "https://brand.example.com/product?utm=x&y=2",
                        "instagram");

        assertEquals(
                "https://brand.example.com/product?utm=x&y=2&utm_source=instagram&utm_medium=influencer"
                        + "&utm_campaign=summer-sale-2026&utm_content=priya-sharma",
                result.getFullTrackingUrl());
    }

    // ------------------------------------------------------------------
    // recordClick: counter increments
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recordClick: UTM_NOT_FOUND when the tracking link id doesn't exist")
    void testRecordClickNotFound() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.recordClick(UTM_ID, "visitor-1"));

        assertEquals("UTM_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName("recordClick: increments both click_count and unique_visitors when a visitorId is given")
    void testRecordClickIncrementsClickAndVisitorCounters() {
        UtmCampaign utm = existingUtm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordClick(UTM_ID, "visitor-1");

        assertEquals(1L, utm.getClickCount());
        assertEquals(1L, utm.getUniqueVisitors());
        ArgumentCaptor<UtmCampaign> captor = ArgumentCaptor.forClass(UtmCampaign.class);
        verify(utmCampaignRepository, times(1)).save(captor.capture());
        assertEquals(1L, captor.getValue().getClickCount());
    }

    @Test
    @DisplayName("recordClick: increments only click_count when visitorId is null/blank")
    void testRecordClickWithoutVisitorIdOnlyIncrementsClicks() {
        UtmCampaign utm = existingUtm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordClick(UTM_ID, null);

        assertEquals(1L, utm.getClickCount());
        assertEquals(0L, utm.getUniqueVisitors());
    }

    @Test
    @DisplayName("recordClick: repeated calls accumulate the counters")
    void testRecordClickAccumulatesAcrossCalls() {
        UtmCampaign utm = existingUtm();
        lenient().when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordClick(UTM_ID, "visitor-1");
        service.recordClick(UTM_ID, "visitor-2");
        service.recordClick(UTM_ID, null);

        assertEquals(3L, utm.getClickCount());
        assertEquals(2L, utm.getUniqueVisitors());
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

    private static Collaboration collaboration() {
        return Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
    }

    private static CreatorProfile creatorProfile(String userId, String displayName) {
        return new CreatorProfile() {
            @Override
            public String getId() {
                return CREATOR_PROFILE_ID;
            }

            @Override
            public String getUserId() {
                return userId;
            }

            @Override
            public String getDisplayName() {
                return displayName;
            }
        };
    }

    private static UtmCampaign existingUtm() {
        return UtmCampaign.builder()
                .id(UTM_ID)
                .campaignId(CAMPAIGN_ID)
                .collaborationId(COLLAB_ID)
                .creatorProfileId(CREATOR_PROFILE_ID)
                .baseUrl("https://brand.example.com/landing")
                .utmSource("instagram")
                .utmMedium("influencer")
                .utmCampaign("summer-sale-2026")
                .utmContent("priya-sharma")
                .fullTrackingUrl("https://brand.example.com/landing?utm_source=instagram")
                .build();
    }
}
