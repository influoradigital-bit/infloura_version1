package com.influora.service.creatorcopilot;

import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.MetaOAuthTokenRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Which Instagram account a creator profile is connected to RIGHT NOW.
 *
 * <p>A creator can disconnect and connect a different Instagram account whenever they like, and
 * before V20260924120000 nothing recorded which account a stored metric came from. Observed live
 * 2026-09-24 on one profile: 57 follower snapshots from {@code @sage_digitalworld}, 20 from
 * {@code @snapsby_ugc} and 8 from {@code @influora.io}, with every reader taking "all rows for this
 * profile" — so Meera's recent posts, her quality score, the 90-day posting pattern behind "best
 * time to post" and the brand-facing performance panel were each built from three accounts at once,
 * under the newest account's follower count.
 *
 * <p>Returns empty when there is no live connection (never connected, revoked, or expired). Callers
 * treat that as "do not narrow": with no account to narrow TO, hiding everything would leave a
 * disconnected creator staring at an empty history rather than their own past data.
 */
@Service
public class ConnectedInstagramAccount {

    private final MetaOAuthTokenRepository tokenRepository;

    public ConnectedInstagramAccount(MetaOAuthTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /**
     * The {@code ig_business_account_id} of the creator's live connection — the same id
     * {@code MetricsPollingJob} polls and throttles on, and now stamps onto every row it writes.
     */
    public Optional<String> currentAccountId(String creatorProfileId) {
        return tokenRepository
                .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                .map(MetaOAuthToken::getIgBusinessAccountId)
                .filter(id -> id != null && !id.isBlank());
    }
}
