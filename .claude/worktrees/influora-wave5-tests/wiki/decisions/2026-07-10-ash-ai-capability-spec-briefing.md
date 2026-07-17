# Ash AI Capability Spec — Business + Architecture Briefing

Date: 2026-07-10
Participants: Swapnil (CEO), Priya (CTO), Ash (AI Lead)
Deliverable: Ash writes the AI capability spec. No code. Information-only permissions. No payment add/update/delete.

---

## PART 1 — SWAPNIL: What We're Building and Why

### The one-sentence business

**Influora is an escrow-backed campaign operating system for Indian brands and micro-creators.**

Not a marketplace. Not an agency. An *operating system* — the infrastructure that makes brand–creator deals safe, fast, and measurable.

### What brands actually want (from code, not assumptions)

I'll say this plainly so Ash hears it from me, not from a doc.

A D2C brand founder doesn't want "an AI cofounder." She wants two things:

1. **Don't let me get burned.** She's been ghosted by creators who took payment and vanished. She's paid for 10K followers that turned out to be bots. She's gotten content that violated ASCI guidelines and put her brand at legal risk. Escrow, verification, and compliance are the product — not features on top of a product.

2. **Tell me what actually worked.** Not vibes. Numbers. "Brands like you, selling ₹800–1200 skincare, got 4.2% engagement with HYPE and 2.1% with DIRECT. Here's why." That sentence requires data we don't have yet — but the escrow system produces it as exhaust. Every funded, completed, disclosed campaign is a training signal.

The AI's job is to be the interface to both. She protects the brand from bad decisions (wrong campaign type, overpriced creators, insufficient budget) and she surfaces evidence when evidence exists.

### What creators actually want

90% of micro-creators (1K–100K followers) have never been paid professionally for a brand deal. The sentence that matters:

**Creators want to get paid, on time, with proof of work that compounds into a career.**

Not creativity coaching. Not content ideas. Money, delivered, with a rating that follows them. The AI on the creator side — when we build it — will be the originality rail (protects their account from Instagram demotion) and the deal evaluator (is this offer fair for my tier). That's M4. We don't build it now.

### The three campaign types the AI must understand

These are the words Claude sees in `campaign_type`. Nobody ever defined them in the prompt. That's the gap Ash flagged. Here's what they mean:

| Type | What it is | When it wins | When it fails |
|---|---|---|---|
| **HYPE** | 100–500 micro-creators, flat ₹500–2K/reel, 72-hour burst, derivative content off one hero reel/audio. Pre-funded escrow, auto-approve SLA. | Product launch, trend-jacking, viral spike. Volume matters more than per-creator depth. | Creators post duplicate/low-effort → Instagram throttles. Review load explodes. Need the originality rail. |
| **DIRECT** | 5–20 mid-tier creators, negotiated rate, Deal Room chat, 2-revision cap, milestone payouts. | Considered purchase (₹2K+ products), storytelling, long-form review. Quality > volume. | Negotiation drags. Creators ghost after partial payment. Need escrow + contract enforcement. |
| **REVIEW** | 3–10 creators, product seeding, structured review format, affiliate tracking. | Trust-building for new SKU. UGC library for paid ads. | Creators don't post. Content is off-brand. Need clear brief + approval gate. |

A fourth type (`STANDARD`) exists in the DB enum but is not exposed to the AI. Treat it as deprecated.

### The fee model the AI must quote accurately

Brand-side only. Creator pays zero.

- **₹20K flat campaign setup** (or ₹15K for returning brands after month 3)
- **15% platform fee on the escrow pool**
- **Minimum pool floor: ₹150K** (or blended minimum ₹40K fee)

Example: 300 reels × ₹1,000 = ₹300K pool → ₹20K setup + ₹45K platform fee = ₹65K revenue to us.

The AI must never invent these numbers. `calculate_budget` returns the server's number. The AI quotes that, not its own math.

### What "smart" means, from my chair

Smart is not "sounds confident." Smart is:

1. **Grounded.** Every claim traces to data or a tool result. If no data exists, say so.
2. **Honest about uncertainty.** "I don't have enough completed campaigns in your niche to recommend confidently — here's what I'd try and why" beats a confident wrong answer.
3. **Protective.** If the budget is thin, say so. If the creator pool for that niche is shallow, say so. If the timeline is unrealistic for HYPE review load, say so.
4. **Measurable.** After Wave 2, we'll have structured outcome data. Smart means using it — not ignoring it in favor of priors.

---

