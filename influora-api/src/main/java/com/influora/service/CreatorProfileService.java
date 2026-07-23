package com.influora.service;

import com.influora.common.ApiException;
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

    public CreatorProfileService(
            CreatorContextService creatorContext,
            CreatorProfileRepository creatorProfileRepository,
            PlatformStatRepository platformStatRepository,
            UserRepository userRepository) {
        this.creatorContext = creatorContext;
        this.creatorProfileRepository = creatorProfileRepository;
        this.platformStatRepository = platformStatRepository;
        this.userRepository = userRepository;
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

        return toSelfResponse(profile, loadUser(principal.getUserId()));
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
                calculateCompleteness(profile, platforms));
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
