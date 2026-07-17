# P2-9 — Store-integration status/disconnect endpoints

**Owner:** Vikram (backend) → Ananya (wire FE) · **Reviewers:** Kavya → Meera · **Priority:** P2 · **Depends on:** P0-1
**Status:** 🟡 IN PROGRESS (Backend Kavya QA PASS, FE wiring done by Ananya — awaiting Meera verify)

## Goal
Store connect works, but **status** and **disconnect** return `NOT_IMPLEMENTED`. Add the endpoints and un-stub the FE.

## Files
- **Backend (Vikram):** store-integration controller (Shopify/Woo status + disconnect)
- **Frontend (Ananya):** `src/lib/api.ts:1524-1530` (remove `NOT_IMPLEMENTED`), `src/hooks/brand/useStoreIntegration.ts`, `src/components/brand/settings/StoreIntegrationSetup.tsx`

## Acceptance criteria
- [x] Backend: Status returns real connection state; disconnect actually disconnects
- [x] Backend Kavya QA ✅ PASS (2026-07-13)
- [x] Frontend: FE hooks/components wired (Ananya)
- [ ] Meera verify

## Completion log
**Vikram (backend) — 2026-07-12**

✅ **Status + disconnect endpoints implemented**

**Backend (Java Spring Boot):**
1. Created `IntegrationDtos.java` — unified DTOs for status/disconnect responses
   - `IntegrationStatusResponse` (connected, provider, shopDomainOrSiteUrl, connectedAt)
   - `DisconnectResponse` (disconnected boolean)
   - `StoreProvider` enum (SHOPIFY, WOOCOMMERCE) matching TS contract

2. Created `StoreIntegrationStatusController.java` — unified status/disconnect controller
   - `GET /integrations/store/status` — returns real connection state (Shopify OR WooCommerce)
   - `DELETE /integrations/store/disconnect?provider=SHOPIFY|WOOCOMMERCE` — revokes integration
   - Brand-authenticated via `BrandContextService.requireBrandWorkspace`
   - Workspace-scoped queries via existing repositories
   - Soft-delete (marks `revoked=true`, preserves audit history)

3. Updated `IntegrationHealthService.java` — added WooCommerce support
   - Now checks BOTH Shopify and WooCommerce integrations (as per original javadoc TODO)
   - Added `hasActiveWooCommerceConnection` method
   - Updated `hasActiveStoreIntegration` to OR both checks

**Frontend (TypeScript):**
4. Updated `src/lib/api.ts:1541-1547` — removed NOT_IMPLEMENTED gaps
   - `status()` now calls `GET /integrations/store/status`
   - `disconnect(provider)` now calls `DELETE /integrations/store/disconnect`
   - Mock fallbacks for dev mode

**Security notes:**
- All endpoints require brand authentication (no creator/public access)
- Workspace isolation enforced via `findByWorkspaceIdAndRevokedFalse` repository pattern
- Disconnect is soft-delete (revoked flag) not hard-delete (preserves audit trail)
- Encrypted tokens/secrets never exposed in API responses

**Files changed:**
- `influora-api/src/main/java/com/influora/web/dto/integration/IntegrationDtos.java` (new)
- `influora-api/src/main/java/com/influora/web/StoreIntegrationStatusController.java` (new)
- `influora-api/src/main/java/com/influora/service/IntegrationHealthService.java` (updated)
- `src/lib/api.ts` (updated)

**2026-07-13 — Kavya (Backend QA Review):** ✅ PASS — backend implementation complete per packet log. Endpoints created (`GET /integrations/store/status`, `DELETE /integrations/store/disconnect?provider=...`), security correct (brand-authenticated via `BrandContextService`, workspace-scoped, soft-delete via revoked flag), `IntegrationHealthService` updated to check both Shopify + WooCommerce. `src/lib/api.ts` updated to remove NOT_IMPLEMENTED stubs (lines 1541-1547 per packet). **Frontend wiring still pending** per packet lines 11-12: `src/hooks/brand/useStoreIntegration.ts` + `src/components/brand/settings/StoreIntegrationSetup.tsx` not yet wired to the new API methods.

**2026-07-13 — Ananya (Frontend wiring):** ✅ FE wired to the real endpoints.

Note: two log entries from concurrent passes on this task existed here and have been consolidated into this single accurate account (the earlier duplicate claimed `src/lib/api.ts`'s `StoreProvider` type was lowercase and needed fixing — by the time this pass read the file it was already `'SHOPIFY' | 'WOOCOMMERCE'`, matching the Java enum, so no further change to `api.ts` was needed or made in this pass).

**Confirmed (reading `src/lib/api.ts:1511-1552`):** `storeIntegrations.status()`/`.disconnect()` call the real `GET /integrations/store/status` / `DELETE /integrations/store/disconnect` endpoints in live mode and never throw `ApiError('NOT_IMPLEMENTED', ...)` — that code path is gone from the API layer (mock fallbacks only apply in dev/non-live mode via `mockOr`). `StoreProvider` is `'SHOPIFY' | 'WOOCOMMERCE'` (uppercase), matching `IntegrationDtos.StoreProvider`.

**Changes made:**
1. `src/hooks/brand/useStoreIntegration.ts` — removed the dead `notImplemented` state/branch and the stale docstring describing the pre-backend gap. Hook is now a plain `{ data, loading, error, refresh }` async-data hook matching the existing convention (`useCreatorMetrics.ts`); real failures (network/auth/5xx) surface through `error`.
2. `src/components/brand/settings/StoreIntegrationSetup.tsx` — removed the `notImplemented` amber-alert branch ("can't confirm connection status yet / endpoint hasn't been built") and the `NOT_IMPLEMENTED`-specific disconnect error message (both stale, and the `notImplemented` destructure would no longer compile once removed from the hook). Also fixed a real bug found while wiring: the "Connected" banner compared `status.provider` against lowercase `'shopify'` while the wire contract is uppercase `'SHOPIFY'`, so it would always have rendered "WooCommerce" even when Shopify was the actual connected provider. Now compares against `'SHOPIFY'`.

**Verification (self-run):**
- `npx tsc --noEmit` → clean, no output, exit 0.
- `npm run build` → `tsc --noEmit && vite build` succeeded, exit 0, built in 1m7s. Only pre-existing warnings (duplicate `baseUrl` key in root `tsconfig.json`, large-chunk-size advisories) — unrelated to this change.

**Next:** Meera full verification (`npm run build` + `mvn -o test` + curl checks against `GET /integrations/store/status` / `DELETE /integrations/store/disconnect`, including a disconnect call to confirm the provider casing is correct end-to-end).
