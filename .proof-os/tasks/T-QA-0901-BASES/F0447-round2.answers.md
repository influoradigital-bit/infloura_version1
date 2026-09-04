# F-0447 Round 2 — Answers (read from current source, 2026-09-04)

### 1. Cancelled or withdrawn server-side — does the row still appear, and what happens on click-through?

Yes, the row still appears, and this is a real gap. Two separate facts drive it. First, nothing anywhere in the backend ever writes the CANCELLED contract status — the only occurrences of that enum constant in ContractService are read-side guards (the duplicate-contract existence check at line 230 and comments), so there is no brand-facing contract cancel or withdraw endpoint at all. A brand cannot withdraw a contract; the closest real action is cancelling the underlying deal, which flips CollaborationStatus, not ContractStatus.

Second, the unsigned query filters on the contract row only — its status and creatorSignedAt and the creator-ownership subquery. It never joins the collaboration's own status. So a contract whose deal was cancelled after the brand signed keeps status PENDING_SIGNATURES forever and keeps showing in this list as an action the creator must take.

On click-through the creator lands on the deal contract tab and, if they sign, the write is rejected at the signature guard with a 409, after the row promised them an action they cannot complete.
`influora-api/src/main/java/com/influora/service/ContractService.java:723` "collaboration.getStatus() == CollaborationStatus.CANCELLED"

### 2. Does the query exclude contracts the creator already signed?

Yes — this one is genuinely correct. The JPQL carries an explicit null check on the creator signature timestamp alongside the ownership subquery, so a contract the creator has already countersigned cannot appear. It is also belt-and-braces: once both timestamps are set, the entity advances itself to ACTIVE, which the status predicate on the preceding line already excludes.
`influora-api/src/main/java/com/influora/repository/ContractRepository.java:80` "AND c.creatorSignedAt IS NULL"

### 3. Do unsent brand drafts appear, or is there a status gate?

There is a real status gate and unsent drafts do not appear. The query requires PENDING_SIGNATURES explicitly, and a freshly generated contract is not in that state: the generate path builds the entity without calling the status setter, so the builder default applies, and the only writer that moves a contract to PENDING_SIGNATURES is the private advance step that runs inside recordBrandSignature and recordCreatorSignature. No send or finalize action exists that flips DRAFT to PENDING_SIGNATURES without a signature, and the public status setter has no call sites in main. So membership in this list means at least one party has actually signed.
`influora-api/src/main/java/com/influora/domain/entity/Contract.java:301` "c.status = ContractStatus.DRAFT"
`influora-api/src/main/java/com/influora/repository/ContractRepository.java:79` "SELECT c FROM Contract c WHERE c.status"

### 4. What renders for a contract with zero milestones?

The row renders fine and reads as 0 milestones, plural, because the count is null-coalesced to zero and the plural suffix is chosen off the same expression. Nothing crashes and no blank gap appears. Note the state is also close to unreachable from the normal path: generate rejects a null or empty milestone list with MILESTONES_REQUIRED before any contract row is written, so a zero-milestone contract only exists if the payment_milestones rows were removed after the fact.
`src/pages/creator-dashboard.tsx:573` "{contract.milestones?.length ?? 0} milestone"

### 5. Does a newly arrived contract appear without a manual refresh?

No. The creator must reload the page or navigate away and back. The unsigned list is fetched exactly once from an effect whose dependency array is empty, there is no polling interval, no websocket or SSE subscription for this list, and no visibilitychange or window focus listener anywhere in creator-dashboard.tsx. A contract sent while the creator sits on this page is invisible to them until they navigate. This is a genuine staleness gap, not a nitpick — the page also shows a card description promising the brand has already signed, so the cost of missing a row is a signature that silently never happens.
`src/pages/creator-dashboard.tsx:341` "await api.contracts.listUnsigned"

### 6. Does the row link pass the correct contractId, and what shows on a 404 or slow load?

No — it does not pass a contract id at all. The link is built from the record's collaborationId plus a tab query parameter pointing at the contract panel; the contract id is used only as the React key. That is defensible on its own, since the destination re-fetches the deal's contract, but the failure mode is bad. The destination resolves the deal by finding the id in the already-loaded deal-room list and, on a miss, silently falls back to the first room in the list. So if the collaboration is not in the creator's deals payload for any reason, the creator is dropped onto some other deal's contract tab with no error and no 404 — a wrong-record outcome, which is worse than an error boundary. If the deals list is empty or failed, the fallback yields null and the creator sees the page's own empty or error state rather than anything tied to the contract they clicked.
`src/pages/creator-chat.tsx:773` "dealRooms.find((d) => d.id === selectedDealId) ?? dealRooms[0] ?? null"

### 7. What renders when campaignName or brandName is null or empty?

Neither field exists, so the question is moot in one direction and a real gap in the other. The backend response record has no campaign or brand name component at all — it carries ids, money, signature timestamps, dates, terms and milestones — and the frontend row correspondingly renders only the formatted rupee total and the milestone count. Nothing can crash on a null name because no name is ever read. The honest negative is that a creator with several pending contracts sees rows distinguished only by an amount and a milestone count, with no brand or campaign to identify which contract is which.
`influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java:238` "public record ContractResponse("
`src/pages/creator-dashboard.tsx:570` "{formatINR(contract.totalAmount)}"

### 8. Any other creator surface showing a pending-contract count, and does it use the same query?

Yes, and it uses a different query, which is a real inconsistency on the same screen. The Pending actions tile on this very page derives its contribution by filtering the deal rows from the deals list endpoint on their contractStatus, not from the unsigned endpoint — the code comment says this is deliberate, to avoid a second request. Those two numbers can legitimately disagree: a contract the creator has already signed while the brand has not is still PENDING_SIGNATURES on the deal row, so it inflates the tile while correctly being absent from the list below it. The reverse skew also exists, since the deal row exposes only one contract per collaboration.

Beyond that, api.contracts.listUnsigned has exactly one call site in the whole frontend — this dashboard. No notification type, no email, no Meera creator context and no sidebar reads it.
`src/pages/creator-dashboard.tsx:150` "dealRows.filter((d) => d.contractStatus ==="

### 9. Navigating away and back — fresh data or a stale cached snapshot?

Fresh. The list lives in plain component state populated by a mount effect; there is no react-query hook on this page despite the dependency being installed, and no memory cache, request dedupe or Cache-Control handling in the api module's request layer. A route change unmounts the page, so returning to it re-runs the effect and re-hits the endpoint. The stale-data risk on this feature is not caching — it is question 5, staying on the page.
`src/pages/creator-dashboard.tsx:335` "React.useEffect(() => {"

### 10. Sort key, tie-breaking, and pagination stability?

Sorted newest-first by creation time, with no secondary sort key — and the ties are more likely than they look. The unsigned query orders by createdAt descending only, and the contracts table stores that column as a plain TIMESTAMP with no fractional-seconds precision, so every write is truncated to whole seconds. Two contracts a brand signs within the same second therefore tie exactly, and with no tie-breaker the database is free to return them in either order, so the two rows can swap places between refetches. Adding the contract id as a secondary descending key would make it deterministic. Pagination is not a factor either way: the endpoint takes no page or limit parameter and returns the entire list in one response.
`influora-api/src/main/java/com/influora/repository/ContractRepository.java:82` "ORDER BY c.createdAt DESC"
`influora-api/src/main/resources/db/migration/V10__contracts_and_milestones.sql:15` "created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
