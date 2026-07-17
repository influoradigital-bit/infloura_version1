# QA Review: Creator Deliverable Metrics Report UI — Task #24b (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~22:00 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (Task #24 security gate carry-forward) → Meera build confirm  
**Scope:** Ananya Task #24b — metrics reporting UI vs Vikram Task #24 API  
**Reference:** `TASK_INBOX.md` Task #24b; `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.6, §6.4; prior backend QA (`creator-deliverable-metrics-T24-kavya-qa.md`)  
**Reviewed Files:**
- `src/lib/api.ts` — `creatorDeliverables.reportMetrics`, `CreatorDeliverableMetricsReportPayload` / `CreatorDeliverableMetricsReportResponse`
- `src/components/creator/deal-room/metrics-report-form.tsx` — metrics dialog form
- `src/pages/creator-chat.tsx` — `canReportMetrics` gate, `handleSubmitMetricsForm`, refresh paths
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `calculateEngagementRate` (mock parity cross-check)
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `MetricsReportRequest` / `MetricsReportResponse`

---

## Executive Summary

Creator deliverable **metrics report UI passes QA**. `MetricsReportForm` collects self-declared performance numbers (likes, comments, shares, views, reach, impressions, saves) plus optional days-after-posting, and submits via `api.creatorDeliverables.reportMetrics` to Vikram's `POST /creator/deliverables/{id}/metrics`. The deal-room header **"Report Metrics"** button renders only when the selected deal is `in_progress` and at least one loaded deliverable status has `actions.canReportMetrics: true` — no client-side state guessing.

Post-success flow refreshes deliverable statuses (`loadDealDeliverables`) and deal list (`fetchDeals`), matching Task #20b submit parity. Error handling surfaces `ApiError.message` via destructive Alert; form state is retained on failure for retry. Loading/disabled states cover submit and deliverables fetch.

`npm run build` **PASS** per Meera gate (4591 modules). No `console.log` / debug code in reviewed files. No linter diagnostics on touched files.

**Non-blocking carry-forward:** mock-mode engagement-rate formula still diverges from backend §5.2 (L-24b-1, reopened from T24 L-24-8); proof-screenshot upload UI omitted until §4.7 ships (L-24b-2); `reportedDaysAfterPosting` accepted by API but not persisted server-side (L-24-1 from T24).

---

## Build Verification

| Gate | Result | Evidence |
|------|--------|----------|
| `npm run build` | ✅ **PASS** | Meera 2026-07-09 — 4591 modules, ~3m 11s |
| ESLint / TS on touched files | ✅ **PASS** | No linter diagnostics |
| `console.log` / debug code | ✅ **PASS** | None in reviewed files |

---

## Task #24b Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `api.creatorDeliverables.reportMetrics` live path | ✅ PASS | `api.ts` L1447–1451 → `POST /creator/deliverables/${id}/metrics` |
| Request types match backend `MetricsReportRequest` | ✅ PASS | `metrics`, optional `proofScreenshots`, `reportedDaysAfterPosting` L1214–1218 |
| Response types match backend `MetricsReportResponse` | ✅ PASS | L1221–1228 |
| Mock path in `!isLive()` | ✅ PASS | L1428–1444; mock `reel-1` POSTED + `canReportMetrics: true` L1257–1268 |
| `MetricsReportForm` wired in deal room | ✅ PASS | `creator-chat.tsx` L2064–2074 |
| Button gated on `canReportMetrics` | ✅ PASS | `metricsReportableDeliverables` L918–921; button L1361–1371 |
| Refresh deliverables + deals post-success | ✅ PASS | L938–939 |
| `npm run build` PASS | ✅ PASS | Meera gate |

---

## API Contract Cross-Check

| Frontend call | Backend route | Match |
|---------------|---------------|-------|
| `creatorDeliverables.reportMetrics(id, { metrics, proofScreenshots?, reportedDaysAfterPosting? })` | `POST /creator/deliverables/{id}/metrics` — `MetricsReportRequest` | ✅ |
| Auth | `role: 'creator'` Bearer via `http.request` | ✅ |
| Response | `deliverableId`, `status`, `metrics`, `engagementRate`, `verificationStatus`, `message` | ✅ |

```1424:1451:src/lib/api.ts
  reportMetrics: async (
    deliverableId: string,
    payload: CreatorDeliverableMetricsReportPayload,
  ): Promise<CreatorDeliverableMetricsReportResponse> => {
    if (!isLive()) {
      const metrics = payload.metrics;
      const engagements =
        (metrics.likes ?? 0) +
        (metrics.comments ?? 0) +
        (metrics.shares ?? 0) +
        (metrics.saves ?? 0);
      const reach = metrics.reach ?? 0;
      const engagementRate = reach > 0 ? Math.round((engagements / reach) * 10000) / 100 : null;
      return mockOr({
        deliverableId,
        status: 'METRICS_REPORTED',
        metrics,
        engagementRate,
        verificationStatus: 'PENDING',
        message: 'Metrics submitted. They will be verified.',
      });
    }

    return http.request<CreatorDeliverableMetricsReportResponse>(
      'POST',
      `/creator/deliverables/${deliverableId}/metrics`,
      { role: 'creator', body: payload },
    );
  },
```

