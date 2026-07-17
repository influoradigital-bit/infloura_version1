# QA Review: P2-16 useNotifications Refactor
Date: 2026-07-12
Reviewer: Kavya (QA Lead)
Owner: Ananya
Status: ✅ **PASS**

---

## Overview
Refactored `useNotifications` hook to use shared API client instead of raw `fetch()` calls. Removed hardcoded `/api/v1/notifications` URLs and replaced with role-aware client methods.

## Files Reviewed
- `src/lib/api.ts` (new NotificationItem type + notifications export)
- `src/hooks/useNotifications.ts` (refactored to use shared client)

---

## QA Checklist Results

### ✅ TECH-STACK.md Compliance
- [x] Uses shared `src/lib/api.ts` client (per TECH-STACK.md data fetching standard)
- [x] Respects `API_BASE_URL` environment variable
- [x] Mock mode (`VITE_API_MODE!=live`) handled correctly
- [x] No raw `fetch()` calls remain in the hook
- [x] TypeScript strict mode — all types properly defined

### ✅ TypeScript Standards
- [x] No `any` types used
- [x] `NotificationItem` interface properly exported from `api.ts`
- [x] Type reuse: `export type Notification = NotificationItem` (eliminates duplicate type definition)
- [x] Return types properly typed: `Promise<void>` on async functions
- [x] Error handling typed: `err instanceof Error` check before `.message` access

### ✅ Code Quality
- [x] **Replaced 3 raw fetch calls** with shared client:
  - `notificationsApi.list('brand')` — line 109
  - `notificationsApi.markRead('brand', id)` — line 130
  - `notificationsApi.markAllRead('brand')` — line 149
- [x] Role properly passed to all client methods (`'brand'` hardcoded — acceptable for brand-only context)
- [x] Optimistic updates preserved (lines 124-126, 145)
- [x] Error rollback logic in place (lines 132-136)
- [x] Mock data flow unchanged (lines 104-106)

### ✅ Security
- [x] No `localStorage.getItem('brand_token')` direct access (delegated to shared client)
- [x] No hardcoded API URLs
- [x] No secrets in code
- [x] Authorization header managed by shared client (not exposed in hook)

### ✅ API Contract Alignment
**Checked `src/lib/api.ts` lines 1022-1040:**
- [x] `notifications.list(role)` returns `NotificationItem[]`
- [x] `notifications.markRead(role, id)` returns `{ ok: true }`
- [x] `notifications.markAllRead(role)` returns `{ ok: true }`
- [x] All methods have mock fallbacks via `mockOr()`
- [x] All methods respect `isLive()` check

**Type Definition (lines 1011-1020):**
```typescript
export interface NotificationItem {
  id: string;
  type: 'info' | 'success' | 'warning' | 'meera_nudge';
  title: string;
  body?: string;
  read: boolean;
  createdAt: string;
  link?: string;
  surfaceInChat?: boolean;
}
```
✅ Matches all fields in `MOCK_NOTIFICATIONS` (lines 45-82 of useNotifications.ts)

---

## Build Verification

**Command:** `npm run build`
**Result:** ✅ exit 0, 3942 modules transformed

**TypeScript Compilation:** Clean (no errors)

---

## Issues Found

### NONE — Clean pass

No blocker, high, or medium issues found.

---

## Minor Observations (Non-blocking)

1. **Hardcoded `'brand'` role** — currently acceptable as this hook is brand-only context, but may need parameterization if creator notifications are added later. Not a blocker for current scope.

2. **Error rollback on `markAllRead` incomplete** (line 150-152) — comment acknowledges this (`// Revert would need to track previous state - skip for now`). This is documented technical debt, acceptable for P2 task.

---

## Diff Summary

**Before:**
- 3 raw `fetch()` calls with hardcoded URLs
- Manual `Authorization` header construction
- Manual error handling per endpoint

**After:**
- 3 shared client calls (`notificationsApi.list/markRead/markAllRead`)
- Authorization delegated to client
- Consistent error envelope handling
- Type safety via `NotificationItem`

**Lines removed:** ~25 (fetch boilerplate)
**Lines added:** ~4 (client imports + calls)
**Net reduction:** ~21 lines

---

## Verdict

**✅ PASS**

Clean refactor. No raw `fetch()` calls remain. Shared client properly integrated. Type safety maintained. Build successful. No security or standards violations.

**Next Steps:**
1. Route to Meera for `npm run dev` verification
2. Verify notifications panel in browser (manual UI test)

---

**QA Sign-off:** Kavya Reddy
**Date:** 2026-07-12
**Status:** Approved for Meera verification
