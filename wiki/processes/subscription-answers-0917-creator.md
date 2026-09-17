# Subscription answers, Part 2 (creator) — Priya, 2026-09-17

**Questions:** `wiki/processes/subscription-questions-0917.md` CR-1..CR-12, PB-1..PB-13, Part 4.
**Answered from code only**, against committed code.

**Which commit.** The brief named HEAD `1921786`. HEAD moved twice during this pass (`e2dba1c`, then `1c7daa7`, both from the other session). `git diff --stat 1921786 1c7daa7 -- influora-api/src influora-ai src` is **empty**, so every code citation holds for all three. Only deploy files and docs changed: `deploy/utho/docker-compose.utho-shared.yml` gained `MEERA_CREATOR_ENABLED` / `CREATOR_COPILOT_ENABLED` in `e2dba1c` (at `1921786` it had neither). Compose line numbers below are at `1c7daa7`.

**Working tree vs HEAD.** Uncommitted edits exist in `src/lib/api.ts` and `CreatorNudgeService.java`; lines cited from those files are HEAD lines. The other files cited here have no uncommitted changes (`git diff --quiet HEAD` checked on MeeraCopilotChat.tsx, chat.py, config.py, CreatorMeeraController.java, MeeraSessionService.java).

**Two facts that decide most answers:**
1. **Phase B is not on this branch.** B0 lives on `feat/meera-creator-phase-b0` (commits `1571dce`, `e54c071`, `d851c87`, `a33f07e`, `948f10f`, `df20091`). It is not an ancestor of HEAD or `main` (`git merge-base --is-ancestor` fails for both). There is also a worktree `../influora-b0` with 58 uncommitted paths. I cite that branch only for context and did not audit it.
2. **The credits spec is not built anywhere.** `CREATOR_CREDITS_ENABLED`, `CreatorCreditService` and `V20260912100*` do not appear in any commit (`git log --all -S` / `-- '*V20260912100*'`, both empty), on the b0 branch, or in the working tree.

**Production.** Repo compose files are not the live Utho env (the live box reads its own env file). Every production value below is **UNKNOWN** unless stated otherwise.

---

## 2A. Is there a creator subscription?

**CR-1 — NO.** Creators have no paid plan and no credit packs.
- Only two plans are seeded, both for brands: `FREE` and `PRO` (`influora-api/src/main/resources/db/migration/V55__seed_billing_plans.sql:17-35`). Every billing read resolves a brand workspace (`BillingController.java:60`, `:89`).
- There is no creator pack, order or credits route: `/creator/credits` and `/creator/meera/credits` have 0 hits in `src` and `influora-api/src`.
- The only creator "price" in code is a **poll**, not a product. `src/components/site/MeeraPricingPoll.tsx:9-19` says there is no backend and stores the vote in the browser; it shows ₹899, ₹999 and ₹1,499 options at `:30-33` and renders on `src/pages/meera-for-creators.tsx:221`.
- What a creator actually pays today is commission on deals, not a subscription.

**CR-2 — PARTLY.** Chat and voice are built and on by default. Co-pilot suggestions are effectively off by default.
- **Meera chat (Phase A):** `POST /creator/meera/sessions`, send a message, read history (`CreatorMeeraController.java:138-227`). It is conversation only, with no tools: `influora-ai/app/prompt/assembler.py:444` says "Available tools: none in this phase" and `:832` sets `tools = []`. The persona says so too (`influora-ai/app/prompt/creator_persona.py:79-90`).
- **Voice:** speak and transcribe (`CreatorMeeraController.java:245-317`).
- **Flag for chat and voice:** `MEERA_CREATOR_ENABLED`. Code default is `true` (`application.yml:215`). Compose sets `"true"` (`deploy/utho/docker-compose.utho.yml:184`, `deploy/hostinger/docker-compose.hostinger.yml:190`) or defaults to `true` (`docker-compose.utho-shared.yml:215`). When off, every route returns 404 `FEATURE_DISABLED` (`CreatorMeeraController.java:104-109`).
- **Co-pilot daily suggestion:** `/creator/copilot/suggestion/today` has no flag check (`CreatorCopilotController.java:42-50`). But the theme-tagging job that feeds it is off by default (`CreatorCopilotProperties.java:26` `enabled = false`, `application.yml:534`, `CreatorThemeTaggingJob.java:67-73`), and compose defaults `CREATOR_COPILOT_ENABLED` to `false` (utho.yml:180, utho-shared.yml:210, hostinger.yml:186). With no themes, the creator gets `pending_tagging` or `no_suggestion_today` (`CreatorNudgeService.java:151-157`, HEAD).
- Production values of both flags: **UNKNOWN**.

