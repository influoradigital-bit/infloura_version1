package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.FestivalCouponCopy;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.FestivalCouponCopyRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminFestivalMetricsDtos.CouponCopyMetricsResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Mockito unit tests for {@code AdminFestivalMetricsService} (T-FESTIVALBOX-0905 phase 6, {@code
 * GET /admin/festival-metrics/copies}). Same plain-Mockito pattern as every other {@code
 * Admin*ServiceTest} in this package.
 */
@ExtendWith(MockitoExtension.class)
class AdminFestivalMetricsServiceTest {

    @Mock private AdminContextService adminContext;
    @Mock private FestivalCouponCopyRepository festivalCouponCopyRepository;
    @Mock private AuthPrincipal principal;

    private AdminFestivalMetricsService service() {
        return new AdminFestivalMetricsService(adminContext, festivalCouponCopyRepository);
    }

    @Test
    @DisplayName("role gate: an unauthorized caller is rejected before any repository query runs")
    void unauthorizedCaller_isRejectedBeforeAnyQuery() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenThrow(new ApiException("FORBIDDEN", "Insufficient role", HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service().getCopyMetrics(principal, "MUMBAI_FESTIVE_2026"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(festivalCouponCopyRepository, never()).sumBySponsorForEdition(org.mockito.ArgumentMatchers.any());
        verify(festivalCouponCopyRepository, never())
                .findByEditionOrderByDayAscSponsorSlugAsc(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("maps sponsor totals and daily series for an authorized caller")
    void authorizedCaller_mapsBothQueries() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(null);
        when(festivalCouponCopyRepository.sumBySponsorForEdition("MUMBAI_FESTIVE_2026"))
                .thenReturn(
                        List.of(
                                new Object[] {"acme-corp", 12L},
                                new Object[] {"other-brand", 3L}));
        when(festivalCouponCopyRepository.findByEditionOrderByDayAscSponsorSlugAsc("MUMBAI_FESTIVE_2026"))
                .thenReturn(
                        List.of(
                                FestivalCouponCopy.seed(
                                        "01ROW1", "MUMBAI_FESTIVE_2026", "acme-corp", "FEST15",
                                        LocalDate.of(2026, 9, 5), 12L)));

        CouponCopyMetricsResponse response = service().getCopyMetrics(principal, "MUMBAI_FESTIVE_2026");

        assertEquals("MUMBAI_FESTIVE_2026", response.edition());
        assertEquals(2, response.sponsorTotals().size());
        assertEquals("acme-corp", response.sponsorTotals().get(0).sponsorSlug());
        assertEquals(12L, response.sponsorTotals().get(0).totalCopies());
        assertEquals(1, response.dailySeries().size());
        assertEquals("acme-corp", response.dailySeries().get(0).sponsorSlug());
        assertEquals(LocalDate.of(2026, 9, 5), response.dailySeries().get(0).day());
        assertEquals(12L, response.dailySeries().get(0).copyCount());
    }
}
