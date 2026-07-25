# Creator Flow — API + Live-Data Report
**Author:** Priya (CTO) · Reporting: Tara · **Date:** 2026-07-24
**Environment tested:** `http://200.141.1.6` (live), API base `/api/v1`, served via Caddy → Spring Boot (Tomcat)
**Accounts:** `demo.creator@influora.com` (CREATOR) · `demo.brand@influora.com` (BRAND)

> Method: mapped all creator controllers/services + the React frontend, then exercised every creator GET endpoint against the **live** server with a real creator JWT. Status codes and payloads below are real responses captured 2026-07-24 07:0x UTC.

---

## 0. Live login proof
Both logins return HTTP 200 with a real access token (900s TTL, refresh in HttpOnly cookie).

| Account | userType | id | workspace | onboardingCompleted |
|---|---|---|---|---|
| demo.creator@influora.com | CREATOR | 01KY4Y1PR2JHG37XRCX0BA17QX | null (creators have no workspace) | true |
| demo.brand@influora.com | BRAND | 01KY4Y1PR2CBXSQP33FJ70VX7Q | Demo Brand Co (VERIFIED) | true |

Auth model: `SecurityConfig` = `anyRequest().authenticated()`; the CREATOR role is enforced at the **service layer** (`CreatorContextService.requireCreator()` → `WRONG_USER_TYPE` 403). Confirmed live: creator hitting brand-only routes returns `403 WRONG_USER_TYPE` ("This endpoint is for brand accounts only") on `/wallet/escrow`, `/dashboard/actions`, `/dashboard/pipeline`.

---

## 1. Live snapshot of the demo creator (what real data shows)

| Endpoint | HTTP | Live data returned |
|---|---|---|
| `GET /me/creator-profile` | 200 | id `01KY4Y1PR2RVPPVDQ3Y4ATJNBB`, username `demo_creator` |
| `GET /creator/campaigns` | 200 | 1 open campaign: **QA E2E — Diwali Skincare Reels** (`01KY523ES7…`), budget ₹5,000–₹25,000, Instagram/reel |
| `GET /deals` | 200 | 1 deal `01KY52585H…` vs **Demo Brand Co**, status **CONTRACTED** |
| `GET /deals/{id}` | 200 | contractId `01KY77ZAZ…`, contractStatus **ACTIVE**, **escrowFunded: false**, deliverables 0/0 |
| `GET /creator/platform-fee` | 200 | **feeBps 1500 = 15%**, source GLOBAL_DEFAULT |
| `GET /wallet/balance` | 200 | balance **0**, escrowBalance 0, INR |
| `GET /wallet/transactions` | 200 | empty (paginated) |
| `GET /wallet/payout-methods` | 200 | empty (no bank/UPI added yet) |
| `GET /creator/campaign-invoices` | 200 | empty |
| `GET /creator/commission-invoices` | 200 | empty |
| `GET /creator/affiliate-earnings` | 200 | empty + summary (INR) |
| `GET /creator/coupons` | 200 | empty |
| `GET /creator/copilot/suggestion/today` | 200 | suggestion null, status **pending_tagging** (needs IG connect/tagging) |
| `GET /creator/analytics/me/metrics` | 200 | zeros (no self-reported data yet) |
| `GET /creator/analytics/me/scores` | 404 | `SCORE_NOT_FOUND` — no computed score yet (expected empty state) |
| `GET /me/portfolio/analytics` | 200 | pageViews(30d) **2**, profileClicks **150**, brandInquiries **1** |
| `GET /notifications` | 200 | real notif: "New message from Demo Brand Co" (event `message.first`) |
| `GET /wallet/escrow` | 403 | WRONG_USER_TYPE (brand-only) ✔ RBAC correct |
| `GET /dashboard/actions` | 403 | WRONG_USER_TYPE (brand-only) ✔ RBAC correct |

**Where the demo pair sits in the flow right now:** contract signed → collaboration **CONTRACTED**, contract **ACTIVE**, but **escrow not funded yet**. Next real step is the brand funding escrow, which flips the collaboration to `IN_PROGRESS` and unlocks deliverable submission → approval → wallet credit → withdrawal.

---

## 2. End-to-end flow (the spine)

The single join entity is **`Collaboration`** (`UNIQUE(campaign_id, creator_id)`), which unifies application / invitation / deal / hire. Its status machine is driven centrally by `CollaborationLifecycleService` (idempotent, monotonic):

```
INVITED (brand invite)  ─┐
APPLIED (creator apply) ─┼─► IN_NEGOTIATION ─► TERMS_AGREED
SHORTLISTED ─────────────┘        │ ContractService.generate (POST /contracts)
                                  ▼
                            CONTRACT_PENDING
                                  │ both sign (POST /contracts/{id}/sign ×2)
                                  ▼
                            CONTRACTED  ◄── demo creator is HERE
                                  │ Razorpay webhook confirmFunded (escrow paid)
                                  ▼
                            IN_PROGRESS ─(creator submit)─► REVIEW_PENDING ─(brand revise)─► REVISION_REQUESTED
                                  │ brand approves all deliverables
                                  ▼
                            COMPLETED ─► reviews enabled
        (either party disputes → DISPUTED → admin resolves → escrow split/refund)
```

