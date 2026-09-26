package com.influora.service.credits;

import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.creatorcopilot.CreatorMetaConnectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.2, §6) — grants the welcome 40 the moment a creator's Meta
 * connect actually lands, instead of waiting for the {@link CreatorCreditService#charge} backstop
 * to notice on her next turn. {@code @Async @TransactionalEventListener(AFTER_COMMIT)}, NOT a
 * plain {@code @EventListener} — {@code CreatorMetaOAuthService#connect} is {@code @Transactional},
 * so the token write must be durably committed before this ever runs (same pattern as {@code
 * CreatorCaptionSyncJob}'s listener on the same event).
 */
@Component
public class CreatorCreditGrantListener {

    private static final Logger log = LoggerFactory.getLogger(CreatorCreditGrantListener.class);

    private final CreatorCreditService creditService;
    private final CreatorProfileRepository creatorProfileRepository;

    public CreatorCreditGrantListener(
            CreatorCreditService creditService, CreatorProfileRepository creatorProfileRepository) {
        this.creditService = creditService;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreatorMetaConnected(CreatorMetaConnectedEvent event) {
        try {
            CreatorProfile profile = creatorProfileRepository.findById(event.creatorProfileId()).orElse(null);
            if (profile == null) {
                return;
            }
            creditService.grantWelcome(profile.getUserId());
        } catch (Exception e) {
            // Never let a grant failure surface anywhere near the connect flow — the charge()
            // backstop (SPEC.md §6) retries the same eligibility check on the creator's next turn.
            log.warn(
                    "CreatorCreditGrantListener: welcome-grant attempt failed for creatorProfileId={}"
                            + " — the charge() backstop will retry",
                    event.creatorProfileId(),
                    e);
        }
    }
}
