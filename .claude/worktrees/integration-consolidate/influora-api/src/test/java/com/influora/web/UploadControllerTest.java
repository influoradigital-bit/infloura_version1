package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.UploadService;
import com.influora.web.dto.upload.UploadDtos.UploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/** N2 (Wave 6) — POST /uploads had no route at all before this pass. */
@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock private UploadService uploadService;
    @Mock private AuthPrincipal principal;

    private UploadController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadController(uploadService);
    }

    @Test
    @DisplayName("POST /uploads delegates to service and returns 201 with {url, key}")
    void testUploadDelegatesToService() {
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1, 2, 3});
        when(uploadService.upload(principal, file))
                .thenReturn(new UploadResponse("https://r2.influora.com/uploads/x", "uploads/x"));

        ResponseEntity<ApiResponse<UploadResponse>> response = controller.upload(principal, file);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("uploads/x", response.getBody().data().key());
        verify(uploadService).upload(principal, file);
    }
}
