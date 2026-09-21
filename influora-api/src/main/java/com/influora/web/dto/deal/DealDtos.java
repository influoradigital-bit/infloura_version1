package com.influora.web.dto.deal;

import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.ExclusivityScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** DTOs for {@code DealController} — field names match {@code src/lib/api.ts} {@code Deal}/{@code DealMessage}. */
public final class DealDtos {

    private DealDtos() {}

    public record DealResponse(
            String id,
            String campaignId,
            String campaignName,
            String counterpartyId,
            /**
             * The counterparty's CreatorProfile id — distinct from {@code counterpartyId}, which
             * is the creator's User id ({@code Collaboration.creatorId} / {@code
             * DealService.resolveCounterparty}). Frontend profile pages (e.g. {@code
             * brand-creator-profile.tsx}) navigate/match on CreatorProfile id, not User id, so
             * without this field the brand-side "does a conversation already exist" lookup in
             * {@code brand-messages.tsx} could never match a real deal. Null when the counterparty
             * is a brand (viewer is CREATOR) — a brand counterparty has no CreatorProfile row.
             */
            String counterpartyProfileId,
            String counterpartyName,
            String counterpartyAvatar,
            String counterpartyHandle,
            /**
             * PR-2 (BrandF.md §83c / §105 VER-1) — {@code Workspace.verificationStatus} enum name
             * when the counterparty is the brand (viewer is CREATOR), the same signal added to
             * {@code CreatorCampaignDtos.BrandSummary}. Null when the counterparty is a creator
             * (viewer is BRAND) — {@code CreatorProfile} verification is a separate, out-of-scope
             * signal ({@code identityKycStatus}), not this field.
             */
            String counterpartyVerificationStatus,
            CollaborationStatus status,
            BigDecimal dealValue,
            String currency,
            String lastMessage,
            Instant lastMessageAt,
            int unreadCount,
            int deliverablesDone,
            int deliverablesTotal,
            Instant nextDeadline,
            String contractId,
            ContractStatus contractStatus,
            boolean escrowFunded,
            /** T-MEERA-CREATOR-PHASE-A (SPEC.md 1.1, A2) — null when no structured terms were ever set. */
            @JsonInclude(JsonInclude.Include.NON_NULL) DealTermsDto dealTerms) {}

    /**
     * T-MEERA-CREATOR-PHASE-A (SPEC.md 1.1/4.2, A2) — structured usage/exclusivity/revision terms,
     * separate from the free-text {@code usageRights} field above (which predates this and stays
     * for the raw-string case). {@code usageChannels} are {@link
     * com.influora.domain.enums.UsageChannel} names; {@code exclusivityBrands} is populated only
     * when {@code exclusivityScope == NAMED_BRANDS}.
     */
    public record DealTermsDto(
            Integer usageMonths,
            boolean usagePerpetual,
            List<String> usageChannels,
            Integer exclusivityDays,
            ExclusivityScope exclusivityScope,
            List<String> exclusivityBrands,
            @Min(0) Integer maxRevisions) {}

    /**
     * Brand-initiated priced offer. {@code creatorId} is a CreatorProfile id (a userId is also
     * accepted — see {@code DealService.requireOfferableProfile}), NOT a User id despite the name.
     *
     * <p>{@code exclusivity} was removed 2026-07-26 (CEO call). It had exactly one occurrence in
     * the whole backend — this field — and was never persisted, read, or surfaced in the contract:
     * {@code Collaboration.propose} never received it and {@code persistProposalMessage} wasn't
     * passed it either. Accepting it made the API advertise a commercial term we do not enforce,
     * so a brand could believe it bought exclusivity while the creator was free to post for a
     * competitor. It comes back as a real contract clause — with a date range and breach handling
     * — after escrow/Route, not as a checkbox.
     *
     * <p>Note {@code deliverables} and {@code deadline} survive only inside the proposal MESSAGE
     * metadata; {@code usageRights} is the one term persisted onto the Collaboration itself.
     */
    public record CreateDealRequest(
            @NotBlank String campaignId,
            @NotBlank String creatorId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotEmpty(message = "At least one deliverable is required") @Valid List<DeliverableSlot> deliverables,
            String deadline,
            String usageRights,
            @Size(max = 2000) String message,
            /** T-MEERA-CREATOR-PHASE-A (SPEC.md 4.2, A2) — optional; null means "no structured terms set". */
            @Valid DealTermsDto dealTerms) {}

