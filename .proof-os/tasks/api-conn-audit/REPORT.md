# FE↔BE API connection audit — brand · creator · admin · AI

**Verdict:** PROVED (compile) + BELIEVED (path alignment). **Zero live connection errors.**
**done_when:** every FE API call resolves to a real backend route, or a documented zero-live-caller phantom; `tsc --noEmit` clean.

## Oracles run (independent of my reasoning)
| oracle | scope | result | ceiling |
|---|---|---|---|
| `npx tsc --noEmit` | all of `src/` | **0 errors (exit 0)** | PROVED (allowlisted) |
| `vitest run src/lib/__tests__/api-contract.test.ts` | api.ts + admin api-contracts.ts vs Java controllers | **3/3 PASS** | BELIEVED (vitest ∉ deterministic allowlist) |
| `diff_api.py` (own tool) | 215 FE calls vs 270 BE routes | 185 exact + 7 path-only, **0 verb-mismatch**, 23 "missing" all reconciled | corroborating |

## Surfaces
- **Brand + Creator** (`src/lib/api.ts`): all live calls map to real controllers. tsc clean, guardrail green.
- **Admin** (`src/admin/services/api-contracts.ts`, base `/api/v1/admin`): only `financeApi.getFeeConfig/updateFeeConfig/getFeeConfigHistory` are live-wired → `/admin/finance/fee-config` (exists). The Finance/Escrow/Marketing/dashboard-financial|marketing/at-risk/hype-ops exports are **spec-first with ZERO live callers** (deferred by CTO scope decision; tracked in `KNOWN_PHANTOM_PATHS`).
- **AI / Meera** (`src/lib/meera-api.ts`): all 6 endpoints match `MeeraController` — `/meera/brand-profile` `/credits` `/sessions` `/sessions/{}/messages` `/voice/speak` `/voice/transcribe`. SSE `streamUrl` is server-driven (never FE-fabricated). `/internal/meera/*` are AI-service→Spring, not browser calls.

## Latent items (NOT live breaks — dead code / deferred scope)
1. `api.notifications.markAllRead()` (api.ts:2760) → `POST /notifications/read-all` — no backend route (BE has `/read`). **Zero callers**; the live `useNotifications.markAllRead` loops `/notifications/read` instead. Latent 404 if ever wired.
2. Admin `escrowApi` + Finance/Marketing spec-first client — zero callers; `escrowApi` is the codebase's own "strongest DELETE candidate" (money-path endpoints deliberately not built blind).

## Blind spots (law 5 — not checked)
- Request/response **body field-shape** parity (a path can match while JSON field names diverge).
- Runtime auth/CORS/HTTP-status against a live server.
- The Python AI service behind `VITE_MEERA_STREAM_URL`.
- `eslint`/`gitleaks` — frontend.sh gate exceeded the 2-min tool budget (not relevant to API connection).

## Declared skips (law 6)
- proof-os `journal.py` / `ledger.py` / `validate.py` — all `import fcntl` (Unix-only) and crash on this **Windows** host; validate.py also UnicodeEncodeErrors on cp1252. The scoring/journal layer is unavailable here; the API-connection oracles (tsc, vitest guardrail) ran independently of it.
