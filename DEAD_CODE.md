# DEAD_CODE.md — RESOLVED (F-0042)

**Status:** ✅ closed 2026-08-02 · proof-os F-0042 → `gates/dead_exports.py`
**Tool chain:** `ts-prune` candidates → whole-repo cross-verification → batched deletion → tsc/eslint/vitest gate after each batch → fixpoint gate.

## Outcome

| | Count |
|---|---|
| ts-prune raw candidates (original) | 132 |
| **False positives** (ts-prune wrong — kept) | **59** |
| **Genuinely dead — deleted** | **88** |
| Remaining dead exports | **0** (gate-verified) |

Deletion happened in three cascade layers (removing an export orphans its only
consumer): **73 primary + 12 first cascade + 3 second cascade = 88**. Whole files
removed: **16**.

## Why the 59 were NOT deleted (the cross-verification saved the build)
Raw `ts-prune` was ~45% false-positive on this repo. Deleting on its word alone
would have broken the build and tests. Excluded because they ARE referenced:
- **Type exports** — `Proposal`, `Contract`, `Wallet`, `WalletTransaction`, `Deliverable` (`src/lib/types.ts`) used across many components.
- **Barrel re-exports** — `components/3d/index.ts`, `motion/index.ts` (`DiscoverCanvas`, `FadeUp`, `TiltCard`, …) consumed via the barrel.
- **Test-only** — `escrowApi`, `marketingApi` (used by `api-contract.test.ts`); deleting breaks the test.
- **Hooks used elsewhere** — `useScrollPin`, `useAdminSocket`.

## Verification (all independently gated, not self-scored)
- `.proof-os/gates/dead_exports.py` — **aligned (proved), 0 genuinely-unreferenced exports**; proved bidirectionally (exit 1 on an injected dead export).
- `tsc -p tsconfig.json --noEmit` — exit 0.
- `vitest run` — 313/313 pass (baseline unchanged).
- `react_hooks.py` + `react_hooks_immutability.py` — still green (F-0039/F-0040 intact).

## Regression guard
`gates/dead_exports.py` now catches this class forever: it runs `ts-prune`, then
cross-verifies every candidate against `src/` + `scripts/ci/trendspark/public`,
and fails on any export with zero references anywhere. A test-only reference
counts as a live reference (so test-covered exports are never flagged).

## Not in scope (noted, not touched)
- Root-level `lib/types.ts` (outside `src/`, excluded from `tsconfig.json`) carries
  its own orphaned `MediaFile`/`RevisionFeedback` — disconnected dead weight, not a
  reference. Separate cleanup if desired.
- The 178→~172 eslint unused *locals* (`no-unused-vars`) are a different list; this
  task removed unused *exports* only.
