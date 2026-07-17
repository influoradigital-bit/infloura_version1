# Creator Platform — Code-Verified Pending Audit

> **Author:** Priya Sharma (CTO)
> **Date:** 2026-07-10
> **Method:** Direct read of every file below — not a tracker summary. Every "shipped" claim was confirmed by opening the actual controller/service/page; every "missing" claim was confirmed by a negative grep/glob across `influora-api/src/main/java` and `src/pages`/`src/lib/api.ts`. Cross-referenced against `wiki/tech/creator/00_..15_*_SPEC.md`, `CREATOR_PROGRESS.md`, `TASK_INBOX.md`, `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md`, `CREATOR_TASK_ASSIGNMENTS_PRIYA.md`, and `wiki/tech/PENDING_TASKS_REPORT.md`.
> **Important caveat:** the git history stops at 2026-07-07; everything below Task #7 in `CREATOR_PROGRESS.md`/`TASK_INBOX.md` (through Task #37, 2026-07-09) is **real code on disk but not yet committed**. This audit reads disk state, which is the only source of truth that matters for "what's built."

---

## 0. Headline numbers

| Metric | Value |
|---|---|
| **Full 13-spec platform, blended (code-verified)** | **~83%** |
| Backend journey | **~90%** |
| Frontend journey | **~85%** |
| Security hardening (OWASP full pass) | **~48%** — biggest remaining chunk, not a feature gap |
| QA coverage (80% target) | **~58%** — second biggest remaining chunk |
| Real, unbuilt *feature* gaps (not hardening) | **7** (see §3) |
| Stale doc claims corrected in this pass | **9** (see §2) |

**Bottom line:** the creator platform's *functional* journey (signup → profile → OAuth → discover → apply → negotiate → contract → chat → deliver → get paid → review → dispute) is essentially end-to-end wired with real APIs, not mocks. What's left is (a) a short list of genuinely missing endpoints/pages, (b) two production-hardening gates (security cycles 2–4, QA coverage), and (c) one deliberately deferred integration (YouTube OAuth).

---

## 1. Methodology note on spec-vs-code divergence

The 13 spec files (`01`–`13`) were written as **aspirational architecture references**, not literal build contracts — this is explicit in the codebase itself (`CreatorCampaignController` comment: *"the 05 spec's entity shapes are a feature reference per TECH-STACK.md, not literal"*). Two known, **intentional** architecture deviations that are NOT gaps:

1. **Discovery search (spec 04) specs Elasticsearch** with a dedicated `CreatorSearchIndex`/`FeaturedCreator` cluster. Actual code (`CreatorController.java`) implements the same functionality **MySQL-native** (JPA Specifications + in-memory facet post-filter). This is a deliberate, documented simplification — functionally equivalent, not a missing feature.
2. **Bids (spec 06) specs a dedicated `Bid`/negotiation entity.** Actual code reuses `Collaboration` + `DealMessage` (`DealController`/`DealService`) for the entire apply → negotiate → accept flow. This is a **locked architecture decision** (Priya, CEO doc §6: "do not build a second bid/negotiation entity"). Not a gap — same function, different name.

Everywhere else, gaps below are real code-level absences, verified by file check.

---

## 2. Stale tracker claims — corrected

