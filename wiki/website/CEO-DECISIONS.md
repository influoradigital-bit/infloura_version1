# CEO DECISIONS — Influora.in Website Rebuild

> **Swapnil Maruti, CEO** — 2026-07-13
> Phase 1 REVIEWED and APPROVED. These decisions unblock Phase 2/3 build.

---

## ✅ PHASE 1 APPROVAL

All 4 deliverables reviewed and approved:
- `personas.md` (Tejas) — 6 personas, India-specific ✅
- `keywords.md` (Aditya) — GEO-first, 9 clusters, schema plan ✅
- `content-map.md` (Nisha) — 35 pages mapped ✅
- `homepage-copy.md` (Ishaan) — full homepage copy ✅

**Verdict:** Strong work. Proceed to build.

---

## 🎯 DECISIONS ON OPEN QUESTIONS

### 1. Pricing model
**DECISION:** Do NOT publish hard fee percentages yet. Pricing page uses the "Pay only when deals close" framing with the value breakdown (what's included) but NO specific % until Rohan finalizes. Use "0% to start, transparent fee per closed deal" messaging. Ship the page structure; fill numbers later.

### 2. Legal review (Terms/Privacy)
**DECISION:** Adapt standard templates NOW with a visible disclaimer ("This is a template pending legal review"). Do not block the build on external counsel. Legal pages ship as v0 drafts. Flag for counsel review post-launch.

### 3. Blog CMS
**DECISION:** **Option B — Markdown files in repo.** No backend blog API for MVP. Fast, SEO-friendly, no infra. Vikram does NOT build blog endpoints. Migrate to CMS only if blog scales past ~50 posts. This is the fastest path to indexed content.

### 4. Client logos on About
**DECISION:** No client logos until we have written permission. Use anonymized stats and "trusted by 500+ brands" text instead. No fake logos.

### 5. Blog author attribution
**DECISION:** "Influora Team" for all posts. Cleaner, no personal-brand dependency.

### 6. Minimum follower count
**DECISION:** Do not state a hard threshold publicly. Say "verified creators of all sizes, from nano to macro." Keeps it inclusive (matches persona work — tier 2/3 inclusivity).

### 7. KYC SLA
**DECISION:** State "24–48 hours" — matches existing backend copy.

### 8. Sample contract
**DECISION:** Show a GENERIC template preview (not a real redacted contract). No client data risk.

---

## 🔗 URL STRUCTURE RECONCILIATION

Nisha's content-map and Aditya's keywords doc used slightly different URL schemes. **LOCKED canonical structure** (Ananya follows this):

```
/                          Homepage (enhance existing landing.tsx)
/about                     About Us
/how-it-works/brands       How It Works — Brands
/how-it-works/creators     How It Works — Creators
/pricing                   Pricing
/features/escrow           Escrow Protection (also the primary GEO escrow page)
/features/deal-room        Deal Room
/features/hype             Hype Campaigns
/features/contracts        Contracts & Compliance
/blog                      Blog index
/blog/:slug                Blog post (flat slug, no /category/ in path)
/blog/category/:category   Category filter view
/support                   Support + FAQ
/kyc                       KYC explainer
/tds                       TDS explainer
/terms /privacy            Legal (v0 templates)
/guidelines/creators       Creator guidelines
/guidelines/brands         Brand guidelines
/refund-policy             Refund policy
```

Comparison pages (`/compare/qoruz` etc.) → DEFERRED to a later content wave. Not in first build.

---

## 🏗️ BUILD SCOPE — FIRST WAVE (Phase 3)

Priority order. Ship these first, defer the rest:

**TIER 1 — Ship now (highest impact):**
1. **Homepage enhancement** — premium 3D/motion, sharpened copy, trust signals, How-It-Works teaser (Ananya, using `/3d-cinematic-web`, `/framer-motion-variants`, `/soft-skill`, `/taste-skill`)
2. **Technical SEO/GEO foundation** — React Helmet meta tags, JSON-LD schema (Organization, Product, FAQPage), `sitemap.xml`, `robots.txt` (allow AI crawlers), `llms.txt` (Vikram + Aditya)
3. **Blog infrastructure** — markdown loader, blog index, post template, category filter, Article schema (Ananya)
4. **3 launch blog posts** — the top GEO-citable topics (Ishaan writes, Aditya SEO-reviews):
   - "How to Pay Influencers Safely in India (2026)"
   - "What is Escrow in Influencer Marketing?"
   - "Micro Influencer Pricing Guide 2026 (India)"

**TIER 2 — Ship next:**
5. `/features/escrow`, `/features/hype` pages
6. `/how-it-works/brands`, `/how-it-works/creators`
7. `/pricing`, `/about`

**TIER 3 — Trust/legal (v0 templates):**
8. `/terms`, `/privacy`, `/support`, `/kyc`, `/tds`, guidelines, refund

---

## 📐 DESIGN DIRECTION (non-negotiable)

- Build on existing "Lilac Mist" palette + `src/components/motion/*` — DO NOT reinvent the design system
- Every animation gated by `useReducedMotion()`
- WCAG AA on all CTAs — brand accent, NOT pale pastel (per standing feedback)
- Mobile-first, works on all devices
- Max 1 WebGL context per page, lazy-loaded (existing rule)
- Premium feel — no template look, no AI slop. Use `/taste-skill` + `/soft-skill` pre-flight checks.

---

## ▶️ NEXT: Arjun orchestrates Phase 3 build

Gate passed. Engineers may now write code. Pipeline per page:
Ananya build → Kavya QA → Meera verify (`npm run build`) → mark DONE.

— *Swapnil*

---

# 📋 POLICY DECISIONS — Influora Digital Private Limited (Swapnil, 2026-07-13)

Following the policy inventory + Tejas content strategy + Rohan CFO advisory (`wiki/website/cfo-payment-advisory.md`).

## Money / payments

| # | Item | DECISION |
|---|------|----------|
| P-1 | **Refunds** | **NO unilateral refunds. Escrow governs.** Reject the blanket "NO REFUNDS" headline (Rohan's stop-ship flag accepted). Official framing: *"Funds move only through escrow release or a dispute-resolution outcome — there are no informal or on-demand refunds outside that process."* |
| P-2 | **Dispute outcome (load-bearing)** | **CONFIRMED: the Dispute Resolution Policy (B6) MUST allow the brand's escrowed funds to be returned to the brand when a creator genuinely fails to deliver, or a brand rejection is upheld.** This is escrow doing its job, not a consumer refund. B6 + Refund/Escrow policy ship together. |
| P-3 | **Brand platform fee** | Rohan's tiered bands (10%→5% by 30-day spend) **APPROVED as the internal pricing decision.** BUT `/pricing` and policies stay **digit-free** ("0% to start, transparent fee per deal") until I explicitly greenlight publishing the numbers. Publishing pricing is a commitment — separate gate. |
| P-4 | **Creator commission** | 15% (Priya-locked, built into `PlatformFeeConfig`) — confirmed sound. Legal pages describe the *mechanism* ("commission deducted at escrow release, shown on every invoice"); hard % lives on `/pricing`, not baked into legal text. |
| P-5 | **Hype Campaign fee** | **APPROVED: no separate rate.** Same escrow + fee architecture as any deal. Do not present Hype with its own commission line. |
| P-6 | **TDS (Sec 194-O)** | Applies. **Do NOT hard-code a rate.** `/tds` + B3 ship as v0 templates with no number. **APPROVED: one-time CA consult (~₹5–10k)** to confirm current rate + PAN-threshold mechanics before any TDS number publishes. |
| P-7 | **Escrow partner naming** | **APPROVED: "a licensed / RBI-authorized Payment Aggregator" in public text** (not "Razorpay"). Razorpay named only in internal vendor register (D3). Key public claim: funds sit with a licensed PA, never pooled in Influora's own account. |

## Content / compliance

| # | Item | DECISION |
|---|------|----------|
| P-8 | **AI accuracy disclaimer** | **REQUIRED in Terms + wherever Meera/AI recommends.** "AI features are provided as-is, may produce errors or suboptimal suggestions, and do not constitute professional (legal/financial/tax) advice — verify before acting." |
| P-9 | **ASCI Advertising Disclosure** | **APPROVED into Wave 1** (per Tejas). We publicly enforce #ad/#sponsored disclosure — trust signal, not just compliance. |
| P-10 | **Policy list** | **APPROVED** as the working inventory (`policy-list.md`), with ASCI moved to Wave 1. |
| P-11 | **Draft now vs wait** | **DRAFT NOW.** Ishaan drafts P0 v0 templates immediately → counsel reviews something 90% done, not a blank page. Drafts live in `wiki/website/policy-drafts/` (NOT shipped). Publishing stays gated on the 3 human blockers below. |

## 🚧 HARD BLOCKERS — only the real human/company can provide these (cannot be invented)

1. **Grievance Officer** — a real name, email, address + resolution SLA. Templates use `[GRIEVANCE OFFICER — TBD]` placeholder until provided. `/grievance` cannot publish without it.
2. **Indian legal counsel + CA** — must validate all P0 money/KYC/TDS/DPDP policies before they flip from `noindex` to indexable.
3. **Company identity** — CIN, registered address, official support email for footer + `/contact`. Templates use `[CIN — TBD]` etc.

Until 1–3 land: P0 legal pages exist as `noindex` v0 drafts with a visible "pending legal review" banner. Nothing binds the company yet.

— *Swapnil*
