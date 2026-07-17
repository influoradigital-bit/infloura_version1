# TASK INBOX — Sage Digital

> **Orchestrator:** Arjun Kapoor  
> **Last Updated:** 2026-07-10 ~15:50 IST (Tick #36 — Priya #38/#39/#40 CTO sign-off)
> **Active Sprint:** Creator GA hardening — Weeks 3–4 ✅ 100%; full-platform blended **~84%**; #38/#39/#40 gates **CLOSED**; remaining = P1 + K6-3/4 + E2E 80%
> **Source of truth:** `wiki/tech/creator/CREATOR_GA_ASSIGNMENTS_PRIYA.md` (supersedes reconciliation §5 for who-does-what)

---

## 🔴 ACTIVE TASKS (Tick #36 — P1 AFTER #38/#39/#40 SIGN-OFF) — 2026-07-10 ~15:50 IST

> **CRITICAL:** #38 `creator-disputes.tsx` is ✅ **SHIPPED/CONDITIONAL** (Priya) — do **NOT** rebuild. Do **NOT** start K6-3 from this close.

### Kv-GA-1 + Kv-GA-2 — Kavya — **P0** (parallel)
**Status:** ✅ **DONE Tick #35** (~15:00 IST)  
**Kv-GA-1 (highest priority):** `src/pages/creator-disputes.test.tsx` + hostile QA → `wiki/errors/creator-disputes-T38-kavya-qa.md`  
**Kv-GA-2:** Playwright scaffold (`playwright.config.ts` + `e2e/creator-journey.spec.ts` smoke)  
**DoD:**
- [x] `creator-disputes.test.tsx` green (**10/10**)
- [x] Playwright smoke passes locally (`creator-journey` 2/2 + dashboard smoke)
- [x] E2E % update posted (~58% → **~62%**; P2 slices ~78% → **~80%**)

**Verdict:** ✅ **PASS WITH NOTES** (0C/0H) — Meera M-GA-4 unblocked for #38.

---

### M-GA-1/2/3 — Meera — **P0** (parallel; M-GA-4 waits Kv-GA-1)
**Status:** ✅ **DONE Tick #35** ~14:57 IST — all gates CLOSED after V-GA-1 re-run  
**DoD:**
- [x] M-GA-1: `AuthRateLimitFilterK6BucketTest` **15/15 PASS**
- [x] M-GA-2: deliverable scoped **54/54 PASS** (Service **24/24**, Controller **5/5**; post V-GA-1)
- [x] M-GA-3: full `mvn test` **894/894 PASS** (TC/Flyway featured_creators ✅; suite >858)
- [x] M-GA-4: `npm run build` ✅ **4601 modules**, 28s (Kv-GA-1 green)

---

### A-GA-2 — Ananya — **P0** (M-K6-C2-3 only)
**Status:** ✅ **SHIPPED Tick #35** — **NOT** #38 rebuild  
**What:** Move access token out of `localStorage` → memory/sessionStorage; harden CSP; do not touch refresh HttpOnly cookie.  
**Files:** `src/lib/auth-session.ts`, `src/lib/api.ts`, `src/App.tsx`, layouts/settings/hooks, `vite.config.ts`, `public/_headers`  
**DoD:**
- [x] Access token not in `localStorage`
- [x] Login/refresh/logout/API still work
- [x] Kabir K-GA-3 re-spot — ✅ CLOSED Tick #36

---

### A-GA-3/4/5 — Ananya — **P1** (unblocked by V-GA-6/7/8)
**Status:** ✅ **SHIPPED Tick #36**  
**DoD:**
- [x] A-GA-3: OTP UX handles `EMAIL_DELIVERY_FAILED` / 503 — destructive toast + inline error on creator/brand register + brand onboarding OTP
- [x] A-GA-4: `AffiliateEarningsView` / `useAffiliateEarnings` live via `GET /creator/affiliate-earnings` (`api.affiliateEarnings.get`)
- [x] A-GA-5: creator reviews received tab live via `GET /creator/reviews/received` (`api.creatorReviews.listReceived`)
**Files:** `creator-register.tsx`, `brand-register.tsx`, `onboarding-steps.tsx`, `api.ts`, `useAffiliateEarnings.ts`, `AffiliateEarningsView.tsx`, `creator-affiliate-earnings.tsx`, `collaboration-reviews-panel.tsx`, `creator-reviews.tsx`  
**Note:** Do **NOT** rebuild #38 disputes. Brand `GET /brand/reviews/received` still NOT_IMPLEMENTED.

---

### V-GA-2…5 — Vikram — **P1→P0 this tick** (C2 Mediums)
**Status:** ✅ **SHIPPED** — V-GA-1…5 complete; Kabir re-spot C2-4/5  
**DoD:**
- [x] V-GA-2 M-K6-C2-1 OTP email enumeration — ✅ Kabir CLOSED
- [x] V-GA-3 M-K6-C2-2 PasswordPolicy — ✅ Kabir CLOSED
- [x] V-GA-4 M-K6-C2-4 Meta OAuth PKCE — ✅ Kabir CLOSED Tick #36
- [x] V-GA-5 M-K6-C2-5 review-flag uniqueness — ✅ Kabir CLOSED Tick #36
- [x] **V-GA-1** — `CreatorDeliverableServiceTest` **24/24** + IT coupon FK seed — ✅ SHIPPED (Meera M-GA-2/3 green)

---

### K-GA-1/2/3 — Kabir — **P0 watch + P1 re-spot**
**Status:** ✅ **DONE Tick #36** — C2-3/4/5 CLOSED  
**DoD:**
- [x] No new full pass on #38/#39/#40 unless access-control bug from Kavya
- [x] Close M-K6-C2-1/2 — ✅ Tick #35
- [x] Close M-K6-C2-3/4/5 — ✅ Tick #36 (A-GA-2 + V-GA-4/5)

---

### 38 / 39 / 40 — gate status (CLOSED — Priya 2026-07-10 ~15:50 IST)
| Task | Code | Kabir | Kavya | Meera | Priya |
|------|------|-------|-------|-------|-------|
| #38 disputes FE | ✅ | n/a (no ACL bug) | ✅ Kv-GA-1 PASS WITH NOTES | ✅ M-GA-4 4601 modules | ✅ **SHIPPED/CONDITIONAL** |
| #39 rate limits | ✅ | ✅ PASS 0C/0H | n/a | ✅ M-GA-1 15/15 | ✅ **SHIPPED/CONDITIONAL** |
| #40 upload | ✅ | ✅ PASS 0C/0H | n/a | ✅ M-GA-2 54/54 (post V-GA-1) | ✅ **SHIPPED/CONDITIONAL** |

**Priya prod-only (non-blocking):** M-K6-2 Redis; L-T38-1 `GET /creator/disputes`; L-K6-C2-5/6; Phase 2 dispute money-movement; K6-3/4 for full GA security.

---

### P1 QUEUE (after / parallel with C2 where disjoint)
1. ~~V-GA-6 MSG91 OTP delivery · V-GA-7 affiliate GET · V-GA-8 reviews-received GET~~ ✅
2. ~~A-GA-3/4/5 wire after Vikram halves~~ ✅ · A-GA-6 Discovery tabs · YouTube OAuth→Swapnil
3. K-GA-4 K6-3 cycle (after C2 Medium close-outs start)

---

## 🟣 CEO DIRECTIVE (Swapnil, 2026-07-09 ~19:30 IST) — READ FIRST

**Full doc:** `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` — audited `wiki/tech/PENDING_TASKS_REPORT.md` creator section against actual code. Result: 8 genuinely open items, 4 items the report mis-tracked as "not started" that are already shipped (`BrandSafetyScoreService`, `AudienceDemographicsJob`, creator OTP flow, and partial analytics backend), and 3 "missing pages" that are resolved by existing deal-room architecture (no build needed).

**Policy rulings (unblock stalled specs immediately):**
1. Platform fee **APPROVED**: 10% brand / 15% creator, Option A (Influora absorbs Razorpay cost).
2. Review/rating policy **APPROVED**: post-`COMPLETED` only, 1–5 stars + text, no anonymous reviews, admin moderation via existing `ContentFlag` pattern.
3. Dispute/refund interim policy **APPROVED**: admin arbitrates, unreleased escrow freezes on dispute open (no auto-refund/clawback).

**Arjun: dispatch all open creator P0s in the next loop tick** (re-arm `AGENT_LOOP_WAKE_CREATOR` first — confirm it's actually alive, not just logged as stopped). Top 5, ranked:
1. `PlatformFeeService` — creator escrow-release 15% deduction (Vikram)
2. `GET /creator/platform-fee` transparency endpoint + wallet fee UI (Vikram + Ananya)
3. `Review` entity + `creator-reviews`/`brand-reviews` pages (Vikram + Ananya) — was blocked on policy, now unblocked
4. `creator-dashboard` home page (Ananya) — zero backend dependency, ship immediately
5. Creator-facing coupon-read endpoint + live-wire `creator-coupons.tsx` off mock (Vikram + Ananya)

**Do NOT build:** standalone `creator-bids`/`creator-deliverables`/`creator-contracts` pages — already covered inside `creator-chat.tsx`'s deal room per locked architecture. See CEO doc §2/§6 for the full "what not to do" list.

**Note on "100% blended":** that figure is Week 3 sprint scope only (auth→deliverables→e-sign→rate limits), not the full 13-spec creator platform. Both "100%" and Priya's "~71% full-spec blended" are simultaneously true — see CEO doc §1.4. Do not re-litigate either number.

---

## 🔴 ACTIVE TASKS (Week 4)

> **Sprint status:** ✅ **CEO TOP 5 COMPLETE** — Tick #29 P2 **COMPLETE**. **Tick #30 COMPLETE** (~21:15 IST dispatch; all 4 workers landed). Full-platform blended **~82%** (Discovery + Security + QA advances; gates pending).

### 36. Discovery backend — Vikram (V7)
**Priority:** P0 (CEO Tick #30)  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED** — 9/9 `CreatorDiscoveryServiceTest`; migration `V20260709163000__featured_creators.sql`

**What:** Audit + extend `CreatorDiscoveryService` per `04_CREATOR_DISCOVERY_SPEC.md` — MySQL-native (no Elasticsearch).

**Shipped:** `GET /creators/featured`, `/{username}/similar`, `POST /suggestions`, `GET /search` w/ facets, `GET /profile/{usernameOrId}`; categories/languages filters; `FeaturedCreator` entity.

**Definition of Done:**
- [x] Gap audit vs spec 04 documented
- [x] P0 endpoints + unit tests (**9/9**)
- [x] Meera unit gate — Tick #35 M34: **881/886** (suite grew; 4 deliverable harness + 1 IT seed FK); docker-java **1.44** pin ✅ TC unblocked
- [x] Meera migration runtime gate — ✅ Flyway applied `V20260709163000 - featured creators` on Testcontainers MySQL (smoke IT PASS; `doubleCredit` seed FK still red)
- [ ] Ananya `api.ts` live wire for featured/similar/suggestions
- [ ] Kavya QA → Kabir security → Priya sign-off

**Deferred:** Elasticsearch, `accepting_collabs`, portfolio/reviews on public profile, admin featured API

---

### 37. Discovery UI — Ananya (A6 + #37b)
**Priority:** P0 (CEO Tick #30)  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED** — #37 + #37b live wire complete; gates pending (Kavya → Meera → Priya)

**What:** Audit + wire `creator-discovery.tsx` against spec 04 — filters, sort, featured section, creator detail route, `api.creators.*` parity.

**Files:** `src/components/brand/discover/creator-discovery.tsx`, `src/lib/api.ts`, `src/pages/brand-creator-profile.tsx`

**Definition of Done:**
- [x] Live API wire when `isApiLive()`; demo fail-closed otherwise
- [x] Loading/error/empty states per Influora patterns
- [x] #37b: `featured/similar/suggestions/getProfile/searchWithFacets` live wired
- [x] Meera `npm run build` — ✅ PASS (4599 modules)
- [x] Kavya Discovery QA — ⚠️ **APPROVED WITH FINDINGS** (`wiki/errors/creator-discovery-T36-kavya-qa.md`)
- [ ] Kabir M-K6-5 spot → Meera gate → Priya sign-off

**Deferred:** portfolio/reviews/audience tabs illustrative; `accepting_collabs` filter

---

### K6. Final OWASP audit kickoff — Kabir
**Priority:** P0 (CEO Tick #30)  
**Status:** ✅ **PASS WITH FINDINGS** (~22:30 IST) — **0C/0H/5M/12L**; kickoff does not block conditional sign-offs

**What:** Full OWASP pass kickoff per `12_CREATOR_SECURITY_SPEC.md` + Week 4 gate. Spot-check Week 4 money/reputation surfaces (#26 fee, #29 reviews, #34 dispute, #35 analytics, `/creators` discovery).

**DoD:**
- [x] `wiki/errors/creator-owasp-K6-kickoff.md` with verdict + numbered findings
- [x] SHARED_CONTEXT handoff posted
- [ ] K6 cycles 2–4 (auth/OTP/PII backlog ~52%)
- [ ] Vikram M-K6-1–5 rate-limit pre-prod sprint → Kabir K6-2 re-spot

**Matrix:** Security **30% → ~48%**

---

### Kv3. Full E2E test plan execution — Kavya
**Priority:** P0 (CEO Tick #30)  
**Status:** ⚠️ **SLICE 1 DONE** — IN PROGRESS, not sign-off ready (~58% blended E2E; 80% gate **NOT MET**)

**What:** Execute `KAVYA_QA_TEST_PLAN.md` §1–§22 systematically; 80%+ coverage target per Kv-5.

**DoD:**
- [x] Slice 1: `wiki/errors/creator-e2e-Kv3-kickoff.md`; §23 checkboxes; gaps routed
- [x] `npm run build` PASS (4599); Vitest 139/139
- [ ] **Meera M-Kv3-1** — full `mvn test` (858 tests) — ⚠️ **ENV-BLOCKED** 857/858; `DatabaseConstraintIntegrationTest` Testcontainers API 1.32 vs Docker 29 min 1.44 (daemon ✅); fix: `docker-java.properties` or TC bump; `CreatorDiscoveryServiceTest` 9/9 ✅
- [ ] Kv3 slice 2 — staging smoke (auth OTP → profile → apply)
- [x] Playwright harness (Ananya G-Kv3-A1); ~~auth unit tests (Vikram G-Kv3-1)~~ ✅ **SHIPPED** — AuthServiceTest **19/19** · BrandEmailOtpServiceTest **14/14** · AuthControllerTest **10/10** (scoped **43/43**)

**Gaps routed:** ~~Vikram G-Kv3-1 (P0 auth unit)~~ ✅; G-Kv3-2–4; Ananya G-Kv3-A1/A2

---

### 26. PlatformFeeConfig + PlatformFeeService — Vikram (V1)
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — Kavya **APPROVED**; Kabir K2 **PASS WITH FINDINGS** (`wiki/errors/creator-platform-fee-T26-kabir-redteam.md`); Meera M2 **PASS** ~19:57 IST (**8/8**)

**Files created/modified:**
- `V41__platform_fee_config.sql`, `PlatformFeeConfig.java`, `PlatformFeeConfigRepository.java`, `PlatformFeeService.java` (created)
- `PlatformFeeServiceTest.java` (6), `EscrowServiceReleaseTest.java` (2)
- `EscrowService.java`, `PlatformWalletService.java`, `EscrowServiceTest.java` (modified)

**What:** DB-backed `PlatformFeeConfig` (1500 bps seeded) + `deductAtRelease()` in `EscrowService.release()` before creator credit. `PLATFORM_FEE` → platform revenue wallet.

**Reference:** `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §4 P0-V1

**Definition of Done:**
- [x] 15% deducted at release, not funding
- [x] Fee traceable in platform ledger per txn
- [x] Zero direct `Wallet.balance` mutations outside `WalletLedgerService.post()`
- [x] Config read from DB (redeploy not required to change %)
- [x] Unit tests (**8** new/updated)
- [x] Kavya QA (Kv1) — ✅ **APPROVED** (L-T26-1–4 Low carry-forward)
- [x] Kabir K2 — ✅ **PASS WITH FINDINGS** (L-K2-T26-1–6 Low; no Critical/High)
- [x] Meera M2 — ✅ **PASS** ~19:57 IST (`mvn test -Dtest=PlatformFeeServiceTest,EscrowServiceReleaseTest` **8/8** — `PlatformFeeServiceTest` 6/6, `EscrowServiceReleaseTest` 2/2; BUILD SUCCESS in 11.7s)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (L-K2-T26-1–6 Low carry-forward; `effectiveAt`/per-creator fee hierarchy deferred per §1A)

**Unblocks:** Task #27 (V2), Ananya A2 (wallet fee UI) ✅

---

### 27. GET /creator/platform-fee — Vikram (V2)
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — Meera M2 **PASS** (3/3)

**Files created/modified:**
- `CreatorPlatformFeeController.java`, `CreatorPlatformFeeService.java`, `CreatorPlatformFeeDtos.java` (created)
- `CreatorPlatformFeeServiceTest.java` (2), `CreatorPlatformFeeControllerTest.java` (1)

**What:** `GET /api/v1/creator/platform-fee` — global fee from DB via `CreatorContextService`; response `feeBps`/`feePercent`/`source`.

**Reference:** CEO doc §4 P0-V2, `10_CREATOR_PAYMENTS_SPEC.md` §7

**Definition of Done:**
- [x] 200 for authenticated creator; global config only, no PII
- [x] Unit tests (**3/3**)
- [x] Kabir K3 — ✅ **PASS WITH FINDINGS** (batched w/ #28)
- [x] Meera M2 — ✅ **PASS** (3/3 platform-fee tests)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (L-T27-1–5 Low; `source` GLOBAL_DEFAULT only — per-creator override wave must extend resolution without IDOR)

**Unblocks:** Ananya A2 (wallet fee transparency UI) ✅

---

### 28. GET /creator/coupons — Vikram (V3)
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — Kavya **APPROVED**; Kabir K3 **PASS WITH FINDINGS**; Meera M2 — ✅ **PASS** (`CreatorCouponServiceTest` **4/4**, `CreatorCouponControllerTest` **1/1** = **5/5**; BUILD SUCCESS in 10.3s)

**Files created/modified:**
- `CouponCodeRepository.java` — `findByCreatorIdOrderByCreatedAtDesc`
- `CreatorCouponDtos.java`, `CreatorCouponService.java`, `CreatorCouponController.java` (created)
- `CreatorCouponServiceTest.java` (4 tests), `CreatorCouponControllerTest.java` (1 test)

**What:** Self-scoped coupon list for authenticated creator. Read-only.

**Reference:** CEO doc §4 P0-V3

**Definition of Done:**
- [x] Cross-creator isolation unit test (4 service + 1 controller = **5/5**)
- [x] Kavya Kv1 — ✅ **APPROVED**
- [x] Kabir K3 — ✅ **PASS WITH FINDINGS**
- [x] Meera M2 — ✅ **PASS** 2026-07-09 ~20:04 IST (`mvn test -Dtest=CreatorCouponServiceTest,CreatorCouponControllerTest` — **5/5**)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (L-T28-1–7 Low; unbounded list acceptable MVP)

---

### 29. Review entity + review endpoints — Vikram (V4)
**Priority:** P0  
**Deadline:** 2026-07-18  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — all gates through Meera **PASS** ([12/12](484b3e47-d37f-4f80-bcfb-5bda6d3a2b2e))

**Files created/modified:**
- `V41__reviews.sql` (renamed from conflicting V40 — see note), `ReviewerType.java`, `Review.java`, `ReviewRepository.java`, `ReviewDtos.java`, `ReviewService.java`, `CreatorReviewController.java`, `BrandReviewController.java`, `ContentFlagType.java`, `ContentFlag.java`, `ReviewServiceTest.java`

**What:** `Review` entity per §1.2. `POST /creator/reviews` + `POST /brand/reviews` + flag endpoints via `ContentFlag.REVIEW`. **12/12** unit tests.

**Reference:** CEO doc §1.2 + §4 P1-V4

**Definition of Done:**
- [x] Cannot review before COMPLETED; cannot double-review; text sanitized; IDOR tests
- [x] Kavya Kv1 — ✅ **APPROVED** (L-T29-2–5 Low carry-forward)
- [x] Kabir K1 — ✅ **PASS WITH FINDINGS** (M-T29-1/2 pre-prod; no Critical/High)
- [x] Meera M2 — ✅ **PASS** (`ReviewServiceTest` **12/12**, `npm run build` **4592 modules**)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (M-T29-1/M-T29-2 rate limits + duplicate-flag **pre-prod**; `Review.stars` TINYINT Hibernate alignment **pre-prod boot**; spec `14_CREATOR_REVIEWS_SPEC.md`)

**Note:** Parallel V1/V4 both authored `V40__*.sql` — Arjun renamed reviews migration to **V41** (Flyway collision fix).

**Blocks:** Ananya A4 (`creator-reviews` + `brand-reviews` pages)

---

### 30. creator-dashboard home page — Ananya (A1)
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — Kavya **APPROVED** ~20:10 IST; Meera **PASS** ~20:20 IST (`npm run build` **4592 modules**, 57.50s)

**Files created/modified:**
- `src/pages/creator-dashboard.tsx` (created)
- `src/App.tsx` — route `/creator/dashboard`
- `src/pages/creator-login.tsx` — redirect → dashboard
- `src/pages/creator-onboarding.tsx` — completion redirect → dashboard
- `src/components/creator/creator-layout.tsx` — logo → dashboard
- `src/pages/creator-deals.tsx` — exported `mockDeals` for dev rollup

**What:** `/creator/dashboard` — balance, active deals, pending actions rollup, quick links. Login redirect updated.

**Reference:** CEO doc §4 P0-A1

**Definition of Done:**
- [x] All data from existing shipped endpoints only
- [x] Honest empty states
- [x] `npm run build` PASS (Ananya)
- [x] Kavya Kv1 QA — ✅ **APPROVED** (M-1 retry button, M-2 action deep links — non-blocking)
- [x] Meera `npm run build` verify — ✅ **PASS** (4592 modules, 57.50s)
- [x] Kabir — **SKIPPED** (no new backend surface)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (M-1 retry button, M-2 action deep links — non-blocking; zero new backend dep ✅)

---

### 31. Wallet fee transparency UI — Ananya (A2)
**Priority:** P1  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — Kavya **APPROVED** ~20:40 IST (`wiki/errors/creator-wallet-fee-T31-kavya-qa.md`); Meera build **PASS** ~20:20 IST (`npm run build` **4597 modules**, 46.01s)

**Files created/modified:**
- `src/lib/api.ts` — `wallet.platformFee()` → `GET /creator/platform-fee`
- `src/pages/creator-wallet.tsx` — transparency card + dynamic fee labels in payout breakdown

**What:** Platform fee line item sourced from V2 endpoint — no hardcoded 15% in UI.

**Definition of Done:**
- [x] Fee from API not hardcoded
- [x] Kavya Kv1 — ✅ **APPROVED** (L-31-1–3 Low)
- [x] Kabir — **SKIPPED** (frontend-only; #27 K3 covers backend)
- [x] Meera `npm run build` verify — ✅ **PASS** (~20:20 IST — **4597 modules**, 46.01s)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (L-31-1–3 Low; fee from V2 `resolveCreatorFeeBps()` path ✅)

---

### 32. Wire creator-coupons.tsx — Ananya (A3)
**Priority:** P1  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — Kavya **APPROVED** ([A3 QA](3abd904a-bdbc-44e3-873a-9c5b1813ade2)); Meera build **PASS** (2026-07-09 ~20:05 IST)

**Files:** `api.ts`, `useCreatorCoupons.ts`, `creator-coupons.tsx`, `App.tsx`

**What:** Live `GET /creator/coupons` wire; loading/error/empty + retry.

**Definition of Done:**
- [x] Kavya Kv1 — ✅ **APPROVED** (L-T32-1–3 advisory)
- [x] Kabir — **SKIPPED** (T28 gated)
- [x] Meera `npm run build` verify — ✅ **PASS**
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (L-T32-1–3 advisory)

---

### 33. creator-reviews + brand-reviews pages — Ananya (A4)
**Priority:** P1  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:15 IST) — Kavya **APPROVED** ([A4 QA](ddadbd81-85b7-4988-9ce4-0dd3efd65fe7)); Meera build **PASS** (2026-07-09 ~20:05 IST)

**Files:** `api.ts`, `creator-reviews.tsx`, `brand-reviews.tsx`, `collaboration-reviews-panel.tsx`, `star-rating-input.tsx`, `review-card.tsx`, `App.tsx`

**What:** POST review wire for completed collabs; received-reviews tab honest `NOT_IMPLEMENTED` in live (write-only V4 backend).

**Definition of Done:**
- [x] Kavya Kv1 — ✅ **APPROVED** (advisory: no nav link, session-only reviewed state)
- [x] Kabir — **SKIPPED** (T29 K1 covers backend)
- [x] Meera `npm run build` verify — ✅ **PASS** (Vite 6.4.2, **4597 modules**, 29.34s, zero errors; non-blocking `baseUrl` duplicate + chunk-size warnings)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:15 IST (L-T33-1–3 advisory; received-reviews GET deferred per `14_CREATOR_REVIEWS_SPEC.md` §12)

---

## 🟡 P2 ACTIVE (Week 4+ backlog — Tick #29 dispatch)

### 34. Dispute entity v1 + admin resolution stub — Vikram (V5)
**Priority:** P2  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~22:10 IST) — all gates green: Kavya **APPROVED WITH FINDINGS**; Kabir K1 + H-T34-1 re-spot **CLOSED** (0C/0H); Meera M2 **19/19 PASS** (~21:05 IST)  
**What:** Per CEO §1.3 interim policy. `Dispute` entity + `POST /deals/{id}/disputes` + admin-only resolve. One active dispute per collaboration; unreleased escrow freezes on open.  
**Reference:** `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` P2-V5; `15_CREATOR_DISPUTES_SPEC.md` ✅  
**Blocks:** admin dispute console (Phase 2, separate ticket)  
**Definition of Done:**
- [x] Kavya Kv1 — ⚠️ **APPROVED WITH FINDINGS** (~20:45 IST) — `wiki/errors/creator-dispute-T34-kavya-qa.md`
- [x] Kabir K1 — ⚠️ **PASS WITH FINDINGS** (~20:50 IST) — 0 Critical, 1 High, 2 Medium; `wiki/errors/creator-dispute-T34-kabir-redteam.md` — H-T34-1 freeze-release race confirmed; prod blocked until fix
- [x] Vikram H-T34-1 hotfix — freeze-before-save, `ESCROW_BLOCKED_BY_DISPUTE` guards on release/refund, `findByIdForUpdate` row locks, `DisputeEscrowConcurrencyTest` (5 cases)
- [x] Meera M2 — ✅ **PASS** ~21:05 IST (`mvn test -Dtest=DisputeServiceTest,EscrowServiceTest,EscrowServiceReleaseTest,DisputeEscrowConcurrencyTest` — **19/19**: `DisputeServiceTest` 9/9, `EscrowServiceTest` 3/3, `EscrowServiceReleaseTest` 2/2, `DisputeEscrowConcurrencyTest` 5/5 incl. `releaseRejectedWhenCollaborationDisputed`; Failures **0**, Errors **0**, BUILD SUCCESS in 6.7s; pre-hotfix baseline **11/11** ✅ ~20:48 IST)
- [x] Kabir H-T34-1 re-spot — ✅ **CLOSED** (~21:00 IST) — 0 Critical, 0 High; `wiki/errors/creator-dispute-T34-kabir-redteam.md` §H-T34-1 re-spot
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~22:10 IST (M-T34-1/M-T34-2 pre-prod; L-T34-* Low carry-forward; Phase 2 money movement + admin console deferred per `15_CREATOR_DISPUTES_SPEC.md` §13)

### 35. GET /creator/analytics/me/* — Vikram (V6)
**Priority:** P2  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:50 IST) — Vikram ~20:35 IST; Meera M2 **6/6** ✅; Kabir **PASS WITH FINDINGS** ~20:45 IST; Kavya Kv2 §22 documented  
**What:** Principal-scoped creator-self analytics endpoints reusing `AnalyticsController`/`BrandSafetyScoreService`/`AudienceDemographicsJob` data pipeline (B5/B6).  

**Files created/modified:**
- `CreatorAnalyticsController.java`, `CreatorAnalyticsService.java` (created)
- `AnalyticsService.java` — extracted `loadCreator*` helpers + `getCreator*ForProfile` methods (modified)
- `CreatorAnalyticsServiceTest.java` (3), `CreatorAnalyticsControllerTest.java` (3)

**Endpoints (full path `/api/v1/creator/analytics/me/...`):**
- `GET /metrics` — optional `startDate`/`endDate` ISO-8601 instants for trend series
- `GET /scores` — latest `CreatorScore` row (brand-safety fields when present)
- `GET /demographics` — latest `AudienceDemographics` snapshot; graceful `hasData: false` when none yet

**Definition of Done:**
- [x] Creator reads only own data via `CreatorContextService` — no path-param `creatorId`
- [x] Reuses existing analytics data pipeline (no new jobs/entities)
- [x] Unit tests — cross-creator isolation (**6/6** scoped: service 3 + controller 3)
- [x] Kavya QA (Kv2 batch) — ✅ **COMPLETE** ~21:45 IST (`KAVYA_QA_TEST_PLAN.md` §18–§22)
- [x] Kabir security review (creator-self analytics IDOR) — ✅ **PASS WITH FINDINGS** ~20:45 IST (0 Critical, 0 High; `wiki/errors/creator-analytics-T35-kabir-redteam.md`)
- [x] Meera M2 — ✅ **PASS** ~20:35 IST (`mvn test -Dtest=CreatorAnalyticsServiceTest,CreatorAnalyticsControllerTest` — **6/6**: service 3/3 + controller 3/3; BUILD SUCCESS in 4.6s)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:50 IST (L-T35-1–5 Low carry-forward; max date-range span + negative-auth tests **pre-prod**; full `11_CREATOR_ANALYTICS_SPEC` earnings/campaigns/AI-insights wave deferred)

**Reference:** `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` P2-V6, `11_CREATOR_ANALYTICS_SPEC.md`  
**Unblocks:** Ananya A5 (`creator-analytics` page) ✅

### A5. creator-analytics page — Ananya
**Priority:** P2  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:50 IST) — Ananya ~20:45 IST; Meera build **PASS** ~20:43 IST (**4598 modules**, 26.75s)  
**What:** Wire `creator-analytics.tsx` to `GET /creator/analytics/me/*` endpoints.  
**Deps:** Task #35 ✅ SHIPPED

**Files:** `src/pages/creator-analytics.tsx`, `src/lib/api.ts` (`creatorAnalytics.getMy{Metrics,Scores,Demographics}`), `src/hooks/analytics/useCreator{Metrics,Scores,Demographics}.ts`, `src/App.tsx` (`/creator/analytics`)

**Definition of Done:**
- [x] Growth metrics, scores, demographics wired to V6 endpoints
- [x] Loading/error/empty states + `isApiLive()` demo banner
- [x] `npm run build` PASS (Ananya)
- [x] Kabir — **SKIPPED** (frontend-only; #35 K3 covers backend)
- [x] Meera `npm run build` verify — ✅ **PASS** ~20:43 IST (Vite 6.4.2, **4598 modules**, **26.75s**, zero errors; non-blocking `baseUrl` duplicate + chunk-size warnings)
- [x] Kavya Kv2 §22 — ✅ **DOCUMENTED** ~21:45 IST (`KAVYA_QA_TEST_PLAN.md` §22; per-task QA report deferred — test-plan gate only)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:50 IST (L-A5-1 nav link advisory; spec §4 dashboard/earnings/AI wave deferred; `isApiLive()` fail-closed ✅)

### M1. CREATOR_PROGRESS changelog backfill B5/B6 — Meera
**Priority:** P2  
**Status:** ✅ **DONE** — 2026-07-09 ~20:30 IST  
**What:** Backfilled changelog entries for `BrandSafetyScoreService` (CEO B5 / Wave C C3) and `AudienceDemographicsJob` (CEO B6 / Wave B B4) — shipped in prior waves, never cross-referenced in creator tracker. Analytics matrix row updated 5% → 25%; blended % unchanged per CEO §4 P0-M1 DoD.  
**Reference:** CEO doc §4 P0-M1  
**Files:** `wiki/tech/creator/CREATOR_PROGRESS.md`, `TASK_INBOX.md`, `SHARED_CONTEXT.md`

### Priya batch sign-off #26–#33 + Review/Dispute spec docs
**Priority:** P0  
**Status:** ✅ **COMPLETE** — Priya 2026-07-09 ~21:15 IST  
**What:** CTO architectural sign-off on all Week 4 CEO Top 5 tasks (#26–#33 **SHIPPED/CONDITIONAL**); wrote `14_CREATOR_REVIEWS_SPEC.md` + `15_CREATOR_DISPUTES_SPEC.md` per CEO §1.2/§1.3.

### Kv2. KAVYA_QA_TEST_PLAN.md extension — Kavya
**Priority:** P1  
**Status:** ✅ **COMPLETE** — Kavya 2026-07-09 ~21:45 IST  
**What:** Extended test plan per Priya specs + shipped slices #26–#35.  
**Sections added:** §18 Fee transparency (#26/#27/#31), §19 Coupon-read (#28/#32), §20 Reviews (#29/#33), §21 Disputes (#34), §22 Creator-self analytics (#35 brief).  
**Reference:** `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` P1-Kv2; `14_CREATOR_REVIEWS_SPEC.md`, `15_CREATOR_DISPUTES_SPEC.md`  
**Definition of Done:**
- [x] §18 Platform fee transparency (backend + wallet UI)
- [x] §19 Creator coupon read (backend + live wire)
- [x] §20 Collaboration reviews (backend + write-path UI)
- [x] §21 Collaboration disputes (backend v1 stub)
- [x] §22 Creator self analytics (brief; 6/6 unit tests documented)
- [x] TASK_INBOX Kv2 status updated
- [x] SHARED_CONTEXT handoff posted

---

## ✅ COMPLETED TASKS (Week 3)

### 25. Rate Limit Hardening — Vikram
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~18:30 IST) — Vikram 2026-07-09 ~18:00 IST

**Files created/modified:**
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java` — buckets `creator-deliverable-write`, `brand-deliverable-review`, `contract-sign`; JWT-sub keying via `JwtService`
- `influora-api/src/main/resources/application.yml` — `influora.creator.deliverable-write-rate-limit-per-window` (10), `influora.brand.deliverable-review-rate-limit-per-window` (30), `influora.contract.sign-rate-limit-per-window` (10)
- `influora-api/src/test/java/com/influora/security/AuthRateLimitFilterDeliverableContractBucketTest.java` — **8/8**
- `influora-api/src/test/java/com/influora/security/AuthRateLimitFilter{Tracking,Shopify,WooCommerce}BucketTest.java` — constructor fix (`JwtService` null stub)

**Tasks:**
1. ✅ **M-19-2** — `POST /creator/deliverables/{id}/upload|submit|metrics` → `"creator-deliverable-write"` bucket (10/min per JWT `sub`, spec §6.1)
2. ✅ **M-21-1** — `POST /deliverables/{id}/approve|revise` → `"brand-deliverable-review"` bucket (30/min per JWT `sub`)
3. ✅ **L-23-3** — `POST /contracts/{id}/sign` → `"contract-sign"` bucket (10/min per JWT `sub`)
4. ✅ Unit tests: **8/8** new + existing bucket regression tests green

**Reference Spec:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.1; Kabir M-19-2/M-21-1/L-23-3 carry-forward from Tasks #19–#24, #21, #23

**Definition of Done:**
- [x] Three buckets wired in `AuthRateLimitFilter.bucketFor` / `limitFor`
- [x] Per-user keying for authenticated write surfaces (JWT `sub`, IP fallback)
- [x] Config keys in `application.yml` with env overrides
- [x] Unit tests prove throttle + shared-bucket + per-user isolation
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~18:15 IST (`wiki/errors/creator-rate-limit-T25-kavya-qa.md`): M-19-2/M-21-1/L-23-3 buckets wired; JWT-sub keying + IP fallback PASS; shared-bucket semantics PASS; config + env overrides PASS; **L-T25-1** invalid-JWT IP fallback untested; **L-T25-4** per-instance in-memory documented
- [x] Kabir security re-verify — ✅ **PASS WITH FINDINGS** 2026-07-09 ~18:30 IST (`wiki/errors/creator-rate-limit-T25-kabir-redteam.md`): M-19-2/M-21-1/L-23-3 **CLOSED** on primary POST paths; JWT-sub bypass **CLOSED** (HMAC verify); IP fallback abuse **CLOSED** on valid-JWT path; **L-T25-B1** legacy PUT metrics unthrottled; **L-T25-B2** XFF spoof on IP fallback; no Critical/High — sprint gate **GO**
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~18:21 IST (`npm run build` **4591 modules** in **16.97s**; `mvn test -Dtest=AuthRateLimitFilterDeliverableContractBucketTest,AuthRateLimitFilterWooCommerceBucketTest,AuthRateLimitFilterShopifyBucketTest,AuthRateLimitFilterTrackingBucketTest` **22/22** — new **8/8** + regression **14/14**)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~18:30 IST (final blended 100% tick)

**Closes:** M-19-2 (upload + submit + metrics), M-21-1 (brand approve/revise), L-23-3 (contract sign).

**Next:** Week 4 planning — no Week 3 P0s.

### 21. Brand Deliverable Review API — Vikram
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~16:45 IST) — Vikram backend ~23:15 IST; Kavya **APPROVED** ~23:30 IST; Kabir **PASS WITH FINDINGS** ~23:45 IST; Meera **PASS** ~16:37 IST (**11/11** re-verify after fixture fix)

**Files created/modified:**
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` — `findByIdAndWorkspaceId` (collaboration → campaign join-through)
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applyApprove()`, `applyRevision()`
- `influora-api/src/main/java/com/influora/service/BrandDeliverableService.java` (created)
- `influora-api/src/main/java/com/influora/web/BrandDeliverableController.java` (created)
- `influora-api/src/main/java/com/influora/web/dto/deliverable/BrandDeliverableDtos.java` (created — `ReviseRequest`, `ReviewResponse`)
- `influora-api/src/test/java/com/influora/service/BrandDeliverableServiceTest.java` — **9/9**
- `influora-api/src/test/java/com/influora/web/BrandDeliverableControllerTest.java` — **2/2**

**Tasks:**
1. ✅ `POST /api/v1/deliverables/{id}/approve` — brand role; `SUBMITTED`/`RESUBMITTED` → `APPROVED`; sets `approved_at` + `reviewed_at`
2. ✅ `POST /api/v1/deliverables/{id}/revise` — body `{ feedback }`; → `REVISION_REQUESTED`; increments `revisionCount`; stores `reviewNotes`
3. ✅ `BrandContextService.requireBrandWorkspace` + `findByIdAndWorkspaceId` — foreign workspace → uniform `404`
4. ✅ Unit tests: `BrandDeliverableServiceTest` 9/9 + `BrandDeliverableControllerTest` 2/2 = **11/11**

**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §11.4–11.5; `src/lib/api.ts` `deliverables.approve` / `deliverables.requestRevision`

**Definition of Done:**
- [x] Approve/revise state transitions + timestamps
- [x] Brand workspace isolation via campaign join-through (DealService pattern)
- [x] Foreign deliverable probes return `DELIVERABLE_NOT_FOUND` 404
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~23:30 IST (`wiki/errors/creator-deliverable-review-T21-kavya-qa.md`)
- [x] Kabir security review — ✅ **PASS WITH FINDINGS** 2026-07-09 ~23:45 IST (`wiki/errors/creator-deliverable-review-T21-kabir-redteam.md`): IDOR + workspace isolation + state machine **CLOSED**; **M-2 ACTIVE extended** (brand `feedback` raw — `TextSanitizer` before brand review prod); **M-21-1** brand-review rate limit carry-forward; no Critical/High — sprint gate **GO**
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~16:37 IST re-verify (`npm run build` PASS 4587 modules in 21.9s; `mvn surefire:test -Dtest=BrandDeliverableServiceTest,BrandDeliverableControllerTest` **11/11** — `BrandDeliverableServiceTest` 9/9, `BrandDeliverableControllerTest` 2/2; fixture fix: `applyUpload` then `applySubmit` for `SUBMITTED` state)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~16:45 IST

**Pre-prod debt (unchanged):** M-2 `TextSanitizer` on deliverable text ingress incl. brand `feedback` (**required before brand review prod**); ~~M-19-2 creator-deliverable-write rate limit~~ ✅ **CLOSED** Task #25; ~~M-21-1 brand-deliverable-review rate limit~~ ✅ **CLOSED** Task #25.

**Next:** metrics reporting UI.

### 22. M-2 TextSanitizer Hardening — Vikram
**Priority:** P0 (prod blocker)  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Vikram ~17:15 IST; Kabir **PASS WITH FINDINGS** ~17:05 IST — **M-2 + M-9-1 CLOSED**; deal room + brand review text paths prod-unblocked)

**Tasks:**
1. ✅ Create shared `TextSanitizer` util — HTML strip/escape before persistence
2. ✅ Wire into `DealService` messages (M-9-1), `Collaboration` notes (M-2), `CreatorDeliverableService` submit text, `BrandDeliverableService` revise `feedback`
3. ✅ Unit tests with XSS payloads (script tags, event handlers) — **59/59** scoped pass
4. ⏳ Kabir re-review to close M-2 + M-9-1

**Files created/modified:**
- `influora-api/src/main/java/com/influora/common/TextSanitizer.java` (created)
- `influora-api/src/test/java/com/influora/common/TextSanitizerTest.java` (created — **11/11**)
- `influora-api/src/main/java/com/influora/domain/entity/Collaboration.java` — `invite()`/`apply()`/`propose()` notes
- `influora-api/src/main/java/com/influora/service/DealService.java` — `sendMessage`, `persistProposalMessage`, `reject` reason
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `submitForReview` caption/notes/hashtags
- `influora-api/src/main/java/com/influora/service/BrandDeliverableService.java` — `requestRevision` feedback
- Service XSS regression tests: `DealServiceTest` +1, `CreatorCampaignServiceTest` +1, `CreatorDeliverableServiceTest` +1, `BrandDeliverableServiceTest` +1

**Reference:** `wiki/errors/creator-deal-controller-T9-kabir-redteam.md`, `wiki/errors/creator-deliverable-review-T21-kabir-redteam.md`, `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` V-4

**Definition of Done:**
- [x] Shared sanitizer on all free-text ingress paths listed above
- [x] Unit tests pass (**59/59** scoped: TextSanitizer 11 + Deal 7 + Campaign 13 + CreatorDeliverable 18 + BrandDeliverable 10)
- [x] Kabir re-review closes M-2 + M-9-1 — ✅ **PASS WITH FINDINGS** ~17:05 IST

**Next:** Priya sign-off on M-2 hardening slice (optional); L-22-1 upload ingress sanitize (sprint carry-forward).

### 23. E-Sign Backend Slice — Vikram
**Priority:** P0  
**Deadline:** 2026-07-18  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~19:00 IST) — Vikram ~17:00 IST; Kavya **APPROVED** ~17:05 IST; Meera **PASS** ~17:00 IST **16/16**; Kabir **PASS WITH FINDINGS** ~18:00 IST

**Tasks:**
1. ✅ Creator unsigned contracts list endpoint (`GET /contracts/unsigned` + `GET /contracts?dealId=` creator branch)
2. ✅ `POST /contracts/{id}/sign` creator branch end-to-end via `ContractService.recordSignatureForCreator`
3. ✅ Escrow funding prompt on dual signature (`ContractReadyForEscrowEvent` when no FUNDED hold; brand funds via existing `EscrowService.initiateFund`)
4. ✅ Unit tests: creator sign flow + idempotency + cross-creator IDOR rejection (**16/16** `ContractServiceTest` pass)

**Reference:** `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` V-6, Task #10 creator contract path (H-1 closed)

**Definition of Done:**
- [x] Creator can list + sign own contracts
- [x] Cross-creator IDOR blocked
- [x] Unit tests pass
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~17:05 IST (`wiki/errors/creator-esign-T23-kavya-qa.md`)
- [x] Kabir security review — ✅ **PASS WITH FINDINGS** 2026-07-09 ~18:00 IST (`wiki/errors/creator-esign-T23-kabir-redteam.md`): IDOR + replay + escrow abuse **CLOSED**; L-23-1–L-23-4 Low carry-forward; no Critical/High — sprint gate **GO**
- [x] Meera build verify — ✅ **PASS** ~17:00 IST (`ContractServiceTest` **16/16**)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~19:00 IST

**Pre-prod debt (unchanged):** L-23-1 no `ContractControllerTest`; L-23-2 foreign `dealId` / `CANCELLED` filter gap; ~~L-23-3 sign rate limit~~ ✅ **CLOSED** Task #25; L-23-4 terminal status guard; E2 LOW-4 brand relay `role=CREATOR` residual (product decision).

**Next:** metrics reporting UI.

### A-3 / #23c. E-Sign UI Wire — Ananya
**Priority:** P0  
**Deadline:** 2026-07-18  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Ananya 2026-07-09 ~19:30 IST) — Kavya **APPROVED** ~19:30 IST; Kabir **PASS WITH FINDINGS** ~20:00 IST; Meera build **PASS** final ~17:41 IST post pre-prod fixes (**4590 modules** in **1m 7s**; H-A3-1/H-21b-1/M-A3-1); Priya frontend sign-off **SHIPPED/CONDITIONAL** ~20:30 IST

**Files created/modified:**
- `src/lib/api.ts` — `contracts.listUnsigned`, typed `ContractApiRecord`, `contracts.sign` creator branch (no body), `pdfDownloadUrl`, `normalizeDeal`
- `src/lib/creator-contract-mappers.ts` — `mapApiContractToDealStatus`, `dealHasContractFromApi`
- `src/lib/creator-deal-mappers.ts` — `contractId` / `contractStatus` / `escrowFunded` on `CreatorChatDealRoom`
- `src/pages/creator-chat.tsx` — live fetch via `contracts.list`/`get`/`listUnsigned`; sign reconcile; mock gated on `!isApiLive()`
- `src/components/creator/deal-room/creator-deal-contract-tab.tsx` — `api.contracts.sign` + presigned PDF download
- `src/components/creator/deal-room/creator-contract-panel.tsx` — same live wire for timeline sheet

**Tasks:**
1. ✅ Add `api.contracts.listUnsigned` → `GET /contracts/unsigned` (Kavya L-23-6)
2. ✅ Wire `CreatorDealContractTab` / contract panel in `creator-chat.tsx` — `contracts.list` / `contracts.get` / `contracts.sign` when `isApiLive()`
3. ✅ Replace `creator-contract-store.ts` local status with server `contractStatus` / `ContractApiRecord` when live
4. ✅ Loading/error/empty states; mock mode retains `creator-contract-store` demo path + honest `!isApiLive()` gap banners

**Reference:** `wiki/tech/creator/CREATOR_TASK_ASSIGNMENTS_PRIYA.md` A-3; `07_CREATOR_CONTRACTS_SPEC.md`; Task #23 backend (`wiki/errors/creator-esign-T23-kavya-qa.md`)

**Definition of Done:**
- [x] Contract tab fetches real contract by `deal.contractId` / `GET /contracts?dealId=`
- [x] Sign action calls `contracts.sign`; post-success reconcile from API
- [x] Escrow-funded state from `Deal.escrowFunded` (not localStorage store in live mode)
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~19:30 IST (`wiki/errors/creator-esign-A3-kavya-qa.md`): `listUnsigned`/`list`/`get`/`sign`/`pdfDownloadUrl` live wire PASS; mock gating PASS; post-sign reconcile PASS; **H-A3-1** pre-prod — timeline panel `contractStatus ?? 'brand_signed'` default; **M-A3-1** tools tab error masked when status unresolved
- [x] Kabir security review — ✅ **PASS WITH FINDINGS** 2026-07-09 ~20:00 IST (`wiki/errors/creator-esign-A3-kabir-redteam.md`): IDOR + live store forgery + sign body injection **CLOSED** (Task #23 server); PDF presign `noopener,noreferrer` **PASS**; **H-A3-1** pre-prod HIGH — timeline panel Sign before status resolve + synthetic `contractId`; **M-A3-1** error masking; **M-A3-2** live demo PDF fallback on `CONTRACT_PDF_NOT_READY`; no Critical — sprint gate **GO**; timeline Sign **pre-prod NO-GO** until H-A3-1
- [x] Meera build verify — ✅ **PASS** (~17:41 IST final post pre-prod fixes — `npm run build` **4590 modules** in **1m 7s**; H-A3-1 + H-21b-1 + M-A3-1)
- [x] Priya CTO sign-off (frontend slice) — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~20:30 IST

**Pre-prod debt (A-3):** ~~H-A3-1 panel status default (timeline Sign NO-GO)~~ ✅ **CLOSED** 2026-07-09 (Ananya — no `brand_signed` default; Sign gated on `dealContract.id` + resolved status; live `resolveContractId` fallback removed); ~~M-A3-1 tools tab error surfacing~~ ✅ **CLOSED** 2026-07-09 (Ananya — `contractError` Alert when status unresolved); ~~M-A3-2 live demo PDF fallback~~ ✅ **CLOSED** 2026-07-09 (Ananya — honest `CONTRACT_PDF_NOT_READY` message + banner in live mode; no client demo PDF); L-A3-1–L-A3-3 (see Kabir doc); L-A3-1–L-A3-5 Kavya UX (see Kavya doc).

**Depends on:** Task #23 backend ✅ gated. **Blocks:** ~~creator e-sign **full prod UX** (timeline Sign path NO-GO until H-A3-1; Tools tab GO)~~ — timeline Sign path **GO** after H-A3-1 fix; ~~M-A3-2 remains~~ M-A3-2 **CLOSED**.

### 24. Creator Deliverable Metrics API — Vikram
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~18:30 IST) — Vikram 2026-07-09 ~20:45 IST; Kavya **APPROVED** 2026-07-09 ~21:00 IST; Meera **PASS** ~17:50 IST (**29/29**); Kabir **PASS WITH FINDINGS** ~18:00 IST

**Files created/modified:**
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — `POST /creator/deliverables/{id}/metrics`
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `reportMetrics()` via `CreatorContextService` + `DeliverableMetricRepository`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `MetricsReportRequest` / `MetricsReportResponse` / `MetricsPayload`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applyMetricsReport()`
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` — **+6** metrics tests (**24/24** total)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` — **+1** (**5/5** total)

**Tasks:**
1. ✅ `POST /api/v1/creator/deliverables/{id}/metrics` per `09_CREATOR_DELIVERABLES_SPEC.md` §4.6
2. ✅ Creator-scoped via `CreatorContextService` + `findByIdAndCreatorUserId` (no path-param creator id)
3. ✅ State gate: `APPROVED` / `POSTED` / `METRICS_REPORTED` (re-report) → `METRICS_REPORTED`
4. ✅ Persists lean `deliverable_metrics` row (milestone-keyed); engagement rate + `verificationStatus: PENDING`
5. ✅ Unit tests: happy path, APPROVED, invalid state, negative values, missing milestone, foreign 404, controller delegate

**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.6

**Definition of Done:**
- [x] Metrics endpoint + state transition
- [x] Creator ownership isolation
- [x] Unit tests pass (**29/29** scoped: service 24 + controller 5)
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~21:00 IST (`wiki/errors/creator-deliverable-metrics-T24-kavya-qa.md`): `reportMetrics` state gate + milestone-keyed `deliverable_metrics` upsert PASS; `CreatorContextService` + `findByIdAndCreatorUserId` scoping PASS; engagement rate §5.2 PASS; **L-24-1** `reportedDaysAfterPosting` not persisted; **L-24-4** proof key unvalidated (Kabir); **L-24-8** frontend mock rate formula mismatch (Ananya #24b)
- [x] Kabir security review — ✅ **PASS WITH FINDINGS** 2026-07-09 ~18:00 IST (`wiki/errors/creator-deliverable-metrics-T24-kabir-redteam.md`): IDOR + state machine + negative-value gate **CLOSED**; metric inflation (privilege) **CLOSED** (self-declared + `PENDING`); **M-24-1** proof screenshot key unvalidated (Kavya L-24-4 — prod proof display NO-GO); **M-19-2 extended** (no metrics rate limit); no Critical/High — sprint gate **GO**
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~17:50 IST (`npm run build` **4590 modules** in **49.76s**; `mvn test-compile surefire:test -Dtest=CreatorDeliverableServiceTest,CreatorDeliverableControllerTest` **29/29** — `CreatorDeliverableServiceTest` **24/24**, `CreatorDeliverableControllerTest` **5/5**)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~18:30 IST

**Pre-prod debt (Task #24):** M-24-1 proof-key ownership binding (§4.7); ~~M-19-2 creator-deliverable-write rate limit (upload + submit + metrics)~~ ✅ **CLOSED** Task #25.

**Next:** Week 4 planning — no Week 3 P0s.

### 24b. Deliverable Metrics Report UI — Ananya
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~18:30 IST) — Ananya 2026-07-09; Kavya **APPROVED** 2026-07-09 ~22:00 IST; Meera build **PASS** (**4591 modules**)

**Files modified:**
- `src/lib/api.ts` — `creatorDeliverables.reportMetrics` (live `POST /creator/deliverables/{id}/metrics`; mock in `!isLive()`)
- `src/components/creator/deal-room/metrics-report-form.tsx` — metrics dialog (likes/comments/shares/views/reach/impressions/saves + optional days-after-posting)
- `src/pages/creator-chat.tsx` — "Report Metrics" button when `getStatus().actions.canReportMetrics`; refresh list + deals post-success

**Tasks:**
1. ✅ Add `api.creatorDeliverables.reportMetrics` with `MetricsReportRequest` / `MetricsReportResponse` types
2. ✅ Wire `MetricsReportForm` in deal room gated on `canReportMetrics`
3. ✅ Mock path in `!isLive()` (mock `reel-1` POSTED + `canReportMetrics: true`)
4. ✅ `npm run build` PASS

**Definition of Done:**
- [x] Live path calls Vikram Task #24 `POST /creator/deliverables/{id}/metrics`
- [x] Button only visible when at least one deliverable has `canReportMetrics`
- [x] Refresh deliverables list + deal counts after successful report
- [x] Mock path in `!isLive()` mode
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~22:00 IST (`wiki/errors/creator-deliverable-metrics-T24b-kavya-qa.md`): live `reportMetrics` contract PASS; `canReportMetrics` gate + refresh PASS; form validation/error UX PASS; **L-24b-1** mock engagement-rate formula mismatch (demo-only); **L-24b-2** proof screenshot UI deferred (§4.7)
- [x] Meera build verify — ✅ **PASS** 2026-07-09 (`npm run build` **4591 modules** in ~3m 11s)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~18:30 IST

**Pre-prod debt (#24b):** L-24b-1 mock engagement-rate formula mismatch (**demo-only**); L-24b-2 proof screenshot UI deferred (§4.7).

**Next:** Week 4 planning — no Week 3 P0s.

### 24-security. Creator Deliverable Metrics Security Review — Kabir
**Priority:** P0  
**Status:** ✅ COMPLETE — Kabir 2026-07-09 ~18:00 IST. **VERDICT: PASS WITH FINDINGS** — no Critical/High; IDOR + state machine **CLOSED**; M-24-1 proof key unvalidated; M-19-2 extended to metrics POST.

**Scope:**
1. ✅ IDOR on `POST /creator/deliverables/{id}/metrics` — `findByIdAndCreatorUserId` + uniform `404`
2. ✅ State transition abuse — `canReportMetrics` fail-closed; milestone link required
3. ✅ Metric inflation (privilege) — self-declared + `PENDING`; no payout bypass
4. ✅ Proof screenshot key validation (Kavya L-24-4) — **M-24-1 OPEN** until §4.7 proof upload
5. ✅ Rate limiting — **M-19-2 extended**; metrics POST unthrottled at review time → ✅ **CLOSED** Task #25

**Findings doc:** `wiki/errors/creator-deliverable-metrics-T24-kabir-redteam.md`

**Pre-prod (non-blocking sprint):** M-24-1 proof-key ownership binding; ~~M-19-2 creator-deliverable-write rate limit (upload + submit + metrics)~~ ✅ **CLOSED** Task #25.

**Unblocks:** ~~Priya Task #24 CTO sign-off~~ ✅ **SHIPPED/CONDITIONAL** (~18:30 IST); Priya Task #24b CTO sign-off ✅ **SHIPPED/CONDITIONAL** (~18:30 IST)

### 23b. E-Sign Backend Security Review — Kabir
**Priority:** P0  
**Status:** ✅ COMPLETE — Kabir 2026-07-09 ~18:00 IST. **VERDICT: PASS WITH FINDINGS** — no Critical/High; H-1 extended to sign/list; escrow notification-only.

**Scope:**
1. ✅ Cross-creator IDOR on read/sign/list/PDF — `findByIdAndCreatorId` + `principal.getUserId()` only; uniform `404`
2. ✅ Signature replay — `doRecordSignature` already-signed guard + `executeOnce` key `contract-sign:{id}:CREATOR`
3. ✅ Escrow trigger abuse — `ContractReadyForEscrowEvent` → `NotificationListener` only; no `EscrowService` auto-debit
4. ✅ Cross-path race (brand relay `role=CREATOR` vs creator JWT) — shared idempotency key

**Findings doc:** `wiki/errors/creator-esign-T23-kabir-redteam.md`

**Low carry-forward (non-blocking):** L-23-1 no `ContractControllerTest`; L-23-2 foreign `dealId` list test gap; ~~L-23-3 sign rate limit~~ ✅ **CLOSED** Task #25; L-23-4 no terminal status guard. E2 LOW-4 brand relay-sign residual unchanged.

**Unblocks:** Ananya A-3 e-sign UI wire — ✅ Priya Task #23 sign-off complete (~19:00 IST)

### 21b-security. Brand Deliverable Review UI Security Review — Kabir
**Priority:** P0  
**Status:** ✅ COMPLETE — Kabir 2026-07-09 ~18:00 IST. **VERDICT: PASS WITH FINDINGS** — no Critical; H-21b-1 pre-prod inline mock IDs; M-2 closed on brand feedback (Task #22).

**Scope:**
1. ✅ `brand-deliverable-revise-modal` — feedback validation, 2000-char cap, error handling
2. ✅ `deliverable-review-panel` — API wiring, missing `deliverableId` live guard, feedback egress (React text)
3. ✅ `brand-chat` review actions — authz delegated to Task #21 API; H-21b-1 inline mock IDs in live mode
4. ✅ M-2 XSS — backend `TextSanitizer` on `feedback` (Task #22); no `dangerouslySetInnerHTML` in review surfaces

**Findings doc:** `wiki/errors/creator-deliverable-review-T21b-kabir-redteam.md`

**Pre-prod (non-blocking sprint):** ~~H-21b-1 inline timeline mock IDs~~ ✅ **CLOSED** 2026-07-09 (Ananya); M-21-1 rate limit; ~~M-21b-S1 inline error surfacing~~ ✅ **CLOSED** 2026-07-09 (Ananya); L-21b-S1 panel `maxLength`.

**Result:** Priya Task #21b CTO sign-off **SHIPPED/CONDITIONAL** (~18:30 IST)

### 21b. Brand Deliverable Review UI — Ananya
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Ananya 2026-07-09 ~17:30 IST) — Kavya **APPROVED** ~17:05 IST (re-verify); Kabir **PASS WITH FINDINGS** ~18:00 IST; Meera build gate **PASS** (~17:41 IST post H-21b-1 fix — **4590 modules** in **1m 7s**)

**Files created/modified:**
- `src/components/brand/timeline/panels/deliverable-review-panel.tsx` — `api.deliverables.approve` / `requestRevision` when `isApiLive()`; feedback validation; gap banner
- `src/components/brand/timeline/event-cards/deliverable-card.tsx` — `deliverableId` from metadata; `SUBMITTED`/`RESUBMITTED` gate; `onReviewSuccess`
- `src/components/brand/timeline/collaboration-timeline.tsx` — mock deliverable event + post-success status overrides + refresh
- `src/components/brand/timeline/timeline-event.tsx` — `onDeliverableReviewSuccess` pass-through
- `src/lib/types.ts` — `resubmitted` in `deliverableStatus` union
- `src/lib/brand-deliverable-utils.ts` — `isBrandReviewableApiStatus` strict gate (shared with brand-chat slice)
- `src/pages/brand-chat.tsx` — Tools panel `DealDeliverablesTab` + inline timeline approve/revise + `BrandDeliverableReviseModal`
- `src/components/brand/deal-room/deal-deliverables-tab.tsx` — review action buttons + status badges
- `src/components/brand/deal-room/brand-deliverable-revise-modal.tsx` — required feedback dialog (2000 char cap)

**Tasks:**
1. ✅ Wire `api.deliverables.approve` → `POST /deliverables/{id}/approve` in brand deal timeline
2. ✅ Wire `api.deliverables.requestRevision` → `POST /deliverables/{id}/revise` with `{ feedback }` modal/form
3. ✅ Gate actions on deliverable status (`SUBMITTED`/`RESUBMITTED` only); refresh timeline post-success
4. ✅ `isApiLive()` mock gating + honest gap banner when brand list API unavailable

**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §11.4–11.5; `src/lib/api.ts` `deliverables.approve` / `deliverables.requestRevision`

**Definition of Done:**
- [x] Approve + request-revision buttons wired in brand timeline
- [x] Feedback required validation on revise flow
- [x] Post-action timeline refresh
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~17:05 IST (`wiki/errors/creator-deliverable-review-T21b-kavya-qa.md`): `deliverable-review-panel` `approve`/`requestRevision` via `isApiLive()` PASS; SUBMITTED/RESUBMITTED gate PASS; revise feedback required PASS; `CollaborationTimeline` `deliverableOverrides` refresh PASS; mock gap banner PASS; **H-21b-1** pre-prod — `brand-chat` inline timeline mock IDs in live mode; M-21b-1 inline error surfacing
- [x] Kabir security review — ✅ **PASS WITH FINDINGS** 2026-07-09 ~18:00 IST (`wiki/errors/creator-deliverable-review-T21b-kabir-redteam.md`): IDOR + state bypass **CLOSED** (server fail-closed); **M-2 CLOSED** on brand `feedback` ingress (Task #22) + React egress safe; **H-21b-1** pre-prod inline mock IDs; **M-21-1** rate limit carry-forward; no Critical — sprint gate **GO**
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~17:41 IST post H-21b-1 fix (`npm run build` **4590 modules** in **1m 7s**; zero errors)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~18:30 IST

**Next:** metrics reporting UI.

**Pre-prod debt:** M-21-1 brand-review rate limit; ~~**H-21b-1** inline `brand-chat` timeline mock IDs in live mode~~ ✅ **CLOSED** 2026-07-09 (Ananya); ~~M-21b-S1 inline error surfacing~~ ✅ **CLOSED** 2026-07-09 (Ananya); L-21b-S1 panel `maxLength`.

### 20. Deliverable Submit API — Vikram
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~21:30 IST) — Vikram backend + Kavya **APPROVED** 2026-07-09 ~20:45 IST; Meera **PASS** 2026-07-09 ~15:40 IST (**26/26**); Kabir **PASS WITH FINDINGS** 2026-07-09 ~21:15 IST

**Files created/modified:**
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applySubmit()`; builder `revisionCount` + `filesJson`
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `submitForReview()`; `canSubmit()` + `hasUploadedFiles()`
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — `POST /{id}/submit`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `SubmitRequest`, `SubmitResponse`
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` — **17/17** (+6 submit tests)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` — **4/4** (+1 submit test)

**Tasks:**
1. ✅ `POST /api/v1/creator/deliverables/{id}/submit` — optional `finalCaption`, `hashtags`, `notes` on lean row
2. ✅ `CreatorContextService` + `findByIdAndCreatorUserId` ownership gate
3. ✅ State transition: `DRAFT` or `REVISION_REQUESTED` → `SUBMITTED` (or `RESUBMITTED` when `revisionCount > 0`)
4. ✅ Validate `files_json` not empty + `canSubmit` status logic
5. ✅ Response: `deliverableId`, `status`, `message` per spec §4.4
6. ✅ Unit tests: `CreatorDeliverableServiceTest` 17/17 + `CreatorDeliverableControllerTest` 4/4 = **21/21** scoped gate

**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.4  
**Architecture:** Lean `Deliverable` row — no version table; submit updates caption/hashtags/notes on deliverable row

**Definition of Done:**
- [x] Submit transitions status + sets `submitted_at`
- [x] Rejects empty files + invalid states
- [x] Creator isolation via `CreatorContextService`
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~20:45 IST (`wiki/errors/creator-deliverable-submit-T20-kavya-qa.md`)
- [x] Kabir security review — ✅ **PASS WITH FINDINGS** 2026-07-09 ~21:15 IST (`wiki/errors/creator-deliverable-submit-T20-kabir-redteam.md`): IDOR + state transition **CLOSED**; **M-2 ACTIVE extended** (`finalCaption`/`notes`/`hashtags` raw — `TextSanitizer` before brand review prod); **M-19-2 carry-forward** (no submit/upload rate limit); no Critical/High — sprint gate **GO**
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~15:40 IST (`npm run build` 4587 modules in 2m 21s; `mvn test` **26/26** — 17 service + 4 controller + 4 mime + 1 multipart; submit subset **21/21**)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~21:30 IST (submit API backend; Task #20b UI **SHIPPED/CONDITIONAL** ~22:30 IST)

**Pre-prod debt:** M-2 `TextSanitizer` on deliverable text ingress (**required before brand review prod**); M-19-2 creator-deliverable-write rate limit (submit + upload); M-19-3/4 upload prod NO-GO unchanged.

**Next:** Vikram brand review endpoints; M-2 + M-19-2 hardening PR.

### 20b. Deliverable Submit UI — Ananya
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~22:30 IST) — Kavya **APPROVED** 2026-07-09 ~21:15 IST; Meera **PASS** 2026-07-09 ~22:00 IST

**Files modified:**
- `src/lib/api.ts` — `creatorDeliverables.submit` (live `POST /creator/deliverables/{id}/submit`; mock in `!isLive()`)
- `src/pages/creator-chat.tsx` — upload then `submit` when `getStatus().actions.canSubmit`; refresh list + deals
- `src/components/creator/deal-room/deliverable-submission.tsx` — button copy reflects upload + submit flow

**Tasks:**
1. ✅ Add `api.creatorDeliverables.submit` with optional `{ finalCaption, hashtags, notes }`
2. ✅ Wire `DeliverableSubmission` → upload + conditional submit after `getStatus`
3. ✅ Refresh deliverables list + deal counts after successful submit
4. ✅ `npm run build` PASS (4587 modules, ~1m 44s Meera gate)

**Definition of Done:**
- [x] Submit calls Vikram Task #20 endpoint after upload when `canSubmit`
- [x] Hashtags auto-extracted from caption (same as upload)
- [x] Mock path in `!isLive()` mode
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~21:15 IST (`wiki/errors/creator-deliverable-submit-T20b-kavya-qa.md`)
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~22:00 IST (`npm run build` 4587 modules in 1m 44s; submit regression **21/21** — 17 service + 4 controller)
- [x] Priya CTO sign-off Task #20b — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~22:30 IST

**Pre-prod debt (unchanged):** M-2 `TextSanitizer` on deliverable text ingress (**required before brand review prod**); M-19-2 creator-deliverable-write rate limit (submit + upload); upload prod **NO-GO** (M-19-2/3/4).

**Next:** Vikram brand review endpoints; M-2 + M-19-2 hardening PR.

### 19. Deliverable Upload API — Vikram
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~19:00 IST) — Vikram backend + Kavya **APPROVED** + Meera **19/19** + Kabir **PASS** (H-19-1/M-19-1); upload prod **NO-GO** until M-19-2/3/4

**Files created/modified:**
- `influora-api/src/main/resources/db/migration/V37__deliverables.sql` (created)
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` (created — lean row, JSON `files_json`)
- `influora-api/src/main/java/com/influora/domain/enums/DeliverableStatus.java`, `DeliverableType.java` (created)
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` (created — `findByIdAndCreatorUserId`, `findByCollaborationIdOrderBySlotIndexAsc`)
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` (created; M-19-1 MIME sniffing; `listForCollaboration`)
- `influora-api/src/main/java/com/influora/common/MediaMimeSniffer.java` (created — magic-byte MIME detection)
- `influora-api/src/main/resources/application.yml` (H-19-1 `spring.servlet.multipart` 500MB/1GB)
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` (created)
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` (created)
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` (11 tests)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` (3 tests)
- `influora-api/src/test/java/com/influora/config/MultipartConfigTest.java` (created, 1 test)
- `influora-api/src/test/java/com/influora/common/MediaMimeSnifferTest.java` (created, 4 tests)

**Tasks:**
1. ✅ `POST /api/v1/creator/deliverables/{id}/upload` — multipart upload (files, optional thumbnail, caption, hashtags, creatorNotes); R2 via `R2StorageService.putBytes`; MIME allowlist (image/video); 500MB/file + 1GB batch cap
2. ✅ `GET /api/v1/creator/deliverables/{id}/status` — current version, files, review notes, action flags (`canUploadNewVersion`, `canSubmit`, `canReportMetrics`)
3. ✅ `CreatorContextService.requireCreatorProfile` + `findByIdAndCreatorUserId` — never trust path-param creator id
4. ✅ `GET /api/v1/creator/deliverables?collaboration_id=` — deal-room picker (`DeliverableListItem` rows); `CreatorContextService` + `CollaborationRepository.findByIdAndCreatorId` ownership gate
5. ✅ Unit tests: `CreatorDeliverableServiceTest` 11/11 + `CreatorDeliverableControllerTest` 3/3 + `MediaMimeSnifferTest` 4/4 + `MultipartConfigTest` 1/1 = **19/19**
6. ✅ Kabir **H-19-1** — `spring.servlet.multipart` `max-file-size: 500MB`, `max-request-size: 1GB` (env-overridable)
7. ✅ Kabir **M-19-1** — `MediaMimeSniffer` magic-byte validation in `validateMime()`

**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.3  
**Architecture:** Priya `CREATOR_EXEC_PLAN_PRIYA.md` §1.3 — lean `Deliverable` row (no separate version/file tables); `files_json` holds current draft

**Definition of Done:**
- [x] Upload stores files to R2 and sets deliverable `DRAFT`
- [x] Status returns version + action flags
- [x] Creator isolation via `CreatorContextService` + collaboration join-through
- [x] Kavya QA review — ✅ **APPROVED** 2026-07-09 ~16:00 IST (`wiki/errors/creator-deliverable-T19-kavya-qa.md`)
- [x] Kabir security review — ✅ **PASS** 2026-07-09 (`wiki/errors/creator-deliverable-upload-T19-kabir-redteam.md`): IDOR + R2 path traversal **CLOSED**; **H-19-1** ✅ verified closed 2026-07-09; **M-19-1** ✅ verified closed 2026-07-09; M-19-2 no rate limit; M-19-3 in-memory buffering; M-19-4 public URLs vs signed — **prod still NO-GO** until M-19-2/3/4
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~15:00 IST (H-19-1/M-19-1 re-verify: `npm run build` 4587 modules in 39.9s; `mvn test` **15/15** — `MediaMimeSnifferTest` 4/4, `MultipartConfigTest` 1/1, `CreatorDeliverableServiceTest` 8/8, `CreatorDeliverableControllerTest` 2/2). V37 live Flyway apply deferred (unit gate only).
- [x] List API (`GET /creator/deliverables?collaboration_id=`) — ✅ **SHIPPED** 2026-07-09 ~18:30 IST (scoped tests **14/14** deliverable + **19/19** full Task #19 gate; Meera re-verified **19/19** 2026-07-09 ~15:13 IST)
- [x] Ananya upload UI wiring (Task #19b) — ✅ **SHIPPED** 2026-07-09 ~17:15 IST
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~19:00 IST (Tasks #19/#19b/#19c upload+list slice; submit/approve/revise + upload prod hardening deferred)

**Next:** Vikram submit-for-review endpoint; M-19-2 upload rate limit; M-19-3 streaming + M-19-4 presigned URLs before upload prod deploy.

### 19c. Deliverable List API — Vikram
**Priority:** P0  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~19:00 IST) — Kavya **APPROVED** ~15:30 IST; Meera **19/19** + `npm run build` PASS

**Endpoint:** `GET /api/v1/creator/deliverables?collaboration_id={dealId}`

**Files modified:**
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — `list()` handler
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `listForCollaboration()` + `CollaborationRepository` ownership gate
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `DeliverableListItem` DTO
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` — **11/11** (+3 list tests)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` — **3/3** (+1 list test)

**Tasks:**
1. ✅ `GET /creator/deliverables?collaboration_id=` — slot-ordered picker rows (`DeliverableListItem`)
2. ✅ `CreatorContextService.requireCreatorProfile` + `CollaborationRepository.findByIdAndCreatorId` — foreign deal → `DEAL_NOT_FOUND`
3. ✅ Unit tests: `CreatorDeliverableServiceTest` 11/11 + `CreatorDeliverableControllerTest` 3/3 = **14/14**
4. ✅ Meera build verify — ✅ **PASS** 2026-07-09 ~15:13 IST (`npm run build` 4587 modules in 30.2s; full Task #19 scoped gate **19/19** — 11 service + 3 controller + 4 mime + 1 multipart)
5. ✅ Kavya QA review — ✅ **APPROVED** 2026-07-09 ~15:30 IST (`wiki/errors/creator-deliverable-list-T19c-kavya-qa.md`): list ownership gate, blank param 400, `api.ts` live wire, gap banner clears on live list success

**Definition of Done:**
- [x] List API slot-ordered `DeliverableListItem` rows for owned collaborations
- [x] Foreign deal 404 + blank `collaboration_id` 400
- [x] Frontend `listForDeal` live wire (`collaboration_id` query)
- [x] Gap banner clears when live list succeeds (Task #19b contract)
- [x] Kavya QA — ✅ **APPROVED**
- [x] Meera build verify — ✅ **PASS**
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~19:00 IST (bundled with Tasks #19/#19b)

**Next:** Vikram submit-for-review endpoint; Kabir carry-forward M-19-2/3/4 (upload prod NO-GO unchanged).

---

### 19b. Deliverable Upload UI — Ananya
**Priority:** P0  
**Deadline:** 2026-07-16  
**Status:** ✅ **SHIPPED/CONDITIONAL** (Priya CTO sign-off 2026-07-09 ~19:00 IST) — Ananya ~17:15 IST; Kavya APPROVED ~18:00 IST; `npm run build` PASS

**Files modified:**
- `src/lib/api.ts` — `creatorDeliverables.upload`, `getStatus`, `listForDeal` (live `GET /creator/deliverables?collaboration_id=`)
- `src/pages/creator-chat.tsx` — live upload in deal room; deliverables tab from status; honest gap banner when list API missing
- `src/components/creator/deal-room/deliverable-submission.tsx` — image/video only (matches backend MIME allowlist); `submitError` prop; removed debug logs

**Tasks:**
1. ✅ Wire `DeliverableSubmission` → `api.creatorDeliverables.upload` + `getStatus`
2. ✅ Load deliverable picker from `listForDeal` (mock in dev; live wired to Vikram Task #19c list API)
3. ✅ Refresh deal + deliverables tab after successful upload
4. ✅ `npm run build` PASS (4587 modules, ~34s)

**Definition of Done:**
- [x] Upload calls Vikram Task #19 multipart endpoint with `files` part + caption/hashtags query params
- [x] Status refresh after upload
- [x] Live mode fails closed on missing list endpoint (gap banner, submit disabled)
- [x] Kavya QA review (Task #19b) — ✅ **APPROVED** 2026-07-09 ~18:00 IST (`wiki/errors/creator-deliverable-upload-T19b-kavya-qa.md`)
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~15:00 IST (`npm run build` 4587 modules in 39.9s; covered with Task #19 scoped gate)

- [x] Live `listForDeal` wired to `GET /creator/deliverables?collaboration_id=` (2026-07-09)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** 2026-07-09 ~19:00 IST (bundled with Tasks #19/#19c)

**Next:** Vikram submit-for-review endpoint; M-19-2/3/4 before upload prod deploy.

---

## 🔴 COMPLETED TASKS (Week 2)

### 7. Campaign Browse/Apply API — Vikram
**Priority:** P0  
**Deadline:** 2026-07-14  
**Status:** ✅ BACKEND SHIPPED (Vikram, 2026-07-09 ~12:15 IST) — Kavya QA APPROVED; Kabir **PASS WITH FINDINGS** (2026-07-09 ~12:30 IST)

**Files created/modified:**
- `influora-api/src/main/java/com/influora/web/CreatorCampaignController.java` (created)
- `influora-api/src/main/java/com/influora/service/CreatorCampaignService.java` (created)
- `influora-api/src/main/java/com/influora/service/CreatorCampaignMapper.java` (created)
- `influora-api/src/main/java/com/influora/web/dto/creatorcampaign/CreatorCampaignDtos.java` (created)
- `influora-api/src/main/java/com/influora/domain/entity/Collaboration.java` (added `apply()` factory, mirrors `invite()`)
- `influora-api/src/main/java/com/influora/repository/CollaborationRepository.java` (added `findByCampaignIdAndCreatorId`, `findByCreatorIdAndCampaignIdIn`)
- `influora-api/src/main/java/com/influora/repository/CampaignSpecs.java` (added `browsableForCreator`, `applicationDeadlineNotPassed`, `budgetOverlap`)
- `influora-api/src/test/java/com/influora/service/CreatorCampaignServiceTest.java` (created, 12 tests)
- `influora-api/src/test/java/com/influora/web/CreatorCampaignControllerTest.java` (created, 3 tests)

**Tasks:**
1. ✅ `GET /api/v1/creator/campaigns` — browse open (ACTIVE, non-private, deadline-not-passed) campaigns; DB-level `budgetMin`/`budgetMax` filter, in-memory `platform`/`niche` post-filter (Campaign has no dedicated niche/category column — see TECH-STACK.md note that 05 spec entity shapes are a reference, not literal); paginated
2. ✅ `GET /api/v1/creator/campaigns/{id}` — campaign detail; DRAFT and un-invited private campaigns 404 (never leak existence)
3. ✅ `POST /api/v1/creator/campaigns/{id}/apply` — creates `Collaboration` via new `Collaboration.apply()` factory, `source=APPLICATION`, `status=APPLIED`
4. ✅ Idempotency: `UNIQUE(campaign_id, creator_id)` constraint already existed (`V6__creators_collaborations.sql`) — no new migration needed. Sequential duplicate → pre-check 409 `ALREADY_APPLIED`; concurrent-race loser → `DataIntegrityViolationException` caught and translated to the same 409 (same TOCTOU-safe shape as `CreatorDiscoveryService#invite`)
5. ✅ Unit + integration tests: 12 service tests + 3 controller tests, all passing (see below) — true DB-backed integration tests are still blocked on the pre-existing Testcontainers/Docker gap (TECH-STACK.md: "No integration-test infra yet beyond unit tests — flagged debt"), same limitation as every other service in this codebase

**Test results:** `CreatorCampaignServiceTest` 12/12 PASS, `CreatorCampaignControllerTest` 3/3 PASS. Full module regression: `mvn test` → 689 run, 0 failures, 2 pre-existing/unrelated errors (`DatabaseConstraintIntegrationTest` needs Docker, not available in this environment; `MetaOAuthControllerTest` strict-stubbing issue predating this change) — confirmed neither touches any file this task modified.

**Reference Spec:** `wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md`  
**Reference Pattern:** Mirrored `CampaignController`/`CampaignService` brand patterns + `CreatorDiscoveryService#invite`'s TOCTOU-safe duplicate handling; Priya plan §8 task 5

**Definition of Done:**
- [x] Browse returns paginated open campaigns
- [x] Apply creates Collaboration row
- [x] CreatorContextService gates all endpoints (`requireCreatorProfile`, never a path-param creator id)
- [x] Kavya QA APPROVED (2026-07-09) — post-filter pagination semantics verified (page-only total/hasMore when platform/niche active); private 404 vs invited-visible confirmed; hostile tests pass (duplicate, expired, visibility). Findings: `wiki/errors/creator-campaign-browse-T12-kavya-qa.md`
- [x] Kabir security review **PASS WITH FINDINGS** (2026-07-09 ~12:30 IST) — invite-only visibility gating airtight (uniform 404); identity server-derived via `CreatorContextService` (Task #11 consistent); idempotency TOCTOU-safe. **2 MEDIUM (pre-prod, non-blocking sprint gate):** M-1 no apply rate limit (same posture as `invite`, spec §7.2 10/hour); M-2 `message` not XSS-sanitized before `Collaboration.notes` (no active render path yet — fix before Task #9). See `wiki/errors/creator-campaign-apply-T7-kabir-redteam.md`
- [x] Meera build verify PASS (2026-07-09 12:22 IST — `npm run build` + 15/15 scoped backend tests)

**Next:** Vikram Task #9 (DealController); Priya sign-off on campaign slice.

---

### 8. Campaign Browse UI — Ananya
**Priority:** P0  
**Deadline:** 2026-07-15  
**Status:** ✅ SHIPPED (Ananya, 2026-07-09 ~12:30 IST) — browse + detail + apply wired to Vikram #7 API; Kavya QA APPROVED; Meera build verify PASS

**Files created/modified:**
- `src/pages/creator-campaigns.tsx` (created)
- `src/pages/creator-campaign-detail.tsx` (created)
- `src/components/creator/CreatorBrowseCampaignCard.tsx` (created)
- `src/lib/api.ts` (added `creatorCampaigns` client group + `requestWithMeta`)
- `src/App.tsx` (routes `/creator/campaigns`, `/creator/campaigns/:id`)
- `src/components/creator/creator-layout.tsx` (Campaigns nav item)

**Tasks:**
1. ✅ Build campaign browse page mirroring `brand-discover.tsx` filter/search UI
2. ✅ Campaign detail page with apply CTA
3. ✅ Wire to `GET /creator/campaigns` and `POST /creator/campaigns/{id}/apply`
4. ✅ Loading, error, empty states + Framer Motion reveals

**Reference Spec:** `wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md`

**Definition of Done:**
- [x] Browse page loads real campaigns
- [x] Filters work (niche, budget, platform)
- [x] Apply button submits and shows confirmation
- [x] Kavya QA APPROVED (Task #12, 2026-07-09)
- [x] Meera build verify PASS (2026-07-09 12:22 IST — `npm run build` + 15/15 scoped backend tests)

**Next:** Vikram Task #9 (DealController)

---

### 9. DealController + DealMessage Timeline — Vikram
**Priority:** P0  
**Deadline:** 2026-07-18  
**Status:** ✅ SHIPPED — All gates PASS (2026-07-09 ~13:15 IST). Kavya #13 APPROVED; Kabir PASS WITH FINDINGS; Meera **12/12** + frontend build PASS.

**Files created/modified:**
- `influora-api/src/main/java/com/influora/web/DealController.java` (create)
- `influora-api/src/main/java/com/influora/service/DealService.java` (create)
- `influora-api/src/main/java/com/influora/domain/entity/DealMessage.java` (create)
- `influora-api/src/main/java/com/influora/domain/enums/DealMessageKind.java` (create)
- `influora-api/src/main/java/com/influora/domain/enums/DealSenderType.java` (create)
- `influora-api/src/main/java/com/influora/repository/DealMessageRepository.java` (create)
- `influora-api/src/main/java/com/influora/web/dto/deal/DealDtos.java` (create)
- `influora-api/src/main/resources/db/migration/V33__deal_messages.sql` (create)
- `influora-api/src/main/java/com/influora/domain/entity/Collaboration.java` (extend: negotiation helpers)
- `influora-api/src/main/java/com/influora/repository/CollaborationRepository.java` (creator/workspace-scoped lookups)
- `influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java` (`existsByCollaborationIdAndStatus`)
- `influora-api/src/test/java/com/influora/service/DealServiceTest.java` (create — 6 tests)
- `influora-api/src/test/java/com/influora/web/DealControllerTest.java` (create — 6 tests)

**Delivered:**
1. Unified `Collaboration` + `deal_messages` timeline (`text`/`system`/`proposal`/… kinds)
2. Brand path: `BrandContextService.requireBrandWorkspace` + `findByIdAndWorkspaceId` join-through campaign
3. Creator path: `CreatorContextService.requireCreatorProfile` + `findByIdAndCreatorId` — never trust path-param user ids
4. Idempotency on `accept`/`counter` via `IdempotencyService.executeOnce` (auto-derived keys when header absent)
5. Endpoints: `GET/POST /deals`, `GET /deals/:id`, `accept|reject|counter`, `GET/POST /deals/:id/messages`, `messages/read`

**Pre-prod debt:** M-1 apply rate limit; **M-2 ACTIVE** (`Collaboration.notes` now returned via messages seed + `lastMessage`); **M-9-1** (`DealMessage.content` unsanitized) — shared `TextSanitizer` required before prod deploy of deal room.

**Reference:** `wiki/tech/creator/CREATOR_EXEC_PLAN_PRIYA.md` §8 task 4

**Test results:** **Meera 2026-07-09 ~13:15 IST: 12/12 PASS** — `DealControllerTest` 6/6, `DealServiceTest` 6/6 (stub fix landed). Frontend `npm run build` PASS (Vite 6.4.2, 4584 modules).

**Definition of Done:**
- [x] Unified deal room + `deal_messages` timeline
- [x] Creator/brand access isolation via context services + scoped repository queries
- [x] Idempotency on accept/counter
- [x] Kavya QA APPROVED (2026-07-09) — access isolation, idempotency wiring, V33 migration verified; hostile tests partial (foreign accept/brand get/workspace counter). Findings: `wiki/errors/creator-deal-controller-T9-kavya-qa.md`
- [x] Kabir security review **PASS WITH FINDINGS** (2026-07-09 ~14:00 IST) — access isolation + IDOR: PASS; idempotency: PASS; M-2 escalated to ACTIVE (notes render path live); M-9-1 message XSS filed. Findings: `wiki/errors/creator-deal-controller-T9-kabir-redteam.md`
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~13:15 IST (frontend PASS; backend **12/12**; V33 static review OK — Flyway boot not in scoped unit gate)

**Next:** Kavya QA creator-chat live path (Task #15); Ananya `creator-wallet.tsx` — **UNBLOCKED** (Kabir #10 PASS)

---

### 14. Creator Deal Room UI Wiring — Ananya
**Priority:** P0  
**Deadline:** 2026-07-18  
**Status:** ✅ SHIPPED (Ananya, 2026-07-09 ~14:15 IST) — `creator-deals.tsx` + `creator-chat.tsx` wired to Vikram #9 API; `npm run build` PASS

**Files modified:**
- `src/pages/creator-deals.tsx`
- `src/pages/creator-chat.tsx`
- `src/lib/creator-deal-mappers.ts` (new — shared `Deal`/`DealMessage` → UI mappers)
- `src/lib/api.ts` (`normalizeDeal` on list/get/accept/counter responses)

**Wired (live mode):**
1. ✅ `creator-deals.tsx` — `api.deals.list('creator', 'all')` with client-side status chips; accept/reject/counter; error + retry
2. ✅ `creator-chat.tsx` — `api.deals.list('creator')` via `mapDealToChatRoom`
3. ✅ `api.messages.list('creator', dealId)` on deal select + `api.messages.markRead`
4. ✅ `api.messages.send('creator', dealId, content)`
5. ✅ `api.deals.accept` / `.reject` / `.counter` on proposal cards + counter form/dialog
6. ✅ Loading, error, empty states (Alert + Skeleton + retry — pattern from `creator-campaigns.tsx`)
7. ✅ Mock fallback only when `!isApiLive()` — mock deals, timeline, session persisted messages

**Still mock / not in scope:**
- Deliverable submit, shipping address, receipt confirmation, contract sign panel
- Tools panel tabs (Contract / Deliverables / Payments) — local/demo state
- `creator-wallet.tsx` — ✅ SHIPPED (Task #16)

**Blockers:** None — all gates PASS.

**Definition of Done:**
- [x] Kavya QA APPROVED (Task #15, 2026-07-09 ~14:30 IST)
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~13:17 IST (`npm run build` + backend regression **12/12**)

**Next:** Priya sign-off on creator deal-room frontend slice

---

### 16. Creator Wallet UI Wiring — Ananya
**Priority:** P0  
**Deadline:** 2026-07-19  
**Status:** ✅ **SHIPPED** (Ananya, 2026-07-09 ~13:30 IST) — `creator-wallet.tsx` wired to Vikram #10 `WalletController` creator branch; **Priya CTO sign-off SHIPPED** (2026-07-09 ~13:30 IST)

**Files modified:**
- `src/pages/creator-wallet.tsx`
- `src/lib/api.ts` — `WalletSummaryResponse`/`WalletBalanceResponse` types, `wallet.get`/`getBalance`/`withdraw`/`transactions`; NOT_IMPLEMENTED gap for recharge only

**Wired (live mode):**
1. ✅ `api.wallet.get('creator')` — maps `availableBalance`, `escrowLocked`, `pendingPayouts` to earnings hero + sub-cards
2. ✅ Loading skeleton, error Alert + retry (pattern from `creator-campaigns.tsx`)
3. ✅ Mock fallback only when `!isApiLive()` — demo payouts, history, tax docs
4. ✅ Withdraw + transaction history wired in Task #18b (live API paths; min ₹500)

**Still mock / NOT_IMPLEMENTED (live):**
- Payout-method settings, tax doc downloads
- Per-deal payout rows (no list endpoint yet)
- `POST /wallet/recharge` (brand)

**Blockers:** None — withdraw + transaction history wired in Task #18

**Definition of Done:**
- [x] Kabir Task #10 H-1 re-review **PASS**
- [x] Kavya QA APPROVED (Task #17, 2026-07-09 ~15:00 IST)
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~13:24 IST (`npm run build` + `WalletServiceTest` **13/13**)

- [x] Priya CTO sign-off — ✅ **SHIPPED** (2026-07-09 ~13:30 IST)

**Next:** Vikram withdrawal + transaction history endpoints; Ananya M-1 payout-settings gap banner (optional polish)

---

### 17. Creator Wallet Live API QA — Kavya
**Priority:** P0  
**Status:** ✅ **APPROVED** — 2026-07-09 ~15:00 IST

**Tasks:**
1. ✅ Review `creator-wallet.tsx` + `api.ts` wallet group vs `WalletController` Task #10 contract
2. ✅ Verify `isApiLive()` mock gating on summary fetch and tab content
3. ✅ Verify `availableBalance` / `escrowLocked` / `pendingPayouts` field mapping to earnings hero + sub-cards
4. ✅ Verify loading skeleton, error Alert + retry, honest gap banners (withdraw / history / payouts)
5. ✅ `npm run build` PASS
6. ✅ Document — `wiki/errors/creator-wallet-T16-kavya-qa.md`

**Findings doc:** `wiki/errors/creator-wallet-T16-kavya-qa.md`

**Non-blocking carry-over:** M-1 payout Settings dialog shows demo methods in live; M-2 zero hero on fetch error.

**Definition of Done:**
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~13:24 IST (`npm run build` 4587 modules; `WalletServiceTest` **13/13**)

**Next:** Meera build verify after Task #18b

---

### 18. Creator Wallet Withdrawal + Transaction History — Vikram
**Priority:** P0  
**Status:** ✅ **SHIPPED** (2026-07-09 ~16:15 IST) — M-18-1/M-18-2 fixed; **Priya CTO sign-off SHIPPED/CONDITIONAL** (2026-07-09 ~14:00 IST)

**Files created/modified:**
- `influora-api/src/main/java/com/influora/web/WalletController.java` — `POST /wallet/withdraw`, `GET /wallet/transactions` (creator-only via `CreatorContextService`)
- `influora-api/src/main/java/com/influora/service/WalletService.java` — `requestCreatorWithdrawal` (balance + daily count under `findByOwnerIdForUpdate`), `getTransactionsForUser`
- `influora-api/src/main/java/com/influora/service/WalletLedgerService.java` — authoritative `INSUFFICIENT_BALANCE` guard after `findByIdForUpdate` (M-18-1)
- `influora-api/src/main/java/com/influora/repository/WalletRepository.java` — `findByOwnerIdForUpdate` pessimistic owner lock
- `influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java` — `CreatorWithdrawRequest`, `CreatorWithdrawResponse`, `WalletTransactionRowResponse`
- `influora-api/src/main/java/com/influora/repository/WalletTransactionRepository.java` — paginated + daily withdrawal count queries
- `influora-api/src/test/java/com/influora/service/WalletServiceTest.java` — +6 creator withdrawal/transaction tests (**19/19** total; +rate-limit)
- `influora-api/src/test/java/com/influora/service/WalletLedgerServiceTest.java` — created, insufficient-balance-under-lock test (M-18-1)
- `influora-api/src/test/java/com/influora/web/WalletControllerTest.java` — created, 2 delegation tests

**Tasks:**
1. ✅ `POST /wallet/withdraw` — creator branch; `principal.getUserId()` only; ledger double-entry via `WalletLedgerService`; min ₹500 / max ₹1,00,000; 3/day rate limit
2. ✅ `GET /wallet/transactions` — creator-scoped paginated ledger history (`page`/`limit`, `PageMeta` envelope)
3. ✅ Unit tests — `WalletServiceTest` + `WalletLedgerServiceTest` + `WalletControllerTest`
4. ✅ **M-18-1/M-18-2** — balance re-check inside `WalletLedgerService.post()` after pessimistic debit lock; daily withdrawal count moved inside `findByOwnerIdForUpdate` in `requestCreatorWithdrawal`

**Definition of Done:**
- [x] Kavya QA vs `creator-wallet.tsx` + `api.ts` contract — ✅ **APPROVED** 2026-07-09 ~15:45 IST (`wiki/errors/creator-wallet-withdraw-T18-kavya-qa.md`)
- [x] Kabir security review — ✅ **PASS** 2026-07-09 ~14:45 IST initial + ✅ **M-18 CLOSURE PASS** 2026-07-09 ~17:00 IST (`wiki/errors/creator-wallet-T18-kabir-redteam.md` §9)
- [x] M-18-1 balance TOCTOU — ✅ **FIXED** 2026-07-09 ~16:15 IST (`WalletLedgerService.post` + owner lock in `requestCreatorWithdrawal`)
- [x] M-18-2 daily-count TOCTOU — ✅ **FIXED** 2026-07-09 ~16:15 IST (count under `findByOwnerIdForUpdate`)
- [x] Meera scoped `mvn test` build verify — ✅ **PASS** 2026-07-09 ~13:52 IST (`WalletServiceTest` **19/19** + `WalletControllerTest` **2/2** = **21/21**); frontend build PASS
- [x] Meera M-18 re-verify — ✅ **PASS** 2026-07-09 ~14:00 IST (`mvn test -Dtest=WalletServiceTest,WalletLedgerServiceTest,WalletControllerTest` — **22/22**: `WalletServiceTest` 19/19, `WalletLedgerServiceTest` 1/1, `WalletControllerTest` 2/2); `npm run build` PASS (4587 modules, 3m 21s)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** (2026-07-09 ~14:00 IST)

**Findings doc:** `wiki/errors/creator-wallet-withdraw-T18-kavya-qa.md`, `wiki/errors/creator-wallet-T18-kabir-redteam.md`

**Non-blocking carry-forward:** L-18-1 hostile unit tests (max amount, brand 403); L-18-2 DTO min mismatch; L-18-3 idempotency header.

**Next:** Prod deploy of creator withdrawal cleared (L-18-1–L-18-3 sprint carry-forward)

---

### 18b. Creator Wallet Withdraw + History UI — Ananya
**Priority:** P0  
**Status:** ✅ **SHIPPED** (Ananya, 2026-07-09 ~16:00 IST) — **Priya CTO sign-off SHIPPED/CONDITIONAL** (2026-07-09 ~14:00 IST)

**Files modified:**
- `src/pages/creator-wallet.tsx` — live withdraw dialog, transaction history tab, min ₹500
- `src/lib/api.ts` — `wallet.withdraw` + `wallet.transactions` live paths; removed NOT_IMPLEMENTED stubs

**Wired (live mode):**
1. ✅ `api.wallet.withdraw(amount)` — POST with `Idempotency-Key`; refetches summary + history on success
2. ✅ `api.wallet.transactions('creator')` — paginated ledger rows in History tab with period filter
3. ✅ Removed `withdrawLiveBlocked`; min withdrawal aligned to backend ₹500
4. ✅ Honest gap state retained for per-deal payout rows + tax docs only

**Definition of Done:**
- [x] `npm run build` PASS (Vite 6.4.2, 4587 modules, built in 6m 21s)
- [x] Kavya QA Task #18 — pre-wired contract **APPROVED** (2026-07-09 ~15:45 IST)
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~14:00 IST M-18 re-verify (`npm run build` 4587 modules in 3m 21s; backend **22/22** incl. `WalletLedgerServiceTest`)
- [x] Priya CTO sign-off — ✅ **SHIPPED/CONDITIONAL** (2026-07-09 ~14:00 IST)

**Next:** Prod deploy cleared; per-deal payout rows (deferred)

---

### 15. Creator Deal Room Live API QA — Kavya
**Priority:** P0  
**Status:** ✅ **APPROVED** — 2026-07-09 ~14:30 IST (re-QA after Ananya H-1/H-2 fix)

**Tasks:**
1. ✅ Review `creator-deals.tsx` + `creator-chat.tsx` wiring vs `DealController` contract
2. ✅ Verify `isApiLive()` mock gating on list/load/send/accept/reject/counter
3. ✅ Verify shared mappers in `creator-deal-mappers.ts` align with `DealResponse`/`DealMessageResponse`
4. ✅ Hostile paths: error surfaces, empty list, send-in-flight guard, decline URL clear (code review)
5. ✅ Document — `wiki/errors/creator-chat-T15-kavya-qa.md`

**Findings doc:** `wiki/errors/creator-chat-T15-kavya-qa.md`

**Resolved (Ananya):** H-1 `mockTimelineEvents` module scope; H-2 `mapDealToChatRoom` / `mapDealMessageToTimelineEvent`; `npm run build` PASS.

**Non-blocking carry-over:** M-1 counter dialog guard; M-2 proposal metadata; M-3 deliverable count; M-4 earnings breakdown. M-9-1 XSS → Kabir.

**Definition of Done:**
- [x] Kavya QA APPROVED (2026-07-09 ~14:30 IST re-QA)
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~13:17 IST (`npm run build` 4587 modules; backend regression **12/12**)

**Next:** Priya sign-off on creator deal-room frontend slice

---

### 13. DealController QA — Kavya
**Priority:** P1  
**Status:** ✅ COMPLETE — APPROVED 2026-07-09 ~13:45 IST; routed to Kabir (Task #9 security gate)

**Tasks:**
1. ✅ Reviewed `DealController`/`DealService`/`DealMessage`/V33 migration + 12 unit tests
2. ✅ Verified creator/brand access isolation (`CreatorContextService`/`BrandContextService`, no path-param user-id trust)
3. ✅ Verified idempotency wiring on accept/counter; flagged missing replay tests (L-1)
4. ✅ Routed to Kabir — `wiki/errors/creator-deal-controller-T9-kavya-qa.md`

**Findings doc:** `wiki/errors/creator-deal-controller-T9-kavya-qa.md`

---

### 10. Creator Wallet/Contract Access Path — Vikram
**Priority:** P0  
**Deadline:** 2026-07-19  
**Status:** ✅ SHIPPED — All gates PASS + **Priya CTO sign-off SHIPPED** (2026-07-09 ~13:30 IST). Kabir H-1 re-review **PASS**; Meera **26/26** scoped tests + frontend build PASS. Ananya `creator-wallet.tsx` shipped.

**Files modified:**
- `influora-api/src/main/java/com/influora/repository/ContractRepository.java` — `findByIdAndCreatorId` (H-1)
- `influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java` — `findByIdAndCreatorId`
- `influora-api/src/main/java/com/influora/repository/PaymentMilestoneRepository.java` — `sumAmountByCreatorIdAndStatus`
- `influora-api/src/main/java/com/influora/service/ContractService.java` — `getForCreator`, `getPdfDownloadUrlForCreator`
- `influora-api/src/main/java/com/influora/service/WalletService.java` — `getBalanceForUser`, `getSummaryForUser`
- `influora-api/src/main/java/com/influora/service/EscrowService.java` — `getStatusForCreator`
- `influora-api/src/main/java/com/influora/web/WalletController.java` — `UserType.CREATOR` branch
- `influora-api/src/main/java/com/influora/web/ContractController.java` — creator GET + PDF branch
- `influora-api/src/main/java/com/influora/web/EscrowController.java` — creator read-only status branch
- `influora-api/src/test/java/com/influora/service/ContractServiceTest.java` — creator isolation tests
- `influora-api/src/test/java/com/influora/service/WalletServiceTest.java` — creator wallet tests
- `influora-api/src/test/java/com/influora/service/EscrowServiceTest.java` — creator escrow isolation tests

**Tasks:**
1. ✅ Add creator-safe GET paths: own wallet balance, own contracts
2. ✅ Branch on `UserType.CREATOR` vs brand — no changes to money-calculation logic
3. ✅ Verify creator cannot access another creator's rows (unit tests)
4. ✅ H-1 fix: `ContractRepository.findByIdAndCreatorId` join-through query in same change
5. ✅ Kabir H-1 re-review — **PASS** (`wiki/errors/creator-wallet-contract-T10-kabir-redteam.md`)

**Test results:** **Meera 2026-07-09 ~13:10 IST: 26/26 PASS** — `ContractServiceTest` 11/11, `WalletServiceTest` 13/13, `EscrowServiceTest` 2/2. Frontend `npm run build` PASS (Vite 6.4.2, 4587 modules, built in 1m 23s).

**Definition of Done:**
- [x] Creator-safe GET paths: wallet balance, contracts, escrow read-only status
- [x] `UserType.CREATOR` branch isolated from brand paths
- [x] Cross-creator IDOR blocked (unit tests)
- [x] H-1 fix: `ContractRepository.findByIdAndCreatorId` join-through query
- [x] Kabir H-1 re-review **PASS**
- [x] Meera build verify — ✅ **PASS** 2026-07-09 ~13:10 IST (backend **26/26**; frontend PASS)

**Reference:** Priya plan §8 task 6

**Next:** Vikram withdrawal + transaction history endpoints

---

### 16. Task #10 H-1 Re-Review — Kabir
**Priority:** P0  
**Status:** ✅ COMPLETE — Kabir 2026-07-09. **VERDICT: PASS** — H-1 closed; IDOR vectors on contract read + PDF presign blocked.

**Scope:**
1. ✅ `ContractRepository.findByIdAndCreatorId` join-through query matches prescribed H-1 fix
2. ✅ `ContractService.getForCreator` / `getPdfDownloadUrlForCreator` scope via `principal.getUserId()` only
3. ✅ `WalletController` / `EscrowController` creator branches — no client-supplied id trust; escrow mutations remain brand-only
4. ✅ Hostile unit tests cover cross-creator rejection (uniform 404)

**Findings doc:** `wiki/errors/creator-wallet-contract-T10-kabir-redteam.md`

**Unblocks:** Ananya `creator-wallet.tsx` wiring; Meera Task #10 scoped build verify

---

### 11. CreatorContextService Security Review — Kabir
**Priority:** P0 (load-bearing)  
**Status:** ✅ COMPLETE — Kabir 2026-07-09 11:57 IST. **VERDICT: PASS** on `CreatorContextService` isolation (no client-supplied-id trust found, all 4 call sites clean).

**Scope:**
1. ✅ `CreatorContextService` isolation — verified `requireCreatorProfile`/`requireCreator` never trust path params, only `AuthPrincipal`
2. ✅ Pre-review before wallet/contract creator-access-path changes (Task #10) — see go/no-go below
3. ✅ Documented findings in `wiki/errors/creator-context-service-T11-kabir-redteam.md`

**Go/No-Go for Task #10:**
- `WalletController` half: **GO** — safe to implement now (Wallet keyed 1:1 by owner_id; pass `principal.getUserId()` directly, never a request param)
- `ContractController` half: **NO-GO** — **HIGH finding H-1**: `Contract`/`ContractRepository` have no creator-ownership-scoped query (only brand's `findByIdAndWorkspaceId`); a naive creator-facing contract read/PDF-download endpoint is an IDOR. Vikram must add a `findByIdAndCreatorId`-style join-through repository query (mirrors `CollaborationRepository.findByWorkspaceId`) in the **same PR** as Task #10's contract half. Kabir will re-review that diff specifically.
- 2 LOW hardening items filed (non-blocking): no reusable nested-resource ownership helper on `CreatorContextService`; test coverage gap for multi-profile isolation.
- Follow-up (not blocking this review): `CreatorCampaignController`/`CreatorCampaignService` (Task #7) don't exist yet as of this review — will be reviewed once Vikram lands them.

**Reference:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md`
**Findings doc:** `wiki/errors/creator-context-service-T11-kabir-redteam.md`

---

### 12. Campaign Browse QA — Kavya
**Priority:** P1  
**Status:** ✅ COMPLETE — APPROVED 2026-07-09 ~12:45 IST; routed to Kabir (Task #7 security gate)

**Tasks:**
1. ✅ Extend `KAVYA_QA_TEST_PLAN.md` with campaign browse/apply coverage (§16)
2. ✅ Hostile tests verified: cross-creator apply (architectural/Task #11), duplicate apply (sequential + race), expired campaign
3. ✅ Routed to Kabir — `wiki/errors/creator-campaign-browse-T12-kavya-qa.md`

**Findings doc:** `wiki/errors/creator-campaign-browse-T12-kavya-qa.md`

---

## 🟢 COMPLETED TASKS (Week 1)

| # | Task | Owner | Shipped |
|---|------|-------|---------|
| 1 | Backend Auth System | Vikram | 2026-07-09 — Kavya/Kabir/Meera PASS |
| 2 | Profile CRUD Backend | Vikram | 2026-07-09 — Kavya/Kabir/Meera PASS |
| 3 | OAuth Integration Backend | Vikram | 2026-07-09 — Meta OAuth Wave E4 Kabir sign-off |
| 4 | Frontend Auth Pages | Ananya | 2026-07-09 — Kavya PASS |
| 5 | Profile Editor UI | Ananya | 2026-07-09 — Kavya/Meera PASS (11/11 backend tests) |
| 6 | OAuth Connect UI | Ananya | 2026-07-09 — Kavya PASS, Kabir via Wave E4 |
| 7 | Campaign Browse/Apply API | Vikram | 2026-07-09 — Kavya/Kabir/Meera PASS |
| 8 | Campaign Browse UI | Ananya | 2026-07-09 — Kavya/Meera PASS |

---

## 🎯 P0 ASSIGNMENTS (Arjun tick #23 — 2026-07-09 16:50 IST)

| Owner | P0 Task | Status |
|-------|---------|--------|
| **Ananya** | Wire brand deliverable review UI in timeline (Task #21b) | ✅ **SHIPPED/CONDITIONAL** (~17:30 IST; Kavya **APPROVED** ~17:00 IST; Meera **PASS** ~17:00 IST re-verify) |
| **Kavya** | QA Task #21b brand deliverable review UI | ✅ **APPROVED** (~17:00 IST) |
| **Kabir** | Task #21b brand review UI security gate | ✅ **PASS WITH FINDINGS** (~18:00 IST) |
| **Vikram** | M-2 TextSanitizer hardening (Task #22) — prod blocker | ✅ **SHIPPED** (~17:15 IST) — Kabir re-review pending |
| **Vikram** | E-sign backend slice (Task #23) | ✅ **SHIPPED/CONDITIONAL** (Priya ~19:00 IST; Meera **16/16**; Kavya **APPROVED**; Kabir **PASS WITH FINDINGS**) |
| **Ananya** | E-sign UI wire (A-3 / #23c) | ✅ **SHIPPED/CONDITIONAL** (~19:30 IST; Kavya **APPROVED**; Meera **PASS** final ~17:24 IST — **4590 modules** in **22.68s** + **16/16**; Priya **SHIPPED/CONDITIONAL** ~20:30 IST) |
| **Kabir** | Task #23 e-sign backend security gate | ✅ **PASS WITH FINDINGS** (~18:00 IST) |
| **Kavya** | QA Task #23 creator e-sign backend | ✅ **APPROVED** (~17:05 IST) |
| **Kavya** | QA Task A-3 / #23c creator e-sign UI | ✅ **APPROVED** (~19:30 IST) |
| **Kabir** | Task A-3 / #23c creator e-sign UI security gate | ✅ **PASS WITH FINDINGS** (~20:00 IST) |
| **Vikram** | Brand review endpoints (approve/revise deliverables) | ✅ **SHIPPED** (~23:15 IST; Kavya **APPROVED** ~23:30 IST; Kabir **PASS WITH FINDINGS** ~23:45 IST; Meera **PASS** ~16:37 IST **11/11**; Priya **SHIPPED/CONDITIONAL** ~16:45 IST) |
| **Priya** | CTO sign-off Task #23 e-sign backend | ✅ **SHIPPED/CONDITIONAL** (~19:00 IST) |
| **Priya** | CTO sign-off Task A-3 / #23c e-sign UI | ✅ **SHIPPED/CONDITIONAL** (~20:30 IST) |
| **Priya** | CTO sign-off Task #21b brand review UI | ✅ **SHIPPED/CONDITIONAL** (~18:30 IST) |
| **Priya** | CTO sign-off Task #21 brand review API | ✅ **SHIPPED/CONDITIONAL** (~16:45 IST) — **no blocker** |
| **Priya** | CTO sign-off Tasks #19/#19b/#19c deliverables slice | ✅ **SHIPPED/CONDITIONAL** (~19:00 IST) |
| **Priya** | CTO sign-off Task #20 submit API (backend) | ✅ **SHIPPED/CONDITIONAL** (~21:30 IST) |
| **Priya** | CTO sign-off Task #20b submit UI (frontend slice) | ✅ **SHIPPED/CONDITIONAL** (~22:30 IST) |
| **Priya** | CTO sign-off Task #24 metrics API | ✅ **SHIPPED/CONDITIONAL** (~18:30 IST) |
| **Priya** | CTO sign-off Task #24b metrics UI | ✅ **SHIPPED/CONDITIONAL** (~18:30 IST) |
| **Priya** | CTO sign-off Task #25 rate limits + **final blended 100%** | ✅ **SHIPPED/CONDITIONAL** (~18:30 IST) — **LOOP STOPPED** |
| **Vikram** | `POST /wallet/withdraw` + `GET /wallet/transactions` | ✅ **SHIPPED** (~14:30 IST; M-18 fixed ~16:15 IST) |
| **Ananya** | Wire withdraw/transactions in `api.ts` + `creator-wallet.tsx` (Task #18b) | ✅ **SHIPPED** (~16:00 IST) |
| **Ananya** | M-1 payout-settings gap banner (optional polish) | 🟡 Optional |
| **Kavya** | QA Task #21 brand deliverable review | ✅ **APPROVED** (~23:30 IST) |
| **Kabir** | Task #21 brand deliverable review security review | ✅ **PASS WITH FINDINGS** (~23:45 IST) |
| **Kabir** | M-18-1/M-18-2 closure re-sign-off after Vikram fix | ✅ **PASS** (~17:00 IST) |
| **Meera** | Build verify Task #18 wallet endpoints (M-18 re-verify) | ✅ **PASS** (~14:00 IST, **22/22**) |

---

## 📋 UPCOMING (Week 3+)

- E-sign UI (Ananya A-3 / #23c) — ✅ **SHIPPED/CONDITIONAL** (Priya ~20:30 IST; Kavya **APPROVED**; Kabir **PASS WITH FINDINGS**; Meera **PASS**)
- Deliverable upload UI + submit + metrics (Ananya + Vikram) — ✅ **SHIPPED/CONDITIONAL** (Tasks #19–#24b + #25; Priya final sign-off 2026-07-09 ~18:30 IST) — deliverables journey **100%** sprint-gated; ~~M-19-2/M-21-1/L-23-3 rate limits~~ ✅ **CLOSED** Task #25
- Wallet withdrawal flow (Vikram + Ananya) — ✅ **SHIPPED/CONDITIONAL** (Tasks #18/#18b; Priya sign-off 2026-07-09)
- Creator analytics dashboard (Vikram + Ananya)
- Full E2E QA pass (Kavya)
- Final OWASP audit (Kabir)

---

## 🚨 BLOCKERS

1. **Pre-prod debt (non-blocking sprint)** — M-1 apply rate limit; M-9-1 deal message XSS; ~~M-19-2 creator-deliverable-write rate limit~~ ✅ **CLOSED** Task #25; ~~M-21-1 brand-deliverable-review rate limit~~ ✅ **CLOSED** Task #25; ~~L-23-3 contract sign rate limit~~ ✅ **CLOSED** Task #25; ~~M-A3-2 live demo PDF fallback~~ ✅ **CLOSED**; **M-24-1** proof-key ownership binding (§4.7); M-1 wallet payout Settings dialog shows demo methods in live (Ananya optional fix); **M-19-3/4 upload prod NO-GO** (streaming, presigned URLs — sprint carry-forward); **L-24b-1** mock engagement-rate formula (**demo-only**).

---

## 📊 PIPELINE STATUS

```
Week 3: Deliverables journey **[100% COMPLETE]** — upload→metrics end-to-end gated; Priya final sign-off ~18:30 IST

├─ Vikram (Backend)
│  ├─ [✅] Campaign browse/apply API (Task #7) — SHIPPED, all gates PASS
│  ├─ [✅] DealController (Task #9) — Kavya + Kabir + Meera **12/12 PASS**
│  ├─ [✅] Wallet/contract creator path (Task #10) — Kabir H-1 **PASS** + Meera **26/26 PASS**
│  ├─ [✅] Deliverable upload + list API (Tasks #19/#19c) — Kavya + Kabir + Meera **19/19 PASS**; Priya **SHIPPED/CONDITIONAL**
│  ├─ [✅] Deliverable submit API (Task #20) — Kavya + Kabir + Meera **26/26 PASS**; Priya **SHIPPED/CONDITIONAL**
│  ├─ [✅] Creator e-sign backend (Task #23) — Kavya + Kabir + Meera **16/16 PASS**; Priya **SHIPPED/CONDITIONAL** (~19:00 IST)
│  └─ [✅] Deliverable metrics API (Task #24) — Kavya + Kabir + Meera **29/29 PASS**; Priya **SHIPPED/CONDITIONAL** (~18:30 IST)
│  └─ [✅] Rate-limit hardening (Task #25) — Vikram **SHIPPED**; Kavya **APPROVED**; Kabir **PASS WITH FINDINGS**; Meera **PASS** **22/22** (~18:21 IST)
│
├─ Ananya (Frontend)
│  ├─ [✅] Campaign browse UI (Task #8)
│  ├─ [✅] Deal room chat wiring (Task #14) — Kavya #15 APPROVED
│  ├─ [✅] `creator-wallet.tsx` (Task #16/#18b) — withdraw + history live wired; Priya **SHIPPED/CONDITIONAL**
│  └─ [✅] Deliverable upload UI (Task #19b) — live upload + list picker; Priya **SHIPPED/CONDITIONAL**
│  └─ [✅] Deliverable submit UI (Task #20b) — upload + submit wired; Priya **SHIPPED/CONDITIONAL** (~22:30 IST)
│  └─ [✅] Brand deliverable review API (Task #21) — Vikram **SHIPPED** (~23:15 IST); Priya **SHIPPED/CONDITIONAL** (~16:45 IST)
│  └─ [✅] Brand deliverable review UI (Task #21b) — approve/revise wired; Kavya **APPROVED**; Meera build **PASS** (~17:00 IST re-verify — **4589 modules** in **30.05s**)
│  └─ [✅] E-sign UI wire (A-3 / #23c) — Kavya **APPROVED**; Kabir **PASS WITH FINDINGS**; Priya **SHIPPED/CONDITIONAL** (~20:30 IST)
│  └─ [✅] Deliverable metrics UI (Task #24b) — Kavya **APPROVED**; Meera **PASS** (**4591 modules**); Priya **SHIPPED/CONDITIONAL** (~18:30 IST)
│
├─ Kavya (QA)
│  ├─ [✅] Campaign browse QA (Task #12)
│  ├─ [✅] DealController QA (Task #13)
│  ├─ [✅] Creator-chat live path QA (Task #15) — **APPROVED** (M-1–M-4 non-blocking)
│  ├─ [✅] Creator-wallet live path QA (Task #17) — **APPROVED** (M-1–M-2 non-blocking)
│  └─ [✅] Creator-wallet withdraw/history QA (Task #18) — **APPROVED**
│  ├─ [✅] Deliverable upload + list QA (Tasks #19/#19b/#19c) — **APPROVED**
│  └─ [✅] Deliverable submit API QA (Task #20) — **APPROVED** (~20:45 IST)
│  └─ [✅] Deliverable submit UI QA (Task #20b) — **APPROVED** (~21:15 IST)
│  └─ [✅] Brand deliverable review QA (Task #21) — **APPROVED** (~23:30 IST)
│  └─ [✅] Creator e-sign backend QA (Task #23) — **APPROVED** (~17:05 IST)
│  └─ [✅] Creator e-sign UI QA (A-3 / #23c) — **APPROVED** (~19:30 IST)
│  └─ [✅] Deliverable metrics API QA (Task #24) — **APPROVED** (~21:00 IST)
│  └─ [✅] Deliverable metrics UI QA (Task #24b) — **APPROVED** (~22:00 IST)
│  └─ [✅] Rate-limit hardening QA (Task #25) — **APPROVED** (~18:15 IST)
│
├─ Kabir (Security)
│  ├─ [✅] DealController review (Task #9) — PASS WITH FINDINGS
│  ├─ [✅] H-1 re-review on Vikram #10 — **PASS**
│  ├─ [✅] Task #18 withdraw/transactions — **PASS** (M-18-1/M-18-2 closed 2026-07-09)
│  ├─ [✅] M-18 closure re-sign-off — **PASS** (~17:00 IST)
│  └─ [✅] Task #20 deliverable submit API — **PASS WITH FINDINGS** (~21:15 IST)
│  └─ [✅] Task #21 brand deliverable review API — **PASS WITH FINDINGS** (~23:45 IST)
│  └─ [✅] Task #21b brand deliverable review UI — **PASS WITH FINDINGS** (~18:00 IST)
│  └─ [✅] Task #23 e-sign backend — **PASS WITH FINDINGS** (~18:00 IST)
│  └─ [✅] Task A-3 / #23c e-sign UI — **PASS WITH FINDINGS** (~20:00 IST)
│  └─ [✅] Task #24 creator deliverable metrics API — **PASS WITH FINDINGS** (~18:00 IST)
│
├─ Priya (Architecture)
│  ├─ [✅] Task #18 withdrawal slice CTO sign-off — **SHIPPED/CONDITIONAL** (~14:00 IST)
│  ├─ [✅] Tasks #19/#19b/#19c deliverables slice CTO sign-off — **SHIPPED/CONDITIONAL** (~19:00 IST)
│  ├─ [✅] Task #20 submit API CTO sign-off — **SHIPPED/CONDITIONAL** (~21:30 IST)
│  ├─ [✅] Task #20b submit UI CTO sign-off — **SHIPPED/CONDITIONAL** (~22:30 IST)
│  ├─ [✅] Task #21 brand review API CTO sign-off — **SHIPPED/CONDITIONAL** (~16:45 IST)
│  ├─ [✅] Task #21b brand review UI CTO sign-off — **SHIPPED/CONDITIONAL** (~18:30 IST)
│  └─ [✅] Task #23 e-sign backend CTO sign-off — **SHIPPED/CONDITIONAL** (~19:00 IST)
│  └─ [✅] Task A-3 / #23c e-sign UI CTO sign-off — **SHIPPED/CONDITIONAL** (~20:30 IST)
│  └─ [✅] Task #24 metrics API CTO sign-off — **SHIPPED/CONDITIONAL** (~18:30 IST)
│  └─ [✅] Task #24b metrics UI CTO sign-off — **SHIPPED/CONDITIONAL** (~18:30 IST)
│  └─ [✅] Task #25 rate limits + **FINAL blended 100% CTO sign-off** — **SHIPPED/CONDITIONAL** (~18:30 IST) · **LOOP STOPPED**
│
└─ Meera (Build)
   ├─ [✅] Campaign slice — PASS
   ├─ [✅] DealController slice — **12/12 PASS** (~13:15 IST)
   ├─ [✅] Task #10 wallet/contract slice — **26/26 PASS** (~13:10 IST)
   ├─ [✅] Task #15 creator deal-room frontend slice — **PASS** (~13:17 IST)
   ├─ [✅] Task #17 creator wallet slice — **PASS** (~13:24 IST — `npm run build` + **13/13**)
   ├─ [✅] Task #18 creator wallet withdrawal slice — **PASS** (~14:00 IST M-18 re-verify — `npm run build` + **22/22**)
   ├─ [✅] Task #19 deliverables slice — **PASS** (~15:13 IST — `npm run build` + **19/19**); Priya **SHIPPED/CONDITIONAL**
   ├─ [✅] Task #20 deliverable submit API — **PASS** (~15:40 IST — `npm run build` + **26/26**; submit subset **21/21**)
   └─ [✅] Task #20b deliverable submit UI — **PASS** (~22:00 IST — `npm run build` 4587 modules in 1m 44s; submit regression **21/21**)
   └─ [✅] Task #21 brand deliverable review API — **PASS** (~16:37 IST re-verify — `npm run build` PASS; scoped **11/11** after Vikram fixture fix)
   ├─ [✅] Task #21b brand deliverable review UI — **PASS** (~17:41 IST post H-21b-1 — `npm run build` **4590 modules** in **1m 7s**)
   └─ [✅] Task #23 creator e-sign backend — **PASS** (~17:00 IST — `ContractServiceTest` **16/16**)
   └─ [✅] Task #23c creator e-sign UI (A-3) — **PASS** (~17:41 IST post pre-prod fixes — `npm run build` **4590 modules** in **1m 7s**; H-A3-1 + H-21b-1 + M-A3-1)
   └─ [✅] Pre-prod fixes (H-A3-1, H-21b-1, M-A3-1) — **PASS** (~17:41 IST — `npm run build` **4590 modules** in **1m 7s**)
   └─ [✅] Task #24 creator deliverable metrics API — **PASS** (~17:50 IST — `npm run build` **4590 modules** in **49.76s**; scoped **29/29** — service **24/24**, controller **5/5**)
   └─ [✅] Task #24b creator deliverable metrics UI — **PASS** (`npm run build` **4591 modules** in ~3m 11s)
   └─ [✅] Task #25 rate-limit hardening — **PASS** (~18:21 IST — `npm run build` **4591 modules** in **16.97s**; scoped **22/22** — `AuthRateLimitFilterDeliverableContractBucketTest` **8/8** + regression **14/14**)
```

---

**Arjun:** Week 4 CEO Top 5 **COMPLETE** — Tick #30 dispatched. PID **29880** alive. Discovery (#36/#37) + K6 OWASP + Kv3 E2E in flight.

---

## 🔁 LOOP STATUS — TICK #30 ARMED (Arjun, 2026-07-09 ~21:15 IST)

| Item | Status |
|------|--------|
| **Week 3 sprint blended** | **100%** — Priya CTO final sign-off (unchanged) |
| **Week 4 CEO Top 5 blended** | **100%** — Tasks #26–#33 gated + Priya batch sign-off **COMPLETE** |
| **Full-platform blended** | **~81%** — Tick #30 work in flight (Discovery/OWASP/E2E) |
| **Agent loop** | ✅ **ARMED** — PID **29880** alive, detached 30min heartbeat |
| **Tick #30** | Discovery #36/#37 + Kabir K6 + Kavya Kv3 dispatched |
| **Next wake** | ~**21:35 IST** |

- **Tick #30 (~21:15 IST):** Loop PID **29880** confirmed alive (last heartbeat 21:04:48). CEO directive: Discovery (10%) + OWASP (30%) + E2E QA (10%). Dispatched parallel: Vikram #36, Ananya #37, Kabir K6, Kavya Kv3. Cursor session heartbeat re-armed.
- **Tick #29 (~20:26 IST):** Loop PID **29880** confirmed alive (started 19:34:47; last heartbeat 20:04:48). Dispatched P2 parallel: Priya batch sign-off #26–#33 + Review/Dispute specs; Vikram V5 Task #34 Dispute; Vikram V6 Task #35 creator-self analytics; Meera M1 changelog backfill. Ananya A5 blocked on #35; Kavya Kv2 queued after Priya specs.
- **Tick #28 (~20:20 IST):** Audited #26–#33; closed Meera #31; full-platform **~71% → ~78%**.
