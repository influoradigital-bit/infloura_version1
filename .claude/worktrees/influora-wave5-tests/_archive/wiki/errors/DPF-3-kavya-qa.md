# QA Review: DPF-3 mark-posted Endpoint
**Date:** 2026-07-13  
**Reviewer:** Kavya (QA Lead)  
**Status:** ⚠️ CONDITIONAL PASS  
**Next Step:** Route to Meera for local verification

---

## VERDICT: ⚠️ CONDITIONAL PASS — Minor improvements recommended for DPF-3b

Core functionality is solid, contract correct, state machine sound, standards compliant. **No blocking issues.** Kabir's 2 security notes confirmed as non-blocking and accurately assessed. Recommend tracking as separate DPF-3b follow-up rather than loop back to Vikram now.

---

## 1. CONTRACT CORRECTNESS ✅ PASS

**Endpoint path/method:**
- ✅ Controller: `POST /creator/deliverables/{deliverableId}/mark-posted` (L104)
- ✅ Mapping matches spec exactly
- ✅ Service method signature correct: `markPosted(AuthPrincipal, String, MarkPostedRequest)`

**Request DTO (`MarkPostedRequest`):**
- ✅ Single field: `String livePostUrl` (L94)
- ✅ Properly validated in service (not null/blank check L333-338)

**Response DTO (`MarkPostedResponse`):**
- ✅ Returns exactly `{ id, status, postUrl, postedAt }` (L96)
- ✅ All fields populated correctly in service (L344-348):
  - `deliverable.getId()` → id
  - `DeliverableStatus.POSTED` → status
  - `deliverable.getPostUrl()` → postUrl (validated URL)
  - `deliverable.getPostedAt()` → postedAt (timestamp)

**Response envelope:**
- ✅ Wrapped in `ApiResponse.ok()` (L110)
- ✅ Returns `200 OK` (no custom status code, default)

---

## 2. STATE MACHINE ✅ PASS

**Transition guard (L326):**
```java
if (deliverable.getStatus() != DeliverableStatus.APPROVED) {
    throw new ApiException("INVALID_STATE", 
        "Deliverable must be approved before marking as posted", 
        HttpStatus.CONFLICT);
}
```
- ✅ ONLY `APPROVED` → `POSTED` allowed
- ✅ Correct error code `INVALID_STATE` / 409 Conflict
- ✅ Error message is user-facing clear

**What's correctly blocked:**
- ✅ Cannot mark POSTED from DRAFT/PENDING/SUBMITTED → must go through brand approval first
- ✅ Cannot mark POSTED twice → after first call status=POSTED, guard fails with CONFLICT
- ✅ Cannot skip approval flow → state-machine enforced server-side

**Entity state update (`Deliverable.applyMarkPosted` L266-272):**
- ✅ Sets `postUrl` to validated URL
- ✅ Sets `status` to `POSTED`
- ✅ Sets `postedAt` to `Instant.now()`
- ✅ Calls `touch()` to update `updatedAt`

---

## 3. IDOR PROTECTION ✅ PASS

**Ownership enforcement (L324, calls L385-394):**
```java
Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);
```
- ✅ Query: `findByIdAndCreatorUserId(deliverableId, principal.getUserId())`
- ✅ Creator A cannot mark creator B's deliverable → 404 `DELIVERABLE_NOT_FOUND`
- ✅ User ID from authenticated principal, never from request body
- ✅ `CreatorContextService.requireCreatorProfile(principal)` called first (L323)

---

## 4. URL VALIDATION (SSRF/INJECTION PROTECTION) ✅ PASS

**Validation method (`validatePlatformPostUrl` L411-434):**

**HTTPS requirement (L412-417):**
- ✅ Blocks `http://`, `file://`, `javascript:`, `data:` schemes
- ✅ Only `https://` accepted
- ✅ Error message clear: "URL must be HTTPS..."

**Platform whitelist:**
- ✅ Instagram: `^https://(www\.)?instagram\.com/(p|reel)/[A-Za-z0-9_-]+/?$`
- ✅ YouTube: `^https://(www\.)?youtube\.com/watch\?v=[A-Za-z0-9_-]+(&.*)?$` OR `^https://youtu\.be/[A-Za-z0-9_-]+$`
- ✅ Anchor enforcement: `String.matches()` implicitly anchors, patterns have explicit `^…$`
- ✅ Blocks subdomain spoofing (`instagram.com.attacker.com`), path traversal, localhost, internal IPs

**What's correctly blocked (per Kabir's audit):**
- ✅ SSRF: URL never fetched server-side (confirmed via grep — no `RestTemplate`/`WebClient` sink)
- ✅ CRLF injection: `.` does not match newlines, rejected
- ✅ Unknown platforms: explicit error "platform not recognized"

---

## 5. STANDARDS COMPLIANCE ✅ PASS

**Java/Spring conventions:**
- ✅ `@Transactional` on service method (L320)
- ✅ `@PostMapping` + `@PathVariable` + `@RequestBody` correct
- ✅ `@AuthenticationPrincipal AuthPrincipal` used consistently
- ✅ No loose types — all params explicitly typed

**Error handling:**
- ✅ Uses `ApiException` with proper codes: `INVALID_STATE` (409), `INVALID_REQUEST` (400), `INVALID_POST_URL` (400)
- ✅ Messages are user-facing clear, not stack traces

