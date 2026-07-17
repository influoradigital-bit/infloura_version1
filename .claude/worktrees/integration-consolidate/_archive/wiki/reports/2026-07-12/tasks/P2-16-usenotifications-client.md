# P2-16 — `useNotifications` → shared api client

**Owner:** Ananya · **Reviewers:** Kavya → Meera · **Priority:** P2 · **Depends on:** — (**FE-only — can start NOW**)
**Status:** ✅ DONE

## Goal
`useNotifications` live path uses a **raw hardcoded `fetch('/api/v1/notifications')`**, bypassing the shared `http` client (no `API_BASE_URL`, brand-token-only, no creator support). Move onto the shared client.

## Files
- `src/hooks/useNotifications.ts:114-134` (TODO at line 120)
- `src/lib/api.ts` (add a notifications method on the shared client, role-aware)

## Acceptance criteria
- [x] `useNotifications` uses the shared `api`/`http` client (role-aware, respects `API_BASE_URL` + mock mode)
- [x] No raw `fetch` remains in the hook
- [x] tsc + build clean · Kavya QA · Meera verify

## Completion log
- Meera · 2026-07-12 · `npm run build` exit 0, built in 52.49s ✅
