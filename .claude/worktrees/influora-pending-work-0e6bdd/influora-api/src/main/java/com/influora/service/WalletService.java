package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.WalletRepository;
import com.influora.web.dto.money.MoneyDtos.WalletBalanceResponse;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only balance queries plus a thin wrapper around {@link WalletLedgerService} for simple
 * deposit/withdrawal postings. Never mutates {@link Wallet} balances directly — every money
 * movement goes through {@code WalletLedgerService.post()} so the ledger stays the single source
 * of truth (Guardrail 1 / C-3).
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletLedgerService ledgerService;

    public WalletService(WalletRepository walletRepository, WalletLedgerService ledgerService) {
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    public WalletBalanceResponse getBalance(String workspaceId) {
        Wallet wallet = requireWorkspaceWallet(workspaceId);
        return new WalletBalanceResponse(
                wallet.getId(), wallet.getBalance(), wallet.getEscrowBalance(), wallet.getCurrency());
    }

    @Transactional(readOnly = true)
    public Wallet requireWorkspaceWallet(String workspaceId) {
        return walletRepository
                .findByOwnerId(workspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "WALLET_NOT_FOUND", "Wallet not found for workspace", HttpStatus.NOT_FOUND));
    }

    /** Lazily creates a creator's wallet on first payout — creators aren't given one at signup. */
    @Transactional
    public Wallet requireOrCreateUserWallet(String userId) {
        return walletRepository
                .findByOwnerId(userId)
                .orElseGet(() -> walletRepository.save(Wallet.forUser(Ulids.newUlid(), userId)));
    }

    /**
     * Deposits externally-received funds (e.g. a confirmed Razorpay top-up) into a workspace's
     * wallet. {@code amount} must already be server-derived from a verified payment gateway
     * event — never a raw client-supplied value.
     */
    @Transactional
    public void deposit(
            String workspaceId,
            String platformWalletId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String gatewayRef) {
        Wallet destination = requireWorkspaceWallet(workspaceId);
        ledgerService.post(
                platformWalletId,
                destination.getId(),
                amount,
                currency,
                WalletTransactionType.DEPOSIT,
                TxnReferenceType.DEPOSIT_ORDER,
                Ulids.newUlid(),
                "Wallet deposit",
                idempotencyKey,
                gatewayRef);
    }

    /**
     * Withdraws funds from a workspace's wallet to the platform's clearing wallet ahead of a
     * payout. {@code amount} must already be server-derived (e.g. from a released milestone) —
     * never a raw client-supplied value.
     */
    @Transactional
    public void withdraw(
            String workspaceId,
            String platformWalletId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String gatewayRef) {
        Wallet source = requireWorkspaceWallet(workspaceId);
        ledgerService.post(
                source.getId(),
                platformWalletId,
                amount,
                currency,
                WalletTransactionType.WITHDRAWAL,
                TxnReferenceType.MANUAL,
                Ulids.newUlid(),
                "Wallet withdrawal",
                idempotencyKey,
                gatewayRef);
    }
}
