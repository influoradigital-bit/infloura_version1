# Creator Profile + AI Co-pilot — Phased Work Plan

> **Arjun Kapoor, Engineering Lead** — 2026-09-12
> Closes the 20 open tickets from the Co-pilot enablement run, the profile-metrics audit and the
> brand-decision-surface trace. See also `wiki/processes/code-annotation-standard.md`.
> Source specs: `wiki/tech/profile-data-model.md` (Priya, DRAFT), `.proof-os/tasks/T-CEO-AUDIT-0912/priya-audit.md`,
> `.proof-os/tasks/T-COPILOT-ON-0910/job-design.md`.

---

## STATUS

**Enablement track is STOPPED by Swapnil.** The Co-pilot must not be switched on. Ruling on record:
build Option A staged — start narrow, but **build on a creator category field from day one** so the
matching layer is not rewritten twice. Two items are non-negotiable before any creator sees output:
**F-0786** (content filter) and **F-0784** (word-boundary matching).

### ✅ DONE, COMMITTED — 2026-09-15
- `TrendIngestProperties.java` + `CreatorNudgeServiceTest.java` (34 tests) — commit `85861a8`,
  scoped to exactly those two files. Fresh re-verify same day: 39/39 tests green.
- **F-0776** score-threshold mutation reverted (`<=` → `<`), falsified 13 fail → 0
- **F-0777** all-or-nothing config gate fixed
- The three rulings below — **F-0793/F-0792's Task 0.2 blocker, Phase 4's Task 0.3 blocker, and
  Task 2.5b are all unblocked.**

### ⚠️ Also landed, but NOT cleanly — F-0817
The trend-source config wiring (`application.yml`, 4 compose files, `env.example`,
`@EnableConfigurationProperties`) is safe on disk but got silently swept into an unrelated commit
(`864971a`, about the signup OTP gate) two days before this update — its message never mentions
trend-ingest. Content intact; history attribution lost. Ledgered so the next person grepping
`git log` for "why does this config exist" isn't left guessing.

### 🔴 OPEN (ledger status) — 23 tickets, 3 already fixed in code
F-0774 · **F-0775 fixed** · **F-0776 fixed** · **F-0777 fixed** · F-0778 · F-0781 · F-0782 ·
F-0783 · F-0784 · F-0785 · F-0786 · F-0787 · F-0792 · F-0793 · F-0794 · F-0795 · F-0796 · F-0797 ·
F-0798 · F-0817
(+ F-0789, F-0790 [half-closed by the 2026-09-15 commit above, ledger still open pending
promotion], F-0791 from the other lane, not in this plan)
> All 23 still read `open` in the ledger — a code fix isn't a ledger close; that needs a promoted
> gate. The three marked **fixed** are fixed and committed, not yet promoted.

---

## PIPELINE (every task follows this)

```
Priya (arch, if new surface) → Vikram/Ananya (build) → Kavya (QA gate)
  → Meera (build + test verify) → Kabir (OWASP, blocking) → Priya (sign-off) → Rohan (cost log)
```
Kabir's Critical/High findings **block** the phase until fixed and re-tested.

---

## PHASE 0 — UNBLOCK (nothing else starts until this closes)

### Task 0.1: Commit the working tree
**Owner:** Meera · **Blocked by:** nothing · **Estimate:** 30 min
Branch off `main`, commit the DONE list above as one self-contained change. It is green and
security-reviewed. Leaving it uncommitted risks the documented clobber-by-concurrent-session class.
**Done when:** branch pushed, `git status` clean for those 9 files, F-0790 no longer applies to them.

### Task 0.2: Swapnil ruling — engagement-rate formula ✅ RULED 2026-09-15
**Owner:** Swapnil (Priya advises) · **Blocks:** Task 2.1, therefore all of Phase 2
**Reach-based. LOCKED.** `wiki/decisions/2026-09-15-engagement-rate-formula.md`.

