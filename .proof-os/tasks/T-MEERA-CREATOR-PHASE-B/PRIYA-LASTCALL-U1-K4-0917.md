# Priya last call: U-1 (`get_brief`) and K-4 (consent accepts only the version-aware boolean)

**From:** Priya (CTO)
**To:** Arjun. Builder: vikram. QA: kavya. Copy: kabir, meera
**Date:** 2026-09-17, written 15:15
**Tree:** `influora-b0`, branch `feat/meera-creator-phase-b0`, uncommitted on `df20091`
**Bar applied:** `RULINGS-U-0917.md`, "Last-call bar", U-1 items 1-10, as written. For K-4: the general bar (every guard test shown failing against a wrong version first).

## Verdicts

| Item | Verdict | Why, in one line |
|---|---|---|
| **U-1** | **FAIL** | Bar item 3: nothing proves the HTTP client honours the 40s timeout or the no-retry flag. Both wrong versions pass all 949 tests. Items 1 and 8 each have one small gap too. |
| **K-4** | **PASS** | The one-line fix is right, a wrong version turns 6 tests red, and Spring always sends the boolean, so no consented creator gets locked out. |

The U-1 fix list below is small. It needs **no change to production logic**: two test files, one assertion, one sentence of description, and one javadoc paragraph.

---

## How this was checked

- I read every U-1 and K-4 source and test file named below. I did not touch `src/`, `New Influora`, `RULINGS-U-0917.md` or git. No stash, no commit.
- Eleven falsifications, each restored from a byte copy and checked by sha256 (table below).
- **Final Maven**, full suite, compiled from the restored source (870 main, 342 test), 14:53-14:58:
  `Tests run: 3096, Failures: 0, Errors: 0, Skipped: 25` / `BUILD SUCCESS` / exit 0.
  After the J4-J6 restores I recompiled again. U-1 classes: `Tests run: 95, Failures: 0, Errors: 0, Skipped: 0`. Then GetBriefExecutorTest and CreatorBriefServiceTest: `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0` (15:13).
- **Final pytest**, restored tree: `949 passed`.
- **Restoration:**
  - The four tracked files I changed match their starting sha256.
  - `GetBriefExecutor.java` matches `fd5864ad…`.
  - `git diff -- influora-api influora-ai` hashes to `a586ffa8…` both before and after, at `23 files changed, 1105 insertions(+), 97 deletions(-)`.
  - The repo-wide `git diff --stat` did change during my run, from 39 files / 2265 insertions to 40 files / 2335 insertions. All of that change is in `src/`, which Ananya is editing now.
- `python ci/stale-comment-check.py --since df20091` → `OK`.

## Falsifications

