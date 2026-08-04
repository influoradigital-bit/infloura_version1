package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PlatformCommissionInvoice;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PlatformCommissionInvoiceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminRevenueDtos.RevenueSnapshotDto;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link com.influora.web.AdminRevenueController} ({@code GET /admin/finance/revenue}) and the
 * financial dashboard summary ({@code GET /admin/dashboard/financial}, via {@code
 * AdminDashboardController}).
 *
 * <p>Read-only. Every figure is a live aggregate over {@code escrow_holds} (GMV) and {@code
 * platform_commission_invoices} (platform fees); see {@link RevenueSnapshotDto} for per-field
 * derivation and the declared {@code setupFees == 0} structural zero. Buckets are built in-memory
 * from rows in the requested window — fine for an admin report/dashboard over a bounded range.
 */
@Service
public class AdminRevenueService {

    /** Number of trailing buckets the dashboard summary returns. */
    private static final int DASHBOARD_WINDOW = 12;

    private final AdminContextService adminContext;
    private final EscrowHoldRepository escrowHoldRepository;
    private final PlatformCommissionInvoiceRepository commissionInvoiceRepository;

    public AdminRevenueService(
            AdminContextService adminContext,
            EscrowHoldRepository escrowHoldRepository,
            PlatformCommissionInvoiceRepository commissionInvoiceRepository) {
        this.adminContext = adminContext;
        this.escrowHoldRepository = escrowHoldRepository;
        this.commissionInvoiceRepository = commissionInvoiceRepository;
    }

    /**
     * Revenue snapshots bucketed by {@code groupBy} over the inclusive {@code [startDate, endDate]}
     * range. Backs {@code financeApi.getRevenue(startDate, endDate, groupBy)}. SUPER_ADMIN + ADMIN,
     * MFA-gated. Dates are {@code yyyy-MM-dd}; a malformed date or an inverted range is a 400, never
     * a 500. Only buckets that actually have GMV or fees are returned, ascending by period.
     */
    @Transactional(readOnly = true)
    public List<RevenueSnapshotDto> getRevenue(
            AuthPrincipal principal, String startDate, String endDate, String groupBy) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (end.isBefore(start)) {
            throw new ApiException(
                    "INVALID_DATE_RANGE", "endDate must not be before startDate", HttpStatus.BAD_REQUEST);
        }
        String granularity = normalizeGranularity(groupBy, false);

        Map<String, BigDecimal> gmvByBucket = new TreeMap<>();
        Map<String, BigDecimal> feeByBucket = new TreeMap<>();
        aggregate(startOfDay(start), startOfDay(end.plusDays(1)), granularity, gmvByBucket, feeByBucket);

