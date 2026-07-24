# Meera Full-Enablement Plan — AI Campaign Creation, Creator Hiring + Razorpay, Wallet Talk, First-Name

**Author:** Ash (AI/ML lead) · **Date:** 2026-07-21
**Rulings folded in:** Priya (CTO, architecture), Kabir (red-team, security gate), Swapnil (CEO, go/no-go)
**Status:** APPROVED WITH SEQUENCING — ship in 4 lanes, hard gate chain enforced.

---

## 0. What the user asked for
Make Meera able to, end-to-end:
1. **Create campaigns by chat** (AI-driven campaign creation).
2. **Hire creators + launch a private campaign with real payment (Razorpay).**
3. **Talk about the brand's wallet** (check balance in chat).
4. **Personalize with the user's first name.**

This doc traces the *real* current state of each (verified against code, not audit docs), then gives the concrete, ordered path to ship — with the three leads' rulings.

---

## 1. Verified current state (the honest baseline)

| Capability | Built? | Blocked by | Where |
|---|---|---|---|
| Read tools (show_creators, calculate_budget) | ✅ LIVE | — | Phase 1 shipped |
| create_campaign (D-tier draft) | ✅ executor built, **403s** | minted scope excludes it + 2 Phase-2 security fixes | `CreateCampaignExecutor.java`, `OnBehalfTokenService.java:59,95` |
| request_payment (C-tier) | ✅ executor built, **403s** | scope + Phase-4 gate | `RequestPaymentExecutor.java` |
| confirm_launch (C-tier, invites+escrow) | ✅ executor built, **403s** | scope + Phase-4 gate; **global-pool invite bug** | `ConfirmLaunchExecutor.java` |
| Razorpay money path | ✅ built | human-clicked, separate from AI | `RazorpayClient.java`, `RazorpayWebhookController.java`, public `POST /brand/escrow/fund` |
| Brand wallet read (public API) | ✅ **fixed** (was 403) | — | `WalletController.java` branches on userType |
| Meera "talk wallet" (AI sees balance) | ❌ | `wallet_balance` stripped from model context | `assembler.py:68-84` |
| First-name personalization | ❌ | `first_name` not in context; `full_name/phone/email` stripped | `assembler.py` Block B |
| Hire a **specific** creator | ❌ | `creator_ids[]` accepted by schema but **ignored** by executors | `schemas.py:132`, `CreateCampaignExecutor`, `ConfirmLaunchExecutor` |

**Why everything write-tier 403s today:** the per-turn on-behalf JWT is minted with `scope = "show_creators calculate_budget"` only (`OnBehalfTokenService.SCOPE_READ_ONLY`). `OnBehalfAuthResolver` enforces scope per tool. This is **deliberate** — the phased rollout in `docs/security/meera-onbehalf-auth-security-design.md §8`. Enabling a tool = widening that one scope constant **after** its phase's guards exist. That constant is the entire blast radius; every edit to it is a security-reviewed step, not a config tweak.

**Good news since the last review (2 blockers already cleared):**
- ✅ Stream-token single-use replay guard is now implemented and wired (`influora-ai/app/auth/replay_guard.py`, `service_token.py:319` — Redis-first, in-memory fallback).
- ✅ Brand wallet 403 is fixed — `WalletController` now serves a brand's workspace wallet on `/balance`, `/`, `/transactions`.

---

## 2. The gate chain (non-negotiable — Priya + Kabir + Swapnil all lock this)

```
[Phase-2 security fixes]  ──►  widen scope to create_campaign (D)  ──►  widen scope to request_payment + confirm_launch (C)
   conv↔workspace bind            (draft, no money)                        (money path, human-confirm)
   D-tier rate limit
```

**Never widen the minted scope past what the current phase's guards cover.** No compressing phases to hit a date (Priya + Swapnil, explicit).

### The three Phase-2 security items — status & severity

