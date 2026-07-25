# Shipment / Confirm-Receipt Backend — Architecture Decision

**Author:** Priya (CTO) · **Date:** 2026-07-24 · **Status:** APPROVED — build today
**Scope:** Product-seeding deals — brand ships a physical product, creator confirms receipt.
**Verified against:** `Collaboration`, `DealController`/`DealService`, `CreatorContextService`, `BrandContextService`, `CollaborationRepository`, `Campaign`, `ShipmentCreatedEvent`/`ShipmentReceivedEvent`, `NotificationListener`.

---

## 1. Entity — `Shipment` (new)

**Cardinality: 1:1 with Collaboration** (`UNIQUE(collaboration_id)`). Re-ships do NOT create a new row — a `DAMAGED` shipment is re-shipped by overwriting tracking and transitioning back to `SHIPPED` on the same row. Simplest correct model for v1; 1:many is a future migration if a re-ship history is ever needed.

**Address: flat columns, NOT embedded JSON.** Address is a fixed, bounded value object with per-field validation (pincode format, required recipient/phone) and DB-level NOT NULL/length constraints. Matches the `CreatorBankAccount` / tax-identity flat-column precedent, not the `Campaign.*Json` precedent (those are variable-shape lists exclusive to a subtype). No field is ever queried, but validation clarity wins.

| Column | Type | Notes |
|---|---|---|
| `id` | CHAR(26) PK | ULID (`Ulids.newUlid()`) |
| `collaboration_id` | CHAR(26) | **UNIQUE**, FK-by-convention to `collaborations.id` (no hard FK — repo convention here is soft refs) |
| `status` | VARCHAR, `@Enumerated(STRING)` | `ShipmentStatus`, NOT NULL |
| `recipient_name` | VARCHAR(200) | NOT NULL once address provided |
| `address_line1` | VARCHAR(300) | NOT NULL |
| `address_line2` | VARCHAR(300) | nullable |
| `city` | VARCHAR(120) | NOT NULL |
| `state` | VARCHAR(120) | NOT NULL |
| `pincode` | VARCHAR(12) | NOT NULL, validate `^[1-9][0-9]{5}$` (IN) |
| `phone` | VARCHAR(20) | NOT NULL, digits/`+` only |
| `product_name` | VARCHAR(300) | nullable until brand ships; **source of the event `productName`** (see §5) |
| `carrier` | VARCHAR(120) | nullable until SHIPPED |
| `tracking_number` | VARCHAR(120) | nullable until SHIPPED |
| `tracking_url` | VARCHAR(500) | nullable |
| `condition_note` | TEXT | creator's note on receipt (esp. when DAMAGED) |
| `received_condition` | VARCHAR, `@Enumerated(STRING)` | `GOOD` / `DAMAGED`, null until receipt |
| `created_at` / `updated_at` | DATETIME | `Instant`, `updated_at` bumped on every transition |

Sanitize `condition_note` and all free-text address fields via `TextSanitizer.sanitizePlainText` in the service (same as `DealService`).

## 2. Enum — `ShipmentStatus`

```
AWAITING_ADDRESS → ADDRESS_PROVIDED → SHIPPED → RECEIVED
                                          ↘ DAMAGED → (re-ship) SHIPPED
```

`AWAITING_ADDRESS` is the **synthetic initial state** returned by GET when no row exists yet (row is lazily created on first address submit as `ADDRESS_PROVIDED`). `received_condition` enum (`GOOD`/`DAMAGED`) is separate from status so a `RECEIVED` row still records whether it arrived damaged.

## 3. Migration

**Convention:** the active scheme is timestamped `V<yyyyMMddHHmmss>__name.sql` (legacy plain `V68` numbers sort *before* these — Flyway compares numerically, so date versions always win). Latest on disk: `V20260723120000`.

**Vikram: create `influora-api/src/main/resources/db/migration/V20260724120000__shipments.sql`** (bump the time if another migration lands today first). One `CREATE TABLE shipments` with the columns in §1, `UNIQUE KEY uq_shipments_collaboration (collaboration_id)`, index not otherwise needed.

## 4. Endpoints — on existing `DealController` (`@RequestMapping("/deals")`)

New `ShipmentService` (inject `CollaborationRepository`, `CreatorContextService`, `BrandContextService`, `CampaignRepository`, `WorkspaceRepository`, `CreatorProfileRepository`, `ShipmentRepository`, `ApplicationEventPublisher`, `DealMessageRepository`). **Do NOT expand `DealService`** — sibling service, same trust-boundary finders.

| Route | Method | Actor | Guard (pre-state → post-state) |
|---|---|---|---|
| `/deals/{id}/shipping-address` | POST | **creator** | any of {none, `AWAITING_ADDRESS`, `ADDRESS_PROVIDED`} → `ADDRESS_PROVIDED`. Lazy-creates row. **Rejected once `SHIPPED`** (`SHIPMENT_ALREADY_SHIPPED`, 409) |
| `/deals/{id}/shipment` | POST | **brand** | requires `carrier`+`trackingNumber`+`productName`. `ADDRESS_PROVIDED` or `DAMAGED` → `SHIPPED`. Rejected if `AWAITING_ADDRESS` (`SHIPMENT_NO_ADDRESS`, 409). **Publishes `ShipmentCreatedEvent`** |
| `/deals/{id}/shipment/confirm-receipt` | POST | **creator** | body `condition` ∈ {GOOD,DAMAGED} + optional note. `SHIPPED` → `RECEIVED`(GOOD)/`DAMAGED`. Rejected if not `SHIPPED` (`SHIPMENT_NOT_SHIPPED`, 409). **Publishes `ShipmentReceivedEvent`** |
| `/deals/{id}/shipment` | GET | **both** | dual-role read; returns synthetic `{status:AWAITING_ADDRESS}` if no row |

