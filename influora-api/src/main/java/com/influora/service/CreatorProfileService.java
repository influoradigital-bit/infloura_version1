package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.IndianPhoneUtils;
import com.influora.common.JsonLists;
import com.influora.common.UsernameUtils;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.User;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfilePatchRequest;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfileSelfResponse;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreatorProfileService {

    private static final Logger log = LoggerFactory.getLogger(CreatorProfileService.class);

    private final CreatorContextService creatorContext;
    private final CreatorProfileRepository creatorProfileRepository;
    private final PlatformStatRepository platformStatRepository;
    private final UserRepository userRepository;
    private final ExternalCreatorLinkService externalCreatorLinkService;

    public CreatorProfileService(
            CreatorContextService creatorContext,
            CreatorProfileRepository creatorProfileRepository,
            PlatformStatRepository platformStatRepository,
            UserRepository userRepository,
            ExternalCreatorLinkService externalCreatorLinkService) {
        this.creatorContext = creatorContext;
        this.creatorProfileRepository = creatorProfileRepository;
        this.platformStatRepository = platformStatRepository;
        this.userRepository = userRepository;
        this.externalCreatorLinkService = externalCreatorLinkService;
    }

    /**
     * NOT read-only: {@link #ensureUsername} may lazily persist an auto-generated handle for
     * profiles that never went through "claim your handle" (see its javadoc — 2026-07-23 P-1).
     */
    @Transactional
    public CreatorProfileSelfResponse getMyProfile(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        ensureUsername(profile);
        return toSelfResponse(profile, loadUser(principal.getUserId()));
    }

    @Transactional
    public CreatorProfileSelfResponse patchMyProfile(
            AuthPrincipal principal, CreatorProfilePatchRequest req) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        if (req.username() != null && !req.username().isBlank()) {
            applyUsername(profile, req.username().trim());
        }

        if (req.rateMin() != null && req.rateMax() != null && req.rateMin().compareTo(req.rateMax()) > 0) {
            throw new ApiException(
                    "INVALID_RATE_RANGE", "rateMin cannot exceed rateMax", HttpStatus.BAD_REQUEST);
        }

        profile.applySelfEdit(
                req.displayName(),
                req.bio(),
                req.avatarUrl(),
                req.coverImageUrl(),
                req.city(),
                req.categories() != null ? JsonLists.toJson(req.categories()) : null,
                req.languages() != null ? JsonLists.toJson(req.languages()) : null,
                req.contentStyles() != null ? JsonLists.toJson(req.contentStyles()) : null,
                req.rateMin(),
                req.rateMax(),
                req.discoverable());

        try {
            creatorProfileRepository.save(profile);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "USERNAME_TAKEN", "This username is already taken", HttpStatus.CONFLICT);
        }

        // PHONE-0829 — loaded only now (after the profile row above is validated/saved), same
        // relative timing loadUser used to run at via toSelfResponse's argument evaluation, so a
        // rejected username/rate-range patch still fails before touching `users` at all. null
        // means "leave unchanged" (this record's usual convention); an explicit blank string means
        // "clear". Applied/saved separately from the CreatorProfile row above because phone lives
        // on `users`, not `creator_profiles`.
        User user = loadUser(principal.getUserId());
        if (req.phone() != null) {
            applyPhone(user, req.phone());
        }

        return toSelfResponse(profile, user);
    }

    /**
     * PHONE-0829 — first write path {@code users.phone_number} has ever had. Normalizes (strips
     * spaces/+91/leading 0 — see {@link IndianPhoneUtils#normalize}) then validates the strict
     * Indian-mobile rule server-side (never trusts the client's own regex at
     * onboarding-steps.tsx:465). Blank input clears the stored number ({@link User#setPhoneNumber}
     * full-replace semantics). {@code users.phone_number} is {@code UNIQUE} — the DB constraint is
     * the actual race-safe guarantee, this catch just translates it into the same clean 409 the
     * duplicate-email path already returns (see {@code AuthService#brandRegister}) instead of
     * letting it fall through to a raw 500.
     */
    private void applyPhone(User user, String rawPhone) {
        if (rawPhone.isBlank()) {
            user.setPhoneNumber(null);
            userRepository.save(user);
            return;
        }

        String normalized = IndianPhoneUtils.normalize(rawPhone);
        if (!IndianPhoneUtils.isValid(normalized)) {
            throw new ApiException(
                    "INVALID_PHONE",
                    "Enter a valid 10-digit Indian mobile number",
                    HttpStatus.BAD_REQUEST);
        }

        if (!normalized.equals(user.getPhoneNumber())
                && userRepository.existsByPhoneNumber(normalized)) {
            throw new ApiException(
                    "PHONE_ALREADY_EXISTS",
                    "An account with this phone number already exists",
                    HttpStatus.CONFLICT);
        }

        user.setPhoneNumber(normalized);
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "PHONE_ALREADY_EXISTS",
                    "An account with this phone number already exists",
                    HttpStatus.CONFLICT);
        }
    }

    public CreatorProfile requireProfileByUsername(String username) {
        return creatorProfileRepository
                .findByUsernameIgnoreCase(username)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "PORTFOLIO_NOT_FOUND",
                                        "Portfolio not found",
                                        HttpStatus.NOT_FOUND));
    }

    public void applyUsername(CreatorProfile profile, String rawUsername) {
        String username = UsernameUtils.normalize(rawUsername);
        if (!UsernameUtils.isValid(username)) {
            throw new ApiException(
                    "INVALID_USERNAME",
                    "Username must be 3-30 characters: lowercase letters, numbers, underscores",
                    HttpStatus.BAD_REQUEST);
        }
        if (creatorProfileRepository.existsByUsernameIgnoreCaseAndIdNot(username, profile.getId())) {
            throw new ApiException("USERNAME_TAKEN", "This username is already taken", HttpStatus.CONFLICT);
        }
        profile.applyUsername(username);
        // Q5.4 (T-CREATORCONNECT-0902, Critical, fixed 2026-09-03) — do NOT call
        // externalCreatorLinkService.onCreatorIdentified here. An Influora vanity handle claimed
        // via this endpoint is not a verified Instagram identity: any authenticated creator can
        // set it to any string with no proof of controlling that Instagram account. Feeding it
        // into the JOINED-hook matcher (keyed on external_creators.ig_username) let an attacker
        // land-grab a targeted external creator row, flip it to JOINED under the impostor's own
        // creatorProfileId, and get the brand emailed to hire them. The hook now fires only from
        // Meta-verified sources: MetaTokenStorage#storeCreatorToken (id+username from the OAuth
        // token exchange) and PortfolioService#upsertPlatformStat (handle+ig_account_id from a
        // real Meta Graph sync). See ExternalCreatorLinkServiceTest for the regression test.
    }

    /**
     * Lazily assigns and PERSISTS a real, resolvable username for a creator profile that never
     * went through a "claim your handle" step — {@code profile.getUsername()} otherwise stays
     * null forever.
     *
     * Root cause of the 2026-07-23 P-1 live-QA finding: with no username in the DB, the Profile
     * page banner (src/pages/creator-profile.tsx) fell back to the literal placeholder string
     * {@code 'creator'}, and {@code /@creator} 404'd because no profile is actually registered
     * under that handle. {@link com.influora.service.portfolio.PortfolioService#assemble} had the
     * same gap: it computed a display-only username from displayName for the portfolio editor's
     * "your public page" link but never saved it, so that link was equally dead. Calling this once
     * (idempotent — no-ops once a username exists) fixes both call sites at the source.
     *
     * No-op if a username is already set. Otherwise slugifies displayName via {@link
     * UsernameUtils#normalize}, then walks numeric suffixes on collision; after 25 attempts falls
     * back to a suffix derived from the profile's own id (guaranteed unique) rather than looping
     * unbounded.
     */
    @Transactional
    public void ensureUsername(CreatorProfile profile) {
        if (profile.getUsername() != null && !profile.getUsername().isBlank()) {
            return;
        }
        String base = UsernameUtils.normalize(profile.getDisplayName());
        String candidate = base;
        int attempt = 0;
        while (creatorProfileRepository.existsByUsernameIgnoreCaseAndIdNot(candidate, profile.getId())) {
            attempt++;
            if (attempt > 25) {
                String tail = profile.getId().substring(Math.max(0, profile.getId().length() - 6)).toLowerCase();
                candidate = base.substring(0, Math.min(base.length(), 22)) + "_" + tail;
                break;
            }
            String suffixed = base + "_" + attempt;
            candidate = suffixed.substring(0, Math.min(suffixed.length(), 30));
        }
        profile.applyUsername(candidate);
        try {
            creatorProfileRepository.save(profile);
        } catch (DataIntegrityViolationException dup) {
            // Lost a uniqueness race to a concurrent request — leave the in-memory field as-is
            // for this response; the next read will retry ensureUsername from a clean state.
            log.warn("ensureUsername lost a uniqueness race for profile={}", profile.getId());
        }
    }

    private User loadUser(String userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
    }

    private CreatorProfileSelfResponse toSelfResponse(CreatorProfile profile, User user) {
        List<PlatformStat> platforms = platformStatRepository.findByCreatorProfileId(profile.getId());
        List<PlatformStatResponse> platformDtos = platforms.stream().map(this::toPlatform).toList();
        return new CreatorProfileSelfResponse(
                profile.getId(),
                profile.getUserId(),
                profile.getDisplayName(),
                profile.getUsername(),
                profile.getBio(),
                profile.getAvatarUrl(),
                profile.getCoverImageUrl(),
                profile.getCity(),
                JsonLists.stringListFromJson(profile.getCategoriesJson()),
                JsonLists.stringListFromJson(profile.getLanguagesJson()),
                JsonLists.stringListFromJson(profile.getContentStylesJson()),
                platformDtos,
                profile.getRateMin(),
                profile.getRateMax(),
                profile.getCurrency(),
                profile.isDiscoverable(),
                profile.isVerified(),
                profile.getTotalFollowers(),
                profile.getEngagementRate(),
                user.isOnboardingCompleted(),
                calculateCompleteness(profile, platforms),
                user.getPhoneNumber());
    }

    private PlatformStatResponse toPlatform(PlatformStat ps) {
        return new PlatformStatResponse(
                ps.getPlatform(),
                ps.getHandle() != null ? ps.getHandle() : "",
                ps.getFollowers(),
                ps.getEngagementRate(),
                ps.isVerified(),
                ps.getProfileUrl());
    }

    static int calculateCompleteness(CreatorProfile profile, List<PlatformStat> platforms) {
        int score = 0;
        int total = 100;

        if (hasText(profile.getDisplayName())) score += 10;
        if (hasText(profile.getUsername())) score += 10;
        if (hasText(profile.getBio())) score += 10;
        if (hasText(profile.getAvatarUrl())) score += 10;
        if (!JsonLists.stringListFromJson(profile.getCategoriesJson()).isEmpty()) score += 15;
        if (hasText(profile.getCity())) score += 10;
        if (profile.getRateMin() != null || profile.getRateMax() != null) score += 15;
        if (!platforms.isEmpty()) score += 20;

        return Math.min(100, (score * 100) / total);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
