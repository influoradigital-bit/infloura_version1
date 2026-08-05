# API Connection Reconciliation (independent, oracle-style)

Method: extracted every typed `request()` call (method+path, multiline-aware) across all `src/**` TS/TSX,
normalized path params, diffed against 277 resolved backend routes (method+path).

## Findings

### Main client (`src/lib/api.ts` + `src/lib/meera-api.ts`) — brand / creator / AI
- **140 typed `request()` calls → 140 resolve to a real backend method+path. 0 phantom endpoints.**
- Conclusion: path-level API wiring is *excellent*. Any "broken" feature here is NOT a missing/mismatched
  route — it is backend stub logic, auth/scope, env-gating (unprovisioned keys), or a runtime bug.
- SSE endpoints (`GET /deals/{}/messages/stream`) use raw `fetch`/EventSource, not `request()`; backend route exists. OK.

### Admin client (`src/admin/services/api-contracts.ts`) — separate wrapper
- **1 confirmed phantom endpoint:** `GET /admin/marketing/referrals` (api-contracts.ts:751) →
  AdminMarketingController only exposes `/admin/marketing/reputation`. Referrals view → **404 / BROKEN**.
- All other admin paths resolve.

### Orphan backend routes
- Raw orphan diff is unreliable (multiline calls, meera-api.ts, SSE). Not treated as "missing frontend".
- 277 backend routes vs ~200 frontend calls — remainder are webhooks, `/internal/meera/*`, jwks, health,
  oauth callbacks (correctly no frontend).

## Headline
Path-level connectivity is strong. The audit's real signal is at the *logic/runtime/env* layer, not the wiring layer.

## Backend stubs (corroborating PARTIAL, from grep of service/)
- NoOpMalwareScanService — upload malware scan is a documented no-op (accepted risk M-K6-C3-3); prod needs real bean or fails fast. Uploads work, not scanned.
- FakeFollowerDetectionService — deliberately not implemented (needs NLP). Fake-follower score not real.
- QualityScoreService — audienceMatch is a placeholder neutral 50 (no real brand matching).
- CreatorNudgeService — templated fallback copy when AI client unavailable.
- AdminDashboardService (7 markers) / AdminCampaignService (6) — highest stub density; verify metrics realness.
- 59 total TODO/stub/placeholder markers in influora-api service layer.