| # | Item | Status | D-tier verdict | C-tier verdict |
|---|---|---|---|---|
| 3a | **conversation_id ↔ workspace cross-check** in write executors (assert `conversation.workspaceId == token.workspaceId`) | ❌ not done | **HARD BLOCKER** (Kabir: live cross-tenant write primitive / confused deputy) | HARD BLOCKER |
| 3b | **on-behalf JWT `jti` single-use** replay store | ❌ not done | **fast-follow** (draft = no money; stream single-use + rate limit compensate) | **HARD BLOCKER** (money tools) |
| 3c | **per-workspace D-tier rate limit** (cap draft campaigns/hr) | ❌ not done | **HARD BLOCKER** (Kabir: compensating control; without it one injection = unbounded draft flood) | HARD (stricter ceiling than D) |

> **Kabir's P0 — blocks the *entire* batch:** `3a`. Until every write executor asserts tenant match, whitelisting *any* write tool opens a cross-tenant write. Escalated to **Priya** (tenant-isolation owner) for the assertion design.

---

## 3. Feature-by-feature enablement

### LANE 1 — First-name personalization *(ship this week; zero security dependency)*
**How it works today:** Block B carries `display_name` (the *brand* name). The user's own first name never reaches the prompt; `full_name/phone/email` are stripped as forbidden fields.
**Change:**
1. Spring: add `first_name` to `POST /internal/meera/context` response (`MeeraContextDtos.ContextResponse`), sourced server-side from the signed-in user's profile.
2. Python: add `first_name` to `CONTEXT_PAYLOAD_FIELDS` (`assembler.py:51`) and render it into the greeting/persona.
3. CI schema-check diffs the two automatically — keep them in sync.
**Guardrails (Kabir + Priya):**
- Treat `first_name` as **untrusted** — neutralize/delimiter-wrap (`_safe`), a user can set it to an injection string.
- Do **NOT** place it in cached Block A. Safest: per-turn (non-cached) or Block B **only if** the cache key is strictly per-user. `cache_key_for` today is `(prompt_version, audience, workspace_id, session_id)` — **verify a workspace can't have multiple users sharing a session cache**, or first-name bleeds across users. (Priya: real cross-user leak if the key is workspace-only.)
- Do **NOT** relax the `full_name/phone/email` stripping — add exactly `first_name`, nothing adjacent (both leads, explicit).
**Owner:** Vikram (Spring context field) + Ananya/AI (assembler). **Risk:** minimal.

### LANE 2 — Wallet talk *(ship next; read-only, rides Phase-1 read scope)*
**How it works today:** `assembler.py` strips `wallet_balance` from the model's context (Kabir guardrail #4). Meera literally cannot see the balance. Public API brand wallet read works, but that's the UI, not the AI.
**Decision (Priya + Kabir agree — reject the easy option):** add a **new R-tier read tool** (`check_wallet` / `show_wallet`), **NOT** a balance field in Block B.
- **Why not Block B:** cached → stale the moment money moves (Meera would quote wrong numbers); relocates a deliberately-stripped secret into a broadly-logged blob; ambient vs. auditable.
- **Tool design:** fetches balance **fresh at call time** through an authorized, scope-gated, audited read path. Never cached.
- **Kabir constraint:** return a **coarse band** ("sufficient / low / insufficient for this campaign") rather than exact rupees where possible — caps exfil value under injection; the model rarely needs the precise figure to converse. Exact figure only if product truly needs it.
- **Swapnil hard-no #3:** wallet is **read-only** for the AI — Meera can *tell* you the balance, never transfer/withdraw/commit. (This tool is R-tier; it structurally cannot move money.)
**Work:** new R-tier tool schema (`schemas.py`) + Spring `/internal/meera/show_wallet` executor (read-only, workspace-scoped, scope-gated like `show_creators`) + add to minted read scope. **Owner:** Vikram + AI.

