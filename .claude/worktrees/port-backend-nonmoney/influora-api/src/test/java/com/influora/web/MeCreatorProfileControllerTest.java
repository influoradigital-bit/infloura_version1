package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorProfileService;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfilePatchRequest;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfileSelfResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class MeCreatorProfileControllerTest {

    @Mock private CreatorProfileService creatorProfileService;
    @Mock private AuthPrincipal principal;

    private MeCreatorProfileController controller;

    @BeforeEach
    void setUp() {
        controller = new MeCreatorProfileController(creatorProfileService);
    }

    @Test
    @DisplayName("GET /me/creator-profile delegates to service")
    void testGet() {
        CreatorProfileSelfResponse dto =
                new CreatorProfileSelfResponse(
                        "p1",
                        "u1",
                        "Priya",
                        "priya",
                        "bio",
                        null,
                        null,
                        "Mumbai",
                        List.of("Fashion"),
                        List.of("English"),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        "INR",
                        true,
                        false,
                        0,
                        null,
                        false,
                        40);

        when(creatorProfileService.getMyProfile(principal)).thenReturn(dto);

        ResponseEntity<ApiResponse<CreatorProfileSelfResponse>> response = controller.get(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Priya", response.getBody().data().displayName());
        verify(creatorProfileService).getMyProfile(principal);
    }

    @Test
    @DisplayName("PATCH /me/creator-profile delegates to service")
    void testPatch() {
        CreatorProfilePatchRequest patch =
                new CreatorProfilePatchRequest(
                        "Priya", null, "Updated bio", null, null, null, null, null, null, null, null, null);
        CreatorProfileSelfResponse dto =
                new CreatorProfileSelfResponse(
                        "p1",
                        "u1",
                        "Priya",
                        null,
                        "Updated bio",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        "INR",
                        false,
                        false,
                        0,
                        null,
                        false,
                        30);

        when(creatorProfileService.patchMyProfile(principal, patch)).thenReturn(dto);

        ResponseEntity<ApiResponse<CreatorProfileSelfResponse>> response =
                controller.patch(principal, patch);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated bio", response.getBody().data().bio());
        verify(creatorProfileService).patchMyProfile(principal, patch);
    }
}
