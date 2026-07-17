# Known Limitations & Technical Debt

A consolidated, honest inventory of stubs, gaps, drift, and scaling constraints found **in the source code**. This is required reading before extending any money, AI, or notification flow. Items are grouped by severity/area; each names the responsible code.

---

## Money & payments (highest priority)

1. **Subscription webhooks are not routed.** `applySubscriptionWebhookUpdate` is documented as the only place subscription state is written from Razorpay, but `RazorpayWebhookController` has **no `subscription.*` case**. Real Pro purchases never create a local ACTIVE Pro row; `cancel` (needs a non-null `razorpaySubscriptionId`) is unreachable for real customers; ACTIVE→PAST_DUE never triggers. The renewal/dunning jobs are, in practice, the only subscription-state mutators besides admin comp. → [features/billing-subscriptions.md](features/billing-subscriptions.md).
2. **Real payouts are half-wired.** The `payouts` table / `Payout` entity / `PayoutRepository` are **dead code** (state lives on `payment_milestones`); `PayoutService.confirmExecuted` is a **no-op** (payouts never leave `queued`, reversals invisible); the live payout passes `collaboration.getCreatorId()` (internal user id) as `fund_account_id` (a placeholder); `CreatorBankAccountService`/`RazorpayFundAccountService` are orphaned (no HTTP routes to add/list bank instruments). Works only because RazorpayX `isConfigured()` is false in dev. → [features/payouts.md](features/payouts.md).
3. **`wallets.escrow_balance` is never written.** It has no mutator; escrow funding moves money into the clearing wallet's `balance`, so the brand dashboard's `escrowLocked` figure is always **0.00**. → [features/wallet.md](features/wallet.md).
4. **Escrow-release net vs payout gross mismatch.** `EscrowService.release` credits the creator `net = gross − fee`, but the RazorpayX payout initiates for `milestone.getAmount()` (**gross**). → [features/escrow.md](features/escrow.md).
5. **Affiliate earnings accrue only via the hourly backfill job.** The advertised synchronous `RedemptionService.doRedeem → recordEarning` call **does not exist**; `AffiliateEarningReconciliationJob` (hourly, 30-min grace) is the only path creating earnings — and it WARNs on essentially every run. Accrual lags ≥30 min. Settlement is also **not period-bounded** (it sweeps a creator's entire PENDING/FAILED backlog, so `period_year_month`/`total_amount` misstate the month). Currency is hardcoded `INR`. → [features/affiliate-coupons.md](features/affiliate-coupons.md).
6. **GST is not return-ready.** `influora.company.gstin` is a placeholder, so the CGST/SGST-vs-IGST split always resolves to **IGST**; `company.state-code` is dead (split uses GSTIN prefixes only); Doc#2 (campaign service) emits no GST even for registered creators (only a 1% report-only TCS); Doc#3 (commission) GST split is recomputed at render, not persisted (a later GSTIN change retroactively alters an issued invoice). HSN/SAC codes are placeholders pending CA sign-off. → [features/invoicing-gst.md](features/invoicing-gst.md).
7. **`payment_milestones.release_condition` (V52) is unmapped on the entity** — the release path gates on dispute + FUNDED only, never on `release_condition`. → [features/contracts.md](features/contracts.md).
8. **`isConfigured()` is true with the placeholder Razorpay secret** (non-blank), so a misconfigured deploy could call the real API with junk creds; only `isFullyConfigured()`/webhook-fail-closed guard this.

---

## AI / Meera

9. **`MeeraSessionService` does not call an LLM** — it persists a placeholder ASSISTANT echo; real assistant text is streamed by Python and written back via `/internal/meera/messages`.
10. **AI/JWKS/Meta config prefixes are absent from committed `application*.yml`** — they run on hardcoded localhost defaults with empty secrets, and several eager beans (`SpringJwksKeyService`, `MetaTokenStorage`) throw at startup on blank keys, so a real deploy must inject PEMs/keys out-of-band.
11. **`BrandSafetyScoreService` is not wired into `ScoreCalculationJob`** — brand-safety score / GARM flags / sentiment columns on `creator_scores` are always NULL.

---

## Analytics & jobs

12. **Per-post `media_metrics` polling is not wired** (TODO in `MetricsPollingJob`); `InstagramMetricsFetcher` exists but is deliberately not invoked.
13. **`ScoreCalculationJob` passes empty historical metrics** (only latest snapshot available), so the fake-follower growth-spike signal never fires; `QualityScoreService.audienceMatch` is hardcoded 50 (demographics not consumed).
14. **No YouTube support** — verification and metrics are Instagram/Meta only (`DeliverableVerificationJob` returns `FALLBACK_YOUTUBE_UNSUPPORTED`).
15. **Report export has no frontend caller** and no numeric usage cap (boolean plan-gate only).

---

## Notifications

16. **Notifications are HTTP-poll only** — no WebSocket/SSE for notifications (the only SSE is Meera chat).
17. **5 published events have no listener** (`ContractReadyForEscrowEvent`, `InvoiceReadyEvent`, `PortfolioContactEvent`, `SubscriptionHaltedEvent`, `SubscriptionPaymentFailedEvent`) — they fire into the void.
18. **Most notification handlers pass `toEmail=null`** ("user lookup" TODO), so those emails currently no-op at the blank-email guard.
19. **No SMS** despite UI toggles — MSG91 is email-only.
20. **Frontend calls non-existent endpoints**: `POST /notifications/read-all`, `GET/POST /notifications/preferences` (will 404); support `POST /support/tickets/{id}/escalate` + `getStats` (frontend has live buttons, backend lacks them); generic `POST /uploads` has no controller.

---

## Deliverables / uploads

21. **`file_uploads` table (V1) is orphaned** — no JPA entity; media metadata lives in `deliverables.files_json`.
22. **R2 `presignPut` is dead code** — all uploads are server-side streaming; the documented presign→client-PUT→confirm flow is not wired.
23. **`DeliverableStatus.REJECTED` is defined but nothing transitions into it.**
24. **`src/lib/upload.ts` is a full mock** (fake progress + fabricated URLs); real transport is `http.upload` in `api.ts`.

---

## Frontend

25. **Frontend tests are not runnable as configured** — `src/test/setup.ts` imports vitest/testing-library but there's no wired `vitest.config.ts`/`test` script and the deps aren't in `package.json`; **no Playwright/e2e** despite a `playwright.config.ts`. Several existing `*.test.*` files are stale (assert old stubbed behavior).
26. **Many surfaces still render mock/demo data** (brand campaign detail, wallet, messages, creator dashboard/chat/inbox, admin billing) — live endpoints noted in file headers.
27. **Dead `src/app/` + `next.config.mjs`** — Next scaffold; the app is Vite (tsconfig excludes it).
28. **Admin controllers return raw DTOs** with two flagged frontend mismatches (error message path `error.message` vs `error.error.message`; base `/api/admin` vs `/api/v1/admin`).

---

## Security & scaling

29. **Access tokens in `localStorage`** (all roles) — XSS exposure; refresh cookie limits durability not theft.
30. **No refresh-token reuse detection**; **frontend refresh is half-wired** (cookie sent, `/auth/refresh` never called, so sessions break on expiry).
31. **Per-instance in-memory state**: `AuthRateLimitFilter`, `NonceCache`, and OAuth state stores are per-node — need Redis/edge for horizontal scale (Redis is already a dependency).
32. **`idempotency_keys` has no TTL/reaper** — unbounded growth.
33. **Admin lockout has no in-app recovery** — direct DB update required for a locked-out SUPER_ADMIN/ADMIN.
34. **Defaults**: `require-email-otp-before-register=false`, `refresh-cookie.secure=false` (both guarded outside dev by `SecretsStartupValidator`).

---

## Schema/entity drift & dead code (quick list)

- `payment_milestones.release_condition` (V52) — unmapped.
- `payouts` table / `Payout` entity — dead.
- `file_uploads` table — orphaned.
- `TransactionStatus` transitions — ledger rows are created COMPLETED; no code transitions them.
- `CreatorTaxRegistrationStatus` — stored but unused (no reverse-charge logic).
- `DealResponse.deliverablesDone/Total/nextDeadline` — hardcoded stubs.

---

## What is solid (so you don't over-correct)

The money core is genuinely robust: double-entry ledger with DB-unique idempotency and cross-feature collision defense; webhook HMAC (SHA-256, constant-time, fail-closed); server-derived amounts with webhook amount/currency cross-checks; optimistic locking on singleton fee config / subscriptions / disputes; pessimistic locking on wallet mutations and invoice sequences; exact paise conversion with `longValueExact()`. The AI safety model (tier gate, tool whitelist, DB-verified escrow, human-confirm money) is well-constructed. Don't refactor these away while fixing the gaps above.
