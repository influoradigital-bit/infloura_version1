package com.influora.service.payout;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.User;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.repository.UserRepository;
import com.influora.service.security.CreatorBankPiiCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages Razorpay Contact and Fund Account provisioning for creator payouts (P2-12). Lazily
 * provisions fund accounts on first payout attempt — not eagerly when a creator adds their bank
 * details.
 *
 * <p>Fund account IDs are cached in {@code creator_bank_accounts.razorpay_fund_account_id} after
 * first provisioning to avoid redundant Razorpay API calls.
 */
@Service
public class RazorpayFundAccountService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayFundAccountService.class);

    private final CreatorBankAccountRepository bankAccountRepository;
    private final UserRepository userRepository;
    private final CreatorBankPiiCipher cipher;
    private final RazorpayXClient razorpayXClient;

    public RazorpayFundAccountService(
            CreatorBankAccountRepository bankAccountRepository,
            UserRepository userRepository,
            CreatorBankPiiCipher cipher,
            RazorpayXClient razorpayXClient) {
        this.bankAccountRepository = bankAccountRepository;
        this.userRepository = userRepository;
        this.cipher = cipher;
        this.razorpayXClient = razorpayXClient;
    }

    /**
     * Resolves or provisions a Razorpay fund_account_id for the given creator and bank account.
     * Returns cached fund account ID if already provisioned, otherwise creates Contact + Fund
     * Account in RazorpayX and persists the IDs.
     *
     * @param creatorUserId Creator's user ID
     * @param bankAccountId CreatorBankAccount ID (encrypted bank/UPI details)
     * @return Razorpay fund_account_id ready for payout initiation
     * @throws ApiException if bank account not found, cool-down active, or provisioning fails
     */
    @Transactional
    public String resolveFundAccountId(String creatorUserId, String bankAccountId) {
        // Load the creator's bank account (validates ownership + existence)
        CreatorBankAccount bankAccount =
                bankAccountRepository
                        .findByIdAndCreatorUserId(bankAccountId, creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "BANK_ACCOUNT_NOT_FOUND",
                                                "Bank account not found for this creator",
                                                HttpStatus.NOT_FOUND));

        // Check 24h cool-down (security requirement from Kabir M-K6-C3-2)
        if (!bankAccount.isUsableAt(java.time.Instant.now())) {
            throw new ApiException(
                    "BANK_COOLDOWN_ACTIVE",
                    "New bank accounts cannot be used for payouts for 24 hours",
                    HttpStatus.TOO_EARLY);
        }

        // Return cached fund account ID if already provisioned
        if (bankAccount.getRazorpayFundAccountId() != null
                && !bankAccount.getRazorpayFundAccountId().isBlank()) {
            log.debug(
                    "Using cached Razorpay fund account: userId={}, fundAccountId={}",
                    creatorUserId,
                    bankAccount.getRazorpayFundAccountId());
            return bankAccount.getRazorpayFundAccountId();
        }

        // Lazy provisioning: create Contact + Fund Account in RazorpayX
        log.info("Provisioning Razorpay fund account for creator: userId={}", creatorUserId);

        User creator =
                userRepository
                        .findById(creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));

        // Create or reuse Contact
        String contactId;
        if (bankAccount.getRazorpayContactId() != null
                && !bankAccount.getRazorpayContactId().isBlank()) {
            contactId = bankAccount.getRazorpayContactId();
            log.debug("Reusing existing Razorpay contact: contactId={}", contactId);
        } else {
            contactId =
                    razorpayXClient.createContact(
                            creator.getEmail(), // Use email as name fallback if no display name
                            creator.getEmail(),
                            "creator_" + creatorUserId);
            log.debug("Created new Razorpay contact: contactId={}", contactId);
        }

        // Decrypt bank details for Razorpay API
        String decryptedAccount = cipher.decrypt(bankAccount.getAccountCiphertext());
        String decryptedIfsc =
                bankAccount.getIfscCiphertext() == null
                        ? null
                        : cipher.decrypt(bankAccount.getIfscCiphertext());

        // Determine account type and create fund account
        String accountType = determineAccountType(bankAccount.getInstrumentType());
        String fundAccountId =
                razorpayXClient.createFundAccount(contactId, decryptedAccount, decryptedIfsc, accountType);

        // Cache the IDs in our database
        bankAccount.markRazorpayFundAccountProvisioned(contactId, fundAccountId);
        bankAccountRepository.save(bankAccount);

        log.info(
                "Razorpay fund account provisioned: userId={}, fundAccountId={}", creatorUserId, fundAccountId);
        return fundAccountId;
    }

    /**
     * Maps our instrument_type to Razorpay's account_type. Our schema uses "BANK" or "UPI"
     * (uppercase), Razorpay expects "bank_account" or "vpa".
     */
    private String determineAccountType(String instrumentType) {
        if ("BANK".equalsIgnoreCase(instrumentType)) {
            return "bank_account";
        } else if ("UPI".equalsIgnoreCase(instrumentType)) {
            return "vpa";
        } else {
            throw new ApiException(
                    "UNSUPPORTED_INSTRUMENT_TYPE",
                    "Unsupported instrument type: " + instrumentType,
                    HttpStatus.BAD_REQUEST);
        }
    }
}
