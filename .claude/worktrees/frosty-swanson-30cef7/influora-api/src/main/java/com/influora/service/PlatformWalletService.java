package com.influora.service;

import com.influora.common.Ulids;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.WalletOwnerType;
import com.influora.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the platform's single clearing wallet — the counterparty leg for every escrow
 * hold/release/refund and platform-fee posting. {@code WalletLedgerService.post()} requires two
 * distinct wallets for every double-entry movement; this is the "other side" of a brand's escrow
 * hold (brand wallet DEBIT -> platform clearing wallet CREDIT) and of a milestone release
 * (platform clearing wallet DEBIT -> creator/brand wallet CREDIT).
 *
 * <p>Reserved owner id {@code PLATFORM_CLEARING_WALLET_OWNER_ID} is a fixed, non-ULID sentinel so
 * it can never collide with a real workspace/user id (those are always 26-char ULIDs). Created
 * lazily on first use.
 */
@Service
public class PlatformWalletService {

    /** Deliberately not a ULID — a real workspace/user id can never collide with this sentinel. */
    public static final String PLATFORM_CLEARING_WALLET_OWNER_ID = "platform-clearing-wallet";

    private final WalletRepository walletRepository;

    public PlatformWalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet requireClearingWallet() {
        return walletRepository
                .findByOwnerId(PLATFORM_CLEARING_WALLET_OWNER_ID)
                .orElseGet(this::createClearingWallet);
    }

    private Wallet createClearingWallet() {
        Wallet wallet = Wallet.forWorkspace(Ulids.newUlid(), PLATFORM_CLEARING_WALLET_OWNER_ID);
        // forWorkspace defaults ownerType to WORKSPACE; that's fine — this row is never surfaced
        // to any brand/creator UI, it exists purely as the ledger's platform-side counterparty.
        return walletRepository.save(wallet);
    }
}
