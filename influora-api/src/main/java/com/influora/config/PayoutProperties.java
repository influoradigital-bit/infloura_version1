package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side kill switch for OUTBOUND creator payouts (EV-014 / payoutswitch).
 *
 * <p><b>Why this exists.</b> "Creator withdrawals are off" used to be an accident rather than a
 * decision. {@code VITE_PAYOUTS_ENABLED} ({@code src/lib/api.ts:109}) is a FRONTEND build flag: it
 * only decides whether the browser renders the Withdraw dialog. {@code POST /wallet/withdraw} was
 * still reachable with any authenticated creator token — anything that spoke HTTP (curl, a stale
 * SPA bundle, a mobile client, an AI assistant calling the same endpoint a human button calls)
 * walked straight past it and got as far as {@code WalletService#requestCreatorWithdrawal}, where
 * the only thing that stopped it was an incidental {@code IDEMPOTENCY_KEY_REQUIRED} 400. That is a
 * crash, not a policy.
 *
 * <p><b>The launch model this encodes.</b> Creators are paid MANUALLY by the team over NEFT/IMPS
 * and the transfer is then recorded through {@code POST /admin/finance/payouts/manual}
 * ({@code AdminFinanceService#recordManualPayout}). That admin rail is deliberately NOT gated by
 * this switch — it is the beta payout rail and must keep working. What this switch closes is every
 * path that would push money out through the RazorpayX gateway on its own.
 *
 * <p><b>Safe default.</b> {@code enabled} defaults to {@code false} in BOTH layers — this field
 * initialiser and the {@code ${PAYOUTS_ENABLED:false}} placeholder in {@code application.yml}. A
 * missing env var, a missing yaml key, an unregistered property source: every one of those fails
 * CLOSED. Turning payouts on has to be an explicit, deliberate act (set {@code PAYOUTS_ENABLED=true}
 * once RazorpayX is actually provisioned and a human has signed off).
 *
 * <p>Deliberately NOT keyed off "is RazorpayX configured": {@code RazorpayXClient} falls back to a
 * mock stub when unconfigured (see {@code RazorpayXClient#initiatePayout}), so credential presence
 * is not a truthful proxy for "we intend to pay people automatically".
 */
@ConfigurationProperties(prefix = "influora.payouts")
public class PayoutProperties {

    /**
     * Whether self-serve / automated outbound payouts are switched on. {@code false} = only the
     * team-managed manual bank-transfer rail may move money to a creator.
     */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
