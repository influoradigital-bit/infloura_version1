# API Documentation — Meera (Phase 2: data + read-only chat)

> Owner: Vikram (Backend). Contracts sourced from `docs/AI connect/backend/02-API-CONTRACT-BRAND.md`.
> This entry documents what is ACTUALLY wired as of Phase 2 — see "Known gaps" before relying on it.

## Public endpoints — `MeeraController` (`/meera`)

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/meera/sessions` | Bearer (BRAND) | Reuses workspace's ACTIVE conversation or opens one. Returns `conversationId`, `brandProfileStatus`, credit summary. |
| POST | `/meera/sessions/{conversationId}/messages` | Bearer (BRAND) | Credit-gates + decrements (Guardrail 5) BEFORE anything else, persists USER turn, assembles sanitized brand context (Guardrail 3, not yet forwarded anywhere — see gap below), mints a scoped stream token, persists a **placeholder** ASSISTANT reply. |
| GET | `/meera/credits` | Bearer (BRAND) | Live credit balance / unlimited-window state. |
| GET | `/meera/brand-profile` | Bearer (BRAND) | Website-analysis status/catalog for onboarding poll. |

All four are scoped off `principal.getWorkspaceId()` — never a body-supplied workspace id.

## Internal endpoints — `MeeraInternalController` (`/internal/meera`) — **STUB ONLY**

Every route (`show_creators`, `calculate_budget`, `create_campaign`, `request_payment`,
`confirm_launch`, `messages`) returns `501 NOT_IMPLEMENTED` with code `TOOL_EXECUTOR_NOT_IMPLEMENTED`.
No mesh-identity / service-token filter chain is wired yet — these routes currently sit behind the
default `anyRequest().authenticated()` matcher, which is NOT the Guardrail 2 model required for a
live money-adjacent executor. Do not point Python traffic at these routes until Phase 4 lands the
real executors + the dedicated internal `SecurityFilterChain`.

## Known gaps (by design, this phase)

1. **No real LLM call.** `MeeraSessionService.sendTurn` persists a placeholder ASSISTANT echo.
   The Python/Domain D service is the actual Claude/Gemini integration point (separate task);
   swap the placeholder for a real write-back via `POST /internal/meera/messages` once that
   integration exists.
2. **Sanitized brand context is assembled but not sent anywhere yet** (`BrandContextAssembler`) —
   there is no live wire to Python in this phase. The allow-list logic is ready for that wire.
3. **`meera_tool_calls` table has no writer.** Schema + repo only; Phase 4's executors are the
   first code to insert rows.
4. **Escrow-funded credit reset (`AICreditService.applyEscrowFundedReset`) is not wired to any
   event listener.** `EscrowFundedEvent` belongs to the parallel money-core (Domain A) build;
   wiring `@EventListener` is a follow-up once that class exists.

## DTOs — `web/dto/meera/MeeraDtos.java`

`SessionStartResponse`, `SendTurnRequest`/`SendTurnResponse`, `StreamTokenResponse`,
`CreditStatusResponse`, `BrandProfileResponse`, `AnalyzeSiteCallback`. All Bean-Validation annotated
where they accept input. `ToolCallRequest`/`CreateCampaignRequest`/`RequestPaymentRequest` are
explicitly NOT included — those are Phase 4 (tool-executor) DTOs.

---

# API Documentation — D14 marketplace invoicing (2026-07-15)

> Owner: Vikram (Backend). Per `INVOICING-GST-SPEC-D14-2026-07-15.md`. Three new documents:
> Doc#2 (creator service invoice, Creator → Brand) and Doc#3a/3b (platform commission invoice,
> split brand/creator legs). Every read below is ownership-checked (resolve row → verify
> workspace/creator match, TECH-STACK.md rule #2), mirrors `InvoiceService.getInvoicePdf`.

## Creator-facing — `CreatorInvoicingController` (`/creator`)

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/creator/campaign-invoices` | Bearer (CREATOR) | Doc#2 — the creator's own earnings invoices, most recent first. |
| GET | `/creator/campaign-invoices/{id}/pdf` | Bearer (CREATOR) | Ownership-checked PDF, rendered on demand if not yet stored to R2. |
| GET | `/creator/commission-invoices` | Bearer (CREATOR) | Doc#3b — Influora's commission invoice TO the creator. |
| GET | `/creator/commission-invoices/{id}/pdf` | Bearer (CREATOR) | Ownership-checked PDF. |

