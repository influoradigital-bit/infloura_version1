# 🏗️ Influora — CTO Consolidated Code Audit (Done vs Pending)

> **Date:** 2026-07-12
> **Branch:** `feature/analytics-platform`
> **Author:** Priya (CTO) — synthesis of 6 parallel specialist audits
> **Method:** **CODE ONLY.** No `.md` status docs were read or trusted. Every claim is backed by a real command run or a `file:line` source read. Read-only — no application code was modified.
> **Contributors:** Ananya (frontend), Vikram (backend), Arjun (AI service + 3-tier integration), Kabir (security seams), Meera (gate execution), Kavya (FE↔BE contract matrix).

---

## 0. TL;DR

- **Frontend:** ✅ Typechecks clean, builds clean. Brand/creator marketplace flows are genuinely wired to live endpoints. Admin is split (some live, some mock). **The headline Meera AI chat UI is NOT wired** — it runs on scripted `setTimeout`, never calls the real stream.
- **Backend (`influora-api`):** ❌ **RED — does not compile.** 12 confirmed blockers (list not exhaustive; javac capped at 100 errors). Only the **Admin** backend slice compiles clean end-to-end. Brand, Creator, and the AI-bridge are all blocked.
- **AI service (`influora-ai`):** ✅ Production-grade — real Claude Sonnet 4.5 streaming, hardened HMAC/JWKS auth, injection defenses actually invoked. **153/153 tests pass**, but one test module fails to even collect (broken import) → CI red.
- **Integration:** The Python↔Claude↔Spring mesh is real and correctly secured. The two breaks that matter: (a) the shipped chat UI is disconnected from that working pipeline, and (b) the whole backend doesn't compile, so nothing runs end-to-end **right now**.

**Honest status: strong frontend + a genuinely working AI service, bolted onto a backend that currently does not build. Not shippable end-to-end today.**

---

## 0b. 🔄 UPDATE — re-verified later same day (2026-07-12)

Owners closed a large slice of the P0/P1 list. Re-ran all gates + code checks. **Net: the backend main source now compiles (all 12 blockers fixed) and the flagship chat is wired — but running the tests exposed a NEW blocker: the test suite itself does not compile.**