| # | Doc | Claim | Reality (verified) |
|---|---|---|---|
| 1 | `wiki/tech/PENDING_TASKS_REPORT.md` §3 "CREATOR — Pending" | `PlatformFeeService` "Not started, P0" | ✅ **Shipped.** `PlatformFeeService.java`, `CreatorPlatformFeeService.java`, `PlatformFeeConfig.java` all exist; deducts fee at escrow release, DB-backed config, audit-logged (Task #26). |
| 2 | Same doc | `GET /creator/platform-fee` "Not started, P1" | ✅ **Shipped.** `CreatorPlatformFeeController.java` — `GET /creator/platform-fee`, principal-scoped (Task #27). |
| 3 | Same doc | "Creator-facing coupon-read endpoint... Not started" | ✅ **Shipped.** `CreatorCouponController.java` — `GET /creator/coupons`, principal-scoped via `CreatorCouponService` (Task #28/#32). |
| 4 | Same doc | `creator-dashboard`, `creator-reviews` "Missing, P0" | ✅ **Both exist and are wired.** `src/pages/creator-dashboard.tsx` (Task #30), `src/pages/creator-reviews.tsx` (Task #29/#33 — write path live). |
| 5 | Same doc | `creator-bids`/`creator-deliverables`/`creator-contracts` "Missing, P0" | 🟡 **Correctly resolved-by-architecture per CEO ruling** (`CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §2 F2/F3/F4) — functionality lives in `creator-deals.tsx` + `creator-chat.tsx`'s deal room, not standalone pages. **Do not build** these as separate pages (see §5). |
| 6 | `CREATOR_PROGRESS.md` "❌ Not Built Yet" list (lines ~106–111) | "Instagram/YouTube OAuth integration (0%)" | ❌ **Self-contradicts the same doc's Feature Matrix** (line 41: OAuth 85%). Instagram/Facebook via `MetaOAuthController.java` (`/meta/oauth/authorize`, `/callback`, `/status`, `/disconnect`) is fully shipped. Only YouTube is genuinely 0% (see §3.1). |
| 7 | Same doc | "Brand discovery for creators (0%)" | ❌ **Stale.** `CreatorController.java` ships `/creators` (search+facets, `/search`, `/featured`, `/suggestions`, `/{username}/similar`, `/{creatorId}`, `/profile/{usernameOrId}`, `/save`, `/invite`) — real, MySQL-backed (Task #36). ~70% because QA/security/Priya gates on this specific slice are still pending, not because it's unbuilt. |
| 8 | Same doc | "Affiliate earnings tracking (0%)" | ❌ **Stale — partially built.** `AffiliateEarningsService.java`, `AffiliateSettlementBatch.java`, `AffiliateEarning.java` entity all exist on the backend; `creator-affiliate-earnings.tsx` + `AffiliateEarningsView.tsx` component exist on the frontend. The **actual** gap is narrower: no creator-facing `GET` read endpoint, so the page renders an honest "not implemented" state (see §3.3). |
| 9 | Same doc, "Existing Creator Pages" table | `creator-affiliate-earnings.tsx` "❌ Empty" | ❌ **Wrong.** It's a real page rendering `AffiliateEarningsView` with correct SETTLED-vs-Paid honesty logic — blocked on a backend endpoint, not empty/unbuilt. |

**Net effect:** correcting these drops the "backend not started" count in the old report from 3 items to **0** (all three — fee service, transparency endpoint, coupon-read — are shipped) and clears 2 of 4 "missing frontend pages" (dashboard, reviews) plus resolves 3 more as architecture decisions, not backlog.

---

## 3. Real pending gaps — code-verified, by spec file

### 3.1 · `03_CREATOR_OAUTH_CONNECT_SPEC.md` — OAuth (85% shipped)

| Spec says | Code has | Gap | Priority |
|---|---|---|---|
| Instagram + YouTube + Facebook OAuth, all read-only, PKCE + encrypted tokens | `MetaOAuthController.java` (Instagram + Facebook via Meta Graph API, AES-256-GCM token encryption, real) | **YouTube OAuth: 0%.** No `YouTubeOAuthController`/`YouTubeApiClient` anywhere in `influora-api/src/main/java` (grep, zero hits). Tracked as "deferred" in `CREATOR_PROGRESS.md` but **no written CEO/Swapnil sign-off doc exists approving the deferral** — it's an informal tracker note, not a signed decision. | **P1** — either get an explicit deferral sign-off (5-minute doc) or build it; leaving it silently unresolved is the actual risk, not the missing OAuth itself. |

### 3.2 · `04_CREATOR_DISCOVERY_SPEC.md` — Discovery (~70%)

| Spec says | Code has | Gap | Priority |
|---|---|---|---|
| Search+filter, featured, similar, suggestions, public profile | `CreatorController.java` — all 8 endpoints real (Task #36, 9/9 unit tests) | Functionally complete. Gap is **process, not code**: Kavya QA pass on this exact slice + Meera build-verify + Priya sign-off are still open per `TASK_INBOX.md` tick #31 (Docker blocking integration tests). Portfolio/reviews tabs on public profile are still illustrative placeholders, not wired to `PortfolioController`/`ReviewRepository` data. | **P1** — close the gate cycle; wire portfolio/reviews tabs on the public profile response. |

### 3.3 · `10_CREATOR_PAYMENTS_SPEC.md` — Payments (100% sprint-gated, 2 real gaps)

| Spec says | Code has | Gap | File(s) | Priority |
|---|---|---|---|---|
| Affiliate earnings visible to creator | `AffiliateEarningsService.java` (backend, computes/settles) + `AffiliateEarningsView.tsx` (frontend, ready to consume) | **No creator-facing `GET` endpoint.** No `AffiliateEarningController`/creator route exists — grep of `influora-api/src/main/java/com/influora/web` for `Affiliate` returns zero controller files. | New: `influora-api/src/main/java/com/influora/web/CreatorAffiliateEarningController.java` (or extend an existing controller) | **P1** — service layer already does the work; this is a thin read endpoint away from shipping. |
| Per-deal payout breakdown (which milestone paid what, net of fee) | `WalletController`/`WalletService` — global balance/transactions only | **No per-deal payout list endpoint.** Confirmed 0% in `CREATOR_PROGRESS.md` "❌ Not Built Yet" and independently verified — no `payout` route scoped to a `collaborationId` in `WalletController.java`. | New endpoint on `WalletController.java` or `EscrowController.java` | **P2** |

### 3.4 · `11_CREATOR_ANALYTICS_SPEC.md` — Analytics (45%)

| Spec says | Code has | Gap | Priority |
|---|---|---|---|
| Growth tracking, scores, demographics | `GET /creator/analytics/me/*` (Task #35) + `creator-analytics.tsx` page (Task #35/A5), reusing real `CreatorScore`/`AudienceDemographics`/`MediaMetric` pipeline (`BrandSafetyScoreService`, `AudienceDemographicsJob`) | Shipped and gated (Kabir 0 Critical/High). | — |
| "AI coach" — personalized growth recommendations | Nothing. Zero hits for `coach`/`recommendation` in analytics service/controller. | **Growth AI coach endpoints: confirmed 0%**, matches `CREATOR_PROGRESS.md`'s own honest flag. No spec section literally requires this beyond a passing mention — treat as a genuinely new build, not a regression. | **P2** — nice-to-have, not launch-blocking. |
| Earnings/campaign-performance analytics tab | Not built — `creator-analytics.tsx` covers growth/scores/demographics only | Deferred to "analytics wave 2" per `CREATOR_PROGRESS.md` tick #30. | **P2** |

### 3.5 · `14_CREATOR_REVIEWS_SPEC.md` (new spec, written this sprint) — Reviews

| Spec says (CEO §1.2 ruling) | Code has | Gap | Priority |
|---|---|---|---|
| Creator can rate brand, brand can rate creator, post-`COMPLETED` only, flaggable | `Review.java` entity, `ReviewService.java`, `CreatorReviewController.java` (`POST /creator/reviews`, `POST /creator/reviews/{id}/flag`), `BrandReviewController.java` mirror, `creator-reviews.tsx`/`brand-reviews.tsx` pages (Task #29/#33) | Write path fully shipped. **No `GET` endpoint to view reviews received about you** — `CreatorReviewController.java` has exactly 2 methods (`create`, `flag`), no `list`/`get`. `creator-reviews.tsx`'s "received" tab is explicitly noted as deferred in the page itself. | **P1** — one read endpoint + one tab wire-up, the write-side hard part is already done. |
| Rate limiting on review/flag creation | Not present | **M-T29-1 / M-T29-2** (Kabir's own findings, still open) — no throttle on review or flag submission. | **P1** (security hardening, see §4) |

### 3.6 · `15_CREATOR_DISPUTES_SPEC.md` (new spec, written this sprint) — Disputes

| Spec says (CEO §1.3 interim policy) | Code has | Gap | Priority |
|---|---|---|---|
| Either party opens a dispute; admin arbitrates; unreleased escrow freezes on open | `Dispute.java`, `DisputeService.java` (`DisputeOpenerType.CREATOR`/`BRAND` both handled), open via `POST /deals/{dealId}/disputes` on `DealController`, resolve via `AdminDisputeController`, list via `BrandDisputeController` (`GET /brand/disputes`) — H-T34-1 freeze-race hotfixed and closed | Backend is real and gated (Task #34, Meera 19/19, Priya SHIPPED/CONDITIONAL). **No creator-facing dispute page exists** — no `creator-disputes.tsx` in `src/pages` (glob confirms zero matches), so a creator can technically hit `POST /deals/{dealId}/disputes` but has no UI to do it from, and no UI to see the status of a dispute they opened. Brand has a page (`brand-disputes.tsx`); creator does not. | **P0** — this is a legal-exposure asymmetry: the party most likely to need dispute access (creator waiting on a stuck milestone) has no way to open one without direct API access. |
| Rate limiting on dispute open | Not present | Same `M-K6-1` cross-cutting gap as reviews. | **P1** |

### 3.7 · `12_CREATOR_SECURITY_SPEC.md` — Security (~48%)

Not a feature gap — a **hardening completeness gap**, confirmed via `wiki/errors/creator-owasp-K6-kickoff.md` (Kabir, kickoff slice 1 of N, 0 Critical/0 High/5 Medium/12 Low):

| Area | Status |
|---|---|
| Rate limiting (**M-K6-1**, cross-cutting) | `AuthRateLimitFilter` covers auth, webhooks, deliverable writes, contract sign — **does not** cover reviews, disputes, discovery search/invite, campaign apply, or HTTP-layer withdrawal. Same gap independently found in 5 separate slice audits (T7, T19, T29, T34, T25-partial). |
| OAuth PKCE/CSRF/refresh matrix, OTP enumeration, session hijack | **Cycle 2 — not started** |
| Malware scan on uploads, SSRF URL mapping | **Cycle 3 — not started** |
| Dependency CVE scan | **Not started** — no CI dependency audit exists |
| PII-at-rest verification (email/phone/bank) | **Not verified this cycle** |
| Common-password denylist, OTP hash-at-rest audit | **Not started** |

**Priority:** P0 for M-K6-1 (rate limiting, it's the single most-repeated finding across the whole audit trail); P1 for cycles 2–4 before any GA claim.

### 3.8 · `13_CREATOR_QA_SPEC.md` — QA (~58%)

| Gap | Detail |
|---|---|
| 80% coverage target | Not met — `Kv3` E2E slice 1 covered 12 sections, ~58% E2E |
| No Playwright / browser E2E | Confirmed absent — QA has run unit + manual hostile-path tests only |
| Auth unit tests thin | Flagged by Kavya directly in her own gate notes |

**Priority:** P1 — this is the standard pre-GA gate, not a feature blocker.

### 3.9 · Frontend pages still genuinely partial

Verified by reading each file, not by trusting the tracker table:

| Page | Status |
|---|---|
| `creator-settings.tsx` | 🟡 Basic structure only — confirmed thin (not full account/notification/security settings per spec) |
| `creator-portfolio-public.tsx` | 🟡 Display-only, no edit/share actions beyond viewing |
| `creator-inbox.tsx` | 🟡 Partial — needs campaign-card rollup |
| `creator-active.tsx` | 🟡 Partial — needs deliverable-tracking view beyond deal room |

**Priority:** P2 each — none block the core money/contract/deliverable journey, which is the part that's actually done.

---

## 4. Already shipped — do not re-flag in future audits

Confirmed by direct file read (controller + service + at least one test file where applicable):

**Backend:** `AuthController`/`AuthService` (creator OTP signup/login), `MeCreatorProfileController`, `PortfolioController`, `MetaOAuthController` (Instagram/Facebook), `CreatorController` (discovery, 8 endpoints), `CreatorCampaignController` (browse/apply), `DealController`/`DealService` (negotiation — accept/reject/counter/messages), `ContractController` (creator-scoped e-sign via `findByIdAndCreatorId`), `CreatorDeliverableController`/`BrandDeliverableController` (upload/list/submit/approve/revise/metrics), `WalletController`/`WalletService` (creator balance, withdraw ₹500 min/₹100,000 max, transactions), `PlatformFeeService`/`CreatorPlatformFeeService`/`PlatformFeeConfig` (fee deduction + transparency), `CreatorCouponController` (coupon read), `ReviewService`/`CreatorReviewController`/`BrandReviewController` (review write path), `DisputeService`/`AdminDisputeController`/`BrandDisputeController` (dispute open/list/resolve), `AnalyticsController` + creator-self `/creator/analytics/me/*` (Task #35), `BrandSafetyScoreService`, `AudienceDemographicsJob`, `EscrowService`, `WalletLedgerService` (double-entry, never bypassed).

**Frontend:** `creator-login.tsx`, `creator-register.tsx`, `creator-onboarding.tsx`, `creator-profile.tsx`, `creator-portfolio-editor.tsx`, `creator-campaigns.tsx`, `creator-campaign-detail.tsx`, `creator-deals.tsx`, `creator-chat.tsx` (deal room + e-sign + deliverables, all API-wired, zero mock in live mode), `creator-wallet.tsx` (balance/withdraw/history), `creator-coupons.tsx`, `creator-dashboard.tsx`, `creator-reviews.tsx` (write path), `creator-analytics.tsx`, `creator-meta-callback.tsx`.

All of the above passed a Vikram/Ananya → Kavya QA → Kabir security → Meera build → Priya sign-off gate chain per the task inbox — not just "code exists," but code that's been adversarially reviewed.

---

## 5. Explicit "do not build" — deferred/out of scope per CEO ruling

| Item | Reason | Source |
|---|---|---|
| Standalone `creator-bids.tsx`, `creator-deliverables.tsx`, `creator-contracts.tsx` pages | Resolved-by-architecture — functionality lives in `creator-deals.tsx`/`creator-chat.tsx` deal room by deliberate design; a separate page would fragment the UX | `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §2/§6 |
| Second `Bid`/`CampaignApplication`/`Conversation` entity | Locked architecture: `Collaboration` + `DealMessage` only | Same doc, §6 |
| Elasticsearch cluster for discovery search | MySQL-native chosen instead — functionally equivalent, no product-visible gap | `CreatorCampaignController`/`CreatorController` code comments |
| Admin dispute-resolution console polish (beyond the v1 resolve endpoint that already exists) | Phase 2, admin-surface scope, not creator | `PENDING_TASKS_REPORT.md` §2 |
| Full legal dispute policy (refund %, SLA, appeals) | Interim policy shipped (§1.3); full policy is an explicit follow-up, not a v1 blocker | `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.3 |
| Virus/malware scan on deliverable uploads | No infra exists; explicitly accepted as risk rather than silently skipped | `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` V-7 DoD |
| Hardcoding platform fee % anywhere | Must stay DB-backed (`PlatformFeeConfig`) — confirmed not hardcoded in either backend or frontend | CEO §6 cross-cutting rule, verified in `CreatorPlatformFeeController`/`PlatformFeeService` |
| TikTok OAuth | Spec 03 marks it "Future" explicitly — not current scope | `03_CREATOR_OAUTH_CONNECT_SPEC.md` §3.1 |

---

## 6. Critical path to 100%

Ordered by dependency, not just priority — this is what actually unblocks the most downstream work per item of effort:

1. **P0 — `creator-disputes.tsx` page** (§3.6). Backend is done; this is pure frontend wiring against an existing, gated API (`POST /deals/{dealId}/disputes` + a status-read call). Closes the single sharpest legal-exposure gap in this audit.
2. **P0 — Rate limiting sweep (M-K6-1)** (§3.7). One consistent fix (extend `AuthRateLimitFilter` coverage) closes 5 independently-filed findings at once: reviews, disputes, discovery search/invite, campaign apply, withdrawal.
3. **P1 — `GET /creator/reviews/received` (or similar) + wire `creator-reviews.tsx`'s received tab** (§3.5). Small, closes the last real gap in a spec that's otherwise done.
4. **P1 — Creator-facing affiliate earnings read endpoint** (§3.3). Service layer exists; this is the thinnest possible slice to close a whole spec section.
5. **P1 — YouTube OAuth decision** (§3.1). Either get Swapnil's written deferral sign-off (fast) or scope the build (slower) — the risk today is the *silence*, not the missing integration.
6. **P1 — Close the Discovery slice's QA/security/Priya gate cycle** (§3.2). Code is done; this is process, not build time.
7. **P1 — QA coverage push to 80% + first Playwright suite** (§3.8).
8. **P1/P2 — Security cycles 2–4** (§3.7): OAuth PKCE/CSRF matrix, OTP/session probes, malware scan, dependency CVE scan, PII-at-rest verification.
9. **P2 — Per-deal payout list, growth AI coach, earnings/campaign analytics tab, `creator-settings.tsx`/`creator-inbox.tsx`/`creator-active.tsx` polish** (§3.3, §3.4, §3.9).

Items 1–4 are estimated at low effort (endpoints/pages against already-built services); items 5–8 are the real remaining investment, concentrated in security/QA hardening rather than new features.

---

## 7. Estimated remaining % to 100% full-platform

| Track | Current | Remaining work |
|---|---|---|
| Backend features | ~90% | Affiliate read endpoint, per-deal payouts, reviews-received GET, YouTube OAuth (if not deferred) |
| Frontend features | ~85% | Dispute page, reviews-received tab, settings/inbox/active polish |
| Security hardening | ~48% | 3 more OWASP cycles + cross-cutting rate limiting |
| QA coverage | ~58% | Coverage push to 80%, Playwright suite |
| **Blended (feature-weighted)** | **~83%** | See §6 critical path |

The platform is closer to done on *features* (~87–88% if you weight backend+frontend only) than the blended number suggests — security and QA hardening are dragging the composite down, which is the correct place for them to drag it down before a GA claim, not a sign the product itself is unfinished.
