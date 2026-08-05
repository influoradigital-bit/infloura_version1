# Deep Code Check — Ranked Findings

**Task:** Find errors, don't fix. Scope: `src/`, `app/`, `influora-api/`, `influora-ai/`
**Date:** 2026-08-02 · **Branch:** fix/brand-audit-remediation
**Rule honored:** nothing on disk modified except this report. No fixes applied.

Every finding below is backed by an **executable check** (a JVM crash dump, the
project's own `tsc`/`eslint`, `python -m compileall`, or `rg`), not by reading
code and forming an opinion. Checks that came back clean are listed at the end
so the negative space is auditable too.

## Executable checks run (evidence provenance)

| Check | Command | Result |
|-------|---------|--------|
| JVM crash dumps | read `influora-api/hs_err_pid*.log` | 4 × OutOfMemoryError |
| TS typecheck | `tsc -p tsconfig.json --noEmit` | **0 errors** (clean) |
| Python syntax | `python -m compileall influora-ai` (full tree) | **0 errors** (clean) |
| JS/TS lint | `eslint . -f json` (project config, `strict`) | **333 errors**, 37 warnings |
| Security sweep | `rg` patterns (injection/secrets/xss/deser) | see SECURITY |

`npm run lint` is currently **RED** (333 project errors). `npm run typecheck` is
green. That split is the headline: types are sound; the runtime/React-rules and
hygiene gate is not.

---

## CRASH

### C-1 — Four JVM OutOfMemoryError dumps, none previously in the ledger
**Class:** crash · **Evidence (executable = the crash itself):**
- `influora-api/hs_err_pid11648.log:2` — mmap failed to map **130 MB** ("G1 virtual space") **1.0 s** into `mvn ... -DskipTests compile`. Died before heap was even up.
- `influora-api/hs_err_pid31460.log:2` — OOM at **24 s** into `InfluoraApiApplication` **even with `-Xmx640m -XX:MaxMetaspaceSize=192m`** (see Command Line at `:36`).
- `influora-api/hs_err_pid23604.log:2` — OOM at **73 s**, GC Thread, app startup.
- `influora-api/hs_err_pid8600.log:2` — malloc failed (`Chunk::new`) at **138 s** in a **surefire** test run, C2 compiler thread.

Host line in each dump: `12 cores, 7G` RAM (`hs_err_pid31460.log:38`). Root cause
is **environmental, not application logic** — a 7 GB box cannot map G1's reserved
virtual space for compile + Spring context + surefire concurrently. This is the
concrete origin of ledger note **F-0026** ("long checks can't run … full build").
**Impact:** local `mvn compile` / app run / `mvn test` are unreliable on this
host; CI must own the Java build. **Not a code bug** — do not "fix" in source;
constrain JVM (`MAVEN_OPTS`/`-Xmx`) or move to CI. Belongs in the ledger as an
environment/tooling class so it stops resurfacing unexplained.

---

## CORRECTNESS
Executable check = project `eslint` (severity-2 errors). Ranked by runtime blast radius.

### CR-1 — Conditional React hook (can crash the component)
**Class:** correctness · `src/components/3d/PortfolioCanvas.tsx:30`
`const texture = avatarUrl ? useLoader(THREE.TextureLoader, avatarUrl) : null;`
eslint `react-hooks/rules-of-hooks`. `useLoader` is a hook called behind a
ternary. **Failure scenario:** a creator with no avatar renders (`avatarUrl`
undefined → hook skipped); their avatar loads/changes → next render calls the
hook → hook count changes → React throws *"Rendered fewer hooks than expected"*
and the Portfolio canvas unmounts. This is a real crash path, not style.

### CR-2 — Use-before-declaration inside hooks, incl. the escrow money path
**Class:** correctness · `react-hooks/immutability` ("accessed before it is declared")
- `src/hooks/useEscrowFund.ts:325` — `recheckBalanceThenRetry` self-references
- `src/hooks/useEscrowFund.ts:377` — `pollForFunded` self-references
- `src/pages/creator-chat.tsx:1035` — `setLiveDeliverables` accessed before declared

Recursive `useCallback` via `setTimeout` (`:324-326`, `:376-378`). Works at
runtime today (the `const` is assigned before any timer fires), but it defeats
React-Compiler memoization and keeps the lint gate red on the **escrow
fund/poll retry loop** — the money path. Worth fixing deliberately, not silently.

### CR-3 — 99 × synchronous setState inside useEffect (cascading re-renders)
**Class:** correctness · `react-hooks/set-state-in-effect`, **75 files**
e.g. `src/admin/hooks/useAdminSocket.ts:73,106`, `useBillingData.ts:52,119`,
`src/admin/components/support/TicketList.tsx:229`. Each fires an extra render
pass synchronously; concentrated in the admin data-hook layer. Perf/correctness
smell, not a crash — but 99 of them is a systemic pattern.

### CR-4 — 11 × impure call during render (non-deterministic output)
**Class:** correctness · `react-hooks/purity`
`Date.now()` / `Math.random()` evaluated in render bodies:
`src/pages/brand-campaign-detail.tsx:667,672`, `src/pages/creator-chat.tsx:2336`,
`src/components/brand/dashboard/dashboard-page.tsx:207`,
`src/components/ui/sidebar.tsx:611`, `src/components/3d/DiscoverCanvas.tsx:135`,
+5 more. Under React Compiler these produce unstable renders/hydration drift.

### CR-5 — 13 × ref access during render
**Class:** correctness · `react-hooks/refs` — e.g. `src/admin/hooks/useAdminSocket.ts:61`.

### CR-6 — Misleading emoji character-class (TTS leak)
**Class:** correctness · `src/lib/strip-markdown-for-speech.ts:82`
`no-misleading-character-class`. The class mixes ZWJ (`‍`) / VS16
(`️`) with ranges, so composite ZWJ emoji ("family", flags) are only
partially stripped and fragments can still reach Sarvam TTS. Low severity.

### CR-7 — Shadowing global `Infinity`
**Class:** correctness · `src/components/feature/meera/CreditMeter.tsx:18`
`import { … Infinity … } from 'lucide-react'` shadows the numeric global in that
module. Harmless as long as the file never uses numeric `Infinity` — but it's a
trap for the next editor of a credits component.

### CR-8 — Thrown error drops its cause
**Class:** correctness · `src/lib/contract-generator.ts:236`
`preserve-caught-error` — a new error is thrown without `{ cause }`, so the
original stack is lost in contract generation. Debuggability only.

---

## DEAD-CODE

### DC-1 — Entire "Contract Details" sheet is unreachable
**Class:** dead-code · `src/pages/creator-chat.tsx:2457`
`{false && ( <Sheet open={false} onOpenChange={() => {}}> … )}`
eslint `no-constant-binary-expression`. Double-dead: gated behind literal
`false` **and** `open={false}`. A whole Contract Details panel (`:2457-~2470+`)
ships but can never render. Either wire it or delete it.

### DC-2 — 178 unused vars/imports across 60 files
**Class:** dead-code · `@typescript-eslint/no-unused-vars`
Top: `src/pages/creator-chat.tsx` (15), `src/pages/brand-pipeline.tsx` (10),
`src/components/brand/discover/creator-discovery.tsx` (9),
`src/pages/brand-wallet.tsx` (9), `src/pages/creator-wallet.tsx` (7),
`src/components/creator/deal-room/creator-contract-panel.tsx` (7). Includes
unused UI imports (`CardHeader`/`CardTitle`/…), unused `setPage`, dead layout
imports (`CreatorLayout` at `src/App.tsx:76`).

### DC-3 — 6 stale eslint-disable directives
**Class:** dead-code · `no problems were reported` — e.g.
`src/components/ErrorBoundary.tsx:63`, `src/hooks/useEscrowFund.ts:328`,
`src/lib/voice-usage.ts:13`, `trendspark/n8n/tagger-sync.check.js:104`.

---

## SECURITY — no confirmed vulnerabilities from executable checks
This is a positive result, verified, not skipped:

- **Placeholder secrets fail closed.** `application.yml:247` ships a real-looking
  base64 default for `ADMIN_MFA_SECRET_ENCRYPTION_KEY`, but
  `SecretsStartupValidator.java:122-125,345` lists that exact string as a rejected
  placeholder and fails closed outside dev; dev JWT/stream/HMAC defaults
  (`SecretsStartupValidator.java:106-111`) and the Razorpay webhook placeholder
  (`:129-132`) are gated the same way. Not exploitable in prod.
- **JSON-LD injection blocked.** `src/lib/seo/schema.ts:253` escapes `<` to
  `<` before `dangerouslySetInnerHTML` (`:257`).
- **No SQL string-concatenation** in Java (`influora-api/src/main`) or Python
  (`influora-ai/app`) — `rg` for concatenated `SELECT/INSERT/UPDATE` = 0 hits.
- **No unsafe deserialization** — `pickle.loads`/`yaml.load`/`marshal` = 0 hits.
- **No disabled JWT verification, no `verify=False`, no `shell=True`/`eval`,
  no CORS `*` wildcard, no committed live `rzp_live`/`sk_live`/`AKIA` keys.**

Informational only: dev-default secrets live in `application.yml` (lines
183-302). Fine because the startup validator rejects them; flagged so it's a
conscious dependency, not luck.

---

## Ranking (most to least actionable)
1. **C-1** — 4 JVM OOM dumps (environmental; blocks local Java build/test) — *crash*
2. **CR-1** — conditional `useLoader` hook, PortfolioCanvas — *correctness (crash-capable)*
3. **CR-2** — use-before-declare in escrow fund/poll loop — *correctness (money path)*
4. **DC-1** — dead Contract Details sheet in creator-chat — *dead-code*
5. **CR-3** — 99× setState-in-effect (admin hooks) — *correctness*
6. **CR-4 / CR-5** — impure render + ref-in-render (24 total) — *correctness*
7. **DC-2** — 178 unused vars/imports (60 files) — *dead-code*
8. **CR-6 / CR-7 / CR-8** — TTS regex, Infinity shadow, lost error cause — *correctness (minor)*
9. **DC-3** — 6 stale eslint-disable directives — *dead-code*

## Coverage & limits (honest)
- **Java (880 files): not compiled** — `mvn compile` OOMs on this host (see C-1),
  so no Java-side correctness beyond the crash dumps and `rg` security patterns.
  Java findings would need a CI compile.
- **Python (1892 files):** syntax-clean only; no type checker (`mypy`/`pyflakes`
  not installed) — logic/type bugs in `influora-ai` are **not** covered.
- **TS/React:** covered by `tsc` (green) + `eslint` (333 errors above).
- `.venv/` and `node_modules/` eslint hits were excluded as third-party noise.
