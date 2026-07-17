package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorPlatformFeeService;
import com.influora.web.dto.creatorplatformfee.CreatorPlatformFeeDtos.PlatformFeeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Task #27 (P0-V2) — pure delegation tests for {@code GET /creator/platform-fee}. */
@ExtendWith(MockitoExtension.class)
class CreatorPlatformFeeControllerTest {

    @Mock private CreatorPlatformFeeService creatorPlatformFeeService;
    @Mock private AuthPrincipal principal;

    private CreatorPlatformFeeController controller;

    @BeforeEach
    void setUp() {
        controller = new CreatorPlatformFeeController(creatorPlatformFeeService);
    }

    @Test
    @DisplayName("GET /creator/platform-fee delegates to service and returns 200")
    void testGetCurrentFee() {
        PlatformFeeResponse fee =
                new PlatformFeeResponse(
                        1500, 15.0, CreatorPlatformFeeService.SOURCE_GLOBAL_DEFAULT);
        when(creatorPlatformFeeService.getCurrentFee(principal)).thenReturn(fee);

        ResponseEntity<ApiResponse<PlatformFeeResponse>> response =
                controller.getCurrentFee(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1500, response.getBody().data().feeBps());
        assertEquals(15.0, response.getBody().data().feePercent());
        assertEquals(
                CreatorPlatformFeeService.SOURCE_GLOBAL_DEFAULT, response.getBody().data().source());
        verify(creatorPlatformFeeService).getCurrentFee(principal);
    }
}