**Live path:** contract-aligned. **Mock path:** engagement formula mismatch — see L-24b-1.

---

## UI Gate Review: `canReportMetrics`

Statuses are loaded per deliverable via `getStatus()` inside `loadDealDeliverables`:

```828:861:src/pages/creator-chat.tsx
  const loadDealDeliverables = React.useCallback(async (roomId: string) => {
    // ...
    const items = await api.creatorDeliverables.listForDeal(roomId);
    // ...
    const statuses = await Promise.all(
      items.map((item) => api.creatorDeliverables.getStatus(item.id)),
    );
    setDealDeliverableStatuses(statuses);
```

Reportable subset and button gate:

```918:921:src/pages/creator-chat.tsx
  const metricsReportableDeliverables = React.useMemo(
    () => dealDeliverableStatuses.filter((s) => s.actions.canReportMetrics),
    [dealDeliverableStatuses],
  );
```

```1361:1371:src/pages/creator-chat.tsx
            {selectedDeal.status === 'in_progress' && metricsReportableDeliverables.length > 0 && (
              <Button
                variant="outline"
                size="sm"
                onClick={handleOpenMetricsDialog}
                disabled={deliverablesLoading}
                className="gap-1.5"
              >
                <BarChart3 className="h-4 w-4" />
                <span className="hidden sm:inline">Report Metrics</span>
              </Button>
            )}
```

| Check | Result | Notes |
|-------|--------|-------|
| Uses backend `actions.canReportMetrics` | ✅ PASS | No status-string guessing |
| Button hidden when none reportable | ✅ PASS | `length > 0` gate |
| Deal must be `in_progress` | ✅ PASS | Consistent with Submit Deliverable header gate |
| Disabled while deliverables loading | ✅ PASS | Prevents race on empty picker |
| Dialog deliverable list = reportable only | ✅ PASS | L2067–2070 maps filtered statuses |

---

## Metrics Report Form Review

```64:130:src/components/creator/deal-room/metrics-report-form.tsx
export function MetricsReportForm({
  open,
  onOpenChange,
  deliverables,
  onSubmit,
  isSubmitting = false,
  submitError = null,
}: MetricsReportFormProps) {
  // ... state for deliverable picker, 7 metric fields, days-after-posting
  const canSubmit = Boolean(selectedDeliverable) && hasAnyMetric(metrics);

  const handleSubmit = async () => {
    if (!canSubmit) return;
    // ... builds metrics payload, calls onSubmit, resets on success
  };
```

| Check | Result | Notes |
|-------|--------|-------|
| All §4.6 metric fields present | ✅ PASS | likes, comments, shares, views, reach, impressions, saves |
| At least one metric required | ✅ PASS | `hasAnyMetric` requires value > 0 — stricter than backend L-24-5 (acceptable UX) |
| Non-negative integer validation | ✅ PASS | `parseMetricValue` rejects NaN and negatives |
| Optional days-after-posting | ✅ PASS | Sent in payload; backend non-persist (L-24-1) |
| Loading / disabled on submit | ✅ PASS | `isSubmitting` on inputs + button |
| Error surfacing | ✅ PASS | `submitError` → destructive Alert |
| Form retained on failure | ✅ PASS | catch block keeps state; parent rethrows |
| Form reset on success | ✅ PASS | Clears fields + closes dialog |
| shadcn Dialog/Input/Select patterns | ✅ PASS | Matches deal-room components |
| `useReducedMotion` on animations | N/A | No motion in this form |

---

## Submit Handler Review

```928:948:src/pages/creator-chat.tsx
  const handleSubmitMetricsForm = async (data: MetricsReportData) => {
    if (!selectedDeal) return;
    setIsSubmittingMetrics(true);
    setMetricsSubmitError(null);
    try {
      await api.creatorDeliverables.reportMetrics(data.deliverableId, {
        metrics: data.metrics,
        proofScreenshots: data.proofScreenshots,
        reportedDaysAfterPosting: data.reportedDaysAfterPosting,
      });
      await loadDealDeliverables(selectedDeal.id);
      await fetchDeals();
      setShowMetricsDialog(false);
    } catch (e) {
      setMetricsSubmitError(
        e instanceof ApiError ? e.message : 'Could not submit metrics. Try again.',
      );
      throw e;
    } finally {
      setIsSubmittingMetrics(false);
    }
  };
```

| Check | Result |
|-------|--------|
| Calls live API with full payload shape | ✅ PASS |
| Refreshes statuses after success | ✅ PASS |
| Refreshes deal list after success | ✅ PASS |
| User-friendly error messages | ✅ PASS |
| Guard: no submit without selected deal | ✅ PASS |

