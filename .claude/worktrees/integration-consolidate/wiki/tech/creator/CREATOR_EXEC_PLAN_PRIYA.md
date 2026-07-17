# CREATOR BUILD — CTO ARCHITECTURE PLAN

> **Author:** Priya (CTO)
> **Date:** 2026-07-09
> **Method:** Full read of all 14 specs in `wiki/tech/creator/` + direct audit of `influora-api` (67 entities, 42 repositories, 25 controllers, 78+ services), `influora-ai`, `src/lib/api.ts` (1974 lines), `src/lib/types.ts`, `src/App.tsx` routing, and all 15 `creator-*.tsx` / 22 `brand-*.tsx` pages.
> **Companion doc:** `TECH-STACK.md` (root) — written in this same pass; establishes the real stack (Vite+React Router, Spring Boot 3/MySQL, FastAPI sidecar) since no such file existed before.
> **Prior art referenced:** `wiki/tech/MASTER_IMPLEMENTATION_PLAN.md`, `wiki/tech/REMAINING_WORK_PLAN.md`, `wiki/tech/FEATURE_GAP_ANALYSIS.md` — this plan follows the same wave/owner/acceptance format and does not re-litigate standing rules already locked there.

---

## 0. Executive Summary — read this first

**The wiki/tech/creator/00–13 specs describe a system that was never built the way they describe it, and we should not build it that way now.** They model the creator journey as ~9 separate entity families (`Bid`, `BidHistory`, `NegotiationChat`, `CampaignApplication`, `CampaignInvitation`, `Conversation`, `Message`, `Deliverable`, `DeliverableVersion`, `AiInsight`, etc.) each with its own controller, in a Next.js/`src/app/` shape. **None of that exists, and the codebase we actually have already made a different, better call for the brand side that we must mirror, not override.**

What actually exists (confirmed by direct read, not spec):

- **A single `Collaboration` entity** (not Bid + Application + Invitation) already carries `campaignId`, `creatorId`, `status` (`INVITED → APPLIED → SHORTLISTED → IN_NEGOTIATION → TERMS_AGREED → CONTRACT_PENDING → CONTRACTED → IN_PROGRESS → REVIEW_PENDING → REVISION_REQUESTED → COMPLETED/CANCELLED/DISPUTED`), and `source` (`INVITATION`/`APPLICATION`). This is a state machine, not a stack of separate tables.
- **The frontend already abstracted this into a single `Deal` concept** (`src/lib/api.ts` `deals`/`messages`/`contracts`/`deliverables` clients, and a `TimelineEvent` type in `types.ts` that tags one event stream as `message | proposal | contract | deliverable | payment | system`).
- **The brand side already consolidated its UI around this**: `App.tsx` shows `/brand/deals`, `/brand/pipeline`, and `/brand/contracts` all now *redirect* to one chat-first surface, `/brand/chat` (see the `DealRedirect`/`BrandMessagesRedirect` components and the comment "Deal Room was consolidated into the chat-first surface at /brand/chat"). This was a real architecture decision made during the brand build and it is the correct one to extend to creator.
- **The backend has not caught up to this UI decision yet.** There is no `DealController`, no `Message`/`Conversation` entity, no `Proposal` entity. `AiConversation`/`AiMessage` exist but are Meera-only. This is a known, CTO-tracked gap (`FEATURE_GAP_ANALYSIS.md` A5) that blocks brand AND creator equally — **it is not creator-specific debt to duplicate, it is the #1 shared blocker.**
- **Money rails are further along than anything else and are already creator-aware at the data-model level.** `Wallet.forUser()` ("lazily created the first time a creator needs a wallet to receive a payout"), `EscrowHold`, `PaymentMilestone`, `PayoutService` (RazorpayX, ULID idempotency, ownership-before-state checks, Kabir-reviewed) all already resolve the *creator* as payee from `Collaboration.creatorId`. **The gap is entirely the creator-facing read/withdraw HTTP surface, not the ledger engine.**
- **Meta OAuth connect for creators is real and production-grade already** (`MetaOAuthController`, `MetaTokenStorage` AES-256-GCM, CSRF state store, Kabir-signed-off) — this is the one creator vertical slice that is genuinely close to done end-to-end.
- **Creator frontend pages mostly exist as UI shells (15 of them) but are wired to nothing.** `creator-login.tsx` calls `createMockCreatorUser()` and writes `localStorage.setItem('creator_token', 'mock_creator_token')` directly — it does not even call `api.auth.creatorLogin()`, which already exists in `src/lib/api.ts` and is written correctly, pointing at a `/auth/creator/login` endpoint that **does not exist on the backend** (`AuthController` only has `/auth/brand/*`). This is the pattern across almost every creator page: the frontend client (`src/lib/api.ts`) was written *ahead* of the backend, with explicit, honest `NOT_IMPLEMENTED` gap comments already in the code for: creator wallet/withdraw, creator affiliate earnings, creator coupons, deals, messages, portfolio (backend), integration status. **Ananya already did the hard job of documenting the gaps. This plan turns those comments into Vikram's backlog.**