### Task 0.3: Swapnil ruling — is creator category declared or derived? ✅ RULED 2026-09-15
**Owner:** Swapnil (Priya advises) · **Blocks:** Phase 4
**Declared at onboarding, a real field. LOCKED.** `wiki/decisions/2026-09-15-creator-category-declared.md`.

### Task 0.4: Priya — resolve remaining spec decisions
**Owner:** Priya · **Blocked by:** nothing · **Estimate:** 1 h
`wiki/tech/profile-data-model.md` §8.2–§8.5: per-post detail vs aggregates to brands;
does `discoverable=false` hide metrics; `media_metrics` retention; does a creator see their own scores.
**Done when:** §8 answered and the doc moves DRAFT → LOCKED.

---

## PHASE 1 — SAFETY NON-NEGOTIABLES (before any creator sees output)

### Task 1.1: Content filter on the fallback copy — F-0786
**Owner:** Vikram · **Blocked by:** nothing · **Estimate:** 3-4 h · **Gate: Kabir, blocking**
`CreatorNudgeService.templatedFallback` formats a raw third-party news headline straight into
creator-facing copy with no filter. This path runs precisely when influora-ai is down.
**Done when:** a headline containing death/crime/communal/legal terms never reaches creator copy;
the fallback degrades to generic wording instead. Unit test per rejected category.

### Task 1.2: Word-boundary matching — F-0784
**Owner:** Vikram · **Blocked by:** nothing · **Estimate:** 2-3 h
`ThemeMatchService.themesForText` uses unanchored `contains`. `onam`→**Sonam**, `holi`→**holiday**,
`eid`→**Heidi**. Fires constantly because the source is entertainment headlines full of names.
**Done when:** negative-fixture test proves `Sonam`, `holiday`, `Heidi` produce NO festive/seasonal
theme, and the same fix lands in the n8n JS tagger so the two do not diverge.
⚠️ The tagger-sync CI check compares the two implementations **to each other** — it will stay green
while both are wrong. Fix both, then re-run `trendspark/n8n/tagger-sync.check.js`.

### Task 1.3: Concurrency 500 on the daily cap — F-0785
**Owner:** Vikram · **Blocked by:** nothing · **Estimate:** 2 h
`catch (DataIntegrityViolationException)` is dead code (assigned `@Id`, no `@Version` → `merge()`,
violation fires at commit outside the try). Two simultaneous opens = one HTTP 500 to a creator.
Also `CREATOR_COPILOT_DAILY_CAP` has no reader — either wire it or delete the advertised knob.
**Done when:** two concurrent `getSuggestion` calls return the same row, no 500. Needs a real-MySQL
integration test; a Mockito test cannot reach a commit-time violation.

---

## PHASE 2 — PROFILE METRICS (highest value, zero Meta dependency)

> Meta is **not** the constraint. `instagram_manage_insights` is live at Advanced Access and we
> already poll `reach, likes, comments, saved, shares, views, total_interactions` every 6 hours.
> This phase is compute + DTO width + UI only. No new scope, no App Review, no new API spend.

### Task 2.1: Compute `avgEngagementRate` — F-0793
**Owner:** Vikram · **Blocked by:** ~~Task 0.2~~ ruled 2026-09-15, reach-based — unblocked · **Estimate:** 4-5 h
Inputs already stored in `media_metrics`. Write the result to `CreatorMetric.avgEngagementRate` so
`PlatformStatsAggregationJob` can publish it. Formula defined **once**, in one class.
**Done when:** a creator with `media_metrics` rows gets a non-null engagement rate end-to-end.
⚠️ Two FE tests currently pin the permanent-null path as correct behaviour — they must be updated,
not deleted.

