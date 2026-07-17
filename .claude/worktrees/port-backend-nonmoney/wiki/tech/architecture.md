# Architecture Decisions (CTO — Priya)

> This file holds durable, LOCKED architecture rulings. Working members implement to these;
> changes require CTO sign-off. Companion to `wiki/decisions/` (dated ADRs).

---

## ADR-2026-07-12 — P2-11: Fake-follower "comment quality" + QualityScore audience-match

**Status:** LOCKED · **Owner:** Priya (CTO) · **Implementer:** Vikram · **Reviewers:** Kavya → Meera
**Context files (read before implementing):**
- `service/scoring/FakeFollowerDetectionService.java` (Signal 4 placeholder, line ~102–104)
- `service/scoring/QualityScoreService.java` (`audienceMatch` hardcoded 50, line ~62–64)
- `job/ScoreCalculationJob.java` (the only real caller; brand-agnostic daily job)
- `domain/entity/AudienceDemographics.java` + `repository/AudienceDemographicsRepository.java` + `V25__audience_demographics.sql`
- `service/scoring/RateEstimationService.java` (consumes `qualityResult.overall()` only)

### Decision 1 — Fake-follower detection stays **Java heuristics**. No Python NLP. No new dependency.

**Why NLP-on-comments is rejected (data-grounded):** the spec's "Signal 4: Comment Quality" wants NLP
over follower **comment text**. That text does not exist anywhere in the pipeline. `MediaMetric` stores
`comments` as a `Long` **count**, never the comment strings; the only free text in the metrics layer is
`caption` (the creator's own post text), already consumed by `BrandSafetyScoreService`. Fetching real
follower comments would require a new Graph API comments-edge fetch, a new table, a new Meta permission
scope, workspace-isolation plumbing, and — decisively — holding **third-party users' PII** (comments are
not the creator's own words). That is a multi-wave feature with a privacy liability, not a scoring tweak.
We will not hold that PII.

**What we implement instead — a real, computable signal from data we already have:**
**comment-to-like ratio anomaly** (replaces the Signal-4 placeholder; the service is then no longer a
partial stub). Computed over `recentMedia` (already passed in):
- Sum `likes` (L) and `comments` (Cm) across posts where both are non-null. Ratio = `Cm / L`.
- If `L <= 1000` (below floor) or no usable posts → **signal contributes 0, no reason added** (never fabricate).
- `ratio < 0.001` (near-zero comments against real likes → purchased-likes tell): **+20**,
  reason `"Near-zero comment-to-like ratio (X.XXX%) — possible purchased likes"`.
- `ratio > 0.5` (comment-pod / bot-comment tell): **+15**,
  reason `"Abnormally high comment-to-like ratio (X.XX) — possible engagement pod"`.
- Put `commentToLikeRatio` in the `debug` map. Existing `score = min(score, 100)` cap unchanged.

No signature change to `analyze(...)`. No new dependency. Stays a pure function.

### Decision 2 — `audienceMatch` is computed from the creator's **real `AudienceDemographics` snapshot**, brand-agnostically. Null (never 50) when absent. Composite re-normalizes.

**Framing:** `ScoreCalculationJob` is **brand-agnostic** — one score per creator, no brand/campaign in
scope. So `audienceMatch` here is NOT "match to brand X"; it is the intrinsic **definition/targetability**
of the audience — exactly what the V22 column comment already says ("how well audience matches *typical*
brand targets"). Per-campaign brand-specific matching is a separate, brand-facing feature and is explicitly
**out of scope** for P2-11 (no brand-target input flows into this job).

**Data source (already pipelined):** `AudienceDemographics` (V25), refreshed weekly by
`AudienceDemographicsJob`, read via `findFirstByCreatorProfileIdOrderByTimeDesc`. Dimensions are
`{bucket: count}` JSON maps: `age_gender_breakdown` (e.g. `{"F.25-34": 400, ...}`) and
`country_breakdown` (e.g. `{"US": 1200, ...}`).

**Algorithm (LOCKED — do not re-derive):** define `topTwoShare(map)` = (sum of the two largest bucket
counts) / (sum of all counts). A well-defined, targetable audience is concentrated in a few identifiable
cohorts; a diffuse smear (or bot noise) is not.
- `audienceMatch = 100 * ( wAG*topTwoShare(ageGender) + wGeo*topTwoShare(country) )`
  with base weights `wAG = 0.6`, `wGeo = 0.4`.
- **Present-dimension renormalization:** if only one of the two dimension maps is non-empty, use it alone
  at weight 1.0 (drop the missing one and renormalize).
- Single-bucket dimension → its `topTwoShare` = 1.0 (correct: fully concentrated).
- Result rounded HALF_UP to scale 2, clamped [0,100].

**Graceful degradation (matches the LOCKED BrandSafety "null = unscored, never a sentinel" discipline):**
if the creator has **no demographics snapshot**, or the snapshot's age-gender AND country maps are both
empty → `audienceMatch = null`. Never 50, never 0.

**Composite must re-normalize (this is the load-bearing change):** `QualityScoreResult.audienceMatch`
becomes **nullable**. `overall` = weighted sum of engagement(.40), consistency(.25), frequency(.20),
audienceMatch(.15):
- audienceMatch **present** → weights unchanged.
- audienceMatch **null** → redistribute its .15 proportionally across the other three: divide each of the
  three present weights by 0.85 (so they sum to 1.0). A creator lacking demographics is **not** silently
  docked ~7.5 points on `overall`.
- `overall` stays **non-null** in every case → `RateEstimationService` (reads only `overall()`) is unaffected.

### Wiring (concrete — for Vikram, minimal blast radius)

1. `QualityScoreService.calculate` gains a 3rd param `Optional<AudienceDemographics> latestDemographics`.
   Stays a **pure function** (no repo injection) — caller fetches, consistent with every scoring service.
2. `QualityScoreResult.audienceMatch` → nullable `BigDecimal`. Composite re-normalization as above.
3. `FakeFollowerDetectionService.analyze` → implement the comment-to-like signal in place of the Signal-4
   comment. No signature change.
4. `ScoreCalculationJob`: inject `AudienceDemographicsRepository`; in `scoreOne` fetch
   `findFirstByCreatorProfileIdOrderByTimeDesc(creatorProfileId)` and pass to `calculate(...)`. The existing
   `.audienceMatchScore(qualityResult.audienceMatch())` already maps null → null column (nullable, no change).
5. `common/JsonLists`: add `public static Map<String,Long> longMapFromJson(String json)`
   (`TypeReference<Map<String,Long>>`, returns **empty map** on null/blank/parse-fail — same defensive
   contract as `stringListFromJson`). `QualityScoreService` uses it to parse the breakdown JSON.
6. **Tests (MANDATORY — constructor/signature drift is exactly what re-broke P0-1):** update
   `QualityScoreServiceTest`, `FakeFollowerDetectionServiceTest`, and `ScoreCalculationJobTest` (its
   constructor gains the repository arg). New cases: comment-to-like low/high/floor paths; audienceMatch
   from a real breakdown; **null-demographics → null audienceMatch + re-normalized overall**; one-dimension-
   present path.

### Dependencies
**None added.** No Python NLP, no new Maven artifact. Logged accordingly in `approved-deps.md` (no-op).

### Sign-off gate
Mark P2-11 ✅ only after **Meera's real `mvn -o test`** passes with the updated tests. Otherwise leave 🟡/🔵
with the accurate remaining item.
