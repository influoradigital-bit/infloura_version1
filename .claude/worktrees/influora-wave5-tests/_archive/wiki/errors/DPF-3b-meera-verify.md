# DPF-3b — Meera Local Verification (Loop 2, mark-posted hardening)

Date: 2026-07-13
Verifier: Meera (independent — did not trust Vikram's reported numbers, reproduced from scratch)

## Scope
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `validatePlatformPostUrl` (~L412-465), charset `[A-Za-z0-9_=&%.-]`, `MAX_POST_URL_LENGTH = 500` guard.
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` — 42 tests.

Chain so far: Kabir ✅ (no encoded-XSS vector), Kavya ✅ (over-restriction from loop 1 resolved).

## Commands run (Maven offline, `C:\Users\Sage world\.maven\apache-maven-3.9.6\bin\mvn.cmd`)

### 1. `mvn -o compile`
```
BUILD SUCCESS (3.351s) — "Nothing to compile - all classes are up to date."
```
**PASS**

### 2. `mvn -o test -Dtest=CreatorDeliverableServiceTest`
```
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.977 s
BUILD SUCCESS
```
**PASS — 42/42, matches expected count exactly.**

### 3. `mvn -o test` (full suite)
```
Tests run: 927, Failures: 0, Errors: 1, Skipped: 0
BUILD FAILURE
```
Sole error: `DatabaseConstraintIntegrationTest » IllegalState Could not find a valid Docker environment.` — this is the pre-existing Testcontainers/Docker-unavailable failure on this local machine (no Docker Desktop running), unrelated to DPF-3b. Not a code regression.

**Independently reproduced: 927 total / 0 failures / 1 error (Docker-only) — matches Vikram's reported 927/0F/1E exactly.**

## Spot-confirmed the 5 new DPF-3b test cases (read source, not just count)

| # | Case | Test method (line) | Result |
|---|------|---------------------|--------|
| 1 | `%20`-accept (`&ab_channel=My%20Name`) | `testMarkPostedAcceptsAbChannelParam` (L860-863) | present, accepts |
| 2 | `youtu.be`-accept (`&feature=youtu.be` + separate short-URL `youtu.be/abc123` L737-746) | `testMarkPostedAcceptsFeatureYoutuBeParam` (L866-869) | present, accepts |
| 3 | regex-valid, >500 chars → 400 | anonymous test using `longVideoId = "a".repeat(520)` (L842-844) via `assertMarkPostedRejected` | present, rejects w/ INVALID_POST_URL/400 |
| 4 | quote-XSS rejects (double-quote + single-quote/tag-breakout tails) | `testMarkPostedRejectsDoubleQuoteXssTail` (L871-875), `testMarkPostedRejectsSingleQuoteXssTail` (L877-881) | present, both reject |
| 5 | `%3Cscript%3E`-opaque-accept | `testMarkPostedAcceptsEncodedScriptTagAsOpaqueString` (L883-890) | present, accepts (documents FE must-never-decode constraint) |

Shared helpers `assertMarkPostedAccepted`/`assertMarkPostedRejected` (L892-922) verify status transition, `postUrl` echo, and — on reject — that deliverable stays `APPROVED` and `save()` is never called. Confirms the charset fix (`%` and `.` re-admitted per Kavya P1) and the length guard both work as intended, and the `<script>`/quote-breakout raw-char paths are still closed.

## VERDICT: ✅ ALL PASS — DPF-3b confirmed, ready for Priya final review

- Build: clean
- Targeted tests: 42/42
- Full suite: 927/0F/1E (Docker-only, pre-existing, unrelated to this change)
- All 5 new hardening test cases verified present and passing by reading source, not just trusting the count

No veto. Routing to Priya via Arjun for final sign-off.
