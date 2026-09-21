# Priya last call: U-1 re-check (items 1, 3, 8) and the U-6 build

**From:** Priya (CTO)
**To:** Arjun. Builders: vikram, ananya. QA: kavya. Copy: kabir, nisha, meera
**Date:** 2026-09-17, written 16:55
**Tree:** `influora-b0`, branch `feat/meera-creator-phase-b0`, uncommitted on `df20091`
**Bars:**
- **U-1:** `RULINGS-U-0917.md` "Last-call bar", U-1 items 1, 3 and 8, plus UF-1 to UF-3 in `PRIYA-LASTCALL-U1-K4-0917.md`.
- **U-6:** R-U2 in `RULINGS-U-0917.md`, and the Round 3 §3 consent-version sequence ("Wave U commits Case A + `v2`; U-7 UI switches to Case B and bumps to `v3` in the same commit").

## Verdicts

| Item | Verdict | Why, in one line |
|---|---|---|
| **U-1** (items 1, 3, 8) | **PASS** | All three of my mutations from the first call now go red against the new tests. Item 8 closes as I amended it. **U-1 is PASS overall**; only the live check after S-2 is still owed. |
| **U-6** (build) | **FAIL** | The build that was reviewed (Case A + `v2`) is no longer in the tree. The tree now carries U-7's Case B + `v3` on the frontend against a `v2` backend, and promises deletion with no delete route. |

---

## How this was checked

- **Maven.** After all mutations were restored, the full suite compiled from source (870 main / 344 test) between 16:42:52 and 16:46:07: `Tests run: 3104, Failures: 0, Errors: 0, Skipped: 25`, `BUILD SUCCESS`, exit 0.
- **pytest.** `tests/clients/test_spring_client.py`, `test_tool_schema_anthropic_valid.py` and `test_loop_creator_dispatch.py`: `74 passed`. I did not run the whole suite; Vikram is adding K-3 tests. Kavya's full run was 958.
- **Restoration.** Each file I mutated matches its starting sha256:
  - `spring.py` `fa09b901…`
  - `CreatorBriefService.java` `71985c82…`
  - `CreatorAgentPreferences.java` `14bca5f1…`

  `grep FALSIFY` in `influora-api/src` and `influora-ai/app` → 0. I did not touch `src/`.

---

## U-1 re-check: PASS

| # | Wrong version | Result | Red line, quoted |
|---|---|---|---|
| P2 | `spring.py` L187-188 removed: the 40s timeout is built but never sent | **RED**, 1 failed / 3 passed | `test_spring_client.py::test_b_read_timeout_override_reaches_the_actual_request`: `assert seen_timeouts[0]["read"] == 40.0` → `E assert 5.0 == 40.0` |
| P3 | `spring.py` L163 → `attempts = settings.retry.max_retries + 1`, so `allow_retry` is ignored | **RED**, 1 failed / 3 passed | `test_a_timing_out_request_makes_exactly_one_call_when_allow_retry_is_false`: `assert attempts["n"] == 1` → `E assert 3 == 1` |
| J4 | AI call made inside the young-NEW branch before the 409 (`CreatorBriefService.java` L343) | **RED**, 38 run / 3 failures. Now red on **both** paths | `GetBriefExecutorTest.dealId_secondCallOnAYoungNewBriefIsRefused:264` `TooManyActualInvocations … Wanted 1 time … But was 2 times: -> at CreatorBriefService.analyse(CreatorBriefService.java:438) -> at CreatorBriefService.readOrReanalyse(CreatorBriefService.java:343)`. Also red: `briefId_youngNewBriefIsRefused:319` and `CreatorBriefServiceTest.get_youngNewBriefRefusesRatherThanReadingUntouched:604` |

- **Item 1: met.** A2 is the `verify(briefAiClient, times(1))` at `GetBriefExecutorTest.java` L264. Kavya checked it by reading only; J4 above is its red line.
- **Item 3: met.**
  - A1 is a real client over `httpx.MockTransport`, built by its own `__init__` (`test_spring_client.py` L43-54).
  - Both controls stop the checks passing vacuously: L93-112 (`allow_retry=True` makes `max_retries + 1` calls) and L139-157 (no override → `spring_read`).
- **Item 8: met, as I amended it (UF-3).**
  - **Description:** `creator_schemas.py` L206-225 names `error=BRIEF_STILL_READING` and `error=BRIEF_ANALYSIS_UNAVAILABLE` with separate handling, and adds the FALLBACK sentence ("never present a FALLBACK summary as your own reading").
  - **Javadoc:** `GetBriefResult` (`CreatorToolDtos.java` L129-146) records why there is no `degraded_reason` and names the B0-52 follow-up.
  - **My recommended message fixes are done too:** "This brief has no readable analysis" (`GetBriefExecutor.java` L177, no "yet") and "Still reading this brief — wait for the creator's next message before trying again" (`CreatorBriefService.java` L345-346).
  - **Prompt version:** `PROMPT_VERSION` is still `meera-2026.09.10.3` (`config.py` L69). That is correct: under UF-3 the description rides the unshipped `.3` bump.
