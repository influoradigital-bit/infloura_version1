# T-MEERASNAP-0920 — Frontend investigation (Ananya, read-only)

Repo: New Influora, branch feat/meera-creator-phase-e, HEAD a76ea77.

## 1. Is 'funding'/'live' reachable in live mode?

REFUTED as reachable — confirmed dead in live mode. Only two things ever call `advance()`
(`src/hooks/useMeeraStage.ts:38-45`, `setStage(stageForFunctionCall(call))`):

- `MeeraChatPanel.tsx:996` `onFunctionCall(STAGE_TO_CALL[turn.triggersStage])` — inside
  `handleMockSend` (:976), gated by the whole mock-turn engine. Mock-only.
- `MeeraChatPanel.tsx:880-881` `if (event.status === 'ok' && isMeeraFunctionCall(event.name)) onFunctionCall(event.name, event.data)`
  — the live SSE tool_result handler.

`STAGE_TO_CALL` (`MeeraChatPanel.tsx:118-124`) maps `funding→request_payment`,
`live→confirm_launch`, but that map is read only by `handleMockSend`, never by the live path.
For live, `isMeeraFunctionCall` (`:170-172`) checks membership in `MEERA_FUNCTION_CALLS`
(`:160-168`), which DOES include `request_payment`/`confirm_launch` — but the comment at
`:146-151` (and stage-config.ts:16-26 for the sibling tool) states the Python tool loop never
offers Meera these two "money tools" (filtered out server-side, on-behalf token scoped without
them), so any `tool_result` for them can only arrive with `status: 'error'`. The `event.status
=== 'ok'` guard at `:880` then blocks `onFunctionCall` from ever firing for them live.

`MeeraWorkspace.tsx:83-85` `handleGoLive` calls `advance('confirm_launch')` directly, but it is
only wired as `StageFunding`'s "Approve & release" `onClick={onGoLive}` (`StageFunding.tsx:99,107`,
comment at `:36`), and `StageFunding` only mounts when `stage === 'funding'`
(`LivingCanvas.tsx:116-124`) — which the above shows is unreachable live. No independent trigger.

Checked `setStageDirect` (`useMeeraStage.ts:29,47-49`): defined but never called anywhere in
`src/` — `MeeraWorkspace.tsx:44` destructures only `{ stage, isPaid, advance, markPaid,
stagePayloads }`, dropping it. No URL param / devtools path exists today.

Conclusion: `handlePay` (`MeeraWorkspace.tsx:75-81`) is genuinely dead code in live mode — it can
only run from `StageFunding`'s Pay CTA, which cannot mount live. Context bullet 2 (funding/live
mock-only) and bullet 3 (`onFunctionCall(event.name, event.data)` at :881 is the live driver) are
CONFIRMED as stated.

## 2. What breaks if handlePay / MEERA_DEMO_CAMPAIGN_ID / funding+live stages were deleted

- `MEERA_DEMO_CAMPAIGN_ID` (`MeeraWorkspace.tsx:21`): referenced only within the same file
  (`:76-77`) plus one comment mention, `brand-wallet.tsx:1438`. No other importer. Safe in isolation.
- `handlePay`: passed as `onPay` to both `LivingCanvas` mounts (`MeeraWorkspace.tsx:111,140`) →
  `LivingCanvas.tsx:78,119` → `StageFunding.tsx:38,120` (`PayButton onPay={onPay}`). Deleting it
  requires removing the whole `onPay` prop chain through `LivingCanvasProps` (`:22`) and
  `StageFundingProps` (`StageFunding.tsx:22`).
- `handleGoLive`/`onGoLive`: same chain, `LivingCanvasProps.onGoLive` (`:24`), `StageFundingProps
  .onGoLive` (`:24`), `StageFunding.tsx:99,107` CTAs.
- `MeeraStageId` union (`stage-config.ts:7`) includes `'funding' | 'live'`; `STAGE_CONFIG` (:58-71),
  `STAGE_ORDER` (:81), and `STAGE_TO_CALL`/`MEERA_FUNCTION_CALLS` in `MeeraChatPanel.tsx` all key
  off them — removing the union members means touching all four tables plus `stageForFunctionCall`.
- `LivingCanvas.tsx` L33 (`escrowStateForStage`: `stage === 'funding'` branch) and L116
  (`stage === 'funding' && <StageFunding .../>`) plus L125 (`stage === 'live' && <StageLive
  .../>`) would need removal; `EscrowPillState` values `'securing'|'secured'|'releasing'` become
  partly orphaned (still used by `'performance' → 'secured'` at :41).
  Note: `LivingCanvas.tsx` line numbers for the funding/live JSX are 116 and 125, not "33/116" as
  a single pair — L33 is the escrow-state switch, L116 is the StageFunding mount. Both exist as
  described, just clarifying which is which.
