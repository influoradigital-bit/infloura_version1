package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.CreatorAlreadyOnInfluoraException;
import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.domain.enums.ExternalCreatorStatus;
import com.influora.integration.meta.client.CreatorMarketplaceClient;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.BusinessDiscoveryResponse;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creator.ExternalCreatorDtos.ConnectionRequestResponse;
import com.influora.web.dto.creator.ExternalCreatorDtos.ExternalCreatorResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * T-CREATORCONNECT-0902. Plain Mockito, no {@code @SpringBootTest} — matches every other
 * service-level test in this package.
 */
@ExtendWith(MockitoExtension.class)
class ExternalCreatorServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE0000000001";
    private static final String USER_ID = "01HUSER00000000000001";
    private static final String EXTERNAL_CREATOR_ID = "01HEXTCREATOR000000001";

    @Mock private BrandContextService brandContextService;
    @Mock private ExternalCreatorRepository externalCreatorRepository;
    @Mock private CreatorConnectionRequestRepository connectionRequestRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private MetaTokenStorage metaTokenStorage;
    @Mock private InstagramInsightsClient instagramInsightsClient;
    @Mock private FacebookPageClient facebookPageClient;
    @Mock private CreatorMarketplaceClient creatorMarketplaceClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    // Q1.4/Q3.4 — ExternalCreatorService now drives its racy saves through a hand-managed
    // TransactionTemplate(REQUIRES_NEW) instead of a self-invoked @Transactional method (see that
    // class's javadoc for why: self-invocation bypasses Spring AOP entirely). A mocked
    // PlatformTransactionManager returning a plain SimpleTransactionStatus from getTransaction()
    // lets TransactionTemplate.execute(...) run the callback exactly as it would for a real one
    // (commit()/rollback() are no-op void mock calls) WITHOUT needing a real EntityManager/DB —
    // this class's tests stay plain Mockito. Proving the REQUIRES_NEW isolation itself (that a
    // losing writer's flush failure cannot poison the CALLER's real ambient transaction) is what
    // ExternalCreatorServiceRaceRollbackIsolationTest (@DataJpaTest, real transaction manager) is
    // for — this mock cannot and does not attempt to prove that part.
    @Mock private PlatformTransactionManager transactionManager;

    private ExternalCreatorService service;
    private MetaApiProperties metaApiProperties;

    @BeforeEach
    void setUp() {
        metaApiProperties = new MetaApiProperties();
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service =
                new ExternalCreatorService(
                        brandContextService,
                        externalCreatorRepository,
                        connectionRequestRepository,
                        metaOAuthTokenRepository,
                        metaTokenStorage,
                        metaApiProperties,
                        instagramInsightsClient,
                        facebookPageClient,
                        creatorMarketplaceClient,
                        eventPublisher,
                        transactionManager);
        // lenient — not every test in this class actually reaches a workspace.getId() call
        // (e.g. connect() on a JOINED creator throws before touching it; lookup() with Meta
        // unconfigured 503s before resolving a caller token at all).
        lenient().when(workspace.getId()).thenReturn(WORKSPACE_ID);
    }

    private ExternalCreator unverifiedCreator() {
        return ExternalCreator.builder()
                .id(EXTERNAL_CREATOR_ID)
                .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                .igUsername("foodie.mumbai")
                .build();
    }

    @Test
    @DisplayName("connect: idempotent — an existing non-DECLINED request is returned unchanged, no new save/event")
    void connect_idempotent_returnsExistingRequestUnchanged() {
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(unverifiedCreator()));

        CreatorConnectionRequest existing =
                CreatorConnectionRequest.builder()
                        .id("01HREQUEST0000000001")
                        .workspaceId(WORKSPACE_ID)
                        .requestedByUserId(USER_ID)
                        .externalCreatorId(EXTERNAL_CREATOR_ID)
                        .message("please connect")
                        .build();
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(WORKSPACE_ID, EXTERNAL_CREATOR_ID))
                .thenReturn(Optional.of(existing));

        ConnectionRequestResponse first = service.connect(principal, EXTERNAL_CREATOR_ID, "second message");

        assertEquals(existing.getId(), first.id());
        assertEquals("PENDING", first.status());
        verify(connectionRequestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("connect: JOINED creator with a linked profile -> 409 CREATOR_ALREADY_ON_INFLUORA carrying linkedCreatorProfileId")
    void connect_onJoinedCreator_throwsWithLinkedProfileId() {
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);

        ExternalCreator joined = unverifiedCreator();
        joined.markJoined("01HCREATORPROFILE00001");
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(joined));

        CreatorAlreadyOnInfluoraException ex =
                assertThrows(
                        CreatorAlreadyOnInfluoraException.class,
                        () -> service.connect(principal, EXTERNAL_CREATOR_ID, "hi"));

        assertEquals("01HCREATORPROFILE00001", ex.getLinkedCreatorProfileId());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(connectionRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("connect: creates a fresh PENDING request and publishes one CreatorConnectionRequestedEvent when none exists")
    void connect_noExistingRequest_createsAndPublishesEvent() {
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getName()).thenReturn("Acme Brand");
        when(principal.getUserId()).thenReturn(USER_ID);
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(unverifiedCreator()));
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(WORKSPACE_ID, EXTERNAL_CREATOR_ID))
                .thenReturn(Optional.empty());

        ConnectionRequestResponse response = service.connect(principal, EXTERNAL_CREATOR_ID, "let's work together");

        assertEquals("PENDING", response.status());
        verify(connectionRequestRepository).saveAndFlush(any(CreatorConnectionRequest.class));
        verify(eventPublisher)
                .publishEvent(
                        any(com.influora.service.notification.event.CreatorConnectionRequestedEvent.class));
    }

    @Test
    @DisplayName("lookup: Meta not configured (no app id/secret) -> 503 INSTAGRAM_LOOKUP_UNAVAILABLE, never a mock creator")
    void lookup_metaUnconfigured_returns503() {
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.lookup(principal, "foodie.mumbai"));

        assertEquals("INSTAGRAM_LOOKUP_UNAVAILABLE", ex.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        verify(instagramInsightsClient, never()).businessDiscovery(anyString(), anyString(), anyString());
        verify(externalCreatorRepository, never()).save(any());
    }

    /** Configures Meta as on and gives {@code resolveBusinessDiscoveryCaller} an Influora-owned
     * system caller (Q1.3), so a lookup test never needs to also mock the MetaOAuthToken/
     * MetaTokenStorage brand- or creator-token chains. */
    private void configureMetaOnWithSystemCaller() {
        metaApiProperties.setAppId("test-app-id");
        metaApiProperties.setAppSecret("test-app-secret");
        metaApiProperties.setSystemIgUserId("17841400000099999");
        metaApiProperties.setSystemIgAccessToken("system-caller-token");
    }

    @Test
    @DisplayName(
            "lookup: Business Discovery happy path — a real business_discovery response is parsed"
                    + " and persisted (Q1.1)")
    void lookup_happyPath_parsesAndPersistsBusinessDiscoveryResponse() {
        configureMetaOnWithSystemCaller();
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(externalCreatorRepository.findByIgAccountId("17841400000000123")).thenReturn(Optional.empty());
        when(externalCreatorRepository.findByIgUsername("foodie.mumbai")).thenReturn(Optional.empty());
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(eq(WORKSPACE_ID), anyString()))
                .thenReturn(Optional.empty());

        BusinessDiscoveryResponse.BusinessDiscovery bd =
                new BusinessDiscoveryResponse.BusinessDiscovery(
                        "17841400000000123",
                        "foodie.mumbai",
                        "Foodie Mumbai",
                        "Street food across the city.",
                        "https://cdn.example/pic.jpg",
                        42000L,
                        120L);
        when(instagramInsightsClient.businessDiscovery(
                        "17841400000099999", "foodie.mumbai", "system-caller-token"))
                .thenReturn(new BusinessDiscoveryResponse("17841400000099999", bd));

        ExternalCreatorResponse response = service.lookup(principal, "foodie.mumbai");

        assertEquals("foodie.mumbai", response.igUsername());
        assertEquals(42000L, response.followers());
        assertEquals(120L, response.mediaCount());
        assertEquals("UNVERIFIED", response.status());

        ArgumentCaptor<ExternalCreator> captor = ArgumentCaptor.forClass(ExternalCreator.class);
        verify(externalCreatorRepository).saveAndFlush(captor.capture());
        ExternalCreator saved = captor.getValue();
        assertEquals("17841400000000123", saved.getIgAccountId());
        assertEquals(42000L, saved.getFollowers());
        assertEquals(120L, saved.getMediaCount());
        assertEquals(ExternalCreatorSource.BUSINESS_DISCOVERY, saved.getSource());
    }

    @Test
    @DisplayName(
            "lookup: handle renamed on Instagram — matches by ig_account_id and reconciles the new"
                    + " username instead of 409ing forever (Q1.4)")
    void lookup_handleRenamed_reconcilesUsernameByAccountId() {
        configureMetaOnWithSystemCaller();
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);

        ExternalCreator existing =
                ExternalCreator.builder()
                        .id(EXTERNAL_CREATOR_ID)
                        .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                        .igAccountId("17841400000000123")
                        .igUsername("old.handle")
                        .build();
        when(externalCreatorRepository.findByIgAccountId("17841400000000123")).thenReturn(Optional.of(existing));
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(eq(WORKSPACE_ID), anyString()))
                .thenReturn(Optional.empty());

        BusinessDiscoveryResponse.BusinessDiscovery bd =
                new BusinessDiscoveryResponse.BusinessDiscovery(
                        "17841400000000123", "new.handle", "New Handle", null, null, 500L, 10L);
        when(instagramInsightsClient.businessDiscovery(anyString(), eq("new.handle"), anyString()))
                .thenReturn(new BusinessDiscoveryResponse("caller", bd));

        ExternalCreatorResponse response = service.lookup(principal, "new.handle");

        assertEquals(EXTERNAL_CREATOR_ID, response.id());
        verify(externalCreatorRepository).renameIgUsername(EXTERNAL_CREATOR_ID, "new.handle");
        // The row was resolved by account id — the username-only lookup must never be consulted.
        verify(externalCreatorRepository, never()).findByIgUsername(anyString());
        // Priya (2026-09-03 rejection): the previous pin asserted ONLY that renameIgUsername was
        // called and never checked what the request actually got back — the bug was that the
        // response (and the managed entity itself) kept serving the stale pre-rename username
        // even though the bulk-update UPDATE had already committed. Both must now read the NEW
        // handle.
        assertEquals(
                "new.handle",
                response.igUsername(),
                "the response must carry the NEW handle, not stale in-memory state left over from"
                        + " before the rename bulk-update committed in its own transaction");
        assertEquals(
                "new.handle",
                existing.getIgUsername(),
                "the managed entity itself must be reconciled so this request's own transaction"
                        + " commit does not flush the stale username and silently revert the"
                        + " rename that already committed");
    }

    @Test
    @DisplayName(
            "lookup: rename collides with another row already holding the new username -> keeps the"
                    + " OLD username and still returns 200 (never a 409/500) (Q1.4 third path)")
    void lookup_renameCollision_keepsOldUsernameInsteadOfThrowing() {
        configureMetaOnWithSystemCaller();
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);

        ExternalCreator existing =
                ExternalCreator.builder()
                        .id(EXTERNAL_CREATOR_ID)
                        .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                        .igAccountId("17841400000000123")
                        .igUsername("old.handle")
                        .build();
        when(externalCreatorRepository.findByIgAccountId("17841400000000123")).thenReturn(Optional.of(existing));
        // renameIgUsername raced against/collided with another row (e.g. an ADMIN_IMPORT stub
        // already holding "new.handle") — uk_external_creators_username rejects the bulk update.
        when(externalCreatorRepository.renameIgUsername(EXTERNAL_CREATOR_ID, "new.handle"))
                .thenThrow(new DataIntegrityViolationException("uk_external_creators_username"));
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(eq(WORKSPACE_ID), anyString()))
                .thenReturn(Optional.empty());

        BusinessDiscoveryResponse.BusinessDiscovery bd =
                new BusinessDiscoveryResponse.BusinessDiscovery(
                        "17841400000000123", "new.handle", "New Handle", null, null, 500L, 10L);
        when(instagramInsightsClient.businessDiscovery(anyString(), eq("new.handle"), anyString()))
                .thenReturn(new BusinessDiscoveryResponse("caller", bd));

        // Must return normally (200), NOT throw — the collision is caught, logged, and the row
        // resolved by ig_account_id is returned as-is with its OLD username, never a 409/500.
        ExternalCreatorResponse response = service.lookup(principal, "new.handle");

        assertEquals(EXTERNAL_CREATOR_ID, response.id());
        assertEquals(
                "old.handle",
                response.igUsername(),
                "a rename collision must keep the row's existing username — it remains lookupable"
                        + " by ig_account_id, which is how this same call just resolved it");
    }

    @Test
    @DisplayName(
            "lookup: concurrent first-lookup race on a brand-new handle -> the recovery re-read runs"
                    + " in its own REQUIRES_NEW transaction (not lookup()'s own REPEATABLE READ"
                    + " snapshot) and returns the winning row instead of a 409 (Q1.4)")
    void lookup_concurrentRace_returnsWinningRowInsteadOf409() {
        configureMetaOnWithSystemCaller();
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(externalCreatorRepository.findByIgUsername("brandnew.handle")).thenReturn(Optional.empty());
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(eq(WORKSPACE_ID), anyString()))
                .thenReturn(Optional.empty());

        ExternalCreator winner =
                ExternalCreator.builder()
                        .id("01HWINNER0000000002")
                        .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                        .igAccountId("17841400000000999")
                        .igUsername("brandnew.handle")
                        .build();
        when(externalCreatorRepository.saveAndFlush(any(ExternalCreator.class)))
                .thenThrow(new DataIntegrityViolationException("uk_external_creators_username"));
        // Scenario: this caller's own snapshot never sees a row (first resolve, empty), it loses
        // the race to insert (saveAndFlush throws above), then the recovery re-read finds the row
        // the winner committed meanwhile (second call, winner). Q1.4 (2026-09-03 pin) — this
        // ordered stub alone is NOT the proof the fix works: it would pass identically whether the
        // recovery re-read runs in a fresh REQUIRES_NEW transaction or reuses lookup()'s own
        // REPEATABLE READ snapshot (a Mockito mock cannot express MySQL snapshot visibility), which
        // is exactly how the pre-fix code's javadoc "assumed away" this question. The
        // transaction-boundary assertions below are the actual pin: they fail if the recovery
        // re-read stops asking transactionManager for a fresh transaction.
        when(externalCreatorRepository.findByIgAccountId("17841400000000999"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        BusinessDiscoveryResponse.BusinessDiscovery bd =
                new BusinessDiscoveryResponse.BusinessDiscovery(
                        "17841400000000999", "brandnew.handle", null, null, null, null, null);
        when(instagramInsightsClient.businessDiscovery(anyString(), eq("brandnew.handle"), anyString()))
                .thenReturn(new BusinessDiscoveryResponse("caller", bd));

        ExternalCreatorResponse response = service.lookup(principal, "brandnew.handle");

        assertEquals("01HWINNER0000000002", response.id());
        assertEquals("brandnew.handle", response.igUsername());

        // Transaction-boundary assertion: the recovery re-read must open a SECOND, independent
        // transaction (runInNewTransaction/REQUIRES_NEW) — one for the failed save attempt, one
        // for the recovery re-read — never reuse the failed save's transaction, and never run with
        // zero transactionManager involvement at all (a plain, unwrapped re-read on lookup()'s own
        // ambient @Transactional would call transactionManager.getTransaction() exactly ONCE,
        // for the save attempt only, and this assertion would fail).
        verify(transactionManager, times(2)).getTransaction(any());
        InOrder inOrder = inOrder(transactionManager, externalCreatorRepository);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(externalCreatorRepository).saveAndFlush(any(ExternalCreator.class));
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(externalCreatorRepository).findByIgAccountId("17841400000000999");
    }

    @Test
    @DisplayName(
            "connect: concurrent race on save -> re-reads and returns the winning request as the"
                    + " idempotent 200 the contract specifies, not a 409 (Q3.4)")
    void connect_concurrentRace_returnsWinningRequestInsteadOf409() {
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(unverifiedCreator()));

        CreatorConnectionRequest winning =
                CreatorConnectionRequest.builder()
                        .id("01HWINNER0000000001")
                        .workspaceId(WORKSPACE_ID)
                        .requestedByUserId("01HOTHERUSER00000001")
                        .externalCreatorId(EXTERNAL_CREATOR_ID)
                        .message("someone else got there first")
                        .build();
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(WORKSPACE_ID, EXTERNAL_CREATOR_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winning));
        when(connectionRequestRepository.saveAndFlush(any(CreatorConnectionRequest.class)))
                .thenThrow(new DataIntegrityViolationException("uk_ccr_workspace_creator"));

        ConnectionRequestResponse response = service.connect(principal, EXTERNAL_CREATOR_ID, "my message");

        assertEquals("01HWINNER0000000001", response.id());
        assertEquals("PENDING", response.status());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("connect: per-workspace daily cap on new/reopened requests -> 429, no save (Q3.5)")
    void connect_dailyCapExceeded_throws429() {
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(unverifiedCreator()));
        when(connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(WORKSPACE_ID, EXTERNAL_CREATOR_ID))
                .thenReturn(Optional.empty());

        java.util.List<CreatorConnectionRequest> fiftyRecentRequests = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fiftyRecentRequests.add(
                    CreatorConnectionRequest.builder()
                            .id("01HREQ" + i)
                            .workspaceId(WORKSPACE_ID)
                            .requestedByUserId(USER_ID)
                            .externalCreatorId("01HOTHERCREATOR" + i)
                            .build());
        }
        when(connectionRequestRepository.findByWorkspaceIdOrderByCreatedAtDesc(WORKSPACE_ID))
                .thenReturn(fiftyRecentRequests);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.connect(principal, EXTERNAL_CREATOR_ID, "one too many"));

        assertEquals("CREATOR_CONNECT_RATE_LIMITED", ex.getCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        verify(connectionRequestRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
