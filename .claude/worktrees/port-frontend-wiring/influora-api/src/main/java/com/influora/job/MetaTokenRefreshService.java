package com.influora.job;

import com.influora.common.JsonLists;
import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.service.AuditLogService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background refresh of long-lived Meta tokens before they expire (Wave B task B2,
 * REMAINING_WORK_PLAN.md). Mirrors {@link MetricsPollingJob}'s conventions: a single-threaded
 * scheduled sweep, an {@link AtomicBoolean} overlap guard, and per-token error isolation so one
 * creator's failed refresh never aborts the batch.
 *
 * <p>Uses {@link MetaTokenStorage#findTokensExpiringSoon(int)} — already wired system-wide (not
 * workspace-scoped by design, same as {@code MetricsPollingJob}, per that method's own javadoc) —
 * to find tokens within {@code influora.meta.token-refresh-days-before-expiry} days of expiry.
 *
 * <p>[SEC: Kabir sign-off gate] Never logs a token value. The decrypted current token only ever
 * lives in a local variable passed straight into {@link MetaOAuthService#refreshLongLivedToken},
 * and the refreshed token is immediately re-encrypted via {@link MetaTokenStorage#storeToken} —
 * the same encrypted-storage seam every other write path in the codebase uses. No new plaintext
 * persistence or logging surface is introduced.
 */
@Component
public class MetaTokenRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MetaTokenRefreshService.class);

    private final MetaTokenStorage tokenStorage;
    private final MetaOAuthService oAuthService;
    private final MetaApiProperties props;
    private final AuditLogService auditLog;

    /** In-memory overlap guard — same per-instance style as {@code MetricsPollingJob.running}. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MetaTokenRefreshService(
            MetaTokenStorage tokenStorage,
            MetaOAuthService oAuthService,
            MetaApiProperties props,
            AuditLogService auditLog) {
        this.tokenStorage = tokenStorage;
        this.oAuthService = oAuthService;
        this.props = props;
        this.auditLog = auditLog;
    }

    /** Once a day — refresh cadence only needs to keep ahead of the expiry threshold, not the 6h metrics poll. */
    @Scheduled(cron = "0 30 2 * * *")
    public void refreshExpiringTokens() {
        if (!running.compareAndSet(false, true)) {
            log.warn("MetaTokenRefreshService: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runRefresh();
        } finally {
            running.set(false);
        }
    }

    private void runRefresh() {
        List<MetaOAuthToken> expiringSoon =
                tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry());

        int refreshed = 0;
        int failed = 0;

        for (MetaOAuthToken tokenRow : expiringSoon) {
            String workspaceId = tokenRow.getWorkspaceId();
            String creatorProfileId = tokenRow.getCreatorProfileId();

            try {
                if (refreshOne(workspaceId, creatorProfileId, tokenRow.getGrantedScopesJson())) {
                    refreshed++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                // Defensive catch-all so one creator's unexpected refresh failure never aborts the batch.
                failed++;
                log.error(
                        "MetaTokenRefreshService: unexpected failure refreshing token for creator {}",
                        creatorProfileId,
                        e);
            }
        }

        auditLog.recordToolCall(
                null,
                "META_TOKEN_REFRESH_SWEEP_COMPLETED",
                "SYSTEM",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of(
                        "tokensRefreshed", refreshed,
                        "tokensFailed", failed,
                        "totalExpiringSoon", expiringSoon.size()));
    }

    /** @return true if the token was successfully refreshed and re-persisted. */
    private boolean refreshOne(String workspaceId, String creatorProfileId, String grantedScopesJson) {
        Optional<String> currentToken = tokenStorage.getValidToken(workspaceId, creatorProfileId);
        if (currentToken.isEmpty()) {
            // Already expired/revoked by the time the sweep got to it — nothing to refresh;
            // StaleTokenCleanupJob is responsible for that state, not this service.
            log.warn(
                    "MetaTokenRefreshService: no valid token to refresh for creator {}, skipping",
                    creatorProfileId);
            return false;
        }

        try {
            MetaTokenResponse refreshed = oAuthService.refreshLongLivedToken(currentToken.get());
            if (refreshed == null || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
                log.error(
                        "MetaTokenRefreshService: Meta returned an empty refreshed token for creator {}",
                        creatorProfileId);
                return false;
            }

            Instant newExpiresAt = computeExpiresAt(refreshed.expiresInSeconds());
            List<String> grantedScopes = JsonLists.stringListFromJson(grantedScopesJson);

            tokenStorage.storeToken(
                    creatorProfileId, workspaceId, refreshed.accessToken(), newExpiresAt, grantedScopes);

            return true;
        } catch (MetaApiException e) {
            // Never log e's message body here beyond what MetaOAuthService/MetaGraphApiClient
            // already redact — no token value ever reaches this class's own log lines.
            log.error(
                    "MetaTokenRefreshService: Meta refresh call failed for creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
            return false;
        }
    }

    /** Meta's long-lived tokens are typically ~60 days; fall back to the current ~60-day default if absent. */
    private Instant computeExpiresAt(Long expiresInSeconds) {
        long seconds = (expiresInSeconds == null || expiresInSeconds <= 0) ? 60L * 24 * 60 * 60 : expiresInSeconds;
        return Instant.now().plusSeconds(seconds);
    }
}
