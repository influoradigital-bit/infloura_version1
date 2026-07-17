# QA Review: DPF-3b Loop 2 (Post-Kavya-P1-Fix)
Date: 2026-07-13  
Reviewer: Kavya Reddy (QA Lead)  
Status: ✅ **PASS — ROUTE TO MEERA**

---

## Context
Loop 1 FAILED for over-restriction: charset `[A-Za-z0-9_=&-]` rejected valid creator URLs containing `%` (URL-encoded params like `&ab_channel=My%20Name`) and `.` (params like `&feature=youtu.be`). Vikram fixed it in loop 2. Kabir re-confirmed security is still solid (no server-side decode, no FE render path = no encoded-XSS risk). This is my re-QA.

---

## Files Reviewed
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` (L409-465)
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` (42 total tests)

---

## 1. Over-Restriction RESOLVED ✅

**Original concern (loop 1):** charset `[A-Za-z0-9_=&-]` rejected valid URLs like:
- `https://www.youtube.com/watch?v=abc&ab_channel=My%20Name` (rejected `%`)
- `https://www.youtube.com/watch?v=abc&feature=youtu.be` (rejected `.`)

**Loop 2 fix (L456):**
```java
// OLD (loop 1): [A-Za-z0-9_=&-]
// NEW (loop 2): [A-Za-z0-9_=&%.-]
if (url.matches("^https://(www\\.)?youtube\\.com/watch\\?v=[A-Za-z0-9_-]+(&[A-Za-z0-9_=&%.-]*)?$")
```

**Verdict:** ✅ **FIXED** — `%` and `.` now accepted.

---

## 2. Required Tests Present ✅

### 2a. Accept Tests (my exact failure cases)
- **L860-863** `testMarkPostedAcceptsAbChannelParam` → `&ab_channel=My%20Name` now ACCEPTED
- **L866-869** `testMarkPostedAcceptsFeatureYoutuBeParam` → `&feature=youtu.be` now ACCEPTED
- **L884-890** `testMarkPostedAcceptsEncodedScriptTagAsOpaqueString` → `&x=%3Cscript%3E` ACCEPTED as opaque (documents hard constraint: postUrl must NEVER be URL-decoded server-side or in FE)

### 2b. Length Guard Test (proper isolation)
- **L836-845** `testMarkPostedRejectsOverLengthRegexValidYouTubeUrl`  
  Constructs a 520-char URL with a regex-VALID video-id shape (all `a` chars, matches `[A-Za-z0-9_-]+`). Old test used a regex-invalid URL so the 400 could've come from regex mismatch, not the length guard. This one proves the guard works independently. ✅

### 2c. Quote-XSS Rejects
- **L872-875** `testMarkPostedRejectsDoubleQuoteXssTail` → `&x="onmouseover="` REJECTED
- **L878-881** `testMarkPostedRejectsSingleQuoteXssTail` → `&x='></a>` REJECTED

**Total tests:** 42 (verified via `grep -c "^\s*@Test"`)  
**All required additions:** ✅ PRESENT

---

## 3. Code Standards ✅

- Javadoc L412-429 documents the Kavya P1 fix, the `%` re-admit rationale, and the **hard constraint** (postUrl NEVER URL-decoded server-side or in FE).
- DPF-6 carry-forward clearly documented in javadoc and test L885-887 (FE must not decode before render).
- No TypeScript/React concerns (backend-only change).
- Test names are clear, assertion helpers are clean.

---

## 4. DPF-6 Carry-Forward Acknowledged ✅

Kabir's P2 for DPF-6 (reject only `%3C/%3E/%22/%27/%60` at FE-render) is tracked separately. Not a DPF-3b blocker. Noted.

---

## Final Verdict: ✅ PASS

**Routing:** DPF-3b → **Meera** for final backend verify (build + `mvn test` + manual curl test of the 5 new cases).

**What Meera should verify:**
1. `mvn clean test -Dtest=CreatorDeliverableServiceTest` → 42/42 PASS
2. `mvn test` (full suite) → 927/0F/1E or better
3. Manual API test: POST `/creator/collaborations/{id}/deliverable/mark-posted` with:
   - `postUrl=https://www.youtube.com/watch?v=abc&ab_channel=My%20Name` → 200 OK
   - `postUrl=https://www.youtube.com/watch?v=abc&feature=youtu.be` → 200 OK
   - `postUrl=https://www.youtube.com/watch?v=abc&x="onmouseover="` → 400 INVALID_POST_URL
   - `postUrl=https://www.youtube.com/watch?v=abc&x='></a>` → 400 INVALID_POST_URL
   - `postUrl=https://www.youtube.com/watch?v=<520-char-video-id>` → 400 exceeds maximum length

---

## Next Steps
1. **Meera:** backend verify (build + test + manual curl)
2. **DPF-6:** Kabir's P2 for FE-render encoded-bracket reject (not a DPF-3b block)

**Loop 2 QA:** ✅ COMPLETE
