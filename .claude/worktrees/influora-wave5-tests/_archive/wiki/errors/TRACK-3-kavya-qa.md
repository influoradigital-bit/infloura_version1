# QA Review: TRACK-3 — Creator Coupon redirectUrl (Click-Tracking Link)
**Date:** 2026-07-13  
**Reviewer:** Kavya (QA Lead)  
**Developer:** Vikram (Backend)  
**Status:** ✅ PASS — route to Meera for local verification

---

## CRITICAL PATH-RESOLUTION CHECK ✅

**The entire point of TRACK-3 is a link that ACTUALLY resolves to the click-tracking endpoint.**

### Endpoint Declaration
- `ConversionWebhookController.java` line 282: `@GetMapping("/track/click/{utmCampaignId}")`
- No class-level `@RequestMapping` prefix (controller only has `@RestController` annotation)
- Endpoint path: `/track/click/{utmCampaignId}`

### Application Context Path
- `application.yml` line 32-33: `server.servlet.context-path: /api/v1`
- ALL endpoints in this app are served under `/api/v1` prefix

### URL Construction
- `CreatorCouponService.java` line 107: `apiPublicUrl + "/track/click/" + utm.getId()`
- `apiPublicUrl` injected from `${influora.api.public-url}` (application.yml line 84)
- Default value: `http://localhost:8080/api/v1` (includes context-path)

### Full Path Resolution
| Component | Value |
|-----------|-------|
| Endpoint declaration | `/track/click/{utmCampaignId}` |
| Context-path prefix | `/api/v1` |
| **ACTUAL resolved endpoint** | `http://localhost:8080/api/v1/track/click/{utmCampaignId}` |
| **Constructed redirectUrl** | `http://localhost:8080/api/v1/track/click/{utmCampaignId}` |
| **Result** | ✅ EXACT MATCH — link resolves correctly |

**No double-prefix, no missing prefix, no 404 risk. The constructed URL will resolve to the endpoint.**

---

## STANDARD QA CHECKS

### 1. redirectUrl null handling ✅
- Line 106-107: `utmLink.map(...).orElse(null)` — null when no UTM link exists
- Test coverage: `CreatorCouponServiceTest.java` line 137-152 (`testListWithoutTrackingUrl`)
- Frontend can guard on it (same as `trackingUrl`)

### 2. utmCampaignId correctness ✅
- Uses `utm.getId()` — the PK of `UtmCampaign` entity
- `ConversionWebhookController.trackClick` line 290-296 does `utmCampaignRepository.findById(utmCampaignId)` — correct PK lookup
- No mismatch

### 3. Config pattern ✅
- Follows R2Properties pattern: `@Value` injection, `.env.example` updated, no hardcoded domain
- `application.yml` line 76-84 documents the property (matches R2/Meta/MSG91 property javadoc pattern)
- `.env.example` line 6-9 adds `API_PUBLIC_URL` with clear comment

### 4. Security ✅
- No API keys hardcoded
- Config value comes from env var (`API_PUBLIC_URL`)
- `.env.example` shows dev default, prod deployment must override (documented in yml comment line 82-83)

### 5. Tests meaningful ✅
- `CreatorCouponServiceTest.java`:
  - Line 155-175: explicit test that `redirectUrl` is the click-tracking link (not raw brand URL)
  - Line 119: asserts exact format `{apiPublicUrl}/track/click/{utmId}`
  - Line 174: sanity check `redirectUrl != trackingUrl` (catches the bug this task fixes)
  - 5/5 tests passing per task brief

### 6. TypeScript/DTO ✅
- `CreatorCouponDtos.java` line 41: `redirectUrl` added to record
- Javadoc line 19-26 explains the distinction from `trackingUrl` and marks `trackingUrl` as display-only
- No breaking change (new field is nullable, backward-compatible)

### 7. Performance ✅
- No N+1 queries (line 94-96: single batch lookup of `UtmCampaign` via `findByCampaignIdAndCreatorProfileId`)
- String concat on line 107 is trivial overhead

---

## ISSUES FOUND

**NONE.**

---

## VERDICT

**✅ PASS** — route to Meera for local verification.

The constructed URL (`{apiPublicUrl}/track/click/{utmCampaignId}`) genuinely resolves to the endpoint (`GET /track/click/{utmCampaignId}` under servlet context-path `/api/v1`). No path mismatch, no 404 risk, no functional breakage.

All standard QA checks green. Tests cover the critical behavior (redirectUrl is the click-tracking link, not the raw brand URL). Config follows established patterns. No security issues.

---

## NEXT STEPS

1. Meera: local verification
   - `mvn clean install` — expect 954/0F/1E (green, per task brief)
   - `curl http://localhost:8080/api/v1/creators/coupons` with valid creator JWT — check `redirectUrl` field in response
   - Verify `redirectUrl` format: `http://localhost:8080/api/v1/track/click/{utmCampaignId}`
   - Optional: seed a UTM campaign, hit the `/track/click/{id}` endpoint, verify it records click + 302s to brand URL

2. Update `SHARED_CONTEXT.md`:
   - Kavya verdict: PASS
   - Next: Meera local verification