    /**
     * One line of an order: "2x Instagram Reel".
     *
     * <p><b>{@code type} is a {@link com.influora.domain.enums.DeliverableType} NAME, not a
     * label.</b> The accepted values are exactly that enum's constants — {@code INSTAGRAM_POST},
     * {@code INSTAGRAM_REEL}, {@code INSTAGRAM_STORY}, {@code INSTAGRAM_CAROUSEL}, {@code
     * YOUTUBE_VIDEO}, {@code YOUTUBE_SHORT}, {@code FACEBOOK_POST}, {@code FACEBOOK_REEL}, {@code
     * TIKTOK_VIDEO} — and the SPA sends them from one shared list ({@code
     * src/lib/deliverable-slots.ts}). Bean validation cannot express "member of that enum" without
     * a second copy of the list that would drift from it, so the membership check lives in {@code
     * DealService#requireOrderedDeliverables}, which every route carrying this record runs through
     * before writing anything. It answers with a 400 that names the offending value and lists the
     * accepted ones, rather than the generic "Request validation failed" a field constraint would
     * produce.
     *
     * <p>Until 2026-09-21 an unrecognised type was not an error at all: {@code ContractService}
     * caught the parse failure and substituted {@code INSTAGRAM_REEL}, so a brand's YouTube video
     * order reached the creator as a reel. Do not reintroduce a default here or anywhere
     * downstream.
     */
    public record DeliverableSlot(@NotBlank String type, @NotNull @Positive Integer qty) {}

    /**
     * A counter-offer from either party. Aligned with {@link CreateDealRequest} on 2026-07-26:
     * {@code deadline} and {@code usageRights} were added so the two negotiation entry points
     * model the same terms.
     *
     * <p>Before that, a counter could only carry amount/message/deliverables, so every frontend
     * that let a user revise a deadline or usage rights concatenated them into {@code message} as
     * free text — see the identical workarounds that used to sit in {@code creator-chat.tsx} and
     * {@code brand-chat.tsx}. Terms a party actually negotiated were therefore unreadable to the
     * server and unusable by the contract generator. {@code usageRights} is now persisted onto
     * the Collaboration exactly as {@code createProposal} persists it; {@code deadline} rides in
     * the proposal message metadata (there is still no deadline column on Collaboration).
     */
    public record CounterRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 2000) String message,
            // Nullable — a counter that only revises price/deadline need not re-specify
            // deliverables, and DealService.doCounter() carries them forward from the superseded
            // proposal card when omitted. That is a convenience, not a guarantee: a counter on a
            // deal with NO earlier proposal card (a creator's first counter on their own
            // application; a brand countering from the campaign page's Bids tab) has nothing to
            // inherit, and doCounter refuses it rather than writing an offer that orders nothing.
            // Send the list whenever the form has one.
            @Valid List<DeliverableSlot> deliverables,
            String deadline,
            String usageRights,
            /** T-MEERA-CREATOR-PHASE-A (SPEC.md 4.2, A2) — same shape/semantics as {@link CreateDealRequest#dealTerms}. */
            @Valid DealTermsDto dealTerms,
            /**
             * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.6 / &sect;3.4) — the Meera draft this counter was
             * approved from, when there was one. Nullable: most counters are typed by hand.
             *
             * <p><b>Its only job in Phase B0 is authorship.</b> It is an INPUT to that decision and not
             * the decision itself: {@code DealService.meeraDraftedAuthorship} resolves it against
             * {@code meera_drafts} and only then writes {@code MEERA_COUNTER} with
             * {@code meera_drafted = true}. That stamp is what SPEC.md &sect;14.1.d's
             * {@code meeraAnchoredShare} — the number the B0-to-B1 decision turns on — is computed
             * from, and it is not recoverable later without being captured at write time, so the field
             * has to exist before the rest of the drafts flow does.
             *
             * <p><b>A non-blank string is not evidence, and this field is not trusted.</b> An earlier
             * revision of this javadoc said the id was deliberately unvalidated and that the claim
             * recorded was "the creator says Meera drafted this". It was not: {@code counter} is a
             * MUTUAL route, so a BRAND client sets this field too, and any brand could inflate the
             * Meera-anchored share — the honesty label creators read on a price — by sending an
             * arbitrary string. The id is now resolved server-side to a draft owned by the acting
             * creator on this collaboration, and an id that does not resolve is recorded as a
             * hand-typed counter rather than rejected, so a stale id cannot block a negotiation.
             */
            String meeraDraftId) {}

    public record RejectRequest(@Size(max = 500) String reason) {}

    public record DealMessageResponse(
            String id,
            String dealId,
            DealMessageKind kind,
            String senderId,
            DealSenderType senderType,
            String content,
            Map<String, Object> metadata,
            Instant createdAt,
            List<String> readBy) {}

    public record SendMessageRequest(
            @NotBlank @Size(max = 5000) String content,
            DealMessageKind kind) {}

    public record OkResponse(boolean ok) {
        public static OkResponse success() {
            return new OkResponse(true);
        }
    }
}
