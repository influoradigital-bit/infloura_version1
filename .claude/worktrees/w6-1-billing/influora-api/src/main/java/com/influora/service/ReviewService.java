package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.ContentFlag;
import com.influora.domain.entity.Review;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContentFlagType;
import com.influora.domain.enums.ReviewerType;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.ReviewRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.review.ReviewDtos.CreateReviewRequest;
import com.influora.web.dto.review.ReviewDtos.FlagReviewRequest;
import com.influora.web.dto.review.ReviewDtos.FlagReviewResponse;
import com.influora.web.dto.review.ReviewDtos.ReviewResponse;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collaboration reviews for creator↔brand (Task #29 / CEO §1.2). Identity always from {@link
 * CreatorContextService} / {@link BrandContextService} — never trust path-param user ids.
 */
@Service
public class ReviewService {

    private final CreatorContextService creatorContext;
    private final BrandContextService brandContext;
    private final CollaborationRepository collaborationRepository;
    private final ReviewRepository reviewRepository;
    private final ContentFlagRepository contentFlagRepository;

    public ReviewService(
            CreatorContextService creatorContext,
            BrandContextService brandContext,
            CollaborationRepository collaborationRepository,
            ReviewRepository reviewRepository,
            ContentFlagRepository contentFlagRepository) {
        this.creatorContext = creatorContext;
        this.brandContext = brandContext;
        this.collaborationRepository = collaborationRepository;
        this.reviewRepository = reviewRepository;
        this.contentFlagRepository = contentFlagRepository;
    }

    @Transactional
    public ReviewResponse createCreatorReview(AuthPrincipal principal, CreateReviewRequest request) {
        creatorContext.requireCreatorProfile(principal);
        return createReview(principal, request, ReviewerType.CREATOR);
    }

    @Transactional
    public ReviewResponse createBrandReview(AuthPrincipal principal, CreateReviewRequest request) {
        brandContext.requireBrandWorkspace(principal);
        return createReview(principal, request, ReviewerType.BRAND);
    }

    @Transactional
    public FlagReviewResponse flagCreatorReview(
            AuthPrincipal principal, String reviewId, FlagReviewRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Review review = requireReviewForParty(reviewId, principal, ReviewerType.CREATOR);
        return saveFlag(review, request, principal.getUserId());
    }

    @Transactional
    public FlagReviewResponse flagBrandReview(
            AuthPrincipal principal, String reviewId, FlagReviewRequest request) {
        brandContext.requireBrandWorkspace(principal);
        Review review = requireReviewForParty(reviewId, principal, ReviewerType.BRAND);
        return saveFlag(review, request, principal.getUserId());
    }

    /**
     * V-GA-8 — reviews brands left about the authenticated creator. Principal-scoped via {@link
     * CreatorContextService}; repository query joins collaborations by {@code creatorId =
     * principal.userId} so another creator's reviews never leak.
     */
    @Transactional(readOnly = true)
    public List<ReviewResponse> listReceivedByCreator(AuthPrincipal principal) {
        creatorContext.requireCreatorProfile(principal);
        return reviewRepository
                .findReceivedByCreatorUserId(principal.getUserId(), ReviewerType.BRAND)
                .stream()
                .map(ReviewService::toResponse)
                .toList();
    }

    /**
     * P2-14 — reviews creators left about the authenticated brand workspace. Principal-scoped via
     * {@link BrandContextService}; repository query joins {@code Collaboration→Campaign→workspace}
     * so another workspace's reviews never leak.
     */
    @Transactional(readOnly = true)
    public List<ReviewResponse> listReceivedByBrand(AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return reviewRepository
                .findReceivedByBrandWorkspaceId(workspace.getId(), ReviewerType.CREATOR)
                .stream()
                .map(ReviewService::toResponse)
                .toList();
    }

