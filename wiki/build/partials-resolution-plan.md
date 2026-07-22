# Partials Resolution Plan — CTO ruling (Priya)

Date: 2026-07-22 · For: Arjun (routing) · Source audit: `wiki/reports/brand-feature-audit.md`
Method: traced against real code in the MAIN working tree (`feat/portfolio-view-tracking`), not the audit's prose. Two PARTIALs, one ruling each. I rule architecture; implementers write the code.

---

## PARTIAL 1 — Brand content-performance (per-post media)

**RULING: RESOLVE by mounting the existing `ContentPerformancePanel` on the existing brand creator-analytics page. Correct, low-risk, small FE task. Approved.**

### Why this is the right, low-risk move (all four links already exist)

The end-to-end chain is built; only the final "surfaced in UI" mount is missing. Verified:

1. **Backend route is live and authz-gated** (Fix #4). `AnalyticsController.java:101-105` exposes `GET /analytics/creators/{creatorId}/media` → `analyticsService.getContentPerformance(principal, creatorId)`. Same brand↔creator authz pre-read as the sibling `/metrics` and `/scores` routes already mounted on this page — no new auth surface.
2. **FE client is wired.** `src/lib/api.ts:2706-2711` `contentPerformance.list(creatorId)` calls that exact path in live mode; `ContentPerformanceItem` DTO defined at `api.ts:2688`.
3. **Hook is ready.** `useContentPerformance(creatorId)` (`src/hooks/analytics/useContentPerformance.ts`) returns `{ data, loading, error, notImplemented, refresh }` — same shape as `useCreatorMetrics` / `useCreatorScores` already used on the page.
4. **Component is ready.** `ContentPerformancePanel` (`src/components/analytics/ContentPerformancePanel.tsx`) already handles loading / error / empty / populated states and the NON_NULL null-handling from Fix #4.

Grep confirms `ContentPerformancePanel` and `useContentPerformance` are imported by **nothing** in `src/` except each other — genuinely unmounted, not double-mounted. Mounting on the page that already loads the same creator's metrics/scores by the same authz path is the lowest-risk resolution: no new route, no new auth, no new data source, identical prop conventions.

### Exact scope for the implementer (Ananya)

**File:** `src/pages/brand-creator-analytics.tsx` (the only edit).

1. Add imports:
   - `import { ContentPerformancePanel } from '@/components/analytics/ContentPerformancePanel';`
   - `import { useContentPerformance } from '@/hooks/analytics/useContentPerformance';`
2. Call the hook alongside the existing ones (after the `useCreatorScores` line, ~`:60`):
   - `const { data: content, loading: contentLoading, error: contentError, notImplemented: contentNotImplemented } = useContentPerformance(creatorId);`
3. Mount the panel as a full-width card **between the "Scores" grid (ends `:179`) and the "Audience demographics" coming-soon block (`:181`)**:
   ```
   <ContentPerformancePanel
     data={content}
     loading={contentLoading}
     error={contentError}
     notImplemented={contentNotImplemented}
   />
   ```

Prop mapping is 1:1 with the hook return — no adapter, no reshaping. `notImplemented` stays wired (harmless dead branch now that the route is live; leave it for graceful behavior if the route ever regresses to a NOT_IMPLEMENTED envelope).

**Out of scope / do NOT do:** no backend change, no new route, no auth change, no api.ts change. If the panel throws a NETWORK_ERROR in local/demo it's the unprovisioned-backend gate (audit's "live-proven 0%"), not a wiring bug — that is expected and not part of this task.

**Size: S (single-file FE mount). Verify:** Kavya (prop wiring) → Meera (`npm run build` typecheck; the hook/panel/DTO types must line up).

---

## PARTIAL 2 — Meera flywheel logging (`meera_interaction_log`)

**RULING: (a) — KEEP write-only. RE-CLASSIFY as ALIGNED-by-design. Do NOT build a read consumer now. Arjun RE-SCORES; implementers build nothing.**

### The decision

