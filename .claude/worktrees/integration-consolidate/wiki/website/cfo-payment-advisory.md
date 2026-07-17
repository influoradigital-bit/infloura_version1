# CFO Advisory — Fees & Payment Structure (pre-publish review)

> **Author:** Rohan (CFO) | **For:** Swapnil (CEO) — final call needed on items marked 🔴
> **Date:** 2026-07-13
> **Trigger:** CEO-DECISIONS.md priced pricing page as "0% to start, transparent fee per closed deal" with no hard % published; policy-list.md B1/B2/B3 need this locked before draft.
> **Builds on:** `wiki/decisions/budget-proposals/2026-07-09-influora-pricing-proposal.md` (fee tiers, CEO not yet signed off) + `wiki/processes/PLATFORM_COST_STRUCTURE.md` (Razorpay margin math, GST open item).

---

## 1. Platform fee on brands — RECOMMENDATION READY, needs CEO sign-off

**Recommendation: tiered % of campaign value, charged on top of the brand's budget at escrow-funding time.**

| Trailing 30-day brand spend | Fee |
|---|---|
| Under ₹1,00,000/mo | **10%** (default) |
| ₹1,00,000–5,00,000/mo | 8% |
| ₹5,00,000–20,00,000/mo | 6% |
| Above ₹20,00,000/mo | 5% |

