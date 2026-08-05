# Accepted Security Risks

This file records security advisories that are **knowingly accepted** rather than fixed,
with the justification and who accepted them. Anything not listed here must be fixed.

Re-review on every major dependency bump.

---

## react-router / react-router-dom — RSC-mode CSRF (2 × high)

- **Advisory:** "React Router: RSC Mode CSRF Bypass Allows Action Execution Before 400 Response"
- **Affected range:** `react-router` / `react-router-dom` `7.12.0 – 8.2.0`
- **Fix available in:** `react-router` `> 8.2.0` (a **breaking v8 major** migration only — no 7.x patch exists)
- **Status:** ACCEPTED — not fixed
- **Accepted by:** human:Swapnil, 2026-08-03 (proof-os task `fix-shipblockers`, decision "Accept-risk waiver")

### Why it is not reachable here
This app is a **client-side SPA in react-router library mode**: `BrowserRouter` + `<Routes>`
mounted via `createRoot` (`src/main.tsx`, `src/App.tsx`). There is **no `@react-router/dev`
dependency and no RSC (React Server Components) mode anywhere in the tree** — the exact
configuration the CSRF advisory targets. The vulnerable code path cannot execute in this
build.

### Why we did not upgrade
Upgrading to react-router v8 is a breaking migration across ~60 routes, including the
protected `/brand/*`, `/creator/*`, and `/admin/*` zones — the same invasive, destabilizing
change the prerender architecture note (`scripts/prerender.mjs`) was written to avoid. The
cost/risk of the migration is not justified by an advisory that is not reachable in this
app's mode.

### What WAS fixed in the same pass (2026-08-03)
The other high-severity advisories were cleared with in-range, non-breaking bumps:
- `vite` `6.4.2 → 6.4.3` (server.fs.deny bypass, launch-editor NTLM hash disclosure)
- `postcss` `→ ≥ 8.5.18` (source-map path traversal) — via `npm audit fix`
- `brace-expansion` (DoS) — via `npm audit fix`

Result: **4 high → 2 high**, and the 2 remaining are this single, non-reachable advisory.
`tsc` and `npm run build` both stayed green (no regression).

### Re-evaluate when
- react-router ships a 7.x backport of the fix, **or**
- this app adopts RSC / framework mode (then it becomes reachable and MUST be fixed), **or**
- a v8 migration is scheduled for other reasons.
