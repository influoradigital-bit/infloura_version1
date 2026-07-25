# Influora — Brand & Creator Live Test Report (Complete)

**Target:** `http://200.141.1.6/` (live `influora-test` deploy, VPS 1844961)
**Date:** 2026-07-22 · **Method:** live online testing only — real HTTP + browser (UI+backend) + Hostinger-MCP log correlation
**Accounts:** brand `demo.brand@influora.com` · creator `demo.creator@influora.com` · (admin `influoradigital@gmail.com` — blocked on MFA, not covered here)
**Coverage:** 55 backend GET endpoints swept with both role tokens; all major screens walked in-browser; full brand→creator campaign lifecycle exercised.

---

## Overall verdict

🔴 **NOT production-ready.** Auth, discovery, and the entire campaign lifecycle *backend* work well, but three things block real use: (1) the brand can't create a campaign in the UI (End Date bug), (2) the demo-brand has no wallet which cascades into a dashboard of fabricated data, and (3) heavy demo/mock data leaks into live screens (creator profile & wallet are entirely fake). Plus two systemic backend issues (client errors return 500; email delivery is dead).

**Completion (weighted: works=1, partial=0.5, broken=0):** ~**62%** across 47 checked features (see dashboard HTML).

---

## Blocking / High issues (fix before launch)

| # | Sev | Feature | What's wrong | Where |
|---|---|---|---|---|
| 1 | 🔴 Blocking | Brand create-campaign (UI) | End Date picker writes to Start Date; end date can't be set; wizard stuck at Budget. Backend is fine (API create works). | `src/components/brand/campaigns/campaign-form.tsx` (End Date Popover ~700) |
| 2 | 🟠 High | Brand wallet 404 → dashboard cascade | Demo workspace has no wallet row → `/wallet` 404. Dashboard `Promise.all([actions,wallet,pipeline])` fails-fast → discards the 2 good calls → shows **mock** actions/pipeline/wallet + error toast. | seed data; `dashboard-page.tsx:150` |
| 3 | 🟠 High | Creator profile page 100% fake | Renders `mockProfile` ("Priya Sharma / 125K / 45 collabs"), **makes no API call**, ignores real `/me/creator-profile`. Shows wrong identity to every creator. | `src/pages/creator-profile.tsx:43` |
| 4 | 🟠 High | Creator wallet fabricated money | `/wallet`→200 (real, empty) but UI shows "₹4,25,000 earned" + fake payouts (BoAt/Mamaearth/Nykaa) + mock TDS/GST. | `src/pages/creator-wallet.tsx:89,97` |
| 5 | 🟠 High | Featured creators broken | `/creators/featured` → **500 even with valid params** (real unhandled exception). | `CreatorDiscoveryService.getFeatured:284` |
| 6 | 🟠 High | Brand email delivery dead | Welcome/reset emails fail: `535 IP not whitelisted` (MSG91 SMTP). | `Msg91EmailClient` / MSG91 account |
| 7 | 🟡 Med | API returns 500 for client errors | `GlobalExceptionHandler` doesn't map `NoResourceFoundException` / `MissingServletRequestParameter` / wrong-method → all become `500 INTERNAL_ERROR` instead of 404/400. Repo-wide. | `GlobalExceptionHandler` |
| 8 | 🟡 Med | Shopify OAuth start broken | `/shopify/oauth/authorize` → 500 (broken or unconfigured store integration). | `ShopifyOAuthController` |
| 9 | 🟡 Med | Fake Hype deal in creator inbox | `demoHypeCampaign` "Glow Drop Challenge" renders over the real empty deals list, with a live Accept button. | `src/lib/demo-data.ts` |
| 10 | 🔵 Low | Creator login badge mislabel | Creator sign-in page shows "Brand workspace" chip. | creator login page |

---

## ✅ What works (verified live)

**Auth & isolation:** brand login, creator login, brand register+OTP flow (dev OTP logged not emailed), role isolation (every brand endpoint 403s for creator token and vice-versa — correct).

**Campaign lifecycle (backend, end-to-end):** brand `POST /campaigns` → creator `GET /creator/campaigns` (discovers it) → `GET /creator/campaigns/{id}` → `POST .../apply` (collaboration APPLIED) → both sides see it in `/deals` → brand `POST /deals/{id}/accept` → **TERMS_AGREED**. Validation solid (deadline<start enforced; double-apply → 409).

