# Wave D5 Backend Follow-ups for Vikram

**Logged by:** Kavya Reddy (QA Lead)  
**Date:** 2026-07-07  
**Context:** Wave D5 UI QA — two backend gaps identified during honest-gap verification

---

## Background

Wave D5 built frontend UI for:
1. Store integration setup (brand connects Shopify/WooCommerce)
2. Affiliate earnings view (creator sees commission per sale)

Both connect/write actions work (call real backend endpoints), but **read/query/disconnect** endpoints don't exist yet. Frontend handles this with the established honest-gap pattern (amber informational alerts, no fabricated data), but these endpoints are legitimate follow-ups now that the UI shells exist.

---

## Follow-up 1: Integration Status & Disconnect Endpoints

**Priority:** Medium (non-blocking for Wave D ship, but needed for full store-integration UX)

**Scope:**
1. `GET /integrations/status` — brand-workspace-authed, returns which provider (if any) is connected + when
2. `POST /shopify/disconnect` — removes the stored Shopify token for this workspace
3. `POST /woocommerce/disconnect` — removes the WooCommerce integration row for this workspace

**Expected shape (from frontend `api.ts` lines 1331-1336):**
```typescript
interface IntegrationStatus {
  connected: boolean;
  provider: 'shopify' | 'woocommerce' | null;
  shopDomainOrSiteUrl: string | null;
  connectedAt: string | null;  // ISO-8601 timestamp
}
```

**Implementation notes:**
- Status endpoint should follow the same brand-workspace resolution pattern as `ShopifyConnectController`/`WooCommerceConnectController` — call `BrandContextService.requireBrandWorkspace(principal)` to get workspace ID, then check both `shopify_tokens` and `woocommerce_integrations` tables for a row with that `workspace_id`.
- Disconnect endpoints should mirror the connect endpoints' structure — same `@AuthenticationPrincipal` + `BrandContextService` workspace-scoping pattern, just deleting the row instead of upserting it.
- Consider: should disconnect be idempotent (204 even if already disconnected) or return 404 if not connected? Frontend `StoreIntegrationSetup` doesn't currently distinguish, but idempotent is safer.

**Current frontend behavior (when these are NOT_IMPLEMENTED):**
- Status: amber Alert "Can't confirm connection status yet... You can still connect a store below."
- Disconnect: button exists but shows "Disconnecting a store is not available yet — contact support to remove a connection."

**Once these ship:**
- Status Alert disappears, replaced by either "Connected · Shopify" / "Connected · WooCommerce" (green) or "Not connected" (amber).
- Disconnect button becomes functional.

---

## Follow-up 2: Creator Affiliate Earnings Read Endpoint

**Priority:** Medium (non-blocking for Wave D ship, but needed for affiliate earnings UX to show real data)

**Scope:**
`GET /creator/affiliate-earnings` — creator-authed, returns this creator's `AffiliateEarning` rows + summary rollup

**Expected shape (from frontend `api.ts` lines 1451-1469):**
```typescript
interface AffiliateEarningRow {
  id: string;
  campaignId: string;
  campaignName: string;
  brandName: string;
  redemptionId: string;
  orderId?: string;
  orderTotal?: number;
  commissionAmount: number;
  status: 'PENDING' | 'SETTLED' | 'FAILED';
  createdAt: string;  // ISO-8601
}

interface AffiliateEarningsSummary {
  thisMonthSales: number;
  thisMonthRevenue: number;
  thisMonthCommission: number;
  unsettledCommission: number;
}

// Response shape: { data: AffiliateEarningRow[], summary: AffiliateEarningsSummary }
```

**Implementation notes:**
- Should follow the same creator-authed `/me/...` pattern already established by `CreatorPortfolioController.getAnalytics()` (which resolves `/me/portfolio/analytics` from the auth principal) — NOT a path param like `/{creatorId}/affiliate-earnings` (security: path param would allow cross-creator access if not carefully gated, `/me` pattern is inherently safe).
- Query: `SELECT ae.* FROM affiliate_earnings ae JOIN coupon_codes cc ON ae.coupon_code_id = cc.id JOIN collaborations c ON cc.collaboration_id = c.id WHERE c.creator_profile_id = ?` (resolve `creatorProfileId` from auth principal via the same service method `CreatorPortfolioController` uses).
- Summary rollup:
  - `thisMonthSales`: count of rows where `created_at >= start-of-current-month`
  - `thisMonthRevenue`: sum of `order_total` for same rows (if non-null)
  - `thisMonthCommission`: sum of `commission_amount` for same rows
  - `unsettledCommission`: sum of `commission_amount` where `status IN ('PENDING', 'FAILED')` (NOT 'SETTLED')
- Join to get `campaignName` / `brandName`: `affiliate_earnings` → `coupon_codes` → `collaborations` → `campaigns` (for title) + `workspaces` (for brand name).
- Consider: pagination if a creator has hundreds of earnings? Frontend currently renders all in a single table (no pagination UI yet), but the endpoint should be designed to support `?page=1&limit=50` from day one even if frontend doesn't use it yet.

**Current frontend behavior (when this is NOT_IMPLEMENTED):**
- Amber Alert "The backend endpoint that lists your affiliate earnings (`GET /creator/affiliate-earnings`) hasn't been built yet — commission is already being tracked internally per sale, but there's no way for you to read it back here yet. This screen is a UI shell so the design can be reviewed early; it will light up once that endpoint ships."
- Table and summary cards are hidden entirely (not rendered when `notImplemented === true`).

**Once this ships:**
- Alert disappears.
- Table and summary cards populate with real data from `affiliate_earnings` table (which is already being written to by `AffiliateEarningsService.recordEarning()` + `AffiliateSettlementJob` per Wave D4).

---

## Related Context

- **Wave D1:** Shopify OAuth connect (D1) built `ShopifyConnectController.authorize/callback` but no status/disconnect.
- **Wave D2:** WooCommerce connect (D2) built `WooCommerceConnectController.connect` but no status/disconnect.
- **Wave D4:** Affiliate earnings write path (D4) built `AffiliateEarningsService.recordEarning()` + settlement job, entity exists, but no read endpoint.
- **Wave D5:** UI shells for both (store integration settings panel + affiliate earnings table) exist, wired to real routes, but surface honest gaps where read/disconnect endpoints are missing.

**Pattern established in Wave A/B:** When a backend read endpoint doesn't exist yet, frontend renders an amber (not destructive red) informational Alert explaining the gap, with no fabricated data. This is the discipline applied here. Once the endpoints ship, the Alerts disappear and the UI lights up with real data.

---

**Next steps:**
1. File these as Vikram tasks (or add to TASK_INBOX.md if that's the current intake pattern).
2. When implemented, update `api.ts` to replace the `NOT_IMPLEMENTED` rejections with real `http.request()` calls.
3. No frontend changes needed beyond removing the `Promise.reject(new ApiError('NOT_IMPLEMENTED', ...))` stubs — the components already handle the `notImplemented: false` case correctly.

---

**Reviewer:** Kavya Reddy  
**Timestamp:** 2026-07-07 19:52 UTC