- **Kavya's CI finding stands, unchanged by this call.** Rule 3 of `ci/stale-comment-check.py` passes vacuously after the first bump on a branch (`KAVYA-BATCH1-0917.md` round 2, item B). The `PROMPT_SOURCES` gate I required is still a **condition of the Wave U commit**, and it cannot count as proof while that hole exists. Owner vikram; the three-step falsification is still owed.

**U-1 live check owed after S-2 (meera), on a build that carries K-2 and K-2b:** one cold `get_brief` by `deal_id`, for a deal with no brief row, inside a real Meera chat turn. It must return ANALYZED with non-null extraction and flags on the first call, with no 409 and no `network_error`. The stream stays alive to `done`, and the new row's `created_at` equals its insert time in UTC.

---

## U-6 build: FAIL

### What the coordinator's brief describes vs what the tree holds (read 16:37-16:42)

| | Reviewed (Kavya round 2, Kabir G-2/G-3, Arjun's browser check) | On disk now |
|---|---|---|
| `ConsentScreen.tsx` | Case A, `CONSENT_TEXT_VERSION = 'v2'` | **Case B**: "Influora saves a copy until you delete it. Your briefs and your conversations are deleted separately." (L51, and the hi-IN body). **`CONSENT_TEXT_VERSION = 'v3'`** (L40). Changed 16:32:23. The class javadoc now describes the U-7 switch |
| `ConsentScreen.test.tsx` | Case A exact bodies, pins `v2` | Case B exact bodies, pins **`v3`** (L42), bans Case A wording (L33-36). Changed 16:33:29 |
| `api.ts` mock | `consent_version: 'v2'` | **`'v3'`** (L6646) |
| `CreatorAgentPreferences.java` | `"v2"` | `"v2"` (L56). Unchanged |
| Delete route that Case B's promise needs | — | **None.** `CreatorBriefController.java` maps only `POST`, `GET`, `GET /{briefId}`, `POST /{briefId}/dismiss` (L122, L132, L150, L158). U-7 backend is not built |

So the tree fails in three ways:
- It breaks the version sequence Round 3 §3 set.
- It is split-version against itself: text `v3`, backend `v2`. That is the in-tree form of what G-1 exists to stop at deploy: a creator who accepted `v2` would never re-consent to Case B.
- It makes the deletion promise Kabir said the notice must not make until the route ships.

Nothing is committed or deployed, so no creator is harmed. But the artifact under this last call no longer exists, and the one that does cannot be committed.

### Falsifications on the backend half (the part still as reviewed)

| # | Wrong version | Result | Red line |
|---|---|---|---|
| M2 | `isConsentAccepted()` ignores the stored version (L411 → `return consentAcceptedAt != null;`) | **RED**, 22 run / 1 failure | `CreatorAgentPreferencesServiceTest.staleConsentVersionIsNotAcceptedAndRecordConsentRestamps:316 expected: <false> but was: <true>` |
| M1 | `CURRENT_CONSENT_VERSION` put back to `"v1"` (L56) | **SURVIVED**, 105 run / 0 / 0 across `CreatorAgentPreferencesServiceTest`, `CreatorAgentControllerTest`, `AdminCreatorAgentControllerTest`, `CreatorBriefControllerTest`, `CreatorMeeraControllerTest`, `MeeraContextServiceTest`, `CreatorMeeraToolControllerTest`, `InfoBarrierRuntimeTest` | Nothing pins the value. Grep agrees: every `"v1"`/`"v2"` literal in `influora-api/src/test` is fixture DTO data |

- **M1 is consistent with R-U2 as I wrote it.** R-U2 said "the suite run is the proof" of the bump.
- **The value is now the weak point.** Today's tree shows how easily the two halves drift, so the missing pin is what UF6-2 closes.

### What has to change before U-6 passes

**UF6-1 (blocking; ananya builds, arjun sequences the commits): the Wave U commit carries the reviewed Case A + `v2`, byte for byte.**
- **Restore for that commit:**
  - `ConsentScreen.tsx`: Case A bodies from `NISHA-CONSENT-0917.md` "Final — Case A (v2)" (L116 en, L132 hi), and `CONSENT_TEXT_VERSION = 'v2'`.
  - `ConsentScreen.test.tsx`: exact-equality bodies for Case A, the `v2` pin, and the G-3 class test.
  - `api.ts` mock: `'v2'`.
- **The Case B + `v3` edits already made are not lost.** Keep them as a separate change for the U-7 commit, with Vikram's `v3` constant and the `DELETE /creator/briefs/{id}` route (Round 3 §3).
- **Do not fold U-6 into U-7 by default.** Round 3 kept U-7 off Wave U's path. Folding would make Wave U wait for the U-7 backend, which is Arjun's call to make explicitly, not a side effect of a shared working tree.
- **Re-check (kavya):**
  1. Diff both bodies against Nisha's Case A, character for character.
  2. Change one Devanagari character and show the hi-IN exact-equality assertion red.
  3. Set the version pin to `'v3'` and show the version assertion red.
  4. Quote the green file-level count.
- **Arjun's 375×553 and 200% browser check** was run on Case A, so it stands for Case A. It must be **re-run for Case B** at U-7, because the Case B Hindi paragraph is longer.

**UF6-2 (required before the Wave U commit; ananya builds, vikram reviews): one test that ties the three consent versions together.**
- **What it checks:** a vitest that reads `influora-api/src/main/java/com/influora/domain/entity/CreatorAgentPreferences.java`, extracts the value from the declaration line only (`public static final String CURRENT_CONSENT_VERSION = "…";`, anchored), and asserts it equals `CONSENT_TEXT_VERSION` and the `api.ts` mock's `consent_version`.
- **Show it red three ways:**
  1. Today's split: TS `v3`, Java `v2`.
  2. The mock alone differs.
  3. A **comment** in the Java file containing `CURRENT_CONSENT_VERSION = "v9"` must not satisfy it, because a grep gate that matches its own comment has happened in this repo before.
- **Why I am raising this from "worth doing" to required.** R-U2 called this test "not a B0 blocker". The tree drifted today within an hour of Kabir's approval, and M1 shows nothing else would catch it.
- **It does not replace G-1.** It keeps one commit honest; G-1 keeps one deploy honest.

**Not changed by this verdict:**
- The Case A words: Nisha approved them, and Kabir approved them with G-2 and G-3.
- G-1 stays a release gate. The backend `v2` and the Case A text go out in the same deploy. At S-2, a `v1`-stamped account gets `consent_accepted: false` and sees all three paragraphs (meera).

**Re-check scope:** UF6-1 and UF6-2 only, then back to me.

### For U-7, so the same thing does not reach its last call
- **Case B + `v3` ships only with the `DELETE` route and the settings table in the same commit.** Vikram's backend constant also goes to `"v3"` in that commit, and UF6-2 turns red if it doesn't.
- **G-3 has to be re-run on Case B hi-IN.**

---

## Heads-up item 3 (K-2b)

I ruled on it separately. It is in `RULINGS-U-0917.md`, Round 4.


---
---

# U-6 re-check (2026-09-17, 18:00-18:08)

**Scope:** UF6-1 and UF6-2 only. My earlier U-6 findings on the backend half (M2 red, the mechanism) stand.
**How:** frontend tools only. **No Maven.** Before touching `CreatorAgentPreferences.java` I confirmed from the process list that Vikram's b0 Maven (PID 31796) had finished; the only Maven running was in `C:\kb2`, another worktree. I also confirmed that nothing but `consent-version-sync.test.ts` reads that Java file as text.

## Verdict

| Item | Verdict | Why, in one line |
|---|---|---|
| **U-6** (build) | **PASS** | Case A + `v2` is back byte for byte on all three frontend surfaces, and both new guards go red against wrong versions I wrote myself. Put back to `"v1"`, the constant that passed 105 consent tests before now fails the sync test. |

**G-1 stays a release gate** (meera, at S-2): the backend `v2` and the Case A text go out in the same deploy. A `v1`-stamped account gets `consent_accepted: false` and sees all three paragraphs.

## UF6-1: Case A + `v2` restored

A program, not a reading, compared the `body` literals parsed out of `ConsentScreen.tsx` against Nisha's "Final — Case A (v2)" (`NISHA-CONSENT-0917.md` L99-136) and against `git show HEAD:src/components/meera/ConsentScreen.tsx`:

| | Paragraphs | Paragraphs 1-2 identical to HEAD | Paragraph 3 identical to Nisha Case A | NFC | Format/space characters other than U+0020 | Curly quotes |
|---|---|---|---|---|---|---|
| en-IN | 3 | yes | **yes** (294 chars) | yes | none | none |
| hi-IN | 3 | yes | **yes** (317 chars) | yes | none | none |

- Nisha's file carries each Case A paragraph twice. Both copies are identical, so the comparison is not against a stale copy.
- **Versions:**
  - `CONSENT_TEXT_VERSION = 'v2'` (`ConsentScreen.tsx` L41)
  - the `api.ts` mock `consent_version: 'v2'` (L6651)
  - backend `CURRENT_CONSENT_VERSION = "v2"` (`CreatorAgentPreferences.java` L56, sha256 `14bca5f1…`, unchanged since my first U-6 call)
- **Case B is out of this build.** The G-3 `max-h-[calc(100dvh-2rem)] overflow-y-auto` class stays. The Case B/v3 edit is parked in `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/U7-caseB-v3.patch` (16:50) for U-7.

## Falsifications

Each wrong version was restored from a byte copy and checked by sha256.

| # | Wrong version | Result | Red line, quoted |
|---|---|---|---|
| G2-ADD | One extra Hindi sentence appended to the hi-IN body (`… Meera पढ़े। Meera आपकी मदद करेगी।`) | **RED**, 1 failed / 4 passed | `ConsentScreen.test.tsx:82` `AssertionError: expected 'Meera आपकी AI मैनेजर है। …' to be 'Meera आपकी AI मैनेजर है। …' // Object.is equality`. It failed on the **exact-equality** line, after the three `toContain` checks (L76-78) passed. That is G-2's own failure mode, a silently **added** line |
| M1 | Java `CURRENT_CONSENT_VERSION = "v1"` (my earlier survivor, 105/0/0) | **RED**, 1 failed / 6 passed | `consent-version-sync.test.ts:88` `AssertionError: ConsentScreen.tsx's CONSENT_TEXT_VERSION ("v2") does not match the backend's CURRENT_CONSENT_VERSION ("v1"): expected 'v2' to be 'v1'` |
| M2 | Indirection: `CURRENT_CONSENT_VERSION = CONSENT_V1;` with `CONSENT_V1 = "v1"` declared just above | **RED**, fails closed | `consent-version-sync.test.ts:82` `Error: Expected exactly one CURRENT_CONSENT_VERSION declaration, found 0` |
| M3 | Decoy: a string literal `DOC_EXAMPLE = "public static final String CURRENT_CONSENT_VERSION = \"v2\";"` placed above the real declaration, which is set to `"v1"` | **RED** | `:88` `… CONSENT_TEXT_VERSION ("v2") does not match the backend's CURRENT_CONSENT_VERSION ("v1")`. The decoy was not taken as the value |

- **Why Kavya's check 2 did not prove G-2.** Her Hindi one-character change (`KAVYA-U6-RESTORE-REVERIFY-0917.md` check 2) failed on a `toContain` line, before execution reached the exact-equality assertion. So the assertion G-2 added had not been shown red on its own until G2-ADD. It now has.
- **Cases already shown by others.** Ananya's (a)/(b)/(c)/(d)/(f) and Kavya's mock `'v1'` and pin `'v3'` cases I did not repeat. Ananya is right that (e), the trailing-comment case, is a regression guard and cannot be a falsification.

## Note on the parser (LOW, non-blocking, ananya)

`stripJavaComments` removes `/* … */` across the whole file **before** it knows about strings (`consent-version-sync.test.ts` L44). A future string literal containing `/*`, such as a glob `"/creator/*"`, would swallow code up to the next `*/`.
- **Every failure I could construct is closed** (`found 0` or `found 2`), never a silent wrong value. So it is safe, but it could someday redden a correct file.
- **If it ever fires,** make the block-comment pass string-aware, the same way the line-comment pass already is.

## Final runs (restored tree)

- `npx vitest run` on `ConsentScreen.test.tsx` and `consent-version-sync.test.ts`: `Test Files 2 passed (2)`, `Tests 12 passed (12)`.
- All five files I backed up match their pre-check sha256:
  - `ConsentScreen.tsx` `a0383ecf…`
  - `ConsentScreen.test.tsx` `08764030…`
  - `consent-version-sync.test.ts` `9af12662…`
  - `api.ts` `c077e838…`
  - `CreatorAgentPreferences.java` `14bca5f1…`
- `git diff -- src` hashed `074e81da…` before and after. `grep FALSIFY` in `src` and `influora-api/src/main` → 0.
- For the full-suite count I rely on Ananya's run (1338 passed, plus the known `creator-protected-route` timeout passing when run alone). I did not repeat it.

**Wave U commit reminder, unchanged:** the U-7 saved-briefs UI now in the tree (`SavedBriefsSection`, `api.creatorBriefs.delete`, its tests and stubs) is not part of U-6 and must stay out of the Wave U commit, along with `U7-caseB-v3.patch`.
