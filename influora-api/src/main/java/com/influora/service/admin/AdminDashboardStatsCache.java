package com.influora.service.admin;

import com.influora.config.RedisCacheConfig;
import com.influora.domain.entity.SupportTicket;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.web.dto.admin.AdminDashboardDtos.CeoPulseDataDto;
import com.influora.web.dto.admin.AdminDashboardDtos.RedFlagDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link AdminDashboardService#pulse} with a short-TTL Redis cache
 * (src/admin/TASK_ASSIGNMENTS.md P1 "Redis cache setup", {@link RedisCacheConfig#ADMIN_PULSE_CACHE}
 * = 45s TTL) so {@code AdminLayout}'s dashboard polling doesn't re-run the campaign/escrow/support/
 * user COUNT and SUM queries on every single request.
 *
 * <p><b>Deliberately a separate bean from {@link AdminDashboardService}, not a private method on
 * it:</b> {@code @Cacheable} is only honored through the Spring AOP proxy — a self-invoked private
 * method on the same bean would silently skip caching entirely. Splitting the bean also keeps the
 * RBAC gate correct: {@link AdminDashboardService#pulse} calls
 * {@code adminContext.requireRoleWithMfaSatisfied(...)} itself, uncached, on every single
 * invocation, and only delegates to {@link #pulseStats()} afterward. So a cache hit here can never
 * bypass the authorization check — it only ever skips the expensive query work. The cache key is a
 * fixed literal ({@code 'pulse'}), not principal-derived, because these are global admin stats,
 * identical for every authorized caller; keying by principal would just fragment the cache for no
 * benefit.
 */
@Service
public class AdminDashboardStatsCache {

    /** Tickets still OPEN past this age surface as a SUPPORT_AGING red flag. */
    private static final long SUPPORT_AGING_HOURS = 48;

    private static final long MAU_WINDOW_DAYS = 30;

    private final CampaignRepository campaignRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final UserRepository userRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    public AdminDashboardStatsCache(
            CampaignRepository campaignRepository,
            EscrowHoldRepository escrowHoldRepository,
            SupportTicketRepository supportTicketRepository,
            UserRepository userRepository,
            WalletTransactionRepository walletTransactionRepository) {
        this.campaignRepository = campaignRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.userRepository = userRepository;
        this.walletTransactionRepository = walletTransactionRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = RedisCacheConfig.ADMIN_PULSE_CACHE, key = "'pulse'")
    public CeoPulseDataDto pulseStats() {
        long activeCampaigns = campaignRepository.countByStatus(CampaignStatus.ACTIVE);
        BigDecimal escrowFloat = escrowHoldRepository.sumAmountByStatusIn(List.of(EscrowStatus.FUNDED));
        BigDecimal gmv =
                escrowHoldRepository.sumAmountByStatusIn(
                        List.of(EscrowStatus.FUNDED, EscrowStatus.RELEASED));
        long supportQueueDepth =
                supportTicketRepository.countByStatusIn(
                        List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_USER));
        Instant mauSince = Instant.now().minus(MAU_WINDOW_DAYS, ChronoUnit.DAYS);
        long mauBrands = userRepository.countByUserTypeAndLastLoginAtAfter(UserType.BRAND, mauSince);
        long mauCreators = userRepository.countByUserTypeAndLastLoginAtAfter(UserType.CREATOR, mauSince);
        // Rohan's (CFO) signed-off formula, 2026-07-15: revenue = SUM(wallet_transactions.amount)
        // WHERE type = 'PLATFORM_FEE', over the ledger to date -- see
        // WalletTransactionRepository.sumAmountByTypeAndCreatedAtBefore javadoc for the double-count
        // traps this deliberately avoids (campaign_service_invoices, platform_commission_invoices).
        BigDecimal revenue =
                walletTransactionRepository.sumAmountByTypeAndCreatedAtBefore(
                        WalletTransactionType.PLATFORM_FEE, Instant.now());

        return new CeoPulseDataDto(
                gmv,
                // gmvChange/revenueChange/activeCampaignsChange are genuinely null, not 0.0 --
                // TODO: needs a kpi_daily_snapshot table + scheduled job to diff against (see
                // AdminDashboardService class javadoc). A fabricated 0 would read as "measured, and
                // flat" on this CEO/CFO-facing dashboard; null renders an honest "--" instead. Do
                // not change this back to 0.0 until the snapshot table exists.
                null, // gmvChange
                revenue,
                null, // revenueChange
                activeCampaigns,
                null, // activeCampaignsChange
                escrowFloat,
                supportQueueDepth,
                mauBrands,
                mauCreators,
                supportAgingRedFlags());
    }

    private List<RedFlagDto> supportAgingRedFlags() {
        Instant cutoff = Instant.now().minus(SUPPORT_AGING_HOURS, ChronoUnit.HOURS);
        List<SupportTicket> aging =
                supportTicketRepository.findTop5ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        TicketStatus.OPEN, cutoff);
        List<RedFlagDto> flags = new ArrayList<>();
        for (SupportTicket ticket : aging) {
            long hoursOpen = ChronoUnit.HOURS.between(ticket.getCreatedAt(), Instant.now());
            String severity = hoursOpen >= SUPPORT_AGING_HOURS * 3.5 ? "CRITICAL" : "WARNING";
            flags.add(
                    new RedFlagDto(
                            "support-aging-" + ticket.getId(),
                            "SUPPORT_AGING",
                            "Ticket \"" + ticket.getSubject() + "\" has been open for " + hoursOpen + "h",
                            severity,
                            ticket.getCreatedAt(),
                            ticket.getId(),
                            "SUPPORT_TICKET"));
        }
        return flags;
    }
}
