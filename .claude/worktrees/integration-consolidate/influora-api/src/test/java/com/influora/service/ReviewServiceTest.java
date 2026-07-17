package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.ContentFlag;
import com.influora.domain.entity.CreatorProfile;
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
import com.influora.web.dto.review.ReviewDtos.ReviewResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Task #29 — review gates (COMPLETED, double-review) + IDOR isolation. */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final String COLLAB_ID = "01HCOLLAB12345678901";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String OTHER_CREATOR_ID = "01HOTHERCREATOR123456";
    private static final String BRAND_USER_ID = "01HBRANDUSER1234567890";
    private static final String WORKSPACE_ID = "01HWORKSPACE1234567890";
    private static final String REVIEW_ID = "01HREVIEW123456789012";

    @Mock private CreatorContextService creatorContext;
    @Mock private BrandContextService brandContext;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ContentFlagRepository contentFlagRepository;
    @Mock private AuthPrincipal principal;

    private ReviewService service;
    private CreatorProfile creatorProfile;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service =
                new ReviewService(
                        creatorContext,
                        brandContext,
                        collaborationRepository,
                        reviewRepository,
                        contentFlagRepository);
        creatorProfile = CreatorProfile.newForUser("profile1", CREATOR_USER_ID, "Test Creator");
        workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "SMB");
    }

    private static Collaboration completedCollaboration(String creatorId) {
        Collaboration c =
                Collaboration.apply(
                        COLLAB_ID, "camp1", creatorId, "Applied", "INR");
        c.transitionTo(CollaborationStatus.COMPLETED);
        return c;
    }

    private static Collaboration inProgressCollaboration(String creatorId) {
        Collaboration c =
                Collaboration.invite(
                        COLLAB_ID, "camp1", creatorId, "Invite", "INR");
        c.transitionTo(CollaborationStatus.IN_PROGRESS);
        return c;
    }

    @Test
    @DisplayName("creator create: happy path on COMPLETED collaboration")
    void creatorCreateHappyPath() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(reviewRepository.existsByCollaborationIdAndReviewerType(COLLAB_ID, ReviewerType.CREATOR))
                .thenReturn(false);

        ReviewResponse response =
                service.createCreatorReview(
                        principal, new CreateReviewRequest(COLLAB_ID, 5, "Great brand to work with"));

        assertEquals(COLLAB_ID, response.collaborationId());
        assertEquals("CREATOR", response.reviewerType());
        assertEquals(CREATOR_USER_ID, response.reviewerUserId());
        assertEquals(5, response.stars());
        assertEquals("Great brand to work with", response.text());

        ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(saved.capture());
        assertEquals(ReviewerType.CREATOR, saved.getValue().getReviewerType());
    }

    @Test
    @DisplayName("creator create: rejects non-COMPLETED collaboration")
    void creatorCreateRejectsNotCompleted() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(inProgressCollaboration(CREATOR_USER_ID)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createCreatorReview(
                                        principal, new CreateReviewRequest(COLLAB_ID, 4, null)));

        assertEquals("COLLABORATION_NOT_COMPLETED", ex.getCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("creator create: rejects duplicate review")
    void creatorCreateRejectsDuplicate() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(reviewRepository.existsByCollaborationIdAndReviewerType(COLLAB_ID, ReviewerType.CREATOR))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createCreatorReview(
                                        principal, new CreateReviewRequest(COLLAB_ID, 3, "Again")));

        assertEquals("ALREADY_REVIEWED", ex.getCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("creator create: concurrent duplicate maps DataIntegrityViolation to ALREADY_REVIEWED")
    void creatorCreateRaceDuplicate() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(reviewRepository.existsByCollaborationIdAndReviewerType(COLLAB_ID, ReviewerType.CREATOR))
                .thenReturn(false);
        when(reviewRepository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createCreatorReview(
                                        principal, new CreateReviewRequest(COLLAB_ID, 5, null)));

        assertEquals("ALREADY_REVIEWED", ex.getCode());
    }

    @Test
    @DisplayName("creator create: IDOR — foreign collaboration returns 404")
    void creatorCreateIdorForeignCollaboration() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createCreatorReview(
                                        principal, new CreateReviewRequest(COLLAB_ID, 5, null)));

        assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("creator create: sanitizes HTML in review text")
    void creatorCreateSanitizesText() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(reviewRepository.existsByCollaborationIdAndReviewerType(COLLAB_ID, ReviewerType.CREATOR))
                .thenReturn(false);

        ReviewResponse response =
                service.createCreatorReview(
                        principal,
                        new CreateReviewRequest(COLLAB_ID, 4, "<script>alert(1)</script>Clean text"));

        assertEquals("Clean text", response.text());

        ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(saved.capture());
        assertEquals("Clean text", saved.getValue().getReviewText());
    }

    @Test
    @DisplayName("brand create: happy path on COMPLETED collaboration")
    void brandCreateHappyPath() {
        when(principal.getUserId()).thenReturn(BRAND_USER_ID);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(collaborationRepository.findByIdAndWorkspaceId(COLLAB_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(reviewRepository.existsByCollaborationIdAndReviewerType(COLLAB_ID, ReviewerType.BRAND))
                .thenReturn(false);

        ReviewResponse response =
                service.createBrandReview(
                        principal, new CreateReviewRequest(COLLAB_ID, 3, "Solid deliverables"));

        assertEquals("BRAND", response.reviewerType());
        assertEquals(BRAND_USER_ID, response.reviewerUserId());
        assertEquals(3, response.stars());
        verify(reviewRepository).save(any());
    }

    @Test
    @DisplayName("brand create: IDOR — foreign workspace collaboration returns 404")
    void brandCreateIdorForeignCollaboration() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(collaborationRepository.findByIdAndWorkspaceId(COLLAB_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createBrandReview(
                                        principal, new CreateReviewRequest(COLLAB_ID, 5, null)));

        assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("creator flag: creates ContentFlag with REVIEW type")
    void creatorFlagHappyPath() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        Review review =
                Review.create(
                        REVIEW_ID, COLLAB_ID, ReviewerType.BRAND, BRAND_USER_ID, 2, "Bad experience");
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(contentFlagRepository.existsByContentIdAndFlaggedByUserId(REVIEW_ID, CREATOR_USER_ID))
                .thenReturn(false);

        var response =
                service.flagCreatorReview(
                        principal, REVIEW_ID, new FlagReviewRequest("Inappropriate content"));

        assertEquals("PENDING", response.status());

        ArgumentCaptor<ContentFlag> saved = ArgumentCaptor.forClass(ContentFlag.class);
        verify(contentFlagRepository).save(saved.capture());
        assertEquals(ContentFlagType.REVIEW, saved.getValue().getContentType());
        assertEquals(REVIEW_ID, saved.getValue().getContentId());
        assertEquals(CREATOR_USER_ID, saved.getValue().getFlaggedByUserId());
    }

    @Test
    @DisplayName("creator flag M-K6-C2-5: second flag from same user returns 409 ALREADY_FLAGGED")
    void creatorFlagDuplicateRejected() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        Review review =
                Review.create(
                        REVIEW_ID, COLLAB_ID, ReviewerType.BRAND, BRAND_USER_ID, 2, "Bad experience");
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(contentFlagRepository.existsByContentIdAndFlaggedByUserId(REVIEW_ID, CREATOR_USER_ID))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.flagCreatorReview(
                                        principal, REVIEW_ID, new FlagReviewRequest("Spam again")));

        assertEquals("ALREADY_FLAGGED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(contentFlagRepository, never()).save(any());
    }

    @Test
    @DisplayName("creator flag: IDOR — review on foreign collaboration returns 404")
    void creatorFlagIdorForeignReview() {
        when(principal.getUserId()).thenReturn(OTHER_CREATOR_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        Review review =
                Review.create(
                        REVIEW_ID, COLLAB_ID, ReviewerType.BRAND, BRAND_USER_ID, 1, "Low quality");
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, OTHER_CREATOR_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.flagCreatorReview(
                                        principal, REVIEW_ID, new FlagReviewRequest("Spam")));

        assertEquals("REVIEW_NOT_FOUND", ex.getCode());
        verify(contentFlagRepository, never()).save(any());
    }

    @Test
    @DisplayName("brand flag: IDOR — review outside workspace returns 404")
    void brandFlagIdorForeignReview() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Review review =
                Review.create(
                        REVIEW_ID, COLLAB_ID, ReviewerType.CREATOR, CREATOR_USER_ID, 1, "Unfair");
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        when(collaborationRepository.findByIdAndWorkspaceId(COLLAB_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.flagBrandReview(
                                        principal, REVIEW_ID, new FlagReviewRequest("Defamatory")));

        assertEquals("REVIEW_NOT_FOUND", ex.getCode());
        verify(contentFlagRepository, never()).save(any());
    }

    @Test
    @DisplayName("creator create: blank text after sanitization stored as null")
    void creatorCreateBlankTextBecomesNull() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(completedCollaboration(CREATOR_USER_ID)));
        when(reviewRepository.existsByCollaborationIdAndReviewerType(COLLAB_ID, ReviewerType.CREATOR))
                .thenReturn(false);

        ReviewResponse response =
                service.createCreatorReview(
                        principal, new CreateReviewRequest(COLLAB_ID, 5, "   "));

        assertNull(response.text());
    }

    @Test
    @DisplayName("listReceived V-GA-8: returns only brand reviews for the authenticated creator")
    void listReceivedReturnsOwnBrandReviews() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        Review own =
                Review.create(
                        REVIEW_ID, COLLAB_ID, ReviewerType.BRAND, BRAND_USER_ID, 5, "Great work");
        when(reviewRepository.findReceivedByCreatorUserId(CREATOR_USER_ID, ReviewerType.BRAND))
                .thenReturn(List.of(own));

        var responses = service.listReceivedByCreator(principal);

        assertEquals(1, responses.size());
        assertEquals(REVIEW_ID, responses.get(0).id());
        assertEquals("BRAND", responses.get(0).reviewerType());
        assertEquals(5, responses.get(0).stars());
        verify(reviewRepository).findReceivedByCreatorUserId(CREATOR_USER_ID, ReviewerType.BRAND);
    }

    @Test
    @DisplayName("listReceived V-GA-8 isolation: other creator's principal never queries own userId")
    void listReceivedIsolationUsesPrincipalUserIdOnly() {
        when(principal.getUserId()).thenReturn(OTHER_CREATOR_ID);
        CreatorProfile otherProfile =
                CreatorProfile.newForUser("profile2", OTHER_CREATOR_ID, "Other Creator");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(otherProfile);
        when(reviewRepository.findReceivedByCreatorUserId(OTHER_CREATOR_ID, ReviewerType.BRAND))
                .thenReturn(List.of());

        var responses = service.listReceivedByCreator(principal);

        assertEquals(0, responses.size());
        verify(reviewRepository).findReceivedByCreatorUserId(OTHER_CREATOR_ID, ReviewerType.BRAND);
        verify(reviewRepository, never())
                .findReceivedByCreatorUserId(CREATOR_USER_ID, ReviewerType.BRAND);
    }
}