**CR-3 — NOT BUILT** on this branch, all four.
- **Rate quote:** no `RateQuoteService` at HEAD. `RateEstimationService` only pre-fills a default floor (`CreatorAgentPreferencesService.java:137`) and feeds scoring (`ScoreCalculationJob.java:298`). No creator can ask for a quote.
- **Deal risk:** no `DealRiskService`. The only hit is a comment at `src/lib/api.ts:2522`.
- **Drafted replies:** no `draft_reply` and no `meera_drafts` (0 hits).
- **Campaign fit:** no `CampaignFitService` (0 hits).
- Rate quote and deal risk exist on the unmerged b0 branch (`a33f07e`). Drafts and campaign fit are spec only.

## 2B. Limitations

**CR-4 — PARTLY.** The cap is **$0.75**, not $2.00. Hitting it is a dead end.
- **Where it is set:**
  - `influora-ai/app/config.py:455-457` (default 0.75)
  - `influora-ai/app/costs/spend_tracker.py:80` (`CREATOR_MONTHLY_CAP_USD = Decimal("0.75")`)
  - `influora-ai/env.example:141`
  - compose `"0.75"`: `deploy/utho/docker-compose.utho.yml:308`, `deploy/hostinger/docker-compose.hostinger.yml:348`
  - `utho-shared.yml` does not set it, so the code default 0.75 applies.
  - Admins can override per creator (`AdminCreatorAgentController.java:52-59`). A cap of `<= 0` disables it (`spend_tracker.py:573-574`).
- **What the creator sees:** HTTP 429 `CREATOR_MONTHLY_CAP_REACHED` (`influora-ai/app/routes/chat.py:253`, `:263-274`) with the text "resets on the 1st of next month… message support" (`spend_tracker.py:85-88`). The frontend shows that sentence on both paths (`MeeraCopilotChat.tsx:38`, `:266-267`, `:309`).
- **What is missing:** no machine-readable `resets_on` (0 hits) and no "what still works" links. Nothing else works for free anyway, because there is no deterministic creator feature to fall back to.

**CR-5 — NOT BUILT.** There is no brief-extraction route and no `BRIEF_EXTRACT_MONTHLY_CAP_USD` at HEAD (0 hits). Chat cannot starve something that does not exist. The separate cap exists only on the b0 branch (`influora-ai/app/config.py`, `app/routes/brief_extract.py` there).

**CR-6 — NO.** There is no per-creator daily action cap.
- The 500-per-day cap is brand-only: `AICreditService.java:118-137` throws 429 `DAILY_ACTION_LIMIT_EXCEEDED`, and creator turns skip that service (`MeeraSessionService.java:378-389`, `if (!isCreatorTurn)`).
- The only creator throttle is the per-user rate limit: 20 turns per window (`AuthRateLimitFilter.java:111-112`, `:232-233`) and 30 voice calls per window (`:241-242`, `:446-448`). Callers get the filter's standard 429, not R7's code.

## 2C. Price and credits

**CR-7 — NOT BUILT.** None of turn=1, voice=2, brief=3, 30 signup or 40 monthly exist for creators, and the `CREATOR_CREDITS_ENABLED` flag does not exist at all (0 hits, any ref).
- A creator session returns `CreditsSummary` null (`CreatorMeeraController.java:172`) and `creditsRemaining` hard-coded to `0` (`:204`).
- The only credit constant is the brand's `TURN_CREDIT_COST = 1` (`MeeraSessionService.java:84`).

**CR-8 — NOT BUILT / NO.** There is no pack catalogue, no `POST /creator/credits/orders`, no `credits:` receipt prefix, and no webhook branch (0 hits for `CreatorCredit`, `creator_credit`, `creator/credits`). Nothing can be bought on production.

**CR-9 — NOT BUILT.** There are no buckets, no expiry rule, no reset job and no split refund for creators. `CreatorCreditResetJob` has 0 hits.

