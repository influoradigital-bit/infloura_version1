# Code Annotation Standard

> **Arjun Kapoor, Engineering Lead** — 2026-09-12
> Applies to every agent and every human who writes code in this repo: Vikram, Ananya, Meera, Dev.
> **Status:** DRAFT — proposed. Priya may ratify into `wiki/tech/` to make it binding.

This codifies a convention **already in use** in this codebase. It is not new process. Examples
already in the tree: `[SEC: MF-1 follow-up, 2026-07-21]`, `F-0392 (Track E) — …`,
`CR-99/F-0113 fix: …`, `F-0166 follow-up (Priya review): …`, `(Kabir 2.1)`.

---

## 1. The format

```java
// <TICKET> [<author> · <YYYY-MM-DD>] — <WHY: what was wrong, or why this is not the obvious code>
//   Source: <path/to/authority.md §section>
```

Real example, written against the code it describes:

```java
// F-0793 [vikram · 2026-09-12] — engagementRate was null for every creator because nothing ever
//   computed CreatorMetric.avgEngagementRate, so the UI showed "not available yet" permanently
//   rather than temporarily. Formula is reach-based per Swapnil's ruling; do not switch it
//   silently — brands compare this number against other platforms.
//   Source: wiki/decisions/2026-09-12-engagement-rate-formula.md
BigDecimal rate = totalInteractions.divide(reach, 4, RoundingMode.HALF_UP);
```

### Field rules

| Field | Required | Rule |
|---|---|---|
| `<TICKET>` | **yes** | `F-####`, `CR-###`, `BR-##`, or `[SEC: …]` for security fixes. This is the join key to the ledger — without it the comment cannot be traced to the defect it closes. |
| `<author>` | yes | agent or human name, lowercase |
| `<date>` | yes | `YYYY-MM-DD`, the date the code was written |
| **WHY** | **yes** | the part that actually pays. See §2. |
| `Source:` | yes when one exists | the spec, ruling, audit or decision doc that authorised the change. Repo-relative path. Omit only when there genuinely isn't one. |

---

## 2. Write WHY, never WHAT

The diff already shows what changed. `git blame` already shows who and when. **The only thing a
comment can carry that no tool can reconstruct is why** — and specifically, why the obvious code
would have been wrong.

❌ **Useless**
```java
// vikram 2026-09-12 — added null check
if (rate != null) { … }
```

✅ **Earns its place**
```java
// BR-18 [vikram · 2026-09-12] — must stay null, never coerce to ZERO. A fabricated 0 renders as
//   "0% — Excellent authenticity" in the brand UI and silently passes a minScore filter, so an
//   unscored creator looks like a scored-bad one.
if (rate != null) { … }
```

The second one stops someone "simplifying" it back into a live bug. That is the whole job.

### What deserves an annotation

- Anything **defensive** — a guard whose reason is not visible locally
- Anything **counter-intuitive** — where the obvious refactor would break production
- Anything **load-bearing but silent** — ordering, transaction boundaries, timezone choices
- Anything closing a **ledger ticket**
- Anything a **ruling** decided (Swapnil, Priya, Kabir)

### What does not

Ordinary code that reads the way it works. Do not annotate every line — a file where everything is
flagged is a file where nothing is.

---

## 3. Worked patterns from this repo

**Branch order is production data**
```java
// F-0782 [vikram · 2026-09-12] — branch ORDER is load-bearing. EDUCATIONAL keywords are tested
//   BEFORE the tmdb branch, so a film titled with "wellness" gets a 30-day window, not 3.
//   Reordering this for readability changes every future row's expires_at.
//   Source: .proof-os/tasks/T-COPILOT-ON-0910/job-design.md step 10
```

**A guard that looks removable**
```java
// F-0778 [vikram · 2026-09-12] — soft expiry only. creator_nudge_log.trend_id FKs to trends.id
//   with no ON DELETE clause (RESTRICT), so a bulk DELETE fails errno 1451 once suggestions
//   exist and rolls back entirely. Do NOT "fix" with ON DELETE CASCADE — the generated-column
//   unique key is the per-creator/day cap backstop and cascading would allow a re-nudge.
```

**A comment that must come down on a date**
```java
// [SEC: arjun · 2026-09-12] — TRUE only while Meta Advanced Access holds. If the scope lapses
//   this claim becomes false and must be removed the same day.
//   Source: wiki/tech/profile-data-model.md §1
```

---

## 4. Commit messages carry the same spine

```
fix(analytics): compute the engagement rate we already had the inputs for (F-0793)

engagementRate was null for every creator because nothing computed
CreatorMetric.avgEngagementRate. Inputs were already in media_metrics.

Source: wiki/decisions/2026-09-12-engagement-rate-formula.md
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

Ticket ID in the subject. Why in the body. Source at the foot.

---

## 5. The honest trade-off — read before adopting

**`<author>` and `<date>` duplicate `git blame`**, and unlike git blame they go stale: move a
method to another class and the comment now records who wrote it *somewhere else*, on a date that
no longer means anything. Git blame stays correct through moves; the comment does not.

They are included here because they were explicitly asked for, and they do earn their place in one
situation — when the code moves between repos or gets pasted into a doc, where blame does not
follow. **Do not treat a stale author/date as a defect. Treat a missing or wrong WHY as one.**

The ranking, if you only have energy for part of it:

1. **WHY** — irreplaceable, no tool reconstructs it
2. **Ticket ID** — the join key to the ledger
3. **Source** — where the authority came from
4. Author / date — nice to have, `git blame` already knows

**This repo has a documented history of comments being wrong in both directions.** A comment
asserting "no backend endpoint exists" (F-0795) was true when written and is false today, and it
froze a page into rendering empty boxes for data the API already serves. So:

> **A comment that asserts a fact about another part of the system must name its source, and is
> only as trustworthy as the last time someone checked it.** Prefer asserting *why this code is
> shaped this way* over asserting *what some other file does*.

---

## 6. Enforcement

Not gated in CI today, deliberately — a regex gate on comment shape would fire on the examples in
this very document, which is a failure mode this repo has already hit twice.

Enforced at review instead: **Kavya rejects a defensive or counter-intuitive change that carries no
WHY.** If it needed a ticket, it needs the ticket ID.
