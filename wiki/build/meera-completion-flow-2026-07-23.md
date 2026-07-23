# Build: Meera conversational campaign completion — STANDARD Option B + full HYPE (2026-07-23)

Decision: Swapnil/Priya approved **Option B MVP for STANDARD** + **full conversational HYPE** (with the edit-mode build). Design: Ash's point-wise behavior spec (this session). Money guardrail: AI proposes, human commits every rupee — NO AI-persisted budget/rate/slots.

## Owners

### Vikram — AI service + backend
- `influora-ai/app/prompt/persona.py`:
  - STANDARD completion behavior (§2): after create_campaign DRAFT, detect missing fields from DRAFT STATE (budget null → dates null), ask ONE field per turn (budget via calculate_budget first, then dates), honest completion CTA ("drafted — open to set budget+dates, then publish"), never "live".
  - HYPE completion behavior (§3): on explicit HYPE signal only; compose hashtag+format lanes; ask sourceReelUrl → perReelRate (propose via calculate_budget, human sets) → slotCap → say "rate × slots = ₹X escrow" out loud → confirm 72h window; one field per turn.
- `influora-ai/app/tools/schemas.py`: add FLAT optional `source_reel_url` (string) + `format_lanes` (array of string) to create_campaign. NO `per_reel_rate`/`slot_cap` (money = human). **NO combinators (anyOf/oneOf/allOf) — Anthropic 400s the whole payload (we just hotfixed exactly that).**
- `influora-api/.../service/meera/tool/CreateCampaignExecutor.java`: when campaign_type HYPE and template==null, persist source_reel_url + format_lanes into a PARTIAL HypeConfig JSON on the draft (rate/slots/liveUntil left for the human). Never set perReelRate/slotCap/budget.
- `influora-ai/app/config.py`: bump PROMPT_VERSION.

### Ananya — Frontend
- `src/pages/brand-new-hype-campaign.tsx`: add EDIT/RESUME mode — optional `campaignId` (useParams), load the draft (`GET /api/v1/campaigns/:id`), prefill from its HypeConfig (sourceReelUrl, hashtag, formatLanes, and any partial), let the human set perReelRate + slotCap, and on submit UPDATE the existing draft (not create new) → status ACTIVE (preserve the existing launch semantics + WORKSPACE_NOT_VERIFIED handling). DRAFT-aware primary action ("Review → Launch"), not blind create.
- Routing: branch `/brand/campaigns/:id/edit` by `campaignType === 'HYPE'` → the hype edit form (or a dedicated `/edit/hype` route). STANDARD keeps the existing wizard.
- `src/components/feature/meera/ToolResultRenderer.tsx`: deep-link the DRAFT result card — STANDARD → `/edit?budgetHint=&start=&end=` (editable hints); HYPE → the hype edit screen. No fake ₹0 (already done).
- `src/components/brand/campaigns/campaign-form.tsx`: read `useSearchParams` on the budget step, render Meera's budget/date hints as EDITABLE soft-prefill the human must actively confirm — NOT auto-satisfying validation (masquerade guard, Ash §7.2).

## Guardrails (non-negotiable)
- No AI-persisted money: STANDARD budget, HYPE perReelRate + slotCap all HUMAN-ONLY. Meera proposes verbally (grounded in calculate_budget), human types + server re-derives.
- No schema combinators (Anthropic 400).
- HYPE draft must route through DRAFT → explicit human launch (never AI one-click ACTIVE).
- Prefill is advisory; the human keystroke on money must be real.

## Gate chain
Vikram + Ananya → Kabir (money guardrail: no AI write of budget/rate/slots) → Kavya QA → build → deploy → neha live E2E (STANDARD completion + HYPE completion both drive to a real publish/launch).
