# Influora — Per-Campaign Cost Structure (Razorpay + Escrow)

> **Author:** Rohan (CFO)
> **Date:** 2026-07-09
> **Why this exists:** the pricing proposal (`wiki/decisions/budget-proposals/2026-07-09-influora-pricing-proposal.md`) locked our 10% brand fee + 15% creator fee as gross platform revenue. This file shows what Razorpay actually costs us on every rupee that moves — the **true net margin**, not the gross fee.

---

## 1. Razorpay Fee Rates (current, verified)

| Fee Type | Rate | When It Hits |
|---|---|---|
| Payment gateway (UPI, cards, netbanking, wallets — domestic) | 2% + 18% GST on the fee (~2.36% effective) | When brand funds escrow |
| International cards | 3% + GST (~3.54% effective) | Rare — most brands are domestic |
| RazorpayX payout (bank transfer/UPI, Lite plan) | Flat ₹5 + GST (~₹5.90/payout) | When creator withdraws from wallet |
| Razorpay Route (marketplace split payments) | Not publicly listed — needs a direct quote from Razorpay before we build on it | If we auto-split at funding time instead of manual payout |

**Note on Route:** our escrow model (fund now, release to creator wallet on milestone approval, creator withdraws later) is a **held-funds** pattern, not an instant-split pattern — so we likely don't need Route at all for launch. Standard payment gateway (funding) + RazorpayX payouts (withdrawal) covers it. I'd only pull in Route if we later want to auto-split and pay creators the instant a milestone clears, skipping our wallet entirely — not recommended at launch, we want funds to sit in our escrow long enough to handle disputes.

---

## 2. Worked Example — ₹20,000 Creator Milestone

| Step | Amount | Who Pays / Receives |
|---|---|---|
| Creator's agreed rate | ₹20,000 | — |
| + Brand platform fee (10% tier) | ₹2,000 | Brand pays this on top |
| **= Brand charged at escrow funding** | **₹22,000** | Brand → Razorpay → Influora escrow |
| − Razorpay gateway fee (2.36% of ₹22,000) | ₹519 | Influora absorbs this, NOT the brand |
| Escrow holds (brand's original ₹20,000 budget) | ₹20,000 | Held until milestone approved |
| − Creator platform fee (15% of ₹20,000) | ₹3,000 | Deducted at release |
| Creator wallet credited | ₹17,000 | Creator (net, before withdrawal) |
| − RazorpayX payout fee (flat) | ₹5.90 | Influora absorbs this, NOT the creator* |
| **Creator receives in bank/UPI** | **₹17,000** (fee absorbed by us) OR **₹16,994.10** (if passed to creator) | **Decision needed — see §4** |

### Net margin on this transaction

| Line | Amount |
|---|---|
| Gross platform fee revenue (₹2,000 + ₹3,000) | ₹5,000 |
| − Razorpay gateway fee | −₹519 |
| − Razorpay payout fee | −₹5.90 |
| **Net platform margin (before GST on our own fee)** | **≈ ₹4,475** |
| As % of gross fee revenue | **~89.5%** — Razorpay eats ~10.5% of our take |
| As % of total money moved (₹22,000) | **~20.3% net** (vs. 25% headline blended rate) |

**Takeaway: our real take-rate is ~20%, not the ~25% headline** once Razorpay's cut is accounted for. Still healthy, but this is the number to model runway on, not the gross fee.

---

## 3. GST on Our Own Platform Fee (compliance flag, not yet resolved)

Our 10%/15% platform fees are a **service we're providing** (facilitating the marketplace) — this fee itself is likely subject to **18% GST as output tax**, payable to the government, separate from Razorpay's GST-on-their-fee above.

- If we're GST-registered (we should be, once revenue crosses the registration threshold or immediately if we want input credit on Razorpay/tooling GST), we owe 18% output GST on our ₹5,000 fee revenue in the example = **₹900 payable**, though we can claim input credit on the GST we paid Razorpay (~₹95 embedded in their ₹519 fee) and on our tooling GST.
- **I'm not a chartered accountant and this needs sign-off from one before launch** — specifically whether our fee should be GST-inclusive (fee stays ₹5,000, we net less) or GST-exclusive (we charge ₹5,000 + 18% GST on top, brand/creator effectively pay more). This changes the real numbers materially and I don't want to guess on tax structure.
- **Ask of Swapnil:** approve a small CA consult budget (~₹5,000–10,000 one-time) before we go live with real money — this is not optional given we're moving brand and creator funds through escrow.

---

## 4. Open Decision — Who Absorbs Razorpay's Fees?

Two options, and it changes our real margin materially at scale:

**Option A (recommended) — Influora absorbs Razorpay costs.**
Brand pays exactly "budget + our fee," creator receives exactly "rate − our fee." Clean, matches what we already told brands/creators in the pricing proposal, and Razorpay's cost is just our cost of doing business (~10.5% of fee revenue, per §2). Simple, no surprise line items.

**Option B — Pass Razorpay's cost through.**
Brand pays "budget + our fee + gateway fee," creator receives "rate − our fee − payout fee." Preserves our full margin but adds visible extra charges on both sides, which undercuts the "transparent, no surprises" pitch from the pricing proposal and adds real UI/support complexity for a few rupees per transaction.

**My recommendation: Option A.** At this transaction size the absolute Razorpay cost is small (~₹525 on a ₹22,000 flow); the trust cost of nickel-and-diming brands/creators on gateway fees is not worth it this early. Revisit only if we're doing very high transaction volume and the aggregate becomes meaningful.

---

## 5. Volume Projection — What Razorpay Costs Us at Scale

Assuming the volume tiers from the pricing proposal and Option A (we absorb Razorpay costs):

| Monthly campaign spend processed | Gross platform fee revenue (blended ~20-25%*) | Razorpay cost (~2.4% of gross flow) | Net margin |
|---|---|---|---|
| ₹5,00,000 | ~₹1,00,000–1,25,000 | ~₹12,000 | ~₹88,000–1,13,000 |
| ₹20,00,000 | ~₹3,20,000–4,00,000 (lower tier % applies) | ~₹48,000 | ~₹2,72,000–3,52,000 |
| ₹50,00,000 | ~₹7,00,000–8,50,000 | ~₹1,20,000 | ~₹5,80,000–7,30,000 |

*Blended rate drops as brands qualify for lower volume tiers (10%→5% brand-side) — this table is directional, not exact; I'll build a real calculator once Vikram has the fee-scope extension live and we have actual transaction data.

---

## 6. What I Need From Engineering

- Log Razorpay's actual fee (returned in their webhook/settlement payload) against every transaction, not just our platform fee — so I can reconcile real net margin monthly instead of estimating.
- Flag in `wiki/processes/cost-log.json`-equivalent for Influora transactions (separate from Sage Digital's own tooling cost log) once volume starts.

## 7. Asks of Swapnil

1. **Approve Option A** (we absorb Razorpay fees) or pick Option B.
2. **Approve a CA consult** on GST treatment of our platform fee before real money moves — this is not something I should guess on.
3. Confirm whether we register for GST before or at launch (affects invoicing setup Vikram needs to build).

Sources: [Razorpay payment gateway pricing](https://razorpay.com/blog/razorpay-payment-gateway-pricing-explained/), [Razorpay UPI charges explained](https://razorpay.com/blog/upi-charges-explained-mdr-vs-platform-fees/), [RazorpayX fees and taxes docs](https://razorpay.com/docs/x/manage-teams/billing/), [Razorpay Route](https://razorpay.com/route/)
