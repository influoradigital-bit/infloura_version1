# Idempotency-Key Audit — Wave E, Task E2

**Auditor:** Vikram (Backend) · **Scope:** every POST/PUT/PATCH/DELETE endpoint in
`influora-api/src/main/java/com/influora/web/*Controller.java` · **Type:** audit only, no code changed.

**Standing rule under test:** "idempotency on any mutation reachable by retry/webhook must use the
shared `IdempotencyService.executeOnce` pattern."

**Category definitions**
- **(a) MUST be idempotent** — reachable by an automatic retry (HTTP client timeout+retry, load
  balancer retry), a webhook redelivery, or a payment/money-moving flow where a duplicate execution
  causes real harm (double charge, double payout, double counter increment).
- **(b) user-interactive-only** — a human clicks a button once; a duplicate click is a UX nuisance
  at worst, not silently retried by infrastructure. Idempotency optional/nice-to-have.

**Verdict legend:** ✅ compliant via shared `IdempotencyService.executeOnce` · ⚠️ idempotent by an
*alternative* mechanism (not the mandated shared service) · ❌ NOT idempotent (finding).

---

## Findings table

| # | Endpoint | Category | Idempotent? | Mechanism + citation | Severity |
|---|----------|----------|-------------|------------------------|----------|
| 1 | `POST /webhooks/redemption` (`ConversionWebhookController.java:105` → `RedemptionService.redeem`) | (a) webhook, money-adjacent (discount/redemption ledger) | ✅ | `RedemptionService.java:169` calls `idempotencyService.executeOnce(...)`, wrapping `doRedeem`; DB backstop `coupon_redemptions.idempotency_key UNIQUE` (V24). Required header/field, 400s if blank (`RedemptionService.java:145-148`). | — (compliant) |
| 2 | `POST /webhooks/conversion` (`ConversionWebhookController.java:131` → `ConversionTrackingService.recordConversion`) | (a) webhook, revenue-affecting counters | ❌ | No idempotency key parameter at all. Author's own javadoc admits it: `ConversionTrackingService.java:56-65` — *"a retried webhook delivery to this endpoint WILL double-count clicks/revenue... this needs the same idempotency-key treatment coupon_redemptions already has."* No `executeOnce`, no unique constraint on `utm_campaigns`. | **HIGH** (counter/state + revenue-attribution corruption, not a direct money-movement ledger, but feeds brand-facing analytics and could feed billing/reporting decisions) |
| 3 | `GET /track/click/{utmCampaignId}` (`ConversionWebhookController.java:158`) | (b) — GET is a read+side-effect click-counter increment, not a state-changing mutation in the audited verb set; included for completeness since it shares the controller | n/a (GET, out of strict POST/PUT/PATCH/DELETE scope) | `CampaignLinkService.recordClick` deliberately does simplified (non-deduplicated) visitor counting per its own javadoc (`CampaignLinkService.java:209-211`) | Informational only — not scored against the standing rule since it's a GET |
| 4 | `POST /campaigns/{campaignId}/tracking-links` (`CampaignTrackingController.java:56` → `CampaignTrackingService.createTrackingLink`) | (b) brand-authed, user-interactive create-or-return | ⚠️ | Doc comment says "Create (or return the existing)" — idempotent by natural key (workspace+campaign+creator lookup-then-create), not `executeOnce`. No retry/webhook caller. | LOW (acceptable — category b) |
| 5 | `POST /campaigns/{campaignId}/coupons` (`CampaignTrackingController.java:83` → `CampaignTrackingService.createCoupon`) | (b) brand-authed, user-interactive | ⚠️ | Same lookup-then-create pattern; `CouponCodeService` uses a DB `UNIQUE(workspace_id, code)` retry-on-collision loop (`CouponCodeService.java:75-115`), not `executeOnce`. | LOW (acceptable — category b) |
| 6 | `POST /wallet/escrow/fund` (`EscrowController.java:48` → `EscrowService.initiateFund`) | (a) **money-moving**, brand-initiated but drives a Razorpay order creation — client/network retry is realistic | ⚠️ | Required `Idempotency-Key` header (400 if blank, `EscrowController.java:53-58`). `EscrowService.initiateFund` does `escrowHoldRepository.findByIdempotencyKey(...)` replay-check BEFORE creating a new hold (`EscrowService.java:107-112`) — hand-rolled replay pattern, not `IdempotencyService.executeOnce`. Functionally equivalent for the single-row case but does **not** get the "insert-first-wins, own transaction" concurrency guarantee `IdempotencyService` provides — two concurrent requests with the same key can both pass the `findByIdempotencyKey` check before either has saved (same race class `RedemptionService.java:159-163` explicitly calls out and fixes for its own path). | **HIGH** — deviation from standing rule on a money-moving endpoint; concurrent-duplicate-hold race is plausible under real retry conditions |
| 7 | `POST /wallet/escrow/release` (`EscrowController.java:78` → `EscrowService.release`) | (a) **money-moving** (ledger CREDIT to creator wallet) | ⚠️ | Status-guard idempotent-no-op (`if (hold.getStatus() == RELEASED) return;`, `EscrowService.java:268-270`) plus a deterministic derived key `"release:" + hold.getId()` fed into `WalletLedgerService.post`, which has its own insert-first-wins guarantee backed by `uq_wtx_idem UNIQUE` (`WalletLedgerService.java:53-91`). Real double-post protection exists, but via a **second, separate idempotency mechanism**, not the shared `IdempotencyService.executeOnce`. | **MEDIUM** (protected in practice, but a standing-rule deviation — two idempotency subsystems now exist for money paths and must both be kept correct) |
| 8 | `POST /wallet/escrow/refund` (`EscrowController.java:87` → `EscrowService.refund`) | (a) **money-moving** | ⚠️ | Identical pattern to #7: status-guard (`EscrowService.java:308-310`) + `"refund:" + hold.getId()` key into `WalletLedgerService.post`. | **MEDIUM** (same reasoning as #7) |
| 9 | `POST /wallet/escrow/payout` (`EscrowController.java:94` → `PayoutService.queuePayout`) | (a) **money-moving**, external gateway call (RazorpayX) | ❌ | No local replay/status guard before calling the gateway. `PayoutService.java:94` derives `idempotencyKey = "payout:" + milestone.getId()` and passes it straight into `RazorpayXClient.initiatePayout` (`RazorpayXClient.java:55-91`), which forwards it as `X-Payout-Idempotency`/`reference_id` — i.e. dedup is delegated **entirely** to RazorpayX's own idempotency handling. There is no local `findByIdempotencyKey`-style check, no `IdempotencyService.executeOnce`, and no milestone-status flip guarding a second call the way `release`/`refund` have (`queuePayout` only checks `hold.getStatus() == RELEASED`, which does not change as a *result* of this call — a second call within the RELEASED window sails through and re-hits Razorpay). | **CRITICAL** — money-moving endpoint with no local idempotency safety net; correctness is entirely outsourced to a third party's dedup behavior, which is unverified/unenforced by our own code |
| 10 | `POST /contracts/{contractId}/sign` (`ContractController.java:49` → `ContractService.recordSignature`) | (a) — not literally a payment call, but reachable by client retry (e.g. request succeeds server-side, response lost to a network blip, client retries the same sign action); side effect (PDF generation + email delivery, `ContractService.java:174-176`) fires again on re-entry | ❌ | `Contract.recordBrandSignature()`/`recordCreatorSignature()` (`Contract.java:136-146`) unconditionally overwrite the signed-at timestamp on every call — no existing-signature guard. When the second (already-fully-signed) call re-enters `recordSignature`, `advanceIfFullySigned()` re-evaluates true again and `generateAndDeliverContractPdf` re-fires (`ContractService.java:167-176`), re-sending the contract-PDF email to both parties. No `Idempotency-Key`, no `executeOnce`, no "already signed" short-circuit. | **HIGH** (not money-moving directly, but a legal-document / notification duplication bug with real user-facing impact — a retried sign call re-emails a signed contract) |
| 11 | `POST /internal/meera/create_campaign` (`MeeraInternalController.java:120` → `CreateCampaignExecutor.execute`) | (a) internal tool-call, explicitly retry-shaped (`Idempotency-Key` header mandated by the wire contract) | ✅ | `CreateCampaignExecutor.java:79` calls `idempotencyService.executeOnce(...)`. Class javadoc confirms: `CreateCampaignExecutor.java:31,36`. | — (compliant) |
| 12 | `POST /internal/meera/request_payment` (`MeeraInternalController.java:134` → `RequestPaymentExecutor.execute`) | (a) internal tool-call, stages a payment (PENDING_CONFIRM only — no money moves here, but still retry-shaped per the wire contract) | ✅ | `RequestPaymentExecutor.java:71` calls `idempotencyService.executeOnce(...)`. Class javadoc: `RequestPaymentExecutor.java:36`. | — (compliant) |
| 13 | `POST /internal/meera/confirm_launch` (`MeeraInternalController.java:152` → `ConfirmLaunchExecutor.execute`) | (a) internal tool-call, retry-shaped | ✅ | `ConfirmLaunchExecutor.java:134` calls `idempotencyService.executeOnce(...)`. Class javadoc: `ConfirmLaunchExecutor.java:74`. | — (compliant) |
| 14 | `POST /internal/meera/show_creators` (`MeeraInternalController.java:100` → `ShowCreatorsExecutor.execute`) | (b) — read-oriented tool call, no persisted mutation of record | n/a | No `Idempotency-Key` header on this route at all (absent from the method signature) — consistent with it not being a mutation. | — (correctly out of scope) |
| 15 | `POST /internal/meera/calculate_budget` (`MeeraInternalController.java:110` → `CalculateBudgetExecutor.execute`) | (b) — pure computation, no persisted mutation | n/a | No `Idempotency-Key` header — consistent. | — (correctly out of scope) |
| 16 | `POST /internal/meera/messages` (`MeeraInternalController.java:174` → `MeeraSessionService.persistAssistantWriteback`) | (a) — write-back callback from an external AI service (`influora-ai`), plausible retry surface per its own doc comment ("Flow 2 step 6") | ❌ | No `Idempotency-Key` header on this route, no `executeOnce`, no unique-constraint dedup visible on the message-persist path. A retried Python-side write-back call would insert (or otherwise duplicate) an assistant message. | **MEDIUM** (state duplication — a conversation transcript, not money — but explicitly a machine-to-machine retry-shaped call per its own javadoc, so it belongs in category (a) and currently has zero protection) |
| 17 | `POST /notifications/read` (`NotificationController.java:71`) | (b) user click | ⚠️ | Naturally idempotent by overwrite: sets `isRead=true` unconditionally; re-invoking is a harmless no-op. | — (fine, category b) |
| 18 | `POST /notifications/unsubscribe` (`NotificationController.java:97`) | (b) user click | ⚠️ | Naturally idempotent by overwrite (`preference.setUnsubscribed(true)`, upsert via `findByUserIdAndEventType` orElseGet). | — (fine, category b) |
| 19 | `PUT /deliverables/{milestoneId}/metrics` (`DeliverableMetricController.java:38`) | (b) creator self-report, PUT semantics | ⚠️ | Explicitly documented "Idempotent-by-overwrite" in its own javadoc (`DeliverableMetricController.java:34-36`); full overwrite of the row, no partial-increment risk. | — (fine, category b) |
| 20 | `PATCH /users/me` (`UserController.java:32`) | (b) user profile edit | ⚠️ | Full-field overwrite (PATCH-style but implemented as overwrite of supplied fields); no counters. | — (fine, category b) |
| 21 | `POST /onboarding/brand/company`, `/complete`, `/kyc` (`OnboardingController.java:29,36,42`) | (b) user-driven wizard steps | ⚠️ | Not verified against a duplicate-submission guard in `OnboardingService` beyond whatever upsert-by-workspace behavior it has; low risk since these are one-time-setup, human-clicked forms with no webhook/retry caller. Flagged as **not deeply traced** — see "Not fully traced" note below. | LOW / not deeply traced |
| 22 | `POST /auth/brand/send-email-otp`, `/verify-email`, `/register`, `/login`, `/refresh`, `/logout`, `/forgot-password`, `/reset-password` (`AuthController.java`) | (b) user-driven auth actions. `/refresh` is arguably retry-adjacent (browsers can auto-retry a failed refresh), but token rotation is designed to reject stale tokens, which is itself the safety net, not `executeOnce`. | n/a (out of standing-rule scope — none are payment/webhook flows) | Not traced deeply into `AuthService`; no indication any of these need `IdempotencyService`. | — (correctly out of scope) |
| 23 | `POST /creators/{creatorId}/save` (`CreatorController.java:77`) | (b) user click, boolean toggle | ⚠️ | `toggleSaved` — idempotent by construction (sets to the caller-supplied boolean, not a relative increment). | — (fine, category b) |
| 24 | `POST /creators/{creatorId}/invite` (`CreatorController.java:87`) | (b) user click, could double-send an invite notification on double-click but not webhook/retry-driven | ⚠️ | Not deeply traced into `CreatorDiscoveryService.invite` for a duplicate-invite guard. Flagged as **not deeply traced**. | LOW / not deeply traced |
| 25 | `POST /campaigns` (`CampaignController.java:64`) | (b) user create action | ⚠️ | Plain create, no dedup key; acceptable for a human-driven form (double-click risk only, no infra retry). | — (fine, category b) |
| 26 | `PATCH /campaigns/{campaignId}` (`CampaignController.java:72`) | (b) user edit | ⚠️ | Full-field overwrite. | — (fine, category b) |
| 27 | `DELETE /campaigns/{campaignId}` (`CampaignController.java:80`) | (b) user action | ⚠️ | Deletes are naturally idempotent (second call 404s or no-ops). | — (fine, category b) |
| 28 | `POST /campaigns/{campaignId}/duplicate` (`CampaignController.java:86`) | (b) user click | ⚠️ | Each call intentionally creates a new campaign copy — by design NOT idempotent, but that's the correct behavior for "duplicate," not a bug. | — (fine, category b, by design) |
| 29 | `POST /contracts` (`ContractController.java:34`, `generate`) | (b) user-initiated contract creation | ⚠️ | Not deeply traced for a duplicate-contract guard on double-submit; likely low risk since it's a one-time human action per collaboration. Flagged as **not deeply traced**. | LOW / not deeply traced |