### LANE 3 — AI campaign creation (create_campaign, D-tier) *(ship after Phase-2 fixes)*
**How it works:** brand chats → Meera calls `create_campaign` → `CreateCampaignExecutor` writes a `Campaign(status=DRAFT)` + `CampaignIntent`. No money field writable; reversible; invites nobody.
**Blockers to clear first (§2):** `3a` conv↔workspace cross-check (HARD) + `3c` D-tier rate limit (HARD). `3b` jti single-use is a fast-follow for draft.
**Then the enable step (one line):** add `create_campaign` to a new `SCOPE_DRAFT` (= read set + `create_campaign`) in `OnBehalfTokenService.mint`. Resolver, executor, route already built.
**No human-confirm on create_campaign** (Priya, firm): it's D-tier — draft, reversible, no money. Adding a confirm trains users to click through confirms and erodes the C-tier confirm that *actually* matters. The rate limit (3c) is the safety net that replaces a confirm.
**Also fix (P1, from prior review):** stop offering `create_campaign` to Claude until scope is granted — today the tool is in `get_tool_schemas()` unconditionally, so the model calls it and burns a user credit on a guaranteed 403. Filter the offered tool set by the currently-enabled scope. **Owner:** Vikram (3a, 3c, scope) + AI (tool-exposure filter).

### LANE 4 — Hire creators + private campaign + Razorpay (C-tier) *(ship last)*
**The full money path (all built, strong guardrails):**
```
create_campaign (DRAFT)
  → request_payment  → AmountDerivationService re-derives amount server-side,
                       rejects AI amount drift (409), returns PENDING_CONFIRM, NEVER debits
  → [HUMAN CLICKS]   → public POST /brand/escrow/fund  → Razorpay
  → Razorpay webhook → EscrowStatus.FUNDED (verified, webhook-only)
  → confirm_launch   → reads FUNDED fresh from DB (never trusts AI), charges publish fee
                       in same @Transactional, then invites creators
```
**Security invariants to preserve (Kabir, verify in tests):**
- Meera must **never** be able to call `POST /brand/escrow/fund` — keep the debit on the human-clicked endpoint + webhook. Assert this whitelist exclusion explicitly.
- C-tier requires OWNER/ADMIN **and** scope — assert the elevated-role gate in the **executor**, not only at mint.
- `3b` on-behalf jti single-use is now a **HARD** prerequisite (money). Plus idempotency keys on request_payment/confirm_launch (kill double-charge on retry/replay) — already present, keep them.
- Stricter C-tier rate limit than D.

**THE ONE CONFLICT — resolved:**
- Priya: shipping C-tier while `confirm_launch` auto-invites a **global top-N by follower count** (its current behavior, `ConfirmLaunchExecutor.inviteCreators`) is a **correctness bug** — it would charge a fee and invite the *wrong* creators.
- Swapnil (final authority): targeted hiring is **not a v1 blocker** — "AI drafts, brand picks creators in the UI" is fine for v1 — **but hard-no #2: no auto-invite to creators without brand review.**
- **Reconciliation:** Swapnil's hard-no #2 **removes** the global-pool auto-invite anyway. So:
  - **v1:** `confirm_launch` funds escrow + activates the campaign but **does NOT auto-invite** creators. The brand reviews and picks creators in the UI (existing invite flow). This satisfies Priya (no wrong-creator invite) **and** Swapnil (brand review + fine to ship without targeting).
  - **v1.1:** wire `creator_ids[]` end-to-end — `create_campaign` persists them, `confirm_launch` invites exactly those, "private campaign" visibility flag added to `Campaign` for the AI path. Targeted, reviewed hiring.
  - **Deferred (own ticket):** relevance/ranking ("who *should* I hire") — Priya: targeting = honoring an explicit list; recommendation is a separate feature.

**"Private campaign":** no dedicated visibility flag exists on the AI path today. Add it in v1.1 alongside `creator_ids` (a targeted hire is inherently a private/closed call, not a public open brief).