**CR-10 — NOT BUILT.** A creator turn is never charged, so there is nothing to refund.
- For creators, influora-ai skips the early release (`chat.py:403`).
- If the release route is called anyway (`MeeraInternalController.java:354-360` → `MeeraSessionService.java:681-682`), `AICreditService.doRelease` refuses a turn that was never charged (`AICreditService.java:243-249`). No double refund is possible today, but no single refund exists either.

**CR-11 — NOT BUILT.** Transcribe charges nothing (`CreatorMeeraController.java:277-317`: no credit call, fallback returns `{"fallback": true}`). A voice turn costs 1 USD-metered chat turn and 0 credits.

**CR-12 — NO** (trivially true). No creator credit code exists, so nothing posts to `wallet_transactions`. When credits are built, the test for this has to be written with them.

## 2D. Meera Creator Phase B

**PB-1 — NOT BUILT** on this branch.
- B0 is committed on unmerged `feat/meera-creator-phase-b0`: Wave 0 `1571dce`, Wave 1 `e54c071`, Wave 2 `d851c87`, Wave 3 `a33f07e`, controls `948f10f`, paste `df20091`. More B0 files are uncommitted in `../influora-b0` (assignment L13).
- **B1:** no secure-link, send-log or `introduced_by` migration anywhere. The only trace is a result record shape, `SendRoutineReplyResult`, on the b0 branch (`CreatorToolDtos.java:154`), declared with the other §3.5 DTOs. No B1 service or route exists.

**PB-2 — NOT BUILT.** At HEAD there is no `/creator/briefs`, no `creator_briefs` table and no extraction route (0 hits). The b0 commit `df20091` claims paste → summary, price and flags; not verified here.

**PB-3 — NOT BUILT.** There are no creator tools (`assembler.py:832`). The Phase A routes do check the flag, the creator profile (which a brand principal cannot resolve) and consent on the server (`CreatorMeeraController.java:104-109`, `:129-136`, `:141-144`, `:182-185`), but there is no scope level to refuse.

**PB-4 — NOT BUILT.** No tool result, draft, secure link or media kit exists that could carry a floor.
- The barrier covers Phase A only: `InfoBarrierTest.java:59-86` (import allow-list) and `InfoBarrierRuntimeTest.java:132`, `:204`, `:262` (brand context never shows the floor; response shape).
- No test covers **agency name** (assignment L15 AGENCY-LEAK).

**PB-5 — NOT BUILT.** No `draft_reply`, no strategic flag, no override log (0 hits).

**PB-6 — NOT BUILT, not even on b0.**
- `brand-chat.tsx` at HEAD mentions Meera only in deal-terms comments (`:187`, `:1621`, `:1895`). The property `influora.meera.brand-facing-stamp` does not exist in `application.yml`, `env.example` or either compose file. The b0 worktree has only a persona comment (`creator_persona.py:35`) and a test that bans the copy (`meera-for-creators.claims.test.tsx:46`).
- The label appears only in the Remotion demo films: `src/remotion/script.ts:197`, `script.en.ts:113`, `script.mr.ts:113` ("Drafted with Meera · approved by Riya"). Marketing shows a feature that does not exist.

**PB-7 — NOT BUILT.** `Campaign.java` has no introduced-by field (0 hits) and no migration exists. This is a B1 item and correctly not started.

**PB-8 — NOT BUILT** at HEAD. No quote formula; `RateEstimationService` is not exposed to creators (see CR-3). b0 `a33f07e` claims "quotes with honest provenance"; not verified here.

**PB-9 — NOT BUILT** at HEAD. The spec contradicts itself: §1 and §11 say ten rules, §14.5.a says "all 14". b0 `a33f07e` claims fourteen rules with `DealRiskServiceTest` and `DealRiskServiceEvaluateDealTest`; not verified here.

**PB-10 — NOT BUILT** at HEAD. `negotiation_holdout` has 0 hits. It exists on b0 (14 files).

**PB-11 — NO.** HEAD records nothing for any of the five gate metrics:
- no `creator_briefs` table (metrics 1, 2, 4)
- no `meera_drafts` table (metric 3)
- metric 5 has only the Phase A `InfoBarrierRuntimeTest`

On b0, `meera_drafts` has the `edited TINYINT(1)` column (`V20260910100300__meera_drafts.sql:62`, statuses PENDING, SENT and DISCARDED only, `:61`). No `B0-METRICS` query or report exists anywhere.