Reasoning: matches the marketplace-fee norm (10–20% is standard for pay-per-use influencer/marketplace platforms; Fiverr's blended buyer+seller take is ~25%, we land at ~25% blended / ~20% net-of-Razorpay too — see PLATFORM_COST_STRUCTURE.md §2). Volume tiering rewards high-spend brands and is what makes an agency tier work without separate pricing (agency spend rolls up across clients).

**This was proposed 2026-07-09 and never got a CEO decision on the exact number** — CEO-DECISIONS.md only approved the *messaging* ("0% to start, fee per closed deal"), not the %. **🔴 Swapnil: approve these bands (or set your own) before `/pricing` and B1 can ship with real numbers.** Until approved, `/pricing` stays on the value-framing copy with no digits, per the existing CEO decision — do not let Ishaan/Ananya publish a % that hasn't been signed off.

---

## 2. Creator commission — ALREADY LOCKED, no further data needed

**15% default, deducted at escrow release (not at withdrawal), admin-configurable 0–30%, per-creator/plan overrides possible.** This is engineering-locked by Priya (CTO) 2026-07-07 in `wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md` §1A and already built into `PlatformFeeConfig`. Nothing for me to re-decide here — CFO confirms 15% is financially sound (in line with Upwork/Fiverr seller-side take of 10–20%). Policy B3/creator guidelines should just state this number; no research gap.

---

## 3. Hype Campaign pricing — 🔴 OPEN, needs a decision before `/features/hype` and `/pricing` describe it

There is **no separate fee spec for Hype campaigns anywhere in the repo** — `content-map.md` even flags "Hype Campaign Pricing (if different structure)" as an open question, unresolved.

**My recommendation: no separate fee structure.** Hype is a campaign *type* (100 creators, 72-hour window, one-tap accept), not a different money-flow. The per-reel rate the brand sets during Hype setup is just the gross contract value per creator — it should run through the **exact same fee architecture**: brand fee (tiered, §1 above) added on top at escrow-funding, creator fee (15%) deducted at release. Building a third `FeeScope` for Hype specifically would be extra engineering with no clear justification, and a different fee % for Hype vs. regular collabs would be confusing to disclose under the E-Commerce Rules 2020 transparent-pricing requirement.

**Policy wording:** describe the fee as baked into the standard escrow flow, same rate table as any other deal — do not present Hype as having its own commission line. **Ask of Swapnil:** confirm no special Hype rate before Aditya/Ishaan draft the `/features/hype` fee section.

---

## 4. TDS — Section 194-O applies, but 🔴 CA CONFIRMATION MANDATORY before B3/`/tds` publishes

Influora functions as an **e-commerce operator** facilitating payment for services (creator content) between a brand (buyer) and creator (participant/seller) through its platform — this squarely fits the fact pattern Section 194-O was written for, so applicability is not in doubt.

What I will **not** publish without a CA's written sign-off:
- **The exact current rate.** Section 194-O's rate has changed by Finance Act amendment before (1% at introduction; reduced by later Budget). Rates and thresholds are legislated and revised — I am not going to guess or copy a number from memory into a live tax policy page. The CA must confirm the rate in force at time of publish.
- **PAN-linked mechanics:** the ₹5,00,000/year threshold exemption applies only where the creator has furnished PAN; no-PAN cases attract a much higher deduction rate (historically 5%) under 206AA-type provisions. This distinction must be correct in the `/tds` copy or it misleads creators about their net payout.
- **GST-on-our-own-fee** is a separate, still-open question flagged in `PLATFORM_COST_STRUCTURE.md` §3 (whether our 10%/15% fee is GST-inclusive or exclusive) — do not conflate this with 194-O TDS in the policy copy; they're different taxes, different payers.

**Ask of Swapnil (repeated from PLATFORM_COST_STRUCTURE.md, still open):** approve a one-time CA consult (~₹5,000–10,000) before `/tds` and B3 go live with real numbers. `/tds` should ship as a v0 template with the "pending CA/legal review" banner per CEO-DECISIONS.md #2, not with a hard-coded rate I haven't had verified.

---

## 5. Escrow partner disclosure — name generically, not "Razorpay" in public policy text

**Recommendation: use "a licensed/RBI-authorized Payment Aggregator"** in the public B1 policy and Terms, not "Razorpay" by name. Reasons:
- Keeps us free to change PA providers later without rewriting a published legal policy.
- Avoids needing Razorpay's marketing/trademark consent to name them in our public-facing legal docs (a merchant-agreement check item, not something to assume).
- The RBI PA/PG Guidelines requirement is that we disclose **that funds sit with a licensed aggregator, not pooled in Influora's own account** — it does not require naming the specific vendor to the public.

Internally, keep Razorpay named explicitly in the **internal** vendor/sub-processor register (D3, `wiki/policies/internal/`) and in the actual merchant/user agreement if legal decides that level of specificity is needed there — that's a different, non-public document. **Flag for legal:** confirm whether Razorpay's merchant terms require or forbid public naming either way before finalizing B1 wording.

---

## 6. No-refund policy — 🔴 real legal risk in how it's framed, escalate before publishing

The escrow-instead-of-refunds model is sound in substance, but the **labeling** "NO REFUNDS" as a blanket policy is a risk if published as-is:

- **Consumer Protection (E-Commerce) Rules 2020, Rule 5** requires clear, upfront disclosure of refund/cancellation terms — a strict no-refund policy is legally *permitted* as long as it's disclosed prominently before the transaction and isn't hidden in fine print. That part is fine if A4/B1 disclose it clearly at checkout/escrow-funding, not just buried in Terms.
- **The real exposure:** under CPA 2019 §2(46), a contract term that creates a **significant imbalance in the parties' rights** (brand pays into escrow, creator fails to deliver, brand has zero path to get funds back) can be challenged as an **unfair contract term**, independent of whether it's disclosed. "No refunds, period" combined with a dispute process that *cannot* return the brand's escrowed funds when a creator genuinely fails to deliver is the one-sided setup that invites that challenge — and it's also a bad-faith risk for a platform whose whole pitch is trust/escrow safety.
- **My recommendation, not a legal rewrite, a framing fix:** don't publish "no refunds" as the headline. Publish **"funds move only through escrow release or a dispute-resolution outcome — no informal/unilateral refunds outside that process."** Then make sure B6 (Dispute Resolution Policy) explicitly states that one possible resolution outcome, when a creator fails to deliver or a brand's rejection is upheld, **is the brand's escrowed funds being released back to the brand** — that outcome is functionally a refund, just gated behind adjudication instead of being automatic/on-demand. If B6 doesn't already allow that outcome, that's a bigger issue than wording and needs Swapnil + legal to resolve before A4/B1/B6 go live together.

**Ask of Swapnil:** confirm the dispute-resolution policy (B6) actually permits fund-return-to-brand as an outcome. If yes, my framing fix above resolves the risk. If B6 is silent or says otherwise, escalate to legal before any of A4/B1/B6 publish — this is the one item here I'd call a stop-ship risk, not just a nice-to-have.

---

## Summary — what's ready vs. what's blocked

| Item | Status |
|---|---|
| Creator commission (15%) | ✅ Locked, ready to publish |
| Brand fee tiers (10%→5%) | 🔴 Needs Swapnil's number sign-off (recommendation ready) |
| Hype pricing | 🔴 Needs Swapnil confirmation: same fee table, no special rate |
| TDS 194-O rate/thresholds | 🔴 CA sign-off mandatory — do not hard-code a rate |
| Escrow partner naming | ✅ Recommendation: generic "licensed PA" publicly; flag Razorpay-terms check to legal |
| No-refund framing | 🔴 Stop-ship risk if B6 doesn't allow fund-return-to-brand as a dispute outcome — confirm before publish |

All P0 money/tax pages (A4, A6, B1, B2, B3) should ship as v0 templates with the "pending legal/CA review" banner per CEO-DECISIONS.md #2 regardless — none of the above blocks the build, but the 🔴 items block filling in real numbers/claims.