---

## TECH-STACK.md Compliance

| Rule | Status |
|------|--------|
| Vite + React (not Next.js `'use client'` needed) | ⚠️ L-24b-3 — harmless `'use client'` at top of form |
| TypeScript strict types on API payloads | ✅ |
| shadcn/Radix UI components | ✅ |
| Live path fails closed (no silent mock in prod) | ✅ |
| `react-hook-form` + `zod` for forms | ⚠️ L-24b-4 — manual state; consistent with other deal-room forms (T20b) |
| No secrets in client | ✅ |
| Creator auth via JWT role | ✅ |

---

## Findings (Non-Blocking)

### L-24b-1: Mock engagement-rate formula mismatch (carry-forward L-24-8)
Mock `reportMetrics` uses `(likes + comments + shares + saves) / reach × 100`. Backend §5.2 uses `(likes + comments) / reach × 100` with 1-decimal rounding (`calculateEngagementRate` L347–354). **Mock/demo mode only** — live path uses server-computed rate. Recommend aligning mock before demo walkthroughs.

### L-24b-2: Proof screenshot upload UI omitted
Spec §6.4 includes `proofScreenshots` upload flow; form never collects or sends screenshot IDs. Acceptable for sprint slice — §4.7 proof-upload endpoint not shipped; Kabir L-24-4 applies when UI adds proof keys.

### L-24b-3: `'use client'` directive in Vite SPA
`metrics-report-form.tsx` L1 — Next.js convention; no runtime impact. Remove on next touch.

### L-24b-4: `reportedDaysAfterPosting` UI field without backend persist
Form collects and sends field; backend accepts but does not store (T24 L-24-1). UI copy does not promise persistence — low confusion risk.

### L-24b-5: Deal progress counts unchanged after report (M-3 carry-forward)
`fetchDeals` called but `deliverablesDone`/`deliverablesTotal` remain 0 from backend until aggregate work ships. Tab statuses refresh via `dealDeliverableStatuses`.

### L-24b-6: No frontend unit tests
No Vitest coverage for `MetricsReportForm` or `handleSubmitMetricsForm`. Consistent with frontend test debt (T20b L-20b-8).

### L-24b-7: Double dialog close on success
Both `MetricsReportForm.handleSubmit` (`onOpenChange(false)`) and `handleSubmitMetricsForm` (`setShowMetricsDialog(false)`) close the dialog. Harmless redundancy.

### Security carry-forward (Kabir — Task #24 gate)
- Proof screenshot key injection when §4.7 ships — L-24-4.
- Metrics IDOR — backend closed in T24; no new frontend vector.
- No metrics rate limit — L-24-10.

---

## Hostile / Edge-Case Matrix

| Scenario | Expected | Status |
|----------|----------|--------|
| Mock mode: `reel-1` POSTED, `canReportMetrics: true` | Button visible; submit succeeds | ✅ PASS |
| Live: no reportable deliverables | Button hidden | ✅ PASS |
| Live: deliverable `canReportMetrics: false` | Excluded from dialog picker | ✅ PASS |
| Submit with empty metrics | Submit disabled | ✅ PASS |
| Negative metric input | Parsed as undefined; may fail `hasAnyMetric` | ✅ PASS |
| API 409 `INVALID_STATE` | Error Alert; dialog open | ✅ PASS |
| API 404 foreign deliverable | Error Alert | ✅ PASS |
| Deliverables loading | Button disabled | ✅ PASS |
| Deal not `in_progress` | Button hidden | ✅ PASS |
| Re-report (`METRICS_REPORTED` + `canReportMetrics: true`) | Allowed per backend | ✅ PASS (live) |
| Mock engagement rate display | Wrong formula vs backend | ⚠️ L-24b-1 |

---

## QA Sign-Off

- [x] `creatorDeliverables.reportMetrics` live contract verified vs Task #24 backend
- [x] `MetricsReportForm` fields match §4.6 payload
- [x] Button gated on `actions.canReportMetrics` from `getStatus()`
- [x] Deliverables list + statuses refresh after success
- [x] `fetchDeals` called post-report
- [x] Error UX and loading states verified
- [x] Mock path exercises report flow in `!isLive()` mode
- [x] No debug code; linter clean
- [x] TECH-STACK.md alignment (minor L-24b-3/L-24b-4 noted)
- [x] `npm run build` PASS — Meera gate
- [ ] Kabir metrics security review — **NEXT GATE** (Task #24, bundles proof-key surface)

**Kavya verdict: ✅ APPROVED.** Route to Kabir deliverable metrics red-team (Task #24 security gate). Mock formula alignment (L-24b-1) recommended before stakeholder demo but not blocking prod live path.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #24b). Prior: `creator-deliverable-metrics-T24-kavya-qa.md` (backend). Next: Kabir Task #24 security → Priya sign-off.
