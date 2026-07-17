# Brand Deal Room (B-1, 60% → live) — Vikram backend handoff

**Item:** `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` PART 1 / P1 — "Deal Room (60% → live) — 11
prop-driven components need real persistence instead of external/local-only state."
**Owner split:** Vikram (backend, this report) + Ananya (frontend, next cycle).
**Date:** 2026-07-11.

## TL;DR

Deal Room's backend was mostly already live. The one real gap was a documented, previously-known
missing route: `GET /deals/{dealId}/deliverables`. Implemented it. Everything else that looks
"local-only" in `brand-chat.tsx` falls into one of two buckets:

1. **Backend already built, frontend never wired it** (chat messages) — Ananya's next-cycle task,
   contract confirmed below.
2. **Genuinely no backend concept exists at all** (shipment) — a product-scope gap, not an
   endpoint-missing gap. Documented as backlog for Priya, not built.

## Investigation

Read (per task instructions): the 11 prop-driven `src/components/brand/deal-room/*` components,
`src/pages/brand-chat.tsx` (2037 lines — the real routed page; `deal-room-dashboard.tsx` /
`brand-deals.tsx` confirmed still dead/unrouted, untouched), `src/lib/api.ts` `deals`/`messages`/
`deliverables`/`contracts` client surfaces, `DealController`/`DealService`/`ContractController` and
related entities, and the earlier-closed "Deals (40%→live)" / "Contracts (40%→live)" tracker entries
for the established conventions to reuse.

Went through every piece of state in `brand-chat.tsx` that looked local-only:

| State / handler | Backend status found | Action taken |
|---|---|---|
| `chatMessages` / `handleSendMessage` | **Backend fully exists and is live**: `DealMessage` entity, `GET/POST /deals/{id}/messages`, `POST /deals/{id}/messages/read` (`DealService.listMessages/sendMessage/markRead`) — dual-role via `requireOwnedCollaboration`, `TextSanitizer.sanitizePlainText` on all free text, already used successfully by `creator-chat.tsx`. `src/lib/api.ts`'s `messages.list/send/markRead` client is complete and typed. **`brand-chat.tsx` just never calls it** — it keeps its own local `chatMessages` array instead. This is a frontend wiring gap, not a backend gap. | Confirmed the contract is solid; added missing brand-role + cross-workspace test coverage (see below) since Ananya is about to point brand traffic at it for the first time. No backend code change needed beyond tests. |
| `brandDeliverableRows` / `loadBrandDeliverables` (`api.deliverables.list('brand', dealId)`) | **Route did not exist.** `src/lib/api.ts` called `GET /deals/{dealId}/deliverables` and the comment literally said "not yet built" — confirmed via grep there was no matching `@GetMapping` anywhere in `influora-api`. This is the exact gap the earlier "Contracts (40%→live)" cycle surfaced and left as a backlog note. | **Built it.** See "What shipped" below. |
| Deal proposals (`handleSendProposal`), counter-offers (`handleCounterSubmit`), contract sign/PDF (`handleSignContract`/`handleDownloadContractPdf`), deliverable approve/revise (`handleApproveDeliverable`/`handleReviseSubmit`) | Already live-wired in prior cycles (`api.deals.create/counter`, `api.contracts.sign/pdfDownloadUrl`, `api.deliverables.approve/requestRevision`). Confirmed still correct, no regressions from this change. | No action — out of scope, already `[x]` via earlier tracker items. |
| `shipment` / `handleSubmitShipment` / `shippingAddress` | **No backend concept exists at all.** Grepped `influora-api` for `shipment`/`shipping` — found only `DealMessageKind.shipment` (an unused chat-message tag) and unused `ShipmentCreatedEvent`/`ShipmentReceivedEvent` notification types nothing ever publishes. No `Shipment` entity, no `Collaboration` field, no endpoint. The code already documents this honestly (comment block above `handleSubmitShipment`, "Not saved to the backend" Alert in the UI) — it is deliberately kept as local-only state rather than faking a success against a real backend. | **Did not build.** This needs a product decision first (does Deal Room even own shipping in v1? what fields does an address/tracking model need? which courier integration, if any?) before an entity/migration/endpoint makes sense — that's a scope call for Priya, not a "confirm+wire" task like the deliverables list was. Flagged as its own backlog line below. |

## What shipped (backend, working tree only — not committed)

### New endpoint: `GET /deals/{dealId}/deliverables`

