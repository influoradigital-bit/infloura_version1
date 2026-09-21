# Vikram — B0-live answers (2026-09-18)

All commands run read-only. No edits/commits/merges made in either checkout. Scratch copy of b0 built at a temp path for isolated mvn/tsc/vitest runs (b0 worktree itself untouched except vitest/tsc dry runs which write no git-tracked files).

## 1. Uncommitted b0 paths (106) — grouped

Full list: `git -C influora-b0 status --porcelain` (106 lines).

- **B0 core feature (backend, ~20 files):** `CreatorBriefService.java`(M), `CreatorBriefController.java`(M), `GetBriefExecutor.java`(new), `AuthRateLimitFilter.java`(M, paste+get buckets), `DealRiskService.java`/`RiskText.java`/`rules/HideDisclosureRule.java`/`rules/OffPlatformPaymentRule.java`(M), `CreatorMeeraToolController.java`+`CreatorToolDtos.java`+`CreatorToolScopes.java`(M), `CreatorAgentPreferences.java`(M) + matching new tests (`GetBriefExecutorTest`, `CreatorBriefServiceRealRiskRulesTest`, `AuthRateLimitFilterCreatorBriefGetBucketTest`, `DealRiskServiceEvaluateExtractionTest`, `risk/rules/`, `test/resources/risk-corpus/`).
- **B0 core feature (frontend, ~14 files):** `PasteBriefCard.tsx`+test(new), `creator-copilot-paste-brief.test.tsx`(new), `creator-copilot.tsx`(M, mounts card), `MeeraSettingsSection.tsx`+`.saved-briefs.test.tsx`, `useRiskFlagDismissals.ts`+test(new), `deal-risk-card.tsx`+test(M), `CreatorToolResultRenderer.tsx`+test(M, "Use in counter").
- **B0 core feature (AI service, ~9 files):** `creator_schemas.py`, `loop.py`, `config.py`, `assembler.py`, `creator_persona.py` + their tests, `tests/clients/`(new), `test_k3_dto_field_classification_drift.py`(new).
- **Consent/other-lane overlap (~9 files):** `ConsentScreen.tsx`+test(new), `consent-version-sync.test.ts`(new), `auth/consent.py`, `routes/voice.py`+`test_voice_consent.py`, `security/redaction.py`+test — these are Kabir/Nisha consent-flow work (see `KABIR-CONSENT-0917.md`, `NISHA-CONSENT-0917.md`), not B0-specific but currently entangled in the same tree.
- **Unrelated/noise (~30 files):** `.proof-os/journal.jsonl`, `ledger/failures.jsonl`, `rc/confirm.rc` (bookkeeping, regenerate — do not hand-merge); ~20 untracked `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/*.md` process/review notes (KAVYA-*, PRIYA-LASTCALL-*, RULINGS-U, ASSIGN-PENDING) — planning artifacts, not code; `MeeraCopilotChat.tsx`+test, `src/lib/api.ts`/`meera-api.ts`, `creator-chat.tsx`, `creator-settings-*.test.tsx` (4 files), `remotion/script*.ts`+tests, `.github/workflows/frontend-checks.yml`, `ci/stale-comment-check.py`, `FloorBarrierTest.java` — other lanes' in-flight edits sharing the tree, not part of the B0 diff (df20091 only touched brief/risk/rate-limit files per its own commit body).

## 2. Does the tree compile/pass? — BUILT-uncommitted, proven

Copied the b0 worktree (excl. `node_modules`/`.git`/`target`) to a scratch dir, ran offline:
- `mvn -o -q clean test-compile` in `influora-api` → exit 0, no errors.
- Targeted tests via surefire reports (not piped through grep, per past false-green lesson): `CreatorBriefServiceTest` 21/21, `GetBriefExecutorTest` 17/17, `AuthRateLimitFilterBriefPasteBucketTest` 4/4, `AuthRateLimitFilterCreatorBriefGetBucketTest` 5/5, `CreatorBriefServiceRealRiskRulesTest` 3/3 — all green, 0 failures/errors.
- `npx tsc --noEmit -p .` in the b0 worktree itself → exit 0, empty output.
- `npx vitest run PasteBriefCard.test.tsx creator-copilot-paste-brief.test.tsx` in the b0 worktree → 20/20 passed.

