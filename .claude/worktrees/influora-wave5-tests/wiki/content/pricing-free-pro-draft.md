# Pricing Page — Free + Pro Messaging Draft

> **Nisha Patel, Content Lead** — 2026-07-14  
> For Task 18 (SUBSCRIPTION-BILLING-PLAN.md) — Free + Pro tier rewrite  
> Ananya applies this copy across `pricing.tsx`, `landing.tsx`, `how-it-works-brands.tsx`, `public/llms.txt`

---

## MESSAGING STRATEGY

**Core promise shift:**
- **OLD:** "No monthly subscription" (singular, universal)
- **NEW:** "Free tier has no subscription, Pro is an optional upgrade with lower fees + unlocked features"

**Key framing:**
- Free is still **pay-per-deal** (no subscription) — the original promise holds for the default tier
- Pro is the **growth tier** — monthly subscription + lower fees + feature unlocks for brands running regular campaigns
- **No trial language anywhere** — Free is permanently limited by design, not a countdown clock
- Breakeven messaging (optional): Pro wins on fee math above ~₹2L/month campaign spend, but that's not the headline — the analytics/export/seat limits are the upgrade pressure

**Fee number decision (CONTENT LEAD FLAG):**
Per `CEO-DECISIONS.md` #1, pricing stays "digit-free" until Swapnil greenlights publishing the numbers. **I am intentionally breaking that rule in Section 2 (tier comparison table) below** — showing "10% fee" on Free and "7% fee" on Pro — because:
1. Pro's value proposition is *"lower fees"* — saying that without showing the delta feels evasive, not transparent
2. The 10% Free fee is already live and charged today (per plan §0 "already live, CEO-approved") — we're not hiding a new number, we're disclosing an existing one
3. Brands doing the math need to see the 7% vs 10% split to understand the breakeven (₹2.1L/month spend per plan §2)

**If Swapnil rejects showing the numbers**, fallback copy for Section 2 is provided at the end of this doc ("number-free tier table").

---

## SECTION 1: HERO HEADLINE + SUBHEAD
**File:** `pricing.tsx` lines 82-89 (replaces "Pay only when deals close" hero)

### Headline (H1)
```
Choose your tier — Free or Pro
```

### Subhead (paragraph)
```
Free to start, no subscription required. Upgrade to Pro for lower fees, more seats, and unlimited analytics — designed for brands running regular campaigns.
```

**Rationale:** Neutral, tier-first framing. Free is still the default ("Free to start"), Pro is positioned as the scale-up option.

---

## SECTION 2: TWO-TIER COMPARISON TABLE
**File:** `pricing.tsx` lines 95-152 (replaces the current brand/creator side-by-side cards)

**New structure:** Two columns (Free | Pro), with feature rows. Keep the creator panel separate (unchanged — creators have no tiers).

### Free Tier — ₹0/month

**Tagline:**  
"Pay only when deals close"

**Included:**
- 1 workspace seat
- 5 tracked creators
- 100 AI credits/month (150 after first funded campaign)
- 1 creator analytics deep-dive/month
- Campaign performance dashboard (unlimited)
- Auto-generated contracts + e-signature
- Escrow protection on every deal
- TDS handling and dispute resolution

**Platform fee:**  
**10% brand fee per closed deal** (transparent, shown before checkout)  
15% creator commission (unchanged)

**CTA:**  
[Start free →]

---

### Pro Tier — ₹4,999/month

**Tagline:**  
"Lower fees + unlocked features for growth"

**Included:**
- 5 workspace seats
- Unlimited tracked creators
- 400 AI credits/month
- Unlimited creator analytics deep-dives
- Campaign performance dashboard (unlimited)
- Export reports (CSV/PDF)
- Campaign templates library
- Auto-generated contracts + e-signature
- Escrow protection on every deal
- TDS handling and dispute resolution

**Platform fee:**  
**7% brand fee per closed deal** (vs. 10% on Free)  
15% creator commission (unchanged)

