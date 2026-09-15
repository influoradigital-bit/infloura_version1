# Feature: Reports & Exports

**Business Purpose** — Lets brands export a campaign's performance report (CSV or PDF) for offline analysis and stakeholder sharing. It's a Pro-plan value-add built on the same numbers shown on-screen.

**Who uses it** — Brands on the Pro plan.

## User Roles
Brand (Pro).

## Permissions
Gated by `@RequiresPlan(feature=EXPORT)` → 402 `UPGRADE_REQUIRED` for Free.

## Business Flow
```
Brand (Pro) → request campaign export (csv|pdf) → server builds report from campaign analytics → download bytes
```

## Frontend
- **API**: export method on the campaigns resource ([CORRECTED 2026-09-13, doc-stale-doc-claim, F-0805a: this line said "no live frontend caller currently exists" — false; has a real frontend caller, `brand-campaign-detail.tsx:604` `handleExportReport`, wired to CSV/PDF buttons at `:1092,1098`; see Known issues below]).

## Backend
- **Controller**: `ReportExportController` (`GET /campaigns/{id}/export`).
- **Services**: `ReportExportService` (reuses `getCampaignAnalytics`), `ReportPdfService`, `CsvWriter`.

## Database
Reads `deliverable_metrics` + campaign aggregates. No dedicated table.

## APIs
`GET /campaigns/{campaignId}/export?format=csv|pdf` → `application/octet-stream`, `Content-Disposition: attachment`, filename `campaign-{id}-report.{csv|pdf}` (no PII).

## AI
Not involved.

## Notifications
None.

## Dependencies
- **Depends on**: analytics (source numbers), billing (Pro gate).
- **Depended on by**: brand reporting workflows.

## Connected Files
`ReportExportController`, `ReportExportService`, `ReportPdfService`, `CsvWriter`, `security/PlanGateInterceptor`.

## Execution Flow
```
GET /campaigns/{id}/export?format=csv → PlanGateInterceptor (@RequiresPlan EXPORT → 402 if Free)
  → ReportExportController → ReportExportService (creator-reported metrics) → CSV/PDF bytes
```
CSV columns: `campaign_id, deliverable_id, reach, impressions, engagements, link, reported_by_creator_id, reported_at, source` + a SUMMARY row.

## Error Handling
`UPGRADE_REQUIRED` (402) for Free. Invalid format falls back appropriately.

## Security
Plan-gated; filenames carry no PII; numbers are creator-reported (never platform-verified unless verification ran).

## Performance
Reuses on-screen analytics; no heavy recompute.

## Testing
Export service tests. Regression risks: plan gate, CSV/PDF formatting.

## Production Readiness
- **Health**: 6/10 · **Completion**: ~70%
- **Known issues**: [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0611: has a real frontend caller — `brand-campaign-detail.tsx:604` `handleExportReport`, wired to CSV/PDF buttons at `:1092,1098`, with a 402 mapped to an upgrade-plan toast at `:619-624`]; CSV + PDF only (no XLSX); boolean plan-gate only (the `UsageMetric.EXPORT` counter isn't incremented here). [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a "See [../known-limitations.md]" link — docs/docs/ contains only features/, so that file never existed].
- **Last verified**: 2026-07-15
