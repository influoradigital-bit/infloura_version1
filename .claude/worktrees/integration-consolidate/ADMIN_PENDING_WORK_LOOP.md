# ADMIN PENDING-WORK LOOP — Orchestration Driver

> **For:** Admin / orchestrator use (Arjun drives, Priya arbitrates).
> **Created:** 2026-07-11 · CTO directive.
> **Purpose:** Single driver file the agent loop reads each cycle to decide *what to build next*.
> **Sequencing rule:** **BRAND first** — every BRAND item in
> [`wiki/tech/BRAND_ADMIN_PENDING_WORK.md`](wiki/tech/BRAND_ADMIN_PENDING_WORK.md) must be `[x]`
> before any ADMIN item starts.
> **Source of truth for status:** `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` (this file points at it, never contradicts it).
> **Reporting layer:** `MASTER-BUSINESS-PLAN.md` — refreshed by **Tara every 10 minutes**.

---

## Loop mechanics (how this file is driven)

- **Work loop (self-paced):** each cycle, Arjun reads this file + Priya's assessment, picks the highest-priority
  open item in the active section, and dispatches it down the pipeline. Advances one pipeline stage per wake.
- **Tara loop (every 10 min):** Tara refreshes `MASTER-BUSINESS-PLAN.md` §3.1 / §3.3 / §7 scorecards to match
  the actual `[x]` items in `BRAND_ADMIN_PENDING_WORK.md`. **Never bumps a % without a closed item as evidence.**

### Pipeline (per item)
```
Priya (assess / arbitrate)
   └─► Arjun (assign next open item to owner)
          └─► Vikram (backend)  +  Ananya (frontend)   ── build
                 └─► Kavya (QA gate)
                        └─► Kabir (security — mandatory on money / KYC / auth)
                               └─► Meera (npm run build + mvn test — final gate)
                                      └─► Arjun marks [x] with evidence
                                             └─► Tara refreshes MASTER-BUSINESS-PLAN.md
```

### Agent roster
| Agent | Role in the loop |
|---|---|
| **Priya** | CTO. Produces the ADMIN pending-work assessment; arbitrates architecture escalations. |
| **Arjun** | COO/orchestrator. Reads this file each cycle, assigns the next open item, tracks status. |
| **Vikram** | Backend — API routes, entities, persistence, migrations. |
| **Ananya** | Frontend — replace mock/no-op handlers with real `api.*` calls. |
| **Kabir** | Security review — mandatory on anything touching money, KYC, or auth. |
| **Kavya** | QA gate — no item is `[x]` without her PASS. |
| **Meera** | Build/local verification — `npm run build`, `mvn test`; final gate. |
| **Tara** | Every 10 min, refreshes `MASTER-BUSINESS-PLAN.md` scorecards to match closed items. |

---

## ACTIVE QUEUE (as of 2026-07-11)

### ▶ PHASE 1 — BRAND (finish this entirely first)

Remaining open BRAND items (from `BRAND_ADMIN_PENDING_WORK.md` — all P0/P1 wiring already `[x]`):

| # | Item | Owner(s) | Security? | Status |
|---|------|----------|-----------|--------|
| B-1 | **Deal Room (60% → live)** — 11 prop-driven components need real persistence instead of local/external-only state | Vikram + Ananya | Kabir (deal/money-adjacent) | `[ ]` — **NEXT** |
| B-2 | **Timeline (55% → live)** — presentational only; needs a real data layer | Vikram + Ananya | — | `[ ]` |
| B-3 | **Settings / Store Integration** (P2) — backend returns `NOT_IMPLEMENTED`; build it or remove dead UI (Priya scopes) | Vikram | — | `[ ]` |
| B-4 | **Brand-initiated deal accept/reject** (P2) — `DealService.accept()/reject()` hard-gated creator-only; needs role-aware branch | Vikram | Kabir (money-adjacent) | `[ ]` |
| B-5 | **KYC collection at first campaign creation** (P2) — wire the unused `api.onboarding.submitBrandKyc` + gate decision | Ananya + Vikram | Kabir (KYC/compliance) | `[ ]` |
| B-6 | **Deduplicate source trees** (cleanup) — triplicate `components/brand` copies | Vikram | — | `[ ]` |

**Gate to Phase 2:** B-1 … B-6 all `[x]` in `BRAND_ADMIN_PENDING_WORK.md`.

### ▶ PHASE 2 — ADMIN (starts only after Phase 1 is fully `[x]`)