### Task 2.2: Widen `PlatformStatResponse` — F-0792
**Owner:** Vikram · **Blocked by:** Task 0.4 (§8.2 aggregates-vs-per-post) · **Estimate:** 5-6 h
**Gate: Kabir, blocking** (DTO widening is where PII leaks).
Add `postCount, avgViews, avgLikes, avgComments, avgReach, avgSaves, avgShares,
avgWatchTimeSeconds, lastPostedAt, statsAsOf` per `wiki/tech/profile-data-model.md` §7.1.
`statsAsOf` is mandatory — 48h Meta delay plus daily aggregation means an undated metric is
indistinguishable from a stale one.
⚠️ **`NoBrandFacingCaptionExposureTest` — if a new DTO container class is added it must be
registered in `DTO_CONTAINER_CLASSES` or the caption guardrail silently stops covering it.**
`MediaMetric.caption` may never appear in a brand-facing DTO (LOCKED decision).
⚠️ `MediaMetric.impressions` is a **dead column** — no longer polled. Do not surface it.

### Task 2.3: Render the new metrics
**Owner:** Ananya · **Blocked by:** Task 2.2 · **Estimate:** 4-5 h
Creator profile and brand-view-of-creator. Show `statsAsOf`. Empty states must not promise a
resolution that cannot arrive — the current *"your first idea lands by tomorrow morning"* is the
anti-pattern to avoid.

### Task 2.4: Sync on connect — F-0794
**Owner:** Vikram · **Blocked by:** nothing · **Estimate:** 3 h
Connecting an account triggers no fetch; worst case ~24h of an empty profile at the exact moment
the creator looks. Copy the proven `@Async` connect-triggered pattern from `CreatorCaptionSyncJob`.
**Done when:** connecting populates followers within one minute.

---

## PHASE 2.5 — BRAND DECISION SURFACE (added 2026-09-12)

> Added after tracing what a brand actually sees when CHOOSING a creator. Answer: followers, a
> null engagement rate, nullable scores, a rate range and a star average. Every richer signal is
> gated behind `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId` (:65-75), which
> 403s unless that creator has ALREADY connected to that brand's workspace — i.e. unavailable at
> exactly the moment the buying decision is made. **F-0796.**

### Task 2.5a: Wire the demographics endpoint that already exists — F-0795
**Owner:** Ananya · **Blocked by:** nothing · **Estimate:** 2-3 h
`src/pages/brand-creator-profile.tsx:372-380` hardcodes `ageGroups`/`gender`/`topCities` to null,
citing comments that say no backend endpoint exists. **Those comments are stale.**
`influora-api/src/main/java/com/influora/web/AnalyticsController.java:84-89` serves
`GET /{creatorId}/demographics`, and `:91-100` documents a per-post content-performance route on
the same gate. `AudienceDemographicsJob` populates the table.
**Done when:** the page calls the real endpoint, renders a typed empty state when no snapshot
exists (the endpoint returns an empty shape, never a 404), and the stale comments are deleted.
⚠️ Keep the honest-null discipline. F-0295/F-0260 were fabricated-zero bugs — never render a 0 for
"not measured".

### Task 2.5b: RULING — what does a brand see pre-consent? — F-0796 ✅ RULED 2026-09-15
**Owner:** Swapnil (Priya advises) · **Blocks:** 2.5c, 2.5d · **Estimate:** decision only
**Coarse aggregate bands pre-consent (age spread, gender majority, top-3 cities, no exact
numbers); full detail post-connection. LOCKED.**
`wiki/decisions/2026-09-15-brand-preconsent-visibility.md`.

### Task 2.5c: Widen `PortfolioItemResponse`
**Owner:** Vikram + Ananya · **Blocked by:** ~~2.5b~~ ruled 2026-09-15 — unblocked · **Estimate:** 4-5 h
`/portfolio/{username}` exists, but the record carries no `views`/`likes`/`brand`/`type`, so the
brand-page grid cannot render without inventing data — which is why it is `portfolio: []` with a
`TODO(vikram)` today. The team was right to refuse to invent it; widen the contract instead.

