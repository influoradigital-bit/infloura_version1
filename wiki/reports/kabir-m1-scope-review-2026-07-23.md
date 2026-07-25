# Kabir Red-Team Review — M-1 On-Behalf Scope Widening

**Branch:** feat/creator-taxonomy-keyword-patch
**Date:** 2026-07-23
**Reviewer:** Kabir (offensive-security / red-team, authorized self-audit)
**Change:** `OnBehalfTokenService.SCOPE_DEFAULT` widened from read-only (`show_creators calculate_budget`) to add the two non-money tools `create_campaign` + `get_campaign_performance`. Money tools (`request_payment`, `confirm_launch`) deliberately left out.

## VERDICT: SHIP

The change is narrowly and correctly scoped. It grants exactly the two non-money tools that were silently 403-ing, adds no money capability, and every tenant/identity boundary that matters is derived from the server-minted JWT — not from anything the AI or a prompt-injector controls. The money path remains doubly gated (scope AND OWNER/ADMIN) and staging-only. No blocking issue introduced by this change.

Two pre-existing, already-documented gaps (jti single-use replay, conversationId tenant binding) are unchanged by this patch — noted below as non-blocking follow-ups, not regressions.

---

## Check 1 — Token security invariants intact

| Property | Status | Evidence |
|---|---|---|
| Short-TTL 120s | PASS | `OnBehalfTokenService.java:43` `MAX_TTL_SECONDS = 120`; minted at `:89` `now.plusSeconds(MAX_TTL_SECONDS)`; test asserts `<=121` `OnBehalfTokenServiceTest.java:87` |
| Workspace+user+turn bound | PASS | claims `workspaceId`/`sub=userId`/`turnId`/`conversationId` minted `:97-105`; all three sourced server-side (see below) |
| Audience-restricted | PASS | `aud=meera-onbehalf` `:99`; `requireAudience(ONBEHALF_AUDIENCE)` on verify `:130`; distinct from stream/public audiences |
| Signature-verified | PASS | ES256 / dedicated JWKS keypair `:108`, `:131`; cross-family HMAC access token structurally rejected — test `OnBehalfTokenServiceTest.java:181-196` |
| jti stamped | PARTIAL (pre-existing) | jti minted `:95` but replay store NOT enforced — self-documented `OnBehalfTokenService.java:37-38`, `OnBehalfAuthResolver.java:48-49`. Within-120s replay possible. **Not introduced by M-1.** |

The mint claims are all server-authoritative — an injected model cannot forge them: `MeeraController.java:123-133` sources `workspaceId = requireBrandWorkspace(principal).getId()`, `userId = principal.getUserId()`, `userType = principal.getUserType()`, threaded to `MeeraSessionService.java:255` `onBehalfTokenService.mint(...)`. None come from the request body.

## Check 2 — create_campaign cannot escalate

| Attack | Status | Evidence |
|---|---|---|
| Set/move budget or money | PASS | `CreateCampaignExecutor.java:188` budgetMin/budgetMax never set; `:202-203` never copied from template; `proposed_budget` only writes advisory `CampaignIntent.proposedBudget` (`:181`), no escrow/money rail touched |
| Act outside caller workspace | PASS | executor receives `ctx.workspaceId()` (JWT-verified), `MeeraInternalController.java:179-180`; every save uses it `CreateCampaignExecutor.java:174,192` |
| Escalate userType | PASS | userType not consulted in create path; `createdBy = userId` from ctx `:198` |
| Create for another brand | PASS | resolver asserts `token.workspaceId == body.workspace_id` `OnBehalfAuthResolver.java:75-83`; workspaceId never read from body in executor |
| Bypass OWNER/ADMIN | N/A by design | create_campaign is D-tier (`ToolCallValidator.java:41`); matrix permits any workspace member to draft. Draft has no money and requires a separate human Commit to go live. Correct — not a bypass. |
| template_id cross-tenant pull | PASS | `CampaignTemplateService.requireVisible(templateId, workspaceId)` `:165` — SYSTEM or owning-workspace only, 404 on cross-workspace id |

## Check 3 — Money tools remain doubly gated (defense-in-depth)

Both `request_payment` and `confirm_launch` route through `resolveForWorkspaceRequiringElevatedRoleAndScope` (`MeeraInternalController.java:195,211`), which enforces **both**:
- OWNER/ADMIN role — `OnBehalfAuthResolver.java:116-121`
- scope claim contains the tool — `:148-153`, `:162-173`

`SCOPE_DEFAULT` does **not** list `request_payment`/`confirm_launch` (`OnBehalfTokenService.java:68-69`), so every minted token now fails the scope gate for money tools (`ON_BEHALF_SCOPE_INSUFFICIENT`) **before** the role check even runs. Widening the scope to add the two non-money tools did not touch the money entries — there is no path where this change enabled a money action. Even if both gates were bypassed, `request_payment` only stages `PENDING_CONFIRM` (controller javadoc `:69-70`, `:190-193`) — actual money movement lives on a separate human-clicked public endpoint. Defense-in-depth fully intact. **PASS.**

## Check 4 — Injection / cross-tenant leak

A brand user can prompt-inject Meera into *calling* `create_campaign`/`get_campaign_performance` with attacker-chosen args — but every tenant boundary is enforced server-side against the JWT workspace, so injection cannot cross a tenant:

- **get_campaign_performance IDOR:** `campaign_id` is model/attacker-controlled, but resolved via `CampaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)` `GetCampaignPerformanceExecutor.java:112-118`, workspaceId from JWT ctx (`MeeraInternalController.java:234`). A cross-tenant or typo'd id both return an identical generic 404 — no existence oracle, no data leak. Method exists `CampaignRepository.java:17`. **PASS.**
- **Performance result is PII-stripped + server-authoritative:** deliverables carry opaque `milestoneId` + numeric metrics only `:151-159`; spend/reach filtered to `RELEASED`/`PLATFORM_VERIFIED` rows `:128,131`. No self-reported or free-text creator data. **PASS.**
- **create_campaign injection:** worst case an injected model drafts a spurious campaign in the user's **own** workspace (no money, human must commit) or reads the user's **own** performance. Both stay inside the authenticated tenant. **PASS.**

## Check 5 — Other risk from the scope widening

Nothing risky introduced. Observations (all non-blocking):

1. **create_campaign now reachable by any workspace member role** (scope gate has no role check; D-tier by design). Consistent with the permissions matrix — drafts are harmless without a human Commit. No action required; flagging for awareness.
2. **Pre-existing, unchanged by M-1:** jti single-use replay is not enforced (`OnBehalfAuthResolver.java:48-49`) — a captured on-behalf token is replayable within its 120s window. Same as before the patch.
3. **Pre-existing, unchanged by M-1:** `conversation_id` is read from the body (`MeeraInternalController.java:180` `conversationIdOf(body)`) and written to `CampaignIntent.conversationId`/`MeeraToolCall.conversationId` without a tenant cross-check. Because workspaceId is JWT-authoritative, the blast radius is a mislabeled conversation reference *within the caller's own workspace* — no cross-tenant effect. Low severity; already tracked (`OnBehalfAuthResolver.java:47-48`).

None of items 2–3 are caused or worsened by widening the scope; they were accepted known items before this change and remain so.

## Required changes

None blocking. Recommended follow-ups (already on the design-doc backlog, do not gate this ship):
- Implement jti consumed-store to close the 120s replay window.
- Cross-check `conversation_id` against the JWT tenant in create_campaign.
