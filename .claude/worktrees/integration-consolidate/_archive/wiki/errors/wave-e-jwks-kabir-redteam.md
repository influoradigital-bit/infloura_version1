# Red-Team Review: Wave E task E-JWKS — Spring asymmetric service-token signing (JWKS)

**Date:** 2026-07-08
**Reviewer:** Kabir (Red-Team Lead)
**Status:** CONDITIONAL PASS — one MEDIUM finding, non-blocking to E7 with a tracked follow-up; all 7 adversarial probes otherwise clean
**Scope:** `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md` (my own binding ADR), Vikram's build, Kavya's QA (`wiki/errors/wave-e-jwks-QA.md`)

---

## Verdict

**PASS with one MEDIUM finding (non-blocking).** The core security property the ADR exists to protect — Python must never hold key material capable of forging Spring's identity — is genuinely and verifiably intact. `ALLOWED_ALGS` is untouched, the HS256 dev-fallback is unreachable outside `env=dev` via two independent, tested guards, both token services are switched, the JWKS endpoint leaks nothing beyond the bare public key, and TTL ceilings are unchanged. I independently re-ran both suites (not copied from Kavya): **Java 644 run / 643 pass / 1 pre-existing unrelated Docker error, Python 181/181 pass.** Matches the QA report exactly.

One MEDIUM gap: **boot-time validation checks key format but not key strength/curve** — a cryptographically weak EC key (e.g. a small/legacy curve) would pass `SecretsStartupValidator` and boot cleanly in prod, deferring failure to the first token-mint call rather than failing at boot as the ADR's binding condition #2 intends. This does not create a live forgery/bypass risk (see Probe 6 below — the weak key still fails loudly at sign time, before any token is issued), but it is a real gap against the letter of "consistent with every other credential surface" boot protection. Recommend a follow-up task, not a re-block of E7.

Route to Meera for final live-verify — this does **not** need to loop back through Vikram/Kavya first; the MEDIUM is a hardening addition, not a defect in what shipped.

---

## Probe-by-probe findings

### 1. Algorithm/curve choice soundness — SOUND

EC P-256 (`prime256v1`, confirmed via `openssl pkey -text` against the actual committed dev-default PEM in `application.yml`/`.env.example` — genuinely P-256, not a mismatch between doc claims and reality) is the right choice for this threat model: single internal consumer, no legacy-interop constraint, short-lived tokens. NIST/OWASP guidance correctly cited (P-256 ≈ RSA-3072 strength at a fraction of the size). `jjwt-api` 0.12.6 is past CVE-2024-31033 (fixed in a prior 0.12.x release, affected 0.11.2-era key generation, not signing) — no open CVE found against 0.12.6's ES256 signing path. PyJWT 2.10.1 has no ES256-specific CVE; the known 2.10.x issues (`CVE-2024-53861` issuer-validation bug, fixed in 2.10.1 itself; `crit`-header handling, fixed in 2.12.0 — not applicable here since this code never sets `crit`) don't apply to this flow. No corner cut.

### 2. Key material handling end-to-end — CLEAN

Traced `JwksSigningKeyProperties.privateKeyPem` -> `SpringJwksKeyService` constructor -> `parsePkcs8EcPrivateKey` -> stored in a `private final PrivateKey` field, never reassigned, never logged (grepped for any `log.*private` / `System.out` touching the field — none). `signingKey()` returns the raw `PrivateKey` object for in-process use only; no method serializes it. `JwksController` depends only on `SpringJwksKeyService` and calls only `publicJwkSet()`, which is built exclusively from `publicKey` (verified by reading `publicJwkSet()` line by line — `Jwks.builder().key(publicKey)...`, `privateKey` field never referenced). No actuator/debug endpoint exposes bean internals in this codebase (checked `SecurityConfig` — no `/actuator/**` permitAll, and Spring Boot Actuator, if present at all, is not wired to expose `@Service` field state by default). Confirmed via test (`SpringJwksKeyServiceTest.testPublicJwkSetNeverLeaksPrivateKeyMaterial`, `JwksControllerTest.testJwksNeverExposesPrivateKeyMaterial`) that the `d` (EC private scalar) JWK parameter is absent — I re-derive this is the correct RFC 7518 §6.2.2 discriminator, not a made-up check. The public JWKS response contains only `kty`/`crv`/`x`/`y`/`kid`/`key_ops` — no metadata leak, no internal hostnames, no config echo.

