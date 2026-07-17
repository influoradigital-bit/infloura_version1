package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CreatorProfileRepository;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns each creator's stable, per-creator statutory invoice-series code ({@code
 * creator_profiles.creator_invoice_code}, {@code uq_creator_profiles_invoice_code}, D14-C).
 *
 * <p><b>[Meera, D14 build-verification fix — 2026-07-15]</b> {@link CampaignServiceInvoiceService}
 * previously derived this code inline as {@code "CRT" + creator.getId().substring(0, 9)}.
 * {@code creator.getId()} is a ULID ({@code Ulids.newUlid()}), whose leading ~10 characters ARE
 * the 48-bit millisecond timestamp — so any two creators onboarded within the same ~8ms window
 * were assigned the IDENTICAL code. The second such creator's first Doc#2 issuance then threw a
 * {@link DataIntegrityViolationException} out of {@code creatorProfileRepository.save(creator)},
 * uncaught inside the caller's {@code @Transactional} escrow-release method — marking that
 * transaction rollback-only and reversing an already-completed creator payout.
 *
 * <p>This service fixes that on three axes:
 *
 * <ol>
 *   <li><b>Collision-free source</b> — the code suffix is drawn from a {@link SecureRandom} draw
 *       over a Crockford-style base32 alphabet, not a timestamp-derived substring, so two
 *       concurrent onboardings no longer share a birthday-paradox window.
 *   <li><b>Retry on genuine collision</b> — a real (astronomically unlikely, but possible) random
 *       collision is caught and retried with a fresh draw instead of being assumed away.
 *   <li><b>Transaction isolation</b> — {@link #resolveOrAssign} runs in its OWN transaction
 *       ({@link Propagation#REQUIRES_NEW}, via a dedicated Spring bean so the transactional proxy
 *       actually applies — self-invocation from within {@code CampaignServiceInvoiceService} would
 *       silently no-op the propagation). A collision here can therefore never mark the caller's
 *       escrow-release transaction rollback-only; it is retried and resolved entirely within this
 *       method's own transaction boundary.
 * </ol>
 */
@Service
public class CreatorInvoiceCodeService {

    private static final Logger log = LoggerFactory.getLogger(CreatorInvoiceCodeService.class);

    // Crockford base32 minus I, L, O, U — same family of alphabet as Ulids, avoids visually
    // ambiguous characters on a printed statutory invoice.
    private static final String CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    // "CRT" (3) + 9 random chars = 12, matching creator_profiles.creator_invoice_code VARCHAR(12)
    // (V20260715120000__creator_tax_identity.sql).
    private static final int SUFFIX_LENGTH = 9;
    private static final int MAX_ATTEMPTS = 10;

    private final CreatorProfileRepository creatorProfileRepository;
    private final SecureRandom random = new SecureRandom();

    public CreatorInvoiceCodeService(CreatorProfileRepository creatorProfileRepository) {
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /**
     * Auto-provisions a stable per-creator statutory series code on first Doc#2 issuance if the
     * creator has not been through explicit GST onboarding yet (D14-B: schema captures the field,
     * but enforcement/onboarding is a runtime toggle, not a schema-blocking prerequisite — a
     * creator must still be able to get paid and invoiced even before formal onboarding assigns
     * one). Persisted onto the profile so it never changes for this creator again.
     *
     * <p>Re-fetches the creator profile fresh inside this method's own transaction (rather than
     * accepting a possibly-detached entity from the caller's transaction) so the read-check-write
     * of {@code creatorInvoiceCode} is atomic within the {@code REQUIRES_NEW} boundary.
     *
     * @param creatorProfileId {@link CreatorProfile#getId()} (the profile's own PK, NOT the user id)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String resolveOrAssign(String creatorProfileId) {
        CreatorProfile creator =
                creatorProfileRepository
                        .findById(creatorProfileId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "Creator profile not found for id " + creatorProfileId,
                                                HttpStatus.CONFLICT));

        if (creator.getCreatorInvoiceCode() != null && !creator.getCreatorInvoiceCode().isBlank()) {
            return creator.getCreatorInvoiceCode();
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String code = "CRT" + randomSuffix();
            creator.applyTaxIdentity(null, null, null, code);
            try {
                creatorProfileRepository.saveAndFlush(creator);
                return code;
            } catch (DataIntegrityViolationException collision) {
                log.warn(
                        "creator_invoice_code collision on attempt {}/{} for creator profile {} — retrying"
                                + " with a fresh random code",
                        attempt,
                        MAX_ATTEMPTS,
                        creatorProfileId);
            }
        }

        throw new ApiException(
                "CREATOR_INVOICE_CODE_ASSIGN_FAILED",
                "Could not assign a unique creator_invoice_code for creator profile "
                        + creatorProfileId
                        + " after "
                        + MAX_ATTEMPTS
                        + " attempts",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
