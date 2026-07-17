package com.influora.web.dto.wallet;

import com.influora.domain.entity.CreatorBankAccount;
import jakarta.validation.constraints.NotBlank;

/**
 * DTOs for the creator payout-instrument endpoints on {@code WalletController}, backed by the
 * encrypted {@code CreatorBankAccountService} (Kabir M-K6-C3-2) — never a plaintext store.
 */
public class BankAccountDtos {

    private BankAccountDtos() {}

    /**
     * Add a UPI or bank instrument. {@code accountOrVpa} is write-only — encrypted immediately by
     * {@code CreatorBankAccountService} and never returned by any endpoint after this call.
     */
    public record AddBankAccountRequest(
            @NotBlank String type,
            @NotBlank String accountOrVpa,
            String ifsc,
            String displayMask) {}

    /** Masked, display-safe view of a stored instrument — never contains decrypted account/IFSC values. */
    public record BankAccountResponse(
            String id, String type, String displayMask, boolean isPrimary, boolean usable) {

        public static BankAccountResponse from(CreatorBankAccount account) {
            return new BankAccountResponse(
                    account.getId(),
                    account.getInstrumentType(),
                    account.getDisplayMask(),
                    account.isPrimary(),
                    account.isUsableAt(java.time.Instant.now()));
        }
    }
}