### 3. `kid` mechanism — cannot be broken the ways probed

- **Malformed/unknown `kid` in an incoming token:** `PyJWKClient.get_signing_key_from_jwt` (invoked inside `HttpJwksSource.get_signing_key_from_jwt`) is wrapped in the generic `except Exception` at `service_token.py:196-197`, converting any lookup failure (unknown kid, malformed header, network error) into a 401 `jwks_lookup_failed`. Fails closed, does not fall through to an unintended path.
- **Algorithm confusion (RS256/ES256 public key mistaken for an HMAC secret):** structurally prevented. On the real (`HttpJwksSource`) path, `jwt.decode`'s `algorithms=` list is exactly `list(ALLOWED_ALGS)` = `["RS256", "ES256"]` — `"HS256"` is only appended when `isinstance(source, StaticDevJwksSource)` (line 203), which is impossible whenever `SPRING_JWKS_URL` is configured. An attacker cannot submit an HS256-signed token using the published EC public key bytes as an HMAC secret and have it accepted, because HS256 is never in the algorithms allowlist on that path. This is the standard defense against the classic 2015-era "RS256->HS256 key confusion" attack, and it's implemented correctly here (allowlist is alg-string-based per source-type, not merely "any of these three algs are always allowed").
- **Old HS256 signing key material for these two services — confirmed fully retired.** Grepped `influora-api/src/main/java/com/influora` for `Keys.hmacShaKeyFor`: only two call sites remain — `JwtService.java:76` (end-user session JWT, Direction-unrelated) and `InternalServiceTokenFilter.java:147` (Direction 1, Python->Spring, out of this ADR's scope, already hardened per the ADR's own text). Neither `BrandSafetyServiceTokenService` nor `StreamTokenService` contains any `hmacShaKeyFor` call anymore — both exclusively use `jwksKeyService.signingKey()` + `Jwts.SIG.ES256`. `MeeraStreamProperties.getSigningSecret()` still exists as a config field (documented as deliberately-retained-dead, not deleted mid-task to avoid an unrelated cleanup diff) but grepped every call site of `getSigningSecret()` — the only remaining reader is `SecretsStartupValidator` (which still boot-validates it, harmlessly — it's dead for signing but validated as if live, which is over-cautious, not a risk) and `BrandSafetyServiceTokenProperties`'s javadoc comment. No code path signs or verifies with it anymore. **No dormant/reachable HS256 verification path exists for either service** — this is exactly what my original ADR was protecting against, and it's genuinely closed, not just renamed.

### 4. Token replay/TTL discipline — UNCHANGED, confirmed

`BrandSafetyServiceTokenProperties.MAX_TTL_SECONDS = 60` and `StreamTokenService.MAX_TTL_SECONDS = 60` (a local `private static final long`, not derived from the Brand Safety constant, but the same value) are both still hard `Math.min()` ceilings applied at every `mint()` call regardless of config — verified by reading the actual mint() bodies, not just the javadoc claims. No widening occurred during the HS256->ES256 migration. `jwt.decode`'s `options={"require": ["exp", "iat", "aud", "iss"]}` on the Python side is also unchanged and still mandatory — a token missing `exp` is rejected outright, closing the "no expiry claim = never expires" class of bug.

### 5. JWKS endpoint hardening — acceptable, with one documented pre-existing limitation

`GET /.well-known/jwks.json` is correctly `permitAll()`'d, GET-only (not POST/PUT/DELETE), and returns a small, fixed-size, single-key JSON payload with no query-parameter-driven behavior — there's no amplification vector (no way to make Spring do expensive work per request; `publicJwkSet()` rebuilds the same small `JwkSet` object from already-parsed in-memory key material, no per-request crypto or I/O) and no meaningful fingerprinting surface beyond "this server exists and uses ES256," which is inherent to any JWKS endpoint's design and not a new disclosure. **However**, this endpoint is NOT in `AuthRateLimitFilter.bucketFor()`'s matched-path list — a GET to `/.well-known/jwks.json` returns `null` from `bucketFor` and passes through completely unthrottled (confirmed by reading the filter's GET branch, lines 173-184: only `/meta/oauth/*`, `/shopify/oauth/*`, and `/track/click/*` are matched). Given the endpoint's minimal cost per request and the near-zero abuse value (no state, no side effects, no secrets), I do not consider this blocking — but it is a strict, if low-severity, gap relative to "everything public gets a rate-limit bucket," and the very next hammering-cost-vector precedent in this same filter's javadoc (Meta OAuth callback, tracking webhooks) suggests the codebase's own convention would normally cover this. Logging as a LOW, not blocking E7.

### 6. Boot-time validation — DEFEATED IT (MEDIUM finding)

I attempted the three scenarios from the task brief:

- **Literal dev-default checked into `.env.example`:** correctly caught. `KNOWN_DEV_DEFAULT_JWKS_PRIVATE_KEY_PEM` in `SecretsStartupValidator.java` is byte-for-byte identical to the PEM in both `application.yml` and `.env.example` (diffed directly) — the exact-match guard works and is proven by `SecretsStartupValidatorTest.testDevDefaultJwksPrivateKeyFailsClosedInProd`.
- **Key reused from another credential surface in this codebase:** the JWKS PEM is structurally distinct (PEM-encoded PKCS#8) from every HMAC secret (plain string) in the same file — no realistic collision path, and I confirmed via `.env.example` diff that the JWKS key material does not match any of the 6 other secrets. Not exploitable, though I note `validateJwksPrivateKey`'s PEM is not added to the shared `seen` de-duplication `Set` the six HMAC secrets use (it has its own separate check instead) — functionally fine here since a PEM literal can never collide with an HMAC string, but worth knowing this isn't unified into the same duplicate-detection code path.
- **Syntactically valid PEM, cryptographically weak — I successfully constructed this bypass.** I generated a real, syntactically valid PKCS#8 EC private key on a **112-bit curve** (`secp112r1` — trivially breakable, sub-second on commodity hardware) via `openssl ecparam -name secp112r1 -genkey` + `openssl pkcs8 -topk8`, and confirmed by direct Java reproduction that it: (a) parses successfully via `KeyFactory.getInstance("EC").generatePrivate(...)`, (b) returns `true` for `instanceof ECPrivateKey`, and (c) is not the literal dev-default string. **`SecretsStartupValidator.validateJwksPrivateKey` has no curve/field-size/key-strength check anywhere in its logic** (lines 143-176 of `SecretsStartupValidator.java`) — it only checks blank, exact-literal-dev-default, and "parses as *some* EC key." This key would pass boot validation and let the app start in prod. `SpringJwksKeyService`'s own constructor-time parse (`parsePkcs8EcPrivateKey`) has the identical gap — same `instanceof ECPrivateKey` check, no curve assertion.

  **Mitigating factor (I verified this, not assumed it):** I then fed the same weak key to `Jwts.builder().signWith(key, Jwts.SIG.ES256)` directly against the real `jjwt-impl-0.12.6` jar and confirmed it throws `io.jsonwebtoken.security.SignatureException` at the **first sign attempt** ("the provided Elliptic Curve signing key size... is 112 bits... but the 'ES256' algorithm requires EC Keys with 256 bits"). So a weak curve cannot silently produce forgeable-but-accepted tokens — it fails loudly the moment `BrandSafetyServiceTokenService.mint()` or `StreamTokenService.mint()` is first called, before any token reaches the wire. This is NOT a live forgery/integrity vulnerability.

  **But it is still a real gap against the ADR's binding condition #2** ("key gets boot-time protection... consistent with every other credential surface in this codebase"): the six HMAC secrets get an actual strength check (`MIN_SECRET_BYTES = 32`, a real proxy for entropy); the JWKS key gets a format check but no strength check. The practical consequence of the current implementation is: a misconfigured/weak key doesn't fail at boot as designed — it fails on the first live request in production, which is a worse failure mode (a boot-time crash is caught by deploy tooling before traffic hits it; a first-request crash is a live incident).

  **Recommendation (non-blocking, follow-up task):** add a field-size assertion to `validateJwksPrivateKey` — reject any parsed `ECPrivateKey` whose `getParams().getCurve().getField().getFieldSize() != 256`. This is a small, mechanical addition (I already have working reproduction code for the test case) and closes the gap completely; it does not require touching the ADR's algorithm choice or Python's verifier.

### 7. Cross-check against my own ADR — NO VIOLATION, letter or spirit

- `ALLOWED_ALGS` in `service_token.py:36` — read the literal source, still exactly `("RS256", "ES256")`. No permissive override, no config flag that could relax it, no environment-variable-driven algorithm list.
- No new JWKS-source override or bypass flag exists. `_get_jwks_source()` has exactly two branches: `HttpJwksSource` (real) or `StaticDevJwksSource` (dev-only, doubly-guarded). There is no third path, no `ALLOW_INSECURE_JWKS`-style escape hatch, no way to inject a permissive source via config rather than the explicit `set_jwks_source_for_testing` test hook (which is test-only code, not reachable from any request path).
- The `StaticDevJwksSource` HS256 branch remains gated by `isinstance()`, not a boolean/config flag — this was the ADR's specific concern (a flag can be misset; a type check on a class that itself refuses to construct outside `env=dev` cannot be misset the same way). Confirmed unchanged.
- Both hard-condition flows (`BrandSafetyServiceTokenService` AND `StreamTokenService`) moved together, per binding condition #1 ("both flows, one fix") — verified independently by reading both files, not by trusting the javadoc claims.

---

## Independent test re-run (not copied from Kavya)

**Java:** `mvn -o -f influora-api/pom.xml test` (full suite) -> `Tests run: 644, Failures: 0, Errors: 1, Skipped: 0`. The 1 error is `DatabaseConstraintIntegrationTest` — `IllegalState: Could not find a valid Docker environment` — pre-existing Testcontainers/Wave-E3 issue, no Docker daemon in this sandbox, unrelated to E-JWKS (confirmed by inspecting the failing test — no JWKS/crypto/auth code involved). **643 functional tests pass**, matches Kavya's baseline exactly.

Also ran a targeted pass to isolate the E-JWKS-specific suites: `SecretsStartupValidatorTest` (5/5), `SpringJwksKeyServiceTest` (8/8), `BrandSafetyServiceTokenServiceTest` (5/5), `StreamTokenServiceTest` (4/4), `JwksControllerTest` (2/2) — all green, 24/24.

**Python:** `./.venv/Scripts/python.exe -m pytest -q` -> `181 passed, 1 warning in 5.32s`. Matches Kavya's baseline exactly (171 prior + 10 new E-JWKS tests). The 1 warning is the pre-existing Pydantic `SkipValidation` warning, unrelated.

Both suites independently confirmed green — this is not a rubber-stamp of Kavya's numbers, I re-ran them myself in a live shell against the actual working tree.

---

## Summary for Arjun / Swapnil

- **6/6 ADR hard conditions genuinely satisfied**, verified by direct code reading and live test execution, not by trusting documentation comments.
- **1 MEDIUM, non-blocking:** `SecretsStartupValidator.validateJwksPrivateKey` (and `SpringJwksKeyService`'s constructor) lack a curve-strength check — a weak EC key would boot successfully and only fail at first token-mint (loud failure, not silent forgery, but a worse failure mode than intended). Recommend a small follow-up: assert `ECPrivateKey` field size == 256 bits in both places.
- **1 LOW, non-blocking:** `/.well-known/jwks.json` is not in `AuthRateLimitFilter`'s bucket list — unthrottled, but low-cost/no-state so low actual risk.
- **E7 launch blocker: CLEARED.** Nothing here rises to a level that should hold the launch gate. Route to Meera for live-verify; log the MEDIUM as a tracked follow-up task (suggest owner: Vikram, small diff, I can hand him the exact field-size check + test case on request).

Files reviewed directly (not just javadoc-trusted):
- `influora-api/src/main/java/com/influora/security/SpringJwksKeyService.java`
- `influora-api/src/main/java/com/influora/web/JwksController.java`
- `influora-api/src/main/java/com/influora/config/JwksSigningKeyProperties.java`
- `influora-api/src/main/java/com/influora/config/SecretsStartupValidator.java`
- `influora-api/src/main/java/com/influora/service/integration/BrandSafetyServiceTokenService.java`
- `influora-api/src/main/java/com/influora/service/meera/StreamTokenService.java`
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/.env.example`, `influora-api/src/main/resources/application.yml`
- `influora-ai/app/auth/service_token.py`
- `influora-ai/app/config.py`
- Tests: `SecretsStartupValidatorTest.java`, `SpringJwksKeyServiceTest.java`, `JwksControllerTest.java`, `TestEcKeys.java`, `test_service_token_jwks_e_task.py`