**CTA:**  
[Upgrade to Pro →]

---

**Breakeven note (optional — include below the table):**  
*Pro pays for itself above ~₹2,10,000/month in campaign spend. Below that threshold, upgrade for the analytics, export, and team seat unlocks.*

---

### FALLBACK: Number-free tier table (if Swapnil rejects showing 7%/10%)

**Free — Platform fee:**  
Transparent fee per closed deal, shown before checkout. No subscription.

**Pro — Platform fee:**  
**Lower brand fee** (vs. Free) + ₹4,999/month subscription. Fee reduction covers the monthly cost at scale.

*(Omit the "10% / 7%" lines, omit the breakeven note)*

---

## SECTION 3: FAQ UPDATE
**File:** `pricing.tsx` lines 51-54 (FAQ question "Is there a monthly subscription?")

### OLD (current):
```
Q: Is there a monthly subscription?
A: No monthly subscription for standard use. Browsing, messaging, and building a profile are always free — fees apply only when a deal is funded and completed.
```

### NEW:
```
Q: Is there a monthly subscription?
A: Free tier has no subscription — pay only when deals close. Pro tier (₹4,999/month) is an optional upgrade that unlocks lower fees, more seats, and unlimited analytics. Choose the tier that fits your workflow.
```

---

## SECTION 4: CTA LANGUAGE
**File:** `pricing.tsx` line 213 (final section CTA heading)

### OLD:
```
Start free, pay only when a deal closes
```

### NEW:
```
Start free — upgrade to Pro when you're ready to scale
```

**CTA buttons (unchanged):**  
[Start free] / [Join free] (same as current)

---

## SECTION 5: ONE-LINER FOR LANDING / HOW-IT-WORKS PAGES
**Files:** `landing.tsx` line 167, and any other "pricing teaser" copy across the site

### Current (landing.tsx line 167):
```
Free to start. Pay only when a deal clears escrow.
```

### NEW:
```
Free to start — no subscription on the Free tier. Upgrade to Pro for lower fees and team features.
```

**Alternative (shorter, if space is tight):**
```
Free tier: pay-per-deal. Pro tier: lower fees + team features.
```

---

## SECTION 6: llms.txt SNIPPET
**File:** `public/llms.txt` line 15 (currently: "Pay only when a deal closes - no upfront platform fee to start.")

### OLD:
```
- **Pricing**: Pay only when a deal closes - no upfront platform fee to start. See /pricing for current terms.
```

### NEW:
```
- **Pricing**: Two tiers. Free (no subscription, pay per deal). Pro (₹4,999/month, lower fees, team features). See /pricing for full comparison.
```

---

## ADDITIONAL NOTES FOR ANANYA (Frontend implementation)

### Where the tier comparison lives
Replace the current "For brands" / "For creators" side-by-side cards in `pricing.tsx` (lines 96-151) with:
- **Two-column comparison:** Free (left) | Pro (right)
- Keep creator panel **below** the tier comparison as a separate section — creators are unaffected by the brand tier system
- Use the same `<Card>` component structure; swap content only

### Upgrade CTA behavior
- **Free tier CTA** ("Start free") → existing `/brand/register` flow (unchanged)
- **Pro tier CTA** ("Upgrade to Pro") → placeholder for now (button disabled or links to a "Coming soon" modal) until Vikram builds the Razorpay checkout (Task 12)
- Once Razorpay integration is live (Task 17), "Upgrade to Pro" → `/brand/settings/billing` (the new billing settings page with Razorpay hosted checkout)

### SEO meta description update
**File:** `pricing.tsx` line 67 (Seo component description prop)

**OLD:**
```
Influora is free to start for brands and creators. Pay only when a deal closes and clears escrow — no subscription, no fee to browse or negotiate.
```

**NEW:**
```
Two tiers for brands: Free (pay-per-deal, no subscription) and Pro (₹4,999/month, lower fees + team features). Creators join free. Transparent escrow-backed pricing.
```

---

## APPROVAL CHAIN

