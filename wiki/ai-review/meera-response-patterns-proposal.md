# Proposal: Meera Self-Referential Response Patterns

**Author:** Ash (AI/ML) · **For sign-off:** Priya (CTO) · **Date:** 2026-07-20
**Status:** PROPOSAL — no code yet, needs Priya approval before build
**Routes to:** Vikram (Python tool contract), Ananya (canvas cards), Kavya (QA), Meera (verify)

---

## The idea in one line

Give Meera a small, **closed, versioned vocabulary of response patterns** that she
**declares herself** (self-reference), so the UI renders each pattern as its own
component instead of dumping prose — and neither side needs a second model to
"guess" the pattern.

## Why (the problem)

Meera's persona wants 1–2 short spoken sentences, but when the brand asks to
compare things (e.g. "review vs hype vs direct for my rice cooker"), the model
falls back to a wall of prose with `**bold**` headers — because **there is no
pattern for "presenting options."** Chat becomes an essay; the voice reads it all.

## The key insight: we already HAVE a pattern identifier

The 5-tool loop IS one. The model declares a pattern by **calling a tool**, and
`ToolResultRenderer` → canvas renders it:

| AI is doing… | declared via | renders as |
|---|---|---|
| showing creators | `show_creators` | StageMatching cards |
| sizing a budget | `calculate_budget` | StageRecommend breakdown |
| funding / launching | `request_payment` / `confirm_launch` | fund / live cards |

The gap is **conversational** patterns that aren't server actions — "here are
options", "here's a 3-step plan", "confirm this". So we **extend the existing
mechanism**, we don't invent a new one.

## Design: a self-declared pattern vocabulary

A **closed set** of response patterns. `say` is the default (99% of turns);
the rest are opt-in and only for genuine structure moments.

| pattern | when Meera uses it | renders as | spoken line |
|---|---|---|---|
| `say` (default) | normal reply | plain bubble | the sentence |
| `options` | a choice among alternatives | tappable cards, one `recommended` | "I'd go review — want it?" |
| `plan` | a short ordered set of steps | compact checklist (never read as a list) | "here's the 3-step plan" |
| `confirm` | a yes/no action gate | confirm card | "ready to fund?" |

`budget` / `creators` / `payment` already exist as tools — this just adds the
**conversational** patterns that are missing.

### Mechanism — reuse the tool contract (self-reference done right)

Add **display-only local tools** (like `analyze_site` is local — run in
`loop.py`, NOT forwarded to Spring, no Java executor needed). Example schema:

```
present_options → {
  title: string,
  options: [{ key, label, why (one clause), budget_hint?, recommended: bool }]
}
```

- The model **calling the tool = the model self-referencing the pattern.**
- The tool's `input_schema` **IS** the pattern's data contract — one source of
  truth in `app/tools/schemas.py`, mirrored to a TS type (same CI diff-check
  discipline the 5 tools already have).
- The **persona teaches the vocabulary** (prompt), the **schema enforces it**.
  That is the "self-reference": Meera composes against her own pattern library,
  which is versioned by `PROMPT_VERSION` so it evolves safely.

### Why NOT a separate classifier model

A second model (Haiku/Gemini) reading Meera's reply to guess the pattern adds
+1 call/turn (latency + cost), a new failure point, and can **disagree with what
the first model actually meant.** Never make a second model guess what the first
model already knows — have the first model tag its own output. (This is the
single most important architectural call in this proposal.)

## Guardrails (so it doesn't over-structure everything)

- `say` stays the hard default. Patterns are for genuine choice/plan/confirm
  moments only — the persona must say "use a pattern only when there's a real
  choice or ordered plan; otherwise just say one sentence."
- **Voice contract holds:** the card CONTENT is never read aloud — only the one
  short spoken line is. So structure lives on the canvas; voice stays snappy.
- Closed vocabulary + schema validation: an unknown pattern is dropped, never
  rendered raw (same as the unknown-tool reject in `loop.py`).

## Rollout — one pattern first, prove it end-to-end

1. Ship **`options`** only (the rice-cooker case). Validate schema → render →
   tap → next turn works through the whole stack.
2. Then add `plan`, then `confirm`. Each is a schema + a card + a persona line.
3. Do NOT build all patterns at once.

## Ownership & effort (display-only ⇒ no Spring/Java)

| Piece | Owner | Effort |
|---|---|---|
| `present_options` schema + local routing in `loop.py` | Vikram | S–M |
| persona rule: when to use it, keep spoken line short + version bump | Ash/Vikram | S |
| TS type + `OptionsCards` canvas component + ToolResultRenderer case | Ananya | M |
| QA (schema/render/tap/cancel) | Kavya | S |
| build + local verify | Meera | S |

## Data flywheel (ASH #5)

Log `pattern emitted` + `which option the brand tapped` per turn. That's a clean
signal to (a) tune Meera's `recommended` pick, (b) build a golden eval set of
"question → right pattern + right recommendation", (c) later fine-tune if volume
justifies it.

## Verdict

**Recommend BUILD, phased.** It's the correct, lowest-risk way to deliver the
tappable-options format AND establish a reusable pattern framework — an extension
of the proven tool→canvas pipeline, not a new model. Start with `options`.
