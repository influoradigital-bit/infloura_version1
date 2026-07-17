# Creator Module — Pending Work: User Report vs. Priya's Audit — Reconciliation

> **Author:** Priya Sharma (CTO)
> **Date:** 2026-07-10
> **Inputs reconciled:**
> 1. User's pasted "Creator Module — Pending Work Report"
> 2. `wiki/tech/creator/CREATOR_PENDING_CODE_AUDIT_PRIYA.md` (my prior audit, 2026-07-10)
> 3. `wiki/tech/creator/CREATOR_PROGRESS.md` (tracker, through Tick #31 — the most current entry in the doc)
> 4. Direct re-read of `influora-api/src/main/java` (`DisputeService.java`, `PlatformFeeService.java`, `BrandEmailOtpService.java`, `AuthController.java`, `WalletController`/`useWalletTopUp.ts`, `TextSanitizer.java` + call sites) and `src/pages/creator-*.tsx`
>
> **Verdict up front:** the user's report is **substantively accurate** — better than mine on one real gap (OTP delivery) and correctly graded on most of the messy items (Bids 35%, Dispute stub, platform fee global-only, top-up mock). My own audit is more accurate on **one high-severity item the user's report omits entirely: there is no `creator-disputes.tsx` page**, and on the precise 5-way breakdown of the rate-limiting findings. Net: two good-faith audits, ~90% overlap, a handful of correctable deltas below.

---

## 1. MATCH — user report and code/audit agree

| # | Item | User report | Code/audit evidence |
|---|---|---|---|
| 1 | Analytics wave 2 gap | 45%, metrics/scores/demographics only | `AnalyticsController` + `creator-analytics.tsx` cover growth/scores/demographics via real `CreatorScore`/`AudienceDemographics`/`MediaMetric`. Zero hits for `coach`/`recommendation` in analytics service (`§3.4` of my audit). Confirmed no "AI coach" or earnings/campaign-performance tab exists. |
| 2 | YouTube OAuth 0%, deferred | 0%, deferred | Zero `YouTubeOAuthController`/`YouTubeApiClient` in `influora-api/src/main/java` (grep, zero hits). `03_CREATOR_OAUTH_CONNECT_SPEC.md` — only Instagram/Facebook shipped via `MetaOAuthController.java`. |
| 3 | Growth AI-coach endpoints 0% | 0% | Confirmed — zero hits for `coach` anywhere in `influora-api/src/main/java` analytics package. |
| 4 | Per-deal payout list 0%, P2 | 0%, P2 | No `payout` route scoped to `collaborationId` in `WalletController.java`. `CREATOR_PROGRESS.md` line 111 independently confirms "Per-deal payout list endpoint (0%)". |
| 5 | Discovery FE ~70%, tabs mock | ~70%, tabs mock | `CREATOR_PROGRESS.md` line 42: Discovery **~70%**, "portfolio/reviews tabs illustrative." My audit §3.2 confirms the 8 backend endpoints are real but portfolio/reviews tabs on the public profile are still placeholders, and the QA/security/Priya gate cycle is still open (Docker-blocked per Tick #31). |
| 6 | Chat / "Meera AI partial" 75% | 75%, AI partial | `CREATOR_PROGRESS.md` Feature Matrix line 46: Chat **75%**, "AI partial; M-2 metadata polish." Confirmed independently: zero references to "Meera" inside `src/pages/creator-chat.tsx` — the deal-room messaging is real and API-wired, but the AI-assistant layer of chat is not built for the creator side. `CREATOR_PROGRESS.md` line 196 Definition-of-Done still shows "[ ] Creator can chat with brands + AI (Meera)" unchecked. |
| 7 | Bids 35% "misleading" — deal-room already does it | Flagged as misleading | Exact match to my audit §1 architecture-deviation note and §5 do-not-build list: `Collaboration` + `DealMessage` (`DealController`/`DealService`) implement the full apply→negotiate→accept flow. This is a **locked CEO architecture decision** (`CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §6: "do not build a second bid/negotiation entity"), not a real 35%-built gap. |
| 8 | Platform fee — global only, no tier/per-creator override | Confirmed gap | `PlatformFeeConfig` is a DB-backed **singleton** (`PlatformFeeConfig.SINGLETON_ID`, `PlatformFeeService.java` line 105) — one global fee row, no tier or per-creator override column/table. User is correct; my audit under-called this (I marked `PlatformFeeService` fully "✅ Shipped" in §2 item 1 without flagging the global-only limitation). |
| 9 | Dispute money movement — stub only | Confirmed gap | `DisputeService.java` lines 108–111 (javadoc) + `resolveDispute()` body (lines 112–146): **"Admin resolution stub — transitions dispute status only. Does not refund, release, or claw back funds (CEO §1.3: money movement is a follow-up, not v1)."** Exact match. This is in my do-not-build list §5 ("Full legal dispute policy... interim policy shipped") but I should have stated the money-movement-is-a-stub fact as plainly as the user did. |
| 10 | Wallet withdrawal real; top-up mock | Confirmed | Withdrawal: real, gated, tested (`WalletController`, Task #18/#18b, Kabir M-18 closure PASS). Top-up: `POST /wallet/topup` creates a **real** server-side order (`WalletTopUpService`), but the client-side Razorpay Checkout invocation is explicitly commented out in `src/pages/brand-wallet.tsx` line 316 (`// In production: window.Razorpay({...}).open()`) — no `VITE_RAZORPAY_KEY_ID` / checkout.js loader exists anywhere in the repo (confirmed by comment at line 303–306 of the same file). So: **the order-creation API is real, the actual payment collection is mocked.** This nuance ("stub order, no real checkout") is more precise than either side's one-line claim but both land on the same conclusion. |
| 11 | Security ~48%, cycles 2–4 not started | Confirmed | `CREATOR_PROGRESS.md` line 50 + my audit §3.7: Cycle 1 (K6 kickoff) done, 0C/0H/5M/12L. Cycles 2 (OAuth/OTP/session), 3 (malware/SSRF), 4 (dependency CVE/PII-at-rest) **not started**, verified via `wiki/errors/creator-owasp-K6-kickoff.md`. |
| 12 | QA ~58%, no creator FE tests, no Playwright, thin auth units | Confirmed | `CREATOR_PROGRESS.md` line 51 + my audit §3.8: "Kv3 E2E slice 1 covered 12 sections, ~58% E2E"; "No Playwright / browser E2E confirmed absent"; "Auth unit tests thin — flagged by Kavya directly." |
| 13 | M-19-3/4 upload streaming/presigned URLs still open | Confirmed prod blocker | `CREATOR_PROGRESS.md` line 47 + sprint verdict line 31: "Prod-only debt (non-blocking): M-19-3/4 upload streaming/presigned URLs." Still open, not closed by any later tick. |
| 14 | M-24-1 proof-key ownership binding still open | Confirmed prod blocker | Same line 47/31 — "M-24-1 proof-key ownership binding" listed as unresolved condition before prod. |
| 15 | Recommended order: Security → Upload → FE tests → features | Reasonable sequencing | Consistent with my audit's critical path §6 items 2 (rate limiting P0), 7/8 (QA + security cycles), with upload hardening (M-19-3/4) sitting alongside security as the other prod gate. No disagreement on sequencing logic. |

---

## 2. MISMATCH — where user report and code/my audit differ

| # | Item | User report says | Reality (code-verified) | Who's right | Corrected % |
|---|---|---|---|---|---|
| 1 | **OTP MSG91** | "~80%" | **User is more right than my own audit, which didn't flag this at all.** `BrandEmailOtpService.java` (used by both `/auth/brand/send-email-otp` **and** `/auth/creator/send-email-otp` — `AuthController.java` lines 81–92) implements OTP generation, hashing (`JwtService.hashToken`), 5-min TTL, 3-attempt lockout, and per-email send rate-limiting **for real**. But the actual send-via-MSG91 call is a literal `// TODO: MSG91 Email API (docs/MSG91-EMAIL-OTP.md)` (line 69) — in dev, the OTP is only logged (`log.info("[dev] Brand email OTP for {}: {}", ...)`), never emailed. Note: a *separate*, unrelated `Msg91EmailClient`/`EmailWorker` **is** fully wired — but only for transactional notification emails (contract-signed, proposal-received), not OTP delivery. | **User's ~80% is a fair estimate** — mechanics (90%+) minus the literal not-yet-wired MSG91 send call is a real, specific gap my audit missed entirely. | **~75–80%** confirmed — mechanics done, actual OTP delivery integration is a `TODO`, not shipped. |
| 2 | **Affiliate earnings** | "~0% wired, stub pages" | **My audit is more accurate — this undersells what's built.** `AffiliateEarningsService.java`, `AffiliateSettlementBatch.java`, `AffiliateEarning.java` entity all exist and compute/settle on the backend. `AffiliateEarningsView.tsx` is a real component with correct SETTLED-vs-Paid honesty logic (not a stub render) — it's `creator-affiliate-earnings.tsx` line 6–10's own comment: *"Wave D task D4 (`AffiliateEarningsService`/`AffiliateSettlementJob`) is done on the backend, but there is still no creator-facing read endpoint."* The gap is narrow and specific: one missing `GET` controller, not an unbuilt feature. | **My audit is right**; "~0% wired" is stale language carried over from `CREATOR_PROGRESS.md`'s "❌ Not Built Yet" list (line 108), which my audit already flagged as stale in §2 item 8. | **~60–65%** (service + entity + settlement batch + FE component all real; only the creator-facing `GET` endpoint + its wiring are missing) — not ~0%. |
| 3 | **Full-platform blended %** | ~82% | `CREATOR_PROGRESS.md`'s own most recent entry — **Tick #31 CLOSED** (line 995–1003, timestamped 2026-07-09 ~22:15 IST, the newest tick in the file) — states **"Full-platform blended: ~82%."** My audit's headline number (§0) says **~83%**. | **User's 82% matches the tracker's most current tick more precisely than my own audit's 83%.** My audit's 83% was computed independently and is 1pt optimistic relative to the doc's own latest checkpoint. | **~82%** — see §6 below, adopting the tracker's most recent tick as the anchor. |
| 4 | **Security M-K6-1..5** | Grouped together generically as "rate limits" | **Accurate but under-detailed on both sides.** All five are real, distinct findings in `wiki/errors/creator-owasp-K6-kickoff.md`: **M-K6-1** (systemic write-path gaps: reviews/disputes/discovery-invite/campaign-apply), **M-K6-2** (in-memory-only limiter, doesn't survive horizontal scale), **M-K6-3** (review-flag moderation-queue DoS), **M-K6-4** (withdrawal missing HTTP-layer 5/hr throttle — business rule 3/day exists but that's not the same control), **M-K6-5** (discovery search unthrottled, confirmed still open independently in `wiki/errors/creator-discovery-T36-kavya-qa.md`). My audit's narrative in §3.7/§6 only named M-K6-1 explicitly in prose (the other 4 are in the source doc it cites but weren't surfaced in my own summary table). | **Tie — user's report is right that there are 5 distinct findings; my audit is right that M-K6-1 is the highest-priority one to fix first** (fixing the `AuthRateLimitFilter` pattern for M-K6-1 largely also closes M-K6-3/M-K6-5 mechanically). | No % correction — this is a completeness note, not a wrong number. |

---

## 3. MISSING FROM USER REPORT — gaps my audit found that the user's report omits

| # | Item | Why it matters | Evidence |
|---|---|---|---|
| 1 | **No `creator-disputes.tsx` page exists** | **This is the single highest-severity omission.** Backend dispute-open/list/resolve APIs are real and gated, but there is genuinely no UI for a creator to open a dispute or see its status. A creator with a stuck milestone has no way to exercise this right without direct API access — a legal-exposure asymmetry (brand has `brand-disputes.tsx`; creator does not). | `Glob` of `src/pages/creator-*.tsx` returns 20 files — zero contain "dispute". My audit §3.6/§6 item 1 flagged this **P0**. User's report Section 2 discusses disputes only as "money movement stub" and never mentions the missing creator-facing page. |
| 2 | **No `GET` endpoint for "reviews I've received"** | `CreatorReviewController.java` has exactly 2 methods (`create`, `flag`) — no `list`/`get`. `creator-reviews.tsx`'s "received" tab is explicitly deferred in the page itself. Write path (rating a brand) is 100% done; read path (seeing what brands said about you) is 0%. This was in my audit's §3.5/§6 item 3 as **P1**, and is not mentioned anywhere in the user's report. | `CreatorReviewController.java` (2 methods only); `14_CREATOR_REVIEWS_SPEC.md`. |
| 3 | **No written CEO/Swapnil sign-off doc for the YouTube OAuth deferral** | The user's report correctly flags YouTube OAuth as 0%/deferred, but doesn't note that the deferral itself is informal — a tracker note, not a signed decision. This is a process risk (silent scope-drop), independent of the missing code. | My audit §3.1/§6 item 5. No `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` section approves this specific deferral (checked against the doc's §1–§6 rulings, none cover YouTube). |
| 4 | **The explicit "do-not-build" list** | 7 items (standalone bids/deliverables/contracts pages, second Bid entity, Elasticsearch cluster, admin dispute console polish, full legal dispute policy, malware scanning infra, hardcoded fee %, TikTok OAuth) are locked-scope decisions from `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md`. Without this list being shared, there's a real risk some of these get "rediscovered" as gaps and re-built or re-flagged in a future audit. | My audit §5. Not referenced anywhere in the user's report. |
| 5 | **Discovery's QA/security/Priya gate is process-blocked, not code-blocked** | The user's report says Discovery FE is "~70%, tabs mock" — true, but omits that the 8 discovery backend endpoints are fully code-complete (9/9 unit tests) and the remaining 30% is mostly a **Docker-blocked integration test gate** (`Tick #31`: "Meera 857/858 unit green, Docker blocks integration"), not unwritten code. This changes the remediation plan — it's an infra/environment fix, not a build task. | `CREATOR_PROGRESS.md` line 1000; my audit §3.2. |

---

## 4. MISSING FROM MY AUDIT — items the user found that I missed

| # | Item | User's finding | Verified? |
|---|---|---|---|
| 1 | **OTP MSG91 delivery gap (~80%)** | The actual `// TODO: MSG91 Email API` stub inside `BrandEmailOtpService.sendOtp()` — used for *both* brand and creator OTP | **Confirmed real, genuine miss on my part.** This is a legitimate P1: signup/login mechanics work end-to-end in dev (OTP is logged), but no real user would ever receive their OTP by email in a deployed environment today. |
| 2 | **Platform fee has no tier/per-creator override** | Explicitly called out as a doc/code mismatch | **Confirmed.** `PlatformFeeConfig.SINGLETON_ID` — single global row. My audit's §2 item 1 credited `PlatformFeeService` as "✅ Shipped" without noting this structural limitation. Worth a one-line addendum to that entry, not a full re-grade (it's a limitation, not a bug — the CEO's own do-not-build list item "hardcoding platform fee % anywhere" was about *not hardcoding*, and a DB-backed global singleton satisfies that; per-tier override was never spec'd as required for v1). |
| 3 | **Dispute money-movement is a stub — stated as a standalone doc/code mismatch line item** | User calls this out as its own top-level bullet | I had this fact correctly documented (§5 do-not-build entry, DisputeService javadoc citation available), but buried it inside the "do not build" framing rather than surfacing it as a plain gap in the main gap table (§3.6). Same underlying fact, different presentation — worth promoting to a first-class line item in future audits since a reader skimming only §3 could miss it. |
| 4 | **Razorpay top-up "mock" framed as its own line item** | User calls out "Wallet withdrawal real; top-up mock" as a standalone mismatch | My audit did not mention wallet top-up at all (it's outside the creator scope — top-up is a **brand**-side wallet feature, not creator). Technically out of scope for a "Creator Module" audit, but the user's report includes it, so flagging for completeness: confirmed real gap (order creation is real, Razorpay Checkout SDK invocation is commented-out placeholder), just scoped to brand wallet (`brand-wallet.tsx`), not creator wallet. |

---

## 5. FINAL AGREED PENDING LIST — single reconciled table

| Priority | Item | Effort | Spec | Notes |
|---|---|---|---|---|
| **P0** | Build `creator-disputes.tsx` (open + status view) | Low — pure FE wiring against existing gated API | `15_CREATOR_DISPUTES_SPEC.md` | Backend done. Closes the sharpest legal-exposure gap. **Not in user's report — added here.** |
| **P0** | Rate-limiting sweep — M-K6-1 (+ mechanically closes M-K6-3, M-K6-5) | Low-medium — extend `AuthRateLimitFilter` pattern from Task #25 | `12_CREATOR_SECURITY_SPEC.md` | Covers reviews, disputes, discovery search/invite, campaign apply. |
| **P1** | OTP MSG91 real send integration (replace `TODO` in `BrandEmailOtpService`) | Low — client wiring only, template config already scaffolded in `application.yml` | `01_CREATOR_AUTH_SPEC.md` | **User's finding — Priya's audit missed this.** OTP mechanics (hash/TTL/lockout/rate-limit) are done; only the actual send call is stubbed. |
| **P1** | `GET /creator/reviews/received` + wire `creator-reviews.tsx` received tab | Low | `14_CREATOR_REVIEWS_SPEC.md` | Write path done; read path 0%. |
| **P1** | Creator-facing affiliate earnings `GET` endpoint | Low — service layer already computes everything | `10_CREATOR_PAYMENTS_SPEC.md` | Corrected from user's "~0%" — actually ~60–65% done; this is the thin remaining slice. |
| **P1** | M-K6-4 — withdrawal HTTP-layer rate limit (5/hr) | Low | `12_CREATOR_SECURITY_SPEC.md` | Business rule (3/day) exists in `WalletService`; HTTP-layer control does not. |
| **P1** | M-K6-2 — move rate limiter off in-memory (Redis/bucket4j or edge WAF) | Medium | `12_CREATOR_SECURITY_SPEC.md` | Needed before any horizontally-scaled GA claim. |
| **P1** | YouTube OAuth — get written Swapnil/CEO deferral sign-off, or scope the build | Low (sign-off) / High (build) | `03_CREATOR_OAUTH_CONNECT_SPEC.md` | Risk today is the *silent* deferral, not the missing integration itself. |
| **P1** | Close Discovery's QA/security/Priya gate cycle (Docker-blocked integration tests) | Low — infra fix, not code | `04_CREATOR_DISCOVERY_SPEC.md` | Code is done (9/9 unit tests); unblock Docker → run integration suite → Kavya/Meera/Priya sign-off. |
| **P1** | Wire portfolio/reviews tabs on public creator profile to real `PortfolioController`/`ReviewRepository` data | Medium | `04_CREATOR_DISCOVERY_SPEC.md` | Currently illustrative placeholders. |
| **P1** | QA coverage push to 80% + first Playwright suite | High | `13_CREATOR_QA_SPEC.md` | ~58% today; no browser E2E exists. |
| **P1** | Security cycles 2–4 (OAuth/OTP/session probes, malware scan, SSRF, dependency CVE, PII-at-rest) | High | `12_CREATOR_SECURITY_SPEC.md` | Cycle 1 (K6 kickoff) done — 0C/0H/5M/12L. |
| **P1** | Upload hardening: M-19-3 (in-memory buffering DoS), M-19-4 (public R2 URLs → signed URLs) | Medium | `09_CREATOR_DELIVERABLES_SPEC.md` | Explicit prod NO-GO condition, unresolved since Task #19. |
| **P1** | M-24-1 — proof-screenshot key ownership binding | Low | `09_CREATOR_DELIVERABLES_SPEC.md` | Explicit prod condition since Task #24. |
| **P2** | Per-deal payout list endpoint (which milestone paid what, net of fee) | Low-medium | `10_CREATOR_PAYMENTS_SPEC.md` | 0% — new endpoint on `WalletController`/`EscrowController`. |
| **P2** | Growth AI-coach recommendations | High — new build, not a regression | `11_CREATOR_ANALYTICS_SPEC.md` | 0%, genuinely new scope. |
| **P2** | Earnings/campaign-performance analytics tab ("analytics wave 2") | Medium | `11_CREATOR_ANALYTICS_SPEC.md` | Growth/scores/demographics done; this slice deferred. |
| **P2** | `creator-settings.tsx` / `creator-inbox.tsx` / `creator-active.tsx` polish | Medium each | Various | Basic structure only; none block the core money/contract/deliverable journey. |
| **P2** | Platform fee tier / per-creator override | Medium | `10_CREATOR_PAYMENTS_SPEC.md` | Currently a correct-but-single global config; not spec'd as v1-required, flag for product decision. |
| **P2 (policy)** | Dispute money-movement (refund/release/split execution) | High — legal + engineering | `15_CREATOR_DISPUTES_SPEC.md` | Explicit CEO interim-policy decision: v1 ships status-transition only, execution is a deliberate follow-up. |
| **Out of scope for Creator** | Brand wallet top-up — real Razorpay Checkout SDK wiring | Medium | `10_...` (brand-side) | Flagged by user report; belongs to Brand module, not Creator, but tracked here since it's in the same wallet code path. |

---

## 6. Agreed blended %

| Source | Number | Basis |
|---|---|---|
| User's report | ~82% | Matches `CREATOR_PROGRESS.md` Tick #31 (2026-07-09 ~22:15 IST) — the most recent checkpoint in the tracker. |
| My prior audit | ~83% | Independently computed, 1pt above the tracker's own latest tick. |
| **Agreed** | **~82%** | Adopting the tracker's most recent tick as the anchor (it postdates my audit's computation and reflects Tick #31's Discovery/Docker-blocked state). The 1pt delta is immaterial and within normal rounding of a feature-weighted blend, but where the two disagree, **the more recent, single-sourced tracker number wins** going forward — both sides should cite Tick #31 (~82%) until a new tick supersedes it. |

**By track (unchanged by this reconciliation, both sides agree):**

| Track | % |
|---|---|
| Backend features | ~90% |
| Frontend features | ~85% |
| Security hardening | ~48% |
| QA coverage | ~58% |
| **Blended** | **~82%** |

---

## Summary for the user

Your report and my audit agree on roughly 90% of the substance — the real feature gaps (analytics wave 2, YouTube OAuth, growth AI coach, per-deal payouts), the security/QA hardening percentages, and the "Bids 35%" mislabeling are all independently confirmed by direct code read. Two corrections in your favor: **OTP MSG91 delivery is genuinely a `TODO` in `BrandEmailOtpService.java`** (a gap I missed entirely — good catch), and the platform-fee-is-global-only / dispute-money-movement-is-a-stub items are both confirmed real. One correction in mine: **affiliate earnings is closer to ~60–65% done, not ~0%** — the backend service, entity, and settlement batch are all real; only a single `GET` endpoint is missing. The one gap your report doesn't mention that I'd flag as the top priority: **there is no `creator-disputes.tsx` page at all**, so a creator has no UI to open or track a dispute today. Agreed blended is **~82%**, anchored to the tracker's most recent tick (#31) rather than my slightly-higher 83%. Full reconciled P0/P1/P2 list is in §5 above — recommend both sides use that table going forward.