1. **Nisha (me)** — draft complete ✅
2. **Tejas** — strategy review (does this position Pro correctly as the growth tier?)
3. **Swapnil** — final sign-off on showing 7%/10% numbers (or fall back to "lower fee" framing)
4. **Ananya** — applies copy to all 4 files once approved

---

## REFERENCES

- Tier matrix: `SUBSCRIPTION-BILLING-PLAN.md` §2
- CEO fee-framing policy: `CEO-DECISIONS.md` #1 ("0% to start, digit-free until Swapnil greenlights")
- Audit findings: `SUBSCRIPTION-BILLING-PLAN.md` §0.5 Finding 9 (copy spans 4 files, not 1)
- Breakeven math: `SUBSCRIPTION-BILLING-PLAN.md` §2 "Why 7% and ₹4,999"

---

---

## TEJAS CMO REVIEW — 2026-07-14

**Decision:** Ship the numbers-shown version (7% / 10% fee split) **in the tier-comparison table only**. Keep hero and final CTA number-free.

**Rationale:**
1. The 10% Free fee is already live and charging real customers (plan §0). We're not disclosing a hypothetical — we're documenting operational reality.
2. Pro's value proposition is "lower fees than Free." Without showing the 7%-vs-10% delta, the upgrade case collapses into vague marketing language that doesn't help brands make an informed decision.
3. The tier-comparison table is the RIGHT context for hard numbers — it's a feature matrix where brands are actively comparing two options. The hero/CTA can stay aspirational ("Start free, upgrade to scale").
4. This is a middle path that respects the spirit of the original CEO rule (avoid leading with hard numbers in the hero) while solving the actual problem (brands need to see the fee delta to evaluate Pro).

**Implementation guidance for Ananya:**
- **Hero (pricing.tsx lines 82-89):** Keep number-free. Use Nisha's "Choose your tier — Free or Pro" headline + "Free to start, no subscription required. Upgrade to Pro for lower fees…" subhead.
- **Tier table (pricing.tsx lines 95-152):** Show "10% brand fee per closed deal" on Free card, "7% brand fee per closed deal (vs. 10% on Free)" on Pro card. Include the optional breakeven note below the table.
- **Final CTA (pricing.tsx line 213):** Keep number-free. Use "Start free — upgrade to Pro when you're ready to scale."
- **FAQ (pricing.tsx line 51-54):** Update per Nisha's draft (mentions both tiers, no hard fee numbers in the answer).

**Escalation required:**
This decision contradicts the letter of CEO-DECISIONS.md #1 ("Do NOT publish hard fee percentages yet"). I am escalating to Swapnil for final sign-off before Ananya applies this copy. If Swapnil rejects, fall back to Nisha's number-free tier table (draft lines 108-116).

— Tejas Mehta, CMO

**END DRAFT**

---

## SWAPNIL CEO SIGN-OFF — 2026-07-14

**Decision: ✅ APPROVED — ship the numbers-shown version (7%/10%) in the tier-comparison table only.**

Tejas's middle path is the right call. The original "digit-free" rule was meant to keep the marketing hero aspirational, not to hide operational facts from brands actively comparing tiers. The 10% fee is already live and charging — disclosing it in a feature-comparison context is transparency, not premature commitment.

**Approved for implementation:**
- **Tier table:** Show "10% brand fee" on Free, "7% brand fee (vs. 10% on Free)" on Pro, include the breakeven note
- **Hero/CTA/FAQ:** Keep number-free per Nisha's draft + Tejas's guidance
- **All 4 files:** pricing.tsx, landing.tsx, how-it-works-brands.tsx, public/llms.txt — Ananya may apply now

**CEO-DECISIONS.md update:** This sign-off does NOT revoke the original #1 rule — it carves out a specific exception for the tier-comparison table where hard numbers are appropriate. The hero, CTAs, and general marketing copy remain number-free.

— Swapnil Maruti, CEO

**NEXT:** Arjun route to Ananya for implementation.
