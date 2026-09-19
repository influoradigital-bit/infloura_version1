# K-5 last call: raw_text / summary_lines redaction (kabir, 2026-09-19)

**done_when, verbatim:** "A log record or dict that carries raw_text or summary_lines (the full pasted brief and its AI summary, which can hold names, emails, phones and UPI ids of people who never consented) is redacted wherever the influora-ai key-based redaction applies, and a test goes red when either key is removed from the redaction list."

## Verdict: MET. Final: PASS

| Clause | Result | Evidence |
|---|---|---|
| A dict or log record carrying `raw_text` / `summary_lines` is redacted wherever key-based redaction applies | **MET** | Probe P1: a name plus a UPI VPA under both keys comes out as `{"type":"str","len":97}` / `{"type":"list","count":2}`. P3b: the same when nested (`brief.raw_text`, `brief.extraction.summary_lines`). |
| A test goes red when either key is removed | **MET** | M1 (drop `raw_text`) is red at `test_redaction.py:260`. M2 (drop `summary_lines`) is red at `test_redaction.py:261`. Each key fails on its own. |

The artifact is uncommitted. At the start and at the end of this check, the files were at these hashes:
- `influora-ai/app/security/redaction.py` sha256 `44ba0aaf…0535bd`
- `influora-ai/tests/security/test_redaction.py` sha256 `ca8ca0db…58d4a`

## What I read
- `redaction.py` in full. `test_redaction.py` in full. `git diff HEAD` for both files (+11 / +79).
- Every `log_event(` / `logger.*(` / `print(` call site in `influora-ai/app`, which is 20 files.
- `routes/brief_extract.py` L1662-1883 as I first read it, which is the route and all of its log lines. Another lane is editing that file right now, and the same lines have since moved to about L1720-2040. Every `brief_extract.py` line number below is from my first read. `providers/claude.py` L395-425, the forced-tool error path. `tools/loop.py` L680-720, `_redact_tool_input` / `_safe_json`. `main.py` in full.
- Pinned `anthropic==0.42.0` `.venv/.../anthropic/_base_client.py` L452 and `_utils/_logs.py` `setup_logging`.
- The `LOG_LEVEL` value in `env.example` and in all four deploy compose files.
- `KABIR-CONSENT-0917.md` L23 and L182 (the K-5 origin).

