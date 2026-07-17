package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.User;
import com.influora.domain.enums.UserType;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class CreatorContextServiceTest {

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthPrincipal principal;

    private CreatorContextService service;

    @BeforeEach
    void setUp() {
        service = new CreatorContextService(creatorProfileRepository, userRepository);
        // Most tests exercise a live (non-deleted) user; the two SEC tests below override this.
        lenient().when(principal.getUserId()).thenReturn("u1");
        User liveUser = mock(User.class);
        lenient().when(liveUser.getDeletedAt()).thenReturn(null);
        lenient().when(userRepository.findById("u1")).thenReturn(Optional.of(liveUser));
    }

    @Test
    @DisplayName("requireCreatorProfile: rejects null principal")
    void testRejectsNullPrincipal() {
        ApiException ex =
                assertThrows(ApiException.class, () -> service.requireCreatorProfile(null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("WRONG_USER_TYPE", ex.getCode());
    }

    @Test
    @DisplayName("requireCreatorProfile: rejects brand principal")
    void testRejectsBrand() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.requireCreatorProfile(principal));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("requireCreatorProfile: resolves profile from authenticated user id")
    void testResolvesProfile() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);

        CreatorProfile profile = CreatorProfile.newForUser("p1", "u1", "Test");
        when(creatorProfileRepository.findByUserId("u1")).thenReturn(Optional.of(profile));

        CreatorProfile resolved = service.requireCreatorProfile(principal);
        assertEquals("p1", resolved.getId());
    }

    @Test
    @DisplayName("[SEC: Kabir H-1] requireCreator: a soft-deleted user's still-valid token is rejected, not honored")
    void testRejectsDeletedUser() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        User deletedUser = mock(User.class);
        when(deletedUser.getDeletedAt()).thenReturn(java.time.Instant.now());
        when(userRepository.findById("u1")).thenReturn(Optional.of(deletedUser));

        ApiException ex = assertThrows(ApiException.class, () -> service.requireCreator(principal));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("ACCOUNT_DELETED", ex.getCode());
    }

    @Test
    @DisplayName("[SEC: Kabir H-1] requireCreator: a user row that no longer exists is treated as deleted, not skipped")
    void testMissingUserTreatedAsDeleted() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(userRepository.findById("u1")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.requireCreator(principal));
        assertEquals("ACCOUNT_DELETED", ex.getCode());
    }
}