### ✅ Now DONE (verified in code / by command)
| Item | Was | Now | Evidence |
|---|---|---|---|
| **Backend main compile (blockers #1–#12)** | ❌ RED, 370+ errors | ✅ **`mvn -o compile` 0 errors, 786 classes** | pom has `spring-boot-starter-data-redis` + `openpdf`; `CreatorProfile.newForUser`(:135)/`getUsername`(:164)/`username`(V32); `MediaMetric.getCaption`(:128, V20260712120000); `Campaign.Builder.campaignType`(:248). All 12 resolved (a single unresolved symbol would still fail). |
| **Meera chat UI wired to live pipeline** | ❌ setTimeout only, `useMeeraStream` dead | ✅ **Live** — imports `useMeeraStream`(:10,104), `.startSession()`(:138), `.sendTurn`(:217) | `MeeraChatPanel.tsx` — `setTimeout` now only in the mock-mode fallback path |
| **Admin API base-path drift** | ❌ `/api/v1/admin` vs `/admin` | ✅ **Aligned** | `application.yml:25` `context-path: /api/v1` + `api-contracts.ts:58` `API_BASE = /api/v1/admin` |
| **AI test collection** | ❌ 1 broken import (`_neutralize_angle_brackets`) | ✅ **186 passed** (was 153 + collection error) | `pytest tests/` exit 0 |
| **Razorpay webhook secret boot-validation** | ❌ public placeholder, unvalidated (MED-HIGH) | ✅ **Fixed** — `validateRazorpayWebhookSecret` fails closed in non-dev | `SecretsStartupValidator.java:45-50,92-95` |
| Frontend tsc / build | ✅ | ✅ still clean | `tsc --noEmit` exit 0 |

### ↩️ CORRECTION (was mislabeled as pending)
- **Python `campaign_type` missing `STANDARD` is BY DESIGN, not a bug.** `schemas.py:100-109` documents STANDARD is *deliberately excluded* so the AI cannot propose it (server-side default only). My earlier "add STANDARD" recommendation was wrong — **removed from pending.** The CI schema-check stays report-only for this exact reason.

### ❌ STILL OPEN / newly surfaced
| Item | Severity | Evidence |
|---|---|---|
| **NEW: backend TEST suite does not compile → `mvn -o test` BUILD FAILURE** | **P0 (blocks all backend tests)** | (a) missing `testcontainers` test dep — `AbstractIntegrationTest.java:6-9` `org.testcontainers.* does not exist`; (b) test↔prod signature drift not reconciled after the refactor — `IdempotencyService.executeOnce`, `WalletService` ctor, `ConfirmLaunchExecutor`/`CreateCampaignExecutor` ctors, `MetaOAuthController`/`MetaOAuthStateStore.issue`/`MetaOAuthService`, `MeeraSessionServiceTest` all fail to compile |
| Admin MFA still opt-in, no lockout | HIGH | `AdminAuthService.java:133` `if (admin.isMfaEnabled())` — unchanged |
| Admin moderation + campaign-monitoring controllers | P2 | Still absent → `useFlagQueue.ts:15,31` + `useCampaignList.ts:6,24` still MOCK |
| Product stubs (store-integration status/disconnect, portfolio sync, fake-follower NLP, payout KYC, content-performance, brand review inbox, affiliate per-campaign rates, prod AI-spend ceiling) | P2 | Not re-verified this pass — assume still open unless owner confirms |
| Admin refresh-token-in-JSON-body, cookie `Secure` flag | MED | Not re-verified this pass |

### 📊 Revised gate line (2026-07-12, later)
`FE tsc ✅ · FE build ✅ · backend mvn COMPILE ✅ (was ❌) · backend mvn TEST ❌ (test-compile, was "skipped") · AI pytest 186 ✅ (was 153 +err)`

**Revised scores:** Backend **45 → 68** (compiles + all blockers cleared; still can't run tests). AI service **80 → 85** (suite fully green). Integration **35 → 58** (chat wired, admin path fixed). **Overall verifiable 62 → ~72.** Deep process **55 → 60** (real fixes landed and were verifiable; but test suite drifting out of sync with prod is the same "green-by-faith" smell — CI still not gating).

**Single most important remaining P0:** add the `testcontainers` test dependency to `pom.xml` and reconcile the ~6 drifted test files with the refactored production signatures, so `mvn test` can actually run. Until then the 113 backend test files still cannot execute — "compiles" is not yet "tested."

---

## 1. Ground-truth gate results (Meera — commands actually executed)

| Gate | Command | Exit | Result |
|---|---|---|---|
| Frontend typecheck | `npx tsc --noEmit -p tsconfig.json` | **0** | ✅ PASS — 0 errors |
| Frontend build | `npm run build` | **0** | ✅ PASS — 3940 modules, `dist/` emitted (~17s); pre-existing chunk-size + duplicate-`baseUrl` warnings only |
| **Backend compile** | `mvn -o compile` (bundled 3.9.9) | **1** | ❌ **RED** — ~370 `[ERROR]` lines, ≥12 files (javac 100-error cap hit) |
| Backend tests | `mvn -o test` | — | **SKIPPED** — cannot run, compile red |
| AI tests | `pytest tests/` | **2** | ❌ 1 collection error; excluding it → **153 passed** |
| Env | — | — | JDK 21.0.11, Python 3.13.3, **no system Maven** (only bundled `.tools/apache-maven-3.9.9`) |

---

## 2. Overall scores (evidence-based)

| Dimension | Score | Rationale |
|---|---:|---|
| **Total project — verifiable** | **62 / 100** | Enormous built surface, but "built" ≠ "green". Backend red blocks E2E. |
| Frontend | 82 | tsc+build clean; admin partly mock; Meera chat UI disconnected |
| Backend | 45 | Vast (49 controllers/~78 services/~55 entities) but won't compile |
| AI service | 80 | Real, tested, hardened; docked for 1 broken test import + no prod AI-spend ceiling |
| Integration / E2E | 35 | Unverifiable while backend is red; admin path drift; chat UI unwired |
| **Deep process** | **55 / 100** | Good role chain + honest caveats, but recurring over-claim; backend never locally compiled before "done"; CI not gating merges (broken import + non-compiling branch both landed) |

---

## 3. DONE vs PENDING — BRAND

### Done (frontend live + backend logic complete)
- Auth (login/register/email-OTP/logout), onboarding (company/complete/KYC) — FE↔BE **MATCH**.
- Campaigns CRUD + duplicate; tracking links/UTM + coupons; dashboard actions/pipeline.
- Deal Room: propose/accept/reject/counter, messages (send/read), deliverables (approve/revise) — ownership-scoped.
- Contracts generate/sign; Wallet get/top-up; Escrow fund/release/refund (full state machine); Shopify/Woo connect + webhooks; Brand reviews create; Brand disputes read.

### Pending (BRAND)
| Item | Where | State |
|---|---|---|
| **Contract PDF generation won't compile** | `ContractPdfService.java:7-188` | ❌ Blocker #2 — `com.lowagie`/openpdf dep missing (~60 cascading errors) |
| **Contract-signed event won't compile** | `ContractService.java:447,458` | ❌ Blocker #9 — `ContractSignedEvent` record 5 params, called with 7 |
| **Campaign create won't compile** | `CampaignService.java:127` | ❌ Blocker #8 — `Campaign.Builder.campaignType(...)` missing |
| Store integrations status/disconnect | `api.ts:1524-1530` | FE returns `NOT_IMPLEMENTED`; no backend route |
| Brand review inbox (`listReceived`) | `api.ts:1612` | FE-ONLY; no `GET /brand/reviews/received` |
| Creator demographics (brand view) | `api.ts:1342` | FE-ONLY; backend deliberately not implemented |
| Content-performance-per-post | `api.ts:1388`, `useContentPerformance.ts` | Stub; no backend endpoint |
| Brand disputes list | `api.ts:1945` | Derived client-side from `/deals`, not a real enriched read |
| Payout execution | `PayoutService.java:240-270` | PARTIAL — creator user-id used as placeholder fund-account ref; `confirmExecuted` a documented no-op |
| Content-moderation approval action | `ApprovalWorkflowService.java:173-179` | Throws `APPROVAL_ACTION_NOT_IMPLEMENTED` (501) |
| Brand help copy | `brand-help.tsx:21-48` | Placeholder copy (awaiting content) |

---

## 4. DONE vs PENDING — CREATOR

### Done
- Creator auth login; shared deal/message/contract/deliverable flows; wallet withdraw/transactions; self-analytics (metrics/scores/demographics) FE↔BE **MATCH**; reviews create/received; Meta OAuth authorize/callback; coupons list; affiliate earnings list; campaigns browse/apply; deliverables list/upload/submit/proof; self profile get/patch; portfolio public view.

### Pending (CREATOR) — **this bucket carries the most compile damage**
| Item | Where | State |
|---|---|---|
| **Creator signup won't compile** | `AuthService.java:253` | ❌ Blocker #7 — `CreatorProfile.newForUser(...)` factory missing (**breaks main signup path**) |
| **Creator analytics won't compile** | `CreatorAnalyticsService.java:5,33-45` | ❌ Blocker #3 — `CreatorDemographicsResponse` DTO + 3 service methods missing |
| **Meta connection status/disconnect won't compile** | `MetaConnectionService.java:13-113` | ❌ Blocker #4 — both response DTOs missing |
| **Coupon list won't compile** | `CreatorCouponService.java:53` | ❌ Blocker #10 — `CouponCodeRepository.findByCreatorIdOrderByCreatedAtDesc` missing |
| **Discovery/public profile won't compile** | `CreatorDiscoveryService.java:240,573` | ❌ Blocker #11 — `CreatorProfile.getUsername()` — entity has no `username` field at all |
| **Content scoring won't compile** | `BrandSafetyScoreService.java:216` | ❌ Blocker #6 — `MediaMetric.getCaption()` missing |
| **Affiliate reconciliation job won't compile** | `AffiliateEarningReconciliationJob.java:96` | ❌ Blocker #5 — `findOrphanedWithoutAffiliateEarning` missing |
| Creator onboarding (socials/profile/complete) | `api.ts:398-426` | FE-ONLY stubs; no backend routes |
| Creator disputes list | `api.ts:1922` | Derived from `/deals`; no dedicated endpoint |
| Affiliate commission | `AffiliateEarningsService.java:73-97` | Flat 10% placeholder; no per-campaign rate config |
| Fake-follower detection | `FakeFollowerDetectionService.java:27` | Deliberate stub — "requires NLP integration" |
| Quality/audience-match score | `QualityScoreService.java:23-63` | `audienceMatch` hardcoded 50 placeholder |
| Portfolio analytics/sync/contact | `PortfolioService.java:161-215` | `analytics()` empty stub; `syncPlatforms()` timestamp-only; `contact()` ack-only |

---

## 5. DONE vs PENDING — ADMIN

**Admin is the only backend slice that compiles clean end-to-end.** Split live/mock on the frontend.

### Done (live-wired)
- Admin auth (login/refresh/logout/me) — rate-limited, bucketed.
- CEO Pulse dashboard (`usePulseData` — mock removed, live).
- Support tickets list/detail (`useTicketList` — live).
- Brand detail + Creator detail reads (`useBrandDetail`/`useCreatorDetail` — live).
- Platform fee config get/update (`useFeeConfig` — live).

### Pending (ADMIN)
| Item | Where | State |
|---|---|---|
| **Admin API base-path drift** | `api-contracts.ts` vs `AdminAuthController.java:50` | FE calls `/api/v1/admin/**`, backend mounts `/admin/**` — **blocks all admin routes** until reconciled |
| Campaign monitoring | `useCampaignList.ts:6-148` | MOCK — hardcoded 8 rows; `TODO(Vikram)`, no `AdminCampaignController` |
| Content moderation flag queue | `useFlagQueue.ts:15-127` | MOCK — hardcoded 8 flags; no moderation controller |
| Flag queue actions (remove/reject/escalate) | `FlagQueue.tsx:280-283` | Stub — `console.info` only, no network call |
| Brand/Creator profile mutations (approve KYC/suspend/reinstate) | `BrandProfile.test.tsx:300-461` | Stub — logs `[BrandProfile] stub: ...`; detail reads live but writes not confirmed wired |
| Admin financial/marketing dashboards | `AdminDashboardController.java:23` | FE-ONLY; backend blocked by design |

---

## 6. DONE vs PENDING — AI

### Done — AI service (`influora-ai`) is production-grade
- Providers: **Claude `claude-sonnet-4-5-20250929`** (pinned), Gemini `gemini-2.5-flash-lite`, Sarvam voice.
- Chat `/chat` SSE + tool loop (6-iter cap, money-tier gate, idempotency, HMAC forward).
- Prompt assembler (3-block A/B/C, ephemeral cache, untrusted-wrapping).
- Brand-safety scorer (GARM, forced-tool, neutralization).
- Auth: JWKS RS256/ES256 verification + HMAC request signing, fail-closed.
- Tests: **153/153 pass** (eval + others).

### Meera 3-tier integration — hop-by-hop (Arjun)
| Hop | Wired? | Note |
|---|---|---|
| Browser → Spring start session | ✅ | `MeeraController.java:59-79` |
| Browser → Spring sendTurn | ⚠️ | Persists **placeholder echo**; `MeeraSessionService.java:137-142` — no LLM call from Spring |
| Browser → Python SSE (`useMeeraStream`) | ✅ | EventSource → Python `/chat` — real path |
| Python token validation | ✅ | JWKS + workspace match |
| Python → Claude streaming | ✅ | **Real Claude Sonnet 4.5** |
| Python → Spring tool calls | ✅ | HMAC + service-token → `/internal/meera/*` executors live |
| Python → Spring assistant writeback | ✅ | `/internal/meera/messages` persists real turn |

### Pending (AI) — the two that matter
| Item | Where | State |
|---|---|---|
| **Chat UI disconnected from the live pipeline** | `MeeraChatPanel.tsx:103,163,165` | Runs on `window.setTimeout`; **never imports `useMeeraStream` or calls `meeraApi.sendTurn`** → users get no real AI output regardless of `VITE_API_MODE` |
| `useMeeraStream.ts` is dead code | `hooks/useMeeraStream.ts` | Fully built SSE client, zero importers in `src/` |
| **Brand-facing Meera turn won't compile** | `MeeraController.java:91-96` vs `MeeraSessionService.java:95` | ❌ Blocker #12 — `sendTurn` called with 5 args, method takes 4 (found past javac's error cap) |
| AI test module can't be collected | `tests/prompt/test_brand_safety_prompt.py:25` | Imports `_neutralize_angle_brackets` (renamed to `neutralize_angle_brackets` in `untrusted.py`) → `ai-tests.yml` red |
| Python↔Java enum drift | `schemas.py:101` vs `CampaignIntentType.java` | Python `campaign_type` missing `STANDARD` → parse failure if backend returns it |
| Voice/TTS billing | `voice-usage.ts:6-14` | Intentional stub — no billing wired |
| `useNotifications` live path | `useNotifications.ts:114-134` | Raw hardcoded `fetch('/api/v1/notifications')`, bypasses shared client; backend not started |
| No production AI-spend ceiling | (config) | No runtime budget/kill-switch for Meera + classify + backfill |

---

## 7. Compile blockers (Vikram — must clear to make ANYTHING run)

> ⚠️ **Not exhaustive** — javac stopped at its 100-error cap; blocker #12 was found only by reading past it. Assume more exist beyond these 12.

| # | File | Missing | Owner-feature |
|---|---|---|---|
| 1 | `config/RedisCacheConfig.java` | `spring-boot-starter-data-redis` (pom) | Cross-cutting cache |
| 2 | `service/ContractPdfService.java` | `com.lowagie`/openpdf (pom) | Brand — contract PDF |
| 3 | `service/CreatorAnalyticsService.java` | `CreatorDemographicsResponse` DTO + 3 methods | Creator analytics |
| 4 | `service/MetaConnectionService.java` | 2 Meta response DTOs | Creator — Meta connect |
| 5 | `job/AffiliateEarningReconciliationJob.java` | repo `findOrphanedWithoutAffiliateEarning` | Creator affiliate |
| 6 | `service/scoring/BrandSafetyScoreService.java` | `MediaMetric.getCaption()` | Creator scoring |
| 7 | `service/AuthService.java` | `CreatorProfile.newForUser(...)` | **Creator signup** |
| 8 | `service/CampaignService.java` | `Campaign.Builder.campaignType(...)` | Brand campaigns |
| 9 | `service/ContractService.java` | `ContractSignedEvent` arity (5 vs 7) | Brand contracts |
| 10 | `service/CreatorCouponService.java` | repo `findByCreatorIdOrderByCreatedAtDesc` | Creator coupons |
| 11 | `service/CreatorDiscoveryService.java` | `CreatorProfile.getUsername()` (no such field) | Creator/Brand discovery |
| 12 | `web/MeeraController.java` vs `MeeraSessionService` | `sendTurn` arg-count mismatch | AI-bridge chat |

**Owner routing:** #1/#2 → Priya dep sign-off + Vikram pom. #3/#4 → analytics/meta owners. #5/#6/#7/#8/#10/#11 → CreatorProfile/Campaign/coupon refactor owners (E1/E7/#8 indicate `CreatorProfile`+`Campaign` are mid-refactor — reconcile, don't drive-by patch). #9/#12 → contract + Meera owners.

---

## 8. Security — pending before production (Kabir)

**Done (production-safe as coded):** internal Spring↔Python mesh (dual-credential JWT + HMAC/nonce + per-route on-behalf workspace re-validation), asymmetric-only JWKS with structurally-unreachable dev fallback, two-sided commit-tier gate from token claims, injection wrapping invoked on every path, admin login rate-limited, frontend exposes no secrets.

| Pending item | Severity | Evidence |
|---|---|---|
| Admin MFA is **opt-in**, no failed-login lockout | **HIGH** | `AdminAuthService.java:101-109` — highest-value surface, weakest gate |
| Admin refresh token returned in **JSON body** (not cookie-only) | MEDIUM | `AdminAuthController.java:71-73` — reintroduces XSS→ATO risk |
| Refresh cookie `Secure` flag env-dependent, not boot-validated | MEDIUM | `application.yml:36`; not in `SecretsStartupValidator` |
| **Razorpay webhook secret** committed placeholder is public + not boot-validated | **MEDIUM-HIGH** | `application.yml:117` — forgeable payment webhooks on misconfigured boot |
| Rate-limit + replay-nonce in-memory / per-instance | LOW-MED | Defeated by horizontal scale; move to Redis |
| `REPLACE_WITH_*` MSG91/R2/Razorpay keys boot successfully | LOW | Fail only at first use |

---

## 9. Prioritized pending-work list (my ruling)

**P0 — unblock the build (nothing runs until these land):**
1. Add `spring-boot-starter-data-redis` + `openpdf` to `influora-api/pom.xml` (blockers #1, #2). *Priya dep sign-off required.*
2. Reconcile the `CreatorProfile` / `Campaign` refactor: restore `newForUser`, `getUsername`, `Campaign.Builder.campaignType`, `MediaMetric.getCaption` (#6, #7, #8, #11).
3. Add missing DTOs + service/repo methods (#3, #4, #5, #10) and fix the 2 arity mismatches (#9, #12).
4. Re-run `mvn -o compile` to BUILD SUCCESS, then `mvn -o test` — the 113 test files can finally execute.

**P1 — make the flagship feature real:**
5. Wire `MeeraChatPanel.tsx` to `useMeeraStream` + `meeraApi.sendTurn` (delete the `setTimeout` script). The live pipeline already exists — connect the UI to it.
6. Fix `ai-tests.yml`: repoint `test_brand_safety_prompt.py` import to `untrusted.neutralize_angle_brackets`.
7. Reconcile admin API base path (`/api/v1/admin` vs `/admin`) — unblocks the entire live admin panel.
8. Add `STANDARD` to Python `campaign_type` enum (schema drift).

**P1 — security before prod:**
9. Mandatory admin MFA + failed-login lockout; move admin refresh token to cookie-only; add `razorpay.webhook-secret` + `*_COOKIE_SECURE` to `SecretsStartupValidator`.

**P2 — close honest stubs:**
10. Admin campaign-monitoring + moderation controllers + actions; store-integration status/disconnect; brand review inbox; content-performance; payout KYC lookup; portfolio sync; fake-follower NLP; affiliate per-campaign rates; production AI-spend ceiling.

**Process fix:** make CI a **merge gate**, not a report. A non-compiling branch and a broken-import test both landed on `feature/analytics-platform` — enforcement would have blocked both.

---

*Produced 2026-07-12 by reading source and running real gates. Status `.md` docs intentionally excluded. No application code modified.*
