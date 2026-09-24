package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorAccountInsight;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.AccountInsightsResponse;
import com.influora.integration.meta.dto.AccountInsightsResponse.Metric;
import com.influora.integration.meta.dto.AccountInsightsResponse.TotalValue;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.CreatorAccountInsightRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import com.influora.service.creatorcopilot.CreatorMetaConnectedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Account insights (2026-09-24): the daily fetch, the 28-day window, and the fetch on connect. */
@ExtendWith(MockitoExtension.class)
class AccountInsightsJobTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR000000001";
    private static final String TOKEN = "valid-access-token";
    private static final String IG_ID = "17841400000000021";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // 24 Sep 2026, 07:30 IST.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC);

    @Mock private MetaOAuthTokenRepository tokenRepository;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private InstagramInsightsClient instagramClient;
    @Mock private CreatorAccountInsightRepository insightRepository;
    @Mock private MetaRateLimitTracker rateLimitTracker;
    @Mock private AuditLogService auditLog;

    private AccountInsightsJob job;

    @BeforeEach
    void setUp() {
        job = new AccountInsightsJob(
                tokenRepository, tokenStorage, instagramClient, insightRepository, rateLimitTracker, auditLog, CLOCK);
        lenient().when(tokenStorage.getCreatorAuthPath(CREATOR_ID)).thenReturn(Optional.of(MetaAuthPath.FACEBOOK_LOGIN));
    }

    private static AccountInsightsResponse response(Long reach, Long views, Long interactions, Long engaged, Long taps) {
        return new AccountInsightsResponse(List.of(
                new Metric("reach", "day", reach == null ? null : new TotalValue(reach)),
                new Metric("views", "day", views == null ? null : new TotalValue(views)),
                new Metric("total_interactions", "day", interactions == null ? null : new TotalValue(interactions)),
                new Metric("accounts_engaged", "day", engaged == null ? null : new TotalValue(engaged)),
                new Metric("profile_links_taps", "day", taps == null ? null : new TotalValue(taps))));
    }

    private void liveCreator() {
        MetaOAuthToken token = token();
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class))).thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_ID)).thenReturn(10);
    }

    @Test
    @DisplayName("asks Meta for the last 28 FULL IST days (27 Aug to 23 Sep) and saves each number")
    void fetchesTheLast28FullDaysAndSavesThem() {
        liveCreator();
        long since = LocalDate.of(2026, 8, 27).atStartOfDay(IST).toEpochSecond();
        long until = LocalDate.of(2026, 9, 24).atStartOfDay(IST).toEpochSecond();
        when(instagramClient.getAccountInsights(IG_ID, TOKEN, since, until, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(response(12400L, 48210L, 1930L, 822L, 64L));

        job.pollAccountInsights();

        ArgumentCaptor<CreatorAccountInsight> saved = ArgumentCaptor.forClass(CreatorAccountInsight.class);
        verify(insightRepository).save(saved.capture());
        CreatorAccountInsight row = saved.getValue();
        assertEquals(CREATOR_ID, row.getCreatorProfileId());
        assertEquals(LocalDate.of(2026, 8, 27), row.getPeriodStart());
        assertEquals(LocalDate.of(2026, 9, 23), row.getPeriodEnd());
        assertEquals(12400L, row.getReach());
        assertEquals(48210L, row.getViews());
        assertEquals(1930L, row.getTotalInteractions());
        assertEquals(822L, row.getAccountsEngaged());
        assertEquals(64L, row.getProfileLinksTaps());
        assertEquals(until - since, 28L * 24 * 3600, "exactly 28 days, inside Meta's 30-day cap");
    }

    @Test
    @DisplayName("a number Meta did not send is saved as null, never 0")
    void missingNumberStaysNull() {
        liveCreator();
        when(instagramClient.getAccountInsights(eq(IG_ID), eq(TOKEN), anyLong(), anyLong(), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(response(900L, null, null, null, null));

        job.pollAccountInsights();

        ArgumentCaptor<CreatorAccountInsight> saved = ArgumentCaptor.forClass(CreatorAccountInsight.class);
        verify(insightRepository).save(saved.capture());
        assertEquals(900L, saved.getValue().getReach());
        assertNull(saved.getValue().getViews());
    }

    @Test
    @DisplayName("no numbers at all: nothing saved (the last good snapshot stays current)")
    void emptyResponseSavesNothing() {
        liveCreator();
        when(instagramClient.getAccountInsights(eq(IG_ID), eq(TOKEN), anyLong(), anyLong(), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(new AccountInsightsResponse(List.of()));

        job.pollAccountInsights();

        verify(insightRepository, never()).save(any());
    }

    @Test
    @DisplayName("a rate limit marks the tracker and skips; the run carries on")
    void rateLimitIsHandled() {
        liveCreator();
        when(instagramClient.getAccountInsights(eq(IG_ID), eq(TOKEN), anyLong(), anyLong(), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenThrow(new MetaRateLimitException("slow down"));

        job.pollAccountInsights();

        verify(rateLimitTracker).markLimited(IG_ID);
        verify(insightRepository, never()).save(any());
    }

    @Test
    @DisplayName("connecting Instagram fetches that creator's numbers at once")
    void connectTriggersAFetch() {
        MetaOAuthToken token = token();
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_ID))
                .thenReturn(Optional.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_ID)).thenReturn(10);
        when(instagramClient.getAccountInsights(eq(IG_ID), eq(TOKEN), anyLong(), anyLong(), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(response(10L, 20L, 3L, 2L, 1L));

        job.onCreatorConnected(new CreatorMetaConnectedEvent(CREATOR_ID));

        verify(insightRepository).save(any(CreatorAccountInsight.class));
    }

    @Test
    @DisplayName("connect with no live token: no call, no save, no exception")
    void connectWithoutTokenIsANoOp() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_ID))
                .thenReturn(Optional.empty());

        job.onCreatorConnected(new CreatorMetaConnectedEvent(CREATOR_ID));

        verify(instagramClient, never()).getAccountInsights(anyString(), anyString(), anyLong(), anyLong(), any());
        verify(insightRepository, never()).save(any());
    }

    private static MetaOAuthToken token() {
        return new MetaOAuthToken() {
            @Override
            public String getCreatorProfileId() {
                return CREATOR_ID;
            }

            @Override
            public String getIgBusinessAccountId() {
                return IG_ID;
            }

            @Override
            public Instant getExpiresAt() {
                return Instant.parse("2026-12-01T00:00:00Z");
            }

            @Override
            public boolean isRevoked() {
                return false;
            }
        };
    }
}