| # | Wrong version | Result | Red line, quoted |
|---|---|---|---|
| J1 | Stale NEW handed back untouched: `readOrReanalyse` L348-349 → `return toResponse(brief, null)` (the suggested falsification; it hits the `deal_id` path too) | **RED**, 38 run / 2 failures / 2 errors | `CreatorBriefServiceTest.get_staleNewBriefIsReanalysedNotReadUntouched:610 expected: <ANALYZED> but was: <NEW>`; `GetBriefExecutorTest.dealId_staleNewBriefIsReanalysedNotReturnedUntouched:290 ApiException: This brief has no readable analysis yet`; same at `briefId_…:326`; `get_budgetDerivesFromConfiguredAiTimeouts:651` |
| J2 | `analysisBudget()` hardcoded to `Duration.ofSeconds(30)`, ignoring the properties | **RED** | `get_budgetDerivesFromConfiguredAiTimeouts:649 ApiException: Still reading this brief — ask again in a moment` |
| J3 | `@Transactional(readOnly = true)` put back on `get` (L282) | **RED** | `get_isNotTransactional:565 get must not be transactional … expected: <null> but was: <@…Transactional(… readOnly=true …)>` |
| J4 | AI call made inside the young-NEW branch, before the 409 | **RED on brief_id, GREEN on deal_id** | `GetBriefExecutorTest.briefId_youngNewBriefIsRefused:312 NoInteractionsWanted … found these interactions on mock 'meeraBriefAiClient' -> at CreatorBriefService.readOrReanalyse(CreatorBriefService.java:343)`. **`dealId_secondCallOnAYoungNewBriefIsRefused` stayed green.** |
| J5 | `ensurePlatformBrief` L248 put back to `findById(collaborationId)` | **RED**, 38 / 4 / 1 | `CreatorBriefServiceTest.ensurePlatformBrief_anotherCreatorsDeal:503 Expected com.influora.common.ApiException to be thrown, but nothing was thrown.`; `GetBriefExecutorTest.anotherCreatorsDealIdIs404:462` (got `Brief not found`, not `DEAL_NOT_FOUND`) |
| J6 | Addition A's flags half dropped: `GetBriefExecutor` L174 → `if (brief.extraction() == null)` | **RED** | `GetBriefExecutorTest.analyzedWithNullFlagsIsRefused:371` (Kavya already did the extraction half) |
| P1 | K-4: the timestamp branch put back in `consent.py` L55 | **RED**, 6 failed / 38 passed | `assert not chat_route.consent_accepted({"consent_accepted_at": "2026-09-03T14:30:00Z"})` → `AssertionError: assert not True`; `test_unconsented_creator_speak_…[timestamp-only-no-version-aware-flag]` → `assert 200 == 403`; the transcribe variants → `AttributeError: 'dict' object has no attribute 'status_code'` (the route answered instead of refusing) |
| P4 | `loop.py` L597 `and tool_name not in CREATOR_NO_RETRY_TOOLS` removed, the tuple left alone | **RED**, 1 failed / 21 passed | `assert spring.calls[0]["allow_retry"] is False` → `E  assert True is False` |
| **P2** | `spring.py` L187-188 removed: the 40s `httpx.Timeout` is built but never sent, so the read falls back to `spring_read` (5s) | **SURVIVED** | full suite `949 passed` |
| **P3** | `spring.py` L163 → `attempts = settings.retry.max_retries + 1`, so `allow_retry=False` is ignored | **SURVIVED** | full suite `949 passed` |

Two notes:
- **J1:** the untouched NEW row did not reach the model as a clean brief. At the executor, Addition A stopped it with a 409. Both layers are proven in one run.
- **P4:** Kavya's round-1 check 4a emptied the tuple, so it failed on the first line, `assert GET_BRIEF in CREATOR_NO_RETRY_TOOLS`. The assertion that checks behaviour had never been shown red. P4 closes that.

---

## U-1 against the bar, item by item

| # | Bar item | Status | Evidence |
|---|---|---|---|
| 1 | Both paths through `get`: young NEW → 409 with no analysis; stale NEW → one analysis, ANALYZED; ANALYZED → no AI call | **Gap** | Stale, both paths: J1 red. Young on `brief_id`: J4 red. **Young on `deal_id` asserts only the 409.** `GetBriefExecutorTest` L254-257 never checks the AI count after the second call, and J4 stayed green. ANALYZED: `briefIdReadsTheStoredSnapshot` L166 and `dealIdCreatesThenReusesThePlatformBrief` L213 (read) |
| 2 | Addition A: missing or unreadable analysis refused | Met | J6 red, plus Kavya's C1 red. DISMISSED+NULL L344; ANALYZED with null flags L360; `[]` still reads L413 |
| 3 | Addition B: `allow_retry=False` on the named 40s timeout; the test fails if the tool leaves the set or its timeout falls back to `spring_read`; budget tracks the properties | **NOT MET** | The loop call site is proven (P4 red, Kavya 4b red). The budget is proven (J2 red). **The client layer is not proven:** P2 and P3 survive. `read_timeout_override` (`spring.py` L138, L166-176, L187-188) is new code in this change with no test at all, and the retry guard at L163 is what "the safety property rests on no retry" relies on |
| 4 | Addition C: no `@Transactional` on `get` | Met | J3 red |
| 5 | Ownership: 404s, both/neither ids 400, no AI call and no row on refusal | Met | J5 red. `anotherCreatorsBriefIdIs404` L437 stubs the unscoped finder too. Both ids 400: L509. Neither: L525. `verify(briefRepository, never()).save` and `verifyNoInteractions(briefAiClient…)`: L465-466 |
| 6 | Seams green | Met | `MeeraContextServiceTest` 13/0/0 (route-set equality moved first, L336-355). `FloorBarrierTest` 2/0/0. `test_creator_context_drift.py` and `test_tool_schema_anthropic_valid.py` collected (48) and inside the 949 passed |
| 7 | Stale text fixed | Met | `api.ts` L7062-7063 now says re-analysed. `creator_schemas.py` L206-222. `assembler.py` L191-196 and L754-756. `PROMPT_VERSION` → `meera-2026.09.10.3` (`config.py` L69) |
| 8 | Kavya's LOWs: both ids refused in the description; degraded reason carried; card drawn and never clean when unanalysed | **Gap** | Description: met (L210-213). Card: met (`CreatorToolResultRenderer.tsx` L720-747 shows NEW as "Still reading"; the backend refuses null analysis). **Degraded reason: not carried.** `GetBriefResult` (`CreatorToolDtos.java` L130-138) has no such field. The executor builds its result at L127-135 without it. The renderer's comment (L698-702) left it to Vikram, and no decision was ever recorded |
| 9 | Residuals 1-4 in javadoc | Met | `CreatorBriefService.readOrReanalyse` javadoc, including the compare-and-set follow-up that needs no migration |
| 10 | Live after S-2 | Owed | Restated below. It blocks "done", not this code pass |

