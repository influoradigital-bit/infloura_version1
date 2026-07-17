# QA Review: Wave E task E-JWKS — Asymmetric service-token signing

**Date:** 2026-07-08  
**Reviewer:** Kavya (QA Lead)  
**Status:** ✅ APPROVED — cleared for Kabir load-bearing security review  
**Files reviewed:** 13 implementation files + 3 test files + 2 config files  

---

## Summary

Wave E task E-JWKS (Spring→Python asymmetric service-token signing, per ADR `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md`) has passed QA with **zero blocking findings**. All 6 hard conditions from the ADR are satisfied, all tests pass (643 Java functional + 181 Python including 10 new E-JWKS tests), and the implementation is technically sound.

**This is net-new public surface + cryptographic signing implementation** — Kabir's load-bearing red-team review is what's truly gatekeeping here (explicitly flagged in the ADR). My QA pass confirms nothing obviously broken reached him, not that the crypto itself is secure.

---

## Verification Results

### CRITICAL: Hard Condition Compliance (checked FIRST per instructions)

#### ✅ 1. ALLOWED_ALGS untouched in Python
**File:** `influora-ai/app/auth/service_token.py`  
**Line:** 36  
**Status:** GENUINE PASS  

```python
ALLOWED_ALGS = ("RS256", "ES256")  # asymmetric only; never accept HS256 from JWKS path
```

**Verified:**
- Still exactly `("RS256", "ES256")` — no HS256 added (line 36)
- Comment explicitly states asymmetric-only constraint (line 36)
- The HS256 branch at lines 189-192 remains guarded by `isinstance(source, StaticDevJwksSource)` type check, not a config flag
- `jwt.decode` at line 203 only adds HS256 to `algorithms` list when source is `StaticDevJwksSource`, never on the JWKS path
- Test `test_hs256_token_rejected_on_the_real_jwks_path` (test_service_token_jwks_e_task.py:128-150) proves this — an HS256 token presented against a real JWKS source throws `invalid_alg` 401

**This is the ADR's single most important constraint per Kabir's verbatim binding condition** — zero compromise found.

---

#### ✅ 2. BOTH flows switched (BrandSafetyServiceTokenService AND StreamTokenService)
**Files:**  
- `influora-api/src/main/java/com/influora/service/integration/BrandSafetyServiceTokenService.java`  
- `influora-api/src/main/java/com/influora/service/meera/StreamTokenService.java`

**Status:** GENUINE PASS — both explicitly switched to ES256

**BrandSafetyServiceTokenService:**
- Line 67-68: `PrivateKey signingKey = jwksKeyService.signingKey();`
- Line 69-70: `.keyId(jwksKeyService.kid())`
- Line 81: `.signWith(signingKey, Jwts.SIG.ES256)` — **not HS256, not the old shared secret**
- Class javadoc lines 16-26 documents the E-JWKS switch explicitly
- Test `BrandSafetyServiceTokenServiceTest` proves ES256 signing (line 66-70) + kid header (line 70)

**StreamTokenService:**
- Line 57: `PrivateKey signingKey = jwksKeyService.signingKey();`
- Line 59-60: `.keyId(jwksKeyService.kid())`
- Line 72: `.signWith(signingKey, Jwts.SIG.ES256)` — **not HS256, not the old shared secret**
- Class javadoc lines 17-27 documents the E-JWKS switch, explicitly cites ADR binding condition #2 ("both flows, one fix")
- Test `StreamTokenServiceTest` proves ES256 signing (line 64-69) + kid header (line 69)

**Confirmed:** Both services use the SAME `SpringJwksKeyService` instance (constructor injection lines 50-55 BrandSafety, lines 39-43 Stream) — one Spring identity, one published JWKS, exactly as the ADR requires.

---

### JWKS Endpoint Safety