**BUILT-uncommitted and verified compiling/green.** Not proof of DB behavior (see Q5).

## 3. Merge vs rebase — BUILT-committed evidence

`git merge-tree --write-tree feat/meera-creator-phase-e feat/meera-creator-phase-b0` → exit 1 (real conflicts), 4 files:
- `.proof-os/journal.jsonl`, `.proof-os/ledger/failures.jsonl` — **trivial**, append-only logs, regenerate rather than merge.
- `influora-api/src/main/resources/application-prod.yml` — **not trivial**: phase-e changed 72 lines since base `8c7b18b`, b0 changed 16 (b0 added a `:https://ai.influora.internal` default to `meera-chat-ai.base-url` that phase-e's version doesn't have). Needs a human pick, small.
- `influora-api/.../MeeraSessionServiceTest.java` — **not trivial**: phase-e's `d1357a6`/`7f48e8d` (persistence + pagination fixes) and b0's `d851c87` (Wave 2 tool surface) both edited this test independently (237 vs 198 diff lines from base). Needs manual reconciliation.

Given only 4 conflicts and 2 of them trivial, **merge b0 into phase-e is faster than rebasing 9 commits** — rebase would replay each of b0's 9 commits individually against phase-e's 77-commit-ahead tree, multiplying conflict-resolution touchpoints; a single merge resolves the same 4 files once.

## 4. Migrations — BUILT-committed evidence

b0-only migrations (`comm -13` on `ls db/migration` main vs b0): `V20260910100000__meera_phase_b_prefs.sql`, `V20260910100100__creator_briefs.sql`, `V20260910100300__meera_drafts.sql`, `V20260910100500__deal_offer_history.sql`. None collide by name with main's highest (`V74__...`). All are timestamp-versioned > any numeric `V##`, so no ordering collision either way. `spring.flyway.out-of-order: true` confirmed identical in both `application.yml`s (main line 60, b0 same block) — same comment block, same setting.

## 5. Flags/config — BUILT-committed evidence

`influora-api/application.yml` (b0): `influora.meera.creator-enabled: ${MEERA_CREATOR_ENABLED:true}` (already on main too, default true — reads open). New in b0 only: `influora.meera.creator-send-enabled: ${MEERA_CREATOR_SEND_ENABLED:false}` (default OFF — this is the send gate, exactly what's wanted: reads on, sends off). AI client: `creator-copilot-ai.base-url` / `connect-timeout-seconds:5` / `request-timeout-seconds:15` (env `CREATOR_COPILOT_AI_BASE_URL`/`_CONNECT_TIMEOUT_SECONDS`/`_REQUEST_TIMEOUT_SECONDS`). influora-ai side (`config.py`): `BRIEF_EXTRACT_MODEL` (defaults to `TRENDSPARK_MODEL`), `BRIEF_EXTRACT_MONTHLY_CAP_USD` default `0.25` (separate bucket key `f"{creator_profile_id}:brief"`, isolated from the chat cap). **To ship paste-and-read with send OFF: set nothing new** — `creator-enabled` defaults true, `creator-send-enabled` defaults false already. Just don't set `MEERA_CREATOR_SEND_ENABLED=true`.

## 6. df20091 "NOT PROVEN" — from the commit message itself

Full text via `git log -1 --format=%B feat/meera-creator-phase-b0`. Three items, "all three need a real database": (1) the offer-history unique key is genuinely unique, not a plain index; (2) the new transaction boundary lets a committed paste survive a rollback further down; (3) the actor clause on Meera-drafted-flag excludes brand rows rather than merely being passed. "ALSO NOT DONE": no frontend screen calls `POST /creator/briefs` yet per that commit — **but** the uncommitted worktree (Q1/Q2) now adds exactly that caller (`PasteBriefCard.tsx`, mounted in `creator-copilot.tsx`), so this gap is closed on disk, just uncommitted. None of the 3 DB items can be proven without a real database — they need Meera's Docker-gated CI job (3060 Docker-gated tests were skipped locally per the commit's own verification line: "3060 run ... all Docker-gated").

