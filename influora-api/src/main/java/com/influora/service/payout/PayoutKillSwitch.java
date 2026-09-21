package com.influora.service.payout;

import com.influora.common.ApiException;
import com.influora.config.PayoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The single server-side decision point for "may anything push money out to a creator right now?"
 * (EV-014 / payoutswitch). Backed by {@link PayoutProperties} — see that class for WHY the switch
 * exists and why it fails closed.
 *
 * <p><b>One decision, one place.</b> Every entry point that can START an outbound payout asks this
 * bean, so the answer cannot drift between rails:
 *
 * <ul>
 *   <li>{@code WalletService#requestCreatorWithdrawal} — {@code POST /wallet/withdraw}, creator
 *       self-serve. {@link #requireEnabled}.
 *   <li>{@code PayoutService#queuePayout} — {@code POST /escrow/payout}, brand-triggered milestone
 *       payout to the creator's bank via RazorpayX. {@link #requireEnabled}.
 *   <li>{@code PayoutReconciliationService#retryFailedPayout} — {@code POST
 *       /admin/finance/payouts/{id}/retry}, admin-triggered gateway retry. {@link #requireEnabled}.
 *   <li>{@code PayoutReconciliationService#reconcileOrphanedPendingPayout} and {@code
 *       #reconcileFailedPayoutRetry} — the {@code PayoutOrphanedDebitSweepJob} scheduled paths that
 *       would RESUME a half-finished gateway attempt. {@link #isEnabled} + skip, never
 *       {@link #requireEnabled}: see the note below.
 * </ul>
 *
 * <p><b>Why the scheduled paths skip instead of throwing.</b> Both sweep methods funnel into
 * {@code PayoutReconciliationService#attemptGatewayPayout}, whose {@code catch (Exception)} treats
 * ANY throwable from the gateway call as "the payout failed" and flips the row to {@code REVERSED}
 * while re-crediting the creator. Throwing the refusal from inside that try block would therefore
 * have the kill switch silently rewriting payout state on every sweep tick. The gate is placed at
 * the top of each sweep method instead, where it is a pure no-op: nothing is read, written or
 * reversed, and a WARN records that a resumable attempt was left frozen for a human to settle over
 * the manual rail.
 *
 * <p><b>Deliberately NOT gated.</b> {@code AdminFinanceService#recordManualPayout} (the manual
 * NEFT/IMPS rail — this switch exists precisely so that path is the ONLY one) and {@code
 * PayoutReconciliationService#confirmExecuted} (an inbound RazorpayX webhook RECORDING the outcome
 * of a payout that was already sent — blocking it would strand real money in an unreconciled state
 * rather than prevent anything).
 *
 * <p><b>AI helper parity.</b> There is one flow. An assistant that offers to "withdraw your
 * balance" calls the same {@code POST /wallet/withdraw} a human clicking the button calls, so it
 * receives the same {@link #CODE} refusal here — the AI has no private path around this gate, and
 * with the AI switched off the product behaves identically.
 */
@Component
public class PayoutKillSwitch {

    private static final Logger log = LoggerFactory.getLogger(PayoutKillSwitch.class);

    /** Stable error code clients (and the AI assistant) branch on. */
    public static final String CODE = "PAYOUTS_DISABLED";

    /**
     * Creator-facing refusal. Says what is actually true today: the money is theirs and is not
     * stuck, a human on the team sends it by bank transfer. No regulator/custody claim (F-0851) and
     * no "escrow" wording in creator-facing copy.
     */
    public static final String MESSAGE =
            "Self-serve withdrawals are switched off right now. Your balance is safe — our team"
                    + " transfers creator earnings to your registered bank account directly."
                    + " Contact support to request a transfer.";

    private final PayoutProperties properties;

    public PayoutKillSwitch(PayoutProperties properties) {
        this.properties = properties;
    }

    /** {@code true} only when outbound payouts have been deliberately switched on. */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Refuses with {@code 403 PAYOUTS_DISABLED} unless payouts are switched on. Call this as the
     * FIRST statement of any request-scoped payout entry point, before any validation, lookup,
     * lock, idempotency reservation or ledger write — a refusal must leave zero rows behind.
     *
     * @param entryPoint short identifier of the calling rail, for the audit log only
     */
    public void requireEnabled(String entryPoint) {
        if (properties.isEnabled()) {
            return;
        }
        log.warn("Payout kill switch: refused '{}' — influora.payouts.enabled=false", entryPoint);
        throw new ApiException(CODE, MESSAGE, HttpStatus.FORBIDDEN);
    }
}
