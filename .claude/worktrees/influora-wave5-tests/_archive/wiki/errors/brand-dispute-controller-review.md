# QA Review: B7 — GET /brand/disputes
Date: 2026-07-09
Reviewer: Kavya (QA Lead)
Status: PASS WITH NOTES

---

## Summary
Read-only brand-scoped dispute list endpoint. The CRITICAL cross-tenant join check PASSES — the JPQL query in DisputeRepository.findByWorkspaceId correctly mirrors CollaborationRepository.findByWorkspaceId's structure. Pagination, authz, and DTO extension are clean. No blocking issues found.

Two HIGH notes flagged: missing test coverage (standard gap across the API), and a clarification needed on whether the DTO change is truly backward-compatible for existing clients.

---

## CRITICAL CHECK: Cross-Tenant Data Leak (PASS ✅)

**DisputeRepository.findByWorkspaceId JPQL join (line 21-24):**
```java
@Query(
    "SELECT d FROM Dispute d WHERE d.collaborationId IN "
        + "(SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
        + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
Page<Dispute> findByWorkspaceId(@Param("workspaceId") String workspaceId, Pageable pageable);
```

**Reference: CollaborationRepository.findByWorkspaceId (line 24-26):**
```java
@Query(
    "SELECT c FROM Collaboration c WHERE c.campaignId IN "
        + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId)")
List<Collaboration> findByWorkspaceId(@Param("workspaceId") String workspaceId);
```

**Verdict: STRUCTURALLY IDENTICAL** — adds one outer wrapper (`d.collaborationId IN (SELECT c.id...)`) around the exact same campaign-to-workspace join. The trust boundary is correctly resolved:
- Disputes → Collaborations → Campaigns → Workspace
- Workspace ID comes from `BrandContextService.requireBrandWorkspace(principal)` (line 154 DisputeService) — never client-suppliable
- No way for a brand in Workspace A to see disputes from Workspace B

**Tested trust boundary:**
- `requireBrandWorkspace` resolves workspace from JWT `principal.getWorkspaceId()` OR looks up the calling user's active workspace membership (BrandContextService line 34-56) — both server-derived, not path-param or query-string suppliable
- Join requires `campaign.workspaceId = :workspaceId` at the innermost subquery — if a dispute's collaboration points to a campaign not in the caller's workspace, it is filtered out
- Orphaned/null campaign/collaboration links are also filtered (the IN clause requires a match)

**No cross-tenant leak found.**

---

## TECH-STACK.md Compliance (PASS ✅)

### Security
- ✅ No API keys in code
- ✅ No hardcoded credentials
- ✅ Auth principal from JWT only — no trusted path params (`DisputeService.listForBrand` line 153-154 — workspace resolved server-side via `BrandContextService.requireBrandWorkspace`)
- ✅ Idempotency: not required here (read-only endpoint, no mutation)

### Code Standards
- ✅ All types explicit (no `any` — Java, N/A)
- ✅ No unused imports (checked BrandDisputeController, DisputeService, DisputeDtos, DisputeRepository)
- ✅ No console logging in production code

### Architecture
- ✅ Follows pattern: BrandXController → XService → Repository
- ✅ Trust boundary resolved in service layer (`BrandContextService.requireBrandWorkspace`), not controller
- ✅ Read-only transaction annotation (`@Transactional(readOnly = true)` — DisputeService line 152)

---

## Pagination & Consistency (PASS ✅)

**WalletController.transactions (reference pattern, line 122-130):**
```java
@GetMapping("/transactions")
public ResponseEntity<ApiResponse<List<WalletTransactionRowResponse>>> transactions(
    @AuthenticationPrincipal AuthPrincipal principal,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int limit) {
    creatorContext.requireCreator(principal);
    var result = walletService.getTransactionsForUser(principal.getUserId(), page, limit);
    return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
}
```

**BrandDisputeController.list (line 34-40):**
```java
@GetMapping
public ResponseEntity<ApiResponse<List<DisputeResponse>>> list(
    @AuthenticationPrincipal AuthPrincipal principal,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int limit) {
    var result = disputeService.listForBrand(principal, page, limit);
    return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
}
```

**Verdict: IDENTICAL CONVENTION**
- Same defaultValue (page=1, limit=20)
- Same clamping logic in service layer (DisputeService line 155-156 vs WalletService line 206-207):
  - `safePage = Math.max(page, 1)` — negatives become 1
  - `safeLimit = Math.min(Math.max(limit, 1), 100)` — clamps to 1-100 range
- Same PageMeta structure (page, limit, total, hasMore)
- Same sort order (newest-first via `Sort.by(Sort.Direction.DESC, "createdAt")`)

**No way to request unbounded/negative page size.**

---

## DisputeResponse DTO Extension (NEEDS CLARIFICATION — HIGH ⚠️)

