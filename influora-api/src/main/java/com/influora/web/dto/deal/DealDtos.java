package com.influora.web.dto.deal;

import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
            boolean escrowFunded) {}

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
            @Size(max = 2000) String message) {}

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
            // Nullable — a counter that only revises price/deadline need not re-specify deliverables.
            // DealService.doCounter() carries them forward from the superseded proposal card when
            // omitted, so the contract generator always sees a full set of terms.
            @Valid List<DeliverableSlot> deliverables,
            String deadline,
            String usageRights) {}

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
