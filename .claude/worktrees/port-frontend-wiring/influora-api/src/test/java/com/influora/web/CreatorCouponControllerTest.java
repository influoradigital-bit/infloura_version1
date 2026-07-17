package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorCouponService;
import com.influora.web.dto.creatorcoupon.CreatorCouponDtos.CreatorCouponListItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Task #28 (P0-V3) — pure delegation tests for {@code GET /creator/coupons}. */
@ExtendWith(MockitoExtension.class)
class CreatorCouponControllerTest {

    @Mock private CreatorCouponService creatorCouponService;
    @Mock private AuthPrincipal principal;

    private CreatorCouponController controller;

    @BeforeEach
    void setUp() {
        controller = new CreatorCouponController(creatorCouponService);
    }

    @Test
    @DisplayName("GET /creator/coupons delegates to service and returns 200")
    void testList() {
        CreatorCouponListItem item =
                new CreatorCouponListItem(
                        "cpn1",
                        "camp1",
                        "Summer Collection",
                        "Nykaa Fashion",
                        "PRIYA20",
                        "percentage",
                        BigDecimal.valueOf(20),
                        500,
                        87,
                        null,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        "https://nykaa.com/summer?utm_source=influora",
                        "http://localhost:8080/api/v1/track/click/01HUTM12345678901234A");
        when(creatorCouponService.list(principal)).thenReturn(List.of(item));

        ResponseEntity<ApiResponse<List<CreatorCouponListItem>>> response = controller.list(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().data().size());
        assertEquals("PRIYA20", response.getBody().data().get(0).code());
        verify(creatorCouponService).list(principal);
    }
}
