package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.User;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminCreatorDtos.CreatorDetailDto;
import com.influora.web.dto.admin.AdminCreatorDtos.CreatorSummaryDto;
import com.influora.web.dto.admin.AdminCreatorDtos.PagedCreatorsDto;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * PHONE-0829 Gap B — {@code AdminCreatorService} had no dedicated test file before this. Scoped
 * strictly to the new {@code phone} field on {@code CreatorSummaryDto}/{@code CreatorDetailDto}:
 * carried through from {@code users.phone_number} with the SAME visibility as {@code email} (no
 * separate access-control tier, no masking), and null-safe for the common case of a creator who
 * has never captured one. Plain Mockito, no {@code @SpringBootTest} — matches every other {@code
 * Admin*ServiceTest} in this package (see {@code AdminBrandServiceBudgetOverrideTest}'s javadoc).
 */
@ExtendWith(MockitoExtension.class)
class AdminCreatorServiceTest {

    private static final String ADMIN_ID = "01HADMIN00000000000001";
    private static final String PROFILE_ID = "01HCREATORPROFILE000001";
    private static final String USER_ID = "01HCREATORUSER0000000001";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private CreatorScoreRepository creatorScoreRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ContentFlagRepository contentFlagRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private AuthPrincipal principal;

    private AdminCreatorService service;
    private AdminUser superAdmin;

    @BeforeEach
    void setUp() {
        service =
                new AdminCreatorService(
                        adminContext,
                        adminAuditLogService,
                        creatorProfileRepository,
                        userRepository,
                        platformStatRepository,
                        creatorMetricsRepository,
                        mediaMetricsRepository,
                        creatorScoreRepository,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        contentFlagRepository,
                        metaOAuthTokenRepository);
        superAdmin = AdminUser.create(ADMIN_ID, "ops@influora.ai", "hash", AdminRole.SUPER_ADMIN);
    }

    @Test
    @DisplayName("getById: carries the creator's phone through to CreatorDetailDto")
    void testGetByIdIncludesPhone() {
        stubSuperAdminOrAdminOrSupport();
        CreatorProfile profile = profile();
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        User user = user("9876543210");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        stubEmptyDetailCollaborators(profile);

        CreatorDetailDto dto = service.getById(principal, PROFILE_ID);

        assertEquals("9876543210", dto.phone());
        assertEquals("creator@example.com", dto.email());
    }

    @Test
    @DisplayName("getById: null phone (never captured) round-trips as null, not fabricated")
    void testGetByIdNullSafeWhenPhoneNotCaptured() {
        stubSuperAdminOrAdminOrSupport();
        CreatorProfile profile = profile();
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        User user = user(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        stubEmptyDetailCollaborators(profile);

        CreatorDetailDto dto = service.getById(principal, PROFILE_ID);

        assertNull(dto.phone());
    }

    @Test
    @DisplayName("list: carries phone through to CreatorSummaryDto, batch-loaded alongside email")
    void testListIncludesPhoneInSummary() {
        stubSuperAdminOrAdminOrSupport();
        CreatorProfile profile = profile();
        Page<CreatorProfile> page = new PageImpl<>(List.of(profile));
        when(creatorProfileRepository.findAll(
                        any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(page);
        when(userRepository.findAllById(List.of(USER_ID))).thenReturn(List.of(user("9876543210")));
        when(platformStatRepository.findByCreatorProfileIdIn(List.of(PROFILE_ID))).thenReturn(List.of());

        PagedCreatorsDto paged = service.list(principal, null, null, null, 1, 20);

        assertEquals(1, paged.data().size());
        CreatorSummaryDto summary = paged.data().get(0);
        assertEquals("9876543210", summary.phone());
        assertEquals("creator@example.com", summary.email());
    }

    @Test
    @DisplayName("list: a creator with no captured phone shows a null phone in the summary, never a fabricated value")
    void testListNullSafeWhenPhoneNotCaptured() {
        stubSuperAdminOrAdminOrSupport();
        CreatorProfile profile = profile();
        Page<CreatorProfile> page = new PageImpl<>(List.of(profile));
        when(creatorProfileRepository.findAll(
                        any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(page);
        when(userRepository.findAllById(List.of(USER_ID))).thenReturn(List.of(user(null)));
        when(platformStatRepository.findByCreatorProfileIdIn(List.of(PROFILE_ID))).thenReturn(List.of());

        PagedCreatorsDto paged = service.list(principal, null, null, null, 1, 20);

        assertNull(paged.data().get(0).phone());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void stubSuperAdminOrAdminOrSupport() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(superAdmin);
    }

    /** Empty-collaborator stubs shared by both getById tests — a clean happy path through every
     * sub-lookup {@code toDetailDto} makes beyond the User row (platform stats, flags, OAuth,
     * collaborations, quality score), none of which this test cares about. */
    private void stubEmptyDetailCollaborators(CreatorProfile profile) {
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());
        when(contentFlagRepository.countByContentTypeAndContentIdAndStatusIn(any(), anyString(), any()))
                .thenReturn(0L);
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        PROFILE_ID))
                .thenReturn(Optional.empty());
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(mediaMetricsRepository.findByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        eq(PROFILE_ID), eq("INSTAGRAM"), any(PageRequest.class)))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(profile.getUserId())).thenReturn(List.of());
        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(PROFILE_ID))
                .thenReturn(Optional.empty());
    }

    private static CreatorProfile profile() {
        return CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Creates");
    }

    private static User user(String phone) {
        User u = User.newCreator(USER_ID, "creator@example.com", "hash", "Priya", "Sharma", "Priya Sharma");
        if (phone != null) {
            u.setPhoneNumber(phone);
        }
        return u;
    }
}
