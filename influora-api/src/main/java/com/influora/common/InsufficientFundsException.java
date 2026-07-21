package com.influora.common;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/**
 * [SEC: MF-1 follow-up, 2026-07-21] Thrown by {@code EscrowService.initiateFund} in place of a
 * bare {@link ApiException} when the brand's wallet balance is below the server-derived required
 * escrow amount. Carries the exact server-computed {@code requiredAmount}, {@code walletBalance},
 * and {@code shortfallAmount} so the 402 response body can hand the frontend an authoritative
 * top-up figure — closing the gap where the inline top-up-then-fund UX (Option 1) was previously
 * forced to CLIENT-ESTIMATE the shortfall from its own {@code GET /wallet} read before charging
 * the brand's card via {@code POST /wallet/topup}. All three numbers here are computed from the
 * exact same server-side balance read that gates the charge (never a stale/cached figure), so the
 * frontend can send this value straight to {@code /wallet/topup} instead of estimating.
 *
 * <p>Keeps the existing {@code INSUFFICIENT_FUNDS} code and {@link HttpStatus#PAYMENT_REQUIRED}
 * status — additive only, no behavior change for callers that only read {@code code}/{@code
 * message} (e.g. any pre-existing test asserting on the generic {@link ApiException} shape still
 * passes, since this is a subclass).
 */
public class InsufficientFundsException extends ApiException {

    private final BigDecimal requiredAmount;
    private final BigDecimal walletBalance;
    private final BigDecimal shortfallAmount;
    private final String currency;

    public InsufficientFundsException(
            String message,
            BigDecimal requiredAmount,
            BigDecimal walletBalance,
            BigDecimal shortfallAmount,
            String currency) {
        super("INSUFFICIENT_FUNDS", message, HttpStatus.PAYMENT_REQUIRED);
        this.requiredAmount = requiredAmount;
        this.walletBalance = walletBalance;
        this.shortfallAmount = shortfallAmount;
        this.currency = currency;
    }

    public BigDecimal getRequiredAmount() {
        return requiredAmount;
    }

    public BigDecimal getWalletBalance() {
        return walletBalance;
    }

    public BigDecimal getShortfallAmount() {
        return shortfallAmount;
    }

    public String getCurrency() {
        return currency;
    }
}
