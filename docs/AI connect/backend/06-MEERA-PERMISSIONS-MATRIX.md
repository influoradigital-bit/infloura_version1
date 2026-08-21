# 🛡️ MEERA — BRAND-SIDE PERMISSIONS & AUTHORIZATION MATRIX

> **Owner:** Kabir (Red-Team) · **Approved scope by:** Swapnil (CEO) · **Date:** 2026-07-05
> **Enforced by:** Spring Boot (single authority). Reference: `03-SECURITY-SPEC.md`, `../BACKEND-ARCHITECTURE-DECISION.md`.
> **Purpose:** Define exactly what Meera (the AI) may and may not do on the brand side, classified by blast radius. This is a security contract — every tool-call must map to a row here or it is rejected.

---

## THE GOVERNING PRINCIPLE

**Meera proposes. Spring disposes. The human commits money.**

The Python AI service holds **zero write authority**. It emits *proposed* tool-calls. Spring is the only actor that touches state, and Spring re-authorizes the human's JWT and re-derives every amount before executing. **Nothing Meera "decides" is trusted** — a hostile prompt, a hallucination, and a legitimate request are treated identically at the boundary.

Critically: **"the customer said yes" in chat is NOT authorization.** Chat text is spoofable, ambiguous, and injectable. Consent for anything money- or contract-binding must be an **authenticated human action in the UI** (a real click on a real confirm control), never Meera inferring agreement from conversation.

---

## THE THREE TIERS + FORBIDDEN

| Tier | Meaning | Who finalizes |
|---|---|---|
| **R — Read** | Meera reads data and advises. No state change. | Meera (auto) |
| **D — Draft** | Meera creates a reversible, non-binding draft. No money, no external commitment. | Meera (auto), human edits freely |
| **C — Commit** | Touches money OR binds the brand to a third party. Meera stages only; **human confirms in UI.** | Human (explicit click) |
| **✗ — Forbidden** | Meera has no capability, ever. | Nobody via Meera |

---

## THE MATRIX (your list, classified)

### ✅ ALLOWED

