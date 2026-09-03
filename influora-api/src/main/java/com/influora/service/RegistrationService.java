package com.influora.service;

import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.enums.ExternalCreatorStatus;
import com.influora.repository.ExternalCreatorRepository;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — server-side consumption of the signed creator-invite
 * token issued by {@code AdminCreatorConnectionService#sendJoinInvitationEmail} /
 * {@link InviteTokenService#issue}. This is the piece that was missing entirely: {@code
 * signup_url} carried a bare {@code ?ref=influora-invite&handle={igUsername}} that {@code
 * creator-register.tsx} never read, so the JOINED flip for an invited creator depended entirely
 * on them separately connecting Meta later.
 *
 * <p><b>Wiring:</b> {@code AuthService#creatorRegister} calls {@link #consumeInviteToken} after
 * the new {@link com.influora.domain.entity.CreatorProfile} is created and its id is known,
 * passing {@code CreatorRegisterRequest#inviteToken()} (the value {@code creator-register.tsx}
 * reads out of the {@code invite_token} query param on the {@code signup_url} this class's
 * javadoc references and forwards on the register call). See {@code AuthController.java:94-98}
 * for the HTTP entry point.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final InviteTokenService inviteTokenService;
    private final ExternalCreatorRepository externalCreatorRepository;
    private final ExternalCreatorLinkService externalCreatorLinkService;

    public RegistrationService(
            InviteTokenService inviteTokenService,
            ExternalCreatorRepository externalCreatorRepository,
            ExternalCreatorLinkService externalCreatorLinkService) {
        this.inviteTokenService = inviteTokenService;
        this.externalCreatorRepository = externalCreatorRepository;
        this.externalCreatorLinkService = externalCreatorLinkService;
    }

    /**
     * Verifies {@code token} (signature + expiry via {@link InviteTokenService#verify}), then
     * re-checks it against the LIVE {@link ExternalCreator} row: still {@code INVITED} (not
     * already {@code JOINED} — a token cannot re-link a row a second time) and {@code invitedAt}
     * still matches exactly what was on the row when the token was issued (a re-invite re-stamps
     * {@code invitedAt}, silently invalidating every token from a prior invite). Only once both
     * hold does it call {@link ExternalCreatorLinkService#linkViaVerifiedInvite}.
     *
     * <p>Never throws — best-effort exactly like every other JOINED-hook call site in this
     * feature; a bad/expired/already-consumed token must never fail creator registration itself.
     * Returns whether the link was actually applied, purely for the caller's own logging/metrics.
     */
    public boolean consumeInviteToken(String token, String creatorProfileId) {
        if (token == null || token.isBlank() || creatorProfileId == null || creatorProfileId.isBlank()) {
            return false;
        }
        try {
            Optional<InviteTokenService.Parsed> parsed = inviteTokenService.verify(token);
            if (parsed.isEmpty()) {
                log.info("consumeInviteToken: invalid or expired token for creatorProfileId={}", creatorProfileId);
                return false;
            }

            Optional<ExternalCreator> externalOpt =
                    externalCreatorRepository.findById(parsed.get().externalCreatorId());
            if (externalOpt.isEmpty()) {
                return false;
            }
            ExternalCreator external = externalOpt.get();

            if (external.getStatus() != ExternalCreatorStatus.INVITED) {
                // Already JOINED (single-use — token already consumed, or the creator connected
                // Meta and the ordinary hook already linked it) or never invited at all.
                log.info(
                        "consumeInviteToken: externalCreatorId={} is not INVITED (status={}) —"
                                + " token not consumed",
                        external.getId(),
                        external.getStatus());
                return false;
            }
            // Q5.5 precision fix: compare truncated to whole SECONDS on both sides. The token
            // embeds invitedAt at second precision (InviteTokenService#issue javadoc — the
            // invited_at column is a MySQL TIMESTAMP with fsp 0), but the live row's Instant can
            // still carry a sub-second component depending on the DB/test config (e.g. H2's
            // MODE=MySQL default fsp), so comparing raw Instants would reject a legitimately
            // just-issued token as if it had been superseded by a re-invite.
            boolean invitedAtMatches =
                    external.getInvitedAt() != null
                            && external
                                    .getInvitedAt()
                                    .truncatedTo(ChronoUnit.SECONDS)
                                    .equals(parsed.get().invitedAt().truncatedTo(ChronoUnit.SECONDS));
            if (!invitedAtMatches) {
                // A re-invite since this token was issued re-stamped invitedAt — stale token.
                log.info(
                        "consumeInviteToken: externalCreatorId={} invitedAt no longer matches the"
                                + " token (superseded by a re-invite) — token not consumed",
                        external.getId());
                return false;
            }

            externalCreatorLinkService.linkViaVerifiedInvite(external.getId(), creatorProfileId);
            return true;
        } catch (Exception e) {
            log.error(
                    "consumeInviteToken failed for creatorProfileId={} (swallowed — registration"
                            + " itself must never fail over this best-effort link)",
                    creatorProfileId,
                    e);
            return false;
        }
    }
}