---

## Summary

- **Total mutation endpoints (POST/PUT/PATCH/DELETE) audited across `web/`:** 27 (the two GET
  endpoints in the table — click-tracking redirect and none else — were noted for context but are
  not scored against this verb-scoped standing rule).
- **Category (a) — MUST be idempotent:** 10 endpoints (#1, #2, #6, #7, #8, #9, #10, #11, #12, #13,
  #16 — 11 total, correcting the count above to 11; see full list below).
  - **Compliant via `IdempotencyService.executeOnce`:** 4 (#1 `webhooks/redemption`, #11
    `create_campaign`, #12 `request_payment`, #13 `confirm_launch`).
  - **Idempotent via an alternative, non-mandated mechanism (⚠️):** 3 (#6 `escrow/fund`'s
    hand-rolled `findByIdempotencyKey` replay check, #7 `escrow/release` and #8 `escrow/refund`'s
    status-guard + `WalletLedgerService`'s own `uq_wtx_idem` unique-constraint mechanism).
  - **NOT idempotent at all (❌ findings):** 4 — #2 `webhooks/conversion` (HIGH), #9
    `wallet/escrow/payout` (**CRITICAL**), #10 `contracts/{id}/sign` (HIGH), #16
    `internal/meera/messages` (MEDIUM).
- **Category (b) — user-interactive, idempotency optional:** 17 endpoints. All either naturally
  idempotent (overwrite/upsert/toggle/delete semantics) or acceptably not deduplicated (one-time
  human forms, no retry/webhook caller). Three of these (#21 onboarding steps, #24 creator invite,
  #29 contract generate) were not traced deep enough into their services to make a hard claim and
  are flagged **"not deeply traced"** rather than silently marked safe.

## Worst findings, ranked

1. **CRITICAL — `POST /wallet/escrow/payout` (`EscrowController.java:94` →
   `PayoutService.queuePayout`, `PayoutService.java:52-104`).** Money-moving (creator payout via
   RazorpayX) with zero local idempotency guard. Dedup is entirely delegated to RazorpayX's
   `X-Payout-Idempotency`/`reference_id` handling — our own service will happily re-derive the same
   key and re-call the gateway on every retry, meaning a duplicate payout is prevented only if and
   only if RazorpayX's dedup works exactly as assumed, which this codebase never verifies. No
   `IdempotencyService.executeOnce`, no `findByIdempotencyKey`-style local check unlike its sibling
   `EscrowService.release`/`refund`.
2. **HIGH — `POST /webhooks/conversion` (`ConversionWebhookController.java:131` →
   `ConversionTrackingService.recordConversion`).** Self-documented by its own author
   (`ConversionTrackingService.java:56-65`) as having **no idempotency guarantee at all**; a
   retried webhook delivery will double-count clicks/revenue. This directly contradicts the sibling
   `RedemptionService.redeem` on the same controller, which is fully compliant — an inconsistent
   guarantee across two webhooks on the same public controller.
3. **HIGH — `POST /contracts/{contractId}/sign` (`ContractController.java:49` →
   `ContractService.recordSignature`, `Contract.java:136-146`).** No idempotency key, no
   already-signed guard; a retried sign call re-fires PDF generation + email delivery
   (`ContractService.java:174-176`) to both contract parties.
4. **MEDIUM — `POST /wallet/escrow/release` and `/refund`** (`EscrowController.java:78,87`).
   Actually protected in practice (status-guard + `WalletLedgerService`'s own
   `uq_wtx_idem`-backed insert-first-wins), but via a second bespoke idempotency mechanism instead
   of the mandated shared `IdempotencyService.executeOnce` — a standing-rule deviation that should
   either be reconciled (migrate to `executeOnce`) or the standing rule should be amended to
   explicitly bless `WalletLedgerService.post`'s mechanism as an approved equivalent for the ledger
   layer.
5. **MEDIUM — `POST /internal/meera/messages`** (`MeeraInternalController.java:174` →
   `MeeraSessionService.persistAssistantWriteback`). No `Idempotency-Key` header at all despite
   being a machine-to-machine write-back call from `influora-ai`, explicitly retry-shaped per its
   own javadoc ("Flow 2 step 6").
6. **HIGH (deviation, not a break) — `POST /wallet/escrow/fund`** (`EscrowController.java:48`).
   Required `Idempotency-Key` header + `findByIdempotencyKey` replay-check give it working
   duplicate-suppression for the common case, but the check-then-insert is not done inside
   `IdempotencyService`'s insert-first-wins transaction, so it does not get the same
   race-under-concurrency guarantee `RedemptionService.redeem` explicitly engineered around
   (`RedemptionService.java:159-176`). A plausible (if narrow) window for a duplicate escrow hold
   under truly concurrent double-submission.

## Not fully traced (flagged, not silently cleared)

`OnboardingController` (`/company`, `/complete`, `/kyc`), `CreatorController.invite`, and
`ContractController.generate` were classified as category (b) by call-site reasoning (human-clicked
forms, no retry/webhook caller identified) but their service-layer duplicate-submission behavior was
not traced line-by-line. Recommend a lighter follow-up pass if these are ever exposed to
automated/retry-capable callers (e.g. a KYC provider webhook is added later).

## Recommended follow-up tasks (not performed — audit only)

1. Wrap `ConversionTrackingService.recordConversion` in `IdempotencyService.executeOnce` (needs a
   caller-supplied or UTM-click-derived idempotency key; `webhooks/conversion` request DTO has none
   today — DTO change required).
2. Add a local idempotency guard to `PayoutService.queuePayout` before calling
   `RazorpayXClient.initiatePayout` — either `IdempotencyService.executeOnce` or, at minimum, a
   `findByIdempotencyKey`-style check against a persisted payout record (none exists yet per
   `PayoutService.confirmExecuted`'s own comment that no `payouts` table exists in this slice).
3. Add an already-signed short-circuit to `ContractService.recordSignature` (e.g. skip
   `recordBrandSignature()`/`recordCreatorSignature()` and the PDF-delivery side effect if that
   role's signature timestamp is already set).
4. Reconcile `EscrowService.release`/`refund`/`fund`'s bespoke idempotency mechanisms against the
   standing rule: either migrate them onto `IdempotencyService.executeOnce`, or get an explicit
   CTO/Priya ruling that `WalletLedgerService`'s `uq_wtx_idem`-backed mechanism is an approved
   equivalent for the ledger-posting layer specifically (it is arguably a stronger guarantee than
   `executeOnce` for that one layer, but it's a second mechanism that must independently stay
   correct).
5. Add an `Idempotency-Key` header + `executeOnce` wrap to `MeeraInternalController.persistTurnWriteback`
   / `MeeraSessionService.persistAssistantWriteback`.
