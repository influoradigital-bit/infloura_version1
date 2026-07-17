# 📋 Influora — Pending Work & Owner Assignments

> **Date:** 2026-07-12 · **Branch:** `feature/analytics-platform`
> **Owner:** Priya (CTO) · **Routing:** Arjun (Eng Lead) · **Basis:** code-verified (see `PRIYA-CTO-CONSOLIDATED-REPORT-2026-07-12.md` §0b)
> **This file is the single source of truth for remaining work.** The older trackers (`wiki/tech/BRAND_ADMIN_PENDING_WORK.md`, `ADMIN_PENDING_WORK_LOOP.md`, `wiki/tech/PENDING_TASKS_REPORT.md`) are stamped SUPERSEDED and kept for history only.

---

## ✅ Already DONE (verified 2026-07-12 — do NOT reassign)
- Backend **main source compiles** — all 12 compile blockers fixed (`mvn -o compile` = 0 errors, 786 classes).
- **Meera chat UI wired live** — `MeeraChatPanel.tsx` uses `useMeeraStream` + `meeraApi.sendTurn`/`startSession` (mock kept behind `isApiLive()`).
- **Admin API path drift resolved** — `context-path: /api/v1` + `API_BASE=/api/v1/admin` aligned.
- **AI test suite green** — `pytest` 186 passed (broken import fixed).
- **Razorpay webhook secret boot-validated** — `SecretsStartupValidator.validateRazorpayWebhookSecret` (was MEDIUM-HIGH).
- Frontend `tsc` + `vite build` clean.

**Gate line now:** FE tsc ✅ · FE build ✅ · backend `mvn compile` ✅ · **backend `mvn test` ❌ (P0-1 below)** · AI pytest ✅ 186.

---

## 🎯 Assignment matrix (P0 → P1 → P2)