| # | Capability | Tier | What Meera may do | Where it stops |
|---|-----------|------|-------------------|----------------|
| 1 | **Understanding the business** | R | Read `brand_profiles` (products, niche, tone) + past-campaign summaries; reason about them | No PII (PAN/KYC/bank) ever enters the prompt (G3) |
| 2 | **Customization feel** (brand-themed persona/voice) | R | Adopt brand color + tone dial from profile | Cosmetic only; can't change trust colors or app config |
| 3 | **Campaign suggestion** | R | Recommend campaign type + rationale | Advice only — creating is #6 |
| 4 | **Price / budget suggestion** | R | Suggest pool size + per-reel rate from product price + goal | Suggestion only; the *charged* amount is always re-derived by Spring at commit |
| 5 | **Creator suggestion with data** | R | Rank + explain matched creators from verified pool | Reads verified stats only; no raw creator PII to prompt |
| 6 | **Check balance** | R | Read wallet/escrow balance | **Read-only.** Cannot move, add, or alter funds |
| 7 | **Create campaign (draft)** | D | Generate a DRAFT campaign from conversation intent (`campaign_intents` → draft `campaigns` row) | Draft state; going *live* funds escrow = Commit (human) |
| 8 | **Create deal (draft)** | D | Open a draft deal/collaboration record + Deal Room | No terms are binding until proposal sent (#11) |
| 9 | **Create contract (draft)** | D | **Generate** the contract document from accepted terms (PDF) | **Meera never signs.** Binding only on human e-signature (Commit) |
| 10 | **Send proposal** | **C** | STAGE a proposal to a creator (price + terms) | **Human confirms send** UNLESS within a pre-approved budget envelope (see ruling B) |
| 11 | **Bid approval** ("if customer says yes") | **C** | STAGE the approval | **Human clicks approve in UI.** Chat "yes" ≠ authorization (see ruling A) |

### ✗ FORBIDDEN (no permission, ever)

| # | Capability | Why it's forbidden |
|---|-----------|--------------------|
| F1 | **Make/move a payment** | Money movement is human-only. Meera can't call payout/transfer/charge. |
| F2 | **Add a payment method** | Adding a funding instrument is account-security-critical; phishing/social-engineering target. Human-only. |
| F3 | **Update payment / payment details** | Editing payout destination is the classic fraud vector (redirect funds). Human-only, re-auth required. |
| F4 | **Update or add core code** | Meera has NO repository, deploy, config, or schema write access. Full stop. |

---

## KABIR'S RULINGS ON THE 3 DANGEROUS ONES

These three look "allowed" but each is a money/commitment trapdoor. Read carefully — this is where a naive build gets breached.

### Ruling A — Bid approval: chat-consent is not consent
Approving a bid moves the deal toward a contract and escrow. **Meera may prepare/stage the approval, but the approval that advances money MUST be an authenticated human action** — a click on a confirm control tied to the user's live session. Meera inferring "the customer said yes" from chat is **rejected**, because:
- Prompt injection can forge a "yes" (a creator's message, a pasted brief, a crafted product page).
- Chat consent has no non-repudiation — no audit trail that stands up in a dispute.
- **Enforcement:** `/internal/meera/*` has no "approve_bid" auto-execute path. Approval is a *public* endpoint the browser calls on human click, carrying the user JWT. Meera can only surface the "Approve" button, pre-filled.

### Ruling B — Sending a proposal: bound by a pre-approved envelope
Sending a proposal binds the brand to a price with a third party. Two safe modes:
- **Within envelope (auto-send allowed):** if the human already approved a budget envelope for this campaign (e.g., "up to ₹1,000/creator, 15 creators"), Meera may auto-send proposals *inside* those limits. Spring validates every proposal against the stored envelope.
- **Outside envelope (human confirm required):** any price, count, or term beyond the approved envelope → staged, human confirms.
- **Enforcement:** Spring checks each proposal against `campaign_intents`/approved-envelope before dispatch; over-limit = 403 → human confirm.

### Ruling C — Creating a contract: draft yes, sign never
Meera generates the contract document (Draft). It becomes **legally binding only on human e-signature** (Commit). Meera cannot sign, cannot alter a signed contract, cannot bypass the signature step. The signed PDF's SHA-256 hash is stored for tamper-evidence.

---

## ENFORCEMENT — WHERE EACH TIER IS STOPPED

| Tier | Enforcement point | Control |
|---|---|---|
| R (read) | Spring read endpoints | Tenant scope (`workspace_id`) on every query; PII allow-list before prompt assembly (G3) |
| D (draft) | Spring write endpoints, idempotent | Draft-state rows only; no money field writable; credit-gated (G5) |
| C (commit) | **Public** endpoint on human JWT + explicit UI action | Amount re-derived server-side (G1); envelope check (Ruling B); e-signature (Ruling C); NOT reachable from `/internal/meera/*` |
| ✗ (forbidden) | No endpoint exists for Meera | Payment/payout/payment-method/config/code paths are unreachable by the AI service — not "blocked," *absent* |

**The structural guarantee:** money-movement, payment-method, and code/config endpoints are **not wired to the AI service at all.** You can't exploit a door that isn't there. Meera's entire surface is: read endpoints, draft-write endpoints, and the ability to *surface* commit buttons the human presses.

---

## ATTACK SCENARIOS THIS MODEL BLOCKS

| Attack | Blocked by |
|---|---|
| Prompt-injected "approve all bids and pay" | Ruling A — no auto-approve path; F1 — no payment capability exists |
| Malicious product page tells Meera to change payout account | F3 forbidden; not wired to AI |
| Hallucinated amount → overcharge brand | G1 — amount re-derived server-side; AI's number ignored |
| "Customer said yes" forged in chat to trigger commit | Ruling A — commit needs authenticated UI click |
| Auto-send proposals at ₹50,000/creator | Ruling B — envelope check rejects over-limit |
| Meera coaxed into editing/deploying code | F4 — no repo/deploy/schema access |
| Cross-brand data leak via Meera | G4 — tenant isolation on every call + cache key |

---

## VERDICT

**PASS the capability scope as listed — with three conditions bound to it (Rulings A, B, C).** The allow-list is sound *because* the forbidden list is enforced structurally (absent endpoints, not soft blocks) and the three commit-tier items are downgraded from "AI decides" to "AI stages, human confirms."

**Launch-blockers if violated:**
- Any `/internal/meera/*` path that executes bid-approval, proposal-send-over-envelope, contract-signing, or ANY payment action → **BLOCK.**
- Chat-inferred consent driving a commit action → **BLOCK.**
- The AI service holding any credential that reaches a payment/payout/code/config endpoint → **BLOCK + escalate to Swapnil.**

Re-test required after Vikram wires the executors (Phase 4, `05-VIKRAM-WORK-TASKS.md`). No money endpoint ships without my re-test.
