# Remaining Features — Post Subscription/Billing Plan

> **Author:** Priya (CTO) + Rohan (CFO) · **Date:** 2026-07-13**
> **Update 2026-07-14 (Priya):** Multi-agent verify + implement pass. All 6 items re-verified against real code (not the tracker's own prior claims); 3 quick wins shipped and independently build/test-verified, 3 remain blocked on CEO/product decisions. See "Verified update" sections below each item.
> **Update 2026-07-14 (rev. 2, Priya):** Re-audited the "Now in progress" Pro-tier items against real code — subscription work is **~15% (skeleton only)**, and A4/B5 are **0% (not started)**. Corrected the section below so the tracker stops implying progress that isn't there.
> **Supersedes the open items in** `FEATURE_GAP_ANALYSIS.md` (2026-07-07) **now that A1/A2/A5/B2/B6 are confirmed shipped and A3/A4/B5 are covered by** `SUBSCRIPTION-BILLING-PLAN.md`.
> **Method:** re-verified against current code, not the original doc's claims.

---

## Already closed — no action needed

| Gap | Status |
|---|---|
| A1 Reviews & Ratings | ✅ Shipped (`Review` entity + Brand/CreatorReviewController) |
| A2 Disputes & Refunds | ✅ Shipped (`Dispute` entity + Brand/Creator/AdminDisputeController + escrow refund) |
| A5 Brand↔Creator Messaging | ✅ Shipped (`DealMessage` + `/deals/{id}/messages`) |
| B2 Public Creator Pages + SEO | ✅ Shipped (`/:handle` public portfolio + `Seo.tsx` + JSON-LD `schema.ts`) |
| B6 Verified Badges | ✅ Shipped (`ui/verified-badge.tsx` + `CreatorProfile.verified`) |

## Now in progress — Pro-tier build (Tasks 10–20) — **actual build status, code-verified 2026-07-14**

| Gap | How it's being closed | Real status (code) |
|---|---|---|
| A3 Billing engine | Feature-gate subscription layer on the existing 10%/15% fee engine | 🟡 **~15% — skeleton only.** Built: 4 entities (`Plan`/`Subscription`/`Invoice`/`UsageCounter`), enums, migration `V54__subscription_billing.sql`, `InvoicePdfService` (unwired), mock billing page + route. **Not built (0%):** repositories, services, controllers/endpoints, Razorpay subscriptions client, webhook handling, plan-aware fee override, seat gating, plan-gate filter, analytics cap, dunning/renewal, admin console, tests. **Not usable end-to-end.** Functional core blocked on Swapnil §6 sign-off. Full breakdown: `SUBSCRIPTION-BILLING-PLAN.md` §0.5. |
| A4 Report Export (CSV/PDF) | Pro-tier feature (§2 of billing plan) | 🔴 **0% — not started.** No export endpoint on any analytics/campaign controller; `brand-analytics.tsx` renders on-screen only. |
| B5 Campaign Templates | Pro-tier feature (§2 of billing plan) | 🔴 **0% — not started.** No `template` field on `Campaign`, none in `brand-new-campaign.tsx`. |

Don't duplicate these — they're scoped and task-tracked (Tasks 10–20), just not yet built beyond the A3 skeleton. A4 and B5 are Pro-gated features that only get built alongside the gating layer (Task 15/18).

---

## Shipped this pass (2026-07-14) — verified build/test/typecheck green

### A6. ✅ Notification Preferences — fake save fixed, real persistence live (email channel only)

**What shipped:**
- Backend: `GET /notifications/preferences` + `POST /notifications/preferences` added to `NotificationController.java` (bidirectional — the old `/unsubscribe` endpoint was one-way-only). No migration needed; `email_preferences.unsubscribed` (V18) already existed, just wasn't exposed.
- Frontend: `notifications.getPreferences()` / `setPreference()` added to `src/lib/api.ts`. `brand-settings.tsx`'s `alert('Settings saved successfully!')` fake save is **gone** — real load-on-mount + real save with success/error state. `creator-settings.tsx` had **no save handler at all** for notifications — one now exists.
- Toggles with no backend model (`pushNotifications`, `weeklyDigest`, `deadlines`, `marketing`, `sms`) are now **visibly disabled with "coming soon"** copy instead of silently pretending to save.

**Verified:** `mvn -o compile` BUILD SUCCESS · `npx tsc --noEmit` 0 errors (independently re-run by Priya after both parallel agents landed, not just self-reported).

**Still open (separate ticket, needs a product decision):** persisting push/SMS/weekly-digest for real requires new schema + a taxonomy decision on what each channel means. Not started.

### B4. 🟢 Lifecycle Email — one real transactional hook resurrected (not the full digest)

**What shipped:** the `CampaignCreatedEvent → creator.campaign_match` email existed in code but was **dead** — recipient hardcoded to `null`, and the event was never published anywhere. Now: `CampaignService.create()` publishes the event (categories/platforms inferred via the existing `CreatorDiscoveryService.inferCategories()`, reused not reinvented); `NotificationListener` resolves real matched-creator emails via `CreatorProfileSpecifications` and sends through the existing `NotificationService`/unsubscribe-respecting pipeline. Capped at 200 matches per campaign.

**Verified:** `mvn -o compile` BUILD SUCCESS · targeted tests `NotificationServiceTest` + `CampaignServiceTest` → **16/16 pass** (both needed constructor-signature updates for the new `ApplicationEventPublisher` dependency, done as part of this change).

**Known scope cut (flagged, not hidden):** the publish call is gated on `status == ACTIVE && !isPrivate` at creation time. Most campaigns in this codebase are created as `DRAFT` and flipped to `ACTIVE` later via `CampaignService.update()` — that activation path was **deliberately not touched** (out of scope: it's a different, fee-charging transactional method). Net effect: this fires reliably only for campaigns created directly as `ACTIVE`. Wiring the `update()` activation path is a small, well-scoped follow-up.

**NOT done (needs a CEO/product decision):** the full weekly "campaigns that match you" digest — new scheduled job, marketing opt-in (distinct from transactional consent), and a build-vs-buy ESP call. Compliance surface (CAN-SPAM/GDPR) — do not build without sign-off.

### B7. ✅ Activation Empty States — audit complete + one quick win shipped

**Audit finding (the actual P6 deliverable):** empty states are **mostly good, unevenly applied**, not the blank slate the tracker worried about:
| Surface | Verdict |
|---|---|
| Brand campaign list (`campaigns-list.tsx:420`) | ✅ Strong — teaches + converts (reference pattern) |
| Brand deal room (`deal-room-dashboard.tsx:246`) | 🔴 Was blank — **fixed this pass** |
| Creator deal inbox (`creator-deals.tsx:599`) | 🟡 Teaches, no CTA to profile editor |
| Creator campaign discovery (`creator-campaigns.tsx:336`) | ✅ Good |
| Creator portfolio (editor/public) | 🟡 No activation nudge for sparse profiles |

Tracker's path reference was also wrong: onboarding wizard is at `src/components/brand/onboarding/onboarding-steps.tsx`, not `src/pages/onboarding-steps.tsx`. Line count (1,564) was correct.

**What shipped:** brand deal room now distinguishes "zero deals ever" (teaching copy + "Discover Creators" CTA → `/brand/discover`) from "filters matched nothing" (clear-filters affordance), mirroring the campaigns-list pattern.

**Verified:** `npx tsc --noEmit` clean.

**Remaining from the audit (not built, small follow-ups):** creator-deals "all" filter CTA to profile editor; portfolio activation nudge for sparse profiles.

---

## Genuinely still open — blocked on a decision, not re-scoped

### A7. 🟡 Content Usage Rights in Contracts — worse than "unstructured": data is silently dropped

**Correction to this tracker's original framing:** it's not that usage rights are stored as a raw string blob. Verified by reading `DealService.createProposal` — the `usageRights` string submitted by the frontend (`DealDtos.CreateDealRequest.usageRights`) is **accepted by the API and never read or persisted at all** (`grep .usageRights( across influora-api` = zero matches). It's discarded at the boundary. Separately, `ContractService.java:160` writes a SHA-256 tamper hash into `termsJson`, not the actual terms — so even the "free-form JSON" fallback the original tracker pointed to contains no usage-rights data today, for any contract.

The frontend `UsageRights` interface (`types.ts:390`) exists but is dead code relative to the real submit path; the rest of the UI has 3+ incompatible ad-hoc shapes (`usageRightsDuration`+`usageRightsAddOns`, `'3_MONTHS'` enum strings, free text) that would need reconciling before any structured build.

**Needs:** a product/legal decision on the canonical usage-rights shape before building (structuring now would need to pick one of the 3+ existing FE shapes to standardize on). Once decided: new `V55` migration or `ContractUsageRights` row, `DealService`/`ContractService` actually persisting the field, FE consolidation onto one shape.
**Owner:** Vikram (backend), Ananya (FE consolidation). **Effort:** S once the shape is decided; the audit itself is done.
**Flag to Swapnil:** the silent-drop is a legal-risk bug independent of any A7 build — brands believe they're setting usage-rights terms today and the value never reaches storage.

### B1. 🟠 Referral / Invite Program — not built, blocked on reward economics

**Confirmed:** zero backend references (`ReferralCode` doesn't exist, no migration through V54). Reusable ledger patterns (`CouponCode`/`CouponRedemption`/`AffiliateEarning`) verified as near-ideal templates — same idempotency-key/UNIQUE-constraint discipline should be copied, not reinvented.

**Correction:** admin frontend already has orphan stubs expecting this — `src/admin/services/api-contracts.ts:720` calls `GET /marketing/referrals`, which 404s today (no backend exists). Reconcile or remove when built.

**Blocked on:** who gets rewarded, reward type/amount, qualifying event, fraud thresholds — all CEO/Rohan decisions. Building the ledger before that risks throwing away work. Optional pre-decision groundwork (schema + entities only, no reward logic) is available if the team wants a head start.
**Owner:** Vikram (backend) + Tejas (reward economics) + Ananya (invite UI). **Effort:** L.

### B3. 🟡 Social Proof / Case Studies — hardcoded only, blocked on CEO policy + consent gap

**Confirmed:** `landing.tsx:106-123` `TESTIMONIALS` array, zero backend (`CaseStudy`/`Testimonial` entity/migration/admin CRUD — none exist).

**Two blockers the original tracker didn't flag:**
1. `landing.tsx:324-325` documents `CEO-DECISIONS.md #4`: **no client logos until written permission exists.** A logo-wall (as the tracker suggested) is a legal/product gate, not an engineering one.
2. The A1 review data (`Review` entity, V43) is **private and collaboration-scoped with no public-consent field** — reviews can't be surfaced as public testimonials without adding an opt-in mechanism first.

Also found: a hardcoded, unverified "Trusted by 500+ Indian brands" claim (`landing.tsx:331`) and a second duplicate hardcoded testimonial in `creator-portfolio-public.tsx` — both should be inventoried regardless of B3's timeline.

**Needs:** Swapnil sign-off on logos + a consent mechanism for promoting A1 reviews to public testimonials, before building. The safe post-decision slice (table + admin CRUD + public endpoint, seeded with the existing 3 anonymized quotes, no logos, no auto-import of private reviews) is still M-sized.
**Owner:** Vikram (backend) + Ananya (component/admin) + Nisha/Ishaan (content), gated on Swapnil. **Effort:** M.

---

## Priority order (Priya's recommendation) — updated 2026-07-14

1. ~~A6 fix-the-fake-save-button~~ — ✅ **DONE this pass.**
2. ~~B4 lifecycle email~~ — 🟢 **Partial win shipped** (transactional hook resurrected); full digest still needs a marketing/compliance decision.
3. ~~B7 audit~~ — ✅ **DONE this pass**, one quick fix shipped, 2 small follow-ups remain.
4. **A7 usage-rights** — audit done, revealed a legal-risk bug (silent data drop); needs a product/legal decision on canonical shape, then it's a small build.
5. **B1 referral** — needs Rohan/Swapnil sign-off on reward economics before building the ledger.
6. **B3 case studies** — needs Swapnil sign-off on logos (`CEO-DECISIONS.md #4`) + a consent mechanism for A1 reviews before building.

None of these block the Pro-tier billing build (Tasks 10–20).

---

## Decisions needed from Swapnil (updated)

1. **A7:** the silent usage-rights data-drop is a legal-risk bug today, independent of any rebuild — worth awareness regardless of when A7 is scheduled. Separately, approve a canonical usage-rights shape before a structuring build starts.
2. **B1:** referral reward economics/budget (Rohan models, Swapnil approves spend) — unchanged from original ask.
3. **B3:** approve or reject client logos on the landing page (`CEO-DECISIONS.md #4` currently forbids without written permission) and approve a consent mechanism for promoting private A1 reviews to public testimonials.
4. **B4:** build vs. buy an ESP for the full marketing digest (Rohan will cost both once told which to model) — unchanged from original ask.
