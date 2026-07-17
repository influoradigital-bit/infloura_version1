# Ash correction — T4 schema-drift fix proposal contradicts Priya's gate

> **From:** Ash (owns AI tool-schema semantics) · **Date:** 2026-07-11
> **Re:** Meera's `wiki/processes/T4-SCHEMA-DRIFT-FIX-PROPOSAL.md`
> **Authority:** Priya's ruling in `wiki/architecture/priya-wave1-gate.md` (code-verified).
> **Status:** DO NOT implement Meera's proposal as written. Vikram — read this first.

Meera's T4 CI work is correct and valuable: the schema-check exists and **demonstrably fails on the
current drift** (rule 6 satisfied). The **fix proposal attached to it is wrong on two counts.**

## Error 1 — Adding `STANDARD` to `schemas.py` violates Priya's ruling
Meera proposes: *"add missing `STANDARD` to `create_campaign.campaign_type` → both fields use
`[HYPE, DIRECT, REVIEW, STANDARD]`."*

Priya ruled, verified against `CreateCampaignExecutor.parseCampaignType`: **`STANDARD` is the
server-side default/fallback and is deliberately NOT exposed to the AI. Do not add it to
`schemas.py`.** Meera being structurally unable to propose `STANDARD` is *correct by design*, not a
drift. The three proposable types stay three.

→ The **CI check must encode this exception**: Python exposing 3 of Java's 4 types is expected. The
check compares the *proposable* set, with `STANDARD` on a known Java-only allow-list. It must not
red-flag that intentional omission — otherwise it fails forever and blocks every PR.

## Error 2 — `goal` and `campaign_type` are different fields, not one vocabulary
Meera's proposal collapses both to the same enum. They are not the same concept:
- `calculate_budget.goal` = marketing **objective**: `awareness | launch | conversion | review`.
- `create_campaign.campaign_type` = execution **format**: `HYPE | DIRECT | REVIEW`.

Forcing `goal` to `[HYPE, DIRECT, REVIEW, STANDARD]` destroys the distinction between "awareness" and
"conversion." Swapnil's briefing (`...briefing.md:131`) frames this as a `goal ↔ campaign_type`
**mapping** to be resolved in **Wave 2**, not a rename. The mapping (which goal implies which type)
needs Priya/Swapnil sign-off before either enum moves. Do not guess it — that is the exact failure
this batch is about.

## Correct Wave-1 action
1. **Do not** rewrite `schemas.py` enums this wave. The drift fix is Wave 2 (per briefing) and needs
   the mapping ruling first.
2. **Land the schema-check as report-only (non-blocking) for Wave 1**, with the `STANDARD`
   Java-only exception encoded. Flip it to a required, blocking check *after* the Wave-2 mapping is
   ruled and the vocabularies are reconciled. A required check that fails on pre-existing, not-yet-
   scheduled drift would block the V1.2/V1.3 injection fixes from shipping — the ship-blocker work.
3. The one thing that IS Wave 1: fix `create_campaign.campaign_type` casing/vocabulary only if it
   diverges from Java's `HYPE|DIRECT|REVIEW` spelling (it currently matches — no change needed).

## Handoff
`FROM Ash → TO Vikram, Meera, Arjun | T4 fix proposal corrected | wiki/ai-review/T4-schema-fix-correction.md`
`STATUS: STANDARD stays Java-only (Priya). goal↔campaign_type mapping = Wave 2, needs ruling. Land check report-only this wave.`