## Brand-facing — `BrandInvoicingController` (`/billing`)

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/billing/campaign-invoices` | Bearer (BRAND) | Doc#2 — creator service invoices billed to this workspace. |
| GET | `/billing/campaign-invoices/{id}/pdf` | Bearer (BRAND) | Ownership-checked PDF. |
| GET | `/billing/commission-invoices` | Bearer (BRAND) | Doc#3a — Influora's commission invoice TO the brand. |
| GET | `/billing/commission-invoices/{id}/pdf` | Bearer (BRAND) | Ownership-checked PDF. |

Mounted alongside the existing `BillingController` (`/billing/invoices*` — Doc#1, subscription).

## DTOs — `web/dto/invoicing/InvoicingDtos.java`

`CampaignServiceInvoiceResponse`, `PlatformCommissionInvoiceResponse` — read-only, never expose
raw JPA entities (same discipline as `BillingDtos`).

## Server-side creation (not directly callable — fired from money-movement paths)

| Document | Service method | Fired from |
|---|---|---|
| Doc#2 | `CampaignServiceInvoiceService.createAtRelease` | `EscrowService.release` / `.adminReleaseForDispute` / `.adminSplitForDispute` (all 3 release call sites), AFTER the `ESCROW_RELEASE`/`PLATFORM_FEE` postings succeed |
| Doc#3a | `CommissionInvoiceService.createBrandLegAtPublish` | `BrandCampaignFeeService.chargeOnPublish`, AFTER the `PLATFORM_FEE` posting succeeds |
| Doc#3b | `CommissionInvoiceService.createCreatorLegAtRelease` | `PlatformFeeService.deductAtRelease`, AFTER the `PLATFORM_FEE` posting succeeds |

Every creation path is gated on the ledger posting's `LedgerPostingResult` having actually
returned (never on "the endpoint was called") and additionally re-checks its own repository for an
existing row before minting a statutory number — a retry can never double-issue.

## Known gaps / follow-ups

1. **Creator GST onboarding flow does not exist yet.** `CampaignServiceInvoiceService` auto-assigns
   a `creatorInvoiceCode` on first Doc#2 issuance if the creator hasn't been through one (Vikram's
   own call, flagged in code — not in the original D14 spec). Ananya's creator tax-identity capture
   form (per the spec's work assignment) should let a creator set their own `gstin`/`pan` before
   that point.
2. **TCS is report-only v1** (D14-D) — `tcs_amount` is computed and recorded on Doc#2 but does not
   change the release payout math. No GSTR-8 export exists yet (Wave 4 follow-up per the spec).
3. **Platform GSTIN/company tax identity is placeholder config** (`influora.company.*` /
   `INFLUORA_COMPANY_GSTIN` etc.) — CA/Rohan to confirm real values before Doc#1/Doc#3 are relied
   on for a filed return.
4. **Subscription invoice GST retrofit (Doc#1) does not migrate `Invoice.amount` off int-paise** —
   deliberately out of scope; new GST fields are computed from it. See `Invoice.java` javadoc.


## Money-path consolidation port (B2/B6/B7/B10/B11 — 2026-07-15)

Ported from `influora-prod-readiness-audit-bc5269` (Wave-2 delta) into `integration-consolidate`.

- **B2 — `POST /brand/{workspaceId}/contracts`:** `ContractService.generate` now materializes
  `Deliverable` rows (one per agreed slot from the last accepted proposal's `deliverableSlots`
  metadata) in the same transaction as the contract/milestones. Idempotent — skips if the
  collaboration already has deliverable rows.
- **B6 — `POST /deliverables/{deliverableId}/reject`** (new route, `BrandDeliverableController`):
  brand terminal-rejects a SUBMITTED/RESUBMITTED deliverable with required `feedback`. Delegates to
  `BrandDeliverableService.reject`, sets `DeliverableStatus.REJECTED` (was declared but previously
  unreachable).
- **B7 — `POST /brand/{workspaceId}/payouts/{milestoneId}`:** `PayoutService.queuePayout` now
  resolves a real RazorpayX fund account (`RazorpayFundAccountService`) instead of passing the
  creator's raw user id, persists a durable `Payout` row (`Payout.createQueued`), and requires
  OWNER/ADMIN workspace role (was any member).
- **B10 — `POST /creator/wallet/withdraw`:** `WalletService.requestCreatorWithdrawal` now requires
  a mandatory `Idempotency-Key`, resolves a real fund account, calls RazorpayX, and persists a
  `Payout` row with `milestoneId = null` (lump-sum withdrawal — see V20260715180000 migration).
  Previously returned a fabricated payout id with no gateway call.
- **B11 — `POST /webhooks/razorpay`:** `payment.captured` no longer routes through the same
  receipt-required path as `order.paid` (was throwing `ESCROW_NOT_FOUND` and causing a Razorpay
  retry storm when the event carried no `payload.order.entity`) — it now safely no-ops when
  unresolvable. `payout.processed`/`reversed`/`rejected`/`cancelled` now parse
  `payload.payout.entity` and route to the new `PayoutReconciliationService.confirmExecuted`
  (webhook-driven `Payout` status update + re-credit-on-reversal via the wallet ledger), replacing
  the previous no-op `PayoutService.confirmExecuted` stub.

**New file:** `PayoutReconciliationService.java` — separate bean from `PayoutService` (keeps
`PayoutService`'s pinned unit-test constructor intact) with `WalletLedgerService`/
`PlatformWalletService`/`WalletService` access for the reversal re-credit path.

**Deliberately NOT ported** (out of scope for this pass, belongs to a separate fix wave already
diverged in `influora-prod-readiness-audit-bc5269`): `WalletOwnerType`-scoped wallet repository
methods (L-4/M-5), `RazorpayXClient.initiatePayout`'s non-2xx fail-loud fix (C-6 sibling, same
file family), contract H-12 terms-hash verification, M-10 contract-generation blocked-status guard,
L-10 deliverable revision cap, C-10 auto-complete-collaboration. Flagged for a follow-up pass —
none of these were required for B2/B6/B7/B10/B11 to compile or pass tests.

## Wave 6 — N1/N2/N3 net-new backend (2026-07-15)

Three routes confirmed absent on every branch (no controller caller existed anywhere). Built to
match `src/lib/api.ts`'s already-shipped client contract exactly — none of these paths were
invented; the frontend was calling all of them with nothing on the other end.

- **N3 — `GET/POST /wallet/payout-methods`, `PUT /wallet/payout-methods/{id}/primary`** (added to
  the existing `WalletController`, not a new controller — `BankAccountDtos`'s javadoc already named
  `WalletController` as the intended home). Thin controller over the pre-existing, previously
  UNREACHABLE `CreatorBankAccountService`/`CreatorBankAccountRepository`/`CreatorBankPiiCipher`
  (Kabir M-K6-C3-2 — AES-GCM encryption, 24h cool-down, masked-only reads). No service/entity
  changes — this endpoint was the missing controller, nothing else.
- **N1 — `POST /onboarding/creator/{socials,profile,complete,kyc,payout}`** (new
  `CreatorOnboardingController` + `CreatorOnboardingService`, `/onboarding/creator` — the existing
  `OnboardingController` only ever mapped `/onboarding/brand`). `payout` routes through the SAME
  `CreatorBankAccountService` N3 wires up — one encrypted persistence path for creator payout
  instruments, not two. `socials` honestly persists a `platform_stats` connection row with zero
  followers/handle rather than fabricating data — no real OAuth token exchange exists for any
  social platform yet (the frontend itself sends a hardcoded `mock_oauth_code`); see
  `OnboardingDtos.CreatorSocialResponse` javadoc. `kyc` adds new `creator_profiles` columns
  (V20260715190000, see schema-changes.md) and reuses the D14 `pan` column.
- **N2 — `POST /uploads`** (new `UploadController` + `UploadService`, generic — no route existed at
  all). Multipart field `file`; accepts images (magic-byte sniffed via `MediaMimeSniffer`) or PDF
  (new local magic-byte check — GST/PAN documents are commonly PDF, `MediaMimeSniffer` itself only
  ever covered image/video); 10MB cap; malware-scanned
  (`MalwareScanService.requireClean`) before the R2 write. D6: persists metadata into the
  previously-orphaned `file_uploads` table via new `FileUpload` entity/repository (see
  schema-changes.md) instead of a second table. Returns `{ url, key }` — `url` is
  `R2StorageService.publicUrl(key)` (stable, non-expiring) because the frontend persists it
  long-term into DB fields (`gstinDocUrl`/`panDocUrl`/`selfieUrl`), not a short-lived presigned GET
  that would rot. **Flagged gap:** this assumes a public-read R2 bucket base; genuinely
  access-controlled serving of private KYC documents (signed/expiring/audit-logged reads) is not
  built here — same limitation the schema/contract already implied, not introduced by this change.

**S7 (2026-07-15, Priya approval) — real malware scanner for prod.** Added
`ClamAvMalwareScanService` (`@Profile("prod")`), backed by a new hand-rolled `ClamAvClient`
(`com.influora.integration.clamav`, plain `java.net.Socket` INSTREAM protocol — see
wiki/tech/approved-deps.md for why this is NOT a third-party Maven artifact: offline `mvn -o`
verification + no cached `clamav` jar + no network this session). Fails closed on infected,
unparseable-response, AND socket-unreachable — a prod scanner that can't be reached is never
treated as "assume clean". `NoOpMalwareScanService` stays `@Profile("!prod")` exactly as Wave-1 S7
left it. `docker-compose.yml` gained a `clamav` (`clamav/clamav:1.3`) service; a live clamd smoke
test is deferred to Meera's build verification (unit tests here mock `ClamAvClient`).
