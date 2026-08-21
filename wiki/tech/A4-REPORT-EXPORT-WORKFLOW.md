# A4 · Report Export (CSV / PDF) — Workflow & Build Spec

> **Owners:** Priya (CTO) · Arjun (routing) · **Status:** 🔴 0% — not started · Pro-tier feature (`SUBSCRIPTION-BILLING-PLAN.md` §2)
> **Date:** 2026-07-14 · Closes gap **A4** · Grounded in real code.

---

## 1. What it is
Let a brand export campaign performance / creator-comparison / conversion data as **CSV** (raw data) and **PDF** (branded one-pager). Today analytics render on-screen only; agencies need a file to drop into their client's boardroom.

## 2. Build with the current system (reuse, don't reinvent)
| Need | Already exists | How to use it |
|---|---|---|
| Analytics data | `DeliverableMetricService.getCampaignAnalytics()` (→ `CampaignAnalyticsResponse`), `AnalyticsController` `/analytics/creators/*`, `CreatorAnalyticsController` `/creator/analytics/me` | Export reads the **same service methods** the on-screen views already call — one data source, two renderers |
| PDF rendering | `ContractPdfService` / `InvoicePdfService` (pure OpenPDF, `com.lowagie.text.*`) | Copy the pattern into a `ReportPdfService`; helpers are `private static` so extract a shared `PdfKit` or duplicate |
| File delivery | `R2StorageService` (has `S3Presigner`, PUT + GET presign) | Upload the generated file, return a short-lived presigned GET URL — same pattern as `/contracts/{id}/pdf-download-url` |
| Gating | `Plan.exportEnabled` (field exists on the `Plan` entity) | Wrap the endpoint in `@RequiresPlan` (billing Task 15); Free tier → `UPGRADE_REQUIRED` |
| CSV | **nothing exists** | Tiny in-house CSV writer via `StringWriter` (RFC-4180 quoting) — **no new dependency**; do NOT pull opencsv |

## 3. Architecture
- **No new entity** — export is a read+render over existing analytics.
- **New service:** `ReportExportService` — takes a report type + format, pulls from the existing analytics services, renders CSV (StringWriter) or PDF (OpenPDF).
- **Endpoints (on a new `ReportExportController`):**
  - `GET /campaigns/{id}/export?format=csv|pdf` → campaign performance
  - `GET /analytics/creators/export?ids=…&format=csv` → creator comparison
  - Response: either a direct `application/octet-stream` download, or `{ url }` presigned (pick direct for small, presigned for large — decide in spec gate).
- **Frontend:** an "Export ▾" button on `brand-analytics.tsx` + `brand-campaign-detail.tsx` (CSV / PDF), Pro-gated with an upgrade CTA on Free.
- **Migration:** none needed (no schema change).

## 4. Task loop (Arjun routing)
| # | Task | Owner | Blocked by |
|---|---|---|---|
| E1 | Spec: report types, columns, CSV vs PDF layout, direct-vs-presigned (GATE) | Priya + Arjun | — |
| E2 | `ReportExportService` + CSV writer util | Vikram | E1 |
| E3 | `ReportPdfService` (OpenPDF, branded header) | Vikram | E1 |
| E4 | `ReportExportController` + `@RequiresPlan(export)` + R2 delivery | Vikram | E2, E3 |
| E5 | FE "Export" button + Pro-gate CTA | Ananya | E1 |
| E6 | VERIFY: QA → mvn verify + Playwright download e2e → Priya sign-off | Kavya/Meera/Priya | E2–E5 |

Loop: any red → back to owner; Priya signs before done.

## 5. Acceptance criteria
- [ ] Campaign performance exports as a valid CSV (opens clean in Excel/Sheets) and a branded PDF.
- [ ] Data matches the on-screen analytics exactly (same service, no divergence).
- [ ] Export is Pro-gated (`Plan.exportEnabled`); Free → `UPGRADE_REQUIRED`.
- [ ] Large exports delivered via presigned R2 URL; no PII leak in filenames.
- [ ] `mvn verify` green + Playwright export-download e2e green.

## 6. Dependencies / decisions
- **Pro-gate** depends on billing Task 15 (plan-gate filter) — until then feature-flag the gate, ship the mechanism.
- No Swapnil decision required to build; only the gate flips with billing.
