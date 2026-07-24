package com.influora.web.dto.shipment;

import com.influora.domain.enums.ShipmentCondition;
import com.influora.domain.enums.ShipmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * DTOs for the shipment endpoints on {@code DealController}
 * (wiki/decisions/shipment-backend-design-2026-07-24.md §4/§7). Field names match the frontend
 * contract Priya specified for {@code src/lib/api.ts}'s {@code api.shipments.*}.
 *
 * <p>All server-side validation here is a defense-in-depth boundary, not a UX convenience — the
 * FE's zod schema is UX only (design doc risk #4); pincode/phone format is re-checked here
 * regardless of what the client already validated.
 */
public final class ShipmentDtos {

    private ShipmentDtos() {}

    public record ShippingAddressRequest(
            @NotBlank @Size(max = 200) String recipientName,
            @NotBlank @Size(max = 300) String addressLine1,
            @Size(max = 300) String addressLine2,
            @NotBlank @Size(max = 120) String city,
            @NotBlank @Size(max = 120) String state,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{5}$", message = "pincode must be a valid 6-digit Indian PIN code")
                    String pincode,
            @NotBlank @Pattern(regexp = "^[+0-9][0-9]{6,19}$", message = "phone must contain only digits and an optional leading +")
                    String phone) {}

    public record MarkShippedRequest(
            @NotBlank @Size(max = 120) String carrier,
            @NotBlank @Size(max = 120) String trackingNumber,
            @Size(max = 500) String trackingUrl,
            @NotBlank @Size(max = 300) String productName) {}

    public record ConfirmReceiptRequest(
            @NotNull ShipmentCondition condition, @Size(max = 2000) String note) {}

    public record ShipmentResponse(
            String dealId,
            ShipmentStatus status,
            String recipientName,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String pincode,
            String phone,
            String productName,
            String carrier,
            String trackingNumber,
            String trackingUrl,
            String conditionNote,
            ShipmentCondition receivedCondition,
            Instant updatedAt) {

        /** Synthetic response for a collaboration with no {@code Shipment} row yet. */
        public static ShipmentResponse awaitingAddress(String dealId) {
            return new ShipmentResponse(
                    dealId,
                    ShipmentStatus.AWAITING_ADDRESS,
                    null, null, null, null, null, null, null,
                    null, null, null, null,
                    null, null, null);
        }
    }
}