**DisputeDtos.DisputeResponse (line 15-25) — three new fields added:**
```java
public record DisputeResponse(
    String id,
    String collaborationId,
    String openedByType,
    String openedByUserId,
    String reason,
    String status,
    Instant createdAt,
    String resolvedByAdminId,      // NEW
    String resolutionNotes,         // NEW
    Instant resolvedAt)             // NEW
```

**Existing endpoints that return DisputeResponse:**
1. `POST /deals/{dealId}/disputes` (DealController line 118-126) — openDispute
2. `POST /admin/disputes/{disputeId}/resolve` (AdminDisputeController line 31-38) — resolve

**Question: Is this a breaking change?**
- **Java records are positional constructors** — if any existing client is constructing DisputeResponse directly (not just deserializing from JSON), adding fields at the end is a compile-time break
- **JSON deserializers** (Jackson, etc.) typically ignore unknown fields on read, so adding fields is safe for clients reading responses
- **But**: if any client is doing strict schema validation (e.g. OpenAPI client with additionalProperties: false), this could fail

**The toResponse mapper (DisputeService line 204-216) populates all three new fields:**
```java
dispute.getResolvedByAdminId(),   // null for OPEN disputes
dispute.getResolutionNotes(),      // null for OPEN disputes
dispute.getResolvedAt()            // null for OPEN disputes
```

**For OPEN disputes** (from DealController.openDispute), all three will be null — existing clients will see three new null fields.
**For RESOLVED disputes** (from AdminDisputeController.resolve), all three will be populated.

**Verdict:**
- ✅ No Java code in this repo constructs DisputeResponse directly (only DisputeService.toResponse does)
- ✅ Existing endpoints (DealController, AdminDisputeController) do not break — they just return more fields now
- ⚠️ **Unknown external client impact** — if a frontend or third-party client has a strict schema (e.g. TypeScript interface with exact fields), they will see three unexpected fields

**Recommendation: Flag as a HIGH note for Meera to verify — have the frontend team confirm their DisputeResponse type can accept the new fields (or is using a lenient deserializer).**

---

## Missing Test Coverage (HIGH ⚠️)

**No test coverage found for:**
1. BrandDisputeController
2. DisputeService.listForBrand
3. DisputeRepository.findByWorkspaceId

**Standard gap** (same as most of the API — flagged debt). The JPQL join is CRITICAL to get right, so this is a higher-priority gap than usual.

**Recommended test cases (for when test infra is ready):**
1. Happy path: brand sees only their own workspace's disputes
2. Cross-tenant isolation: brand A cannot see brand B's disputes
3. Pagination: verify page/limit clamping (negative, zero, >100)
4. Empty result: brand with no disputes gets empty list + valid PageMeta
5. Orphaned data: dispute whose collaboration/campaign link is null or points to a deleted campaign is filtered out (or fails gracefully)

---

## VERDICT: PASS WITH NOTES

### PASS ✅
- Critical cross-tenant join is correct
- Pagination matches WalletController convention
- Auth boundary correctly enforced
- TECH-STACK.md compliant
- No security violations

### HIGH NOTES (fix before delivery)
1. **Missing test coverage** — critical join logic untested (standard gap, not a blocker for B7 but flag as priority debt)
2. **DTO backward compatibility** — confirm frontend can accept the three new DisputeResponse fields (resolvedByAdminId, resolutionNotes, resolvedAt) without breaking

### MEDIUM NOTES (document, no fix required)
- DisputeResponse now returns resolution metadata for ALL disputes (null for OPEN, populated for RESOLVED) — this is by design but should be documented in the API spec so clients know to handle nulls

---

## NEXT STEPS
1. Route to Meera for local verification (build + curl check)
2. Meera should verify: `curl -H "Authorization: Bearer <brand-token>" http://localhost:8080/api/v1/brand/disputes?page=1&limit=5` returns 200 + PageMeta, scoped to calling brand only
3. Frontend team (Ananya) should confirm DisputeResponse type accepts the new fields without breaking existing open/resolve flows

---

## FILES REVIEWED
- influora-api/src/main/java/com/influora/web/BrandDisputeController.java (new)
- influora-api/src/main/java/com/influora/web/dto/dispute/DisputeDtos.java (DisputeResponse extended)
- influora-api/src/main/java/com/influora/service/DisputeService.java (listForBrand + PagedDisputes record + toResponse updated)
- influora-api/src/main/java/com/influora/repository/DisputeRepository.java (findByWorkspaceId JPQL)
- influora-api/src/main/java/com/influora/repository/CollaborationRepository.java (reference join pattern)
- influora-api/src/main/java/com/influora/service/BrandContextService.java (requireBrandWorkspace authz)
- influora-api/src/main/java/com/influora/domain/entity/Dispute.java (confirmed new fields exist)
- influora-api/src/main/java/com/influora/web/WalletController.java (pagination reference)
- influora-api/src/main/java/com/influora/service/WalletService.java (getTransactionsForUser pagination logic)
- influora-api/src/main/java/com/influora/web/DealController.java (existing openDispute endpoint)
- influora-api/src/main/java/com/influora/web/AdminDisputeController.java (existing resolve endpoint)