- **`influora-api/src/main/java/com/influora/web/DealController.java`** — new `@GetMapping("/{dealId}/deliverables")` → `dealService.listDeliverables(principal, dealId)`.
- **`influora-api/src/main/java/com/influora/service/DealService.java`**:
  - New constructor dependency `DeliverableRepository deliverableRepository` (Spring-autowired, no config needed — it's already a bean used by `BrandDeliverableService`/`CreatorDeliverableService`).
  - New method `listDeliverables(AuthPrincipal principal, String dealId)`:
    ```java
    Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
    return deliverableRepository
            .findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId())
            .stream()
            .map(CreatorDeliverableService::toListItem)
            .toList();
    ```
  - Reuses `requireOwnedCollaboration` — the exact same dual-role (brand-workspace-scoped via
    `findByIdAndWorkspaceId`, creator-owned via `findByIdAndCreatorId`) helper `listMessages`/
    `sendMessage` already use. Foreign-workspace/foreign-creator probes get a uniform 404
    `DEAL_NOT_FOUND`, same as every other Deal Room read.
  - Reuses `deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc` — already existed, used
    by the creator-side list endpoint, no new query needed.
- **`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java`** — widened
  `private static DeliverableListItem toListItem(Deliverable)` to package-visible `static` so
  `DealService` (same `com.influora.service` package) can call it directly instead of duplicating
  the `Deliverable` → `DeliverableListItem` mapping (completed-status derivation, description
  fallback, revision-count-based `currentRevision`/`maxRevisions`). No behavior change to the
  existing creator-side caller.
- **No migration.** The `deliverables` table, `Deliverable` entity, and `DeliverableListItem` DTO
  (`id`, `title`, `description`, `status`, `completed`, `currentRevision`, `maxRevisions`) all
  already existed from the Week 3 creator-deliverables build — this was purely a missing brand-facing
  route over data that was already being persisted correctly.
- **`src/lib/api.ts`** — updated the stale `deliverables.list` comment ("brand-side list (not yet
  built)") to point at this change and at this report. No client behavior change — the existing
  `deliverables.list(role, dealId)` function already calls exactly this path; it just used to 404.

### Endpoint contract for Ananya (frontend wiring, next cycle)

```
GET /deals/{dealId}/deliverables
Auth: brand or creator JWT (role inferred from AuthPrincipal, not a query param)
Response: ApiResponse<DeliverableListItem[]>

DeliverableListItem = {
  id: string;
  title: string;
  description: string;
  status: 'PENDING' | 'DRAFT' | 'SUBMITTED' | 'REVISION_REQUESTED' | 'RESUBMITTED'
        | 'APPROVED' | 'REJECTED' | 'POSTED' | 'METRICS_REPORTED' | 'VERIFIED';
  completed: boolean;
  currentRevision: number | null;
  maxRevisions: number | null;
}
```

This is byte-for-byte the same shape as `src/lib/api.ts`'s existing `CreatorDeliverableListItem`
interface and the existing `GET /creator/deliverables?collaboration_id=` response — no frontend type
changes needed, `brand-chat.tsx`'s `mapBrandListItemToTabItem`/`brand-deliverable-utils.ts` mapping
functions should work unchanged. Concretely, this closes the `deliverablesListGap` honest-gap UI
(`brand-chat.tsx:585`, the "Deliverable list pending... `GET /deals/:id/deliverables` is not built
yet" alert and the equivalent inline-timeline gap notice) — Ananya can remove the gap-state branches
once wired, since `api.deliverables.list('brand', dealId)` will now return real rows instead of
throwing.

### Chat message wiring — contract confirmation for Ananya (not built by me, backend already exists)

`brand-chat.tsx`'s `chatMessages`/`handleSendMessage`/`mockTimelineEvents` need to be replaced with
the same pattern `creator-chat.tsx` already uses successfully:

```
GET  /deals/{dealId}/messages?before=<ISO-8601 instant>   → DealMessage[]   (api.messages.list)
POST /deals/{dealId}/messages   { content, kind }          → DealMessage    (api.messages.send)
POST /deals/{dealId}/messages/read                         → { ok: true }   (api.messages.markRead)
```

`DealMessage.kind` is one of `text | system | proposal | contract | deliverable | payment |
shipment` — the chat feed's inline proposal/contract/payment/deliverable cards in `brand-chat.tsx`
map directly onto message `kind` + `metadata`, so the existing card-rendering logic in the file
should be reusable, just re-pointed at real `DealMessage[]` rows instead of `mockTimelineEvents`.
One pre-existing, non-blocking finding worth carrying into that wiring: `api.messages.send` always
sends a client `Idempotency-Key` header, but `DealService.sendMessage` never reads/uses it (messages
aren't money-adjacent so this isn't a security issue — same class of finding Kabir logged as
non-blocking LOW on `deals.counter` during the "Deals (40%→live)" cycle).

## Tests added (`influora-api/src/test/java/com/influora/service/DealServiceTest.java`)

Gap found: `listMessages`/`sendMessage` were already dual-role in production code but only had
creator-path test coverage — no brand-role test, no cross-workspace rejection test, even though the
service method they share (`requireOwnedCollaboration`) is the load-bearing security boundary. Added,
mirroring the file's existing `DealServiceTest`/`ContractServiceTest` style:

- `testSendMessageBrandRole` — brand sends a message on their own workspace's deal; asserts
  `senderType == brand` and `senderId == BRAND_USER_ID`.
- `testListMessagesRejectsForeignWorkspace` — brand principal, deal not found in their workspace →
  `DEAL_NOT_FOUND` / 404, verifies `dealMessageRepository.findPageBefore` never called.
- `testSendMessageRejectsForeignWorkspace` — same, for the write path; verifies
  `dealMessageRepository.save` never called.
- `testListDeliverablesBrandHappyPath` — brand sees deliverables for their own deal, ordered by
  slot index, status/`completed` mapping verified for both an `APPROVED` and a `SUBMITTED` row.
- `testListDeliverablesRejectsForeignWorkspace` — brand cross-workspace rejection (the explicitly
  requested test per the task brief), verifies `deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc`
  never called.
- `testListDeliverablesCreatorHappyPath` — confirms the same endpoint is dual-role-safe for the
  creator side too (creator's own deal, empty deliverables list).

`DealServiceTest`'s constructor call and `@Mock` list were updated for the new `DeliverableRepository`
dependency; all pre-existing tests are otherwise unchanged.

## Test results

**No `mvn` available in this environment** (same limitation flagged in every prior Vikram cycle in
`SHARED_CONTEXT.md` / `wiki/processes/api-docs.md`). Hand-reviewed every changed file line-by-line
for signature/import/type correctness against the actual entity builders, repository method
signatures, and DTO records (`Deliverable.Builder`, `DeliverableRepository.findByCollaborationIdOrderBySlotIndexAsc`,
`DeliverableListItem` record field order/types, `SendMessageRequest`/`DealMessageResponse` records).
No route conflicts (`grep`-confirmed `/deals/{dealId}/deliverables` is not claimed by
`BrandDeliverableController` (`/deliverables/**`), `CreatorDeliverableController`
(`/creator/deliverables/**`), or `DeliverableMetricController` (`/deliverables/**`, disjoint sub-paths)).
No other file constructs `DealService` directly except this test (`grep -rln "new DealService("`),
so no other call site needed updating.

**Handing to Kavya for QA, then Kabir** — Deal Room is flagged security-review-required in
`BRAND_ADMIN_PENDING_WORK.md` (money-adjacent: deliverables gate escrow release eligibility), then
**Meera** for `mvn compile` / `mvn test` as the actual build gate.

## Files touched

- `influora-api/src/main/java/com/influora/web/DealController.java`
- `influora-api/src/main/java/com/influora/service/DealService.java`
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` (one method visibility widened)
- `influora-api/src/test/java/com/influora/service/DealServiceTest.java`
- `src/lib/api.ts` (comment-only — stale gap note corrected, no behavior change)
- `wiki/processes/api-docs.md` (new dated section)
- `wiki/errors/brand-deal-room-B1-vikram-backend.md` (this report)

## Gaps / backlog for Priya (not built, need a product decision first)

1. **Shipment/shipping-address persistence** — no `Shipment` entity, no fields on `Collaboration`,
   no endpoint. Building this needs: an address-collection data model (creator side already has a
   local-only `ShippingAddressData` form too — same gap on `creator-chat.tsx`), a shipment entity
   (courier, tracking number/URL, status lifecycle), and a decision on whether/how it integrates with
   a real courier API or stays manual-entry. Not scoped as "implement the missing endpoint" — this is
   a new feature surface, not a persistence gap in an existing one. Recommend a dedicated tracker item
   (its own B-N line) rather than folding it into B-1's remaining scope.
2. **`messages.send`'s unused client `Idempotency-Key` header** — non-blocking, noted above, carried
   forward for whoever picks up the frontend chat-wiring so it isn't re-discovered from scratch.

## Next steps

- **Ananya**: wire `brand-chat.tsx`'s chat feed to `api.messages.list/send/markRead` (mirror
  `creator-chat.tsx`), and wire the Deliverables tool panel to the now-real `api.deliverables.list`
  response (remove the `deliverablesListGap` gap-state UI).
- **Kabir**: security pass on the new `GET /deals/{dealId}/deliverables` route (workspace isolation
  already mirrors the audited `listMessages`/`sendMessage` pattern, but flagging per the mandatory
  money-adjacent review rule) and, separately, on brand chat once Ananya wires it live.
- **Kavya**: QA gate on this backend change once Kabir clears it.
- **Meera**: `mvn compile` / `mvn test` build verification.
- **Priya**: scope decision on shipment persistence (item 1 above).
