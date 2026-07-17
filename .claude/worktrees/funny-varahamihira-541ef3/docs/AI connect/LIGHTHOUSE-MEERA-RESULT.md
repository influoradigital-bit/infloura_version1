# ✅ Lighthouse — /brand/meera — ACTUAL MEASURED RESULT (2026-07-05)

> Closes the open §8 item in `MEERA-DEVOPS-VERIFY.md` ("🔴 NOT VERIFIED / NOT RUNNABLE").
> The DoD item is **FRONTEND-BUILD-SPEC-MEERA.md §9 → "Lighthouse ≥85 mobile; no layout shift"**.
> Priya-approved tooling install (`wiki/tech/approved-deps.md`). Real numbers, no fabrication.

## How it was measured (the "another way to get an unauthenticated crawl through")
The `?demo=true` bypass (`src/App.tsx:48`) is gated on `import.meta.env.DEV`, a compile-time
constant that is **`false` in a production build** — Kabir's A2 control dead-strips it. So a plain
`npx lighthouse .../brand/meera?demo=true` against `vite preview` **redirects to `/brand/login`**
and scores the wrong page (independently confirmed by two runs — one hit `NO_FCP`, one landed on
`/brand/login`).

Fix: drive the already-installed system Chrome with **puppeteer-core**, seed
`localStorage.brand_token` (what `ProtectedRoute` actually checks), **clear the HTTP cache** so it's
a true cold first-visit, then run Lighthouse with `disableStorageReset:true` so the token survives.
No source is modified — this measures the exact shipped production bundle. Committed as the repeatable
gate **`ci/lighthouse-meera.mjs`** (`npm run lh:meera`).

Config: Lighthouse 12.8.2, mobile preset (Moto-G-class screen, simulated Slow-4G / 4× CPU),
`vite preview` of a fresh `npm run build`. Final URL verified = `/brand/meera` (not login) on every run.

## Result — 10 cold runs
| Category / metric | Value | Gate | Status |
|---|---|---|---|
| **Performance** | **81** median (range **66–84**, n=10; never ≥85) | ≥ 85 | 🔴 **FAIL** (short by ~4–19 pts) |
| **CLS (no layout shift)** | **0.009** (deterministic every run) | ≤ 0.01 / ≈0 | ✅ **PASS** |
| Accessibility | 95 | — | (recorded per Priya) |
| Best Practices | 100 | — | (recorded per Priya) |
| SEO | 82 | — | (recorded per Priya) |
| FCP | ~3.2 s | — | |
| LCP | ~3.4 s | — | |
| TBT | 120–730 ms (score-variance driver) | — | |
| Speed Index | ~3.2 s | — | |

Performance scores observed: 66, 73, 78, 80, 81, 82, 82, 82, 84, 84. Variance is entirely TBT
(the two lowest were run while the machine was busy installing/reverting deps concurrently). CLS,
A11y, Best-Practices, SEO were identical across all runs.

## Verdict
- **"Lighthouse ≥85 mobile" → FAILS.** Median ~81, best single run 84, never reached 85.
- **"No layout shift" → PASSES.** CLS 0.009 — effectively zero, far under the 0.1 "good" threshold.

## Culprit (why Performance falls short) — measured on THIS route
Cold-load network for `/brand/meera` = **4 requests, 455 KiB**, dominated by a single chunk:
- **`index-*.js` — 1,530 KB raw / 423 KiB gzip transferred.** One monolithic entry chunk. `App.tsx`
  statically imports all ~40 pages, so the whole app's JS ships in one file regardless of route.
- **`unused-javascript`: ~290 KiB estimated savings (~1,500 ms).** Most of that bundle is unused on
  the Meera route — the direct symptom of no route-level code-splitting.
- Downloading + parsing/executing 1.53 MB of JS on simulated Slow-4G drives **FCP ~3.2 s, LCP ~3.4 s,
  TBT up to 730 ms** (JS bootup ~1.2 s, main-thread work ~3.5 s).

**Important nuance vs. the build-verify report:** that report flagged *two* large chunks —
`index-*.js` (1.53 MB) **and** `PerformanceMonitor-*.js` (891 KB, three.js/R3F). On `/brand/meera`
specifically, **only `index-*.js` loads** — the 891 KB `PerformanceMonitor` chunk is code-split and
**not on this route's critical path** (the R3F `EscrowLockScene` was deferred; the SVG lock ships
instead). So for the Meera score, the monolithic `index` chunk is the *sole* culprit — optimizing
`PerformanceMonitor` would not move this route's number.

## Highest-leverage fix (for Ananya / Priya)
**Route-based code splitting** in `src/App.tsx`: convert the ~40 static `import X from '@/pages/...'`
into `React.lazy(() => import(...))` behind a `<Suspense>`. That alone would cut the 423 KiB critical
bundle to the Meera route's slice + shared vendor and should clear ≥85 comfortably. Optional:
`build.rollupOptions.output.manualChunks` to split heavy vendor libs (framer-motion, recharts, radix).

## Artifacts
- `ci/.lighthouse/meera-mobile.report.html` / `.json` — full report from the committed gate.
- Re-run any time: `npm run build && npm run preview -- --port 4173` then `npm run lh:meera`
  (or `LH_ORIGIN=http://localhost:<port> node ci/lighthouse-meera.mjs`).