| Pri | ID | Task | Owner(s) | Files (real paths) | Acceptance | Depends |
|---|---|---|---|---|---|---|
| **P0** | 1 | **Backend TEST suite won't compile** — add `testcontainers` dep + reconcile drifted tests | **Vikram** → Kavya QA → **Meera** `mvn test` | `influora-api/pom.xml` (+testcontainers, junit-jupiter); `src/test/.../testsupport/AbstractIntegrationTest.java`; tests for `IdempotencyService.executeOnce`, `WalletService` ctor, `ConfirmLaunchExecutor`/`CreateCampaignExecutor` ctors, `MetaOAuthControllerTest`, `MeeraSessionServiceTest` | `mvn -o test` BUILD SUCCESS + all tests run | — (blocks all backend) |
| **P1** | 2 | Admin MFA mandatory + failed-login lockout | **Vikram** → **Kabir** | `service/admin/AdminAuthService.java:133` | MFA enforced for all admins; lockout after N fails; Kabir sign-off | P0-1 |
| **P1** | 3 | Admin refresh token → cookie-only (remove from JSON body) | **Vikram** → **Kabir** | `web/AdminAuthController.java:71-73`; `security/AdminAuthCookieService.java` | Refresh token in `Set-Cookie` only; matches brand/creator; Kabir sign-off | P0-1 |
| **P1** | 4 | Boot-validate cookie `Secure` flag | **Vikram**/Meera → **Kabir** | `config/SecretsStartupValidator.java`; `application.yml:36` | Non-dev boot fails if `*_COOKIE_SECURE` unset; Kabir sign-off | P0-1 |
| **P1** | 5 | Meera 3-tier E2E (confirm browser-direct vs Spring→Python) | **Vikram** → Kavya → **Meera** E2E | `web/MeeraController.java`; `service/meera/MeeraSessionService.java:137-142` (placeholder echo); `influora-ai/app/routes/chat.py`; `src/components/feature/meera/MeeraChatPanel.tsx` | Real AI reply reaches UI end-to-end; sendTurn echo intentional-or-wired, documented | P0-1 |
| **P2** | 6 | Admin moderation controller + flag actions | **Vikram** (new controller) → **Ananya** (wire FE) → Kavya → Meera | create `web/admin/AdminModerationController.java`; `src/admin/hooks/useFlagQueue.ts`; `src/admin/components/moderation/FlagQueue.tsx` | Flag/approve/reject/escalate via real API; FE off mock | P0-1 |
| **P2** | 7 | Admin campaign-monitoring controller | **Vikram** (new) → **Ananya** → Kavya → Meera | create `web/admin/AdminCampaignController.java`; `src/admin/hooks/useCampaignList.ts` | Live campaign list; FE off mock | P0-1 |
| **P2** | 8 | Admin profile mutations (approve KYC/suspend/reinstate) | **Vikram** → **Ananya** → Kavya → Meera | admin brand/creator controllers; `src/admin/hooks/useBrandDetail.ts`, `useCreatorDetail.ts`; `src/admin/components/users/{BrandProfile,CreatorProfile}.tsx` | Stub console-logs replaced by real mutations | P0-1 |
| **P2** | 9 | Store-integration status/disconnect endpoints | **Vikram** → **Ananya** → Kavya → Meera | store-integration controller; `src/lib/api.ts` (remove `NOT_IMPLEMENTED`); `src/hooks/brand/useStoreIntegration.ts` | Real status/disconnect | P0-1 |
| **P2** | 10 | Portfolio analytics/sync/contact real impl | **Vikram** → **Ananya** → Kavya → Meera | `service/portfolio/PortfolioService.java:161-215`; creator portfolio page | No stubs; real data/side-effects | P0-1 |
| **P2** | 11 | Fake-follower NLP + QualityScore audience-match | **Priya** (arch) → **Vikram** → Kavya → Meera | `service/scoring/FakeFollowerDetectionService.java`; `service/scoring/QualityScoreService.java:23-63`; `influora-ai/app/*` | Priya arch sign-off; real detection + audience match | P0-1 + Priya |
| **P2** | 12 | Payout KYC fund-account lookup | **Vikram** → Kavya → Meera | `service/PayoutService.java:240-270` | Real Razorpay fund-account ref (no user-id placeholder); `confirmExecuted` no longer no-op | P0-1 |
| **P2** | 13 | Affiliate per-campaign commission rates | **Rohan** (spec) → **Vikram** → Kavya → Meera | `service/AffiliateEarningsService.java:73-97` | Rohan model sign-off; per-campaign rate config (not flat 10%) | P0-1 + Rohan |
| **P2** | 14 | Content-performance-per-post + brand review inbox + disputes list endpoints | **Vikram** → **Ananya** → Kavya → Meera | new controllers; `src/lib/api.ts` (`content-performance`, `brand/reviews/received`, disputes) | Real endpoints; FE off derived/mock | P0-1 |
| **P2** | 15 | Creator onboarding backend routes | **Vikram** → **Ananya** → Kavya → Meera | creator onboarding controller; `src/lib/api.ts:398-426` | socials/profile/complete backed by real endpoints | P0-1 |
| **P2** | 16 | `useNotifications` → shared api client | **Ananya** → Kavya → Meera | `src/hooks/useNotifications.ts:114-134`; `src/lib/api.ts` | Uses shared client (role-aware), no raw `fetch` | — (FE-only, can parallel now) |
| **P2** | 17 | Production AI-spend ceiling / kill-switch | **Rohan** (proposal) → **Vikram** → Kavya → Meera | `influora-ai/app/config.py`; `influora-ai/app/routes/chat.py` | Rohan spend model; requests stop at ceiling / kill-switch | P0-1 + Rohan |

**Pipeline per item:** owner implements → **Kavya** QA → **Meera** local verify (`mvn test`/`pytest`/build) → **Kabir** for security items → **Priya** sign-off.

---

## 🧭 Critical path
**P0-1 is the single gate.** Backend `mvn test` won't compile → no backend test can run, so nothing downstream is truly verifiable. Vikram fixes dep + drifted tests → Meera confirms `mvn test` green → **P1 security (2/3/4) and P1-5 E2E unblock production readiness**; P2 feature items parallelize after. **P2-16 (`useNotifications`) is FE-only and can start now.** P2-11/13/17 additionally wait on Priya (arch) / Rohan (product) input.

---

## 👤 Per-employee queue (what each person picks up)

- **Vikram (backend):** P0-1 (dep + reconcile tests) FIRST → then P1-2/3/4 (security), P1-5 (E2E wiring) → P2 controllers/services 6–15 & 17-impl. The whole backlog gates on his P0-1.
- **Ananya (frontend):** Start **P2-16** now (independent). Then wire FE for 6,7,8,9,10,14,15 as Vikram's endpoints land.
- **Meera (DevOps/verify):** Own the `mvn test` gate on P0-1; run local verify on every item after Kavya.
- **Kavya (QA):** Review every item before Meera; owns the QA gate.
- **Kabir (security):** Sign off P1-2/3/4.
- **Rohan (finance):** Deliver spend model for **P2-13** and proposal for **P2-17**.
- **Priya (CTO):** Approve testcontainers dep (P0-1) in `wiki/tech/approved-deps.md`; architecture ruling for **P2-11**; final sign-off.

---
*Assignments routed by Arjun on Priya's verified pending list, 2026-07-12. Owners: pull your item, follow the pipeline, report status in `SHARED_CONTEXT.md`.*
