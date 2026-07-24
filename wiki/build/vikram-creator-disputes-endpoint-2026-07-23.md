# Creator disputes list endpoint — Vikram — 2026-07-23

## Gap as reported

QA found `src/pages/creator-disputes.tsx` self-reporting "Showing partial data — no
dispute-list endpoint for creators yet": the creator disputes page derived its list
client-side from `GET /deals` filtered to `status === 'DISPUTED'`, which drops the
dispute reason, review stage, and resolution notes. Brand had a real dispute-list
endpoint (`GET /brand/disputes/list`, P2-14); creator did not have the equivalent.

## What was actually found on investigation

The backend endpoint **already existed** on this branch, unwired from the frontend:

- `GET /creator/disputes` — `influora-api/src/main/java/com/influora/web/CreatorDisputeController.java:29`
- `DisputeService#listDisplayForCreator` — `influora-api/src/main/java/com/influora/service/DisputeService.java:457`
- Tenant-scoped repository query — `influora-api/src/main/java/com/influora/repository/DisputeRepository.java:43` (`findWithCollaborationByCreatorUserId`)
- Response DTO (shared with the brand endpoint) — `influora-api/src/main/java/com/influora/web/dto/dispute/DisputeDtos.java:60` (`DisputeListItemResponse`)

So the real gap was two-fold:
1. **No test coverage** proving the creator endpoint is tenant-safe (zero tests referenced
   `listDisplayForCreator` before this change).
2. **Frontend never called it** — `src/lib/api.ts`'s `creatorDisputes.list()` still derived
   partial rows from `/deals`, and `creator-disputes.tsx` still rendered the "partial data"
   banner unconditionally in live mode.

This handoff closes both. No new backend endpoint, DTO, or repository query was needed —
those were built correctly already; this pass adds the missing test coverage and wires the
frontend to the endpoint that already exists.

## Endpoint

`GET /api/v1/creator/disputes` (base path per `TECH-STACK.md` / existing controller convention)

- Auth: `@AuthenticationPrincipal AuthPrincipal principal`, same pattern as every other
  `/creator/*` controller.
- No request body/params — the caller's identity is the only input.

## Tenant safety

`DisputeService.listDisplayForCreator` (`DisputeService.java:457-463`):

```java
public List<DisputeListItemResponse> listDisplayForCreator(AuthPrincipal principal) {
    creatorContext.requireCreator(principal);
    List<Object[]> rows =
            disputeRepository.findWithCollaborationByCreatorUserId(principal.getUserId());
    return buildDisputeDisplayRows(rows, UserType.CREATOR);
}
```

- `creatorContext.requireCreator(principal)` rejects non-creator principals and
  already-deleted accounts (`CreatorContextService.java`) before any query runs.
- The repository query is parameterized **only** with `principal.getUserId()` — never a
  client-supplied id — so there is no IDOR vector: a creator cannot ask for another
  creator's disputes by any request shape, because the id used to filter never comes from
  the request.
- The query itself (`DisputeRepository.java:39-47`) filters
  `Collaboration.creatorId = :creatorUserId`, mirroring the same
  collaboration-ownership discipline `DealService`/`EscrowService` use elsewhere.

## Test coverage added

`influora-api/src/test/java/com/influora/service/DisputeServiceTest.java`

- `listDisplayForCreatorHappyPath` — verifies the caller's own dispute row comes back with
  campaign name, counterparty (brand workspace) name, dispute status, and reason correctly
  populated from the joined `Dispute`/`Collaboration`/`Campaign`/`Workspace` rows.
- `listDisplayForCreatorTenantIsolation` — stubs the repository so a *different* creator's
  dispute row only exists behind `findWithCollaborationByCreatorUserId(OTHER_CREATOR_ID)`
  (marked `lenient()` since it's intentionally expected never to be consulted), then asserts:
  - the repository is `never()` queried with the foreign id, and
  - the foreign dispute's `collaborationId` never appears in the returned list.

Both tests pass; `DisputeServiceTest` overall now `Tests run: 17, Failures: 0, Errors: 0`
(15 pre-existing + 2 new).

## Frontend wiring

- `src/lib/api.ts`:
  - `creatorDisputes.list()` now calls `http.request<CreatorDisputeRow[]>('GET', '/creator/disputes', { role: 'creator' })`
    in live mode (was: `disputedDealsAsRows('creator')`, a client-side derivation from `/deals`).
  - Removed the now-unused `disputedDealsAsRows` helper.
  - Updated the `DisputeRow` doc comment — creator side no longer needs the "still derived,
    banner fires" caveat.
- `src/pages/creator-disputes.tsx`:
  - Removed the `hasPartialData` flag and the amber "Showing partial data" `Alert` block —
    the live endpoint always returns full detail now, same as the brand page.
  - Removed the now-unused `isApiLive` import.

## Files changed

- `influora-api/src/test/java/com/influora/service/DisputeServiceTest.java` — 2 new tests +
  `Campaign` import + `lenient` static import
- `src/lib/api.ts` — `creatorDisputes.list()` rewired, `disputedDealsAsRows` removed, doc
  comment updated
- `src/pages/creator-disputes.tsx` — partial-data banner and `isApiLive` import removed

No changes to `creator-layout.tsx`, `App.tsx`, `creator-copilot.tsx`, or `creator-deals.tsx`.

## Build/test results

- `mvn -o -q compile` (bundled `.tools/apache-maven-3.9.10`) → **BUILD SUCCESS**, exit 0.
- `mvn -o -Dtest=DisputeServiceTest test` → **Tests run: 17, Failures: 0, Errors: 0**.
- Frontend: `npx tsc --noEmit` and `npm run build` — see final handoff message for results
  (run took longer than the inline shell timeout; results appended once complete).
