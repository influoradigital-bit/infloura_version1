package com.influora.web.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTOs for {@code POST /admin/emails/custom/preview} and {@code POST /admin/emails/custom/send}
 * (T-ADMINMAIL-0903). Field names/shapes are the FIXED, settled contract in
 * {@code .proof-os/tasks/T-ADMINMAIL-0903/SPEC.md} — Ananya's admin UI is built against this exact
 * shape in parallel; do not rename a field or change its nullability without raising a concern
 * first, same discipline as {@code AdminCreatorConnectionDtos}.
 */
public final class AdminCustomEmailDtos {

    private AdminCustomEmailDtos() {}

    /**
     * {@code userType} is one of {@code "CREATOR"}, {@code "BRAND"}, {@code "ALL"} (validated —
     * not an enum type here — by {@code AdminCustomEmailService.resolveUserType} so an invalid
     * value comes back as a clean 400 {@code INVALID_AUDIENCE} rather than a raw Jackson
     * deserialization 400). {@code registeredWithinDays} is nullable — {@code null} means no floor
     * on registration age.
     */
    public record AudienceDto(
            @NotBlank String userType, boolean onlyVerified, Integer registeredWithinDays) {}

    /**
     * {@code sampleUserId} is kept in the wire shape for backward compatibility but is
     * deliberately IGNORED by {@code AdminCustomEmailService#preview} as of round 3 (B2,
     * REVIEW-R2.md ship-blocker) — audience is itself caller-supplied ({@code
     * {userType:"ALL",onlyVerified:false,registeredWithinDays:null}} matches every ACTIVE user),
     * so trusting an arbitrary caller-chosen id there made {@code /custom/preview} an unaudited,
     * platform-wide email-address oracle. The sample recipient is now always the first row the
     * server-side audience query itself returns — see {@code resolveSample}'s javadoc.
     */
    public record PreviewRequest(
            @NotBlank @Size(max = 255) String subject,
            @NotBlank @Size(max = 20_000) String bodyText,
            @Size(max = 100) String ctaLabel,
            @Size(max = 2048) String ctaUrl,
            @NotNull @Valid AudienceDto audience,
            String sampleUserId) {}

    public record PreviewResponse(
            String subject,
            String html,
            long recipientCount,
            boolean capped,
            int cap,
            String sampleRecipientEmail) {}

    /**
     * {@code confirmRecipientCount} is control #3 and load-bearing (SPEC.md) — {@code send}
     * recomputes the audience fresh and 409s ({@code RECIPIENT_COUNT_CHANGED}) if it doesn't match
     * this value exactly. {@code @NotNull} rather than a primitive {@code long} so a client that
     * omits it entirely fails clean validation (400) instead of silently defaulting to {@code 0}
     * and tripping the mismatch check for the wrong reason.
     */
    public record SendRequest(
            @NotBlank @Size(max = 255) String subject,
            @NotBlank @Size(max = 20_000) String bodyText,
            @Size(max = 100) String ctaLabel,
            @Size(max = 2048) String ctaUrl,
            @NotNull @Valid AudienceDto audience,
            @NotNull Long confirmRecipientCount) {}

    /**
     * {@code replay} (T-ADMINMAIL-0903 round 3, REVIEW-R2.md doc defect #2): a deliberate resend
     * of byte-identical copy to an unchanged audience hashes to the same {@code campaignId} and
     * returns the ORIGINAL {@code queued}/{@code skippedUnsubscribed} counts while mailing nobody
     * new — before this field existed, the admin had no way to tell that apart from a genuine
     * fresh send that happened to enqueue the exact same numbers. {@code true} means {@code
     * AdminCustomEmailService#send} short-circuited to an already-committed campaign
     * ({@code replaySendResponse}); {@code false} means this call itself just enqueued the rows.
     */
    public record SendResponse(String campaignId, int queued, int skippedUnsubscribed, boolean replay) {}

    /**
     * T-ADMINMAIL-0903 round 3, B3 (REVIEW-R2.md ship-blocker): response for {@code POST
     * /admin/emails/custom/{campaignId}/cancel}. {@code cancelled} is the number of PENDING {@code
     * admin.custom} outbox rows for this campaign that were marked terminal — 0 is a valid,
     * non-error result (nothing left to cancel: the campaign already fully drained, was already
     * cancelled, or never queued any rows because every recipient was unsubscribed).
     */
    public record CancelResponse(String campaignId, int cancelled) {}
}
