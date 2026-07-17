package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.PlatformFeeConfigRepository;
import com.influora.service.billing.SubscriptionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand-side platform fee at campaign publish ("B1" — LOCKED ruling,
 * wiki/tech/BRAND_EXECUTION_PLAN.md, 2026-07-09 CTO/CEO/CFO/COO panel). Distinct from
 * {@link PlatformFeeService}, which that class's own javadoc scopes explicitly to the
 * CREATOR-side fee deducted at escrow release — this service is the BRAND-side leg: the moment a
 * campaign transitions DRAFT/PENDING_APPROVAL -> ACTIVE ("goes live"), the brand's wallet is
 * debited an ADDITIONAL {@code brandFeeBps} (default 1000 = 10.00%) of the campaign budget into
 * the platform revenue wallet. This is "fee on top" per the ruling: the campaign's
 * {@code budgetMin}/{@code budgetMax} and the creator's payout base are never touched by this
 * charge — only {@link PlatformWalletService#requireRevenueWallet()} and the brand's own
 * workspace wallet move.
 *
 * <p><b>Atomicity contract:</b> {@link #chargeOnPublish} MUST be called from inside the same
 * {@code @Transactional} method that flips the campaign's status to {@code ACTIVE}
 * ({@code CampaignService.update} for the human/brand-initiated publish path,
 * {@code ConfirmLaunchExecutor.doExecute} for the Meera AI-confirmed launch path) — never as a
 * follow-up job. If the charge throws (insufficient balance, missing config), the caller's
 * {@code RuntimeException}-triggered rollback discards the status flip too, so a campaign that
 * fails to pay the fee never ends up ACTIVE. If a campaign never reaches ACTIVE, this method is
 * never invoked and no fee is ever charged — there is deliberately no refund path.
 *
 * <p><b>Double-charge guard:</b> callers are responsible for only invoking this when the
 * campaign's pre-flip status was NOT already ACTIVE (a simple {@code oldStatus != ACTIVE} check
 * before mutating the entity) — this service does not re-derive "was this a real transition" from
 * the campaign row itself. The actual defense against a double charge, though, is
 * {@link WalletLedgerService#post}'s own idempotency dedup: the ledger idempotency key here is
 * deterministic per campaign ({@code "brand-fee-publish:" + campaignId}), so even if two
 * concurrent activation requests (or an AI replay under a different tool-call idempotency key)
 * both reach this method for the same campaign, only the first actually debits — the second lands
 * on the ledger's insert-first-wins unique-constraint replay path and returns the original
 * posting instead of moving money twice.
 */
@Service
public class BrandCampaignFeeService {

  private static final Logger log = LoggerFactory.getLogger(BrandCampaignFeeService.class);

  private final PlatformFeeConfigRepository configRepository;
  private final WalletLedgerService ledgerService;
  private final PlatformWalletService platformWalletService;
  private final WalletService walletService;
  private final SubscriptionService subscriptionService;

  public BrandCampaignFeeService(
      PlatformFeeConfigRepository configRepository,
      WalletLedgerService ledgerService,
      PlatformWalletService platformWalletService,
      WalletService walletService,
      SubscriptionService subscriptionService) {
    this.configRepository = configRepository;
    this.ledgerService = ledgerService;
    this.platformWalletService = platformWalletService;
    this.walletService = walletService;
    this.subscriptionService = subscriptionService;
  }

  /**
   * Brand-side fee in basis points for {@code workspaceId}. Plan-aware (Task 21): an ACTIVE Pro
   * subscription overrides the rate with {@link Plan#getFeeBps()} (700 = 7%); every other case —
   * Free tier, no subscription row, PAST_DUE/HALTED/CANCELLED Pro, a deactivated Plan row — falls
   * back unchanged to the existing global {@link PlatformFeeConfig#getBrandFeeBps()} (1000 = 10%),
   * which stays the money-path authority for the overwhelming majority of brands.
   *
   * <p>Deliberately checks {@code plan.getCode() == PRO} rather than "does {@code feeBps} happen
   * to be non-null" — V55 originally seeded the Free plan row with {@code fee_bps=1000} too (so
   * its value coincidentally matched the global default), but the global {@code
   * PlatformFeeConfig} row is the admin-editable source of truth for Free-tier brands (e.g. a
   * promo rate change). Routing Free-tier through the Plan row instead would silently go stale
   * the next time an admin edits the global config without also touching the Free plan seed.
   * <b>V57 (Phase 3b Task 22 follow-up, Priya's Phase 3a sign-off point 4) NULLs Free's {@code
   * fee_bps} column</b> so the data now honestly matches this method's "null = use global config"
   * contract instead of relying on this comment alone — this {@code getCode() == PRO} guard was
   * already the real safety net (it short-circuits before ever reading {@code feeBps} for a
   * FREE-code plan), so the NULL migration is a pure data-hygiene fix with no behavior change.
   *
   * <p><b>Fail-open direction (flagged for Kabir/Priya, per Task 21 handoff):</b> if plan
   * resolution throws/times out for any reason, this method logs loudly and falls back to the
   * global config rather than blocking campaign publish — a legitimate publish must never be
   * blocked by a fee-lookup failure. This fails open to the LOWER-risk direction only: worst case
   * a Pro brand is charged the standard 10% for one publish (an overcharge relative to intent, not
   * a security hole) — it can never cause a Free brand to accidentally receive the Pro 7% rate,
   * since the exception path always lands on the global 10% config, never on a cached/guessed Plan.
   */
  @Transactional(readOnly = true)
  public int resolveBrandFeeBps(String workspaceId) {
    Integer planFeeBps = tryResolvePlanFeeBps(workspaceId);
    if (planFeeBps != null) {
      return planFeeBps;
    }
    return requireConfig().getBrandFeeBps();
  }

  private Integer tryResolvePlanFeeBps(String workspaceId) {
    try {
      Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);
      if (plan != null && plan.getCode() == PlanCode.PRO && plan.getFeeBps() != null) {
        return plan.getFeeBps();
      }
      return null;
    } catch (RuntimeException e) {
      // [SEC: money-path fail-open, see javadoc above] Never let a plan-resolution failure block
      // campaign publish — log loudly so the failure is visible to ops, then fall back to the
      // global config, which is always a safe (never-too-low) rate for the caller to fall back to.
      //
      // MP-2 observability follow-up (Priya's Phase 3a sign-off, SHARED_CONTEXT.md 2026-07-14;
      // wiki/tech/security.md MP-2): a single log line is not sufficient on its own for a
      // SUSTAINED SubscriptionService outage — under that scenario every Pro brand silently pays
      // the global 10% instead of their 7% rate (an overcharge/trust liability to paying
      // customers), and that needs to be loud to ops, not just a growing pile of log lines nobody
      // watches. This codebase has no metrics infra to wire a real counter/alert into yet
      // (verified: no io.micrometer/spring-boot-starter-actuator dependency in pom.xml, no
      // MeterRegistry bean anywhere in the codebase) — adding one is a new dependency requiring
      // CTO sign-off + an approved-deps.md entry per TECH-STACK.md rule #6, out of scope to add
      // unilaterally in this pass. Interim solution (documented, per MP-2's explicit fallback
      // allowance): a distinctive, greppable "ALERT: FEE_RESOLUTION_FAILOPEN" log prefix so any
      // existing/future log-based alerting (e.g. a log-scrape rule) can key off it in one line,
      // without waiting on the metrics-infra dependency approval.
      log.error(
          "ALERT: FEE_RESOLUTION_FAILOPEN — failed to resolve active plan for workspace {} —"
              + " falling back to global brand fee config for this publish (a Pro brand here is"
              + " temporarily overcharged the global rate until this is resolved)",
          workspaceId,
          e);
      return null;
    }
  }

  /**
   * Charges the brand-side publish fee for {@code campaign} and debits it from the brand
   * workspace's wallet into the platform revenue wallet. The fee base is
   * {@link Campaign#getBudgetMax()} — the same "pool amount" {@code EscrowService.deriveFundAmount}
   * already treats as the campaign's authoritative budget when no milestone is specified — so the
   * brand-fee base and the escrow-fund base stay consistent with each other.
   *
   * <p>[SEC] The unlocked wallet-balance read here is a fast-path/friendly-error check only, same
   * discipline as {@code EscrowService.initiateFund}'s guard — it exists purely to produce a clear
   * "top up Rs. X" error instead of a generic one. The AUTHORITATIVE check-and-debit happens
   * inside {@link WalletLedgerService#post}, which takes a row-level pessimistic lock on both
   * wallets before comparing balance to amount; a concurrent debit landing in the gap between this
   * pre-check and that lock is still caught there, and translated back into the same brand-facing
   * message below rather than leaking the ledger's generic {@code INSUFFICIENT_BALANCE} code.
   *
   * @param campaign the campaign being published; MUST NOT already be {@code ACTIVE} — the caller
   *     checks {@code oldStatus != ACTIVE} before invoking this (see class javadoc)
   * @param workspaceId the brand workspace that owns {@code campaign} (== {@code
   *     campaign.getWorkspaceId()})
   */
  @Transactional
  public FeeChargeResult chargeOnPublish(Campaign campaign, String workspaceId) {
    BigDecimal budget = campaign.getBudgetMax();
    if (budget == null || budget.signum() <= 0) {
      throw new ApiException(
          "CAMPAIGN_BUDGET_MISSING",
          "Campaign has no budget set — cannot compute the publish fee",
          HttpStatus.CONFLICT);
    }

    int feeBps = resolveBrandFeeBps(workspaceId);
    BigDecimal fee =
        budget
            .multiply(BigDecimal.valueOf(feeBps))
            .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);

    if (fee.signum() <= 0) {
      // A configured 0 bps brand fee (e.g. a promo waiver) is a legal no-op: nothing to charge,
      // nothing to block on.
      return new FeeChargeResult(budget, feeBps, BigDecimal.ZERO, null);
    }

    BigDecimal availableBalance = currentBrandWalletBalance(workspaceId);
    if (availableBalance.compareTo(fee) < 0) {
      throw insufficientBalanceError(fee.subtract(availableBalance));
    }

    Wallet brandWallet = requireBrandWallet(workspaceId);
    Wallet revenueWallet = platformWalletService.requireRevenueWallet();
    String idempotencyKey = "brand-fee-publish:" + campaign.getId();

    WalletLedgerService.LedgerPostingResult posting;
    try {
      posting =
          ledgerService.post(
              brandWallet.getId(),
              revenueWallet.getId(),
              fee,
              brandWallet.getCurrency(),
              WalletTransactionType.PLATFORM_FEE,
              TxnReferenceType.CAMPAIGN,
              campaign.getId(),
              "Brand platform fee (" + feeBps + " bps) on publish for campaign " + campaign.getId(),
              idempotencyKey,
              null);
    } catch (ApiException raced) {
      // The pre-check above is not the authoritative guard (see method javadoc) — a concurrent
      // debit that lands in the gap is caught here, under lock, by WalletLedgerService.post
      // itself. Translate its generic code into the same brand-facing message rather than
      // leaking it verbatim.
      if ("INSUFFICIENT_BALANCE".equals(raced.getCode())) {
        BigDecimal freshBalance = currentBrandWalletBalance(workspaceId);
        BigDecimal shortfall = fee.subtract(freshBalance);
        throw insufficientBalanceError(shortfall.signum() > 0 ? shortfall : fee);
      }
      throw raced;
    }

    return new FeeChargeResult(budget, feeBps, fee, posting);
  }

  /**
   * Resolves the brand's current wallet balance, treating "no wallet row yet" (a brand that has
   * never topped up via B0) the same as a zero balance rather than surfacing a generic
   * {@code WALLET_NOT_FOUND} 404 — both cases mean "cannot afford the fee," and the caller should
   * see the same top-up guidance either way.
   */
  private BigDecimal currentBrandWalletBalance(String workspaceId) {
    try {
      return walletService.requireWorkspaceWallet(workspaceId).getBalance();
    } catch (ApiException e) {
      if ("WALLET_NOT_FOUND".equals(e.getCode())) {
        return BigDecimal.ZERO;
      }
      throw e;
    }
  }

  private Wallet requireBrandWallet(String workspaceId) {
    // Re-checked immediately before the debit (see currentBrandWalletBalance for the same
    // no-wallet-yet handling) — if it's still missing here, the brand hasn't topped up at all,
    // which the balance check above already turned into an INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH
    // error before we ever get this far.
    return walletService.requireWorkspaceWallet(workspaceId);
  }

  private static ApiException insufficientBalanceError(BigDecimal shortfall) {
    BigDecimal display = shortfall.signum() > 0 ? shortfall : BigDecimal.ZERO;
    return new ApiException(
        "INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH",
        "Insufficient wallet balance — top up Rs. "
            + display.setScale(2, RoundingMode.HALF_UP).toPlainString()
            + " to publish this campaign",
        HttpStatus.PAYMENT_REQUIRED);
  }

  private PlatformFeeConfig requireConfig() {
    return configRepository
        .findById(PlatformFeeConfig.SINGLETON_ID)
        .orElseThrow(
            () ->
                new ApiException(
                    "PLATFORM_FEE_CONFIG_MISSING",
                    "Platform fee configuration is not initialized",
                    HttpStatus.INTERNAL_SERVER_ERROR));
  }

  /** Result of a successful (or legally-waived, if bps resolves to 0) publish-fee charge. */
  public record FeeChargeResult(
      BigDecimal campaignBudget,
      int feeBpsApplied,
      BigDecimal feeAmount,
      WalletLedgerService.LedgerPostingResult feePosting) {}
}
