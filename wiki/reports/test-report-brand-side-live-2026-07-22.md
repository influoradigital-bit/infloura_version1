# Test Report — Brand Side (Live) — 2026-07-22

**Target:** `http://200.141.1.6/` — brand surface only (live `influora-test` deploy, VPS 1844961)
**Tester stage run:** Functional QA + live smoke (Kavya/Meera domain, executed against the running deployment)
**Method:** real HTTP calls from the whitelisted IP + live container-log correlation via Hostinger MCP
**Overall verdict:** 🔴 **FAIL** — a blocking bug stops brands from creating a campaign (end-date can't be set). Auth + most read screens work, but the primary write flow is broken, and the `/wallet` 404 cascades into a dashboard of fabricated data. 1 blocking High + 3 High (email delivery, demo-wallet 404, dashboard mock-data-on-error) + 2 Medium (wallet-page mock figures, unknown-route 500s).

---

## What passed (evidence)

| Check | Result |
|---|---|
| Homepage `/` | 200, real app HTML (2.7 KB shell) |
| SPA brand routes (`/brand/login`, `/register`, `/dashboard`, …) | 200 (client-routed shell) |
| Login input validation — empty body | 400 |
| Login — wrong credentials | 401 |
| Auth guard — `/brand/reviews/received`, `/wallet` without token | 403 |
| **Full signup chain** — send-OTP → verify-email → register → login | 200 → 200 (`emailVerified:true`) → **201** (BRAND user + workspace created) → 200 |
| Authenticated `/wallet` | 200 — `{availableBalance, escrowLocked, pendingPayouts}` |
| Authenticated `/wallet/balance`, `/wallet/transactions` | 200 |
| Authenticated `/brand/reviews/received`, `/meera/brand-profile` | 200 |
| Historical **brand-wallet 403 bug** | ✅ **FIXED** — brand token now reads `/wallet` |

---

## Findings

### 🟠 HIGH — Brand transactional email is broken (SMTP IP not whitelisted)
- **Where:** `Msg91EmailClient` → `smtp.mailer91.com` from VPS `200.141.1.6`
- **Issue:** After a successful brand `register`, the async `welcome.brand` email fails:
  `SMTP email error: templateKey=welcome.brand, error=Authentication failed` →
  `jakarta.mail.AuthenticationFailedException: 535 5.7.8 200.141.1.6 - Ip is not whitelisted.`
  Same failure hits every brand SMTP email (welcome, and password-reset/`forgot-password`). The API returns success to the client while the email silently never sends.
- **Fix:** Whitelist VPS IP `200.141.1.6` on the MSG91 account (already in flight). No code/redeploy needed once cleared — creds are wired correctly.

### 🟡 MEDIUM — Unknown API routes return 500 instead of 404
- **Where:** `com.influora.common.GlobalExceptionHandler`
- **Issue:** Any unmatched path returns `500 {"code":"INTERNAL_ERROR"}`. Verified: `/api/v1/brand/wallet` and `/api/v1/this/does/not/exist` both → 500. Root cause in logs: `org.springframework.web.servlet.resource.NoResourceFoundException: No static resource …` falls through to the catch-all handler. A wrong/renamed client path looks like a server crash, and it pollutes ERROR logs — masking genuine 500s in monitoring.
- **Fix:** Add a `@ExceptionHandler(NoResourceFoundException.class)` mapping to `404 NOT_FOUND` in `GlobalExceptionHandler`.

### 🔵 LOW / INFO — Brand OTP is delivered only via server logs (dev profile)
- **Where:** `BrandEmailOtpService` (`APP_ENV=dev` branch)
- **Issue:** In dev, the registration OTP is logged in plaintext (`[dev] Brand email OTP for … : 813003`) and returned as "OTP sent successfully" — no email is sent. Fine for internal testing, but it means a real external brand cannot self-serve signup on this box (no inbox delivery), and outside dev the OTP send would use the same broken SMTP as the High finding above.
- **Fix:** For a true end-to-end brand-signup test, whitelist SMTP (High) and exercise a non-dev email path; otherwise accept log-sourced OTP for internal QA only.

---

### 🟠 HIGH — Demo brand account has no wallet (dashboard breaks on login)
- **Where:** workspace `Demo Brand Co` (`01KY4Y1PR2A2CHE0933YPZ3R7R`); endpoints `/api/v1/wallet`, `/wallet/balance`
- **Issue:** Logging in as the demo brand (`demo.brand@influora.com`) then calling `/wallet` returns `404 {"code":"WALLET_NOT_FOUND","message":"Wallet not found for workspace"}`. A freshly *registered* brand gets a wallet auto-provisioned (returns zero balances), so the demo/seed account was created without one. The brand dashboard (`src/components/brand/dashboard/dashboard-page.tsx` → `api.wallet.get('brand')` on mount) will error on the wallet card immediately after demo login — the first thing a demo user sees.
- **Fix (either):** (1) seed a wallet row for the demo workspace, or preferably (2) make the wallet read lazily create/return a zero-balance wallet when none exists, matching registration behavior — robust against any workspace missing a wallet.

### Demo-account surface (login `demo.brand@influora.com`, ACTIVE/VERIFIED/onboarded)
| Endpoint | Result |
|---|---|
| `POST /auth/brand/login` | 200 — ACTIVE, emailVerified, onboardingCompleted |
| `/wallet`, `/wallet/balance` | **404 WALLET_NOT_FOUND** (finding above) |
| `/wallet/transactions` | 200 (empty) |
| `/campaigns` | 200 (empty list) |
| `/brand/reviews/received` | 200 (empty) |
| `/meera/brand-profile` | 200 — `nicheTags:[beauty,skincare]`, `analysisStatus:READY` |
| `/contracts` | 200 (empty) |
| `/brand/disputes`, `/brand/disputes/list` | 200 |
| `/notifications` | 200 — `unreadCount:0` |

> Note: an initial `/disputes` → 500 was the wrong path (real path is `/brand/disputes`); it's the same unknown-route-returns-500 bug already logged as Medium, not a disputes defect.

---

## Browser walkthrough (UI + backend, demo login) — 2026-07-22

Drove the real browser through the brand app logged in as `demo.brand@influora.com`. Verified each screen renders (UI) *and* whether it shows real backend data vs mock.

| Screen | UI renders | Backend wired correctly | Notes |
|---|---|---|---|
| `/brand/login` | ✅ | ✅ | form submits, `login→200`, redirects to dashboard |
| `/brand/dashboard` | ✅ | ❌ **shows all mock data** | see HIGH finding below |
| `/brand/campaigns` | ✅ | ✅ | `campaigns→200`, real empty state ("No campaigns found", ₹0K) |
| `/brand/wallet` | ✅ | ⚠️ | "Wallet not found" error **+ mock TDS/GST/recharge figures** shown |
| `/brand/discover` (Creators) | ✅ | ✅ | `creators→200`, 7 real seeded creators with real profile links |

### 🟠 HIGH — Dashboard shows fabricated data on any load error (Promise.all fail-fast)
- **Where:** `src/components/brand/dashboard/dashboard-page.tsx:150`
- **Issue:** The dashboard seeds state with `mockActionItems / mockWallet / mockPipeline`, then loads real data via `Promise.all([actions, wallet, pipeline])`. Because `/wallet` **404s and throws**, the *entire* `Promise.all` rejects → control jumps to `catch` → `setActionItems/setWallet/setPipeline` never run. The two successful calls (`/dashboard/actions→200 []`, `/dashboard/pipeline→200 []`) are discarded, so the screen stays on **mock data** — "Priya Sharma ₹45K", full pipeline (Outreach 8 … Settled 28), "Wallet ₹2.9L / Healthy / 47d runway" — none of which belongs to this account, plus a red "Couldn't load your dashboard" toast. A demo/real user sees convincing fake numbers.
- **Fix:** Use `Promise.allSettled` so each card independently renders real data or its own error; never leave whole-dashboard mock values on screen after a failed fetch. (Root trigger is the `/wallet` 404 — fixing the wallet provisioning removes the symptom, but the fail-fast design will re-bite on any single endpoint failure.)

### 🟡 MEDIUM — Wallet page renders mock financial figures under a "not found" error
- **Where:** brand wallet page (`/brand/wallet`)
- **Issue:** With `/wallet` 404, the page shows the error "Wallet not found for workspace" yet still displays hardcoded placeholders: "Last recharge ₹1,00,000 on 17 Jul 2026", "Total TDS Deducted ₹1,48,500", "Total GST Paid ₹2,67,300", "Burn rate ₹1,80,000/mo". Fake tax/finance figures next to a not-found error are misleading.
- **Fix:** Render a true empty/zero state (or the create-wallet CTA) when the wallet call fails; don't fall back to mock financials.

> Positive: Login, Campaigns, and Creators/Discover are all correctly backend-wired (real empty states / real seeded data), so the mock-data problem is **isolated to dashboard + wallet**, both downstream of the `/wallet` 404 — not a systemic frontend issue.

---

## Create-campaign flow (Open Campaign wizard) — 2026-07-22

Drove the full wizard as the demo brand: **Basics → Content → Budget → Requirements → Review**.

| Step | Result |
|---|---|
| Basics (title, description, objective, private toggle) | ✅ fields work, validation advances |
| Content (platforms, content types) | ✅ Instagram + Reel/Short selected, advances |
| Budget → **Start/End date** | ❌ **BLOCKED** — see finding |
| Budget validation | ✅ correctly shows "Start/End date is required" |

### 🔴 HIGH (blocking) — Campaign end-date cannot be set; blocks campaign creation
- **Where:** Open Campaign wizard, Budget step (`/brand/campaigns/new`); component `src/components/brand/campaigns/campaign-form.tsx` (End Date `Popover`, ~line 700–729)
- **Issue:** Selecting any date in the **End Date** picker writes to the **Start Date** field instead; End Date stays empty and "End date is required" never clears. Reproduced 3× with correctly-read date-cell refs. When the End Date trigger is opened, the calendar shows the *Start* date pre-selected — i.e. the End Date trigger drives the Start Date calendar. Because the Budget step requires a valid end date, the wizard **cannot advance past Budget** → a brand cannot create a campaign through this flow. No UI workaround.
- **Evidence vs. code:** both the source and the **deployed minified bundle** bind the End picker correctly (`selected:o.endDate, onSelect → updateFormData({endDate})`), so this is **not** a simple `onSelect` typo. Root cause is likely Radix `Popover` open-state/portal association coupling the two pickers at runtime. Needs a developer to reproduce and debug at runtime, not a static one-line fix.
- **Fix (investigate):** give each `Popover` an isolated/controlled open state (or unique ids), verify the End `PopoverContent` renders its own `CalendarComponent` instance, and confirm no portal/z-index overlap causes clicks/selection to land on the Start calendar. Add a regression test that sets end date and asserts `formData.endDate` updates (not `startDate`).

> Positive: every other wizard step (Basics, Content, budget sliders, validation messaging) works correctly; the blocker is isolated to the End Date control.

---

## CREATOR side (live, `demo.creator@influora.com`) — 2026-07-22

Backend swept via API + browser walkthrough (UI + real data).

### Backend endpoints — mostly healthy (200 with real data)
login · `/me/creator-profile` · `/creator/analytics/me/metrics` · `/creator/copilot/suggestion/today` (works; "no suggestion, pending_tagging") · **`/wallet`** (creator HAS a wallet — no 404) · `/wallet/payout-methods` · `/deals` · `/contracts` · `/notifications` · `/creator/reviews/received` · `/creator/coupons` · `/creator/disputes` · `/creator/affiliate-earnings` · `/creator/campaigns` · `/creator/platform-fee`

### Browser walkthrough
| Screen | UI | Backend call | Verdict |
|---|---|---|---|
| `/creator/login` | ✅ | `login → 200` | ✅ works (minor: badge mislabels "Brand workspace") |
| `/creator/deals` (home) | ✅ | `deals?status=all → 200` (empty) | ⚠️ real empty state, but a **fake Hype deal** is shown |
| `/creator/wallet` | ✅ | `/wallet → 200` (real) | ⚠️ UI shows **mock earnings + fake payouts** |
| `/creator/profile` | ✅ | **no API call at all** | ❌ **100% hardcoded mock identity** |

### 🟠 HIGH — Creator Profile page is entirely mock and never calls the backend
- **Where:** `src/pages/creator-profile.tsx:43` (`mockProfile`)
- **Issue:** Logged in as *Demo Creator*, the profile page renders "Priya Sharma / @priyacreates / Mumbai / 125K followers / 45 collabs / 4.8 rating / ₹25,000–75,000 rate / Hindi-English-Marathi". No network request fires on load — the page shows a hardcoded fake creator to **every** logged-in user, ignoring their real `/me/creator-profile` (which returns the correct "Demo Creator"). A creator viewing their own profile sees someone else's fabricated data.
- **Fix:** wire the page to `GET /me/creator-profile`; delete `mockProfile`.

### 🟠 HIGH — Creator Wallet shows fabricated earnings & payouts
- **Where:** `src/pages/creator-wallet.tsx:89,97` (`mockEarningsData`, `mockPayouts`)
- **Issue:** Despite `/wallet → 200` (real, empty), the page shows "Total Earned ₹4,25,000", "This Month ₹85,000", and fake payout rows — **BoAt Lifestyle ₹65,400, Mamaearth ₹30,520, Nykaa Fashion ₹43,600** (+ mock TDS/GST/Form-16A in Tax Docs). Same misleading pattern as the brand wallet.
- **Fix:** render real wallet/payout data (or zero/empty state); delete the mock arrays.

### 🟡 MEDIUM — Fake "Hype" deal renders on the creator Deals inbox
- **Where:** `demoHypeCampaign` (`src/lib/demo-data.ts`) prepended in the deals/campaigns UI
- **Issue:** `deals?status=all` returns empty ("No deals yet"), yet a live-looking "Glow Drop Challenge / Glow Naturals / ₹3,500 per reel / 63 of 100 slots / Accept" card shows above it. A creator could click Accept on a non-existent deal.

### 🟡 MEDIUM — `/creator/deliverables` 500 on missing param (same handler gap)
- **Where:** `CreatorDeliverableController.java:49` requires `collaboration_id`; `GlobalExceptionHandler`
- **Issue:** Omitting the required `collaboration_id` returns **500 INTERNAL_ERROR** instead of 400. Endpoint works with the param. Same root cause as the brand-side unknown-route 500s: the `GlobalExceptionHandler` doesn't map Spring's standard 4xx exceptions (`NoResourceFoundException`, `MissingServletRequestParameterException`, wrong-method) → they all surface as 500 across the whole API.

### 🔵 LOW — Creator login page badge says "Brand workspace"
- **Where:** creator login page header chip. Cosmetic mislabel on the creator sign-in screen.

> Positive: creator **backend is in good shape** — the creator has a real wallet (no brand-style 404 cascade), and there's no hard blocker like the brand create-campaign end-date bug. The creator problems are almost entirely **frontend demo-data contamination** (profile, wallet, deals) rather than broken services.

---

## End-to-end campaign flow: brand → creator (API-level) — 2026-07-22

Since the brand create-campaign **UI** is blocked by the End Date bug, I created a campaign directly via the API to verify whether the underlying lifecycle works. **It does — fully.**

| Step | Endpoint | Result |
|---|---|---|
| Brand creates campaign (ACTIVE, public) | `POST /campaigns` | ✅ 201 — `id 01KY523ES7ZW…`, status ACTIVE |
| Creator discovers it | `GET /creator/campaigns` (browse) | ✅ listed with brand "Demo Brand Co", budget, deadline |
| Creator views detail | `GET /creator/campaigns/{id}` | ✅ 200 |
| Creator applies | `POST /creator/campaigns/{id}/apply` | ✅ 201 — collaboration `01KY52585H…`, status **APPLIED** |
| Duplicate apply blocked | (same) | ✅ 409 (correct) |
| Creator sees deal | `GET /deals` (creator) | ✅ shows deal, counterparty "Demo Brand Co", APPLIED |
| Brand sees applicant | `GET /deals` (brand) | ✅ shows applicant "Demo Creator", APPLIED |
| Brand accepts | `POST /deals/{id}/accept` | ✅ status → **TERMS_AGREED** ("Brand accepted the proposal") |

**Finding:** the campaign lifecycle backend (create → discover → apply → deal room → accept) is **fully functional**. The only thing stopping a brand from doing this through the product is the frontend **End Date picker blocker** — fix that one UI bug and the whole flow is usable.

**Validation quality (positive):** create enforces `applicationDeadline < startDate`; apply enforces no-double-apply (409). Backend validation is solid.

**Not exercised (needs a terms/value step first):** escrow funding → deliverables submit → approve → payout. The accepted deal has `dealValue: null`; the money stages require terms/amount to be set, which I did not drive in this pass.

### Campaign-flow verdict
- Brand create **via UI**: 🔴 blocked (End Date bug)
- Brand create **via API** + full brand→creator lifecycle to TERMS_AGREED: ✅ **working**
- Escrow/deliverables/payout tail: ⚪ untested (needs terms/value)

## Notes / scope
- Port 80 is firewall-locked to the tester IP `103.242.120.204`; all checks ran from there.
- Test posture is intentional and **not** production: `APP_ENV=dev`, admin MFA off, no TLS, ClamAV off.
- One test brand account was created: `qa-brand-1784723196@example.com` (workspace `qa-test-co`) — safe to purge.
- Not run: Security/Kabir and AI/Ash suites (out of scope for this brand-only functional pass).
