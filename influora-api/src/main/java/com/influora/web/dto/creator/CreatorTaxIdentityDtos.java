package com.influora.web.dto.creator;

import jakarta.validation.constraints.Size;

/**
 * D14-B — creator self-service GSTIN/PAN capture ({@code POST /me/tax-identity}).
 *
 * <p>Fills the gap flagged in {@code src/lib/api.ts}'s {@code creatorTaxIdentity} client: the
 * schema (`CreatorProfile.gstin`/`.pan`/`.taxRegistrationStatus`, D14-B) and {@code
 * CreatorProfile#applyTaxIdentity} already existed, but were previously only reachable from the
 * internal auto-provisioning path in {@code CampaignServiceInvoiceService} — no creator-facing HTTP
 * route existed to submit them directly.
 *
 * <p>Both fields are optional at the DTO level (a creator may hold only a PAN, no GST
 * registration) — {@link com.influora.service.CreatorTaxIdentityService} enforces "at least one of
 * gstin/pan present" plus the authoritative format checks server-side (never trusts the client's
 * upper-cased/trimmed value; re-normalizes and re-validates independently). No {@code @Pattern}
 * annotation here on purpose — annotation-level validation would run before this class gets a
 * chance to trim/uppercase, rejecting a client that sent (correctly) lowercase input; the service
 * normalizes then validates so the same request shape works regardless of client casing.
 */
public final class CreatorTaxIdentityDtos {

    private CreatorTaxIdentityDtos() {}

    public record TaxIdentitySubmitRequest(@Size(max = 30) String gstin, @Size(max = 30) String pan) {}

    /**
     * {@code gstin} is echoed in full — a GSTIN is a business's public statutory registration
     * number (it is printed on every GST invoice issued to a brand and is independently look-up-able
     * on the public GST portal), so it does not carry the same secrecy expectation as a bank
     * account number. {@code maskedPan} deliberately withholds the middle 6 characters of the PAN
     * (a personal tax-ID, more sensitive than a GSTIN) — same "never echo full PII back" discipline
     * as {@code CreatorBankAccountService}'s masked list response.
     */
    public record TaxIdentitySubmitResponse(String gstin, String maskedPan, String taxRegistrationStatus) {}
}