### What has to change before U-1 passes (owner: vikram)

**UF-1, bar item 3 (blocking): prove the client layer.** Add a test of `SpringInternalClient.call_tool_endpoint` over `httpx.MockTransport`. Inject the transport the way `tests/providers/test_sarvam_tts.py` L55-60 does, by wrapping the real `httpx.AsyncClient` in `functools.partial(..., transport=...)`. The client must still be built by its own `__init__`, or the default-timeout control proves nothing.
- (a) The handler raises `httpx.ReadTimeout`. With `allow_retry=False`: exactly **1** request, and `SpringCallError.code == "network_error"`. Control: `allow_retry=True` makes `settings.retry.max_retries + 1` requests, so the count cannot pass vacuously.
- (b) `read_timeout_override=40.0`: the handler sees `request.extensions["timeout"]["read"] == 40.0`. With no override it sees `settings.timeouts.spring_read`.
- **Show both red lines:** P2 (delete L187-188) must turn (b) red, and P3 (drop `if allow_retry else 1` at L163) must turn (a) red.

**UF-2, bar item 1 (blocking): one assertion.** In `dealId_secondCallOnAYoungNewBriefIsRefused`, after the second call's `assertThatThrownBy` (L254-257), add `verify(briefAiClient, times(1)).extract(any(), any(), any());`. That one call is the first attempt's. Show J4's red line on it.

**UF-3, bar item 8 (blocking, and I am amending my own item, openly).**
- **The amendment.** As written, "a degraded reason is carried" is not met. It also cannot be met honestly without new storage. `degraded_reason` is not persisted: the migration keeps only `extraction_source` (`V20260910100100__creator_briefs.sql` L10-11). Every `get_brief` reads a stored snapshot, and on a deal's first read `ensurePlatformBrief` throws away the analysis response (`CreatorBriefService.java` L264-265) before `get` re-reads the row. A field filled only on the rare read that re-analyses would give the same brief different reasons on back-to-back reads. That is a worse signal than none.
- **Closure for B0:**
  1. `extraction_source` is the degraded marker `get_brief` carries (it does, L138).
  2. Add one sentence to the `get_brief` description in `creator_schemas.py`: when `extraction_source` is `FALLBACK`, the summary is rule-based and was not read by Meera, and she must say so rather than present it as her reading. It rides the `.3` bump already in this change, since nothing has shipped at `.3`.
  3. Add a paragraph to `GetBriefResult`'s javadoc saying why the reason is not on this result.
- **Follow-up, not B0:** persist `degraded_reason` next to `extraction_source` in a new migration, and read it in `toResponse` (ticket against B0-52, owner vikram, Wave C-2).
- This amendment does not change the verdict. UF-1 alone fails U-1.

**Recommended in the same pass, not a condition:** the refusal messages work against the description.
- `GetBriefExecutor.java` L177 says "no readable analysis **yet**". The description says this case will not heal.
- `CreatorBriefService.java` L345 says "ask again in a moment". The description says don't call again this turn.

The model sees `{"error": code, "message": …}` (`loop.py` L638), so the description should name both codes, and the "yet" should go. No test asserts either message (grep).

**Noted, no action:** `requireCollaborationIsHers` runs after `get` (`GetBriefExecutor.java` L122-124). A stale NEW PLATFORM row that names another creator's deal would be re-analysed before the 404. No such row can exist: only the old unscoped `ensurePlatformBrief` could create one, it had no caller, and the table has no real rows.