**Swapnil's go/no-go on real money (B):** **HOLD commit-tier** until draft-tier proves out live — unlock C-tier only after **50+ campaigns created through the AI path** with no support escalations / "I didn't mean to do that" complaints. Draft-tier telemetry is the gate.

---

## 4. Consolidated ship order (put in the plan verbatim — Priya)

| Lane | Feature | Gate | When |
|---|---|---|---|
| 1 | First-name greeting | none (verify cache-key is per-user) | this sprint |
| 2 | Wallet talk (R-tier read tool, coarse band, fresh fetch) | rides live read scope | this sprint |
| 3 | AI campaign creation (create_campaign, D) | **3a conv↔workspace + 3c rate limit** (HARD); 3b fast-follow; + tool-exposure filter | after Phase-2 fixes land |
| 4 | Hire + private + Razorpay (C) | Phase 3 live + 3b + executor role gate + fund-endpoint exclusion + **draft-tier proven (50+ live, no incidents)** | after draft-tier soak |

---

## 5. Findings (Ash, prioritized)

**P0 — BLOCK the whole batch (Kabir):** `3a` conversation_id↔workspace cross-check missing in write executors → cross-tenant write primitive. *Fix:* assert `conversation.workspaceId == token.workspaceId` in every write executor (pattern already exists on `/messages`, `MeeraInternalController.java:222-223`). *Owner:* Vikram, design → Priya.

**P0 — required before D-tier (Kabir):** `3c` per-workspace D-tier rate limit. *Fix:* extend the existing `DAILY_ACTION_LIMIT_EXCEEDED` signal (`MeeraSessionService`) to D-tier executions. *Owner:* Vikram.

**P1 — wasted credits, model-quality defect (Ash):** `create_campaign` is offered to Claude unconditionally (`schemas.py:252 get_tool_schemas`) but 403s → burns a user's AI credit per attempt on a guaranteed failure. *Fix:* filter offered tools by currently-enabled scope; until enabled, Meera *talks* the brand through what it can do. *Owner:* AI.

**P1 — correctness (Priya):** `confirm_launch` global-pool auto-invite. *Fix (v1):* disable AI auto-invite, brand picks in UI. *(v1.1):* targeted `creator_ids`. *Owner:* Vikram.

**P1 — before C-tier (Kabir):** `3b` on-behalf jti single-use replay store. *Fix:* consumed-jti store mirroring `replay_guard.py`, keyed on the on-behalf JWT jti. *Owner:* Vikram + AI.

**P2 — wallet exfil surface (Kabir):** if wallet tool returns exact rupees, cap to a coarse band. *Owner:* AI + Vikram.

**P2 — cross-user cache leak risk (Priya):** verify `cache_key_for` is per-user before putting `first_name` in Block B. *Owner:* AI.

---

## 6. Data & training roadmap
- **Now:** log a counter for "tool offered but 403'd" (measures the offered-vs-authorized drift directly). Log every `create_campaign` draft.
- **Next:** once create_campaign is live, log `{fields Claude drafted}` vs `{fields the human edited before funding}` — the highest-value few-shot/eval corpus for improving draft quality, and exactly the telemetry Swapnil's 50-campaign gate needs.
- **Later:** an eval set for campaign-type/template and (v1.1) creator-targeting selection, once volume justifies it.

---

## 7. Verdict
**SHIP IN 4 LANES, gate chain enforced.** Lanes 1–2 (first-name, wallet talk) this sprint — independent, low-risk, under the stated constraints. Lane 3 (AI campaign creation) **only after** `3a` + `3c` land. Lane 4 (money path) **only after** draft-tier soaks live (50+ campaigns, no incidents) and `3b` + executor role gate + fund-endpoint exclusion are in.

**Hard blocks:** `3a` (batch-wide, Kabir P0) · any scope widening past its phase's guards (Priya) · commit-tier before draft-tier proves out (Swapnil) · wallet balance ever being writable by the AI (Swapnil) · un-stripping full_name/phone/email alongside first_name (both leads).