**Brand read surface (200, real data):** campaigns list, discover creators (`/creators`, `/creators/search`), wallet escrow/transactions, contracts, disputes, reviews, billing (plan/usage/invoices), meera brand-profile, meera credits, notifications, trendspark nudge, workspace members/invites, campaign templates, integrations/store status, dashboard actions/pipeline (API returns valid data — it's the UI that mis-handles it).

**Creator read surface (200, real data):** `/me/creator-profile`, deals, wallet + balance + transactions + payout-methods, analytics metrics, copilot suggestion (gated on Instagram — correct), coupons, disputes, affiliate-earnings, campaigns browse, reviews, invoices (campaign/commission), platform-fee, contracts.

---

## Screen-by-screen (browser: UI + backend)

### Brand
| Screen | UI | Backend | Verdict |
|---|---|---|---|
| /brand/login | ✅ | login 200 | ✅ |
| /brand/dashboard | renders | actions/pipeline 200, **wallet 404** | 🔴 shows all mock data + error toast |
| /brand/campaigns | ✅ | campaigns 200 | ✅ real empty state |
| /brand/campaigns/new | ✅ steps 1–2 | — | 🔴 blocked at Budget (End Date) |
| /brand/discover | ✅ | creators 200 | ✅ 7 real seeded creators |
| /brand/wallet | renders | **wallet 404** | 🟡 "not found" + mock TDS/GST |
| Messages/Chat, Analytics, Settings, Meera chat | not walked | — | ⚪ untested UI (mock data present in code) |

### Creator
| Screen | UI | Backend | Verdict |
|---|---|---|---|
| /creator/login | ✅ | login 200 | ✅ (badge mislabel) |
| /creator/deals | ✅ | deals 200 (empty) | 🟡 real empty + fake Hype card |
| /creator/wallet | ✅ | wallet 200 (real) | 🟠 mock earnings/payouts shown |
| /creator/profile | ✅ | **no API call** | 🔴 100% fake identity |
| Co-pilot, deal room, deliverables, analytics, settings, onboarding | not walked | — | ⚪ untested UI |

---

## Demo / mock / seed data inventory (report-only — nothing deleted)

**Master switch:** `src/lib/api.ts:51-56` — `VITE_API_MODE`; mock is the default, `live` disables. The deployed build IS live (makes real calls), so leaks below are pages that show mock **regardless of mode** (hardcoded initial state or unconditional demo arrays).

**Live-visible mock leaks (confirmed on the deployment):**
- Brand dashboard — `dashboard-page.tsx:46` `mockActionItems`, `mockWallet`, `mockPipeline` (shown when load fails, which it does).
- Brand wallet — `src/pages/brand-wallet.tsx:118` `mockWalletData` (TDS ₹1,48,500 / GST ₹2,67,300 — comment admits "stay mock-only").
- Creator profile — `src/pages/creator-profile.tsx:43` `mockProfile` (Priya Sharma) — renders with no API call.
- Creator wallet — `src/pages/creator-wallet.tsx:89,97` `mockEarningsData`/`mockPayouts` (BoAt/Mamaearth/Nykaa).
- Deals/landing — `src/lib/demo-data.ts` `demoHypeCampaign` "Glow Drop Challenge" (creator deals inbox + public landing + /features/hype).
- Brand analytics — `demoCreators` in `brand-analytics.tsx:49`, `brand-creator-analytics.tsx:70`.

**DB seed (live in prod — security concern):**
- `db/migration/V7__seed_discoverable_creators.sql` — 5 real ACTIVE creators (Priya Sharma, Arjun Mehta, Maya Kapoor, Rohit Verma, Neha Gupta), **shared password `Password@123`**, runs in every environment. These are the "7 creators" shown in brand discovery.
- **The intended cleanup migration `V72__remove_seed_creators.sql` was never written** (referenced by `DevSeedCreatorsRunner.java` + V7 header, but does not exist) → seed logins remain live in prod.
- `DevSeedCreatorsRunner.java` — `@Profile("dev")` re-inserts the 5 on fixed `01SEED*` ULIDs.

**Demo accounts:** seed creators `*.demo.influora.com` (pwd `Password@123`); `demo.brand@influora.com` / `demo.creator@influora.com` (live accounts, **no source-controlled definition** — provisioned directly against the DB); `demo-access-panel.tsx` one-click mock login bypass (guarded to mock builds).

**Dead code (safe to delete):** `demoAdminUsers/Disputes/Transactions/KycQueue/Stats` in `demo-data.ts` — no importers.

**Test artifacts I created this session (flag for cleanup):** campaign `01KY523ES7ZW…` + collaboration `01KY52585H…` on demo brand/creator; brand account `qa-brand-1784723196@example.com`.

---

## Not covered / still missing
- **Admin surface** — entirely untested (blocked on TOTP MFA for `influoradigital@gmail.com`).
- **Money tail** — escrow fund → deliverables submit → approve → payout not exercised (accepted deal has `dealValue: null`; needs a terms/amount step).
- **Brand UI screens** not walked: Messages/Chat, Analytics, Settings/Billing, Meera chat (live AI reply), campaign tracking, deal room, contract e-sign.
- **Creator UI screens** not walked: co-pilot detail, deal room, deliverables submit, analytics, settings, onboarding wizard.
- **Meera AI** live reply/streaming/voice — not exercised end-to-end in this pass.

---

## Fix routing
- **Ananya (frontend):** #1 date picker · #2 `Promise.all`→`Promise.allSettled` · #3 wire creator profile to API + drop `mockProfile` · #4 real creator wallet data · #9 remove demoHype from live · #10 badge.
- **Vikram (backend):** #2 auto-provision wallet on read · #5 `getFeatured` fix · #7 map 4xx exceptions in `GlobalExceptionHandler` · #8 Shopify OAuth.
- **Infra:** #6 MSG91 whitelist `200.141.1.6`.
- **Security/DevOps:** write `V72` to remove seed creators from prod; rotate the shared seed password.
