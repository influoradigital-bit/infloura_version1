# MEERA-U2-TESTS-PROOF-0919

Ledger: F-0779. Worktree: `C:\Users\Sage world\Downloads\New Influora Ai\influora-b0` (feat/meera-creator-phase-b0, uncommitted).

done_when (verbatim): "PasteBriefCard's four behaviours — opening consent on a CONSENT_REQUIRED
paste, having no maxLength, scoping dismissals as BRIEF:, and hiding on FEATURE_DISABLED — each
have a test that goes red when that behaviour is removed."

Method: mutated `src/components/creator/copilot/PasteBriefCard.tsx` in place, one behaviour at a
time, with `binmut.py` (apply → run `npx vitest run <the two test files>` → confirm red → restore
→ confirm sha256 back to `8dbc20e6cdec08d46adfd1f3f5aa63bb2d548e4a933bc76eaddbb1c7bff0b4ce` → grep
for zero `FALSIFY-TEMP` markers). Baseline (pre- and post-mutation) is 25/25 green across
`PasteBriefCard.test.tsx` (15 tests) and `creator-copilot-paste-brief.test.tsx` (10 tests). Did not
touch `influora-api` or `influora-ai`, no git stash/commit, no changes to the `New Influora` tree.

## 1. Opening consent on a CONSENT_REQUIRED paste — MET

Mutation: `PasteBriefCard.tsx` line 139, `err.code === 'CONSENT_REQUIRED'` →
`err.code === 'CONSENT_REQUIRED_FALSIFY_TEMP'` (the catch block's consent branch never matches;
falls through to the generic error message and `onConsentRequired` is never called).

Red — 2 tests, both files:

```
FAIL src/pages/creator-copilot-paste-brief.test.tsx > CreatorCopilotPage — PasteBriefCard mount (U-2) > server CONSENT_REQUIRED on the paste itself opens the consent screen
FAIL src/components/creator/copilot/PasteBriefCard.test.tsx > PasteBriefCard > rejects with a server CONSENT_REQUIRED refusal (consent unknown): asks for consent, shows the message inline, and renders no brief card
  ❯ src/components/creator/copilot/PasteBriefCard.test.tsx:354:51
    expect(onConsentRequired).toHaveBeenCalledTimes(1)   -- timed out, never called
Test Files  2 failed (2)
     Tests  2 failed | 23 passed (25)
```

## 2. Having no maxLength — MET

Mutation: added `maxLength={PASTE_BRIEF_MAX_CHARS}` to the `<Textarea>` (after
`disabled={analysing}`, line ~180).

Red — 1 test:

```
FAIL src/components/creator/copilot/PasteBriefCard.test.tsx > PasteBriefCard > has no maxLength attribute on the textarea, so a long paste is trimmed by the card, not the browser
Expected the element not to have attribute:
Received:
  maxlength="8000"
Test Files  1 failed | 1 passed (2)
     Tests  1 failed | 24 passed (25)
```

## 3. Scoping dismissals as BRIEF: — MET

Mutation: `PasteBriefCard.tsx` line 224, `` riskScope={result.brief_id ? `BRIEF:${result.brief_id}` : undefined} ``
→ `riskScope={result.brief_id ? result.brief_id : undefined}` (drops the `BRIEF:` prefix, so the
paste card's scope key no longer matches `get_brief`'s `` `BRIEF:${payload.brief_id}` `` scope in
`CreatorToolResultRenderer`).

Red — 1 test:

```
FAIL src/components/creator/copilot/PasteBriefCard.test.tsx > PasteBriefCard > a flag dismissed on the paste card is also hidden on the get_brief tool card for the same brief (scope BRIEF:{id})
Test Files  1 failed | 1 passed (2)
     Tests  1 failed | 24 passed (25)
```

(The dismissal, keyed by the unprefixed `brief_01`, no longer carries over to the `get_brief`
card's `BRIEF:brief_01`-scoped store — the flag that should stay hidden reappears there.)

## 4. Hiding on FEATURE_DISABLED — MET

Mutation: `PasteBriefCard.tsx` line 142, `err.code === 'FEATURE_DISABLED'` →
`err.code === 'FEATURE_DISABLED_FALSIFY_TEMP'` (the catch block's disabled branch never matches;
`onFeatureDisabled` is never called, so the page never swaps in the calm disabled state).

Red — 1 test:

```
FAIL src/pages/creator-copilot-paste-brief.test.tsx > CreatorCopilotPage — PasteBriefCard mount (U-2) > server FEATURE_DISABLED on the paste call itself unmounts the card and shows the calm disabled state
Test Files  1 failed | 1 passed (2)
     Tests  1 failed | 24 passed (25)
```

## Verdict: PROVED

All four behaviours MET — each has at least one test in `PasteBriefCard.test.tsx` and/or
`creator-copilot-paste-brief.test.tsx` that turns red when, and only when, that specific
behaviour is removed. File restored byte-for-byte after each mutation (sha256
`8dbc20e6cdec08d46adfd1f3f5aa63bb2d548e4a933bc76eaddbb1c7bff0b4ce`, confirmed via `git status`
showing no diff beyond the pre-existing untracked-file state); zero `FALSIFY-TEMP` markers remain.
Full suite green (25/25) before and after the mutation series.
