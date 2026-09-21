# Pending Work Register

**Updated 2026-09-12 (end of session)** · branch `feat/meera-creator-phase-e`
Supersedes the earlier version of this file. Sources: the 50-question brand-AI audit
(`wiki/decisions/CTO-AI-BRAND-50A-0912-PART1.md`, `PART2.md`), the P1 defect run
(`wiki/processes/P1-RUN-REPORT-0912.md`), the analyze_site outage investigation, `TASK_INBOX.md`,
and live git state.

---

## ⛔ THE OUTAGE — one `.env` change, and nobody but Swapnil can do it

`analyze_site` has **never** succeeded in production. Live `brand_profiles`: 6 rows, 6 `FAILED`,
zero successes, 2026-08-30 → 2026-09-04, every row with `scraped_at` NULL. No brand's website was
ever fetched.

**Cause, confirmed by Swapnil directly: `ai.influora.internal` is a hostname we never owned.** It
was invented as a placeholder default in `application-prod.yml` and then silently relied on in
production. Var unset on the box → Spring resolved the fiction → NXDOMAIN → instant throw at
`AnalyzeSiteAiClient.java:133`. That is the 1-second `created_at`/`updated_at` delta: connect
timeout is 5s, request timeout 60s, so a sub-second failure proves no SYN was ever sent.

Silent for five weeks because `SecretsStartupValidator` **does** check these and **does** hard-fail
the boot — it only matched `localhost`/`127.0.0.1`/`::1`.

### STEP 1 — `.env` on the box. ✅ RESOLVED 2026-09-13 (F-0804) — but read the correction.

> **CORRECTION (2026-09-17).** An earlier version of this step instructed `http://127.0.0.1:8000`.
> **That value caused ~20 minutes of production downtime.** Commit `66b1860` ships a validator that
> rejects `localhost` and `127.0.0.1`, and so does the previous build, so rollback did not recover it.
> Do not use `127.0.0.1`. Do not use `ai.influora.internal`.

The value that works, applied by Meera to all six `*_AI_BASE_URL` vars in
`/usr/local/App/influora/influora.env`:
```
https://ai.influora.in
```
A real host, `/healthz` 200, not loopback, not `.internal`/`.local` — it satisfies both builds'
validators. Swapnil had stated this was the live value; the earlier advice here argued otherwise.

Still open: prove it with a new signup writing `analysis_status=READY`; re-run the 6 stuck brands via
`POST /admin/brands/{workspaceId}/reanalyze`; and the new build is staged but not deployed.

### STEP 2 — then push the code (`66b1860`, committed, not pushed)
Removes the five fake defaults so an unset var **refuses the boot** instead of resolving to a
fiction; widens the validator to reject `.internal`/`.local`; adds
`POST /admin/brands/{workspaceId}/reanalyze` to recover the 6 stuck brands; adds an
`analyze_site.attempt` counter. Verified 72 tests / 0 failures; 13 assertions falsified.

**Order is not optional.** Code before `.env` = the API refuses to start.

---

## P0 — today

| # | Item | Owner | State |
|---|---|---|---|
| 1 | The `.env` + recreate above | **Swapnil** | ⬜ |
| 2 | **Rotate Anthropic + Sarvam keys** — 9 days, 4 reviews, still open | **Swapnil** | ⬜ |
| 3 | Gitignore both `env.example`, placeholder the values | Meera | ⬜ |
| 4 | Remove plaintext demo password from `meera-live-smoke.yml:38-39` | Meera | ⬜ |
| 5 | Don't commit `application.yml` alone — binds to untracked `TrendIngestProperties.java` | Vikram | ⬜ |

---

## P1 brand-AI defects — 6 of 8 shipped

| Defect | State |
|---|---|
| P1-5 analytics tool uncallable | ✅ `bee93b8` |
| P1-8 / P1-9 `analyze_site` result discarded + false comment | ✅ `bee93b8` |
| P1-11 template thinned the draft | ✅ `bee93b8` |
| P1-13 invisible site analysis | ✅ `d1357a6` |
| P1-14 unbounded history (cap now in the query) | ✅ `d1357a6` |
| P1-6 credit burned on failure (Python half) | ✅ `7595290` |
| **P1-7** canvas rail still declares triggers that can never fire | ⛔ ruling |
| **P1-10** matching is a `LIKE '%term%'` sold as matching | ⛔ ruling |
| **P1-12** budget ignores our own rate-band data | ⛔ Priya |
| **P1-6 backstop** `turn_started` design ready, not built | ⛔ ruling + Kabir |

