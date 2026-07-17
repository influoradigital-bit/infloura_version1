package com.influora.service.payout;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.security.CreatorBankPiiCipher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypt-before-ship gate for creator bank/UPI (Kabir M-K6-C3-2, Spec 12 §3.1 / §4).
 *
 * <p>No plaintext bank/UPI columns exist. First onboarding PR must use this service (AES-GCM + 24h
 * cool-down). Controllers / RazorpayX fund-account wiring may land later — persistence without
 * encryption is rejected by design.
 */
@Service
public class CreatorBankAccountService {

    public static final Duration COOL_DOWN = Duration.ofHours(24);

    private final CreatorContextService creatorContext;
    private final CreatorBankAccountRepository repository;
    private final CreatorBankPiiCipher cipher;

    public CreatorBankAccountService(
            CreatorContextService creatorContext,
            CreatorBankAccountRepository repository,
            CreatorBankPiiCipher cipher) {
        this.creatorContext = creatorContext;
        this.repository = repository;
        this.cipher = cipher;
    }

    /**
     * Encrypts account + optional IFSC, persists ciphertext only, starts 24h cool-down. Returns
     * the stored row (never plaintext).
     */
    @Transactional
    public CreatorBankAccount addInstrument(
            AuthPrincipal principal,
            String instrumentType,
            String accountOrVpa,
            String ifsc,
            String displayMask) {
        var profile = creatorContext.requireCreatorProfile(principal);
        if (accountOrVpa == null || accountOrVpa.isBlank()) {
            throw new ApiException(
                    "INVALID_BANK_DETAILS", "Account / UPI is required", HttpStatus.BAD_REQUEST);
        }
        if (instrumentType == null || instrumentType.isBlank()) {
            throw new ApiException(
                    "INVALID_BANK_DETAILS", "Instrument type is required", HttpStatus.BAD_REQUEST);
        }

        Instant now = Instant.now();
        String accountCipher = cipher.encrypt(accountOrVpa.trim());
        String ifscCipher =
                ifsc == null || ifsc.isBlank() ? null : cipher.encrypt(ifsc.trim().toUpperCase());
        String mask =
                displayMask == null || displayMask.isBlank()
                        ? maskFrom(accountOrVpa)
                        : displayMask.trim();

        // A creator's FIRST instrument becomes primary automatically (there is nothing to choose
        // between yet). Every subsequent instrument is added as non-primary — adding a backup
        // account must never silently redirect where real money already goes; the creator has to
        // explicitly call setPrimary to switch it.
        //
        // [QA fix] Locks every existing row for this creator before checking "is this the first
        // one" — without the lock, two concurrent first-adds for the same brand-new creator could
        // both observe an empty list and both set isPrimary=true, which the plain
        // findByCreatorUserIdOrderByCreatedAtDesc().isEmpty() check used to allow. The lock only
        // has rows to hold once at least one exists; the uq_creator_bank_accounts_primary_marker
        // unique index (V62) is the authoritative backstop for the true zero-rows race window (two
        // inserts with nothing yet to lock) — caught below as a clean 409 instead of silent
        // corruption.
        boolean isFirstInstrument = repository.lockAllForCreatorUpdate(profile.getUserId()).isEmpty();

        CreatorBankAccount row =
                CreatorBankAccount.createEncrypted(
                        Ulids.newUlid(),
                        profile.getUserId(),
                        accountCipher,
                        ifscCipher,
                        instrumentType.trim().toUpperCase(),
                        mask,
                        isFirstInstrument,
                        now.plus(COOL_DOWN),
                        now);
        try {
            return repository.save(row);
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(
                    "PRIMARY_CONFLICT",
                    "Your payout methods changed at the same time — please retry",
                    HttpStatus.CONFLICT);
        }
    }

    /** Masked list for a creator's own settings UI — never decrypts, only returns display-safe fields. */
    @Transactional(readOnly = true)
    public List<CreatorBankAccount> listForCreator(AuthPrincipal principal) {
        var profile = creatorContext.requireCreatorProfile(principal);
        return repository.findByCreatorUserIdOrderByCreatedAtDesc(profile.getUserId());
    }

    /**
     * Explicitly sets one of the creator's own instruments as primary, clearing the flag on every
     * other instrument for that creator in the same transaction. {@link PayoutService} resolves the
     * real payout destination from this flag.
     *
     * <p>[QA fix] Locks every row for this creator up front (rather than separately reading the
     * target and the current-primary row unlocked) so two concurrent setPrimary calls for the same
     * creator can never both succeed — the second transaction blocks on the lock until the first
     * commits, then re-reads the now-current state instead of racing against stale reads. The
     * {@code uq_creator_bank_accounts_primary_marker} unique index (V62) is the DB-level backstop if
     * this lock is ever bypassed.
     */
    @Transactional
    public CreatorBankAccount setPrimary(AuthPrincipal principal, String bankAccountId) {
        var profile = creatorContext.requireCreatorProfile(principal);
        List<CreatorBankAccount> locked = repository.lockAllForCreatorUpdate(profile.getUserId());

        CreatorBankAccount target =
                locked.stream()
                        .filter(a -> a.getId().equals(bankAccountId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "BANK_ACCOUNT_NOT_FOUND",
                                                "Bank account not found",
                                                HttpStatus.NOT_FOUND));
        if (target.isPrimary()) {
            return target;
        }

        locked.stream()
                .filter(a -> a.isPrimary() && !a.getId().equals(target.getId()))
                .forEach(
                        current -> {
                            current.clearPrimary();
                            repository.save(current);
                        });

        target.markPrimary();
        try {
            return repository.save(target);
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(
                    "PRIMARY_CONFLICT",
                    "Your payout methods changed at the same time — please retry",
                    HttpStatus.CONFLICT);
        }
    }

    /** Decrypt for payments-only use after cool-down. Throws if cool-down has not elapsed. */
    @Transactional(readOnly = true)
    public String requireDecryptedAccount(String creatorUserId, String bankAccountId) {
        CreatorBankAccount row =
                repository
                        .findByIdAndCreatorUserId(bankAccountId, creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "BANK_ACCOUNT_NOT_FOUND",
                                                "Bank account not found",
                                                HttpStatus.NOT_FOUND));
        if (!row.isUsableAt(Instant.now())) {
            throw new ApiException(
                    "BANK_COOLDOWN_ACTIVE",
                    "New bank accounts cannot be used for payouts for 24 hours",
                    HttpStatus.TOO_EARLY);
        }
        return cipher.decrypt(row.getAccountCiphertext());
    }

    private static String maskFrom(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }
}