## 7. Smallest shippable cut

Ship: paste → summary/price/risk-flags read path only (`PasteBriefCard` → `CreatorBriefController` → `CreatorBriefService`, fallback extractor, risk rules). Cut/hide: the "Use in counter" button in `CreatorToolResultRenderer.tsx:453` (confirmed present, wired to `dealTerms`/anchor-or-total logic per its own test at line 187 — it does have a handler, so it is NOT a dead button, but it belongs to the chat-tool surface (B2), not B0's paste card — hide behind `creator-send-enabled`-adjacent scope if it implies a counter-offer action). Cut: the removed marketing claims in `meera-for-creators.tsx` are already gated by `meera-for-creators.claims.test.tsx` (asserts "drafts the reply", "send button is always yours", "drafted with Meera" are ABSENT) — this file already matches paste-and-read-only positioning; no further copy work needed for B0.

## Ordered path to live (dependencies, no day-estimates)

1. **Reconcile the 4 merge-tree conflicts** (Q3) — done_when: `git merge-tree --write-tree` on phase-e+b0 returns exit 0.
2. **Commit the 106 b0-worktree paths**, splitting B0-core from the consent/other-lane overlap (Q1) so B0 lands as its own reviewable commit — done_when: `git status` in b0 worktree is clean and the diff review shows only B0 files in the B0 commit.
3. **Merge b0 into phase-e** (not rebase, per Q3) — done_when: merged branch builds (`mvn -o test-compile`) and `npx tsc --noEmit` both exit 0 on the real merge result, not just the scratch copy.
4. **Apply the 4 migrations** (Q4) on a real DB with `ddl-auto=validate` — done_when: boot succeeds against a fresh schema and `flyway_schema_history` shows all 4 applied in order.
5. **Set `MEERA_CREATOR_SEND_ENABLED` explicitly to `false`** in every deploy env file that lists `MEERA_CREATOR_ENABLED` (4 files per spec §12: `application.yml`, `env.example`, both hostinger/utho compose files) — done_when: grep for the new key returns 4 matches, all `false`.
6. **Prove the 3 NOT-PROVEN DB items** (Q6) via Meera's Docker CI job, not locally — done_when: the 3060 Docker-gated tests referenced in df20091's commit message run green in that job.
7. **Wire and smoke `POST /creator/briefs` end-to-end on a running stack** — done_when: a real paste through `PasteBriefCard` returns a summary/price/flags from the live API, observed in the browser network tab.
8. **Start the B0→B1 gate measurement window** (spec §14.5.c) — done_when: `B0-METRICS.md` exists with 14-days-or-100-briefs numbers, per spec.

## Top 3 blockers

1. **DB-dependent correctness is unverified locally** — the unique key, transaction-boundary rollback safety, and actor-clause filter (Q6) can only be proven in Docker CI; commit message says so itself.
2. **Merge conflict in `application-prod.yml`** (Q3) — b0 and phase-e diverged on `meera-chat-ai.base-url` default; picking wrong could silently point prod at `localhost` again (the exact class of bug documented in that same file's own comments).
3. **106 uncommitted paths mix three lanes** (B0, consent work, and unrelated in-flight edits) in one worktree — committing carelessly risks shipping half-done consent/remotion/settings changes bundled with B0, or losing B0 work if someone resets the tree.

Answers file: `C:\Users\Sage world\Downloads\New Influora Ai\New Influora\.proof-os\tasks\T-PHASEB-LIVE-0918\vikram-answers.md`