### Task 2.5d: Work-quality metrics
**Owner:** Vikram · **Blocked by:** ~~2.5b~~ ruled 2026-09-15 — unblocked · **Estimate:** 1-1.5 days
Response time, completion rate, on-time delivery, repeat-hire rate. Net-new: `Review` exists but
holds only the rating aggregate, and no operational metrics are computed anywhere.
⚠️ These are trust signals shown to the party paying money. Every one must be computed from
system events, never self-reported — the SR-1 rule in `wiki/tech/profile-data-model.md` §4.4.

### Task 2.5e: Past brands + individual reviews
**Owner:** Vikram · **Blocked by:** ~~2.5b~~ ruled 2026-09-15 — unblocked · **Estimate:** 4-5 h
The page has slots for both; the DTO carries neither, only `avgRating`.
⚠️ Past-brands history is a disclosure question, not just a DTO question — surface it only if 2.5b
permits, and consider whether a creator may hide specific collaborations.

---

## PHASE 3 — TREND SUPPLY (the Co-pilot cannot work without this)

### Task 3.1: `TrendPullJob` — F-0774
**Owner:** Vikram · **Blocked by:** Phase 1 · **Estimate:** 1.5-2 days · **Gate: Kabir, blocking**
Design is written and adversarially reviewed: `.proof-os/tasks/T-COPILOT-ON-0910/job-design.md` (17 steps).
Non-obvious points that plan already proved:
- **Reuse `ThemeMatchService.themesForText`** — do NOT re-port the JS keyword tagger.
- `campaign-rulebook.json` has **zero Java readers**; `deriveCampaignType` is the genuinely missing half.
- Branch order in `deriveCampaignType` is production data — EDUCATIONAL keywords are tested before
  the tmdb branch. Reordering silently changes every future row's `expires_at`.
- Fold config into the **shipped `TrendIngestProperties`**; do not create a second properties class.

### Task 3.2: Soft expiry — F-0778
**Owner:** Vikram · **Blocked by:** Task 3.1 · **Estimate:** 2 h
`creator_nudge_log.trend_id` FKs to `trends.id` with no `ON DELETE` → RESTRICT. A bulk
`DELETE FROM trends` fails errno 1451 once suggestions exist, and rolls back entirely.
**Ship soft expiry** — `findActive` already filters `expiresAt > :now`.
❌ **Do NOT "fix" this with `ON DELETE CASCADE`** — the generated-column unique key on
`creator_nudge_log` is the per-day cap backstop; cascading would let a creator be re-nudged.

### Task 3.3: First-run observability — F-0781
**Owner:** Vikram · **Blocked by:** Task 3.1 · **Estimate:** 1 h
`TREND_INGEST_ENABLED` currently gates nothing and reads as a working switch. The job must log, on
every run, which sources are active vs skipped vs failed, and the row counts.

---

## PHASE 4 — CATEGORY + LANGUAGE (the actual product promise)

> Swapnil: *"It delivers entertainment headlines matched to emotional adjectives. Two of three
> example creators are structurally unservable."* This phase is what makes the pitch true.

### Task 4.1: Creator category field
**Owner:** Vikram + Ananya · **Blocked by:** ~~Task 0.3~~ ruled 2026-09-15, declared — unblocked · **Estimate:** 1 day
Declared at onboarding, stored on `CreatorProfile`, editable. Not derived from captions.

### Task 4.2: Per-category trend sourcing — F-0782
**Owner:** Vikram · **Blocked by:** Task 4.1, Task 3.1 · **Estimate:** 1-1.5 days
NewsAPI is hard-coded `category=entertainment` for everyone, and the normalizer discards category
so the niche-mapping table is unreachable dead code.
**Done when:** a fitness, finance and food creator each receive a category-relevant suggestion on
the same morning. That is the acceptance test, and it is the one that decides the phase.

