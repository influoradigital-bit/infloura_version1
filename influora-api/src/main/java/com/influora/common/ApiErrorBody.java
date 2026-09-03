package com.influora.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * [SEC: MF-1 follow-up, 2026-07-21] {@code requiredAmount}/{@code walletBalance}/{@code
 * shortfallAmount}/{@code currency} are additive, nullable-by-default fields used only by the
 * {@code INSUFFICIENT_FUNDS} 402 on {@code POST /wallet/escrow/fund}
 * ({@link com.influora.common.InsufficientFundsException}) — every other error path continues to
 * call {@link #of} and leaves them null. Class-level {@link JsonInclude.Include#NON_NULL} drops
 * null fields from the serialized JSON entirely (not just `null` literals), so this is a
 * behavior-neutral change for every pre-existing error response shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorBody(
        String code,
        String message,
        String field,
        List<FieldError> fields,
        BigDecimal requiredAmount,
        BigDecimal walletBalance,
        BigDecimal shortfallAmount,
        String currency,
        // T-CREATORCONNECT-0902 — additive, nullable-by-default (same pattern as the
        // insufficient-funds quartet above): only CreatorAlreadyOnInfluoraException's 409 ever
        // sets this, every other error path leaves it null and NON_NULL drops it from the wire.
        String linkedCreatorProfileId) {

    public record FieldError(String field, String message) {}

    public static ApiErrorBody of(String code, String message) {
        return new ApiErrorBody(code, message, null, null, null, null, null, null, null);
    }

    public static ApiErrorBody validation(String message, List<FieldError> fields) {
        return new ApiErrorBody("VALIDATION_ERROR", message, null, fields, null, null, null, null, null);
    }

    /**
     * [SEC: MF-1 follow-up] Server-derived shortfall for {@code INSUFFICIENT_FUNDS} on escrow
     * fund. {@code requiredAmount}/{@code walletBalance}/{@code shortfallAmount} are the exact
     * figures from {@link InsufficientFundsException} — never re-derived or estimated here.
     */
    public static ApiErrorBody insufficientFunds(
            String code,
            String message,
            BigDecimal requiredAmount,
            BigDecimal walletBalance,
            BigDecimal shortfallAmount,
            String currency) {
        return new ApiErrorBody(
                code, message, null, null, requiredAmount, walletBalance, shortfallAmount, currency, null);
    }

    /**
     * T-CREATORCONNECT-0902 — {@code POST /creators/external/{id}/connect} 409 when the target is
     * already a real Influora creator. {@code linkedCreatorProfileId} lets the frontend offer the
     * create-campaign link straight from the error instead of re-fetching.
     */
    public static ApiErrorBody creatorAlreadyOnInfluora(String message, String linkedCreatorProfileId) {
        return new ApiErrorBody(
                "CREATOR_ALREADY_ON_INFLUORA", message, null, null, null, null, null, null, linkedCreatorProfileId);
    }
}
