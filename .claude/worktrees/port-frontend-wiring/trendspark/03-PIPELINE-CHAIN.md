# Trend-Spark AI — Pipeline Chain & Error Detection

**Goal:** each stage checks the one before it, so a bad output is caught early — not at the user.
**Final call:** Ash (AI correctness) + Swapnil (business). Arjun signs non-strategic stages.
**Persona name:** Meera (placeholder — one config value, rename later).

---

## The chain (each arrow = a handoff WITH a check)

```
[A] Dev — trend pull (n8n, 6 AM)
       │  CHECK: did every source return data? valid JSON? themes tagged? not empty?
       │  FAIL → log to errors, retry w/ backoff, skip dead source, alert Dev
       ▼
[B] Vikram — content-gap check
       │  CHECK: brand profile exists? last-posted date read? own-content library reachable?
       │  FAIL → default to "help with own content", do NOT push Snapsby
       ▼
[C] Vikram — catalog match (rules)
       │  CHECK: theme overlap score computed? ≥ threshold? at least 1 Snapsby video found?
       │  FAIL / low score → STAY SILENT (no nudge). This is correct behavior, not an error.
       ▼
[D] Ash — AI phrasing (one cheap call)
       │  CHECK: JSON parsed? message non-empty? no invented price/video-id? length sane?
       │  FAIL → use fallback templated message. Never show raw error to user.
       ▼
[E] Ananya — render soft nudge card (on-open)
       │  CHECK: card dismissible? "use own content" path present? preview link valid?
       │  FAIL → hide card entirely (fail closed — better silent than broken)
       ▼
[F] nudge_log written (Vikram)
       │  CHECK: nudge + shown + clicked + purchased captured?
       │  This is the flywheel — powers eval + future AI-scoring
       ▼
[G] user acts → into Snapsby preview → purchase
```

**Design principle: fail closed.** At every stage, if something is wrong, the safe default is
**say nothing** rather than show a broken or spammy nudge. Silence never hurts the brand.

---

## Build-time quality chain (before it ships)

```
Code done (Vikram/Ananya/Ash)
      ▼
[1] Kavya — QA: standards, spec compliance, obvious bugs
      │  FAIL → back to developer via Arjun
      ▼
[2] Meera(verifier) — local run: build, test, curl trend feed, trigger a test nudge end-to-end
      │  FAIL → back to developer via Arjun
      ▼
[3] Kabir — security: keys in .env? prompt-injection on brand data? no PII leak in logs?
      │  Critical/High → BLOCK until fixed + re-tested
      ▼
[4] Ash — AI review: prompt quality, model/cost, output validation, eval passes on golden set
      │  P0 → BLOCK. P1 → fix this sprint. P2 → backlog.
      ▼
[5] Swapnil — business sign-off: does it feel right, on-brand, non-spammy?
      ▼
   SHIP  ✅
```

---

## Where "is this logic correct?" gets decided

| Question | Decider |
|----------|---------|
| Is the trend→brand→video match logic sound? | **Ash** (AI/logic) + Priya (architecture) |
| Is the AI's message safe + hallucination-free? | **Ash** |
| Is the code correct + secure? | Kavya → Meera → Kabir |
| Does it make business sense / feel right? | **Swapnil** (final) |
| Can Arjun ship it alone? | Only non-strategic stages; anything touching cost, tone, or brand → Swapnil |

---

## Error-detection summary (what catches what)

- **Empty/garbage trend data** → caught at [A], source skipped, retried
- **Spammy nudge to active brand** → caught at [B], gap-check blocks it
- **Irrelevant match** → caught at [C], low score = silence
- **AI hallucination / bad output** → caught at [D], fallback message
- **Broken UI** → caught at [E], card hidden
- **Security hole** → caught at [3] Kabir, blocks ship
- **Cost blowout** → caught by Rohan's cap + alert

Every failure has a defined catch and a safe default. Nothing broken reaches the brand.

---

## Approval gates (the "final call")

1. **Logic/AI correct?** → Ash
2. **Secure?** → Kabir
3. **Business go?** → Swapnil
4. **Cost sane?** → Rohan

All four green = Arjun releases to production via n8n.