        // Only buckets with activity, ascending.
        TreeSet<String> periods = new TreeSet<>();
        periods.addAll(gmvByBucket.keySet());
        periods.addAll(feeByBucket.keySet());
        List<RevenueSnapshotDto> snapshots = new ArrayList<>();
        for (String period : periods) {
            snapshots.add(snapshot(period, gmvByBucket, feeByBucket));
        }
        return snapshots;
    }

    /**
     * The last {@value #DASHBOARD_WINDOW} buckets at the requested {@code period} granularity
     * ({@code day}/{@code week}/{@code month}/{@code quarter}), ending with the current bucket. Backs
     * {@code dashboardApi.getFinancialSummary(period)}. SUPER_ADMIN + ADMIN, MFA-gated.
     *
     * <p>Unlike {@link #getRevenue}, this ALWAYS returns exactly {@value #DASHBOARD_WINDOW} rows
     * (ascending), emitting {@code 0} for a bucket with no activity — a continuous timeline is what a
     * dashboard chart needs, and a zero bucket genuinely had zero released value/fees. The
     * aggregation window starts at the FIRST day of the oldest bucket, so that oldest bucket is
     * counted in full (no partial-bucket undercount).
     */
    @Transactional(readOnly = true)
    public List<RevenueSnapshotDto> getFinancialSummary(AuthPrincipal principal, String period) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        String granularity = normalizeGranularity(period, true);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // The desired bucket keys (newest at i=0 .. oldest at i=WINDOW-1), deduped + sorted ascending.
        TreeSet<String> wantedKeys = new TreeSet<>();
        LocalDate oldestAnchor = today;
        for (int i = 0; i < DASHBOARD_WINDOW; i++) {
            LocalDate anchor = stepBack(today, granularity, i);
            wantedKeys.add(bucketKey(anchor, granularity));
            oldestAnchor = anchor;
        }

        Map<String, BigDecimal> gmvByBucket = new TreeMap<>();
        Map<String, BigDecimal> feeByBucket = new TreeMap<>();
        // Range starts at the first day of the oldest bucket so it is captured in full.
        aggregate(
                startOfDay(bucketStart(oldestAnchor, granularity)),
                startOfDay(today.plusDays(1)),
                granularity,
                gmvByBucket,
                feeByBucket);

        List<RevenueSnapshotDto> snapshots = new ArrayList<>();
        for (String period_ : wantedKeys) {
            snapshots.add(snapshot(period_, gmvByBucket, feeByBucket));
        }
        return snapshots;
    }

    // --- shared internals ------------------------------------------------------------------------

    private void aggregate(
            Instant startInstant,
            Instant endExclusive,
            String granularity,
            Map<String, BigDecimal> gmvOut,
            Map<String, BigDecimal> feeOut) {
        for (EscrowHold hold :
                escrowHoldRepository.findByStatusAndReleasedAtGreaterThanEqualAndReleasedAtLessThan(
                        EscrowStatus.RELEASED, startInstant, endExclusive)) {
            if (hold.getReleasedAt() == null || hold.getAmount() == null) {
                continue;
            }
            gmvOut.merge(bucketKey(hold.getReleasedAt(), granularity), hold.getAmount(), BigDecimal::add);
        }
        for (PlatformCommissionInvoice invoice :
                commissionInvoiceRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        startInstant, endExclusive)) {
            if (invoice.getCreatedAt() == null || invoice.getCommissionAmount() == null) {
                continue;
            }
            feeOut.merge(
                    bucketKey(invoice.getCreatedAt(), granularity), invoice.getCommissionAmount(), BigDecimal::add);
        }
    }

    private static RevenueSnapshotDto snapshot(
            String period, Map<String, BigDecimal> gmvByBucket, Map<String, BigDecimal> feeByBucket) {
        double gmv = gmvByBucket.getOrDefault(period, BigDecimal.ZERO).doubleValue();
        double platformFees = feeByBucket.getOrDefault(period, BigDecimal.ZERO).doubleValue();
        double setupFees = 0.0; // no setup-fee product exists — honest structural zero
        return new RevenueSnapshotDto(period, gmv, platformFees, setupFees, platformFees + setupFees);
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    "INVALID_DATE", field + " is required (yyyy-MM-dd)", HttpStatus.BAD_REQUEST);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(
                    "INVALID_DATE", field + " must be a valid yyyy-MM-dd date", HttpStatus.BAD_REQUEST);
        }
    }

    /** {@code allowQuarter} is true only for the dashboard summary; the range report is day/week/month. */
    private static String normalizeGranularity(String value, boolean allowQuarter) {
        if (value == null) {
            return "day";
        }
        return switch (value) {
            case "week", "month" -> value;
            case "quarter" -> allowQuarter ? "quarter" : "day";
            default -> "day";
        };
    }

    /** i-th previous bucket anchor date, stepping by whole units so each i lands in a distinct bucket. */
    private static LocalDate stepBack(LocalDate today, String granularity, int i) {
        return switch (granularity) {
            case "week" -> today.minusWeeks(i);
            case "month" -> today.minusMonths(i);
            case "quarter" -> today.minusMonths(3L * i);
            default -> today.minusDays(i);
        };
    }

    /** First calendar day of the bucket containing {@code date}, per granularity. */
    private static LocalDate bucketStart(LocalDate date, String granularity) {
        return switch (granularity) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            case "quarter" -> date.withDayOfMonth(1).minusMonths((date.getMonthValue() - 1) % 3);
            default -> date;
        };
    }

    private static String bucketKey(Instant instant, String granularity) {
        return bucketKey(instant.atZone(ZoneOffset.UTC).toLocalDate(), granularity);
    }

    private static String bucketKey(LocalDate date, String granularity) {
        try {
            return switch (granularity) {
                case "month" -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
                case "quarter" -> String.format("%04d-Q%d", date.getYear(), (date.getMonthValue() - 1) / 3 + 1);
                case "week" -> String.format(
                        "%04d-W%02d",
                        date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                default -> date.toString(); // yyyy-MM-dd
            };
        } catch (DateTimeException e) {
            return date.toString();
        }
    }
}
