# AI Review: Meera `create_campaign` — ON_BEHALF_SCOPE_INSUFFICIENT

**Reviewer:** Ash · **Date:** 2026-07-21 · **Verdict:** WORKING AS DESIGNED (not a bug) — but the enablement fix in the incoming audit is wrong; see Findings.

## TL;DR
The `ON_BEHALF_SCOPE_INSUFFICIENT` error is **correct, intentional behavior**, not a break. Meera can run read tools today; `create_campaign` is gated off at the credential layer. The audit's root cause is right. Its *fix* ("add create_campaign to the minted scope, behind an elevated/human-confirm scope") is **architecturally wrong** and skips the actual blockers the design doc names.

## How It Works (traced flow — verified against code, not the audit text)
```
FE  POST /meera/sessions                    → conversationId
FE  POST /meera/sessions/{id}/messages      → { streamToken, onBehalfToken, messageId }  (charges 1 credit)
    └─ OnBehalfTokenService.mint() stamps scope = SCOPE_READ_ONLY
FE  POST :8000/chat (Bearer streamToken, body carries onbehalf_jwt) → SSE
Python tool loop  → Claude → offers ALL 5 tools incl. create_campaign
    └─ Claude calls create_campaign → POST /internal/meera/create_campaign
        └─ OnBehalfAuthResolver.resolveForWorkspaceRequiringScope("create_campaign")
            └─ requireScope(): "create_campaign" ∉ "show_creators calculate_budget" → 403 ON_BEHALF_SCOPE_INSUFFICIENT
    └─ loop.py catches SpringCallError → tool_result is_error=true → Claude apologizes in-turn (no hard crash)
```
Files: `OnBehalfTokenService.java:59,95` (mint scope) · `OnBehalfAuthResolver.java:134-173` (per-tool enforce) · `MeeraInternalController.java:158-172` (create_campaign route) · `CreateCampaignExecutor.java` (DRAFT-only, no money) · `influora-ai/app/tools/schemas.py:82-149,252-259` (tool exposed to model) · `influora-ai/app/tools/loop.py:314-328` (403 surfaced back).

## Where the audit is RIGHT
- Root cause is exact: `SCOPE_READ_ONLY = "show_creators calculate_budget"`, enforced per-tool by the resolver. ✅
- Gating is deliberate, not broken. ✅
- Executor is built and DRAFT-only — never writes a money field (`CreateCampaignExecutor.java:181-207`). ✅
- Manual `POST /campaigns` path is unaffected. ✅

## Where the audit is WRONG (this is the correction)
The audit says: *"add create_campaign to the minted scope — likely a separate elevated/write scope granted only after a human-confirm step."*

Two problems:
1. **`create_campaign` is D-tier (Draft), not C-tier (Commit).** Per `06-MEERA-PERMISSIONS-MATRIX.md` and `MeeraToolTier.java`, D = "reversible, non-binding draft, no money." The whole reason the D tier exists separately from C is that drafts are **safe to auto-invoke without a human-confirm click**. Requiring a confirm step for it contradicts the matrix. Human-confirm belongs to `request_payment` / `confirm_launch` (C-tier, `resolveForWorkspaceRequiringElevatedRoleAndScope`). So the "elevated/confirm-guarded scope" recommendation is misapplied.
2. **Widening the scope is the *last* step, not the fix.** The design doc (`docs/security/meera-onbehalf-auth-security-design.md` §8) gates `create_campaign` (Phase 3) behind **Phase 2 prerequisites that are NOT yet implemented**. Grant the scope today and you ship known-open holes.

## Findings