---

## Blocked on a decision, not on engineering

| # | Question | Who |
|---|---|---|
| A | P1-7 — delete the two canvas stages, mark them "your step", or drive them from campaign status? | Swapnil |
| B | P1-10 — change the copy (Tejas has it written) or build the algorithm (Priya has a 4-day plan)? | Swapnil |
| C | P1-12 — architecture call; it changes numbers shown to brands | Priya |
| D | P1-6 backstop — build `wiki/tech/P1-6-BACKSTOP-DESIGN.md` or leave it? | Swapnil + Kabir |
| E | Privacy policy claims we train on customer data (we don't) and omits the foreign sub-processor (we use one) | Swapnil / legal |
| F | Are the other session's 6 unpushed billing/auth commits sanctioned? | Swapnil |

---

## By owner

**Meera** — the `.env` + recreate (on Swapnil's go) · **provision the two live test accounts (6
verification passes blocked)** · pin image digests, kill `:latest` · P0 #3, #4 · confirm whether the
other four AI clients were dead on live.

**Neha** — the live brand journey end to end. **Never once completed**; last authenticated pass
2026-07-23. Blocked on Meera's accounts.

**Vikram** — **F-0498** portfolio PATCH persists only row 0 of `rateCard`, skips the range check ·
rate card v2 + availability + budget-fit (30-day) · F-0497 fix still uncommitted ·
`CreateCampaignExecutor.filterAllowed` needs `Locale.ROOT` · P2 items below.

**Ananya** — admin UI button for the re-analyze endpoint (6 brands recoverable by curl meanwhile) ·
P1-7 canvas rail, after ruling A.

**Kabir** — sign off the P1-6 `turn_started` design · **F-0735 open since 2026-09-07: influora-ai is
making its internal calls to a co-tenant product on `:8080`.**

**Priya** — ruling C · **decide what replaces the compose gate** (see below).

**Tejas** — matching-claim copy rewritten and waiting on ruling B · "First AI-first influencer
platform in India" removed from the live chat header.

---

## P2 — safety and false comfort. None urgent; all cheap now, expensive after an incident.

`ToolCallValidator` blocks nothing at runtime (all six call sites pass compile-time enums) · no
replay store for the on-behalf JWT · **no feature flag on brand Meera at all** — the only brake is a
global kill switch · `has_invented_price` not wired to brand chat · **nobody reviews Meera's output:
no sampling, no eval harness, no red-team corpus** · zero tests on `ShowCreatorsExecutor` and
`GetCampaignPerformanceExecutor`, the two most data-exposing read tools · Block B's cache breakpoint
misses every turn (credit counter inside the cached block) · cache hit rate unmeasurable.

---

## P3 — deploy and evidence

**Two live test accounts still not provisioned — the single biggest blocker on this board.** ·
deploy pins mutable `:latest`, so "is it deployed?" is unanswerable · live smoke points at a dead IP
and is `workflow_dispatch` only · `analyze_site` has no JS rendering (`playwright` sits unused in
`requirements.txt`) · `CreditMeter.tsx` + `useMeeraCredits.ts` have zero importers · brand-safety /
GARM chain built and dark since build.

**The compose gate is structurally blind to production.**
`.proof-os/gates/compose-forwards-what-the-app-binds.sh` was written 4 commits before this outage
(`0627115`) for exactly this defect class — and is **green**, because it diffs
`deploy/hostinger/*.yml` against `deploy/utho/*.yml` and both forward the var correctly. **The file
that runs production is neither of them.** Any gate reading `deploy/**` proves nothing about the live
box; only the live file or the container's own `printenv` can. Priya to decide the replacement.

---

## P4 — carried from `TASK_INBOX.md`

F-0498 (above) · rate card v2 Ticket B · F-0497 fix uncommitted.

---

## Recommended sequence

1. **STEP 1 + STEP 2 above.** Nothing else on this board matters while the AI service is unreachable.
2. **P0 #2** — the keys.
3. **Provision the test accounts.** We shipped **nine** fixes this session, all green and falsified,
   and cannot demonstrate one of them working for a real brand because nobody can log in. That is
   precisely how `analyze_site` stayed broken for five weeks: correct code, correct compose, green
   gate, and no human ever completed the flow. It was found by a hand-typed SQL query.
4. Rulings A–F in one sitting.
5. Then P1-10, the 4-day creator-match rebuild.