**Bottom line completion estimate: creator backend ≈ 20%, creator frontend UI shell ≈ 75% built but ≈ 10% actually wired to a real API.** Brand is ~90%+ per `MASTER_IMPLEMENTATION_PLAN.md`. The creator gap is overwhelmingly a **backend** gap, not a design or component gap.

---

## 1. Architecture Decision (locked, Priya sign-off)

1. **Do not build the specs' separate `Bid`/`CampaignApplication`/`CampaignInvitation`/`Conversation`/`Message` entity families.** Extend the existing `Collaboration` entity and add exactly one new entity family: a `DealMessage`/timeline table that unifies proposal/contract/deliverable/payment/system events under one collaboration-scoped stream, matching `TimelineEvent` in `types.ts` and the `deals`/`messages` clients already written in `src/lib/api.ts`. One new controller (`DealController`) replaces the specs' `CampaignApplicationController`, `BidController`, `MessagingController` (brand↔creator half — Meera stays separate), and doubles as the creator-facing view of `Collaboration`.
2. **Reuse `Contract`/`PaymentMilestone`/`EscrowHold`/`Wallet` as-is.** These are correct and Kabir-hardened. The only change needed is a **creator-facing access path** — today `ContractController`, `WalletController`, `EscrowController` all unconditionally call `brandContext.requireBrandWorkspace(principal)`, which means **a creator principal cannot call any of them today — not "gap," a hard 403.** Fix: introduce `CreatorContextService` (mirrors `BrandContextService`) and branch each controller method by `principal.getUserType()`, or split creator-safe read/action endpoints onto the same URL paths with a role check. Vikram decides the exact split; either way, no new money logic — same services, new access path.
3. **`DeliverableMetric` (self-reported post-performance) is not the same as "submit deliverable for review."** The former exists; the latter (upload → submit → brand reviews → approve/request-revision) does not exist at all and is new work, folded into the unified `DealMessage`/timeline model as `tag: 'deliverable'` events, not a separate CRUD resource — this matches `TimelineEventMetadata.deliverableId/deliverableStatus/revisionCount` already defined in `types.ts`.
4. **Discovery stays MySQL-native**, per the locked Phase 2 ruling (`wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md` — same rule applies to spec 04's proposed Elasticsearch index: no new datastore, extend `CreatorDiscoveryService`'s existing query approach).
5. **Portfolio (`docs/CREATOR-PORTFOLIO-PAGE.md`, `creator-portfolio-editor.tsx` / `creator-portfolio-public.tsx`) is real scope, additional to the 13 numbered specs**, and 100% backend-unbuilt. It is public-facing (SEO value, ties to `FEATURE_GAP_ANALYSIS.md` B2 "Public Creator Marketplace + SEO Landing Pages" — Tejas/Aditya will want this prioritized). Included in this plan as its own module.
6. **YouTube/Facebook-page OAuth (spec 03) is out of scope for the first creator milestone.** Meta/Instagram connect is done; do not fork effort until the core deal loop (browse → apply/invite → negotiate → contract → deliver → get paid) works end-to-end for Instagram-only creators. Flag to Rohan/Swapnil as a deliberate sequencing call, not an oversight.
7. **Platform fee model (spec 10's `PlatformFeeConfig`/`PlatformFeeChangeLog`, admin-configurable, resolution order creator-override → plan → global-default) does not exist in any form** — not even a hardcoded constant was found in `EscrowService`/`PayoutService`. This is a **business-model blocker** (money is already flowing through escrow with an implicit, undocumented 0% platform take). Escalating to Swapnil per standing rule (`FEATURE_GAP_ANALYSIS.md` A3 precedent) — Vikram should not invent a fee number.

---

## 2. Module Map — mirrors brand structure 1:1

Brand pattern → Creator pattern, file-for-file. Where a brand file is the template, it's named explicitly.

### 2.1 Auth & Onboarding

| Layer | Brand (exists) | Creator (target) | Status |
|---|---|---|---|
| Backend controller | `AuthController` (`/auth/brand/*`) | Add `/auth/creator/login`, `/auth/creator/register`, `/auth/creator/send-email-otp`, `/auth/creator/verify-email` to the **same** `AuthController` (mirror brand methods) | ❌ missing |
| Backend service | `AuthService.brandRegister/brandLogin` | Add `AuthService.creatorRegister/creatorLogin` (same `User`/`UserType.CREATOR`, same `RefreshToken`/JWT plumbing — no new auth primitives) | ❌ missing |
| Onboarding controller | `OnboardingController` (`/onboarding/brand/*`) | Add `/onboarding/creator/socials`, `/profile`, `/complete`, `/kyc`, `/payout` to the same controller pattern | ❌ missing (frontend client already written in `api.ts` `onboarding.*`, pointing at these exact paths) |
| Frontend pages | `brand-login.tsx`, `brand-register.tsx`, `brand-onboarding.tsx`, `brand-forgot-password.tsx` | `creator-login.tsx`, `creator-register.tsx`, `creator-onboarding.tsx` exist; **no creator forgot-password page** | ⚠️ UI exists, not wired; forgot-password page missing entirely |
| Pattern to copy | `brand-login.tsx` calls `api.auth.brandLogin()`, handles `ApiError`, routes on `onboardingComplete` | `creator-login.tsx` currently bypasses `api.auth.creatorLogin()` entirely | 🔴 rewrite required |

### 2.2 Profile

| Layer | Brand equiv. | Creator | Status |
|---|---|---|---|
| Entity | `BrandProfile` | `CreatorProfile` (exists: id, userId, displayName, bio, avatarUrl, coverImageUrl, city, categoriesJson, languagesJson, contentStylesJson, rateMin/Max, currency, verified, discoverable, engagementRate, totalFollowers) | ✅ solid MVP shape — **missing:** `username`/handle (unique, for portfolio URL `/@username`), onboarding-step/completion flag |
| Repository | `BrandProfileRepository` | `CreatorProfileRepository` | ✅ exists |
| Controller (self-service) | none needed (workspace = brand profile) | none exists for creator self-edit (`PATCH /me/creator-profile`) | ❌ missing — `CreatorController` today is brand-discovers-creator only (`/creators/*`), there is no creator-edits-own-profile endpoint |
| Frontend | — | `creator-profile.tsx` | ⚠️ exists, needs wiring |

### 2.3 Social Connect (OAuth)

| Layer | Pattern source | Creator | Status |
|---|---|---|---|
| Controller | `ShopifyConnectController` (authorize/callback JSON-envelope pattern) | `MetaOAuthController` (`/meta/oauth/authorize`, `/callback`) | ✅ **done, Kabir-signed-off** |
| Token storage | — | `MetaTokenStorage` (AES-256-GCM) | ✅ done |
| Status/disconnect | `storeIntegrations.status()`/`.disconnect()` — also missing on brand side | Meta connection status/disconnect | ❌ missing (same gap class as brand's Shopify/WooCommerce — one `IntegrationStatusController` could serve both) |
| Frontend | — | `creator-meta-callback.tsx`, settings panel in `creator-settings.tsx` | ⚠️ exists, real API already wired in `api.ts` (`metaOAuth.*`) — this is the most-done creator vertical |
| YouTube/Facebook | n/a | Not started | ❌ explicitly deferred (§1.6) |

### 2.4 Discovery / Campaign Browse

| Layer | Brand equiv. | Creator | Status |
|---|---|---|---|
| Controller | `CampaignController` (brand manages own campaigns) | New: `GET /creator/campaigns` (browse open campaigns matching profile), `POST /creator/campaigns/{id}/apply` | ❌ missing entirely |
| Service | `CampaignService` | New `CreatorCampaignService` — reuses `CampaignRepository`/`Campaign` entity (read-only + creates a `Collaboration` with `source=APPLICATION`, mirrors `CreatorDiscoveryService.invite()`'s `Collaboration.invite()` factory) | ❌ missing (factory method `Collaboration` needs a sibling `Collaboration.apply(...)`) |
| Frontend | `brand-discover.tsx`, `brand-campaigns.tsx` | **No `creator-campaigns.tsx`/`creator-discover.tsx` page exists at all** | ❌ missing — biggest single frontend gap |

### 2.5 Deals / Negotiation / Chat / Contract / Deliverables (unified — see §1.1)

| Layer | Brand equiv. | Creator | Status |
|---|---|---|---|
| Controller | none (brand also has no `DealController` — same gap) | New `DealController`: `GET /deals`, `GET /deals/:id`, `POST /deals` (brand-initiated proposal), `POST /deals/:id/accept\|reject\|counter`, `GET/POST /deals/:id/messages`, `POST /deals/:id/messages/read` | ❌ missing — shared brand+creator blocker, `FEATURE_GAP_ANALYSIS.md` A5 |
| Entity | — | New `DealMessage`/timeline table (collaboration-scoped, `tag` enum matching `TimelineEventMetadata`) + reuse `Collaboration` for negotiation state | ❌ missing |
| Contract sub-flow | `ContractController`, `Contract`, `PaymentMilestone` | Same entities; needs creator-callable `POST /contracts/:id/sign` path (currently blocked, see §1.2) | ⚠️ data model done, access path broken for creator |
| Deliverables sub-flow | — | New: file upload + submit/approve/revise, folded into `DealMessage` `tag: 'deliverable'` events (not a separate CRUD table per §1.3) | ❌ missing |
| Frontend | `brand-chat.tsx`, `brand-deals.tsx` (redirects to chat) | `creator-deals.tsx`, `creator-chat.tsx`, `creator-inbox.tsx` (legacy redirect) | ⚠️ UI exists, 100% mock — this is the highest-value wiring target once `DealController` ships |

### 2.6 Payments / Wallet

| Layer | Brand equiv. | Creator | Status |
|---|---|---|---|
| Controller | `WalletController` (`/wallet`, `/wallet/balance` — brand-gated) | Same paths, need creator branch (role-aware, see §1.2); `POST /wallet/withdraw`, `GET /wallet/transactions` already expected by `api.ts` | ⚠️ engine done (`Wallet.forUser`, `WalletTransaction`, `PayoutService`), HTTP surface brand-only |
| KYC/payout method | — | No `PayoutMethod`/bank-verification entity exists; `onboarding.submitCreatorKyc`/`saveCreatorPayout` client methods already call `/onboarding/creator/kyc`/`/payout` | ❌ missing entity + endpoints |
| Platform fee | — | No entity anywhere | ❌ missing — **Swapnil decision required**, §1.7 |
| Affiliate earnings read | `AffiliateEarningsService`/`AffiliateSettlementBatch` compute-side only | `GET /creator/affiliate-earnings` | ❌ missing (documented gap, already in `api.ts`) |
| Frontend | `brand-wallet.tsx` | `creator-wallet.tsx`, `creator-affiliate-earnings.tsx`, `creator-coupons.tsx` | ⚠️ UI exists, honest "not available yet" states already coded for the NOT_IMPLEMENTED paths |

### 2.7 Analytics

| Layer | Brand equiv. | Creator | Status |
|---|---|---|---|
| Controller | `AnalyticsController` (`GET /analytics/creators/{id}/metrics\|scores\|demographics` — brand viewing a creator) | New: `GET /creator/analytics/dashboard` (self-view of the same underlying `CreatorMetric`/`CreatorScore`/`AudienceDemographics` rows, no new computation) | ❌ missing endpoint only — computation layer 100% reusable |
| Entity | `CreatorMetric`, `CreatorScore`, `AudienceDemographics`, `MediaMetric` | Same | ✅ exists |
| AI Insights | — | No `AiInsight` entity/service | ❌ new (small — a templated-insight generator over existing metrics, not new AI infra) |
| Frontend | `brand-analytics.tsx`, `brand-creator-analytics.tsx` | **No creator-facing analytics page exists** | ❌ missing |

### 2.8 Portfolio (additional scope, not in the 13 numbered specs)

| Layer | Source | Status |
|---|---|---|
| Controller | `api.ts` `portfolio.*` fully specified (`GET /portfolio/:username` public, `GET/PATCH /me/portfolio`, `/me/portfolio/sync`, `/cover`, `/analytics`, `POST /portfolio/:username/contact`) | ❌ no `PortfolioController` exists |
| Entity | — | ❌ no `Portfolio`/`PortfolioCustomLink`/`PortfolioPinnedPost` entities — likely composed at read-time from `CreatorProfile` + `Collaboration` (completed) + `PlatformStats` rather than a new denormalized table; Vikram to confirm during design |
| Frontend | `creator-portfolio-editor.tsx`, `creator-portfolio-public.tsx` | ✅ fully built UI, mock-wired |

---

## 3. API Contract Gaps — % complete per feature (from `00_CREATOR_MASTER_PLAN.md` scope)

| # | Feature (spec doc) | Backend endpoints needed | Backend endpoints existing | Frontend page | Est. % complete |
|---|---|---|---|---|---|
| 01 | Auth | 6 (`/auth/creator/*`) | 0 | ⚠️ mocked | **15%** |
| 02 | Profile/Onboarding | 6 (`/onboarding/creator/*`, `/me/creator-profile`) | 0 | ⚠️ mocked | **30%** (entity solid, no endpoints) |
| 03 | OAuth Connect | Meta: done. YouTube: 0. Status/disconnect: 0 | Meta authorize+callback | ⚠️ Meta wired live | **55%** (Meta-only) |
| 04 | Discovery (brand→creator exposure) | mostly exists | `/creators` search/get/save/invite | n/a (brand-side) | **70%** |
| 05 | Campaign Browse/Apply (creator→campaign) | 2–3 | 0 | ❌ page doesn't exist | **8%** |
| 06 | Bids/Negotiation | folded into `DealController` | 0 | ⚠️ mocked | **5%** |
| 07 | Contracts | creator access path only | Data model + brand path done | ⚠️ mocked | **40%** |
| 08 | Chat (brand↔creator) | folded into `DealController` | 0 (Meera-only chat exists, unrelated) | ⚠️ mocked | **5%** |
| 09 | Deliverables | folded into `DealController` timeline + upload | `DeliverableMetric` (post-hoc metrics) only | ⚠️ mocked | **15%** |
| 10 | Payments/Wallet | creator access path + KYC/payout-method + platform fee | Ledger/escrow/payout engine done | ⚠️ mocked, honest gap states | **45%** |
| 11 | Analytics | 1 new endpoint (`/creator/analytics/dashboard`) + `AiInsight` | Computation layer done | ❌ page doesn't exist | **25%** |
| 12 | Security | `CreatorContextService` + apply existing patterns | JWT/refresh/encryption/idempotency patterns all done | n/a | **60%** (patterns exist, application to creator paths is the work) |
| 13 | QA | Creator test suite | Brand test plan exists as template | n/a | **10%** |
| — | Portfolio (extra) | 6 endpoints | 0 | ✅ full UI | **20%** |

**Blended creator completion: ~28%**, weighted toward "data model and money engine are ahead of schedule, HTTP access surface and the unified deal/chat backbone are the blocker."

---

## 4. Database Entities — needed vs existing

### Existing, reuse as-is
`User` (UserType.CREATOR already supported), `CreatorProfile`, `CreatorScore`, `CreatorMetric`, `MediaMetric`, `AudienceDemographics`, `PlatformStat`, `SavedCreator`, `Collaboration`, `Contract`, `PaymentMilestone`, `EscrowHold`, `Wallet` (already `forUser()`-capable), `WalletTransaction`, `MetaOAuthToken`, `AffiliateEarning`, `AffiliateSettlementBatch`, `CouponCode`, `CouponRedemption`, `UtmCampaign`, `Notification`, `EmailPreference`, `RefreshToken`, `PasswordResetToken`, `EmailOtpChallenge`, `AuditLogEntry`, `IdempotencyKeyRecord`.

### New entities needed
| Entity | Purpose | Notes |
|---|---|---|
| `DealMessage` | Unified per-`Collaboration` timeline event (message/proposal/contract/deliverable/payment/system) | Replaces specs' separate `NegotiationChat`, `Conversation`, `Message`. Mirrors `TimelineEvent`/`TimelineEventMetadata` in `types.ts` field-for-field. |
| `Deliverable` (lean) | Tracks file(s) + status per collaboration deliverable slot, referenced by `DealMessage.metadata.deliverableId` | Do **not** build the spec's separate `DeliverableVersion`/`DeliverableFile` tables — one row per deliverable slot with a JSON file-list column, matching how `CreatorProfile` already uses JSON columns for lists. |
| `PayoutMethod` | Creator's bank/UPI destination for KYC-gated withdrawal | Referenced by `onboarding.saveCreatorPayout` client call already written. |
| `CreatorKycRecord` | PAN/Aadhaar-last-4/selfie, verification status | Referenced by `onboarding.submitCreatorKyc`. Kabir must review storage (PII — see §5). |
| `PlatformFeeConfig` + `PlatformFeeChangeLog` | Admin-configurable take rate | **Blocked on Swapnil business decision (§1.7)** — do not build the tables until the resolution order (creator-override → plan → global-default) is confirmed as final. |
| `AiInsight` | Creator-facing generated insight cards (growth/rate/content/engagement) | Small — templated over existing `CreatorMetric`/`CreatorScore`, not a new AI pipeline. |
| `PortfolioCustomLink`, `PortfolioPinnedPost` (or one JSON column each on `CreatorProfile`) | Portfolio page content | Vikram to decide JSON-column vs. child-table during design; lean toward JSON columns to match existing `CreatorProfile` convention. |

### Explicitly rejected (do not build)
`Bid`, `BidHistory`, `CampaignApplication` (separate from `Collaboration`), `CampaignInvitation` (separate from `Collaboration`), `Conversation` (separate from the unified `DealMessage` timeline), `ContractTemplate` (over-engineering for current scale — template lives in `ContractPdfService` code, not DB, until proven otherwise).

---

## 5. Security Requirements — cross-ref `12_CREATOR_SECURITY_SPEC.md`

| Spec requirement | Current state | Action |
|---|---|---|
| Password hashing (bcrypt), OTP security | `AuthService`/`BrandEmailOtpService` already implement this for brand via Spring's `PasswordEncoder` | Reuse verbatim for `AuthService.creatorRegister/creatorLogin` — **no new crypto code**, just new call sites. |
| JWT access + rotating refresh in HttpOnly cookie | Done (`AuthCookieService`, Kabir A1/B3) | Reuse verbatim — `AuthController.refresh`/`logout` are already role-agnostic (work off `AuthPrincipal`, not brand-specific). |
| OAuth state CSRF, token encryption | Done for Meta (`MetaOAuthStateStore`, `MetaTokenStorage` AES-256-GCM) | Template for any future YouTube connect — do not re-derive the pattern. |
| Workspace/tenant isolation | `BrandContextService.requireBrandWorkspace/requireMember` pattern, resolve-then-scope | **New `CreatorContextService` required** — same pattern, scoped to `creatorProfileId` derived from `principal.getUserId()`, never trust a path param. This is the single security-critical prerequisite for §1.2's controller fixes — Kabir review is **load-bearing** here, not routine, since it gates money (`WalletController`, `EscrowController`) and contract signature (`ContractController`). |
| PII encryption/masking (KYC docs, PAN, Aadhaar) | No creator KYC entity exists yet | New `CreatorKycRecord` — Kabir must review encryption-at-rest and access-logging before this ships; treat like `MetaTokenStorage`'s AES pattern, not plaintext columns. |
| File upload validation (type/size/virus-scan/signed URLs) | Generic `/uploads` endpoint exists (`api.ts` `uploads.upload`), presigned R2 pattern established | Deliverable file uploads (§2.5) must reuse this, add type/size allowlist for deliverable content specifically (video/image), per spec. |
| Rate limiting | Established pattern seen on `ConversionWebhookController`/public endpoints | Apply same middleware to any new public creator surface (`GET /portfolio/:username`, `POST /portfolio/:username/contact` — public, anti-spam required per `api.ts` comment). |
| Input validation (SQLi/XSS) | `jakarta.validation` (`@Valid`) used consistently across existing controllers | Same discipline mandatory on every new DTO. |
| Idempotency on money/webhook mutations | `IdempotencyService.executeOnce` pattern, used correctly in `PayoutService`/`AffiliateEarningsService`/`RedemptionService` | Mandatory reuse for: deal accept/counter (double-submit risk), contract sign, escrow-adjacent creator actions. |
| Audit trail | `AuditLogEntry`/`AuditLogService.recordMoneyEvent` exists and is used on money events | Extend to creator withdrawal requests and contract signatures. |
| Red Team Checklist / sign-off gate | Established process in `REMAINING_WORK_PLAN.md` (owner → Kavya → **Kabir load-bearing on money/public/webhook surfaces** → Meera live-verify → Arjun re-check) | **This exact pipeline is mandatory for every creator wave below** — it already caught real bugs on brand (coupon-collision race, redemption TOCTOU, webhook test masking a routing bug). No shortcuts because "it's just creator." |

**Kabir's two hard blockers before any creator money endpoint ships:** (1) `CreatorContextService` isolation review, (2) confirm no controller lets a creator principal resolve or act on another creator's `Collaboration`/`Wallet`/`Contract` row via ID-guessing (the exact class of bug `PayoutService`'s "ownership before state" fix addressed for brand — same discipline required in reverse for creator-initiated calls).

---

## 6. Frontend Component Inventory — reuse vs new

### Reuse verbatim (already exist, brand-proven patterns)
`AuthLoginShell`, `Button`/`Input`/`Label`/all `src/components/ui/*` primitives, `motion-config` (`DURATION_FAST/NORMAL`, `EASE_OUT`), `ApiError`/`http` client in `api.ts`, `useAuthStore` (zustand), toast (`sonner`), `CreatorLayout` (already exists per `App.tsx` import), the entire `creators`/`analytics`/`metaOAuth` client sections of `api.ts` (already correct, just need a live backend).

### Exist as UI shells, need real API wiring (highest-leverage work — no new design)
`creator-login.tsx` (rewrite to call `api.auth.creatorLogin`, drop the mock bypass), `creator-register.tsx`, `creator-onboarding.tsx`, `creator-profile.tsx`, `creator-settings.tsx`, `creator-wallet.tsx`, `creator-deals.tsx`, `creator-chat.tsx`, `creator-portfolio-editor.tsx`, `creator-portfolio-public.tsx`, `creator-coupons.tsx`, `creator-affiliate-earnings.tsx`, `creator-meta-callback.tsx` (already correctly wired — leave alone).

### Missing entirely — new pages needed (mirror brand equivalent named)
| New creator page | Brand template to mirror |
|---|---|
| `creator-campaigns.tsx` (browse/apply) | `brand-discover.tsx` (filter/search UI pattern) |
| `creator-campaign-detail.tsx` | `brand-campaign-detail.tsx` |
| `creator-analytics.tsx` | `brand-analytics.tsx` |
| `creator-forgot-password.tsx` | `brand-forgot-password.tsx` |

### Legacy/retired, do not rebuild
`creator-inbox.tsx`, `creator-active.tsx` — `App.tsx` already redirects these into `/creator/deals?status=...`; keep the redirect pattern, do not resurrect separate pages (matches the brand `/brand/deals` → `/brand/chat` precedent exactly).

---

## 7. Definition of Done — creator = 100% complete

- [ ] Creator can register/login/reset password against real `/auth/creator/*` endpoints (no mock bypass in any code path reachable from a production build — `assertMockAuthAllowed()` gate stays honored).
- [ ] Creator completes onboarding (profile, at least Instagram connect via existing Meta flow, rate card) against real `/onboarding/creator/*` endpoints.
- [ ] Creator can browse/search open campaigns and apply; brand can see the application in their pipeline (`Collaboration` with `source=APPLICATION`).
- [ ] Brand and creator can negotiate a deal end-to-end through the unified `DealController`/timeline (propose → counter → accept), with real-time-enough polling or the existing SSE `/stream` contract mentioned in `api.ts`'s header comment.
- [ ] Contract generates, both parties can view and sign it (creator access path fixed — no more brand-only 403), PDF download works for both roles.
- [ ] Creator submits a deliverable (file upload, notes), brand approves or requests revision, all visible in the same timeline.
- [ ] Escrow funds → milestone releases → creator wallet balance updates → creator can request a withdrawal (KYC-gated) → payout queues via the existing `PayoutService`/RazorpayX path.
- [ ] Creator can view their own analytics dashboard (metrics/scores/demographics reused from the brand-facing computation layer) plus at least one real `AiInsight`.
- [ ] Public portfolio page (`/@username`) renders, is publishable/editable by the creator, and the contact form works with anti-spam protection.
- [ ] Every new endpoint passes the mandatory review chain: owner → **Kavya** (test quality) → **Kabir** (security, load-bearing on anything touching money/PII/public surface) → **Meera** (live build + schema verify) → Arjun independent re-check.
- [ ] Zero endpoints in `src/lib/api.ts`'s creator-facing sections still throw `NOT_IMPLEMENTED` in live mode.
- [ ] `wiki/tech/approved-deps.md` updated for any new dependency (expect: none required — this build reuses existing stack).
- [ ] Platform fee model resolved (Swapnil decision) and `PlatformFeeConfig` either built or explicitly deferred with a documented interim rate.

---

## 8. Task Breakdown

### Vikram (Backend) — critical path, in dependency order
1. `CreatorContextService` (mirrors `BrandContextService`) — **prerequisite for everything below that touches money or another creator's data.**
2. `AuthController`/`AuthService` creator endpoints (`/auth/creator/login|register|send-email-otp|verify-email`) — reuse existing `User`/JWT/refresh-cookie machinery.
3. `OnboardingController` creator endpoints (`/onboarding/creator/socials|profile|complete|kyc|payout`) + `PayoutMethod`/`CreatorKycRecord` entities.
4. `DealController` + `DealMessage` entity + migration — the unified negotiation/chat/contract-notification/deliverable-notification/payment-notification timeline. This is the largest single piece of new work and unblocks brand too (`FEATURE_GAP_ANALYSIS.md` A5).
5. Creator campaign browse/apply: `GET /creator/campaigns`, `POST /creator/campaigns/{id}/apply`, `Collaboration.apply(...)` factory sibling to the existing `.invite(...)`.
6. Fix `ContractController`/`WalletController`/`EscrowController` to accept a creator principal via `CreatorContextService` for the specific creator-safe actions (view own contract, sign, view own wallet, request withdrawal) — **no changes to money-calculation logic**, only access-path branching.
7. `Deliverable` entity + upload/submit/approve/revise wired into the `DealMessage` timeline.
8. `GET /creator/analytics/dashboard` (reuse `AnalyticsService`) + `AiInsight` entity/generator.
9. `GET /creator/affiliate-earnings`, `GET /creator/coupons` — endpoints only, services already exist (`AffiliateEarningsService`, `CouponCodeService`).
10. `PortfolioController` (`GET /portfolio/:username` public, `GET/PATCH /me/portfolio`, `/sync`, `/cover`, `/analytics`, `/contact`).
11. Escalate platform fee model to Swapnil before building `PlatformFeeConfig` (§1.7) — do not guess a number.
12. Log every new dependency (expect zero) in `wiki/tech/approved-deps.md` before merge.

### Ananya (Frontend)
1. Rewrite `creator-login.tsx`/`creator-register.tsx` to call real `api.auth.*` (drop mock bypass), add `creator-forgot-password.tsx` (mirror `brand-forgot-password.tsx`).
2. Build `creator-campaigns.tsx` + `creator-campaign-detail.tsx` (mirror `brand-discover.tsx`/`brand-campaign-detail.tsx` patterns) once Vikram's task 5 lands.
3. Wire `creator-deals.tsx`/`creator-chat.tsx` to the real `DealController` once Vikram's task 4 lands — this is the highest-value single wiring task in the whole plan.
4. Wire `creator-profile.tsx`, `creator-onboarding.tsx`, `creator-settings.tsx` to real onboarding/profile endpoints.
5. Wire `creator-wallet.tsx` (balance/transactions/withdraw), `creator-affiliate-earnings.tsx`, `creator-coupons.tsx` to their now-real endpoints — remove the `NOT_IMPLEMENTED` branches in `api.ts` as each lands, one PR per endpoint per the established discipline.
6. Build `creator-analytics.tsx` (mirror `brand-analytics.tsx`).
7. Wire `creator-portfolio-editor.tsx`/`creator-portfolio-public.tsx` to real `PortfolioController`.
8. Every animation change follows the repo's Framer Motion skills (`influora-framer-*`) already in `.cursor/skills/` — `useReducedMotion()` bypass non-negotiable per `TECH-STACK.md`.

### Kabir (Security) — load-bearing reviews, not routine sign-off, on:
1. `CreatorContextService` isolation review (before any downstream controller uses it).
2. Creator KYC storage (`CreatorKycRecord`) — PII encryption/masking/access-logging.
3. `ContractController`/`WalletController`/`EscrowController` creator-access-path changes — verify no creator can act on another creator's row.
4. `DealController` — verify a creator/brand can only see/act on their own `Collaboration`'s timeline; idempotency on accept/counter/sign actions.
5. `PortfolioController`'s public endpoints (`GET /portfolio/:username`, `POST /contact`) — rate limiting, anti-spam, no enumeration of private data.
6. Full Red Team Checklist + Security Sign-off per `12_CREATOR_SECURITY_SPEC.md` before creator money flows go live.

### Kavya (QA)
1. Extend `KAVYA_QA_TEST_PLAN.md`/`13_CREATOR_QA_SPEC.md` coverage to every new controller above — unit + integration test quality review before Kabir's pass, per the established pipeline.
2. E2E: creator signup → onboarding → apply to campaign → negotiate → sign contract → submit deliverable → get paid → withdraw. This is the one flow that must work end-to-end before "creator complete" is claimed.
3. Accessibility (WCAG AA) pass on the 4 new pages (§6).
4. Bug severity classification and sign-off checklist per existing brand-side convention.

### Meera (DevOps / Live Verification)
1. Live-MySQL migration verification for every new Flyway migration (`DealMessage`, `Deliverable`, `PayoutMethod`, `CreatorKycRecord`, `AiInsight`, `PlatformFeeConfig` if approved) — same 4-pass discipline used for Phase 2 (caught 6 JSON column mismatches + 1 type mismatch last time; assume it will again).
2. `mvn test` + `npm run build`/`tsc` clean checks after every wave, reported to `SHARED_CONTEXT.md`.
3. Real browser walkthrough of the E2E flow in Kavya's task 2, documented, zero console errors — same standard as Wave A's A5 verification.
4. Monitor for the known cross-repo auth gap (`wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md`) if any creator flow ends up calling `influora-ai` (e.g., a future AI rate-suggestion feature) — flag before shipping, don't assume it's fixed.

### Rohan (Cost Tracking)
1. Track Razorpay/RazorpayX transaction-volume cost impact once creator withdrawals go live (new cost category, not present today since creator payout endpoint doesn't exist yet).
2. Cost-model the platform fee decision options (§1.7) for Swapnil — what take-rate covers RazorpayX payout fees + escrow float cost + margin.
3. No new LLM cost expected for `AiInsight` v1 (templated over existing scores, not a new Anthropic/Gemini call) — flag if that assumption changes during Vikram's build.
4. Log any new paid dependency (none expected) in `wiki/tech/approved-deps.md` cost column.

---

## 9. Suggested Sequencing (waves, mirroring `REMAINING_WORK_PLAN.md` format)

- **Wave 1 — Unblock the ground floor:** `CreatorContextService`, creator auth endpoints, creator onboarding endpoints. Nothing else can be honestly wired until this lands.
- **Wave 2 — The core loop:** `DealController`/`DealMessage` timeline, campaign browse/apply, contract creator-access-path fix. This is the money-shape of the product — prioritize over polish.
- **Wave 3 — Get paid:** wallet/withdraw creator-access-path fix, `PayoutMethod`/`CreatorKycRecord`, deliverables upload/review, platform fee resolution (pending Swapnil).
- **Wave 4 — Grow:** creator analytics dashboard + `AiInsight`, affiliate earnings + coupons read endpoints, portfolio backend.
- **Wave 5 — Expand:** YouTube OAuth, anything Swapnil/Tejas prioritize from `FEATURE_GAP_ANALYSIS.md` Part B that intersects creator (referrals, public SEO pages building on Portfolio).

Each wave follows the non-negotiable pipeline from §5: owner → Kavya → Kabir (load-bearing on money/PII/public) → Meera → Arjun re-check.
