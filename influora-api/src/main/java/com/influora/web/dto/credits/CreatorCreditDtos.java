package com.influora.web.dto.credits;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** T-CREATOR-CREDITS-V2 (SPEC.md §8) — the creator-credits public wire DTOs. */
public final class CreatorCreditDtos {

    private CreatorCreditDtos() {}

    public record CreateOrderRequest(@NotBlank @Size(max = 32) String packCode) {}

    public record CreateOrderResponse(
            String orderId, String razorpayOrderId, int amountPaise, String currency, int credits, String keyId) {}

    public record VerifyOrderRequest(
            @NotBlank @Size(max = 64) String razorpayPaymentId,
            @NotBlank @Size(max = 256) String razorpaySignature) {}

    public record VerifyOrderResponse(String status, int balance) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderHistoryItem(
            String orderId,
            int credits,
            int amountPaise,
            String status,
            Instant paidAt,
            Instant expiresAt,
            String invoiceNumber,
            Integer taxablePaise,
            Integer cgstPaise,
            Integer sgstPaise,
            Integer igstPaise) {}

    public record WelcomeInfo(boolean eligible, boolean granted, Instant grantedAt) {}

    public record MonthlyInfo(String period, boolean granted) {}

    public record PendingInfo(int welcome, int monthly) {}

    public record PaidExpiringItem(int credits, Instant expiresAt) {}

    public record PackInfo(String code, int credits, int pricePaise, boolean gstInclusive) {}

    public record CostsInfo(int turn, int voiceTurn, int brief) {}

    /** {@code GET /creator/credits}. Flag off: only {@code enabled=false} is populated (NON_NULL). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BalanceResponse(
            boolean enabled,
            Integer total,
            Integer free,
            Integer paid,
            Integer dailyUsed,
            Integer dailyCap,
            Instant dailyResetsAt,
            Instant nextMonthlyGrantAt,
            WelcomeInfo welcome,
            MonthlyInfo monthly,
            PendingInfo pending,
            List<PaidExpiringItem> paidExpiring,
            PackInfo pack,
            CostsInfo costs) {

        public static BalanceResponse disabled() {
            return new BalanceResponse(
                    false, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
