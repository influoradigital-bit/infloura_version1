package com.influora.service.admin;

import com.influora.domain.entity.FestivalCouponCopy;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.FestivalCouponCopyRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminFestivalMetricsDtos.CouponCopyMetricsResponse;
import com.influora.web.dto.admin.AdminFestivalMetricsDtos.DailyCopyPoint;
import com.influora.web.dto.admin.AdminFestivalMetricsDtos.SponsorCopyTotal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Admin read side of the Festival Box coupon-copy tracker (T-FESTIVALBOX-0905 phase 6). Backs
 * {@code GET /admin/festival-metrics/copies}. On its own controller ({@code
 * AdminFestivalMetricsController}) rather than folded into {@code AdminFestivalEnquiryController}
 * — that controller's whole surface is the enquiry pipeline (list/update-status/provision); a
 * demand-signal read over a completely different table is a different resource, not another verb
 * on enquiries.
 *
 * <p>Role gate lives here, not in the controller — this codebase has no {@code @PreAuthorize}
 * anywhere (see {@code AdminContextService}'s class javadoc), so a controller with no service-layer
 * gate is an open route.
 */
@Service
public class AdminFestivalMetricsService {

    private final AdminContextService adminContext;
    private final FestivalCouponCopyRepository festivalCouponCopyRepository;

    public AdminFestivalMetricsService(
            AdminContextService adminContext,
            FestivalCouponCopyRepository festivalCouponCopyRepository) {
        this.adminContext = adminContext;
        this.festivalCouponCopyRepository = festivalCouponCopyRepository;
    }

    /**
     * Per-sponsor totals + the full daily series for one edition. Both queries are already
     * grouped/ordered in SQL ({@link FestivalCouponCopyRepository#sumBySponsorForEdition} does the
     * SUM, {@link FestivalCouponCopyRepository#findByEditionOrderByDayAscSponsorSlugAsc} is already
     * a small, bounded row set per the migration header) — no in-Java aggregation over an unbounded
     * result set.
     */
    public CouponCopyMetricsResponse getCopyMetrics(AuthPrincipal principal, String edition) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        List<SponsorCopyTotal> sponsorTotals = new ArrayList<>();
        for (Object[] row : festivalCouponCopyRepository.sumBySponsorForEdition(edition)) {
            sponsorTotals.add(new SponsorCopyTotal((String) row[0], ((Number) row[1]).longValue()));
        }

        List<DailyCopyPoint> dailySeries = new ArrayList<>();
        for (FestivalCouponCopy row :
                festivalCouponCopyRepository.findByEditionOrderByDayAscSponsorSlugAsc(edition)) {
            dailySeries.add(new DailyCopyPoint(row.getSponsorSlug(), row.getDay(), row.getCopyCount()));
        }

        return new CouponCopyMetricsResponse(edition, sponsorTotals, dailySeries);
    }
}