## How I ran it
- Scratch copy: `…\scratchpad\k5-ai\`, made by tar of `influora-ai` without `.venv` or `__pycache__`. Its sha matched the worktree for both artifact files.
- Mutation harness: `…\scratchpad\k5_mutate.py`.
  - It mutates in binary mode and deletes the whole line, CRLF included. It asserts exactly one match.
  - It clears every `__pycache__` before each run and runs with `PYTHONDONTWRITEBYTECODE=1`.
  - It restores from the original bytes and sha-checks the restore after every mutant.
  - Full output is in `…\scratchpad\k5-mutation-runs.txt`.
- Command for each run: `python -m pytest tests/security/test_redaction.py -q -p no:cacheprovider -rfE --tb=line` (system Python 3.13.3, pytest 8.3.4).
- Baseline: **15 passed**. After restore: **15 passed**. Restored sha `44ba0aaf4be881e6dc40028663fef4dae001d564c62f15cd0e356c9dd90535bd` equals the original.

## Mutations and red lines

| # | Mutation | Result | Red line |
|---|---|---|---|
| M1 | Delete `"raw_text",` from `_REDACT_KEYS` | **RED**, 1 failed / 14 passed | `test_redaction.py:260: AssertionError: assert 'Brand here. Pay via UPI, confirm on [REDACTED_PHONE], PAN [REDACTED_PAN].' == {'len': 67, 'type': 'str'}` |
| M2 | Delete `"summary_lines",` | **RED**, 1 failed / 14 passed | `test_redaction.py:261: AssertionError: assert ['Brand asked...ACTED_PHONE]'] == {'count': 1, 'type': 'list'}` |
| M3 | Delete both | **RED**, 1 failed / 14 passed | `test_redaction.py:260` (same as M1) |
| M4 | Keys kept, but the key branch is made dead (`if False and str(key).lower() in _REDACT_KEYS:`) | **RED**, 3 failed / 12 passed | `:119` (pan), `:228` (stream_token), `:260` (raw_text) |

The failing test in M1-M3 is `test_k5_known_sensitive_keys_include_raw_text_and_summary_lines`.

### Test weakness (non-blocking)
- **The red comes only from the shape-equality asserts at L260 and L261.**
  - Under M1-M3, the leak asserts at L257-259 (`fake_brief` / `FAKE_PHONE` / `FAKE_PAN not in serialized`) stay green. The fixture contains only PII that the regexes already catch (phone, PAN), so the scrubbed string still satisfies `not in`.
- **`test_k5_end_to_end_log_line_never_contains_pasted_brief_text` stays GREEN under M1, M2, M3 and M4.**
  - Its fixture is email + PAN, both caught by the regexes. So it does not test key-based redaction at all.
- **Suggested fix:** put a plain name and a UPI VPA (for example `Ravi Shankar Iyer`, `ravi.iyer@okaxis`) in both fixtures.
  - The regexes do not catch either one (probe P2), so the leak asserts would go red on removal.
  - This matters because names and UPI ids are the reason K-5 exists. The current fixtures prove the key is present, but they do not show that it is the only thing stopping a name from leaking.

## Is `_REDACT_KEYS` the only key-based path? Does anything else print these fields unredacted?

**It is the only key-based redaction path.**
- A grep for `redact|scrub|sanitiz|mask|_SENSITIVE` across `influora-ai/app` finds no second key set.
- `_redact_for_log` is called only from `RedactionJsonFormatter.format` on `record.fields`. That formatter is installed on the root logger by `configure_logging` (`main.py:34`).
- `loop.py::_redact_tool_input` is a plain `dict()` copy. It is not a redaction, and it goes to the SSE client, not to logs.
- The message string and `exc_info` go through `scrub_text` (regex) only.

**Nothing in `influora-ai/app` logs or prints these fields unredacted today.**
- `brief_extract.py` has three brief-bearing log lines, and all of them pass shapes:
  - `"raw_text": shape_of(raw_text)` in `brief_extract_started`
  - `"summary_lines": shape_of(...)` in `brief_extract_completed`
  - the provider-failure line, which logs `provider_error`. `claude.py` sets that to the literal `"provider_error"` and logs only `type(exc).__name__`.
- There are no `print(` calls. No `logger.*` call in `loop.py`, `chat.py`, `claude.py` or `spring.py` carries brief text. `logger.exception` in `brief_extract.py:1860` prints a traceback without local values.
- The only serializer is the route's response body (`{"success": True, "data": extraction}` → Java), and that is the intended output.

### Residuals found (outside this done_when, none blocks it)

1. **LOW: the anthropic SDK DEBUG log prints the whole pasted brief.**
   - What happens: the pinned `anthropic 0.42.0` `_base_client.py:452` runs `log.debug("Request options: %s", model_dump(options))`. The dump contains the full outbound `messages`, including the wrapped pasted brief (and every chat turn).
   - Why redaction misses it: the brief sits inside a formatted **message string**, so key-based redaction never sees it. `scrub_text` strips emails, phones and PAN, but not names or UPI VPAs.
   - Proof: probe P5 ran against the pinned 0.42.0 through the real `RedactionJsonFormatter`. The line kept `Ravi Shankar Iyer` and `ravi.iyer@okaxis`.
   - Current exposure: none. `LOG_LEVEL=INFO` in `env.example` and in all four compose files (hostinger, hostinger test, utho, utho-shared).
   - What turns it on: `LOG_LEVEL=DEBUG` or `ANTHROPIC_LOG=debug`.
   - Suggested fix: pin `logging.getLogger("anthropic").setLevel(logging.INFO)` inside `configure_logging`, whatever the root level is.
2. **INFO: camelCase keys bypass the key match.**
   - `rawText` / `summaryLines` leak (probe P3), because the match is exact after `.lower()` (`"rawtext" != "raw_text"`).
   - Not live: those spellings have zero occurrences in `influora-ai/app`, and Java sends `raw_text` in the snake-case body.
3. **INFO: shapes at the existing brief_extract call sites no longer show length or count.**
   - Those call sites already pass shapes, so the value that reaches the redacted key is a dict. The log now reads `raw_text: {"type":"dict","key_count":2}` and `summary_lines: {"type":"dict","key_count":2}` (probe P4). Before K-5 they read `{"type":"str","len":N}` and `{"type":"list","count":N}`.
   - The brief length and the summary-line count disappear from `brief_extract_started` and `brief_extract_completed`.
   - This is the same existing pattern as `captions` (`brand_safety.py:319`) and `audio` (`voice.py:479`), and it is not a security defect.
   - Suggested fix: rename those two call-site keys (for example `raw_text_shape`), in the brief_extract lane, not this one.

## Full-suite regression check (scratch copy)
Harness: `…\scratchpad\k5_fullsuite_diff.py`, output in `…\scratchpad\k5-fullsuite-diff.txt`.

| Run | Result |
|---|---|
| With K-5 | 36 failed, 1116 passed, 1 skipped |
| With both keys removed | 38 failed, 1114 passed, 1 skipped |

**No test fails only because of K-5** (that set is empty). The failures present in both runs are:
- **10 drift tests** (`test_creator_context_drift` 2, `test_k3_dto_field_classification_drift` 8). They need the Java tree, which the scratch copy does not have.
- **25 `test_brief_extract.py` failures.** They are grounding content asserts, from a snapshot of `brief_extract.py` taken while another lane was editing it (the file grew by about 160 lines during this check). That lane was not touched.
- **The f14 heartbeat timing tests.** They are flaky: with the artifact unchanged, isolated reruns went 1 pass and 2 fails. The only extra failure in the keys-removed run is one of these.

## Did not touch
- No Java, no `src/`, no brief_extract files.
- No `git stash`, no commit.
- Nothing in the `New Influora` tree.
- Every mutation was made in the scratch copy only.