**Re-check scope:** kavya runs the P2, P3 and J4 wrong versions against the new tests, quotes the red lines, then quotes the green file-level counts. Then back to me for **items 1, 3 and 8 only**. Everything else in this verdict stands.

---

## K-4: PASS

- **The fix:** `influora-ai/app/auth/consent.py` L55, `return creator_context.get("consent_accepted") is True`. The module docstring (L14-32) and `voice.py` L122-128 say the same.
- **Falsified (P1):** 6 red across both test files. Restored: 44 green, and 949 green on the full run.
- **Tests:** the flipped tests now refuse a timestamp alone and a timestamp with an explicit `false`, in three places:
  - `test_chat_creator_audience.py` L403-420
  - `test_voice_consent.py`, route level, both voice routes, L208-223
  - `test_voice_consent.py`, shared helper, L381-387
- **Chat shares the helper by identity:** `chat_route.consent_accepted is consent_module.consent_accepted` (`test_voice_consent.py` L374), and `chat.py` L512 calls it.
- **No consented creator is locked out:**
  - Spring sends `consent_accepted` as a primitive `boolean` (`MeeraContextDtos.java` L199), so it is never omitted.
  - Spring computes it version-aware (`MeeraContextService.java` L308, `prefs.isConsentAccepted()`).
- **Nothing else in `influora-ai/app` reads `consent_accepted_at`.** Grep hits are only the K-4 comments.
- **Findings:** none.

---

## Decisions asked for

### U-1 and K-2: U-1 does not wait on K-2
U-1 closes on its own bar, once UF-1 to UF-3 are done. K-2 stays tracked as the blocker on **U-2's last call**, as I ruled earlier.

**Why:**
- K-2 is in the analysis step, not the read. The null text is at `DealRiskService.java` L287-288, and its one caller, `CreatorBriefService.java` L464, sits under paste, a deal's first read and the stale re-analysis.
- U-1 returns exactly what that step froze. Holding U-1 protects nobody: B0 cannot ship without U-2, and U-2 does not pass without K-2.

**Two conditions follow from it:**
1. **Test through the real rules.** K-2's regression test must run the real `DealRiskService.evaluateExtraction` rules, not only a mocked service with an argument assertion. Use Kabir's case: AI extraction with both hints false, text holding "UPI" and "no #ad", and both non-dismissible flags present. It must go red against today's `null`.
2. **Snapshots are frozen.** Any brief analysed on staging before K-2 lands keeps its missing flags forever. Delete those staging rows before any live check, and run U-1's live check on a build that includes K-2.

### Kavya's CI note: fix it now, owner vikram
Add `"influora-ai/app/tools/creator_schemas.py"` to `PROMPT_SOURCES` (`ci/stale-comment-check.py` L66). Do it in the Wave U commit, the same one as the bump.

**Why now and not a ticket:**
- It is one entry.
- This change already hit the blind spot and was saved only by a manual bump.
- D-1's `draft_reply` edits the same file next, so a ticket would land after the change it exists to catch.

It is **not** a condition of U-1's pass. It **is** a condition of committing Wave U.

**Falsify it in three steps, and kavya confirms:**
1. On a scratch copy, before the edit: a diff touching only `creator_schemas.py` with the `PROMPT_VERSION` line reverted passes rule 3. This proves the gap.
2. After the edit: the same scratch diff prints the rule-3 finding naming `creator_schemas.py`.
3. Restored: `OK`.

### U-1's owed live check, in one line, for tracking
**U-1 live (meera, after swapnil's S-2, on a build with K-2):** on the live stack, one cold `get_brief` by `deal_id` for a deal with no brief row, inside a real Meera chat turn, returns ANALYZED with non-null extraction and flags on the first call. No 409 and no `network_error`, the stream stays alive to `done`, and the new row's `created_at` equals its insert time in UTC.

---

## Not checked
- The live stack and the live server's own compose/JDBC time zone. Item 10 covers them.
- `src/`: U-4 and U-5 cards and the `api.ts` mock. I only read `CreatorToolResultRenderer.tsx` and `api.ts` L7059-7065 and changed nothing.
- `AuthRateLimitFilter`'s `creator-brief-get` bucket was not re-falsified. Kavya's round-2 over-match red line stands.
