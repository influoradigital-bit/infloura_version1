# QA Review: P2-6 Admin Moderation Controller
Date: 2026-07-12
Reviewer: Kavya
Status: **REJECTED**

## Summary
Backend implementation follows TECH-STACK.md and security patterns correctly. Frontend wiring has a critical contract mismatch that will cause runtime failures.

---

## Issues Found

### CRITICAL (must fix before any testing)

**1. Backend service ignores reason field from request**

**Location:** `influora-api/src/main/java/com/influora/service/admin/AdminModerationService.java:84-89`

**Issue:** The backend DTO `ActionFlagRequest` correctly accepts optional `reason` field (per `AdminModerationDtos.java:54`), but the service method signature extracts only the `action` field and ignores `reason`:

```java
public FlagDto actionFlag(
    AuthPrincipal principal,
    jakarta.servlet.http.HttpServletRequest request,
    String flagId,
    String action) // ❌ Should be ActionFlagRequest, not String
```

The controller (line 76) passes `request.action()` directly, discarding the reason the admin typed.

**Impact:** Audit trail logs will not include the admin's explanation for the action. The UI collects the reason (FlagQueue.tsx:261) but it's lost server-side.

**Fix required:** Change `AdminModerationService.actionFlag()` signature to accept `ActionFlagRequest body` instead of `String action`, then extract `body.reason()` and include it in the audit log calls (lines 117-152).

---

**2. API contract path mismatch between frontend and backend**

**Location:** `src/admin/services/api-contracts.ts:487` vs `influora-api/src/main/java/com/influora/web/AdminModerationController.java:43`

**Issue:** Frontend calls `/moderation/flags` but backend is mounted at `/admin/moderation` (full path `/api/v1/admin/moderation/flags`).

**Frontend:**
```typescript
apiRequest<PaginatedResponse<ContentFlag>>(
  `/moderation/flags?...` // ❌ Missing /admin prefix
)
```

**Backend:**
```java
@RequestMapping("/admin/moderation") // Full path: /api/v1/admin/moderation
```

**Impact:** API calls will return 404 Not Found.

**Fix required:** Update `api-contracts.ts` to use `/admin/moderation/flags` prefix for both `getContentFlags` and `actionFlag`.

---

### HIGH (fix before delivery)

**3. Response type mismatch between DTO and entity enums**

**Location:** `influora-api/src/main/java/com/influora/web/dto/admin/AdminModerationDtos.java:30-31`

**Issue:** `FlagDto` declares `flaggedBy` as `ContentFlagSource` (enum) but the frontend `admin.types.ts` expects `flaggedBy: 'AI' | 'USER' | 'ADMIN'` (string literal). If `ContentFlagSource` serializes as enum name this will work, but should be verified.

**Verification needed:** Confirm `ContentFlagSource` enum values match the frontend contract exactly (AI, USER, ADMIN).

---

### MEDIUM (fix when possible)

None identified.

---

## Security Review

✅ **PASS** — Role-based access control correctly implemented:
- `AdminModerationService.listFlags()` and `.actionFlag()` both gate via `adminContext.requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN, SUPPORT)`
- MFA satisfaction checked via `AdminContextService` (same pattern as `AdminDashboardController`)
- Audit trail logged via `AdminAuditLogService` for all actions

✅ **PASS** — No workspace isolation violations (content flags are platform-wide, scoped by admin role)

✅ **PASS** — No API keys or secrets in code

---

## TECH-STACK.md Compliance

✅ Spring Boot controller pattern followed
✅ JWT auth via `@AuthenticationPrincipal AuthPrincipal`
✅ `@Transactional` on write operations
✅ No raw SQL — uses existing `ContentFlagRepository`
✅ Exception handling via `ApiException`
✅ No `any` types in TypeScript
✅ React Query for data fetching
✅ Component naming follows PascalCase

---

## Next Steps

**BLOCK** — Route back to:
1. **Ananya** — Fix frontend issues #1 and #2 above (contract mismatch + path mismatch)
2. **Vikram** — Consider adding `reason` field to backend DTO per issue #3

Re-submit when fixed. Will re-review contract alignment before Meera verification.
