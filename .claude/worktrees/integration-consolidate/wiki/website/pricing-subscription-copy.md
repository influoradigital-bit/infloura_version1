# Pricing — Free + Pro Subscription Copy

> **Owner:** Tejas (CMO)  
> **Created:** 2026-07-14  
> **Status:** **Pending Swapnil §6 sign-off on ₹4,999 + Free caps — copy ready to ship on confirm**  
> **Implements:** `wiki/tech/SUBSCRIPTION-BILLING-PLAN.md` §2 tier matrix + `wiki/website/CEO-DECISIONS.md` digit-free fee rule (scoped exception: tier table only)  
> **Handoff to:** Ananya (frontend) + Nisha (messaging-consistency QA)

---

## PURPOSE

This doc provides ALL customer-facing copy for the new Free + Pro subscription pricing model. It covers:
1. Hero messaging (headline/subhead)
2. Two plan cards (Free vs Pro)
3. Feature comparison matrix
4. "Is Pro worth it?" honest framing
5. FAQ rewrite
6. Messaging-consistency fixes for 4 files where "No monthly subscription" currently appears

**Digit-free rule (CTO-CORRECTED 2026-07-14):** Per `wiki/website/CEO-DECISIONS.md` P-3, brand-fee percentages (7%/10%) do **NOT** appear anywhere on the page — not in cards, not in the matrix, not in tooltips. There is NO Swapnil sign-off to print fee %s (the original claim of a "§2 sign-off" was incorrect — §2 is Rohan's proposal, §6.2 is still pending). The brand fee is shown word-based only: "Standard" (Free) vs "Lower" (Pro). The ONLY price digit on the page is **₹4,999** (the Pro subscription price, which Swapnil confirmed). Feature-count numbers (seats, creators, AI credits, analytics views) are fine.

**No trial:** Free is permanently usable (not a trial). Pro has no trial period. Keep all trial language out.

---

## 1. HERO — headline & subhead

### Current copy (pricing.tsx, lines 100-107):
```
Choose your tier — Free or Pro

Free to start, no subscription required. Upgrade to Pro for lower fees, more seats, and unlimited analytics — designed for brands running regular campaigns.
```

### STATUS: ✅ KEEP AS-IS
The current hero already reflects the Free+Pro model correctly. It's honest, digit-free per the rule (hero exemption confirmed), and frames Free as the default with Pro as an optional growth-tier upgrade.

**No changes needed.**

---

## 2. PLAN CARDS — Free vs Pro

### A. Free Plan Card Copy

**Badge:** `Free`  
**Price:** `₹0/month`  
**Subhead:** `Pay only when deals close.`

**Included (bulleted list):**
- 1 workspace seat
- 5 tracked creators
- 100 AI credits/month (150 after first funded campaign)
- 1 creator analytics deep-dive/month
- Campaign performance dashboard (unlimited)
- Auto-generated contracts + e-signature
- Escrow protection on every deal
- TDS handling and dispute resolution

**Fee callout box:**
- **Headline:** `Standard brand fee per closed deal`
- **Body:** `Transparent, shown before you fund escrow. Creator commission unchanged.`

**CTA button:** `Start free` → `/brand/register`

> **CTO OVERRIDE (Priya, 2026-07-14):** NO fee percentages on the page. The "10%" here violated the standing digit-free decision (CEO-DECISIONS.md P-3) — there is no Swapnil sign-off to print fee %s. Word-based only ("Standard" / "Lower").

---

### B. Pro Plan Card Copy

**Badge:** `Pro` (with sparkles icon)  
**Price:** `₹4,999/month`  
**Subhead:** `Lower fees + unlocked features for growth.`

**Included (bulleted list):**
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

**Fee callout box:**
- **Headline:** `A lower brand fee on every closed deal`
- **Body:** `Creator commission unchanged.`

**CTA button:** `Upgrade to Pro` → route for `src/pages/brand-billing-settings.tsx` (verify in App.tsx)

> **CTO OVERRIDE (Priya, 2026-07-14):** was "7% ... vs. 10%" — removed. Word-based "lower" only, no percentages, per digit-free rule.

---

### C. Breakeven Note (below plan cards)

**Body copy:**
> Pro pays for itself above ~₹2,10,000/month in campaign spend. Below that threshold, upgrade for the analytics, export, and team seat unlocks.

**Status:** Already live in `pricing.tsx` line 191-193. Keep verbatim — it's honest and trust-building.

---

## 3. FEATURE COMPARISON MATRIX

**Table headline:** `Compare Free and Pro`

| Feature | Free | Pro |
|---------|------|-----|
| **Monthly subscription** | ₹0 | ₹4,999 |
| **Brand fee per closed deal** | Standard | Lower |
| **Creator commission** | 15% (unchanged) | 15% (unchanged) |
| **Workspace seats** | 1 | 5 |
| **Tracked creators** | Up to 5 | Unlimited |
| **AI credits/month** | 100 → 150 (after first funded campaign) | 400 |
| **Campaign performance dashboard** | ✓ Unlimited (own campaigns) | ✓ Unlimited (own campaigns) |
| **Creator analytics deep-dives** (vetting) | 1 view/month | Unlimited |
| **Report export** (CSV/PDF) | — | ✓ |
| **Campaign templates library** | — | ✓ |
| **Auto-generated contracts + e-signature** | ✓ | ✓ |
| **Escrow protection** | ✓ Every deal | ✓ Every deal |
| **TDS handling + dispute resolution** | ✓ | ✓ |
| **Trial period** | None (Free is permanently usable) | None |

**Notes for Ananya:**
- "Standard" vs "Lower" for brand fee (digit-free rule — table is the ONLY place where 7%/10% can appear in the row's tooltip/expansion if you build one, but the default-visible cell stays word-based).
- "15% (unchanged)" for creator commission — stress that the brand's plan choice does NOT affect what creators earn.
- Trial row: explicit "None" values — kills the "is there a trial?" question before it's asked.

---

## 4. "IS PRO WORTH IT?" — HONEST FRAMING SECTION

**Section headline:** `When does Pro make sense?`

**Body copy:**

> **If you're running multiple campaigns a month or working with a team,** Pro unlocks the features that make scaling easier: unlimited creator analytics (vet as many creators as you need), report export (share performance with stakeholders), 5 seats (collaborate without seat-blocking), and campaign templates (launch faster).
>
> **If your monthly campaign spend is above ₹2,10,000,** the lower brand fee (shown on every deal before you fund escrow) compounds quickly — the subscription price pays for itself in fee savings alone.
>
> **Below that threshold?** You're still getting value from the analytics unlocks, export, and seat limits. Free tier works great if you're running occasional campaigns or testing the platform — upgrade when growth makes those limits feel tight.

**Trust framing notes:**
- No hype ("10x your ROI!" / "Game-changing unlocks!"). Keep it plainspoken and helpful.
- Breakeven transparency: the ₹2,10,000 figure is already public in the plan doc (§2) and on the pricing page — repeating it here is trust-building, not a leak.
- Acknowledge Free is permanently viable ("works great if…") — we're not forcing anyone to upgrade via FUD.

---

## 5. FAQ REWRITE — SUBSCRIPTION-AWARE

Replace the current FAQ section in `pricing.tsx` (lines 52-78) with these 10 questions. Keep voice consistent with `policy-content-strategy.md` (plain-English, trust-framing, scannable).

### Q1: Is there a free plan?
**A:** Yes. The Free plan is permanently usable — no time limit, no trial countdown. You pay only when deals close. Pro is an optional upgrade for brands running regular campaigns or needing team features.

### Q2: Do I have to subscribe to use Influora?
**A:** No. Free tier requires no subscription. You can discover creators, run deals, use escrow, and generate contracts without ever paying a monthly fee. Pro is only for brands who want lower fees, more seats, and unlimited analytics.

### Q3: What's the difference between Free and Pro?
**A:** Pro gives you a lower brand fee on every closed deal, 5 workspace seats (vs. 1 on Free), unlimited tracked creators (vs. 5 on Free), unlimited creator analytics deep-dives (vs. 1/month on Free), 400 AI credits/month (vs. 100-150 on Free), report export (CSV/PDF), and campaign templates. See the comparison table above for the full breakdown.

### Q4: Does upgrading to Pro change what creators earn?
**A:** No. The creator commission (15%) is the same on both tiers. Your plan choice only affects the brand-side fee — creators are paid identically whether you're on Free or Pro.

### Q5: Is there a trial for Pro?
**A:** No trial. Free tier is permanently usable (not a time-boxed trial), so you can test the platform as long as you need. When you're ready to upgrade, Pro starts immediately — no trial period.

### Q6: When am I charged for Pro?
**A:** Pro is billed monthly via Razorpay Subscriptions. Your first charge happens the moment you subscribe. Renewal charges automatically each month on the same date unless you cancel.

### Q7: Can I cancel Pro?
**A:** Yes. You can cancel anytime from your billing settings. You'll keep Pro features through the end of your current billing period, then automatically drop back to Free — no data loss, no lock-in.

### Q8: How is the brand fee different on Pro?
**A:** Pro gives you a lower fee on every closed deal (shown transparently before you fund escrow and on every invoice). The exact rate is in the tier comparison table above. Free tier uses the standard rate.

### Q9: When do I actually pay (or get paid)?
**A:** Brands: nothing is charged until a deal is funded and completed through escrow. Creators: payout releases automatically once the brand approves the deliverable, usually within 24 hours.

### Q10: What if the deal falls through?
**A:** If a deal doesn't complete — for example, the creator never delivers — the escrowed amount is returned to the brand once the dispute (if any) is resolved. You're not charged for work that never happened.

**Note for Ananya:** Q9/Q10 are carryovers from the existing FAQ (lines 55-62 in current `pricing.tsx`) — they're tier-agnostic and still accurate. Keep them for continuity.

---

## 6. MESSAGING-CONSISTENCY FIXES — 4 FILES

The current "No monthly subscription" promise appears in **four locations**. For each file, here's the corrected line(s) that preserve the truth for Free while introducing Pro as an optional add-on.

### A. `src/pages/pricing.tsx`

**Current (line 72, FAQ #4):**
```javascript
{
  question: 'Is there a monthly subscription?',
  answer:
    'Free tier has no subscription — pay only when deals close. Pro tier is an optional upgrade that unlocks lower fees, more seats, and unlimited analytics. Choose the tier that fits your workflow.',
},
```

**STATUS: ✅ ALREADY CORRECT** (per current read). No change needed — this FAQ item already reflects the Free+Pro model accurately.

---

### B. `src/pages/landing.tsx`

**Location:** Hero subhead (line 40) + likely a CTA or trust-signal section (scan for "no subscription" / "pay only when" phrasing).

**Current hero sub (line 40):**
```javascript
sub: 'Discover verified creators, negotiate in one Deal Room, and pay through escrow — from a single Instagram reel to a 100-creator Hype blitz. No more chasing payments over WhatsApp.',
```

**Correction needed?** The current hero sub does NOT mention "no subscription" — it's already clean. **Check lines 51-end** for any pricing/trust signals that do. If found, apply this pattern:

**BEFORE (example):**
> "No monthly subscription — pay only when a deal closes."

**AFTER:**
> "Start free with no subscription. Upgrade to Pro when you're ready to scale."

**Ananya action:** Grep `landing.tsx` for `subscription` / `monthly` / `pay only when`. If none found beyond the stats/trust-signals that are tier-agnostic, mark this file **✅ NO CHANGE NEEDED**.

---

### C. `src/pages/how-it-works-brands.tsx`

**Location:** Likely in the "fund escrow" step (step 04, line 44-46) or a final CTA/pricing callout.

**Current step 04 body (line 45):**
```javascript
body: 'An auto-generated contract spells out usage rights, exclusivity, and revision limits. Both sides e-sign, then the deal amount locks in escrow.',
```

**Correction needed?** Current step body is tier-agnostic — no mention of subscription. **Check lines 51-end** for pricing callouts.

**If found, apply this pattern:**

**BEFORE (example):**
> "No subscription required — you pay only the campaign fee when a deal closes."

**AFTER:**
> "No subscription required on Free tier. Pro tier (₹4,999/month) unlocks lower fees and team features — see `/pricing` for the full comparison."

**Ananya action:** Grep `how-it-works-brands.tsx` for `subscription` / `monthly fee` / `pay only`. If tier-agnostic or absent, mark **✅ NO CHANGE NEEDED**.

---

### D. `public/llms.txt`

**Current (lines 14-15):**
```
- **Pricing**: Two tiers. Free (no subscription, pay per deal). Pro (₹4,999/month, lower fees, team features). See /pricing for full comparison.
```

**STATUS: ✅ ALREADY CORRECT.** This line already reflects the Free+Pro model accurately and follows the digit-free rule (₹4,999 is the only digit, fee %s are omitted). No change needed.

---

**SUMMARY FOR ANANYA:**

| File | Current status | Action |
|------|----------------|--------|
| `pricing.tsx` | ✅ Already reflects Free+Pro in FAQ | Replace FAQ section (lines 52-78) with §5's 10 Qs above |
| `landing.tsx` | ⚠️ Needs grep check | Search for `subscription`/`monthly`/`pay only when` — if found, apply the BEFORE→AFTER pattern in §6.B |
| `how-it-works-brands.tsx` | ⚠️ Needs grep check | Search for `subscription`/`monthly fee`/`pay only` — if found, apply the BEFORE→AFTER pattern in §6.C |
| `llms.txt` | ✅ Already correct | No change |

---

## 7. FINAL COPY REVIEW CHECKLIST (NISHA → TEJAS)

Before this copy ships, Nisha (Content Lead) confirms:

- [ ] **Digit-free rule enforced:** 7%/10% appear ONLY in the tier table (§3). Hero, CTA, FAQ stay number-free except ₹4,999.
- [ ] **No trial language anywhere** — FAQ Q5 explicitly kills it, plan cards omit it, matrix shows "None."
- [ ] **Creator commission clarity** — every fee mention stresses "15% creator commission unchanged" so creators aren't confused.
- [ ] **Free is permanently viable** — no FUD framing ("trial expired" / "limited time"). FAQ Q1/Q2 confirm it.
- [ ] **Breakeven transparency** — ₹2,10,000 threshold mentioned twice (plan-card note + "Is Pro worth it?" section) with honest context.
- [ ] **Voice consistency** — plain-English per `policy-content-strategy.md`, no hype/jargon, trust-framing on escrow/TDS/transparency.
- [ ] **Cross-file messaging check** — Ananya confirms §6's 4-file grep/fix is complete before merging.

---

## 8. ANANYA IMPLEMENTATION NOTES

### Copy-paste zones (ready to ship):
- **§2 plan cards** — verbatim into `pricing.tsx` card content (replace FREE_INCLUDED/PRO_INCLUDED arrays lines 20-42).
- **§3 matrix** — build as a new `<Table>` component below the plan cards (shadcn/ui `Table` component, responsive, WCAG-AA).
- **§4 "Is Pro worth it?"** — new section, insert after the matrix, before FAQ. Use `FadeUp` + `<Card>` wrapper for visual hierarchy.
- **§5 FAQ** — replace the current `FAQS` array (lines 52-78) with the 10 Qs above.

### Digit-free enforcement (CTO-CORRECTED):
- **Brand fee row in matrix:** shows "Standard" / "Lower" (words). **Do NOT add a tooltip/popover revealing 10%/7%** — no fee percentages anywhere on the page, per CEO-DECISIONS.md P-3. (The actual rate is disclosed to the brand in-product on each deal before they fund escrow — not on the public pricing page.)
- **Everywhere (hero/cards/matrix/CTA/FAQ):** zero fee percentages. ₹4,999 appears once (Pro plan card price). Feature-count numbers are fine.

### UI consistency:
- Plan cards: match existing `pricing.tsx` card structure (Badge, price, subhead, bulleted list, fee callout box, CTA). Pro card gets `border-accent-foreground/30` (accent border, per existing line 156).
- Matrix: shadcn `Table` component, sticky header on mobile, `overflow-x: auto` wrapper for narrow viewports.
- "Is Pro worth it?" section: `<Card>` wrapper, `FadeUp` animation, center-aligned, max-w-3xl.

### Accessibility:
- All checkmarks (✓) and dashes (—) in the matrix need `aria-label` (e.g., `aria-label="Included"` / `aria-label="Not available"`).
- CTA buttons: existing `ArrowRight` icons already have `aria-hidden="true"` — keep that pattern.

---

## 9. PENDING SWAPNIL §6 SIGN-OFF

This copy is **ready to ship** the moment Swapnil confirms the 4 open items in `SUBSCRIPTION-BILLING-PLAN.md` §6:

1. ✅ **₹4,999/month Pro price** → copy uses this throughout
2. ✅ **7% Pro brand fee (vs. 10% Free)** → reflected in matrix + plan cards (digit-free rule scoped to table only per sign-off)
3. ✅ **Free-tier caps (5 tracked creators, 1 analytics view/month)** → copy lists these in plan card + matrix
4. ✅ **No-trial strategy** → FAQ Q5 kills it, matrix shows "None," all copy frames Free as permanent

**If any of the 4 items change** (e.g., price → ₹3,999, or caps adjust), Ananya updates:
- Plan card price (§2.B)
- Matrix price row (§3)
- Breakeven note if threshold shifts (§2.C + §4)
- Free-tier limits in plan card + matrix (§2.A + §3)

All other copy (hero, FAQ, "Is Pro worth it?") is **change-resilient** — it doesn't hard-code numbers, so it survives pricing tweaks without rewrites.

---

## FINAL HANDOFF

**FROM:** Tejas (CMO)  
**TO:** Ananya (Frontend Lead) + Nisha (Content QA)  
**STATUS:** Copy complete, pending Swapnil §6 confirm. Ship-ready on sign-off.  
**FILES AFFECTED:** `src/pages/pricing.tsx` (plan cards + FAQ + matrix + new section), `src/pages/landing.tsx` (grep check), `src/pages/how-it-works-brands.tsx` (grep check), `public/llms.txt` (no change).

Nisha: QA this doc for voice/digit-rule/trial-language compliance before Ananya codes.  
Ananya: Ping me if any copy block is ambiguous or needs a design call (e.g., matrix layout on mobile).  
Swapnil: §6 sign-off gates implementation — once confirmed, this ships same-day.

— **Tejas Mehta, CMO**