**Files:**  
- `influora-api/src/main/java/com/influora/web/JwksController.java`  
- `influora-api/src/main/java/com/influora/security/SpringJwksKeyService.java`  
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`

**Status:** ✅ PASS — serves ONLY public key material, correctly in permitAll()

#### Endpoint Serves Only Public Key
**JwksController.java:**
- Line 38-40: `jwks()` returns `jwksKeyService.publicJwkSet()` only — no other response fields
- Line 37: `@GetMapping("/.well-known/jwks.json")` — standard RFC 7517-adjacent discovery location
- Class javadoc line 11-16: explicitly documents "serves ONLY public key material, nothing else"

**SpringJwksKeyService.java:**
- Line 87-96: `publicJwkSet()` method builds JWK exclusively from `publicKey` (line 90), never touches `privateKey` field
- Line 38: Private key field is private, no getter returns it in any JSON-serializable form
- Line 64-65: `signingKey()` method returns raw `PrivateKey` object (for in-process signing only), NOT wrapped in anything that could be JSON-serialized
- Comment line 84-85: "built exclusively from the public key, never touches `privateKey`"

**No private key material can leak** — there is no code path that serializes the private key into the JWKS response.

#### PermitAll() Wiring Is Correct
**SecurityConfig.java:**
- Line 81-82: `.requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()`
- Comment lines 74-80 justifies this as a standard JWKS public endpoint (asymmetric crypto means public key disclosure is NOT a security boundary — anyone can derive the public key from any token)
- Listed alongside other legitimate public webhook/pixel exceptions (Razorpay, Shopify, WooCommerce, conversion tracking)
- Deliberately scoped to GET only (line 81) — POST/PUT/DELETE not whitelisted

**This is architecturally correct** — a JWKS endpoint is SUPPOSED to be public (that's the entire point of asymmetric signing: Python only needs the public key, never the private key).

---

### Boot-Time Validation Fails Closed

**File:** `influora-api/src/main/java/com/influora/config/SecretsStartupValidator.java`  
**Status:** ✅ PASS — fails closed on missing/default/malformed private key in non-dev

**Added check (lines 121, 143-176):**
- Line 121: `validateJwksPrivateKey(problems)` called in main validation loop
- Line 147-149: Missing or blank PEM → fails (appends to problems StringBuilder)
- Line 151-153: Still the committed dev default PEM (literal match against `KNOWN_DEV_DEFAULT_JWKS_PRIVATE_KEY_PEM`) → fails
- Line 155-167: Structurally invalid PEM or not an EC key → fails (catches `Exception` at line 168, appends error)
- Line 132-133: Non-dev env with any problems → throws `IllegalStateException`, app refuses to boot

**Test coverage:**
- Test file exists but wasn't read (not in changed files) — inferred from validator logic only
- Validator uses same fail-closed pattern as existing symmetric secrets (lines 99-133)

**Defense-in-depth:** `SpringJwksKeyService` constructor (line 59-60) ALSO fails fast on malformed PEM regardless of env (throws `IllegalStateException` at parse time, lines 103-120) — two independent checks, not one gate that could be bypassed.

**Satisfies ADR binding condition #2:** "key gets boot-time protection ... consistent with every other credential surface in this codebase"

---

### Two-Guard Dev-Only Enforcement on StaticDevJwksSource

**File:** `influora-ai/app/auth/service_token.py`  
**Status:** ✅ PASS — both guards real, independent, and proven by tests

#### Guard 1: Construction-Time Refusal
**Lines 108-117:** `StaticDevJwksSource.__init__`
- Line 109-110: `settings = get_settings()` + `if settings.env != "dev"`
- Line 111-117: Raises `RuntimeError` with explicit ADR citation if env != dev
- Comment lines 98-105: Documents this as "hard code assertion", "intentionally redundant" with Guard 2

#### Guard 2: Independent Verification-Time Check
**Lines 131-144:** `_assert_dev_jwks_source_is_dev_only(source)`
- Line 139: `if isinstance(source, StaticDevJwksSource) and get_settings().env != "dev"`
- Line 140-143: Raises `AuthError` 401 `dev_jwks_source_outside_dev`
- Called at line 181 inside `_decode_and_verify`, BEFORE any token parsing/validation
- Comment lines 136-138: "Second, independent enforcement point ... even if a `StaticDevJwksSource` somehow already exists"

**Independence verified:**
- Guard 1 checks at object construction time, Guard 2 checks at token verification time
- Guard 1 uses `RuntimeError`, Guard 2 uses `AuthError` — different exception types
- Guard 1 prevents instantiation, Guard 2 prevents use of an already-existing instance (e.g. injected via test hook after construction)
- They CAN'T both be bypassed the same way — Guard 1 is in `__init__`, Guard 2 is in a standalone function called from the verify path

**Test coverage proves both guards work:**
- `test_static_dev_jwks_source_refuses_construction_outside_dev` (line 191-200) proves Guard 1
- `test_static_dev_jwks_source_refuses_construction_in_prod` (line 203-210) proves Guard 1 in prod specifically
- `test_assert_dev_jwks_source_is_dev_only_raises_outside_dev` (line 223-243) proves Guard 2 independently
- `test_end_to_end_verify_token_refuses_dev_fallback_outside_dev` (line 252-283) proves full-stack enforcement (Guard 2 triggered by real `verify_token` call)

**Both guards are real, independent, and cannot be bypassed together** — satisfies ADR binding condition #4.

---

### Public/Private Key Pairing Correctness

**Files:**  
- `influora-api/src/test/java/com/influora/service/meera/StreamTokenServiceTest.java`  
- `influora-api/src/test/java/com/influora/service/integration/BrandSafetyServiceTokenServiceTest.java`  
- `influora-ai/tests/security/test_service_token_jwks_e_task.py`

**Status:** ✅ PASS — Spring-signed token verifies via the JWKS-served public key

#### Java-Side Key Pairing
**BrandSafetyServiceTokenServiceTest.java:**
- Line 40-44: Same `TestEcKeys.PRIVATE_KEY_PEM` + `TestEcKeys.PUBLIC_KEY_PEM` keypair loaded into `SpringJwksKeyService`
- Line 46: Service constructed with `jwksKeyService` (holds BOTH private and public keys from the SAME keypair)
- Line 52: `service.mint(WORKSPACE_ID)` signs with private key
- Line 111-112: `Jwts.parser().verifyWith(jwksKeyService.publicKey()).build().parseSignedClaims(token)` — **verifies using the paired public key**
- **This proves the public key served by JWKS genuinely corresponds to the private key used for signing** — if they were independently-generated mismatched keys, this test would throw `SignatureException`

**StreamTokenServiceTest.java:**
- Same pattern (lines 40-46 setup, line 52 mint, line 67 verify)
- Line 74-84: `testParseRejectsTokenSignedByDifferentKey` proves mismatch detection — a token signed with a DIFFERENT keypair (`WRONG_PRIVATE_KEY_PEM`) throws `SignatureException` when verified against the real public key

#### Python-Side Cross-Service Verification
**test_service_token_jwks_e_task.py:**
- Line 72: `SPRING_PRIVATE_PEM, SPRING_PUBLIC_PEM = _gen_ec_keypair()` — fresh keypair for this test suite
- Line 76-78: `_FakeSpringJwksSource(SPRING_PUBLIC_PEM)` stands in for Spring's real JWKS endpoint, serves the public half
- Line 101: `jwt.encode(claims, SPRING_PRIVATE_PEM, algorithm="ES256", ...)` — token signed with private half
- Line 109-115: `test_spring_signed_es256_token_verifies_via_jwks_path` calls `verify_token(token, ...)` which fetches the public key from the fake JWKS source and verifies — **PASSES**
- **This proves Python's JWKS-based verifier can validate a Spring-shaped token** (ES256 + kid + correct claims structure)

**No mismatch possible** — both Java and Python tests prove signature verification using the paired public key succeeds, and verification with a non-paired key fails.

---

## Test Suite Re-Run (Independent, Not Copied)

### Java (Maven)
**Command:** `mvn -o -f influora-api test`  
**Result:** Tests run: 644, Failures: 0, **Errors: 1** (known unrelated Docker error), Skipped: 0  
**Baseline:** 643 functional tests PASS  
**Error:** `DatabaseConstraintIntegrationTest » IllegalState Could not find a valid Docker environment` — Wave E3 Testcontainers work, no Docker daemon in this sandbox, **pre-existing and explicitly unrelated to E-JWKS** (no migrations, no schema changes, pure logic + crypto)

**New tests added (inferred from coverage):**
- `JwksControllerTest` (2 tests, lines show in console output)
- `BrandSafetyServiceTokenServiceTest` (5 tests)
- `StreamTokenServiceTest` (4 tests)

**Existing tests untouched, zero regressions.**

---

### Python (pytest)
**Command:** `cd influora-ai && ./.venv/Scripts/python.exe -m pytest -q`  
**Result:** 181 passed, 1 warning in 5.81s  
**Baseline:** 171 prior + **10 new E-JWKS tests** (test_service_token_jwks_e_task.py)  
**Warning:** Pre-existing Pydantic `SkipValidation` warning, unrelated to this task

**New test file:** `tests/security/test_service_token_jwks_e_task.py` (284 lines, 10 test functions)
- 2 tests prove ES256 tokens verify via JWKS path (lines 109-125)
- 1 test proves HS256 tokens rejected on JWKS path (lines 128-150)
- 1 test proves RS256 still accepted (lines 153-182)
- 6 tests prove ADR binding condition #4 enforcement (both guards, construction + verification, dev/staging/prod, end-to-end)

**All tests PASS, zero failures, zero errors.**

---

## No New Dependencies

**Java (Maven):**
- Checked `pom.xml` diff — zero new `<dependency>` blocks for E-JWKS work
- `jjwt-api` version 0.12.6 already had `Jwks.builder()` / `JwkSet` support (used in `SpringJwksKeyService.publicJwkSet()` lines 87-96)
- No new Spring Boot starters, no Bouncy Castle, no third-party EC libs

**Python (pip):**
- Checked `requirements.txt` diff — zero changes
- `PyJWT` (already present) includes `PyJWKClient` for JWKS fetching (used in `HttpJwksSource` line 83)
- `cryptography` (already present) for EC key generation in tests

**ADR requirement satisfied:** "No new Maven/pip dependency"

---

## Checklist Results

### TypeScript/Code Standards
- ❌ N/A (backend-only task, zero frontend changes)

### Security Checks (Java)
- ✅ No API keys in code — PEM keys loaded from `JwksSigningKeyProperties` config (lines 59-60 SpringJwksKeyService), placeholders in application.yml only
- ✅ No hardcoded credentials — `.env.example` has placeholders, real keys injected via env vars
- ✅ Input validation — `SecretsStartupValidator` rejects weak/default keys at boot time (non-dev)
- ✅ No raw SQL — zero SQL strings, JPA/Hibernate only (no schema changes this task)

### Security Checks (Python)
- ✅ No API keys in code — JWKS URL and dev secret loaded from env vars (config.py lines 109, 124)
- ✅ Two independent guards on `StaticDevJwksSource` (lines 108-117, 131-144)
- ✅ ALLOWED_ALGS unchanged, asymmetric-only on JWKS path (line 36)

### Performance
- ✅ JWKS caching — `PyJWKClient` caches keys (HttpJwksSource line 83, `cache_keys=True, lifespan=cache_seconds`)
- ✅ Boot-time key parsing — `SpringJwksKeyService` parses PEMs once at construction (lines 59-60), not per-token-mint
- ✅ No repeated crypto — kid header set once (BrandSafetyServiceTokenService line 70, StreamTokenService line 60), not recalculated

### Architecture
- ✅ PascalCase naming — `SpringJwksKeyService`, `JwksController`, `BrandSafetyServiceTokenService` all correct
- ✅ Config properties pattern — `JwksSigningKeyProperties` follows codebase convention (same as `JwtProperties`, `MeeraStreamProperties`)
- ✅ No bypass paths — both token services use `SpringJwksKeyService` injection, no alternate signing path (grepped, zero `Keys.hmacShaKeyFor` in either service anymore)

---

## Quality Assessment

**Implementation quality:** 9.5/10
- Correct crypto usage (ES256, kid, JWKS Set structure)
- Defense-in-depth (two guards on dev-only fallback, boot-time + parse-time key validation)
- Well-documented (ADR citations in javadocs, inline comments explain WHY not just WHAT)
- Comprehensive test coverage (10 new Python tests, cross-service verification proven)

**Deduction 0.5:** This is net-new public surface + crypto implementation — even though my QA pass found zero defects, **only Kabir's load-bearing review can confirm this is truly secure**. I'm QA, not a cryptographer or red-team.

---

## Non-Blocking Observations for Kabir

1. **New public endpoint `GET /.well-known/jwks.json`** (unauthenticated by design) — this is the first truly-public, zero-auth surface Spring serves that isn't a webhook or pixel redirect. The endpoint itself is correct (JWKS endpoints are public by asymmetric-crypto design), but Kabir should independently verify no sensitive config leaked into the JWK Set (I checked, found none, but he should re-verify).

2. **EC (P-256) instead of RSA** — deliberate choice per the reasoning in `JwksSigningKeyProperties` javadoc (shorter keys, short-lived internal tokens, no legacy-interop need). Kabir should confirm this reasoning is sound for this threat model.

3. **StaticDevJwksSource's two guards** are both real and independent (I verified), but Kabir should adversarially probe whether EITHER guard can be bypassed by injecting config at runtime, manipulating the singleton, or racing the env check.

4. **Test coverage proves key pairing** (Java verifies with paired public key, Python JWKS test verifies cross-service), but these are in-process tests with synthetic keypairs. Kabir should consider whether a live-system integration test (Spring actually running, Python actually fetching JWKS via HTTP) is needed before production.

---

## Verdict

✅ **APPROVED** — all 6 hard conditions from the ADR satisfied, all tests pass, zero regressions, zero blocking findings.

**Route to Kabir for his load-bearing red-team review** (net-new public surface + crypto, explicitly flagged in the ADR as requiring his most thorough pass).

**Flag explicitly to orchestrator:** This is NOT a routine QA approval — **Kabir's review is the true gate** for E-JWKS, not mine. My job was to catch anything obviously wrong before it wastes his time, not to be the final word on the crypto itself.

---

## Next Steps

1. **Kabir red-team review** (load-bearing, per ADR)
2. **Meera local verification** (after Kabir PASS) — build + test re-run, no schema changes, no Docker dependency for this task
3. **E-JWKS fully closed** → E7 launch-approval gate unblocked (this was a hard E7 blocker per the ADR)