**Code quality:**
- ✅ Method is focused — single responsibility (validate, update, save)
- ✅ No code duplication — reuses `requireOwnedDeliverable` pattern
- ✅ Clean separation: controller delegates to service, service delegates to entity method

---

## 6. TESTS ❌ MISSING (NON-BLOCKING)

**Current state:**
- `CreatorDeliverableServiceTest.java` exists (L1-672) with 18 tests
- `CreatorDeliverableControllerTest.java` exists (L1-197) with 6 tests
- ❌ **NO tests for `markPosted` method** — neither unit nor integration

**What should be tested:**
1. Happy path: APPROVED deliverable → 200, status=POSTED, postUrl set, postedAt set
2. State guard: DRAFT/PENDING/SUBMITTED → 409 INVALID_STATE
3. Already posted: call twice → second call fails 409
4. Invalid URL: non-HTTPS, unknown platform, malformed → 400 INVALID_POST_URL
5. IDOR: foreign deliverable → 404 DELIVERABLE_NOT_FOUND
6. Null/blank URL → 400 INVALID_REQUEST

**Why non-blocking:**
- Existing test patterns (`reportMetrics`, `submit`, `upload`) provide strong coverage model
- Logic is straightforward — single state guard + validation + entity update
- Kabir's offensive audit confirmed no exploitable gaps
- DPF-3 is not a money-path (unlike escrow/payout, where tests are mandatory)

**Recommendation:** Add tests in DPF-3b follow-up (same PR as the 2 hardening fixes below).

---

## 7. KABIR'S 2 NOTES — DECISION: TRACK AS DPF-3b, NON-BLOCKING

### M-DPF3-1 (MEDIUM) — YouTube regex accepts `<>"'` in query string

**What Kabir found (DPF-3-kabir-redteam.md L39-51):**
```java
url.matches("^https://(www\\.)?youtube\\.com/watch\\?v=[A-Za-z0-9_-]+(&.*)?$")
```
The `(&.*)?` tail accepts ANY characters after `&`, including `<>"'` → stored verbatim in `post_url`.

**Why it's MEDIUM not CRITICAL:**
- ✅ Kabir confirmed: `post_url` is **NOT rendered in frontend today** (grep `src/` returned nothing)
- ✅ React would auto-escape if rendered as text node
- ✅ No `dangerouslySetInnerHTML` in codebase using this field
- ✅ Latent stored-XSS risk ONLY if future code renders `postUrl` in `href` or unsafe context

**Why it's still a real issue:**
- ⚠️ Defense-in-depth gap — the moment brand UI adds "View Post →" link, this becomes exploitable
- ⚠️ Instagram pattern is clean (`[A-Za-z0-9_-]+/?$`), YouTube should match that tightness

**Fix recommendation (Kabir L52):**
- Constrain query tail: `[?&][A-Za-z0-9_=&%-]*$`
- OR parse with `java.net.URI`, validate host + extract only `v` param

**Kavya's verdict:** Track as DPF-3b, fix when wiring brand viewer UI (likely DPF-2 extension). NOT blocking ship today.

---

### L-DPF3-2 (LOW) — No length guard before 500-char DB column

**What Kabir found (DPF-3-kabir-redteam.md L54):**
- `Deliverable.post_url` column is `VARCHAR(500)` (L80)
- `validatePlatformPostUrl` has no max length check
- Combined with M-DPF3-1's `&.*`, a 501+ char URL passes validation → throws uncontrolled 500 at persist

**Why it's LOW:**
- Legitimate Instagram/YouTube URLs are <150 chars typically
- Attacker needs APPROVED deliverable (brand-gated) + craft >500-char URL matching the regex
- Result is 500 error, not data corruption

**Fix recommendation:** Add explicit length check in validator:
```java
if (url.length() > 500) {
    throw new ApiException("INVALID_POST_URL", 
        "URL exceeds maximum length", HttpStatus.BAD_REQUEST);
}
```

**Kavya's verdict:** Track as DPF-3b. Low priority — natural URL length is <500, exploitation requires state APPROVED.

---

## 8. SUMMARY

| Checklist Item | Status | Notes |
|---------------|--------|-------|
| Contract correctness | ✅ PASS | Path, DTO, response shape all match spec |
| State machine | ✅ PASS | Only APPROVED→POSTED, correct error |
| IDOR protection | ✅ PASS | Ownership enforced, foreign 404 |
| SSRF protection | ✅ PASS | HTTPS-only, whitelist, no fetch sink |
| Standards compliance | ✅ PASS | `@Transactional`, typed, clean errors |
| Tests | ❌ MISSING | Non-blocking, add in DPF-3b |
| Kabir M-DPF3-1 | ⚠️ TRACKED | Fix YouTube regex in DPF-3b |
| Kabir L-DPF3-2 | ⚠️ TRACKED | Add length guard in DPF-3b |

---

## FINAL VERDICT: ⚠️ CONDITIONAL PASS

**Ship as-is for DPF-3:** ✅ YES
- Core functionality sound
- No exploitable security gaps today (Kabir confirmed)
- Contract correct, state machine enforced

**Follow-up work (DPF-3b):**
1. Tighten YouTube regex to block `<>"'` in query string
2. Add 500-char length guard
3. Write 6 unit tests for `markPosted`

**Routing:**
- ✅ Route to **Meera** for local verification (`mvn test`, functional test of endpoint)
- After Meera ✅ → Priya code review
- DPF-3b to be scheduled separately (non-blocking for Phase A close)