- `isPaid`/`markPaid` (`useMeeraStage.ts:24,30,51,55`): `isPaid` also drives `isLive` (`:56`,
  `stage === 'live'`) and `escrowStateForStage` (`LivingCanvas.tsx:32-34`, `:106`). Removing
  `live`/`funding` stages orphans `isPaid`/`markPaid` entirely (no remaining reader).
- Mock turn script: `meera-mock.ts:138` union includes `'funding'|'live'` in `triggersStage`, and
  `:181` has a turn with `triggersStage: 'funding'` — the demo/mock walkthrough would need a
  scripted replacement or would stop at `matching`.
- Components that only exist for these stages: `StageFunding.tsx`, `StageLive.tsx` (imported only
  by `LivingCanvas.tsx:7-8`) become fully orphaned and deletable.
- Test file `MeeraChatPanel.credit-paywall.test.tsx` (`:5, :114`) exercises
  `onFunctionCall('request_payment')` opening `StageFunding` on a credits-exhausted path — this
  test's premise breaks if `request_payment`/`StageFunding` are removed; it needs rewriting, not
  just a value swap.

## 3. StageSnapshot.tsx L156 useBrandProfile() — CONTEXT BULLET IS WRONG

`StageSnapshot.tsx:154` computes `const live = isApiLive()` and the mock block
(`MOCK_BRAND_SNAPSHOT.logoInitials/name/siteUrl/brandColorHex/products`, lines 192/195/196/200/206)
is inside `if (!live) { return ... }` at `:187-218`. `useBrandProfile()` (`:155-156`) result
(`brandProfile, isLoading, error, refetch, analysisTimedOut, restartAnalysisPoll`) is used for
everything AFTER that early return: `analysisInFlight` (:171-174), the timed-out card (:229-242),
loading card (:244-246), error card (:248-259), `ERROR` status card (:264-274), idle card
(:279-281), and the real READY card with `siteInitials(brandProfile.websiteUrl)`,
`brandProfile.nicheTags`, `extractCatalogProducts(brandProfile.productCatalog)` (:285-319).

So: the mock is NOT a fallback for a loading/error state, and the real data is NOT discarded —
they're two fully separate, mutually exclusive branches gated by `isApiLive()`. In live mode, the
mock card at L192/195/196/200/206 never renders — the CONTEXT claim that it's "live-visible
immediately on the INITIAL stage" is FALSE for the snapshot card itself.

HOWEVER, one part of the CONTEXT bullet's underlying worry IS real: `MeeraWorkspace.tsx:42`
(`useBrandTheme(rootRef, MOCK_BRAND_SNAPSHOT.brandColorHex)`) is called unconditionally — not
gated by `isApiLive()` at all (confirmed by reading `useBrandTheme.ts:143-158`, which applies the
derived theme in a plain `useEffect` with no live/mock branch). So in live mode the whole
workspace's `--brand`/`--meera-accent*` CSS vars are still derived from the mock's
`#E8927C` hex, page-wide, regardless of the real brand's color. This is the one live-visible
mock-data leak that actually exists — it's page theming, not the snapshot card.

## 4. Tests that assert the mock values

- `StageSnapshot.site-analysis.test.tsx:61-66`: explicitly force-mocks `isApiLive: () => true` and
  comments "the mock-data branch returns MOCK_BRAND_SNAPSHOT and never touches the profile at
  all, so these states only exist when the API is live" — i.e. this test already routes AROUND
  the mock branch and asserts none of 'Kavala Skincare'/'KS'/product names. It would NOT need to
  change for a snapshot-mock fix, since it never exercises that branch.
- No other test file under `src/` matched `MOCK_BRAND_SNAPSHOT`, `'Kavala'`, or `'KS'` as an
  assertion target (grep found only source usage in `StageSnapshot.tsx`/`MeeraWorkspace.tsx`, and
  unrelated 'Kavala Skincare' placeholder-text usages in `brand-new-hype-campaign` pages/tests,
  `campaign-form.tsx`, and Remotion demo components — different feature entirely, not the Meera
  workspace). So: no existing test currently locks in the snapshot-stage mock bug.

## 5. Recommendation

(a) Wire real data into the snapshot stage's THEMING call specifically — i.e. gate/replace
`MeeraWorkspace.tsx:42`'s `useBrandTheme(rootRef, MOCK_BRAND_SNAPSHOT.brandColorHex)` with the
real live brand color once available (or the `DEFAULT_ACCENT` fallback already built into
`useBrandTheme`, never the mock hex, when `isApiLive()`). The snapshot CARD itself (`StageSnapshot
.tsx`) is already correctly live/mock-gated and needs no fix — only the page-wide theme call
bypasses that gate, and it's a one-line, low-risk fix versus removing an already-correct stage.
