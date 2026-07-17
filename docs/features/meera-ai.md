# Feature: Meera (Conversational AI Assistant)

**Business Purpose** — "Meera" is a conversational AI co-founder for brands: they chat to research creators, size budgets, draft campaigns, and launch them — without leaving the conversation. Meera turns a blank campaign form into a guided dialogue, while a strict guardrail architecture ensures the AI can never move money on its own.

**Who uses it** — Brand workspace members. Powered by the external influora-ai (Python) service.

## User Roles
Brand (chat). Money-committing tool calls require the on-behalf user to be OWNER/ADMIN.

## Permissions
Chat consumes AI credits. Tool calls are validated by tier; C-tier (money) tools require elevated on-behalf role and stage a human confirmation.

## Business Flow
```
Brand chats → Spring charges 1 credit + returns a 60s ES256 stream token
  → browser streams from Python /chat (SSE)
  → LLM proposes tool → Python calls /internal/meera/<tool> (service token + HMAC + on-behalf JWT)
  → Spring validates (name whitelist + tier), executes (R/D) or stages (C) → result streamed back
```

## Frontend
- **Page**: `brand-meera` → `feature/meera/MeeraWorkspace`.
- **Components**: `MeeraChatPanel`, `Composer` (+ voice), `LivingCanvas` (5 stages), `CreditMeter`/`CreditPaywall`, `FundEscrowButton`, `PayoutLedger`.
- **Hooks**: `useMeeraStream` (real SSE/EventSource), `useMeeraCredits`, `useMeeraStage`.
- **Client**: `lib/meera-api.ts`.

## Backend
- **Controllers**: `MeeraController` (`/meera`), `MeeraInternalController` (`/internal/meera/*`).
- **Services**: `service/meera/*` (`MeeraSessionService`, `AICreditService`, `StreamTokenService`), `service/meera/tool/*` (executors + `ToolCallValidator`, `AmountDerivationService`).
- **Security**: `InternalServiceTokenFilter`, `InternalRequestVerifier`, `OnBehalfAuthResolver`, `NonceCache`, `SpringJwksKeyService`.

## Database
`ai_conversations` (V12, ≤1 ACTIVE per workspace), `ai_messages` (V12), `brand_ai_credits` (V14/V16), `meera_tool_calls` (V14, idempotency ledger), `campaign_intents` (V13). See [../database.md](../database.md).

## APIs
`POST /meera/turn`, `POST /internal/meera/{show_creators,calculate_budget,create_campaign,request_payment,confirm_launch,messages}`.

## AI (this is the AI feature)
See [../ai.md](../ai.md) for the full model. Five tools / four tiers (R/D/C/Forbidden). Money safety: `create_campaign` leaves budget null; `request_payment` stages `PENDING_CONFIRM` with 1% drift rejection; `confirm_launch` requires DB-verified FUNDED escrow and charges the publish fee transactionally.

## Notifications
Meera-related events (credits reset, site analyzed, campaign recommended) route in-app.

## Dependencies
- **Depends on**: influora-ai (Python), JWKS keys, campaigns, escrow, wallet, credits.
- **Depended on by**: brand campaign-creation UX.

## Connected Files
`MeeraController`, `MeeraInternalController`, `service/meera/*`, `service/meera/tool/*`, `security/{InternalServiceTokenFilter,InternalRequestVerifier,OnBehalfAuthResolver,NonceCache}`; frontend `feature/meera/*`, `hooks/useMeeraStream`.

## Execution Flow
```
Turn: POST /meera/turn → AICreditService.tryConsume(1) (daily cap 500 → 429; unlimited window; else atomic decrement → 402)
  → persist USER message → StreamTokenService mint (ES256, aud=meera-stream, ≤60s) → {streamToken, publicChatUrl}
Tool: POST /internal/meera/<tool> → InternalServiceTokenFilter (service JWT ≤60s + HMAC + nonce) → OnBehalfAuthResolver
  → ToolCallValidator (name + tier) → executor → server-derived result
```

## Error Handling
`UNKNOWN_TOOL_NAME`/`FORBIDDEN_TIER`/`TOOL_ROUTE_MISMATCH` (403), `AMOUNT_MISMATCH` (409, >1% drift), `ESCROW_NOT_FUNDED` (409), `CREDITS_EXHAUSTED` (402), `DAILY_ACTION_LIMIT_EXCEEDED` (429), `ON_BEHALF_WORKSPACE_MISMATCH`/`INSUFFICIENT_ROLE` (403).

## Security
Triple-gate mesh auth; stream token single-use + 60s TTL; the AI never writes money (staging + human confirm); forbidden tools have no route (structural absence); tool calls idempotent + audited.

## Performance
Spring does not proxy the stream (browser↔Python directly); credit checks and idempotency are cheap DB ops.

## Testing
Tier/validator tests; executor idempotency tests. Regression risks: tier gate, escrow-funded proof, drift tolerance.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~80% (safety model strong; LLM lives in Python)
- **Known issues**: `MeeraSessionService` persists a placeholder assistant echo (real text from Python); JWKS/AI config not in committed yml (must inject; eager beans throw on blank keys). See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