On each transition, append a `DealMessage` with `DealMessageKind.shipment` (via the existing append pattern) so the deal-room timeline reflects address/shipped/received — the enum value already exists for this.

## 5. AuthZ — cross-tenant write prevention (CRITICAL)

**Reuse the exact finders that are already the platform trust boundary — invent no new ownership query.** These are what `DealService.requireOwnedCollaboration` uses:

- **Creator endpoints:** `creatorContext.requireCreator(principal)` then `collaborationRepository.findByIdAndCreatorId(dealId, principal.getUserId())` → 404 `DEAL_NOT_FOUND` if not owned. Scopes at SQL level to the caller's `creator_id`. IDOR-closed.
- **Brand endpoint:** `brandContext.requireBrandWorkspace(principal)` then `collaborationRepository.findByIdAndWorkspaceId(dealId, workspace.getId())`. That finder joins `collaboration.campaignId → campaign.workspace_id` (collaborations carry no `workspace_id`); a brand from another workspace gets 404. IDOR-closed.
- **GET (both):** branch on `principal.getUserType()` and call the matching finder above — never an unscoped `findById`.

**Vikram — watch:** the `Shipment` is loaded by `collaboration_id` *after* the collaboration ownership check passes. Never load a Shipment by its own `id` from a path/body param and trust it — always: authorize the collaboration first, then `shipmentRepository.findByCollaborationId(collaboration.getId())`. That ordering is the whole IDOR guard.

## 6. Event wiring — exact field derivation

Both events already have ready `NotificationListener` handlers: `ShipmentCreatedEvent` → routes creator to `/creator/shipments/{entityId}` (line 190); `ShipmentReceivedEvent` → routes brand to `/brand/shipments/{entityId}` (line 339). Field derivation mirrors `DealService.notifyFirstMessage` exactly:

Load once: `campaign = campaignRepository.findById(collaboration.getCampaignId())`; `workspaceId = campaign.getWorkspaceId()`.

**`ShipmentCreatedEvent`** (on brand mark-shipped → notifies **creator**):
- `userId` = `collaboration.getCreatorId()` (recipient)
- `workspaceId` = `campaign.getWorkspaceId()`
- `entityId` = `collaboration.getId()`
- `brandName` = `workspaceRepository.findById(workspaceId).getName()`, fallback `"The brand"`
- `productName` = `shipment.getProductName()` (brand-supplied on this call)
- `trackingUrl` = `shipment.getTrackingUrl()`

**`ShipmentReceivedEvent`** (on creator confirm-receipt → notifies **brand**):
- `userId` = `brandContext.resolveBillingRecipient(workspaceId).userId()` (workspace OWNER; null-check → skip publish if null, exactly like `notifyFirstMessage`)
- `workspaceId` = `campaign.getWorkspaceId()`
- `entityId` = `collaboration.getId()`
- `creatorName` = `creatorProfileRepository.findByUserId(collaboration.getCreatorId()).getDisplayName()`, fallback `"The creator"`
- `productName` = `shipment.getProductName()`

**`productName` gap resolved:** there is NO product field on `Campaign` or `Collaboration`. Do **not** try to derive it from campaign title. The brand supplies `productName` in the `POST /deals/{id}/shipment` body; it is persisted on the Shipment and read back for both events. This is the single clean source.

## 7. Frontend contract (for Ananya)

Replaces the two `setTimeout` fakes in `src/pages/creator-chat.tsx`: `handleSubmitShippingAddress` (L753) → `POST /deals/{id}/shipping-address`; `handleConfirmReceipt` (L767) → `POST /deals/{id}/shipment/confirm-receipt`. Add `api.shipments.{getAddress? , submitAddress, confirmReceipt, get}` to `src/lib/api.ts`. Brand mark-shipped is a new brand-side deal-room action. All four return the full Shipment DTO (status + fields) so the UI renders from server state, never optimistic-fakes it.

---

### Go / No-Go: **GO.** No new infra, no new dependency, no TECH-STACK change. New table + entity + service + 4 routes on an existing controller, reusing the existing trust boundary and two pre-built notification events.

**Risks for Vikram to watch:** (1) authorize collaboration → then load Shipment by `collaboration_id` — never trust a shipment id directly; (2) enforce state guards server-side (can't ship before address, can't confirm before shipped) — return 409, don't rely on FE; (3) `resolveBillingRecipient` can return null → skip the received-event publish, never NPE; (4) validate pincode/phone server-side (FE zod is UX only, not a boundary); (5) `UNIQUE(collaboration_id)` — on concurrent first-address submits use upsert-or-catch, mirror the `findByIdForUpdate` lock pattern if a race shows up.