    private ReviewResponse createReview(
            AuthPrincipal principal, CreateReviewRequest request, ReviewerType reviewerType) {
        Collaboration collaboration = requireOwnedCollaboration(principal, request.collaborationId(), reviewerType);

        if (collaboration.getStatus() != CollaborationStatus.COMPLETED) {
            throw new ApiException(
                    "COLLABORATION_NOT_COMPLETED",
                    "Reviews are only allowed after the collaboration is completed",
                    HttpStatus.CONFLICT);
        }

        if (reviewRepository.existsByCollaborationIdAndReviewerType(
                collaboration.getId(), reviewerType)) {
            throw new ApiException(
                    "ALREADY_REVIEWED",
                    "You have already submitted a review for this collaboration",
                    HttpStatus.CONFLICT);
        }

        Review review =
                Review.create(
                        Ulids.newUlid(),
                        collaboration.getId(),
                        reviewerType,
                        principal.getUserId(),
                        request.stars(),
                        request.text());
        try {
            reviewRepository.save(review);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "ALREADY_REVIEWED",
                    "You have already submitted a review for this collaboration",
                    HttpStatus.CONFLICT);
        }
        return toResponse(review);
    }

    private Review requireReviewForParty(
            String reviewId, AuthPrincipal principal, ReviewerType partyType) {
        Review review =
                reviewRepository
                        .findById(reviewId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "REVIEW_NOT_FOUND", "Review not found", HttpStatus.NOT_FOUND));
        if (partyType == ReviewerType.CREATOR) {
            collaborationRepository
                    .findByIdAndCreatorId(review.getCollaborationId(), principal.getUserId())
                    .orElseThrow(
                            () ->
                                    new ApiException(
                                            "REVIEW_NOT_FOUND", "Review not found", HttpStatus.NOT_FOUND));
        } else {
            Workspace workspace = brandContext.requireBrandWorkspace(principal);
            collaborationRepository
                    .findByIdAndWorkspaceId(review.getCollaborationId(), workspace.getId())
                    .orElseThrow(
                            () ->
                                    new ApiException(
                                            "REVIEW_NOT_FOUND", "Review not found", HttpStatus.NOT_FOUND));
        }
        return review;
    }

    private FlagReviewResponse saveFlag(Review review, FlagReviewRequest request, String flaggedByUserId) {
        String reason = TextSanitizer.sanitizePlainText(request.reason());
        if (reason == null || reason.isBlank()) {
            throw new ApiException("INVALID_REQUEST", "reason is required", HttpStatus.BAD_REQUEST);
        }

        if (contentFlagRepository.existsByContentIdAndFlaggedByUserId(review.getId(), flaggedByUserId)) {
            throw new ApiException(
                    "ALREADY_FLAGGED",
                    "You have already flagged this review",
                    HttpStatus.CONFLICT);
        }

        String preview = review.getReviewText();
        if (preview != null && preview.length() > 200) {
            preview = preview.substring(0, 200);
        }

        ContentFlag flag =
                ContentFlag.userFlag(
                        Ulids.newUlid(),
                        ContentFlagType.REVIEW,
                        review.getId(),
                        preview,
                        reason,
                        flaggedByUserId);
        try {
            contentFlagRepository.save(flag);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "ALREADY_FLAGGED",
                    "You have already flagged this review",
                    HttpStatus.CONFLICT);
        }
        return new FlagReviewResponse(flag.getId(), flag.getStatus().name());
    }

    private Collaboration requireOwnedCollaboration(
            AuthPrincipal principal, String collaborationId, ReviewerType reviewerType) {
        if (reviewerType == ReviewerType.CREATOR) {
            return collaborationRepository
                    .findByIdAndCreatorId(collaborationId, principal.getUserId())
                    .orElseThrow(
                            () ->
                                    new ApiException(
                                            "COLLABORATION_NOT_FOUND",
                                            "Collaboration not found",
                                            HttpStatus.NOT_FOUND));
        }
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository
                .findByIdAndWorkspaceId(collaborationId, workspace.getId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "COLLABORATION_NOT_FOUND",
                                        "Collaboration not found",
                                        HttpStatus.NOT_FOUND));
    }

    private static ReviewResponse toResponse(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getCollaborationId(),
                review.getReviewerType().name(),
                review.getReviewerUserId(),
                review.getStars(),
                review.getReviewText(),
                review.getCreatedAt());
    }
}
