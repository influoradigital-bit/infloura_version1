package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CreatorTaxRegistrationStatus;
import com.influora.repository.CreatorProfileRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creator.CreatorTaxIdentityDtos.TaxIdentitySubmitRequest;
import com.influora.web.dto.creator.CreatorTaxIdentityDtos.TaxIdentitySubmitResponse;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D14-B creator-facing tax-identity (GSTIN/PAN) capture — backs {@code POST /me/tax-identity}.
 *
 * <p>Persists onto the existing {@code CreatorProfile.gstin}/{@code .pan}/{@code
 * .taxRegistrationStatus} columns (V20260715120000__creator_tax_identity.sql) via {@link
 * CreatorProfile#applyTaxIdentity}, the same null-guarded partial-update entrypoint {@code
 * CreatorInvoiceCodeService} and {@code CampaignServiceInvoiceService} already use internally to
 * auto-provision a {@code creatorInvoiceCode} on first Doc#2 issuance — this service never touches
 * that code (passes {@code null}), so the statutory invoice-number series assignment is untouched.
 *
 * <p><b>PII-at-rest note (flagged for Kabir):</b> {@code gstin}/{@code pan} are stored in plaintext,
 * matching the existing D14-B schema decision — NOT the AES-GCM-at-rest discipline {@code
 * CreatorBankAccountService} enforces for bank/UPI details. This is a deliberate continuation of
 * the status quo, not a new gap introduced here: {@code CampaignServiceInvoicePdfService}, {@code
 * CommissionInvoicePdfService}, and {@code CampaignServiceInvoiceService} all already read {@code
 * creator.getGstin()}/{@code .getPan()} as plaintext directly off the entity to print on generated
 * GST invoices — retrofitting encryption onto these two columns would require decrypting at every
 * one of those call sites too, which is out of scope for wiring this one submit endpoint. GSTIN
 * itself is a public business-registration number (printed on invoices, independently look-up-able
 * on the GST portal) so plaintext-at-rest is a reasonable call regardless. PAN is a more sensitive
 * personal tax ID than GSTIN and arguably deserves the same encrypted-column treatment bank details
 * get — Kabir should weigh in on whether that's worth a follow-up migration + decrypt-at-invoice-time
 * change before this goes live for real creator PAN numbers at volume.
 */
@Service
public class CreatorTaxIdentityService {

    private static final Pattern GSTIN_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");
    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]{1}$");

    private final CreatorContextService creatorContext;
    private final CreatorProfileRepository creatorProfileRepository;

    public CreatorTaxIdentityService(
            CreatorContextService creatorContext, CreatorProfileRepository creatorProfileRepository) {
        this.creatorContext = creatorContext;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    @Transactional
    public TaxIdentitySubmitResponse submit(AuthPrincipal principal, TaxIdentitySubmitRequest req) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        // Never trust client-side trim/uppercase (zod's .toUpperCase() on the FE is a UX
        // convenience, not a security boundary) — normalize independently before validating.
        String gstin = normalize(req.gstin());
        String pan = normalize(req.pan());

        if (gstin == null && pan == null) {
            throw new ApiException(
                    "TAX_IDENTITY_REQUIRED",
                    "Enter a GSTIN or PAN to continue",
                    HttpStatus.BAD_REQUEST);
        }
        if (gstin != null && !GSTIN_PATTERN.matcher(gstin).matches()) {
            throw new ApiException(
                    "INVALID_GSTIN", "Invalid GSTIN format (e.g. 27ABCDE1234F1Z5)", HttpStatus.BAD_REQUEST);
        }
        if (pan != null && !PAN_PATTERN.matcher(pan).matches()) {
            throw new ApiException(
                    "INVALID_PAN", "Invalid PAN format (e.g. ABCDE1234F)", HttpStatus.BAD_REQUEST);
        }

        // A submitted GSTIN implies the creator is GST-registered; submitting PAN alone doesn't
        // change registration status either way — pass null there so applyTaxIdentity's
        // null-guarded update leaves whatever status already exists untouched. creatorInvoiceCode
        // is intentionally left alone (null) — that stays owned by CreatorInvoiceCodeService.
        CreatorTaxRegistrationStatus status =
                gstin != null ? CreatorTaxRegistrationStatus.GST_REGISTERED : null;

        profile.applyTaxIdentity(gstin, pan, status, null);
        creatorProfileRepository.save(profile);

        return new TaxIdentitySubmitResponse(
                profile.getGstin(), maskPan(profile.getPan()), profile.getTaxRegistrationStatus().name());
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 10) {
            return pan;
        }
        return "******" + pan.substring(6);
    }
}