**PB-12 — UNKNOWN.** Code cannot prove a deploy. Phase A code is at HEAD (migrations `V72`–`V74`, `V20260903150000`–`V20260903170000`) and its flag defaults on. The live Utho env is not in the repo, so whether it is deployed and smoke-tested is not provable here.

**PB-13 — NO.** Neither set exists at HEAD. The only `V202609(10|12)` migration is `V20260912120000__backfill_free_subscriptions.sql`.
- On b0: `V20260910100000`, `100100`, `100300`, `100500`, all distinct from `V20260912120000`.
- `V20260912100000`–`100300` exist in no ref.
- No equal versions anywhere, so no collision. Flyway runs with `out-of-order: true` (`application.yml:61`), so b0's lower versions can apply after `V20260912120000`.

---

## Part 4 — Summary

### 1. Verdicts
| Id | Verdict | Id | Verdict |
|---|---|---|---|
| CR-1 | NO | PB-1 | NOT BUILT |
| CR-2 | PARTLY | PB-2 | NOT BUILT |
| CR-3 | NOT BUILT | PB-3 | NOT BUILT |
| CR-4 | PARTLY | PB-4 | NOT BUILT |
| CR-5 | NOT BUILT | PB-5 | NOT BUILT |
| CR-6 | NO | PB-6 | NOT BUILT |
| CR-7 | NOT BUILT | PB-7 | NOT BUILT |
| CR-8 | NOT BUILT | PB-8 | NOT BUILT |
| CR-9 | NOT BUILT | PB-9 | NOT BUILT |
| CR-10 | NOT BUILT | PB-10 | NOT BUILT |
| CR-11 | NOT BUILT | PB-11 | NO |
| CR-12 | NO | PB-12 | UNKNOWN |
| | | PB-13 | NO |

**Totals (25):** YES 0 · NO 5 · PARTLY 2 · NOT BUILT 17 · UNKNOWN 1.

### 2. Blockers to charging creators money
1. **There is nothing to sell or deliver.** No creator balance, ledger, pack catalogue, order route or webhook credit branch exists in any ref (CR-7, CR-8). Any money collected would buy nothing.
2. **A paid credit would unlock no extra value.** Creator Meera has zero tools (`assembler.py:832`). The earning features (paste, quote, risks) are on an unmerged branch (PB-1, D1), and drafts, the brand stamp and campaign fit are not built at all (CR-3, PB-6).
3. **Creators have no metering they can see, and the wall is a dead end.** A $0.75 USD cap enforced in Python, no `resets_on`, no daily action cap, no usage read. Phase A being live is unproven (CR-4, CR-6, PB-12). A top-up cannot be sold against a limit the creator cannot see and the server cannot count in credits.

### 3. New work found (not in `assignments-0917.md` L1–L16 / D1–D3)
| # | Gap | Suggested owner |
|---|---|---|
| N1 | Build CREDITS-SPEC K1–K10 (balance and ledger, packs, order route and webhook branch, reset job, R7 daily cap, 402 plus pack call-to-action). Starts after D1 merge and Swapnil's O1–O5 rulings. L16 covers only the brief-hook timing. | Vikram (API), Ananya (FE), Rohan (pack price and GST line), Kabir gate |
| N2 | Brand-facing "Drafted with Meera" stamp: `influora.meera.brand-facing-stamp` in 4 files, `brandVisibleMetadata` helper, `brand-chat.tsx` label. A §14.3 **B0** item missing even from the b0 worktree. | Vikram + Ananya |
| N3 | Cap-message block (§14.4.c): `resets_on` in `_creator_cap_response`, plus the three "what still works" links on both `MeeraCopilotChat` error paths | Vikram (influora-ai) + Ananya |
| N4 | Prove Phase A live on Utho: read the live env for `MEERA_CREATOR_ENABLED` / `AI_CREATOR_MONTHLY_CAP_USD`, run one consented creator turn, and record the rows | Meera (DevOps) + Neha |
| N5 | B0 gate measurement: queries for metrics 1, 3, 4 and 5, the hand-review protocol for metric 2, and the `B0-METRICS.md` template | Tara + Meera (DB); Kavya for the metric-2 sample |
| N6 | Remotion films show an unbuilt "Drafted with Meera" label (`src/remotion/script.ts:197`, `script.en.ts:113`, `script.mr.ts:113`). L14 covers only `meera-for-creators.tsx`. | Tejas |