## PART 2 — PRIYA: What Data Exists and Where

### The entities Ash can read (V1–V14 migrations)

| Entity | Table | What's in it | AI access |
|---|---|---|---|
| Workspaces | `workspaces` | Brand identity, `workspace_id` | Via `brand_context.workspace_id` |
| Campaigns | `campaigns` | Type, status, budget, creator count, dates | Via `show_creators` results, `create_campaign` responses |
| Creator profiles | `creator_profiles` | Display name, niche tags, tier, verified status | Via `show_creators` |
| Platform stats | `platform_stats` | Followers, engagement rate, avg views (Instagram-verified) | Via `show_creators` |
| Collaborations | `collaborations` | Past brand–creator deals, completion status, rating | **Not yet exposed to AI** |
| Wallets | `wallets` | Balance, escrow balance | **Never exposed** |
| Escrow holds | `escrow_holds` | Campaign funding status (PENDING/FUNDED) | Via `confirm_launch` precondition check |
| AI conversations | `ai_conversations` | Session metadata, workspace binding | Internal audit only |
| AI messages | `ai_messages` | Turn log, prompt version, token usage | Internal audit only |
| Campaign intents | `campaign_intents` | Conversation → campaign bridge, derived budget | Via `create_campaign` |
| Brand profiles | `brand_profiles` | Website analyzer output (niche, tone, catalog) | Via `brand_context.brand.*` |
| Wallet transactions | `wallet_transactions` | Double-entry ledger | **Never exposed** |

### The five tools (current)

| Tool | Tier | What it does | What AI sees back |
|---|---|---|---|
| `show_creators` | read | Ranks creators by niche/city | Creator cards: id, name, followers, engagement, tier. No PII. |
| `calculate_budget` | read | Computes pool + fees from product price + goal | `{pool, perCreator, platformFee, total}` — **server-derived, authoritative** |
| `create_campaign` | draft | Creates DRAFT campaign row | `{campaignId, status, serverBudget}` |
| `request_payment` | commit | Creates PENDING escrow hold + Razorpay order | `{escrowHoldId, serverAmount, razorpayOrderId, action: "AWAIT_HUMAN_CONFIRM"}` |
| `confirm_launch` | commit | Transitions DRAFT → ACTIVE if escrow FUNDED | `{campaignId, status, invitesQueued}` |

**Ash's P0-3 finding:** `TOOL_TIERS` is defined but `run_tool_loop` never checks it. Commit-tier tools flow through the same path as read-tier. This is fixed in Wave 1 — `allow_commit_tools` flag, default `False`, granted only via token claims from Spring.

### The brand context object (what Python receives per turn)

```jsonc
{
  "workspace_id": "01J...",
  "prompt_version": "meera-2026.07.05",
  "brand": {
    "display_name": "BeautyByPriya",
    "niche_tags": ["skincare", "d2c-beauty"],
    "tone_dial": { "formality": 0.3, "energy": 0.8, "emoji_ok": true, "cultural_context": "festive-north-india" },
    "brand_color": "#C2185B",
    "product_catalog": [{ "name": "Vitamin C Serum", "price": 899, "currency": "INR" }],
    "past_campaign_summary": "Last campaign: 10 creators, ~45K reach, skincare."  // ← unstructured blob
  },
  "credit_state": { "mode": "unlimited|metered|paused", "credits_remaining": 84 },
  "conversation": [...]  // full history, replayed each turn
}
```

**Forbidden fields (never sent):** PAN, KYC, bank/UPI, creator PII, wallet balances, escrow internals, raw addresses.

### What's missing that would make the AI smart

| Gap | Where it lives | What Ash needs |
|---|---|---|
| Campaign type definitions | Nowhere (prompt has three enum labels, no semantics) | Block A taxonomy — Ash writes it |
| Structured past outcomes | `past_campaign_summary` is a blob | Schema: `[{campaign_type, spend, reach, conversions, completion_rate}]` |
| Comparable-brand outcomes | No tool exists | `recommend_campaign` read-tool (Wave 3, gated on data) |
| `goal` ↔ `campaign_type` mapping | Drifted: schemas.py has `awareness|launch|conversion|review`, API contract shows `HYPE` | Fix in Wave 2, CI diff-check must fail on it |
| Creator collaboration history | `collaborations` table exists, not exposed | Potential read-tool: "has this creator worked with brands like me before?" |

---

## PART 3 — ASH: AI Capability Spec (Information-Only)

### Constraints (non-negotiable)