The audit scored this PARTIAL on the premise "nothing reads it back." That applies a consumer-feature rubric to an instrumentation feature — a category error. For a logging/telemetry sink, the *surface* is durable, redacted, correctly-scoped persistence, and that is fully built and Kabir-passed (phase2-kabir-security.md check 3: single free-text column, structural redaction via `SensitiveTextRedactor.record`, write-only repository, workspace-scoped rows, all five write points verified). Judged as what it is — a write-only flywheel sink — it is ALIGNED, not PARTIAL.

### Rationale (grounded in the plan's own sequencing)

1. **The plan explicitly designed this as "start now, learn later."** Write-only v1 is the intended shape, not a half-finished consumer. We are accumulating the training/learning substrate before we have a learning question to ask it — that is the design, so meeting the design = ALIGNED. There is no analytics question queued today that a reader would answer; building one now is speculative.
2. **Building a reader now forces the exact cost the plan deferred to "day one on read."** Kabir L1 is unambiguous: retention "stops being optional" the moment a read/join is added, so a consumer would have to ship WITH the 180-day retention purge, AND L2 (`interaction-log` `campaign_id` ownership check, currently benign because write-only) becomes load-bearing. That is real, security-sensitive work spent to service a consumer with no hypothesis behind it.
3. **The info-barrier currently holds BY CONSTRUCTION** — write-only means no joined view can co-locate both parties' data (Kabir check 3). A reader downgrades a *free structural invariant* to a *maintained query-discipline invariant* (single-workspace, no cross-party join, enforced by every future query author). Do not trade a structural guarantee for a policed one before there is a reason to read.

### What Arjun should do

Re-score `meera_interaction_log` flywheel logging from **PARTIAL → ALIGNED** (write-only instrumentation sink, aligned-by-design). It already sits under ALIGNED in the audit's own line-50 list ("Flywheel logging OPTIONS_PRESENTED, write-only by design") — the PARTIAL row is the stale contradiction. **No implementer is deployed for this item.**

### Standing architectural gate (attach to the re-score; not a build task now)

The re-score to ALIGNED is unconditional — retention is not a security gate today (Kabir: redaction + write-only + workspace-scoping = no active leak; fast-follow, not gate-block). But I am locking the sequencing so it cannot be skipped later:

- **HARD GATE:** the 180-day retention purge job (design §3.6; migration `V20260721160000__meera_interaction_log.sql` + a scheduled purge) is a MANDATORY predecessor to ANY future read/join/analytics consumer of this table. No read query merges without it landing first. Owner: Meera (DB/DevOps) + Rohan (cost). When a read is proposed, L2's `campaign_id` ownership check ships in the same changeset.
- **RECOMMENDED (ops hygiene, non-blocking, decoupled from the feature score):** schedule the purge anyway for unbounded row-growth hygiene, independent of whether a reader is ever built. This is a DB-ops task, not a feature — track it on Meera's backlog, do not let it hold the ALIGNED re-score.

---

## Summary for Arjun — what to deploy

- **PARTIAL 1 → BUILD (small FE).** Deploy **Ananya** to mount `ContentPerformancePanel` in `src/pages/brand-creator-analytics.tsx` (single file: 2 imports + 1 hook call + 1 panel between the Scores grid and the demographics block; props map 1:1 to `useContentPerformance`). Then **Kavya** (prop wiring) → **Meera** (build/typecheck). No backend work — Fix #4's route is already live and authz-gated. Resolves to ALIGNED.
- **PARTIAL 2 → NO BUILD. RE-SCORE.** Rule (a): keep `meera_interaction_log` write-only; re-classify **PARTIAL → ALIGNED** (aligned-by-design instrumentation sink). Do not build a consumer — the plan is "start now, learn later," and a reader would prematurely force the retention purge + L2 ownership check for no current learning need. Attach the standing gate: **the 180-day retention purge is a mandatory predecessor to any future read consumer** (owner Meera/Rohan); recommend scheduling it anyway for row-growth hygiene, but it does not block the re-score.

— Priya, CTO
