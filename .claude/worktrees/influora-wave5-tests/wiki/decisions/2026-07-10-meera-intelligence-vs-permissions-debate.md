# Decision Record: Making Meera Smarter Under a Hard No-Write-To-Money Constraint

Date: 2026-07-10 · Convened by: Swapnil (CEO)
Participants: Swapnil (CEO), Priya (CTO), Arjun (Eng Lead/COO), Kabir (Red-Team), Ash (AI Lead)
Constraint set by Swapnil, non-negotiable: **the AI holds no permission to add, update, or delete payments.**

---

## The question

Ash's review (`wiki/ai-review/influora-ai-service-ai-review.md`) found Meera's campaign
recommendations are ungrounded — she picks `campaign_type` from three undefined enum labels.
How do we make her materially smarter without giving her a single new write capability?

---

## Round 1 — Opening positions

**ASH (AI Lead).** The intelligence problem and the permission problem are orthogonal, and we've
been conflating them. Meera is dumb *not* because she's sandboxed — she's dumb because she has no
knowledge and no evidence. `HYPE`, `DIRECT`, `REVIEW` are never defined anywhere in the codebase or
the prompt. `past_campaign_summary` is a free-text blob with no outcomes in it. There is no tool that
answers "what worked for brands like me." Every one of those gaps is fixable with **reads**. I don't
want a payment write. I've never wanted one.

**SWAPNIL (CEO).** Good. Then the constraint costs us nothing and I'm not relaxing it. But answer my
question: what does this get us? I don't fund prompt engineering. I fund outcomes.

**ASH.** A brand that picks the wrong campaign type funds escrow, gets bad completion, and churns.
Today Meera's recommendation is a coin-flip dressed in confident prose. Getting it right raises
campaign completion rate — that's retention and it's GMV.

**PRIYA (CTO).** Stop. Before anyone adds a tool, I have a structural objection. Ash, you say the AI
has no payment permission. Show me where that's enforced.

**ASH.** `schemas.py:40` — `TOOL_TIERS`. `read`, `draft`, `commit`. `request_payment` and
`confirm_launch` are `commit`.

**PRIYA.** And who reads that dict?

**ASH.** ...`is_money_tool()` at `schemas.py:157`.

**PRIYA.** And who calls `is_money_tool()`?

**ASH.** Only `tests/eval/test_prompt_injection.py:404`. Nothing in `app/`.

**PRIYA.** So the tier system is a comment. `run_tool_loop` (`app/tools/loop.py:180-220`) forwards a
`commit` tool through the identical code path as a `read` tool. The only thing standing between
Meera and a payment call is that Spring refuses to honor it. That's one layer, not two, and it lives
in a different repo maintained by a different agent.

---

## Round 2 — Kabir opens fire

**KABIR (Red-Team).** Priya just made my case, so let me sharpen it. Swapnil's constraint is
"the AI cannot add/update/delete payments." Right now that sentence is **true by accident**.

Three findings, and they compound:

1. **The tier is unenforced** (`loop.py`, no tier check). Meera can emit a `request_payment` tool_use
   and Python will forward it. Safety rests entirely on Spring re-deriving the amount.
2. **P0-1 from Ash's review is still open** — `_wrap_untrusted` (`assembler.py:66`) is bypassable by
   split-rejoin and case variation. Verified, both work. So an attacker's text *can* reach the
   instruction layer.
3. **P0-2 is still open** — Gemini's scraped, unvalidated site output lands raw in the Block B
   **system** block (`assembler.py:76-113`).

Chain them: attacker puts a payload in a product name on a website a brand pastes → Gemini classifies
it → it becomes system text above the rails → it instructs Meera to call `request_payment` →
`loop.py` forwards it without a tier check. Spring stops it. **Spring is the only thing that stops
it.** That is one control, not defense in depth, and Swapnil's constraint deserves better than
"the other team's code catches it."

**SWAPNIL.** Ash. Is Kabir right?

**ASH.** Yes. I flagged both P0s and I under-weighted the tier gap — I read `TOOL_TIERS` and assumed
it was load-bearing. It isn't. Kabir's chain is real and it's my miss.

**SWAPNIL.** Then nobody talks about new tools until that's shut. Priya, what's the fix?

---

## Round 3 — Priya's architecture ruling

**PRIYA.** Enforce the constraint in the type system, not in a dict.

1. **Split the tool registry.** `READ_TOOLS` and `COMMIT_TOOLS` become separate structures.
   `get_tool_schemas()` takes an explicit capability set.
2. **Gate the forward.** `run_tool_loop` asserts `tier == "read"` unless `ctx.allow_commit_tools` is
   explicitly `True`. Default `False`. An unknown or mis-tiered tool fails closed.
3. **Make the money path opt-in per request**, granted by Spring's token claims, never by the model
   and never by the request body — same discipline as the service-token minting note in
   `loop.py:57-63`.
4. Then a payment write isn't *forbidden by convention*. It's **unreachable by construction.**

Once that lands, I have no architectural objection to Ash expanding the read surface as far as he
wants. Reads are cheap to reason about. Writes are where six-month problems come from.

**KABIR.** Add: put both `_wrap_untrusted` bypass payloads into
`tests/eval/test_prompt_injection.py` as regression tests, and add one that asserts a `commit` tool
is never forwarded when `allow_commit_tools=False`. If it isn't in the eval set, it isn't fixed.

**ASH.** Agreed on all four. And note what this buys *me*: once commit is unreachable by
construction, I can be far more aggressive about what I let the model reason over, because the blast
radius of a bad inference collapses to "said something wrong," not "moved money."

---

## Round 4 — Ash's proposal, contested

