# ADR: Wave D3 — IntegrationHealthService campaign-creation gate scope

**Date:** 2026-07-07
**Author:** Priya Sharma (CTO)
**Status:** DECIDED
**Decision class:** Scope/architecture call (lightweight — not full ADR ceremony, per escalation)
**Escalated by:** Kavya (QA) — `wiki/errors/wave-d3-integration-health-kavya-qa.md`
**Precedent pattern:** same "partial implementation of a stated requirement, needs explicit sign-off" class as the C3 JWKS ADR and the D4 disbursement-boundary gate.

---

## Decision

**Option B (scoped-minimum variant). D3 does NOT proceed to "done" on the AI path alone.**

The store-connection gate must also cover the **human REST creation path** (`POST /campaigns` → `CampaignService.create`) before D3 is marked done. But D3's scope is the **minimum needed to close the gate on the primary path**, NOT the full campaign-type product taxonomy (that stays with Ananya's D5 / a future schema effort).

Vikram's AI-path implementation is excellent (Kavya rates it 10/10, 9 load-bearing tests, workspace-scoped, additively extensible for WooCommerce). It is kept as-is. This decision **adds** the human-path gate; it does not rework what exists.

---

## Why B, not A (the load-bearing reason)

The deciding question the escalation correctly identified: **which path carries the real campaign-creation volume?** I verified this in code rather than assuming.

**The human form is the primary/default creation path. Meera is a secondary assist.**

- `src/components/brand/campaigns/campaign-form.tsx` is the actual brand UI: a full 5-step "Create Campaign" wizard (Basics → Content → Budget → Requirements → Review) that posts to `api.campaigns.create` → `CampaignService.create` — **the ungated path**.
- Its objective options include first-class **"Drive Sales"** and **"Product Launch"** (line ~134). A brand builds a functional sale campaign here with zero store connected.
- Meera is a **"Let Meera draft this"** button (line ~458) that only *pre-seeds a conversation* — it explicitly does NOT auto-create (see the component's own P1.2 doc comment: "does NOT auto-fill the form or send anything on the brand's behalf... pending Priya's sign-off on the draft-assist interaction model"). Meera's `CreateCampaignExecutor` is the assist path, and it is the ONLY path currently gated.

So D3 as-built gates the *rare, assisted* path and leaves the *common, default* path — the one where the expected-common-case attack lives (a brand creating a sale campaign with no store connected) — **completely uncovered.** That inverts the point of the task. The acceptance criterion (REMAINING_WORK_PLAN.md D3: "block sale-campaign creation when store not connected") is unqualified — it says sale-campaign creation, full stop.

Two secondary confirmations that the human path is genuinely un-gated and won't be closed by other in-flight work:
1. The Campaign entity, `CampaignWriteRequest` DTO, and `CampaignService.create` have **no campaign-type concept at all** (Kavya verified all three directly). No type → no gate is even expressible there today.
2. D5's planned `CampaignTypeSelector` uses a **different, unrelated taxonomy** (`PaymentModel`: flat_fee | gifted | affiliate | hybrid — `ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §18`) that does not map to the backend `CampaignIntentType.DIRECT` the gate keys on, and is frontend-only with no backend schema behind it. So D5 will **not** incidentally close this gap. Relying on it (Option A's implied follow-up) would leave the hole open indefinitely.

Option A would be defensible only if Meera were the primary creation path. It is not. Documenting the primary attack surface as a "known limitation" is not an acceptable production posture for a gate whose entire purpose is to prevent that surface.

---

## Scope of the D3 follow-up (what Vikram builds before D3 is done)

Minimum to close the gate on the human path. Deliberately narrow — this is a security gate, not the D5 product feature.

1. **Add a `campaignType` concept to the human path sufficient to identify a sale/conversion campaign.** Minimum viable: a nullable `campaign_type` column on `campaigns` (migration V29+) + a `campaignType` field on `CampaignWriteRequest`. Reuse the existing `CampaignIntentType` enum (`DIRECT/HYPE/REVIEW/STANDARD`) so both paths share ONE taxonomy — do NOT introduce a parallel type system.
2. **Mirror the exact gate into `CampaignService.create`:** if the resolved type requires store attribution (same `requiresStoreIntegration(...)` predicate as `CreateCampaignExecutor` — `DIRECT` today), call `IntegrationHealthService.hasActiveStoreIntegration(workspace.getId())` and reject with the identical typed `409 NO_STORE_INTEGRATION` before any `campaignRepository.save`. Factor the predicate so both callers share it — no divergence.
3. **Interim safety for untyped/legacy requests:** since the human form does not send a type yet, decide the default explicitly. Default `null`/absent type to a non-store-dependent type (`STANDARD`) is acceptable **only** because the human form cannot yet declare "sale" as a typed value — but this MUST be paired with (1) so the form can send `DIRECT` and get gated. Do not ship (2) without (1), or the gate is unreachable from the UI and we're back to Option A.
4. **Tests mirroring the AI-path suite:** human-path `DIRECT` + no integration → 409, zero DB write; `DIRECT` + active integration → created; non-DIRECT types → created regardless; workspace-scoping correct.
5. Keep the shared `IntegrationHealthService` and its WooCommerce extension point untouched — both paths call the same service, so D2's additive `OR` closes both gates at once. Good.

This is genuinely small (one column, one DTO field, one mirrored check + shared predicate, one test file). It does not require the D5 selector, does not touch money/escrow, does not block D2.

---

## What this is NOT

- Not a rework of Vikram's AI-path code — that stays as-is.
- Not the full D5 campaign-type product feature — that remains Ananya's, separate.
- Not a WooCommerce dependency — the shared service already handles that additively.

---

## Directive for Arjun

**D3 does NOT advance to Meera live-verify yet. Send back to Vikram for the human-path gate above.**

- Route D3 back to **Vikram** with this ADR as the scope. He keeps his existing AI-path work untouched and adds items 1–4 (shared predicate, migration, DTO field, mirrored gate, tests).
- On Vikram done → **Kavya** re-QA (verify both paths gated, shared predicate, workspace-scoping, no regression) → **Kabir** (business-logic gate sitting next to Commit-tier escrow — same review he'd have done) → **Meera** live-MySQL verify of the new migration (V29+) and build.
- D3 is "done" only when **both** creation paths reject a store-dependent (`DIRECT`) campaign with `409 NO_STORE_INTEGRATION` when no active store integration exists.
- Update REMAINING_WORK_PLAN.md D3 acceptance to read explicitly: "Both AI-drafted (`CreateCampaignExecutor`) and human REST (`CampaignService.create`) sale/conversion campaign creation reject with `NO_STORE_INTEGRATION` when no active store integration is connected."
