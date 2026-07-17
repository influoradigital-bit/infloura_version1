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
