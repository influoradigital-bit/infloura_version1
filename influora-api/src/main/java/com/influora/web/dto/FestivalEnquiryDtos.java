package com.influora.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Wire types for the PUBLIC Festival Box enquiry endpoint (T-FESTIVALBOX-0905). Submitted by an
 * anonymous visitor on /festival-box, so every constraint here is the only thing standing between
 * the open internet and a row in {@code festival_enquiries} — bean validation runs BEFORE
 * {@code FestivalEnquiryService} sees the payload.
 */
public final class FestivalEnquiryDtos {

    private FestivalEnquiryDtos() {}

    /**
     * One submission. A single request shape serves both sides of the page toggle rather than two
     * endpoints, because the page posts one form whose fields swap — and a second endpoint would
     * need its own duplicate throttle, its own permitAll entry, and its own chance to drift.
     *
     * <p>Which fields are REQUIRED depends on {@code type}, which bean validation cannot express
     * across fields; that cross-field rule is enforced in {@code FestivalEnquiryService#submit} and
     * is the reason the type-specific fields are only length-bounded here, not {@code @NotBlank}.
     *
     * @param type BRAND or CREATOR — case-insensitive, validated against the enum in the service.
     * @param website brand site. Length-bounded only; the service enforces http/https so a
     *     {@code javascript:} value can never be stored and later rendered as a link in admin.
     * @param followers self-reported, never trusted as a metric — the roster check re-verifies via
     *     Meta. Nullable: a creator who leaves it blank must still be able to apply.
     * @param honeypot anti-bot decoy. The form renders it visually hidden and a human never fills
     *     it, so ANY non-blank value means a bot. The service answers such a submission with the
     *     same 200 a real one gets, while persisting nothing — a bot that can distinguish rejection
     *     from acceptance simply retries with the field omitted.
     */
    public record SubmitEnquiryRequest(
            @NotBlank(message = "type is required") String type,
            @Size(max = 64) String edition,
            @NotBlank(message = "Name is required") @Size(max = 120) String name,
            @NotBlank(message = "Email is required")
                    @Email(message = "Valid email is required")
                    @Size(max = 255)
                    String email,
            @Size(max = 32)
                    @Pattern(
                            regexp = "^$|^[+0-9 ()-]{6,32}$",
                            message = "Phone must be 6-32 digits, spaces or + ( ) -")
                    String phone,
            @Size(max = 160) String company,
            @Size(max = 500) String website,
            @Size(max = 16) String tier,
            @Size(max = 120) String productCategory,
            @Size(max = 80) String instagramHandle,
            @PositiveOrZero(message = "Followers cannot be negative") Long followers,
            @Size(max = 80) String city,
            @Size(max = 2000) String message,
            @Size(max = 120) String utmSource,
            @Size(max = 120) String utmMedium,
            @Size(max = 120) String utmCampaign,
            @Size(max = 200) String honeypot) {}

    /**
     * What the public form gets back. Deliberately carries NO id and no echo of the submitted
     * fields: the endpoint is unauthenticated, so anything returned here is readable by anyone who
     * can POST, and a returned row id would be a handle onto a record the submitter has no right to
     * read back.
     *
     * @param received always true on a 200 — including for a honeypot-rejected submission, which is
     *     indistinguishable from success by design.
     */
    public record SubmitEnquiryResponse(boolean received, String message) {}
}
