package com.influora.web.dto.deal;

import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    public record CreateDealRequest(
            @NotBlank String campaignId,
            @NotBlank String creatorId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            List<DeliverableSlot> deliverables,
            String deadline,
            String usageRights,
            Boolean exclusivity,
            @Size(max = 2000) String message) {}

    public record DeliverableSlot(@NotBlank String type, @NotNull Integer qty) {}

    public record CounterRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 2000) String message,
            List<DeliverableSlot> deliverables) {}

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
