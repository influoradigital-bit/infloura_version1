# Creator Platform — GA Assignments (Priya)

> **Author:** Priya Sharma (CTO)
> **Date:** 2026-07-10 ~14:45 IST
> **Trigger:** Arjun Tick #34 reported this doc **NOT FOUND** (`TASK_INBOX.md` line 7: "not present; using reconciliation §5"). Writing it now as the single authoritative per-employee GA assignment sheet. This **supersedes** ad-hoc dispatch from `CREATOR_REPORT_RECONCILIATION_PRIYA.md` §5 going forward — Arjun should cite this doc, not the reconciliation, for "who does what next."
> **Inputs:** `CREATOR_REPORT_RECONCILIATION_PRIYA.md` (agreed pending list, §5), `CREATOR_PENDING_CODE_AUDIT_PRIYA.md` (code-verified gaps), `CREATOR_PROGRESS.md` (Tick #34 entry, line 1019), `TASK_INBOX.md` (Tick #34 active tasks #38/#39/#40/K6-2/Kv3b/M33-34), plus a direct disk re-check performed while writing this doc (see §0 correction).

---

## 0. Correction to the Tick #34 brief — verify before dispatching

The Tick #34 summary handed to me says **"#38 creator-disputes.tsx still MISSING (Ananya in flight)."** I re-checked disk directly while writing this doc:

- `src/pages/creator-disputes.tsx` **exists** (358 lines, live-wired via `api.creatorDisputes.list/listEligibleDeals/open`, `CreatorLayout`, real lifecycle-status rendering — not a stub).
- `TASK_INBOX.md` Task **#38** itself (lines 15–25) already says **✅ SHIPPED** — page + route in `App.tsx` + creator nav link — with the two remaining boxes being **gates**, not build work: Kavya QA (`creator-disputes.test.tsx` does not exist yet — confirmed by glob) → Meera `npm run build` → my sign-off.

**Correction:** #38 is **code-complete, gate-open** — not "in flight" from a build perspective. Ananya has nothing further to build here unless Kavya's QA pass surfaces a bug. Route Ananya to the next P1 item (§3) instead of re-dispatching #38 build work. Update `TASK_INBOX.md`/`CREATOR_PROGRESS.md` to reflect this the next tick.

Everything else in the Tick #34 brief is confirmed accurate by this same disk check: **#39** (`AuthRateLimitFilter` buckets) and **#40** (`CreatorDeliverableService` streaming/presign/proof-binding) are real code on disk, Kabir has already re-spotted both **PASS (0C/0H)** in `wiki/errors/creator-owasp-K6-cycle2.md`, and Kabir's K6-2 cycle (OAuth/OTP/session) is **done** (5 Medium / 8 Low, no blockers). Kavya's Playwright suite is confirmed absent (no `playwright.config.*` anywhere in the repo). Meera's build-verify is the one gate genuinely still pending for #39/#40.

---

## 1. Current GA blocker state (verified, this tick)

| Item | Code status | Gate status | Blocking |
|---|---|---|---|
| **#38** `creator-disputes.tsx` | ✅ Shipped (verified on disk) | ⬜ Kavya QA → ⬜ Meera build → ⬜ Priya sign-off | GA |
| **#39** Rate-limit sweep (M-K6-1/3/4/5) | ✅ Shipped (`AuthRateLimitFilter` 7 buckets) | ✅ Kabir PASS → ⬜ Meera `mvn test -Dtest=AuthRateLimitFilterK6BucketTest` → ⬜ Priya | GA |
| **#40** Upload hardening (M-19-3/4 + M-24-1 + M-2) | ✅ Shipped (`streamToR2`/`presignGet`/`requireOwnedProofKey`) | ✅ Kabir PASS → ⬜ Meera scoped build → ⬜ Priya | GA |
| **K6-2** Security cycle 2 (OAuth/OTP/session) | N/A (audit, not code) | ✅ **DONE** — PASS WITH FINDINGS 0C/0H/5M/8L | Feeds P1 queue, not a GA blocker itself |
| **Kv3b** Playwright / FE coverage → 80% | 🟡 Partial vitest only (`creator-{dashboard,wallet,reviews}.test.tsx`) | ⬜ No Playwright scaffold exists | GA |
| **M33/M34** Meera build-verify | — | 🔄 In progress (858/858 Docker retry unblocked by `docker-java.properties` pin) | GA |

**Net: 3 real GA blockers left** — Kavya's QA+Playwright gate, Meera's clean build-verify pass, and my final sign-off. No new backend/frontend feature code is required to close #38/#39/#40; this is now a pure QA→build→signoff pipeline.

---

## 2. Vikram (Backend) — ordered task list

### V-GA-1 · P0 · Stand by for Meera re-run of #39/#40 — no new code expected
- **Files:** `AuthRateLimitFilter.java`, `CreatorDeliverableService.java` (already shipped, Kabir-cleared)
- **Action:** No code change unless Meera's `mvn test -Dtest=AuthRateLimitFilterK6BucketTest` or the deliverable scoped tests come back red. If red, you own the same-day fix — do not let this sit past one tick.
- **DoD:** Meera reports green; nothing further from you on #39/#40.
- **Depends on:** nothing. **Blocks:** Meera's gate only (informationally).
- **Parallel with:** V-GA-2 through V-GA-5 below (all P1, all unblocked, none touch `AuthRateLimitFilter` or `CreatorDeliverableService`).

### V-GA-2 · P1 · M-K6-C2-1 — OTP email enumeration fix
- **Files:** `influora-api/src/main/java/com/influora/service/BrandEmailOtpService.java` (`sendOtp` — currently throws `EMAIL_ALREADY_EXISTS` before issuing a challenge)
- **Action:** Return a uniform 200/success response regardless of whether the email is already registered; still enforce the existing per-email send rate limit (`otp-send-per-email-per-hour:3`) on the same path so the fix doesn't open a fresh abuse vector.
- **DoD:** Registered and unregistered emails are indistinguishable from the response; existing 3/hr rate limit still fires; `BrandEmailOtpServiceTest` gets a new case for both email states returning identical shapes.
- **Depends on:** nothing. **Blocks:** Kabir's cycle-2 Medium close-out.
- **Parallel with:** V-GA-3, V-GA-4, V-GA-5.

### V-GA-3 · P1 · M-K6-C2-2 — Password policy
- **Files:** new shared `PasswordPolicy` validator (e.g. `influora-api/src/main/java/com/influora/common/PasswordPolicy.java`); wire into brand + creator register and reset-password paths (`AuthService`, `BrandRegisterRequest`/`CreatorRegisterRequest`).
- **Action:** Enforce spec §1.1 complexity (upper/lower/number, not just `@Size(min=8)`) + a top-10k common-password denylist check.
- **DoD:** Weak passwords (e.g. `password1`, all-lowercase) rejected on register **and** reset; existing valid-password tests still pass; one shared validator, not duplicated per-endpoint logic.
- **Depends on:** nothing. **Blocks:** nothing downstream, but is a named Kabir cycle-2 Medium — don't let it silently age past next sprint.

### V-GA-4 · P1 · M-K6-C2-4 — Meta OAuth PKCE
- **Files:** `influora-api/src/main/java/com/influora/integration/meta/oauth/MetaOAuthService.java` (`buildAuthorizationUrl` + token-exchange call)
- **Action:** Add `code_verifier`/`code_challenge` (S256) per spec §2.2. Confidential client + server secret already mitigate code-theft risk, so this is spec-compliance hardening, not a live exploit — sequence behind V-GA-2/V-GA-3 if time-constrained.
- **DoD:** Authorize URL includes `code_challenge`; token exchange sends matching `code_verifier`; existing Meta OAuth integration tests (state/CSRF, token encryption) still green.
- **Depends on:** nothing. **Blocks:** nothing.

### V-GA-5 · P1 · M-K6-C2-5 — Review-flag uniqueness (M-K6-3 residual / M-T29-2 carry)
- **Files:** new Flyway migration adding `flagged_by_user_id` + unique `(content_id, flagged_by_user_id)` on the review-flag/`ContentFlag` table; `ReviewService`/flag-creation service gate.
- **Action:** Rate limit (already shipped in #39) stops flood volume; this closes the remaining "same user can insert unlimited duplicate flags one-at-a-time" gap.
- **DoD:** Second flag from the same user on the same content is rejected (409, not a duplicate row); migration applies cleanly; unit test for the duplicate-flag case.
- **Depends on:** nothing. **Blocks:** nothing.

### V-GA-6 · P1 · MSG91 OTP delivery wiring (P1 queue item #3, carried from reconciliation)
- **Files:** `influora-api/src/main/java/com/influora/service/BrandEmailOtpService.java` (line ~69 `// TODO: MSG91 Email API`), reuse `influora-api/src/main/java/com/influora/integration/msg91/Msg91EmailClient.java` (already wired for transactional emails — do not build a second MSG91 client).
- **DoD:** OTP actually emailed via MSG91 in non-dev environments; dev-mode log-only fallback preserved; used by both brand and creator OTP paths (`AuthController` lines 81–92 confirm shared service).
- **Depends on:** nothing. **Blocks:** any real-world creator/brand signup — this is a genuine "no one can receive an OTP in prod" gap, sequence it early in P1.

### V-GA-7 · P1 · Affiliate earnings `GET` endpoint (reconciliation §5)
- **Files:** new `influora-api/src/main/java/com/influora/web/CreatorAffiliateEarningController.java` (or extend an existing controller) — reuse `AffiliateEarningsService.java`/`AffiliateEarning.java`/`AffiliateSettlementBatch.java`, all already real.
- **DoD:** `GET /creator/affiliate-earnings` (or similar), principal-scoped, returns SETTLED-vs-pending breakdown matching what `AffiliateEarningsView.tsx` already expects to render.
- **Depends on:** nothing. **Blocks:** Ananya's affiliate wire (A-GA-4).

### V-GA-8 · P1 · `GET /creator/reviews/received`
- **Files:** `influora-api/src/main/java/com/influora/web/CreatorReviewController.java` (currently only `create`/`flag` — add `list`/`get`).
- **DoD:** Creator can list reviews received about them; cross-creator isolation unit test (own reviews only).
- **Depends on:** nothing. **Blocks:** Ananya's `creator-reviews.tsx` received-tab wire (A-GA-5).

### V-GA-9 · P2 · Per-deal payout list, platform-fee tier/override, growth AI-coach backend
- Deferred behind all P0/P1 above — see reconciliation §5 for scope; do not start until P1 queue is clear.

**Vikram sequencing:** V-GA-1 is a standby, not active work. V-GA-2 through V-GA-8 are **mutually parallel** (touch disjoint files/services) — pick them up in the listed priority order as capacity allows, but none blocks another.

---

## 3. Ananya (Frontend) — ordered task list

### A-GA-1 · P0 · Do NOT re-build `creator-disputes.tsx` — stand by for Kavya QA findings only
- Per §0 correction: the page is shipped. Do not touch it unless Kavya's QA pass (A-GA-2 below, owned by Kavya not you) files a bug against it. Confirm with Arjun that Tick #34's "in flight" note is stale before picking up any further #38 work.

### A-GA-2 · P0 · M-K6-C2-3 — Access-token storage hardening
- **Files:** `src/lib/auth-session.ts` (or wherever `brand_token`/`creator_token` are written to `localStorage`), plus SPA host CSP headers (check `vite.config.ts`/hosting config for existing CSP, harden if thin).
- **Action:** Move the **access** token out of `localStorage` into memory (or `sessionStorage` as an interim step) with short-TTL discipline; refresh token is already correctly HttpOnly-cookie-only per Kabir's cycle-2 findings — do not touch the refresh flow.
- **DoD:** Access token no longer readable via `localStorage.getItem` from a same-origin XSS payload in a manual check; existing auth flows (login, refresh, logout, API calls) still work end-to-end; CSP header present and restrictive on script-src.
- **Depends on:** nothing. **Blocks:** nothing, but this is the one Ananya-owned Medium from K6-2 — prioritize over new P1 feature wiring below.
- **Parallel with:** all Vikram P1 items (no shared files).

### A-GA-3 · P1 · MSG91 OTP UX confirm (no new UI expected)
- **Files:** `creator-register.tsx`/`brand register flow` — confirm the existing resend/6-digit UI (already live) doesn't need changes once Vikram's V-GA-6 lands; this is a verification task, not a build task, unless the real MSG91 send surfaces a new error state (e.g. delivery failure) that the UI doesn't currently handle.
- **DoD:** Manual pass confirms OTP arrives by email in a non-dev config; add an error-toast case for delivery failure if one doesn't already exist.
- **Depends on:** V-GA-6.

### A-GA-4 · P1 · Wire `creator-affiliate-earnings.tsx` off the new endpoint
- **Files:** `src/pages/creator-affiliate-earnings.tsx`, `AffiliateEarningsView.tsx` (already a real component with correct SETTLED-vs-pending honesty logic — this is a data-source swap, not a rebuild).
- **DoD:** Component renders real data from Vikram's `GET /creator/affiliate-earnings`; loading/error/empty states match the established pattern from every other creator page this sprint.
- **Depends on:** V-GA-7. **Blocks:** nothing.

### A-GA-5 · P1 · Wire `creator-reviews.tsx` received tab
- **Files:** `src/pages/creator-reviews.tsx` (received tab currently explicitly deferred in-page).
- **DoD:** Received tab shows real reviews from Vikram's `GET /creator/reviews/received`; write-tab (already live) untouched.
- **Depends on:** V-GA-8.

### A-GA-6 · P1 · Discovery FE — wire portfolio/reviews tabs on public profile
- **Files:** `creator-portfolio-public.tsx` or wherever the public discovery profile tabs live — currently illustrative placeholders per the reconciliation doc.
- **DoD:** Portfolio/reviews tabs pull from real `PortfolioController`/`ReviewRepository` data instead of static mock content.
- **Depends on:** nothing new (backend already real). **Blocks:** closing Discovery's remaining ~30% gap alongside the QA/security/Priya gate cycle (already unblocked per Meera's Docker fix).

### A-GA-7 · P2 · `creator-settings.tsx` / `creator-inbox.tsx` / `creator-active.tsx` polish
- Deferred behind all P0/P1 above.

**Ananya sequencing:** A-GA-2 is P0, start immediately. A-GA-3/4/5/6 are mutually parallel once their respective Vikram dependency lands — dispatch each the moment its backend half ships, don't batch-wait for all four.

---

## 4. Kabir (Security) — ordered task list

### K-GA-1 · P0 · Confirm nothing new since K6-2 — standing watch only
- K6-2 (`wiki/errors/creator-owasp-K6-cycle2.md`) already delivered PASS WITH FINDINGS (0C/0H/5M/8L) and already re-spotted #39/#40 PASS. **No further action from you on #38/#39/#40** unless Kavya's QA pass on #38 surfaces something that touches access control (in which case, targeted re-review of just that diff, not a fresh full pass).

### K-GA-2 · P1 · Review V-GA-2/3/4/5 diffs as they land (your own filed Mediums)
- **Scope:** OTP-enumeration fix, password policy, Meta PKCE, review-flag uniqueness — these are your own K6-2 findings; close each with a one-line confirmation in a new dated section of `creator-owasp-K6-cycle2.md` (or a K6-2b addendum) once Vikram's diff lands. Full red-team pass not required for these — they're targeted fixes to findings you already scoped completely.
- **DoD:** M-K6-C2-1/2/4/5 all flip to CLOSED with evidence.

### K-GA-3 · P1 · Review A-GA-2 (access-token storage) diff
- **Scope:** confirm the token-storage change actually removes the `localStorage` XSS-theft surface without introducing a new one (e.g. a global JS variable that's equally scriptable, or a broken refresh flow that silently falls back to a less-secure storage).
- **DoD:** M-K6-C2-3 CLOSED with evidence.

### K-GA-4 · P0 (next cycle) · K6-3 — PII-at-rest + upload malware + OAuth token-log probe
- **Scope:** per K6-2's own pipeline routing (line 208 of that doc) — this is the next full cycle, not a Tick #34 item. Do not start until K-GA-2/3 close-outs are filed; those are same-day, this is a multi-day cycle.
- **DoD:** new `wiki/errors/creator-owasp-K6-cycle3.md`; security matrix ~62% → target ~75%+.

### K-GA-5 · P1 · Cycle 4 — dependency CVE scan + common-password denylist audit
- Sequenced after K6-3; final cycle before GA security sign-off.

**Kabir sequencing:** K-GA-1 is a standing watch (no active hours). K-GA-2/3 are lightweight, parallel, dispatch as soon as each Vikram/Ananya diff lands. K-GA-4 is the next real multi-day block of work — do not let it start late just because K-GA-2/3 are trickling in; it can run in parallel with the tail end of the P1 fixes.

---

## 5. Kavya (QA) — ordered task list

### Kv-GA-1 · P0 · QA pass on `creator-disputes.tsx` — THE critical-path item right now
- **Files to test:** `src/pages/creator-disputes.tsx` against `api.creatorDisputes.list/listEligibleDeals/open`; write `src/pages/creator-disputes.test.tsx` (confirmed absent — this is a net-new file, mirror the pattern already used in `creator-wallet.test.tsx`/`creator-reviews.test.tsx`).
- **Hostile tests to run:** creator cannot open a dispute on a deal they're not party to; creator cannot open a second dispute on a collaboration with one already active (`DisputeService` one-active-dispute rule); dispute list only shows the creator's own disputes; lifecycle status labels render correctly for all 5 states (`OPEN`/`UNDER_REVIEW`/`RESOLVED_BRAND`/`RESOLVED_CREATOR`/`RESOLVED_SPLIT`); empty state (no eligible deals) renders honestly, not a fabricated list.
- **DoD:** `creator-disputes.test.tsx` green; findings doc if anything fails (`wiki/errors/creator-disputes-T38-kavya-qa.md`); this is the **only** thing standing between #38 and Meera's build gate.
- **Depends on:** nothing (code already shipped). **Blocks:** Meera's `npm run build` gate for #38, and my sign-off.
- **This is today's single highest-priority task across all five employees.**

### Kv-GA-2 · P0 · Playwright scaffold + first creator-journey smoke test
- **Files:** new `playwright.config.ts` at repo root (confirmed absent via glob); first spec under e.g. `e2e/creator-journey.spec.ts`.
- **Scope:** one real browser E2E smoke covering login → dashboard → at least one deal-room interaction (matches the "≥1 creator journey smoke" DoD already written in `TASK_INBOX.md` Kv3b). Do not attempt full 80% browser coverage in one pass — this is the scaffold + proof-of-concept, more journeys are P1 follow-up.
- **DoD:** `npx playwright test` runs and passes locally; CI wiring noted for Meera even if not fully automated yet.
- **Depends on:** nothing. **Parallel with:** Kv-GA-1 (different files entirely).

### Kv-GA-3 · P1 · Vitest coverage push toward 80%
- **Scope:** current state is `creator-{dashboard,wallet,reviews}.test.tsx` + brand-disputes stand-ins — extend to remaining creator pages (`creator-analytics.test.tsx`, `creator-coupons.test.tsx`, `creator-deals.test.tsx` already exist per glob — audit which pages still have zero test coverage and prioritize those).
- **DoD:** coverage report published; E2E % moves off the stuck ~58% once Playwright (Kv-GA-2) and the coverage push land together.

### Kv-GA-4 · P1 · Extend QA test plan with K6-2/cycle-3 sections
- **Scope:** add sections mirroring Kabir's cycle-2 findings (OTP enum, password policy, token storage) so QA has a written hostile-test checklist for each, not just ad-hoc verification.

**Kavya sequencing:** Kv-GA-1 and Kv-GA-2 are both P0 and touch disjoint files — run them **in parallel**, not sequentially. Kv-GA-1 is the more urgent of the two because it's the only thing blocking #38's gate chain today.

---

## 6. Meera (Build/DevOps) — ordered task list

### M-GA-1 · P0 · Scoped build-verify for #39
- **Command:** `mvn test -Dtest=AuthRateLimitFilterK6BucketTest`
- **DoD:** green (all bucket tests pass); post result to `SHARED_CONTEXT.md`/`TASK_INBOX.md` in the existing dated-entry format.
- **Depends on:** nothing (code + Kabir PASS already in place). **Blocks:** Priya sign-off on #39.

### M-GA-2 · P0 · Scoped build-verify for #40
- **Command:** deliverable-service scoped `mvn test` (upload/stream/presign/proof-binding test classes) + note L-K6-C2-5 (thin unit coverage) as a backlog item, not a gate blocker per Kabir's verdict.
- **DoD:** green; same tracker-update pattern.
- **Depends on:** nothing. **Blocks:** Priya sign-off on #40.

### M-GA-3 · P0 · Full regression + M-Kv3-1 858/858 retry
- **Command:** full `mvn test` regression using the newly landed `docker-java.properties` (`api.version=1.44`) pin — confirm the Docker-blocked integration suite (previously 857/858) now clears the last test.
- **DoD:** 858/858 green, or a specific named failure routed back to the owning engineer (not a generic "still red" report).
- **Depends on:** the `docker-java.properties` fix (already landed per Tick #34). **Blocks:** closing Discovery's QA/security/Priya gate cycle.

### M-GA-4 · P0 · `npm run build` once Kavya's #38 QA pass is green
- **Command:** `npm run build` (frontend) — confirm no build break from `creator-disputes.tsx` + its new test file.
- **Depends on:** Kv-GA-1. **Blocks:** Priya sign-off on #38.

### M-GA-5 · P1 · Backfill build-verify for any V-GA-2…8 diffs as they land
- **Scope:** standard scoped-test + full-regression pattern, same as every prior task in this sprint — no shortcuts on money-adjacent code (affiliate earnings, reviews).

### M-GA-6 · Ongoing · Tracker hygiene
- **Scope:** update `TASK_INBOX.md`/`CREATOR_PROGRESS.md`/`SHARED_CONTEXT.md` after each gate above, and correct the stale "#38 in flight" framing per §0 of this doc in the next tick's entry.

**Meera sequencing:** M-GA-1, M-GA-2, M-GA-3 can all run **in parallel** right now (different test scopes, no shared state) — none needs to wait for the others. M-GA-4 is the one item genuinely gated on Kavya (Kv-GA-1).

---

## 7. Critical path (recommended sprint order)

This is the dependency-ordered execution plan — items on the same numbered line are parallel; a new number means the prior line's output is needed.

1. **Security cycle 2 (DONE) + M-K6 rate limits (DONE, code+Kabir-cleared)** — Meera M-GA-1 build-verifies `AuthRateLimitFilterK6BucketTest` now; nothing blocks this today.
2. **Upload hardening M-19-3/4 + M-24-1 + M-2 (DONE, code+Kabir-cleared)** — Meera M-GA-2 build-verifies the deliverable-service scoped tests now, in parallel with step 1.
3. **Creator FE tests + Playwright E2E** — Kavya Kv-GA-1 (creator-disputes QA, P0, most urgent single item today) and Kv-GA-2 (Playwright scaffold) run in parallel; both are unblocked right now.
4. **`creator-disputes.tsx` UI (DONE, code-complete)** — gate-only from here: Kavya (step 3) → Meera M-GA-4 (`npm run build`) → **my sign-off**. No further build work unless QA finds a bug.
5. **Then features: affiliate wire, analytics wave 2, YouTube OAuth** — Vikram V-GA-7 (affiliate endpoint) + V-GA-8 (reviews-received endpoint) start now in parallel with everything above (disjoint files); Ananya A-GA-4/A-GA-5 follow once each backend half lands. YouTube OAuth decision (escalate to Swapnil for written deferral, per reconciliation §5) can happen any time — it's a sign-off ask, not code, and isn't blocking anyone.

**The one thing that should happen literally today, before anything else on this list:** Kavya's `creator-disputes.test.tsx` (Kv-GA-1). It's the only remaining piece between #38 and full GA-blocker closure on that item, the code has been sitting shipped, and Meera/Priya are both waiting on it.

---

## 8. Do-not-build confirmation (unchanged from CEO ruling — repeating so it doesn't drift)

Per `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §2/§4/§6 and `CREATOR_PENDING_CODE_AUDIT_PRIYA.md` §5, confirming these are still **closed, not backlog**:

- **Do NOT build standalone `creator-bids.tsx`, `creator-deliverables.tsx`, or `creator-contracts.tsx` pages.** That functionality lives inside `creator-deals.tsx` (bids/negotiation) and `creator-chat.tsx`'s deal room (deliverables, e-sign) by locked architecture decision. A separate page would fragment the UX and contradict a standing design ruling — if this resurfaces in a future audit, cite this doc.
- **Do NOT hardcode the platform fee anywhere**, backend or frontend. It stays a DB-backed `PlatformFeeConfig` singleton row (currently global-only, no tier/per-creator override — that's a tracked P2 product decision, not an excuse to hardcode in the meantime).
- **Dispute money-movement (refund/release/split execution) is Phase 2.** `DisputeService.resolveDispute()` is an intentional v1 stub — status-transition only, per CEO §1.3 interim policy. Do not build automatic clawback or refund execution now; full legal policy (refund %, SLA, appeals) is an explicit follow-up.
- Also still closed: no second `Bid`/`CampaignApplication`/`Conversation` entity (locked architecture, `Collaboration`+`DealMessage` only); no Elasticsearch cluster for discovery (MySQL-native is the shipped, correct choice); no admin dispute-console polish beyond the existing v1 resolve endpoint; no malware-scan infra (accepted risk, flagged not hidden); no TikTok OAuth (explicitly "Future" in spec).

---

## 9. Summary for Arjun

**Top P0s to dispatch this tick, in order:**

1. **Kavya — `creator-disputes.test.tsx`** (Kv-GA-1). Highest priority. Code is done; this is the only gap between #38 and closure.
2. **Kavya — Playwright scaffold + first smoke test** (Kv-GA-2), in parallel with #1.
3. **Meera — `AuthRateLimitFilterK6BucketTest` + deliverable scoped tests + full regression** (M-GA-1/2/3), all three in parallel, none blocked on anything.
4. **Ananya — access-token storage hardening (M-K6-C2-3)** (A-GA-2). The one Ananya-owned security Medium from K6-2.
5. **Vikram — OTP enumeration fix (M-K6-C2-1)** (V-GA-2), then password policy / PKCE / flag-uniqueness (V-GA-3/4/5) in any order, all parallel with each other.

**Correction to carry into the next tracker update:** #38 is **not** "in flight" — it's shipped and gate-blocked on Kavya/Meera/me. Re-dispatching Ananya to "build" #38 wastes a cycle; point her at A-GA-2 instead.

**Nothing here requires my sign-off yet** — that's the last step once Kavya + Meera clear #38/#39/#40. I'll sign off the moment `npm run build` (M-GA-4) and the QA pass (Kv-GA-1) both come back green.