**ASH.** Four changes. None of them writes anything.

**(A) Define the taxonomy in Block A.** Two sentences per campaign type — what it is, when it wins,
when it fails. ~150 tokens, cached, tenant-agnostic. Today the model reasons about `HYPE` using the
English connotation of the word "hype." This is the highest-leverage change in the service and it is
nearly free.

**(B) Fix the `goal` ↔ `campaign_type` drift.** `schemas.py:82` sends
`goal: awareness|launch|conversion|review`. `02-API-CONTRACT-BRAND.md:156` shows Spring receiving
`goal: "HYPE"`. Two vocabularies, no documented mapping. Also `01-DATA-MODEL.md:284` declares four
campaign types (`HYPE, DIRECT, REVIEW, STANDARD`); the tool schema exposes three. Meera structurally
cannot propose `STANDARD`.

**(C) Give `past_campaign_summary` structure.** Replace the blob with
`[{campaign_type, spend, reach, conversions, completion_rate}]`. Evidence instead of vibes.

**(D) Add ONE read-only tool: `recommend_campaign`.** Returns outcome stats for comparable brands —
same niche, same price band. Read-only. No money. No state change.

**ARJUN (Eng Lead).** Hold on. (D) is not one ticket, it's a program. It needs a Spring executor, a
DTO, a CI diff-check entry, an index over historical campaigns, and a cold-start answer for when we
have four campaigns in that niche and the "recommendation" is statistical noise. And it lands in the
same sprint as Priya's registry split and two P0 fixes. I'm not routing that.

**SWAPNIL.** Arjun's right. What's it worth if we ship only A, B, C?

**ASH.** Most of it. (A) alone moves recommendation quality more than (D) does, because right now the
model knows *nothing* about the products it's recommending. (D) is a multiplier on a base that
doesn't exist yet. I'll take the sequencing.

**ARJUN.** Then here's my routing. Two waves.

- **Wave 1 (blocks ship):** P0-1 + P0-2 → Vikram. Registry split + `allow_commit_tools` gate → Vikram.
  Regression evals → Kabir writes the cases, Kavya gates. Re-review → Ash.
- **Wave 2 (this sprint, unblocked):** (A) taxonomy → Ash drafts, Priya approves as prompt-versioned
  content, bump `PROMPT_VERSION`. (B) contract drift → Vikram, and the CI diff-check must **fail** on
  it or it isn't fixed. (C) schema change → Vikram + Meera migration.
- **Wave 3 (next sprint, gated on data):** (D) `recommend_campaign`, only once (C) has been logging
  for four weeks. No index, no tool.

**ARJUN.** One more. Ash — you asked for a golden eval set two conversations ago and it still doesn't
exist for campaign choice. Ten brands, known-correct campaign type. That's Wave 2, and it gates
Wave 3. We do not build (D) with no way to measure whether it helped.

**ASH.** Fair. I'll write it.

---

## Round 5 — Swapnil rules

**SWAPNIL.**

On the constraint: **it stands, permanently, and it gets stronger.** No add, no update, no delete on
payments — and per Priya, that stops being a rule people follow and becomes a thing the code cannot
express. I don't want to depend on Spring's good manners. Kabir found that the constraint is
currently true by luck. Luck is not a control.

On intelligence: I accept Ash's framing. Smarter and safer are not a trade here. Everything that
makes Meera good at her job is a **read** — knowing what HYPE means, knowing what worked for a
₹899 skincare brand, knowing her own history. Nothing in that list requires touching money. The fact
that Ash's four proposals are all reads tells me the constraint was correctly drawn.

On the miss: Ash flagged two P0s and missed that the tier system was decorative. Priya caught it,
Kabir weaponized it. That is the review process working. Ash — own it, and put the tier gap in the
review doc as **P0-3.**

On sequencing: Arjun's three waves. Approved as written.

One thing nobody said, so I will. Ash's line was *"the recommendation intelligence is a three-word
enum and a hope."* That's the honest thing, not the flattering thing, and it's the same rail we put
in Meera's own persona. Keep talking to me like that.

---

## Decisions

| # | Decision | Owner | Wave |
|---|---|---|---|
| D1 | Payment add/update/delete permanently outside AI capability — enforced structurally, not by convention | Priya | 1 |
| D2 | Split tool registry; `run_tool_loop` fails closed on non-read tools unless `allow_commit_tools=True` from token claims | Vikram | 1 |
| D3 | Fix P0-1 (`_wrap_untrusted` bypass) and P0-2 (unescaped Gemini output in Block B system prompt) | Vikram | 1 |
| D4 | **P0-3 (new):** `TOOL_TIERS` / `is_money_tool` never called in `app/` — tier system is unenforced | Vikram | 1 |
| D5 | Regression evals for both injection bypasses + commit-tool-blocked assertion | Kabir → Kavya | 1 |
| D6 | Campaign taxonomy in Block A; bump `PROMPT_VERSION` | Ash → Priya | 2 |
| D7 | Resolve `goal` ↔ `campaign_type` drift; CI diff-check must fail on it | Vikram | 2 |
| D8 | Structure `past_campaign_summary` with outcome fields | Vikram + Meera | 2 |
| D9 | Golden eval set: 10 brands, known-correct campaign type | Ash | 2 |
| D10 | `recommend_campaign` read-only tool — gated on D8 having 4 weeks of data and D9 existing | Arjun | 3 |

**Verdict: BLOCK ship on Wave 1. Wave 2 approved to start in parallel. Wave 3 gated on data, not on calendar.**

Escalations: none. Cost impact → @rohan (Ash's P1-1: prompt cache hit rate is unverified and may be zero;
the business plan's cost model assumes 65%).
