# Ruling: how trend headlines are screened before creators see them

> **Decision by:** Priya (CTO), technical design. Swapnil ruled the business direction on
> 2026-09-18: the Creator Co-pilot goes live today, switched ON.
> **Date:** 2026-09-18
> **Status:** LOCKED
> **Closes the design question behind:** F-0853, F-0855, F-0856 (Kabir, 2026-09-17)
> **Unblocks:** L10 (trend-pull job: F-0774, F-0778, F-0781, F-0823) and switching the Co-pilot on

---

## The question

The only screen today is a hand-written word list (`CreatorNudgeService.UnsafeHeadlineTopic`).
Kabir showed that a word list is always one headline behind:

- word forms: terrorists, bombed, crimes, funerals, verdicts, "self-harming";
- hashtags and joined words: #DelhiRiots, #GangRape;
- Indian news vocabulary: stone pelting, FIR lodged, Delhi HC, succumbs, stampede;
- Hinglish and Devanagari: hatya, balatkar, atmahatya;
- look-alike characters: Hangul filler, small capitals, dotless ı, "murd3r".

Adding words fixes the examples, not the class.

## Decision: screen each headline ONCE, when it is pulled in, with two independent checks

A trend headline is stored in `trends` only if it passes BOTH checks. Anything that fails either
check, or cannot be checked, is never stored and never shown.

1. **Deterministic filter (fast, free, always on).** The existing `UnsafeHeadlineTopic` filter,
   widened:
   - generated inflections for single words as well as phrases (s, es, ies, ed, ing, er, ers);
   - hashtags split on case and joined-word boundaries before matching;
   - an Indian-English news term set (Kabir's list in F-0855 is the starting corpus);
   - a Hindi and Hinglish term set, in Latin script and Devanagari (F-0856);
   - confusable folding driven by the Unicode confusables data, plus digit and symbol
     substitutions (3→e, 0→o, @→a, 1→i/l).
2. **AI classification (catches what no list can).** Each headline that passes step 1 is sent to
   the existing `POST /internal/brand-safety` route (`influora-ai/app/routes/brand_safety.py`),
   which already returns a GARM classification per item. Any GARM category covering death,
   injury, crime, terrorism, violence, hate speech, self-harm, or courts and legal proceedings is
   a reject.

**Fail closed.** If the classifier is down, times out, or returns anything unparseable, the
headline is NOT stored. A day with fewer trends is acceptable; a murder headline turned into a
content idea is not.

**Keep the output check.** `getSuggestion` still checks the theme, the headline, and the AI's
headline and content idea before saving (F-0838, F-0854). Screening at ingest is the main gate,
and the output check is the backstop.

## Why at ingest, not per suggestion

- **Cost:** one classification per trend, shared by every creator, instead of one per suggestion.
- **Speed:** creators never wait on a safety call.
- **One place to audit:** the `trends` table holds only headlines that passed; a rejected
  headline is logged by trend id and category, never by its text (same rule as F-0786).

## Also required in the same build

- **F-0828:** the suggestion prompt (`influora-ai/app/prompt/creator_suggestion.py`) tells the
  model never to build an idea on a death, crime, riot, disaster or court case, even if one gets
  through.
- **F-0823:** `ThemeMatchService.parseThemeJson` accepts only themes in the known taxonomy.
- **F-0778:** trends expire softly (an `expires_at`), no `ON DELETE CASCADE`.
- **F-0781:** each pull logs which sources ran, which were skipped (missing key), and how many
  headlines each check rejected.

## Amendment 2026-09-19 (Priya): accepted word-filter gaps and confusables

After two review rounds, Kabir still found 6 unsafe headlines the deterministic filter lets through:
"Man shot at outside mall", "Body found in suitcase", "maar diya gaya", "goli maar di", "क़ातिल",
"छात्रा से छेड़छाड़". **Accepted for launch.** Every headline also goes through the GARM
classification at ingest, and failing that check or being unable to check means the headline is
not stored. So a word-list miss is caught at the second check, not shown to a creator. The tests
list these 6 as known word-filter gaps, and each new term set must shrink that list.

**Over-blocking is not accepted.** Everyday creator vocabulary must stay quotable. That includes
fitness and skincare terms such as dead skin, dead ends, deadlift, dead hang, dead bug and
Deadpool. It carries the same severity as a bypass, because a filter that blocks a fitness
creator's own vocabulary makes the Co-pilot useless for that niche.

**Confusables:** the curated fold table is accepted in place of the full Unicode confusables data
if it covers every homoglyph probe used in review. No new dependency is added for this.

## done_when for the build

- The deterministic filter blocks every string in Kabir's F-0853/F-0855/F-0856 probe lists.
  They become a table-driven test, so a regression fails by name.
- A benign control set still passes. It includes at least: Tennis courts open, Flash mobs dance,
  Killer ab workout, issues with skincare, Diwali fashion haul, and budget travel in Goa.
- The ingest job stores nothing when the classifier call fails, and a test proves it.
- A headline that passes the word filter but that the classifier flags is not stored, and a test
  proves it.
- Every test goes red with its guard removed and green with it restored, run from `git archive`
  of the commit.