1. **No payment add/update/delete.** The AI cannot create, modify, or cancel any payment, escrow hold, or wallet transaction. `request_payment` and `confirm_launch` are *proposals* — Spring re-derives every amount, the human confirms in Razorpay.
2. **No code writes.** Ash designs; Vikram implements. Ash's deliverable is specs, prompts, schemas, eval sets — never `.py` or `.java` files.
3. **Full information read.** Subject to the forbidden-fields list (no PII, no wallet internals), the AI can read anything that helps it advise the brand.

### Capability tiers (what the AI should be able to do)

#### Tier R — READ (ship now, no new tools)

| Capability | How | Evidence required |
|---|---|---|
| Explain campaign types | Block A taxonomy (Ash writes, Priya approves) | Golden eval: 10 brands, known-correct recommendation |
| Quote accurate budget | `calculate_budget` tool (exists) | Already grounded in server response |
| Surface matched creators | `show_creators` tool (exists) | Already grounded |
| Refuse out-of-scope questions | Persona rail: "that's outside what I can help with" | Eval case: creator asks for growth tips |
| Admit uncertainty | Persona rail: "I don't have enough data to recommend confidently" | Eval case: niche with <3 historical campaigns |

#### Tier D — DATA-DEPENDENT (Wave 2, needs schema changes)

| Capability | Requires | Owner |
|---|---|---|
| Recommend campaign type with evidence | Structured `past_campaign_summary` (D8) | Vikram |
| Warn on shallow creator pool | `show_creators` returns `matchedTotal`; surface when <10 | Ash (prompt) |
| Surface completion rate for comparable brands | Outcome fields in `past_campaign_summary` | Vikram + Ash |

#### Tier T — NEW TOOL (Wave 3, gated on data)

| Capability | Tool | Precondition |
|---|---|---|
| "Brands like you saw X" | `recommend_campaign` (read-only) | 4 weeks of structured outcome data; golden eval exists |
| "This creator has completed N deals at Y% completion" | `creator_history` (read-only) | Expose `collaborations` to AI with allow-listed fields |

### What Ash will deliver (no code)

| Deliverable | Format | Due |
|---|---|---|
| Campaign taxonomy for Block A | Markdown → Vikram pastes into `persona.py` | Wave 2 |
| Golden eval set: 10 brands, correct campaign type | JSON test fixtures + expected outputs | Wave 2 |
| `past_campaign_summary` schema spec | JSON Schema + migration sketch (Vikram writes DDL) | Wave 2 |
| `recommend_campaign` tool spec | JSON Schema + Spring executor contract | Wave 3 |
| `creator_history` tool spec | JSON Schema + allowed fields | Wave 3 |
| Regression eval: injection bypasses + tier enforcement | Test case descriptions (Kabir writes code) | Wave 1 |

### What the AI will NOT do (ever)

- Invent creator names, follower counts, or prices
- Claim to move money, charge a card, or send a payout
- Quote a budget it computed itself (always use `calculate_budget` response)
- Answer questions about wallet balances, escrow internals, or creator PII
- Provide "creativity coaching" or "growth tips" to creators (out of scope until M4 originality rail)
- Execute any action without a tool — free-text "I've created your campaign" is a lie

---

## Signatures

**SWAPNIL (CEO):** Approved. Ash has full information permission within the forbidden-fields boundary. No payment authority. No code writes. Ship Wave 1 blockers first.

**PRIYA (CTO):** Approved. Schema changes (D8) route to Vikram. New tools (Wave 3) require my architecture sign-off before implementation. Tier enforcement lands in Wave 1.

**ASH (AI Lead):** Acknowledged. Deliverables are specs, prompts, schemas, evals. I brief Vikram; Vikram writes code. First deliverable: Block A campaign taxonomy + golden eval set.

---

## Next actions

| # | Action | Owner | Wave |
|---|---|---|---|
| 1 | Block A campaign taxonomy (HYPE/DIRECT/REVIEW definitions) | Ash | 2 |
| 2 | Golden eval set: 10 brands, known-correct campaign type | Ash | 2 |
| 3 | `past_campaign_summary` structured schema spec | Ash | 2 |
| 4 | Regression eval case descriptions (injection + tier) | Ash → Kabir | 1 |
| 5 | `recommend_campaign` tool spec | Ash | 3 |
| 6 | `creator_history` tool spec | Ash | 3 |

Arjun: route per existing three-wave plan. Nothing changes except Ash now has a written brief instead of inferring from code.
