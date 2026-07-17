# 🔧 MEERA — STAGE 4 LOCAL VERIFICATION: Meera AI Cofounder Workspace (M2.5)

> **From:** Meera (DB/DevOps + Local Verifier) · **To:** Arjun (Eng Lead), Kabir (next in pipeline) · **Date:** 2026-07-05
> **Task:** Verify `/brand/meera` workspace build after Ananya's build + Kavya's QA pass
> **Scope:** Build health, type check, lint, tests, bundle size sanity, Lighthouse DoD item

Context read before running anything: `docs/AI connect/ANANYA-BUILD-NOTES.md` (full, incl. §6–8 voice/interactive addenda), `docs/AI connect/FRONTEND-BUILD-SPEC-MEERA.md` §9 (Definition of Done).

Environment: Windows 11, Node/npm via Git Bash, repo root `C:\Users\Sage world\Downloads\New Influora Ai\New Influora`. This is a **Vite + React 19 + TS SPA** — no backend/API routes in this repo (`lib/api.ts` has no AI endpoints per Ananya's notes), so the `/api/*` curl checks in my standard protocol do not apply to this task. Substituted with a `vite preview` route-serve check instead (see Step 5).

---

## Commands run

### 1. `npm install`
```
up to date, audited 325 packages in 4s
31 packages are looking for funding
2 vulnerabilities (1 low, 1 high)
```
**Result: ✅ PASS.** No install errors, no peer-dependency conflicts. (Note: 2 pre-existing audit findings, 1 low/1 high — not investigated this pass, out of scope for a frontend-slice verification; flagging for Arjun/Priya if not already tracked. Not caused by the Meera build — zero new deps were added per Ananya's sign-off checklist.)

### 2. `npx tsc --noEmit`
```
src/components/motion/FadeUp.tsx(32,8): error TS2745: This JSX tag's 'children' prop expects type 'never' ...
src/components/motion/FadeUp.tsx(32,12): error TS2322: Type 'string | undefined' is not assignable to type 'never'.
src/components/motion/WordReveal.tsx(21,13): error TS2745: This JSX tag's 'children' prop expects type 'never' ...
src/components/motion/WordReveal.tsx(21,17): error TS2322: Type 'string | undefined' is not assignable to type 'never'.
```
**Result: ✅ PASS (expected pre-existing errors only).** Exactly 2 error *locations* (4 error lines, 2 per file — `FadeUp.tsx:32`, `WordReveal.tsx:21`), matching Ananya's build notes precisely (framer-motion generic-type strictness on a polymorphic `motion.create(Tag)` call). **Confirmed: zero NEW errors introduced by the Meera slice.** Ananya's M4 fix (removing `EscrowLockSequence`'s dependency on `FadeUp`) is real — the Meera code itself doesn't touch these two files.

### 3. `npm run build`
```
▲ [WARNING] Duplicate key "baseUrl" in object literal — ../../tsconfig.json:20-21 (pre-existing)
vite v6.4.2 building for production...
✓ 3929 modules transformed.
dist/assets/index-CRKDVUde.css               219.42 kB │ gzip:  31.81 kB
dist/assets/Float-BI92WyBh.js                  1.43 kB │ gzip:   0.77 kB
dist/assets/login-scene-3d-DBeGBMHn.js         1.97 kB │ gzip:   0.96 kB
dist/assets/HeroGlobe-BBM31y92.js              2.59 kB │ gzip:   1.31 kB
dist/assets/MeshDistortMaterial-kEmWJ4Sn.js    2.66 kB │ gzip:   1.24 kB
dist/assets/PortfolioCanvas-CTdrgZSr.js        3.01 kB │ gzip:   1.37 kB
dist/assets/DiscoverCanvas-B39p8ext.js         3.09 kB │ gzip:   1.44 kB
dist/assets/Html-BSW38nMI.js                   7.73 kB │ gzip:   3.12 kB
dist/assets/PerformanceMonitor-CC2nxta2.js   891.81 kB │ gzip: 240.72 kB
dist/assets/index-UHxiQFfO.js               1,530.32 kB │ gzip: 431.95 kB
(!) Some chunks are larger than 500 kB after minification.
✓ built in 21.82s
```
**Result: ✅ PASS.** Build time 21.82s (within Ananya's reported 20-31s range across her runs). Only the two known pre-existing warnings: (1) `baseUrl` duplicate key in `tsconfig.json` (root config issue, unrelated to Meera), (2) the chunk-size warning on the main `index-*.js` bundle and the `PerformanceMonitor-*.js` (R3F/three.js) chunk — both pre-existing large vendor bundles per Ananya's notes. **No new warnings, no new large chunks.**

### 4. Bundle-size sanity check
Listed `dist/assets/` sorted by size — the two largest chunks (`index-UHxiQFfO.js` 1.53MB, `PerformanceMonitor-CC2nxta2.js` 891.8KB) are the same pre-existing vendor/3D chunks called out in Ananya's build notes as unrelated to this slice (three.js/R3F is not newly pulled in — `EscrowLockScene.tsx` R3F version was explicitly deferred/not built, per her notes §2). No new oversized chunk introduced. Everything else is small (1.4–7.7 kB range). **No regression evident.**

### 5. Route-serve sanity check (substitute for API curl — no backend in this repo)
```
npm run preview -- --port 4173
curl -s -o /dev/null -w "HTTP_STATUS:%{http_code}\n" http://localhost:4173/brand/meera
→ HTTP_STATUS:200
```
**Result: ✅ PASS.** `/brand/meera` resolves 200 via the SPA's client-side router (Vite serves `index.html` for all routes; React Router v7 handles `/brand/meera` client-side). This is the correct check for a Vite SPA with no server routes — the original protocol's `curl /api/products` / `/api/health` checks don't apply since `lib/api.ts` has no backend endpoints yet (mock-first, confirmed in Ananya's notes §2 gap list item 8).

### 6. `npm run test`
**Result: ⚪ N/A — no test script exists.** Checked `package.json` `scripts` block directly: only `dev`, `build`, `preview`, `lint` are defined. No test runner (Vitest/Jest) is installed or configured anywhere in the repo. This is a pre-existing gap, not something this Meera build introduced — flagging to Arjun since the project has zero automated test coverage today.

### 7. `npm run lint`
```
> eslint .
'eslint' is not recognized as an internal or external command, operable program or batch file.
```
**Result: ❌ FAIL — but this is a pre-existing repo misconfiguration, not a Meera-slice defect.** `eslint` is referenced in the `lint` script but is **not listed anywhere in `package.json` `devDependencies`**, and there is no `eslint.config.js` in the repo root (confirmed via direct `npx eslint .` attempt, which offered to auto-install `eslint@10.6.0` and then failed separately because ESLint v9+ requires a flat `eslint.config.js`, which doesn't exist here). This predates the Meera build — not caused by Ananya's changes. **Per my authority, I did not `npm install eslint` without approval.** Escalating to Arjun: either wire up ESLint properly (add dependency + flat config) or remove the dead `lint` script — this is a devex gap that should get its own ticket, not block this ship decision.

### 8. Lighthouse ≥85 mobile (DoD item, FRONTEND-BUILD-SPEC-MEERA.md §9)
**Result: 🔴 NOT VERIFIED — honest status, no fabricated score.**

Checked for Lighthouse tooling on this machine:
- `which lighthouse` → not found in PATH
- `node_modules/.bin/` → no `lighthouse` binary (not a project devDependency)
- `npm ls -g` → not installed globally either

Per my DevOps authority rules, I do **not** install new tooling (`npm install -g lighthouse` or as a devDependency) without Priya's approval — this is exactly the kind of unapproved-install case the rules call out.

**What's needed to actually verify this DoD item:**
1. Approval from Priya (or Arjun on her behalf) to add `lighthouse` (CLI or `lighthouse-ci`) — as a one-off global install or a devDependency, whichever she prefers for repeatability.
2. Command once available, run against a static preview server (not `vite dev`, which is unoptimized/unminified and will under-report):
   ```
   npm run build
   npm run preview -- --port 4173
   npx lighthouse http://localhost:4173/brand/meera?demo=true \
     --emulated-form-factor=mobile \
     --output=json --output=html \
     --output-path=./lighthouse-meera-mobile
   ```
3. Report the Performance category score specifically (the DoD says "Lighthouse ≥85 mobile" — should confirm with Priya/Arjun whether this means Performance only or all four categories).
4. Because `/brand/meera` sits behind the app's protected route group (per Ananya's notes, App.tsx routes it "inside the protected group"), the Lighthouse run will need either a `?demo=true` bypass (which Ananya's own verification notes show exists and was used for her live-preview testing) or an authenticated session/cookie passed to the headless Chrome instance — need to confirm the demo-mode bypass is sufficient for an unauthenticated Lighthouse crawl.

**No layout shift (CLS) DoD item** is bundled with the same Lighthouide report (CLS is a Lighthouse/Core Web Vitals metric) — also not independently verified for the same reason. Ananya's own notes report a live `boundingBox` inspection showing no shift for the `MeeraPresence` slot specifically (§8 item 8), which is good targeted evidence, but that's a manual spot-check of one component, not a full-page CLS measurement.

---

## Definition of Done — verification coverage (FRONTEND-BUILD-SPEC-MEERA.md §9)

| DoD item | Verified this pass? | Notes |
|---|---|---|
| All colors reference tokens — zero raw Tailwind color classes | ⚪ Not independently re-verified | Ananya self-reports grep-clean in her notes; this is a code-review item (Kavya's job), not a build/run check |
| `--accent` derives from `--brand`; green/danger/warning never themed | ⚪ Not independently re-verified | Same — functional/visual QA territory |
| `useReducedMotion()` bypasses every animation; count-ups snap; lock shows final state | ⚪ Not independently re-verified | Requires live browser interaction/emulation, not a build check |
| One WebGL context; PerformanceMonitor + SVG fallback; DPR [1,1.5] | ✅ Partially — build confirms zero R3F `EscrowLockScene` shipped (deferred per Ananya), so zero WebGL-context risk from this slice | |
| No content hardcoded — comes from `src/data/*` | ⚪ Not independently re-verified | Code-review item |
| Mobile tested at 375px — no overflow, sheet works, Pay CTA reachable | ⚪ Not independently re-verified this pass | Ananya reports live verification; not re-run by me |
| **Lighthouse ≥85 mobile; no layout shift** | 🔴 **NOT VERIFIED** | See full explanation above — tooling not installed, needs Priya approval before I install anything |
| Motion constants from `data/motion-tokens.ts` | ⚪ Not independently re-verified | Code-review item |
| `npm run build` clean | ✅ **VERIFIED** | See Step 3 |
| `npx tsc --noEmit` — only 2 pre-existing errors | ✅ **VERIFIED** | See Step 2 |

Items marked ⚪ are functional/visual/code-review checks that fall under Kavya's QA scope, not Meera's build-verification scope — noting them here only to be explicit about what "build verification" does and doesn't cover.

---

## Lighthouse Run (Priya-approved) — 2026-07-05

Priya approved a scoped, ephemeral `npx lighthouse` run to close this DoD gate: allowed to pull Lighthouse transiently via `npx`, **not** allowed to add it (or anything else) as a permanent dependency.

### Setup
```
npm run build                       # fresh prod build
npm run preview -- --port 4173      # vite preview, serves the real prod bundle
```
Both completed clean (build in 23.8–27.8s across runs, identical bundle sizes to the prior pass — no regressions).

### Command run
```
npx lighthouse "http://localhost:4173/brand/meera?demo=true" \
  --only-categories=performance --form-factor=mobile --screenEmulation.mobile \
  --throttling-method=simulate --quiet \
  --chrome-flags="--headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage" \
  --max-wait-for-load=60000 \
  --output=json --output-path=./lighthouse-meera.report.json
```

### What actually happened
This route **could not be measured**, and I'm not reporting a number for it. Root cause, confirmed by reading the source, not guessed:

`src/App.tsx` lines 44–48 gate `ProtectedRoute` with:
```
const isDemoMode = import.meta.env.DEV && new URLSearchParams(window.location.search).get('demo') === 'true';
```
`import.meta.env.DEV` is a Vite compile-time constant. `npm run preview` serves the **production** build, where Vite statically resolves this to `false` — the whole demo-bypass branch is dead-stripped. There is no unauthenticated path to `/brand/meera` in a prod build.

Empirical confirmation (two independent Lighthouse runs against `/brand/meera?demo=true`):
- Run 1: Chrome never got First Contentful Paint (`runtimeError: NO_FCP`) — `categories.performance.score: null`.
- Run 2 (same URL, hardened Chrome flags, longer wait): audit completed without error, but `finalDisplayedUrl` was `http://localhost:4173/brand/login` — i.e. Lighthouse measured the **login page** (client-side redirect fired), not the Meera workspace. Score in that run was 0.81/CLS 0, but that number describes `/brand/login`, not `/brand/meera` — reporting it as "the Meera score" would be fabricated. Discarded.

Sanity check that the toolchain itself works in this environment: ran Lighthouse against the public `/` route — first attempt also hit `NO_FCP` (this Windows box has a flaky headless-Chrome-under-load issue, independent of the auth gate), second attempt with hardened flags succeeded (0.46 performance on `/`, no error). So Lighthouse + headless Chrome + this box *can* produce real numbers, but not for a route it can't reach.

**Also observed:** Lighthouse's own teardown (deleting its temp Chrome profile) intermittently throws `EBUSY: resource busy or locked, unlink '...\Default\Account Web Data'` on this Windows machine after the audit completes — a known chrome-launcher-on-Windows file-lock/AV-contention issue. Cosmetic; doesn't invalidate a completed audit, but adds to the flakiness.

### VERDICT: 🔴 NOT RUNNABLE HERE for `/brand/meera` — auth gate blocks it, by design, in a prod build
No fabricated score reported. What's needed for a real number:
1. **A CI job with a seeded auth session** — e.g. a Playwright/Puppeteer script that does `localStorage.setItem('brand_token', '<seeded-token>')` (or hits a real/mock login endpoint) before invoking Lighthouse, using Lighthouse's **user-flow / Node API** (`lighthouse-user-flow` or a Puppeteer-driven `startFlow`) rather than the plain CLI, since the CLI's single-navigation model can't inject storage before its own navigation.
2. Alternative: add a build-time (not runtime) flag — e.g. a Vite mode that keeps the demo bypass alive for a `lighthouse-ci`-only build — if the team wants a CLI-only check without standing up Puppeteer auth seeding. This is an architecture decision, not mine to make; flagging to Priya/Arjun.
3. Exact command once auth is seeded (same as attempted above, unchanged):
   ```
   npx lighthouse "http://localhost:4173/brand/meera" --only-categories=performance \
     --form-factor=mobile --screenEmulation.mobile --throttling-method=simulate \
     --chrome-flags="--headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage"
   ```
   (drop `?demo=true` once real auth is seeded — it's dead code in prod anyway.)

**Cleanup performed:** deleted all temp `lighthouse-*.report.json` files and parse scripts from scratchpad; killed the `vite preview` process.

**Dependency scope check — IMPORTANT FINDING:** the `npx lighthouse` invocation, on this npm/npx version, silently wrote `lighthouse` **and its transitive tree (`puppeteer-core`, plus, separately, a full eslint toolchain: `eslint`, `@eslint/js`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`, `globals`, `typescript-eslint`)** into `package.json` devDependencies, `package-lock.json`, and `node_modules` — npx did **not** behave as a purely ephemeral/isolated run here. This exceeded Priya's approval scope (ephemeral only, zero permanent deps). Caught it via a post-run `grep lighthouse package.json` check, reverted `package.json` devDependencies to the exact pre-existing list, and ran `npm install` to prune `package-lock.json`/`node_modules` back to the original 325-package baseline (confirmed: `removed 302 packages, audited 325 packages` — matches the original install count exactly). Re-ran `npm run build` post-revert to confirm no regression. **Flagging to Arjun/Priya:** if lighthouse needs to run again, prefer `npx --no-install=false lighthouse@<version> ...` run from **outside** the project root, or a dedicated CI runner/Docker step, so a stray `npm install`/lockfile write can't touch this repo's committed dependency tree again.

**Recurrence note:** this same pollution (`lighthouse` + `puppeteer-core` back in `devDependencies`) reappeared in `package.json` a second time after the first revert+rebuild, before I'd finished writing this doc — re-caught and re-reverted the same way (`npm install` after removing the two lines, confirmed back to the 325-package baseline, confirmed clean build). Something in this environment (an autosave/linter/background process touching `package.json`, or a residual npx background write) is re-triggering the write. If this keeps happening, treat any Lighthouse run on this box as needing a **post-hoc `git diff`/`grep lighthouse package.json` check every time**, not just once — a git repo here would make this trivially auditable instead of manual diffing.

---

## VERDICT

**BUILD: ✅ PASS**
**TSC: ✅ PASS — 2 pre-existing errors only (FadeUp.tsx, WordReveal.tsx), zero new**
**TEST: ⚪ N/A — no test script/runner in this project**
**LINT: ❌ FAIL (tooling broken, pre-existing, not a Meera-slice defect) — escalating to Arjun**
**LIGHTHOUSE ≥85 mobile: 🔴 NOT RUNNABLE HERE — `/brand/meera` is behind an auth gate that a prod build cannot bypass; needs CI job with seeded auth session (see above). Zero permanent deps added — confirmed and reverted after an npx side-effect exceeded scope.**

### Overall gate decision
Build/type-check/route-serve all pass clean with no regressions — from a pure "does it run" standpoint this slice is solid and I am not blocking on those. However, I **cannot sign off the full §9 Definition of Done** because:
1. The Lighthouse ≥85 mobile + no-layout-shift item is explicitly unverified (not faked, not assumed).
2. The `lint` script is broken at the repo level (pre-existing, but still a red flag that should get fixed before this ships to Kabir/Priya, since "lint clean" is an implicit baseline expectation even though it's not explicitly in the §9 list).

**Routing:** Passing build/tsc results to Arjun for the pipeline to proceed to Kabir (security). Flagging the Lighthouse gap and the broken `lint` script as two separate open items — neither blocks Kabir's security pass (unrelated concerns), but **both must be resolved before Priya/Swapnil final sign-off**, since §9 explicitly lists Lighthouse ≥85 as a DoD checkbox.

— Meera
