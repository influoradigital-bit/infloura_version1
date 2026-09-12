package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.risk.DealRiskService;
import com.influora.web.dto.meera.CreatorToolDtos.CheckDealRisksResult;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.3, B4) — {@code DealService.risksForCreator}, which
 * backs {@code GET /deals/{id}/risks}.
 *
 * <p>The load-bearing test here is {@link #brandPrincipalIsRefusedWith403()}. These flags quote the
 * creator's own rate floor back at her; a brand reading them would learn the lowest number she
 * accepts. The refusal must happen BEFORE the risk service is reached, which is why the test
 * asserts on {@code verifyNoInteractions} as well as on the status — a 403 that still ran the
 * evaluation would be one logging change away from leaking.
 */
@ExtendWith(MockitoExtension.class)
class DealServiceRisksTest {

    private static final String DEAL_ID = "01DEAL0000000000000000000";
    private static final String USER_ID = "01CREATORUSER00000000000000";
    private static final String PROFILE_ID = "01CREATORPROFILE0000000000";

    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private BrandContextService brandContext;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DealMessageStreamRegistry messageStreamRegistry;
    @Mock private CollaborationReviveService collaborationReviveService;
    @Mock private ApplicationHistoryService applicationHistoryService;
    @Mock private DealRiskService dealRiskService;
    @Mock private AuthPrincipal principal;

    private DealService service;

    @BeforeEach
    void setUp() {
        service =
                new DealService(
                        collaborationRepository,
                        dealMessageRepository,
                        campaignRepository,
                        creatorProfileRepository,
                        workspaceRepository,
                        contractRepository,
                        escrowHoldRepository,
                        deliverableRepository,
                        creatorContext,
                        brandContext,
                        idempotencyService,
                        eventPublisher,
                        messageStreamRegistry,
                        collaborationReviveService,
                        applicationHistoryService,
                        dealRiskService);
    }

    @Test
    @DisplayName("a BRAND principal gets 403 CREATOR_ONLY and the risk engine is never reached")
    void brandPrincipalIsRefusedWith403() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);

        assertThatThrownBy(() -> service.risksForCreator(principal, DEAL_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CREATOR_ONLY")
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verifyNoInteractions(dealRiskService);
        verifyNoInteractions(creatorContext);
    }

    @Test
    @DisplayName("an unauthenticated caller gets the same 403, not a null-pointer 500")
    void nullPrincipalIsRefusedWith403() {
        assertThatThrownBy(() -> service.risksForCreator(null, DEAL_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CREATOR_ONLY")
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verifyNoInteractions(dealRiskService);
    }

    @Test
    @DisplayName("a creator gets her flags, wrapped with the highest severity and the deal id")
    void creatorGetsFlagsWithHighestSeverity() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(creatorContext.requireCreatorProfile(principal))
                .thenReturn(CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah"));
        when(dealRiskService.evaluateDeal(PROFILE_ID, DEAL_ID))
                .thenReturn(List.of(flag("USAGE_LONG", "INFO"), flag("BELOW_FLOOR", "CRITICAL")));

        CheckDealRisksResult result = service.risksForCreator(principal, DEAL_ID);

        assertThat(result.flags()).hasSize(2);
        assertThat(result.highestSeverity()).isEqualTo("CRITICAL");
        assertThat(result.target()).isEqualTo(DealRiskService.TARGET_DEAL);
        assertThat(result.targetId()).isEqualTo(DEAL_ID);
    }

    @Test
    @DisplayName("a clean deal returns an empty list and no highest_severity")
    void cleanDealReturnsNoSeverity() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(creatorContext.requireCreatorProfile(principal))
                .thenReturn(CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah"));
        when(dealRiskService.evaluateDeal(PROFILE_ID, DEAL_ID)).thenReturn(List.of());

        CheckDealRisksResult result = service.risksForCreator(principal, DEAL_ID);

        assertThat(result.flags()).isEmpty();
        assertThat(result.highestSeverity()).isNull();
    }

    @Test
    @DisplayName("a suspended creator is still refused, by the profile gate rather than the type gate")
    void suspendedCreatorIsRefusedByTheProfileGate() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(creatorContext.requireCreatorProfile(principal))
                .thenThrow(new ApiException("ACCOUNT_SUSPENDED", "suspended", HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.risksForCreator(principal, DEAL_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_SUSPENDED");

        verifyNoInteractions(dealRiskService);
    }

    private static RiskFlag flag(String code, String severity) {
        return new RiskFlag(code, severity, "t", "d", null, "a", Map.<String, String>of(), true);
    }

    /** Guards the mock wiring: the risk service is the last constructor argument, not a stray null. */
    @Test
    @DisplayName("the service really does delegate to DealRiskService and not to something else")
    void delegatesToTheRiskService() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(creatorContext.requireCreatorProfile(principal))
                .thenReturn(CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah"));
        when(dealRiskService.evaluateDeal(any(), any())).thenReturn(List.of(flag("BARTER", "WARN")));

        assertThat(service.risksForCreator(principal, DEAL_ID).flags())
                .extracting(RiskFlag::code)
                .containsExactly("BARTER");
    }
}
