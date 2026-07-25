# Budget / Decision Memo — Razorpay Escrow Account vs QA Timeline

- **Author:** Rohan (CFO)
- **Date:** 2026-07-25
- **For:** Swapnil (approval), Priya/Vikram (execution)
- **Trigger:** Live E2E QA blocked — escrow money-path 500s because the live Razorpay key is a placeholder. Team status: we have a **regular Razorpay account**, but **not** the **escrow / RazorpayX Route** account permission yet (in progress).

## TL;DR
Do **not** block QA or go-live on the escrow-account approval. Three moves, in order:
1. Wire **Razorpay TEST-MODE keys today** (free, instant) → unblocks the payment/order flow in staging.
2. Fix the escrow bug + add a feature flag so non-prod funds escrow from the **internal wallet balance** when sufficient (no gateway call). ~1–2 dev hrs.
3. Keep **Razorpay Route (production escrow) approval** on its own track (~1–3 weeks). It gates *production money movement*, not QA.

## The distinction that matters
- **Regular Razorpay (what we have):** collect payments via Orders/Payments API. **Test mode is available immediately** with `rzp_test_*` keys — no approval needed. This alone replaces the placeholder key and lets QA exercise the payment flow.
- **Razorpay Route (what's pending) = the "escrow" mechanism** for a marketplace: collect brand funds, hold them, then split/transfer to creators on release. Razorpay (a licensed Payment Aggregator) holds the funds in its own nodal/escrow account — so **we do not build or hold our own escrow**; we use Route. Needs business KYC + marketplace use-case review + linked-account (creator) onboarding.

## Compliance note (India)
Holding third-party (brand→creator) funds ourselves would pull us under **RBI Payment Aggregator/nodal-account rules** — you cannot park customer money in a normal current account. Using **Route keeps the funds with Razorpay's licensed PA infrastructure**, which is the *compliant and cheaper* path than a bespoke nodal account. Recommendation: pursue Route, not a self-run escrow account.

## Cost impact (estimates — confirm with Razorpay before sign-off)
| Item | Cost | Nature |
|---|---|---|
| Razorpay test keys | ₹0 | free, instant |
| Route activation | ₹0 fixed | approval-gated, no subscription |
| Payment MDR (live) | ~2% per txn | variable, scales with GMV |
| Route transfer/payout fee | small per-transfer | variable |
| Eng fix (escrow flag) | ~1–2 dev hrs | one-time, ~₹0 marginal (in-house) |

**Verdict:** no meaningful *fixed* cost — this is transaction-fee based, negligible at QA volume, scales with revenue at go-live. Not a budget risk; it's a **timeline/compliance** item.

## The engineering ask (routed to Priya/Vikram — CFO does not touch product code)
`EscrowService.initiateEscrowFund` **always** calls `razorpayClient.createOrder()`, even when the brand wallet already holds the funds. Two changes:
1. **Bug:** guard the gateway call — when wallet balance ≥ escrow amount and a flag is set, move funds internally (wallet → clearing) instead of creating a Razorpay order.
2. **Config:** put real Razorpay test keys in the live/staging `.env` + `PublicConfigController` keyId so the placeholder is gone.

## Recommendation
- **Approve** wiring test-mode keys + the escrow feature flag now (unblocks QA this week, ~₹0).
- **Continue** the Route escrow-account application in parallel; treat its ETA as the **production go-live** gate for real money, not the QA gate.
- Re-cost at go-live once Razorpay confirms MDR + Route fees for our expected GMV.
