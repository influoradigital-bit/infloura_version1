package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.domain.enums.UserType;
import com.influora.web.dto.analytics.AnalyticsDtos.CampaignAnalyticsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricSubmitRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P0 #3: Unit tests for DeliverableMetricService (wiki/tech/TASK-brand-audit-backend-build.md).
 * Priority: the honesty rule (source flag always CREATOR_REPORTED, never fabricated), the
 * ownership check, and the "reportable only once funded" gate.
 */
@ExtendWith(MockitoExtension.class)
class DeliverableMetricServiceTest {

    private static final String CREATOR_ID = "01HCREATOR12345678901";
    private static final String OTHER_USER_ID = "01HOTHER123456789012A";
    private static final String MILESTONE_ID = "01HMILESTONE1234567890";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";

    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private BrandContextService brandContext;

    private DeliverableMetricService service;

    @BeforeEach
    void setUp() {
        service =
                new DeliverableMetricService(
                        deliverableMetricRepository,
                        milestoneRepository,
                        collaborationRepository,
                        campaignRepository,
                        brandContext);
    }

    @Test
    @DisplayName("submit: rejects a creator reporting on someone else's deliverable")
    void testSubmitRejectsWrongCreator() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.FUNDED);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));

        AuthPrincipal intruder = new AuthPrincipal(OTHER_USER_ID, "x@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req =
                new DeliverableMetricSubmitRequest(100L, 1000L, 50L, "https://x.com/post", null);

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(intruder, MILESTONE_ID, req));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("submit: rejects reporting on a milestone that isn't funded/released yet")
    void testSubmitRejectsUnapprovedMilestone() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.PENDING);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));

        AuthPrincipal creator = new AuthPrincipal(CREATOR_ID, "c@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req =
                new DeliverableMetricSubmitRequest(100L, 1000L, 50L, null, null);

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(creator, MILESTONE_ID, req));
        assertEquals("DELIVERABLE_NOT_APPROVED", ex.getCode());
    }

    @Test
    @DisplayName("submit: rejects negative metric values")
    void testSubmitRejectsNegativeValues() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.FUNDED);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));

        AuthPrincipal creator = new AuthPrincipal(CREATOR_ID, "c@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req = new DeliverableMetricSubmitRequest(-1L, 1000L, 50L, null, null);

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(creator, MILESTONE_ID, req));
        assertEquals("INVALID_METRIC_VALUE", ex.getCode());
    }

    @Test
    @DisplayName("submit: accepted report always carries source = CREATOR_REPORTED")
    void testSubmitResponseCarriesHonestySourceFlag() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.FUNDED);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));
        when(deliverableMetricRepository.findByMilestoneId(MILESTONE_ID)).thenReturn(Optional.empty());
        lenient()
                .when(deliverableMetricRepository.save(any(DeliverableMetric.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuthPrincipal creator = new AuthPrincipal(CREATOR_ID, "c@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req =
                new DeliverableMetricSubmitRequest(500L, 5000L, 250L, "https://x.com/post", null);

        DeliverableMetricResponse response = service.submit(creator, MILESTONE_ID, req);

        assertEquals("CREATOR_REPORTED", response.source());
        assertEquals(500L, response.reach());
        assertEquals(5000L, response.impressions());
        assertEquals(250L, response.engagements());
    }

    @Test
    @DisplayName("getCampaignAnalytics: empty when no deliverables reported yet, not fake zeros with a false source")
    void testAnalyticsEmptyStateStillHonest() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

        AuthPrincipal brandUser = new AuthPrincipal("brandUser", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        CampaignAnalyticsResponse response =
                service.getCampaignAnalytics(brandUser, WORKSPACE_ID, CAMPAIGN_ID);

        assertEquals("CREATOR_REPORTED", response.source());
        assertEquals(0L, response.totalReach());
        assertEquals(0L, response.totalImpressions());
        assertEquals(0L, response.totalEngagements());
        assertNull(response.derivedEngagementRate());
        assertEquals(0, response.deliverablesReported());
        assertEquals(0, response.deliverables().size());
    }

    private static PaymentMilestone milestoneWithStatus(MilestoneStatus status) {
        return PaymentMilestone.builder()
                .id(MILESTONE_ID)
                .contractId("01HCONTRACT123456789A")
                .collaborationId(COLLAB_ID)
                .sequenceNo(1)
                .amount(java.math.BigDecimal.TEN)
                .status(status)
                .build();
    }

    private static Collaboration collaborationOwnedBy(String creatorId) {
        return Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, creatorId, null, "INR");
    }

    private static Campaign campaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Test Campaign")
                .createdBy("brandUser")
                .build();
    }
}