### Task 4.3: Extend the theme vocabulary
**Owner:** Nisha (vocabulary) + Vikram (wiring) · **Blocked by:** Task 4.1 · **Estimate:** 1 day
The 40-theme vocabulary has **zero** finance, money, business, investment or food themes — a
finance creator can never match anything.

### Task 4.4: Non-Latin script support — F-0783
**Owner:** Vikram · **Blocked by:** Task 4.1 · **Estimate:** 1.5-2 days · **Escalate to Priya**
All 57 keyword keys are Latin script. Devanagari/Tamil captions match nothing → `pending_tagging`
forever, while the page defaults Meera to `hi-IN`. This is most of our market.
**Escalation:** transliteration vs multilingual keyword sets vs a model-based tagger is an
architecture decision, not an implementation choice.
**Interim (ship in Phase 1 if Phase 4 slips):** the empty state must stop promising a next-day
resolution it cannot deliver.

---

## PHASE 5 — ENABLEMENT + LIVE PROOF

### Task 5.1: Fix the live compose — F-0787
**Owner:** Meera · **Blocked by:** nothing · **Estimate:** 1 h
`CREATOR_COPILOT_ENABLED` appears **zero times** in `docker-compose.utho-shared.yml`, which per
`deploy/utho/README.md` §7 is the compose for the live shared box. Explicit-map env blocks mean the
flag is invisible to the container whatever ops sets. `MEERA_CREATOR_ENABLED` is missing too.
⚠️ That file's header claims it differs from `docker-compose.utho.yml` **only** by
`JAVA_TOOL_OPTIONS`. That is false and is what hid this. Correct the header.
**Done when:** a gate diffs the env key sets of the two composes and fails on any key in one but not
the other.

### Task 5.2: Provision the three source keys
**Owner:** Swapnil (approval) → Meera (apply) · **Blocked by:** Phase 3, Phase 4
`NEWSAPI_KEY`, `TMDB_API_KEY`, `YOUTUBE_API_KEY` exist in no environment. Swapnil's ruling: do not
buy until the pipeline can serve more than one vertical.

### Task 5.3: Live E2E
**Owner:** Neha · **Blocked by:** everything above · **Estimate:** half day
Connect a real IG account, wait for tagging, `curl /creator/copilot/suggestion/today`, confirm
`status: ready`, confirm the profile shows real numbers.
**Done when:** `SELECT COUNT(*) FROM trends` is non-zero AND one real creator sees one relevant
suggestion. Only then does the flag go true.

### Task 5.4: Cost log
**Owner:** Rohan · **Blocked by:** Task 5.3
Per-post insights cost one Graph call per post per creator per cycle — never modelled. Meta shows
0% quota usage today but `effective_users_count: 1`, so that number says nothing about real volume.

---

## CRITICAL PATH

```
0.1 commit ──┬─→ 1.1 / 1.2 / 1.3 (safety)  ──→ 3.1 TrendPullJob ──→ 3.2 / 3.3 ──┐
             │                                                                   ├─→ 5.3 live E2E
0.2 ruling ──┴─→ 2.1 engagement ──→ 2.2 DTO ──→ 2.3 UI                          │
0.3 ruling ──────→ 4.1 category ──→ 4.2 / 4.3 / 4.4 ────────────────────────────┘
2.5b ruling ─────→ 2.5a / 2.5c / 2.5d / 2.5e (brand decision surface)
```

**Phase 2 is independent of Phases 3-4 and delivers visible value fastest** — the data is already
on disk. If we need something in front of creators soon, that is the phase to run first.

**Phase 0 gates everything.** Two rulings and one commit.

---

## WHAT THIS PLAN DOES NOT COVER

- F-0789 (account enumeration), F-0790, F-0791 — other lane.
- Estimates are engineering judgement, not measured. No task here has been started.
- The app has never been booted this session; every finding behind this plan is a code read.
- Whether the Snapsby n8n on the shared box already runs this workflow out-of-band is **unknown**
  and would change Phase 3's shape. Someone with SSH should check before 3.1 starts.
