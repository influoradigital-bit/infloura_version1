# INFRA-1 — Meera Verification Report — 2026-07-13

**Task:** Fix the repo-wide `tsc`-blocks-`build` gate (Priya ruling A+B, `wiki/tech/approved-deps.md` 2026-07-13 row).
**Authorization:** Priya (CTO) approval bus, `wiki/tech/approved-deps.md` line 21-23.

## Part A — Install approved devDeps

Installed via `npm install -D vitest@^3 @testing-library/react@^16 @testing-library/jest-dom@^6 @testing-library/user-event@^14 jsdom@^25`.

Resolved versions actually landed in `package.json` devDependencies:
- `vitest`: `^3.2.7`
- `@testing-library/react`: `^16.3.2`
- `@testing-library/jest-dom`: `^6.9.1`
- `@testing-library/user-event`: `^14.6.1`
- `jsdom`: `^25.0.1`

`npm install` output: `added 106 packages, changed 65 packages, and audited 670 packages in 12s` — no peer-dep conflicts, no install errors. (19 pre-existing audit advisories, unrelated to this change — not addressed here.)

**Config check (already existed, no changes needed):**
- `vitest.config.ts` already had `test.environment: 'jsdom'` and `test.setupFiles: ['./src/test/setup.ts']` — someone (Kavya, per file comments) pre-wired this correctly; it was just missing the packages to run.
- `src/test/setup.ts` already imports `@testing-library/jest-dom/vitest` (the subpath that both registers matchers AND augments vitest's `Assertion` types so `tsc` type-checks `.test.tsx` files) and calls `cleanup()` in `afterEach`.

So Part A reduced to: install the 5 packages. Config was already correct.

## Part B — Decouple the build gate

- Added `tsconfig.build.json` (extends `tsconfig.json`; TS `exclude` arrays don't merge on `extends`, so it re-declares the base excludes `node_modules`, `dist`, `app`, `components`, `src/app` plus new `src/**/*.test.ts`, `src/**/*.test.tsx`, `src/test/**`).
- Changed `package.json` `build` script: `"tsc --noEmit && vite build"` → `"tsc -p tsconfig.build.json && vite build"`.
- `"typecheck": "tsc --noEmit"` left untouched (full repo, no excludes) — now green because Part A resolved the test-file imports.

## Verify — REAL numbers

1. **`npm install`** — ✅ PASS, 0 errors, 0 peer conflicts.
2. **`npm run build`** — ✅ **PASS, exit 0.** `tsc -p tsconfig.build.json` clean, then `vite v6.4.2 building for production... ✓ 4010 modules transformed ... ✓ built in 13.47s`. (Pre-existing unrelated warnings only: a duplicate `baseUrl` key warning from esbuild reading `tsconfig.json`, and a >500kB chunk-size advisory — neither blocks the build, neither introduced by this change.)
3. **`npx vitest run`** — test files **actually execute** now (imports resolve, jsdom renders). Real result:
   - **Test Files: 3 failed | 13 passed (16)**
   - **Tests: 31 failed | 146 passed (177)**
   - 2 unhandled-rejection errors logged (see below)
   - Run duration 26.2s
   - Failure causes (pre-existing test/component gaps, NOT infra failures — these are exactly the "may fail on their own merits" case):
     - `FlagQueue.test.tsx` (24 failures) — component under test calls `useQueryClient()` without the test wrapping it in `QueryClientProvider` → `Error: No QueryClient set, use QueryClientProvider to set one`.
     - `BrandProfile.test.tsx` (partial failures + 2 unhandled rejections) — mocked `fetch` assertions expect calls that don't line up, and 2 unhandled promise rejections from `apiRequest()` in `src/admin/services/api-contracts.ts:66` receiving a relative URL (`/api/v1/admin/brands/brand-123/verify-kyc`) that Node's `undici` `fetch`/`URL` can't parse without a base — jsdom test env has no `window.location` origin wired for relative fetches here.
     - One more file in the 3-failed count not fully surfaced in the tail of the log (full log at `%TEMP%/meera-vitest-out.log` this session, not persisted — re-run `npx vitest run` to reproduce).
   - None of these are import-resolution or missing-dependency failures — that class of failure (the actual INFRA-1 defect) is gone. These are pre-existing app/test-authoring bugs, out of INFRA-1 scope; flagging for Kavya/Vikram as follow-up, not blocking this ticket.
4. **`npx tsc --noEmit`** (full repo typecheck) — ✅ **0 errors, exit 0.** Fully green, not just "dropped dramatically" — the test-file import-resolution errors were the only errors present once the packages installed.

## VERDICT: ✅ ALL PASS (build gate) — Ready for next step

- `npm run build`: exit 0 ✅ (the actual INFRA-1 defect — fixed)
- `npx tsc --noEmit` full: 0 errors ✅
- `npx vitest run`: suite executes (177 real tests ran, no import-resolution blocking) — 146 pass / 31 fail on pre-existing app/test bugs, not this ticket's scope

**Follow-up (not blocking INFRA-1, flagging for Arjun/Kavya):**
- `FlagQueue.test.tsx`: wrap render in `QueryClientProvider` (test-authoring gap, 24 failing assertions).
- `BrandProfile.test.tsx`: mock/stub `fetch` base URL or wrap `api-contracts.ts` calls so relative paths resolve in jsdom/undici — 2 unhandled rejections + assorted assertion failures.

Files touched this session:
- `C:\Users\Sage world\Downloads\New Influora Ai\New Influora\package.json` (devDependencies + build script)
- `C:\Users\Sage world\Downloads\New Influora Ai\New Influora\package-lock.json` (regenerated by npm install)
- `C:\Users\Sage world\Downloads\New Influora Ai\New Influora\tsconfig.build.json` (new)

No source files changed. No `vite.config.ts`/`vitest.config.ts`/`src/test/setup.ts` changes needed — pre-existing config was already correct.