**Priya assessment (2026-07-11):** [`wiki/admin-progress/ADMIN_PENDING_ASSESSMENT_PRIYA_2026-07-11.md`](wiki/admin-progress/ADMIN_PENDING_ASSESSMENT_PRIYA_2026-07-11.md).
Two findings that reorder this queue:
> 1. `/admin` (`App.tsx:419`, no guard — confirmed) renders the **read-only demo** `src/pages/admin-dashboard.tsx` (fed by `lib/demo-data.ts`), **not** a live money console. The real modular `src/admin/**` panel is mounted on no route. Backend `/admin/**` is already `.authenticated()` (`SecurityConfig.java:120`). → the guard item is lower-risk than the tracker claims, but mounting the real panel is net-new work.
> 2. Most admin backend controllers **already exist** (AdminBrand/Creator/Dashboard/Support, PlatformFeeAdmin w/ MFA+optimistic-lock+audit). The dominant risk is **silent failure**: ~5 client method groups (`financeApi` revenue/escrow/payout, `moderationApi`, `campaignApi`, `brandApi.list`, `dashboardApi.financial/marketing`) point at endpoints that **don't exist** — wiring as-is calls dead routes.
>
> **Revised readiness:** ready-to-wire (both ends exist) = Dashboard pulse, Support, Users detail+KYC. FE-gap only = Finance fee-config (client missing 3 methods). Backend net-new = Campaigns, Moderation content-flags, brand/creator list views.
> **Note:** true two-person maker-checker does NOT exist (only MFA + optimistic-lock + audit) — do not record it as done.

Queue mirrors `BRAND_ADMIN_PENDING_WORK.md` PART 2 (to be re-sequenced per the assessment above when Phase 2 opens):

| # | Item | Owner(s) | Security? |
|---|------|----------|-----------|
| A-P0-1 | **Add `/admin` route guard** — `App.tsx:409` has zero auth wrapper; console is publicly routable | Vikram/Ananya (routing) | **Kabir mandatory** |
| A-P0-2 | **Wire `services/api-contracts.ts`** into the 7 dead admin hooks (client is complete, unused) | Vikram + Ananya | — |
| A-P1-1 | **Users (55%)** — wire stubbed KYC-approve etc. actions | Ananya + Vikram | Kabir (KYC) |
| A-P1-2 | **Dashboard (55%)** — `usePulseData` 100% mock → real WS/REST feed | Ananya + Vikram | — |
| A-P1-3 | **Moderation (45%)** — wire unused `moderationApi`; re-verify tests vs live | Ananya + Vikram | Kavya |
| A-P1-4 | **Support (45%)** — wire reply/assign actions | Ananya + Vikram | — |
| A-P1-5 | **Campaigns (45%)** — `CampaignTable` read-only mock → real data | Ananya + Vikram | — |
| A-P1-6 | **Finance (40%)** — `FeeControlPanel` stub; configures platform fee/escrow. **Highest-risk.** | Vikram | **Kabir mandatory (maker-checker)** |

### ▶ CROSS-CUTTING (runs alongside, non-blocking)
- **Rotate AI provider keys** — `influora-ai/.env` plaintext keys. Owner: **Kabir** (coordinate rotation + git-history check). *Rotation itself is a human action — flag to CTO, do not perform.*
- **Wire real CI** — add `mvn test` / `pytest` / `vitest` / `npm run build` gates. Owner: **Meera**.

---

## Rules
1. Do not mark an item `[x]` here or bump a % in `MASTER-BUSINESS-PLAN.md` without a corresponding `[x]` + evidence in `BRAND_ADMIN_PENDING_WORK.md`.
2. Honest stubs only — never fake success on an unwired mutation.
3. Money / KYC / auth items **must** have a Kabir PASS before Meera's final gate.
4. `BRAND_ADMIN_PENDING_WORK.md` is the status source of truth; this file is the queue view; `MASTER-BUSINESS-PLAN.md` is the report.
5. **NO DESTRUCTIVE GIT (agents + orchestrator).** Never run `git stash`, `git checkout -- <file>`, `git restore`, `git reset --hard`, or `git clean`. On 2026-07-11 a mid-session `git stash` reverted a tracked file (`brand-chat.tsx`) to HEAD and silently destroyed a completed frontend-wiring task — untracked new files survived, tracked edits did not. To inspect state use read-only git only (`git status`, `git diff`, `git log`). The `Bash(git stash *)` allow-list entry has been removed so any such call now prompts.
6. **Commit each verified increment.** After an item passes its gates, commit its files to the working branch immediately (surgical `git add <paths>`, never `git add -A`) so no stray command can lose it. This is the durable recovery point.
