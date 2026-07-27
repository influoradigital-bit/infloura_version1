package com.influora.service.escrow;

import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.service.PlatformFeeService;
import com.influora.service.PlatformWalletService;
import com.influora.service.WalletLedgerService;
import com.influora.service.WalletService;
import org.springframework.stereotype.Service;

/**
 * Today's real (and only) {@link EscrowBackend} — fund/release/refund exactly as {@code
 * EscrowService} implemented them inline before this extraction: every movement posted through
 * {@link WalletLedgerService#post} (brand wallet -> platform clearing wallet -> creator wallet),
 * with the creator-side platform fee split via {@link PlatformFeeService#deductAtRelease} before
 * any release leg posts.
 *
 * <p>Registered unconditionally ({@code @Service}, no {@code @Profile}/{@code
 * @ConditionalOnProperty}) — it is the only {@link EscrowBackend} implementation that exists today,
 * so conditional bean selection would have nothing to select between yet. When a real {@code
 * RouteEscrowBackend} is built, gate selection with a config property (mirroring {@code R2Config}'s
 * {@code @ConditionalOnProperty}, not {@code MalwareScanService}'s {@code @Profile} — profile
 * selects by environment, which is wrong here since Ledger must stay the prod default even after
 * Route exists, until Route is actually cut over):
 * {@code @ConditionalOnProperty(prefix = "influora.escrow", name = "backend", havingValue = "route")}
 * on {@code RouteEscrowBackend}, and
 * {@code @ConditionalOnProperty(prefix = "influora.escrow", name = "backend", havingValue = "ledger", matchIfMissing = true)}
 * added to this class, so exactly one bean is ever selected.
 */
@Service
public class LedgerEscrowBackend implements EscrowBackend {

    private final WalletLedgerService ledgerService;
    private final PlatformWalletService platformWalletService;
    private final PlatformFeeService platformFeeService;
    private final WalletService walletService;

    public LedgerEscrowBackend(
            WalletLedgerService ledgerService,
            PlatformWalletService platformWalletService,
            PlatformFeeService platformFeeService,
            WalletService walletService) {
        this.ledgerService = ledgerService;
        this.platformWalletService = platformWalletService;
        this.platformFeeService = platformFeeService;
        this.walletService = walletService;
    }

    @Override
    public FundOutcome fund(FundCommand command) {
        Wallet brandWallet = walletService.requireWorkspaceWallet(command.workspaceId());
        Wallet clearingWallet = platformWalletService.requireClearingWallet();

        var posting =
                ledgerService.post(
                        brandWallet.getId(),
                        clearingWallet.getId(),
                        command.amount(),
                        command.currency(),
                        WalletTransactionType.ESCROW_HOLD,
                        TxnReferenceType.ESCROW_HOLD,
                        command.escrowHoldId(),
                        command.description(),
                        command.idempotencyKey(),
                        command.gatewayRef());

        return new FundOutcome(posting.debitLeg().getId());
    }

    @Override
    public ReleaseOutcome release(ReleaseCommand command) {
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet payeeWallet = walletService.requireOrCreateUserWallet(command.payeeUserId());

        var feeDeduction =
                platformFeeService.deductAtRelease(
                        clearingWallet,
                        command.feeReferenceId(),
                        command.payeeUserId(),
                        command.grossAmount(),
                        command.currency(),
                        command.escrowHoldId());

        var posting =
                ledgerService.post(
                        clearingWallet.getId(),
                        payeeWallet.getId(),
                        feeDeduction.netAmount(),
                        command.currency(),
                        WalletTransactionType.ESCROW_RELEASE,
                        command.ledgerReferenceType(),
                        command.ledgerReferenceId(),
                        command.description(),
                        command.idempotencyKey(),
                        null);

        return new ReleaseOutcome(posting.creditLeg().getId());
    }

    @Override
    public RefundOutcome refund(RefundCommand command) {
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet brandWallet = walletService.requireWorkspaceWallet(command.workspaceId());

        var posting =
                ledgerService.post(
                        clearingWallet.getId(),
                        brandWallet.getId(),
                        command.amount(),
                        command.currency(),
                        WalletTransactionType.ESCROW_REFUND,
                        TxnReferenceType.ESCROW_HOLD,
                        command.escrowHoldId(),
                        command.description(),
                        command.idempotencyKey(),
                        null);

        return new RefundOutcome(posting.creditLeg().getId());
    }
}