---

## 3. How the creator gets paid (concrete, verified REAL in code)

Money never touches `Wallet.balance` directly — everything posts through a double-entry `WalletLedgerService`. Razorpay is the real integration (Orders SDK for funding, RazorpayX REST for payouts); a `*_stub_*` fallback exists **only in dev** — `requireConfiguredOutsideDev()` throws in staging/prod.

1. **Brand funds escrow** → `POST /wallet/escrow/fund` (Idempotency-Key required; amount re-derived server-side from milestone/campaign, never from body). Requires the contract fully signed (`brandSignedAt` + `creatorSignedAt`). Creates a PENDING `EscrowHold` + Razorpay Order.
2. **Funding confirmed** → HMAC-verified webhook `POST /webhooks/razorpay` (`order.paid`) → `EscrowService.confirmFunded` → ledger DEBIT brand → CREDIT platform clearing; hold **FUNDED**; collaboration → `IN_PROGRESS`.
3. **Deliverable approved** → brand `POST /deliverables/{id}/approve` auto-calls `EscrowService.tryReleaseOnApproval`. Gated by hold FUNDED + release_condition (`ON_APPROVAL`/`ON_POSTED`/`ON_VERIFIED_METRICS`) + no active dispute. **Platform fee (15%) deducted at release**; **NET** credited clearing → **creator wallet**. Creator service invoice (Doc#2) minted.
4. **Creator receives payment — two rails:**
   - **Milestone payout (brand/admin triggered):** `POST /wallet/escrow/payout` → RazorpayX IMPS to the creator's primary bank/UPI.
   - **Creator self-withdrawal (creator triggered):** **`POST /wallet/withdraw`** `{amount}` (₹500–₹100,000, max 3/day, Idempotency-Key). Requires a primary payout method added via `POST /wallet/payout-methods` (AES-GCM encrypted, 24h cool-down). Returns `{payoutId}`.
5. **Settlement** → webhook `payout.processed|reversed|rejected` → `PayoutReconciliationService`; on reversal the net is re-credited to the creator wallet.

> **There is no "request payment from brand" button.** The creator's "request payment" = **wallet withdrawal** of an already-released balance. Brands *release escrow*; creators *withdraw*.

---

## 4. Important points (honesty — gaps & caveats)

- **Deal-room action handlers are UI-only stubs** (`src/pages/creator-chat.tsx` ~715–793): submit deliverable, submit counter, shipping address, confirm receipt, start revision are `setTimeout` placeholders with `// In production:` comments and **no `api.*` call**. The real endpoints exist (`POST /creator/deliverables/{id}/submit`, etc.) but the deal-room UI doesn't call them yet. **Biggest gap** — from the deal room a creator can chat and read the contract but cannot actually submit work.
- **Tax identity submission not implemented** — `api.creatorTaxIdentity.submit` always rejects `NOT_IMPLEMENTED` (backend not built).
- **Coupon `redirectUrl`** forward-declared, always `undefined` until the `/track/click` redirect is added.
- **Analytics scores 404 live** — `SCORE_NOT_FOUND` is an expected empty state (no score computed for a fresh demo account), not a break.
- **Copilot `pending_tagging`** — daily suggestion needs an Instagram business account connected + content tagging before it produces a nudge.
- **Contract-signing caveat** — a real creator-authenticated sign path exists (`recordSignatureForCreator`), but a brand-relay branch also lets an elevated brand member record the creator's out-of-band assent; flagged in-code as residual attribution risk.
- **Frontend defaults to MOCK** — the whole SPA runs on fixtures unless `VITE_API_MODE=live`. Live deployment at 200.141.1.6 is confirmed serving real backend data.

---

## 5. FEATURE TABLE

| # | Feature name | API used | How to use it | When it gets active | How the brand flow ties in | Other (live status / notes) |
|---|---|---|---|---|---|---|
| 1 | **Dashboard / Home** | `GET /wallet`, `GET /deals`, `GET /creator/deliverables`, `GET /me/portfolio(/analytics)` | Landing rollup at `/creator/dashboard` | Immediately after login | Reflects brand deals, escrow, approvals | Wired · live 200 |
| 2 | **Campaign discovery + apply** | `GET /creator/campaigns`, `GET /creator/campaigns/{id}`, `POST /creator/campaigns/{id}/apply` | Browse open campaigns, open detail, Apply | Campaign must be **ACTIVE** | Brand `POST /campaigns` creates it; apply → Collaboration `APPLIED` | Wired · live: 1 campaign (Diwali Skincare) |
| 3 | **Invitations** | `POST /creators/{id}/invite` (brand) → appears in creator deals | Accept/reject from Deals | Brand sends invite | Brand-initiated; Collaboration `INVITED` | Wired |
| 4 | **Deals (inbox/negotiation)** | `GET /deals`, `POST /deals/{id}/accept\|reject\|counter` | Accept/counter/reject offers at `/creator/deals` | On invite/apply/proposal | Brand `POST /deals` proposes; accept → `TERMS_AGREED` | Wired · live: 1 CONTRACTED deal |
| 5 | **Deal Room / Chat** | `GET/POST /deals/{id}/messages`, SSE `/messages/stream`, `GET /contracts/{id}` | Message brand, read contract, (submit work) | Once a deal exists | Two-way timeline with the brand | Msg+contract wired; **submit/counter/shipping/receipt handlers are STUBS** |
| 6 | **Contract sign** | `POST /contracts/{id}/sign`, `GET /contracts/{id}/pdf-download-url` | Sign after terms agreed | Status `CONTRACT_PENDING` | Brand `POST /contracts` generates; both sign → `CONTRACTED` | Real · live: contract `01KY77ZAZ…` ACTIVE |
| 7 | **Deliverables** | `GET /creator/deliverables`, `POST /{id}/upload\|submit\|metrics\|proof\|mark-posted` | Upload → submit → mark posted → report metrics | After escrow funded → `IN_PROGRESS` | Brand approves/revises (`/deliverables/{id}/approve`); approval releases escrow | Backend wired; **deal-room submit UI stubbed** |
| 8 | **Wallet / balance / ledger** | `GET /wallet/balance`, `GET /wallet`, `GET /wallet/transactions` | View balance & transaction history at `/creator/wallet` | Wallet lazily created on first credit | Credited when brand releases escrow | Wired · live: balance ₹0, no txns |
| 9 | **Payout methods** | `GET/POST /wallet/payout-methods`, `PUT /{id}/primary` | Add UPI/bank, set primary | Before first withdrawal (24h cool-down) | — | Wired · live: none added |
| 10 | **Request payment (withdraw)** | **`POST /wallet/withdraw`** `{amount}` + Idempotency-Key | Enter amount, withdraw released balance | Balance > 0 + primary payout method | Brand releases escrow → balance; creator pulls it | Wired · RazorpayX real (stub only in dev) |
| 11 | **Platform fee transparency** | `GET /creator/platform-fee` | Shows the take rate | Always | Fee deducted at brand's escrow release | Wired · live: **15%** |
| 12 | **Invoices (earnings + commission)** | `GET /creator/campaign-invoices(/{id}/pdf)`, `GET /creator/commission-invoices(/{id}/pdf)` | View/download PDFs in Settings | Generated at escrow release | Mirrors brand-side invoicing | Wired · live: empty (no release yet) |
| 13 | **Analytics (self)** | `GET /creator/analytics/me/metrics\|scores\|demographics\|media` | View at `/creator/analytics` | After data/score computed | Creator-self-reported (honesty rule) | Wired; scores 404 until computed (live) |
| 14 | **Co-pilot (daily AI nudge)** | `GET /creator/copilot/suggestion/today`, `POST /{id}/dismiss\|acted` | See today's suggestion, dismiss/act | After IG connect + content tagging | Creator-only (brand uses Meera) | Wired · live: `pending_tagging` |
| 15 | **Portfolio (public page)** | `GET/PATCH /me/portfolio`, `/sync`, `/cover`, `/analytics`; public `GET /portfolio/{username}`, `POST /{username}/contact` | Edit page, share `influora.com/@you` | Immediately | Brands view/contact via public page | Wired · live: 150 profile clicks, 1 inquiry |
| 16 | **Profile (private)** | `GET/PATCH /me/creator-profile` | Edit self profile | Immediately | Feeds brand discovery | Wired |
| 17 | **Reviews** | `POST /creator/reviews`, `GET /creator/reviews/received`, `POST /{id}/flag` | Rate the brand after completion | Collaboration `COMPLETED` | Brand rates creator symmetrically | Wired |
| 18 | **Disputes** | `GET /creator/disputes`, `POST /deals/{dealId}/disputes` | Raise/track a dispute | Any time a deal exists | Either party opens; admin resolves → escrow split | Wired |
| 19 | **Coupons (affiliate codes)** | `GET /creator/coupons` | View assigned codes | Brand issues affiliate campaign | Brand creates coupon | Wired; `redirectUrl` gap |
| 20 | **Affiliate earnings** | `GET /creator/affiliate-earnings` | View settled vs unsettled | On tracked conversions | Brand conversion webhooks feed it | Wired · live: empty |
| 21 | **Settings** | `POST /notifications/preferences`, `DELETE /me/account`, Meta OAuth, `creatorTaxIdentity.submit` | Notifications, connected accounts, delete | Immediately | — | Mostly wired; **tax identity NOT_IMPLEMENTED** |
| 22 | **Onboarding** | `POST /onboarding/creator/socials\|profile\|complete\|kyc\|payout` | Multi-step signup | At registration | — | Wired |
| 23 | **Meta/Instagram OAuth** | `GET /meta/oauth/authorize`, `GET /meta/oauth/callback` | Connect IG business account | For copilot/analytics | — | Wired |

**Legend:** *Wired* = calls a real backend endpoint in live mode. *Stub* = UI-only handler with no API call. *Real* = backend logic + persistence + (where relevant) Razorpay confirmed.
