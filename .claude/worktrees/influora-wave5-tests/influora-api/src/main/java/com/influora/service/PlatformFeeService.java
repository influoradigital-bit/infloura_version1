package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.PlatformFeeConfigRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator-side platform fee at escrow release (§1.1 CEO ruling). Resolves the take rate from the
 * DB-backed {@link PlatformFeeConfig} singleton and posts the fee through
 * {@link WalletLedgerService#post} into the platform revenue wallet — never mutates
 * {@link Wallet#applyBalanceDelta} directly.
 */
@Service
public class PlatformFeeService {

  private final PlatformFeeConfigRepository configRepository;
  private final WalletLedgerService ledgerService;
  private final PlatformWalletService platformWalletService;

  public PlatformFeeService(
      PlatformFeeConfigRepository configRepository,
      WalletLedgerService ledgerService,
      PlatformWalletService platformWalletService) {
    this.configRepository = configRepository;
    this.ledgerService = ledgerService;
    this.platformWalletService = platformWalletService;
  }

  /** Global creator-side fee in basis points — read from DB, no Java fallback constant. */
  @Transactional(readOnly = true)
  public int resolveCreatorFeeBps() {
    return requireConfig().getDefaultFeeBps();
  }

  /**
   * Splits a gross release amount into platform fee and creator net using the configured bps.
   * Amounts are DECIMAL(14,2) rupees, matching the ledger.
   */
  public FeeSplit split(BigDecimal grossAmount, int feeBps) {
    BigDecimal fee =
        grossAmount
            .multiply(BigDecimal.valueOf(feeBps))
            .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
    BigDecimal net = grossAmount.subtract(fee);
    return new FeeSplit(grossAmount, feeBps, fee, net);
  }

  /**
   * Deducts the creator-side platform fee from {@code grossAmount} at milestone release. Posts a
   * {@link WalletTransactionType#PLATFORM_FEE} ledger movement from the clearing wallet to the
   * platform revenue wallet before the caller posts the net {@code ESCROW_RELEASE} leg.
   *
   * @param clearingWallet the platform clearing wallet holding the funded escrow balance
   * @param milestoneId persisted milestone id — used as the ledger reference for traceability
   * @param creatorUserId payee creator user id (audit description only)
   * @param grossAmount server-derived from {@link com.influora.domain.entity.EscrowHold#getAmount()}
   * @param escrowHoldId used to build a stable idempotency key per release event
   */
  @Transactional
  public FeeDeductionResult deductAtRelease(
      Wallet clearingWallet,
      String milestoneId,
      String creatorUserId,
      BigDecimal grossAmount,
      String currency,
      String escrowHoldId) {

    int feeBps = resolveCreatorFeeBps();
    FeeSplit split = split(grossAmount, feeBps);

    WalletLedgerService.LedgerPostingResult feePosting = null;
    if (split.platformFee().signum() > 0) {
      Wallet revenueWallet = platformWalletService.requireRevenueWallet();
      feePosting =
          ledgerService.post(
              clearingWallet.getId(),
              revenueWallet.getId(),
              split.platformFee(),
              currency,
              WalletTransactionType.PLATFORM_FEE,
              TxnReferenceType.MILESTONE,
              milestoneId,
              "Platform fee ("
                  + feeBps
                  + " bps) on milestone release for creator "
                  + creatorUserId,
              "release-fee:" + escrowHoldId,
              null);
    }

    return new FeeDeductionResult(
        grossAmount, feeBps, split.platformFee(), split.netAmount(), feePosting);
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

  public record FeeSplit(
      BigDecimal grossAmount, int feeBps, BigDecimal platformFee, BigDecimal netAmount) {}

  public record FeeDeductionResult(
      BigDecimal grossAmount,
      int feeBpsApplied,
      BigDecimal platformFee,
      BigDecimal netAmount,
      WalletLedgerService.LedgerPostingResult feePosting) {}
}
