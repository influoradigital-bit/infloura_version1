# Superseded gates — F-0482 reconciliation, 2026-09-02

Two sessions independently wrote gates for the same ledger classes. `promote.py` takes
exactly ONE gate path per finding, so a class with two gates cannot be closed by anyone.
These five lost that reconciliation and are kept only for reference.

| archived here | superseded by | why it lost |
|---|---|---|
| `missing_feature.py` | `missing-feature.py` | no `exit(2)` path — cannot report unavailable, so it can only pass or fail |
| `unenforced_limit.py` | `unenforced-limit.py` | same: no `exit(2)` path |
| `empty_state_misleads.py` | `empty-state-misleads.py` | narrower coverage, and the survivor has the `exit(2)` discipline |
| `empty_state_honesty.py` | `empty-state-misleads.py` | 4.6KB, no `exit(2)` path, older finding set |
| `endpoint_reachability.py` | `unreachable-endpoint.py` | survivor reads 277 user-facing endpoints to this one's 206, strips comments before counting call sites, REFUSES allowlist claims whose handler takes an `@AuthenticationPrincipal`, and DECLARES its 9 non-literal call sites instead of guessing at them |

Both gates in the unreachable-endpoint pair independently report **14** findings, which is
useful corroboration — they disagree about method, not about the answer.

The losing four were written in this session and share one flaw: no `exit(2)` path. A gate
that cannot say "I could not check" returns 0 when it checks nothing, which is the exact
false-green that the citations stub and `meta_length` produced and that this whole audit
exists to catch.

CARRY-OVER: `empty_state_honesty.py` covered F-0384, which the survivor does not name.
Confirm F-0384 is still covered before closing it.
