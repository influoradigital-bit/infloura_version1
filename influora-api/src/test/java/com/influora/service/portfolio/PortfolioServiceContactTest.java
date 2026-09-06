package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.PortfolioEventRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.CreatorContextService;
import com.influora.service.CreatorProfileService;
import com.influora.service.ExternalCreatorLinkService;
import com.influora.service.notification.event.PortfolioContactEvent;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.NoOpMalwareScanService;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioContactResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * T-FESTIVALBOX-0905 phase 9 [Kabir F-3] — {@code PortfolioService#contact} was an unauthenticated
 * mail-injection amplifier: no rate limit, no honeypot, no IP tracking, not even a persisted row,
 * and every accepted POST emailed a real creator with attacker-controlled name/reply-to/body. This
 * class proves the two-part fix: (1) a per-recipient-creator {@link AbuseThrottleService} cap
 * refuses a request once the budget is exhausted, and (2) the submitter's email address no longer
 * reaches the application log (it used to, via {@code log.info(...from sender={}..., email)}).
 *
 * <p>The edge {@code AuthRateLimitFilter} "portfolio-contact" bucket is the other half of this fix
 * and is covered separately in {@code AuthRateLimitFilterTrackingBucketTest}-adjacent filter tests
 * — this class only exercises the service-level, per-recipient defense that a rotating-IP caller
 * cannot evade.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceContactTest {

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String USERNAME = "riya";

    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorProfileService creatorProfileService;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AudienceDemographicsRepository audienceDemographicsRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private R2StorageService r2StorageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private UserRepository userRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private PortfolioEventRepository portfolioEventRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private MetaTokenStorage metaTokenStorage;
    @Mock private InstagramInsightsClient instagramInsightsClient;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private ExternalCreatorLinkService externalCreatorLinkService;
    @Mock private AbuseThrottleService abuseThrottleService;

    private PortfolioService service;
    private CreatorProfile profile;

    private Logger portfolioServiceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        service =
                new PortfolioService(
                        creatorContext,
                        creatorProfileService,
                        creatorProfileRepository,
                        platformStatRepository,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        audienceDemographicsRepository,
                        reviewRepository,
                        r2StorageService,
                        new R2Properties(),
                        new NoOpMalwareScanService(),
                        eventPublisher,
                        userRepository,
                        deliverableRepository,
                        portfolioEventRepository,
                        metaOAuthTokenRepository,
                        metaTokenStorage,
                        instagramInsightsClient,
                        creatorMetricsRepository,
                        externalCreatorLinkService,
                        abuseThrottleService);

        // CreatorProfile.newForUser defaults discoverable=true, which is required for
        // requireDiscoverablePortfolio to let contact() proceed at all.
        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Riya Sharma");
        profile.applyUsername(USERNAME);
        when(creatorProfileService.requireProfileByUsername(USERNAME)).thenReturn(profile);

        lenient()
                .when(abuseThrottleService.tryConsume(anyString(), any(Duration.class), anyLong()))
                .thenReturn(true);

        portfolioServiceLogger = (Logger) LoggerFactory.getLogger(PortfolioService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        portfolioServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        portfolioServiceLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("a valid contact consumes the per-recipient-creator throttle budget and publishes")
    void validContact_consumesThrottleBudgetAndPublishes() {
        PortfolioContactResponse response =
                service.contact(USERNAME, "Anita Desai", "anita@rangoli.in", "Interested in a collab.");

        assertTrue(response.delivered());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(PortfolioContactEvent.class));
        // Keyed by the RECIPIENT creator, not the sender — the whole point is that this cap holds
        // no matter how many different IPs/senders target the SAME creator.
        verify(abuseThrottleService)
                .tryConsume(eq("portfolio-contact:" + PROFILE_ID), any(Duration.class), anyLong());
    }

    @Test
    @DisplayName(
            "F-3: once AbuseThrottleService reports the per-creator budget exhausted, contact is"
                    + " refused with 429 and NO event is published — this is what makes a rotating-IP"
                    + " caller (which defeats the edge per-IP bucket) still bounded")
    void throttledContact_refusedWithoutPublishing() {
        when(abuseThrottleService.tryConsume(
                        eq("portfolio-contact:" + PROFILE_ID), any(Duration.class), anyLong()))
                .thenReturn(false);

        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.contact(
                                        USERNAME, "Attacker", "attacker@evil.example", "spam body"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName(
            "F-3: the submitter's email address never reaches the application log, on either the"
                    + " success or the throttled path")
    void emailNeverReachesLogs_successPath() {
        String secretEmail = "reallyshouldnotbelogged@attacker.example";
        service.contact(USERNAME, "Anita Desai", secretEmail, "Interested in a collab.");

        List<ILoggingEvent> leaking =
                logAppender.list.stream()
                        .filter(evt -> evt.getFormattedMessage().contains(secretEmail))
                        .toList();
        assertTrue(
                leaking.isEmpty(),
                "the submitter's email must never appear in an application log line, but found: "
                        + leaking.stream().map(ILoggingEvent::getFormattedMessage).toList());

        // Sanity: the success path does log SOMETHING identifying the delivery (by profile id).
        boolean deliveredLogPresent =
                logAppender.list.stream()
                        .anyMatch(
                                evt ->
                                        evt.getLevel() == Level.INFO
                                                && evt.getFormattedMessage().contains("Portfolio contact delivered")
                                                && evt.getFormattedMessage().contains(PROFILE_ID));
        assertTrue(deliveredLogPresent, "expected an INFO log naming the recipient profile id");
    }

    @Test
    @DisplayName("F-3: the submitter's email does not leak into the log even on the throttled path")
    void emailNeverReachesLogs_throttledPath() {
        when(abuseThrottleService.tryConsume(
                        eq("portfolio-contact:" + PROFILE_ID), any(Duration.class), anyLong()))
                .thenReturn(false);
        String secretEmail = "reallyshouldnotbelogged2@attacker.example";

        assertThrows(
                ApiException.class,
                () -> service.contact(USERNAME, "Attacker", secretEmail, "spam body"));

        boolean leaked =
                logAppender.list.stream()
                        .anyMatch(evt -> evt.getFormattedMessage().contains(secretEmail));
        assertFalse(leaked, "the submitter's email must not appear in the log even when throttled");
    }
}
