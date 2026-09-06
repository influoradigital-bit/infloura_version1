package com.influora.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Wire types for the PUBLIC coupon-copy tracking endpoint (T-FESTIVALBOX-0905 phase 6). Fired by
 * the Festival Box page's tap-to-copy handler, fire-and-forget — see {@code
 * FestivalCouponCopyController} for why the response carries nothing.
 */
public final class FestivalCouponCopyDtos {

    private FestivalCouponCopyDtos() {}

    /**
     * One copy event. Every field is bean-validated against a strict charset in addition to
     * length, because — unlike {@code FestivalEnquiryDtos.SubmitEnquiryRequest} — nothing here goes
     * through {@code TextSanitizer}; these three values are never rendered as HTML anywhere (the
     * admin metrics read returns them as plain JSON strings), but they DO get concatenated into a
     * native SQL statement's bound parameters (not string-interpolated — see {@code
     * FestivalCouponCopyRepository#recordCopy}), so a strict allow-list charset is the defense here
     * rather than sanitization-after-the-fact.
     *
     * @param edition matches {@code FestivalEnquiry.edition}'s existing convention
     *     (upper-snake-case, e.g. {@code MUMBAI_FESTIVE_2026}). The charset/length rule below is
     *     the wire-shape check only; [Kabir H-1] the authoritative check is
     *     {@code com.influora.domain.FestivalEditions} in {@code FestivalCouponCopyService}, since
     *     edition is part of this table's unique key and an unchecked one made row growth
     *     unbounded. A well-formed but unknown edition is accepted at the wire level and silently
     *     produces no row, exactly like an unrecognized {@code sponsorSlug}.
     * @param sponsorSlug the Festival Box page's per-sponsor id ({@code
     *     src/content/festival-editions.ts}'s {@code FestivalSponsor.slug} — lower-kebab-case).
     *     Checked against real provisioned brand workspaces in the service; an unrecognized value
     *     is accepted at the wire level (well-formed) but silently produces no row.
     * @param couponCode the tapped code as shown on the page — usually upper-case alphanumeric.
     */
    public record RecordCouponCopyRequest(
            @NotBlank(message = "edition is required")
                    @Size(max = 64)
                    @Pattern(regexp = "^[A-Z0-9_]{1,64}$", message = "edition has an invalid format")
                    String edition,
            @NotBlank(message = "sponsorSlug is required")
                    @Size(max = 80)
                    @Pattern(
                            regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                            message = "sponsorSlug has an invalid format")
                    String sponsorSlug,
            @NotBlank(message = "couponCode is required")
                    @Size(max = 50)
                    @Pattern(regexp = "^[A-Z0-9_-]{1,50}$", message = "couponCode has an invalid format")
                    String couponCode) {}
}
