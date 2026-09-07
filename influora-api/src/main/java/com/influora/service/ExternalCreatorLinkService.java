package com.influora.service;

import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.enums.ConnectionRequestStatus;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.service.notification.event.ConnectedCreatorJoinedEvent;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The JOINED hook (T-CREATORCONNECT-0902, .proof-os/tasks/T-CREATORCONNECT-0902/TASKS.md §The
 * JOINED hook). Called whenever a real {@code CreatorProfile}'s Instagram identity becomes known
 * or changes:
 *
 * <ol>
 *   <li>{@code MetaTokenStorage#storeCreatorToken} (all three overloads funnel to the 7/8-arg
 *       body) — after the token row is saved, using the resolved IG business account id +
 *       username from the OAuth token exchange itself.
 *   <li>{@code PortfolioService#upsertPlatformStat} — Instagram handle AND {@code
 *       ig_account_id} written from a real, on-demand Meta Graph sync ({@code syncPlatforms}).
 * </ol>
 *
 * <p>Q5.4 (T-CREATORCONNECT-0902, Critical) — {@code CreatorProfileService#applyUsername} used to
 * be a third call site, feeding the unverified Influora vanity handle a creator can PATCH to
 * anything into this same matcher. That let any authenticated creator claim an Influora username
 * equal to a targeted external Instagram handle and get linked (and the brand emailed) with zero
 * proof of controlling the real Instagram account. It has been removed: this hook now fires ONLY
 * from sources that hold a verified Meta identity.
 *
 * <p>Match order: {@code ig_account_id} exact, else {@code ig_username} case-insensitive — but a
 * row with no existing link may only be linked for the FIRST time via the {@code ig_account_id}
 * branch (see the guard below); a username-only match can never itself establish a fresh link.
 * On a match, in one transaction: the {@link ExternalCreator} row flips to {@code JOINED} with
 * {@code linked_creator_profile_id} + {@code joined_at}; every still-open ({@code PENDING}/{@code
 * CONTACTED}) {@link CreatorConnectionRequest} against it flips to {@code JOINED} and publishes
 * its own {@link ConnectedCreatorJoinedEvent} (one per request — a creator can have been asked
 * for by more than one brand). No match, or no open requests, is a normal, silent no-op — this is
 * called from hot creator-facing paths and must never throw or fail the caller's own write.
 */
@Service
public class ExternalCreatorLinkService {

    private static final Logger log = LoggerFactory.getLogger(ExternalCreatorLinkService.class);

    /** F-0701 — the only platform an external_creators row can describe today. */
    private static final String INSTAGRAM = "INSTAGRAM";

    private final ExternalCreatorRepository externalCreatorRepository;
    private final CreatorConnectionRequestRepository connectionRequestRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    /** F-0701 — see {@link #adoptExternalPlatformStat}. */
    private final PlatformStatRepository platformStatRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ExternalCreatorLinkService(
            ExternalCreatorRepository externalCreatorRepository,
            CreatorConnectionRequestRepository connectionRequestRepository,
            CreatorProfileRepository creatorProfileRepository,
            PlatformStatRepository platformStatRepository,
            ApplicationEventPublisher eventPublisher) {
        this.externalCreatorRepository = externalCreatorRepository;
        this.connectionRequestRepository = connectionRequestRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.platformStatRepository = platformStatRepository;
        this.eventPublisher = eventPublisher;
    }

    // Q5.5 (T-CREATORCONNECT-0902) — REQUIRES_NEW, not the default propagation. A flush inside
    // this method (the repository read at connectionRequestRepository.findByExternalCreatorId...
    // below auto-flushes any pending writes) that hits a unique-constraint violation marks the
    // CURRENT persistence context rollback-only; if this method shared the caller's transaction, a
    // caught exception here would still make the caller's own @Transactional proxy throw
    // UnexpectedRollbackException at commit — silently breaking the promise in this class's own
    // javadoc that a best-effort cross-link never fails the caller's write (e.g. the Meta OAuth
    // callback would 500 even though the token save itself succeeded). Running in its own
    // transaction means a failure here can roll back ONLY this method's work.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCreatorIdentified(String creatorProfileId, String igUsername, String igAccountId) {
        if (creatorProfileId == null || creatorProfileId.isBlank()) {
            return;
        }
        boolean hasUsername = igUsername != null && !igUsername.isBlank();
        boolean hasAccountId = igAccountId != null && !igAccountId.isBlank();
        if (!hasUsername && !hasAccountId) {
            return;
        }

        try {
            Optional<ExternalCreator> match = Optional.empty();
            boolean matchedByAccountId = false;
            if (hasAccountId) {
                match = externalCreatorRepository.findByIgAccountId(igAccountId);
                matchedByAccountId = match.isPresent();
            }
            if (match.isEmpty() && hasUsername) {
                match =
                        externalCreatorRepository.findByIgUsernameIgnoreCase(
                                normalizeUsername(igUsername));
            }
            if (match.isEmpty()) {
                return;
            }

            ExternalCreator external = match.get();
            if (external.getLinkedCreatorProfileId() != null
                    && !external.getLinkedCreatorProfileId().equals(creatorProfileId)) {
                // Already linked to a DIFFERENT creator profile — a handle collision (e.g. the
                // external row was seeded from a handle later reused/reassigned on Instagram, or a
                // creator changed their handle after another profile already claimed the old
                // external row). Never silently reassign someone else's linked identity.
                log.warn(
                        "ExternalCreatorLinkService: externalCreatorId={} already linked to a"
                                + " different creatorProfileId={} — refusing to relink for {}",
                        external.getId(),
                        external.getLinkedCreatorProfileId(),
                        creatorProfileId);
                return;
            }
            // Q5.4 hardening (T-CREATORCONNECT-0902, Critical) — a row with NO existing link may
            // only be linked for the FIRST time via a confirmed ig_account_id match. ig_username
            // alone is the weaker, spoofable key (case-insensitive, and every legitimate caller
            // that only ever had a username has been removed from this hook — see
            // CreatorProfileService#applyUsername). Without this, any caller that ever passes a
            // caller-influenced username with no verified account id could land-grab a fresh
            // UNVERIFIED/INVITED row. Re-confirmations of an ALREADY-linked row (handled above)
            // are unaffected — only the first-ever link is gated.
            if (external.getLinkedCreatorProfileId() == null && !matchedByAccountId) {
                log.warn(
                        "ExternalCreatorLinkService: refusing to establish the first link for"
                                + " externalCreatorId={} via a username-only match (no verified"
                                + " ig_account_id supplied) — creatorProfileId={}",
                        external.getId(),
                        creatorProfileId);
                return;
            }
            if (hasAccountId) {
                external.applyIgAccountId(igAccountId);
            }
            finishLinking(external, creatorProfileId);
        } catch (Exception e) {
            // Never fail the caller's own write (token storage / profile save / portfolio sync)
            // over a best-effort cross-link — same discipline as AdminAuditLogService#record.
            log.error(
                    "ExternalCreatorLinkService.onCreatorIdentified failed for creatorProfileId={}"
                            + " (swallowed, caller's own write is unaffected)",
                    creatorProfileId,
                    e);
        }
    }

    /**
     * Q5.5 (T-CREATORCONNECT-0902, Medium) — direct link entry point for a creator who registered
     * through a signed, single-use invite token ({@code InviteTokenService} / {@code
     * RegistrationService}). The token itself IS the proof of identity here — a specific admin
     * invite to exactly this row, verified and consumed server-side, never a bare URL param — so
     * this deliberately bypasses {@link #onCreatorIdentified}'s username/{@code ig_account_id}
     * matcher entirely (that matcher exists for ambient Meta-sync/OAuth signals with no separate
     * proof of identity, which is exactly what Q5.4 hardened). It is therefore safe to call even
     * for a row {@code onCreatorIdentified} would otherwise refuse a first link on for lacking a
     * verified {@code ig_account_id} (e.g. an unenriched ADMIN_IMPORT stub) — {@code
     * RegistrationService} is responsible for having already verified the token and the row's
     * {@code INVITED} status before calling this.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkViaVerifiedInvite(String externalCreatorId, String creatorProfileId) {
        if (externalCreatorId == null
                || externalCreatorId.isBlank()
                || creatorProfileId == null
                || creatorProfileId.isBlank()) {
            return;
        }
        try {
            Optional<ExternalCreator> match = externalCreatorRepository.findById(externalCreatorId);
            if (match.isEmpty()) {
                return;
            }
            ExternalCreator external = match.get();
            if (external.getLinkedCreatorProfileId() != null
                    && !external.getLinkedCreatorProfileId().equals(creatorProfileId)) {
                log.warn(
                        "ExternalCreatorLinkService.linkViaVerifiedInvite: externalCreatorId={}"
                                + " already linked to a different creatorProfileId={} — refusing to"
                                + " relink for {}",
                        external.getId(),
                        external.getLinkedCreatorProfileId(),
                        creatorProfileId);
                return;
            }
            finishLinking(external, creatorProfileId);
        } catch (Exception e) {
            log.error(
                    "ExternalCreatorLinkService.linkViaVerifiedInvite failed for"
                            + " externalCreatorId={} creatorProfileId={} (swallowed, caller's own"
                            + " write is unaffected)",
                    externalCreatorId,
                    creatorProfileId,
                    e);
        }
    }

    /** Shared tail of both link entry points: flip the row JOINED, flip every open request, publish
     * one event each. Callers hold the try/catch — this never throws past a caller-visible failure
     * by design elsewhere, so it stays unguarded here. */
    private void finishLinking(ExternalCreator external, String creatorProfileId) {
        external.markJoined(creatorProfileId);
        externalCreatorRepository.save(external);

        // F-0701 — must run BEFORE the early return below. A creator can be linked with no open
        // connection request at all (an admin invite nobody had a PENDING request against), and
        // this method returns as soon as it finds none; putting the adoption after that point
        // would leave exactly those creators undiscoverable.
        adoptExternalPlatformStat(external, creatorProfileId);

        List<CreatorConnectionRequest> openRequests =
                connectionRequestRepository.findByExternalCreatorIdAndStatusIn(
                        external.getId(),
                        List.of(ConnectionRequestStatus.PENDING, ConnectionRequestStatus.CONTACTED));
        if (openRequests.isEmpty()) {
            return;
        }

        String creatorName =
                creatorProfileRepository
                        .findById(creatorProfileId)
                        .map(CreatorProfile::getDisplayName)
                        .orElse(external.getDisplayName());

        for (CreatorConnectionRequest request : openRequests) {
            request.markJoined();
            connectionRequestRepository.save(request);
            eventPublisher.publishEvent(
                    new ConnectedCreatorJoinedEvent(
                            request.getRequestedByUserId(),
                            request.getWorkspaceId(),
                            request.getId(),
                            creatorName,
                            external.getIgUsername(),
                            creatorProfileId));
        }
    }

    /**
     * F-0701 — carries the Instagram identity we already hold onto the creator's own profile, so a
     * creator the brand personally recruited actually appears when that brand searches for them.
     *
     * <p>Before this, {@link #finishLinking} marked the row JOINED and emailed the brand "they
     * joined" while writing no {@code platform_stats} row. The brand's Discover filter for {@code
     * platforms=INSTAGRAM} is an EXISTS subquery over that table ({@code
     * CreatorProfileSpecifications#hasPlatforms}), so the creator was absent from the results —
     * with the handle, follower count and engagement rate sitting unused on the {@link
     * ExternalCreator} row, already fetched from Business Discovery.
     *
     * <p><b>Why the adopted row is never {@code verified}.</b> The invite proves the creator
     * controls the email address an admin associated with that handle. It does not prove they own
     * the Instagram account — the identity binding is an admin's assertion, while the numbers are
     * genuinely Meta's. {@code PlatformStat.verified} is read by brands as platform-confirmed
     * ownership before they spend money (CR-119), so it fails closed here and the creator earns it
     * by connecting Meta themselves.
     *
     * <p><b>Why this creates but never updates.</b> A creator who connected Meta before accepting
     * the invite already holds a genuinely verified row built from their own token; overwriting it
     * with an admin's older copy of the same numbers would be a downgrade. Absent-only is the whole
     * contract, which is also why this is a small create rather than a third copy of the
     * upsert-with-update in {@code PortfolioService}/{@code PlatformStatsAggregationJob}.
     *
     * <p>Best-effort by construction: a failure to make someone discoverable must never stop them
     * joining, so this swallows its own exceptions rather than letting them reach the JOINED write
     * above — the same discipline the cross-link hook in {@code PortfolioService} already applies.
     */
    private void adoptExternalPlatformStat(ExternalCreator external, String creatorProfileId) {
        try {
            String handle = external.getIgUsername();
            if (handle == null || handle.isBlank()) {
                return;
            }
            if (platformStatRepository
                    .findByCreatorProfileIdAndPlatform(creatorProfileId, INSTAGRAM)
                    .isPresent()) {
                return;
            }

            Long followers = external.getFollowers();
            platformStatRepository.save(
                    PlatformStat.builder()
                            .id(Ulids.newUlid())
                            .creatorProfileId(creatorProfileId)
                            .platform(INSTAGRAM)
                            .handle(handle)
                            // An ADMIN_IMPORT stub that Meta never enriched has no follower count.
                            // 0 keeps the creator findable by the platform chip while claiming
                            // nothing; inventing a number here would be worse than the bug.
                            .followers(followers == null ? 0L : followers)
                            .engagementRate(external.getEngagementRate())
                            .verified(false)
                            .build());

            // CreatorProfileSpecifications#followersBetween reads CreatorProfile.totalFollowers,
            // NOT the platform row — without this roll-up the creator appears for the platform chip
            // and then vanishes the moment a brand touches the follower slider, which looks like an
            // unrelated bug.
            creatorProfileRepository
                    .findById(creatorProfileId)
                    .ifPresent(
                            profile -> {
                                long total =
                                        platformStatRepository.findByCreatorProfileId(creatorProfileId).stream()
                                                .mapToLong(PlatformStat::getFollowers)
                                                .sum();
                                profile.applyAggregatedStats(
                                        total,
                                        external.getEngagementRate() != null
                                                ? external.getEngagementRate()
                                                : profile.getEngagementRate());
                                creatorProfileRepository.save(profile);
                            });

            log.info(
                    "Adopted external Instagram identity onto creatorProfileId={} from"
                            + " externalCreatorId={} (unverified — invite proves email control, not"
                            + " account ownership)",
                    creatorProfileId,
                    external.getId());
        } catch (RuntimeException e) {
            log.error(
                    "adoptExternalPlatformStat failed for creatorProfileId={} externalCreatorId={}"
                            + " (swallowed — the JOINED write above already succeeded and the"
                            + " creator is linked; they are just not yet discoverable by platform)",
                    creatorProfileId,
                    external.getId(),
                    e);
        }
    }

    private static String normalizeUsername(String raw) {
        String trimmed = raw.trim().toLowerCase();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