### P0 — Phase-2 security prerequisites must land BEFORE the scope is widened
**Where:** `OnBehalfTokenService.java:37-38` (jti replay "NOT implemented"), `OnBehalfAuthResolver.java:47-49` (conversationId "NOT yet cross-checked"), `CreateCampaignExecutor.java` (no rate limit).
**Issue:** §8 makes three items hard prerequisites for enabling *any* write tool:
- **Single-use stream token + single-use on-behalf `jti` replay store** (§2/§3 item 10). Idempotency alone does NOT cover a full-stream replay — a replayed turn mints fresh `tool_use.id`s and spams duplicate draft campaigns (§ line 169). Neither single-use check exists yet.
- **`conversation_id` ↔ workspace cross-check** (item 8 / gap 1-C). The token carries a `conversationId` claim but nothing asserts `conversation.workspaceId == token.workspaceId` before the draft is written. Pattern already exists on `/messages` (`MeeraInternalController.java:222-223`); apply it to `create_campaign`.
- **Per-workspace D-tier rate limit** (§6 line 180). Prompt injection can still drive an *in-whitelist* `create_campaign` call; without a cap a successful injection spams the ledger. `DAILY_ACTION_LIMIT_EXCEEDED` already exists (`MeeraSessionService.java`) — extend it to D-tier executions.
**Fix:** Land the three above (Phase 2), then Phase 3 = a **one-line** change: add `create_campaign` to the minted scope (a `SCOPE_DRAFT` = read set + `create_campaign`, still no human-confirm — it's a draft). The resolver enforcement, executor, and route are already built and waiting.
**Gain:** Enables the feature without shipping the replay/cross-tenant/spam holes the design explicitly calls out. Owner: **Vikram** (backend), gated by **Priya** sign-off per §8 exit gates. Tag **Kabir** for the red-team exit gate.

### P1 — Claude is offered a tool it can never successfully call (AI-quality defect, independent of security)
**Where:** `influora-ai/app/tools/schemas.py:252-259` — `get_tool_schemas()` returns all 5 tools unconditionally; `create_campaign` is always in Claude's tool list.
**Issue:** Because the tool is advertised, Claude *will* call it whenever a brand asks to create a campaign — burning a model round-trip (and the user's 1-of-100 credit) to produce a guaranteed 403. The loop degrades it to an in-turn apology, so it's not a crash, but it's a confusing dead-end and wasted spend that scales with every "make me a campaign" request. Tool-design best practice: **don't expose a tool you won't authorize.**
**Fix:** Filter the offered schema set by the currently-enabled scope/tier (single source of truth already exists — `TOOL_TIERS` in `schemas.py`). Until Phase 3 flips, drop `create_campaign`/`request_payment`/`confirm_launch` from `get_tool_schemas()` so Claude only sees callable tools and instead *talks* the brand through what it can do. If you keep it exposed for discoverability, at minimum special-case `ON_BEHALF_SCOPE_INSUFFICIENT` in `loop.py:314-328` into a clean "campaign creation isn't switched on yet" tool_result rather than surfacing the raw scope-error string to the model.
**Gain:** No wasted credits/turns on an un-callable tool; cleaner brand-facing behavior; the enabled tool surface stops drifting from what the mint authorizes.

### P2 — Scope constant and enabled-tool set can silently drift
**Where:** `OnBehalfTokenService.SCOPE_READ_ONLY` (Java) vs `TOOL_TIERS` (Python) vs `get_tool_schemas()`.
**Issue:** Three places encode "what Meera may do," none derived from one another. The CI shared-schema diff-check covers request/response *shapes*, not *which tools are enabled*. A future Phase-3 flip could update one side and not the others.
**Fix:** When widening scope, add a note/test asserting the minted scope set == the offered-tool set for the current phase. Cheap guard; prevents a repeat of exactly this "offered but unauthorized" mismatch.

## Data & Training Roadmap
- **Now:** Nothing to log for this fix — it's authz, not model quality. But note the wasted-credit signal from P1 is worth a counter (how often Claude calls a tool that 403s) — it directly measures the offered-vs-authorized drift.
- **Next:** Once `create_campaign` is live, log `{intent → draft campaign fields Claude proposed}` vs `{fields a human edited before launch}`. That edit-delta is the highest-value few-shot / eval corpus for improving the draft quality later.
- **Later:** Revisit an eval set for campaign-type / template selection once there's real volume.

## Verdict
**SHIP-BLOCK on enabling the tool today.** The error is not a bug — do not "just widen `SCOPE_READ_ONLY`." Correct order:
1. (P0) Land Phase-2 prerequisites: single-use stream token + on-behalf `jti`, conversation↔workspace cross-check, D-tier rate limit.
2. (P0) Then grant a `create_campaign`-inclusive draft scope in the mint — one line, **no human-confirm** (it's D-tier).
3. (P1) Stop offering `create_campaign` to Claude until step 2, or convert the 403 into a graceful message.

Owner: Vikram (impl) · gated by Priya (§8 exit gates) · Kabir (red-team exit gate).
