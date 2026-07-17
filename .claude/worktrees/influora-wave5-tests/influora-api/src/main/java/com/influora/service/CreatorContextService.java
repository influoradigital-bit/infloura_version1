package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Resolves the authenticated creator user's profile — never trust a path-param creator id. */
@Service
public class CreatorContextService {

    private final CreatorProfileRepository creatorProfileRepository;
    private final UserRepository userRepository;

    public CreatorContextService(
            CreatorProfileRepository creatorProfileRepository, UserRepository userRepository) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.userRepository = userRepository;
    }

    /**
     * [SEC: Kabir H-1] Access tokens are stateless JWTs and are NOT invalidated by account
     * deletion — {@code AccountController.deleteAccount} only revokes refresh tokens, so a
     * still-valid access token issued before deletion would otherwise keep working against every
     * creator-scoped endpoint (wallet withdraw, payout-methods, etc.) for up to its remaining TTL
     * (900s default). Re-checking {@code deletedAt} here, at the one gate every creator-scoped
     * endpoint already calls, closes that window on the very next request instead of waiting for
     * the token to expire.
     */
    public void requireCreator(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }
        boolean deleted =
                userRepository.findById(principal.getUserId()).map(u -> u.getDeletedAt() != null).orElse(true);
        if (deleted) {
            throw new ApiException(
                    "ACCOUNT_DELETED", "This account no longer exists", HttpStatus.UNAUTHORIZED);
        }
    }

    public CreatorProfile requireCreatorProfile(AuthPrincipal principal) {
        requireCreator(principal);
        return creatorProfileRepository
                .findByUserId(principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND",
                                        "Creator profile not found",
                                        HttpStatus.NOT_FOUND));
    }
}
