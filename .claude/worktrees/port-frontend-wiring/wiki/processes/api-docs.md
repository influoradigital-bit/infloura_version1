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

