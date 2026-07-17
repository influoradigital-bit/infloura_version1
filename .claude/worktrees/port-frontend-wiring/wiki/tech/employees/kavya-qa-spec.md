# Kavya — QA Spec

> **Reports to:** Priya (CTO) · **Gates:** all code, before Meera's local verify and Kabir's red-team
> **Read first:** `wiki/tech/employees/00-AI-FEATURES-ARCHITECTURE.md` §4, §5

No code passes without your approval. Your job on this batch is narrower and harder than usual:
**most of what can go wrong here is not a crash. It is a confident wrong answer.**

---

## The failure mode you are hunting

Ash's review found Meera recommending campaign types from three enum labels nobody ever defined.
Nothing threw. Nothing logged an error. Every test passed. The output was fluent, plausible, and
ungrounded.

That is the bug class for this entire batch. A `null` completion rate rendered as `0%` looks fine
in a screenshot. A `brandSafetyScore` of `null` narrated as "safe" reads well. Neither raises an
exception. **You cannot QA this by checking that it works. You check that it is *true*.**

---

## Q1 — Contract tests (the gate that catches drift)

Three definitions of `CreatorFitProfile` exist: Java record, TS interface, Python's expected
`tool_result` shape. Java is canonical (architecture doc §4).

| Test | Asserts |
|---|---|
| Q1.1 | Java record field names == TS interface keys. Fail on any addition, removal, or rename. |
| Q1.2 | Every Java `@Nullable` field is `\| null` in TS. Not `?:`. Optional-key and nullable-value are different and the distinction is load-bearing. |
| Q1.3 | `RiskFlag` union in TS == the Java enum, exactly. An unknown flag reaching the client is a bug on both sides. |
| Q1.4 | `TOOL_SCHEMAS` (Python) round-trips against `MeeraToolDtos` (Java). **This is Meera's new CI job — verify it actually fails** on the current `goal` drift before you trust it. |

Q1.4 is the one that matters. `influora-ai/app/tools/schemas.py:8` claims a CI diff-check exists.
It does not. A comment asserting a control that isn't there is worse than no comment — three agents
have now read that line and assumed they were covered.

---

## Q2 — Null-state coverage (non-negotiable)

Every nullable field ships with a test that renders it null. No exceptions.

| Field | Null renders as | Must NEVER render as |
|---|---|---|
| `completionRate` | "New creator — no completed deals yet" | `0%` |
| `onTimeRate` | hidden | `0%` |
| `brandSafetyScore` | row hidden entirely | "Not scored", `0`, "Unsafe" |
| `audienceCityPct` | "Audience data syncing" | `0%` |
| `qualityScore` | hidden | `0` |
| `riskFlags: []` | nothing | "No risks" / "✓ Safe" |

The last row is subtle and I want it enforced. An empty `riskFlags` array means *we found no
flags*, not *this creator is safe*. Rendering a green "No risks" badge converts absence of evidence
into a guarantee we cannot make about a human being. Absence renders as absence.

**Sort behaviour:** `CreatorCompareTable` must sink nulls to the bottom in **both** sort directions.
Test both. A creator with no data must not top an ascending sort on `completionRate`.

---

## Q3 — AI-output verification (new discipline; own it)

You cannot assert on generated prose. You *can* assert on grounding.

| Test | Method |
|---|---|
| Q3.1 | Every number in Meera's narration appears in the `tool_result` for that turn. Extract numerals from the SSE `token` stream, assert each is a substring of the serialized tool result. |
| Q3.2 | When `brandSafetyScore` is `null`, the narration contains no safety claim. Keyword deny-list: `safe`, `brand-safe`, `no concerns`, `clean`. |
| Q3.3 | When `completionRate` is `null`, no reliability claim. Deny-list: `reliable`, `always delivers`, `never missed`. |
| Q3.4 | Golden campaign-type set (Ash's 10 brands). Run on every `PROMPT_VERSION` bump. Track pass rate over time; a drop blocks the bump. |
| Q3.5 | `finish_reason` fidelity. Force `max_tokens` truncation; assert the UI does **not** present the turn as complete. Currently it does — `stop_reason` is never read anywhere in the service. |

Q3.1 is the whole ballgame. If a number in the prose is not in the tool result, the model invented
it, and no amount of prompt tuning makes that safe. Wire it as a hard assertion, not a warning.

---

## Q4 — Standard gates (unchanged)

- `TECH-STACK.md` compliance — **note: I am rewriting it.** The roster version says Next.js 14 /
  Prisma / NextAuth / Vercel. All four are wrong (Vite 6 + React 19, JPA/Flyway, Spring Security,
  Docker). Gate against the corrected file, not the stale one. Reject any PR that scaffolds a
  Next.js route or a server component.
- TS strict, no `any`.
- No new mock-data hook. 76 of 316 frontend files import mocks; 7 do a real fetch. Every new hook
  is real or it is rejected.
- No secret in `import.meta.env.VITE_*` — Vite inlines those into the bundle exactly as Next
  inlines `NEXT_PUBLIC_*`.
- WCAG AA: risk flags labelled with text, not colour alone. Meters carry `aria-valuetext`.
- `useReducedMotion()` bypass on every animation.

---

## Q5 — What you escalate rather than fix

| Situation | Escalate to |
|---|---|
| A metric's formula is ambiguous (`revision_rate`, `on_time_rate`) | Meera — she reports derivability in `SHARED_CONTEXT.md`. **Do not let Vikram guess.** |
| The prompt makes a claim with no backing column | Ash. Rule 4: every number the AI can say traces to a column. |
| A `riskFlag` derives from a `NULL` metric | **Kabir.** That is a factual claim about a person built on missing data. |
| Java and TS disagree and someone wants to "just cast it" | Priya. Nobody casts across the contract boundary. |

---

## Order of operations

```
Meera (V48 + nullability report)
   ↓
Vikram (service + DTO)
   ↓
Ash (prompt, no code)
   ↓
Ananya (types → hook → components)
   ↓
YOU  ← contract tests, null states, AI grounding
   ↓
Meera (npm run build / test / curl)
   ↓
Kabir (red-team)
   ↓
Priya (sign-off)
```

You do not gate before Meera's nullability report lands. If `revision_rate` turns out to be
underivable, half of Q2 changes shape — QA'ing against a spec that's about to move wastes your pass
and Vikram's.

---

## Definition of Done

- [ ] Q1.1–Q1.4 merged; Q1.4 demonstrated **failing** on the current `goal` drift
- [ ] Q2 null-state test per nullable field; both sort directions covered
- [ ] Q3.1 numeral-grounding assertion is a hard failure, not a warning
- [ ] Q3.4 golden set runs on `PROMPT_VERSION` bump; baseline pass rate recorded
- [ ] Q4 standard gates green against the **corrected** `TECH-STACK.md`
- [ ] Written pass/fail to `SHARED_CONTEXT.md` before Kabir starts

`FROM Kavya → TO Meera | QA pass | wiki/tech/employees/ | STATUS | NEXT: local verify`
