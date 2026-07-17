# ADR: Spring→Python service-auth (Direction 2) — no asymmetric-signing path today

> **Decision by:** Priya (CTO) — final architecture authority (`wiki/tech/REMAINING_WORK_PLAN.md` §"What I am NOT approving without a further decision")
> **Security consult:** Kabir (Red-Team Lead) — verdict cited inline
> **Date:** 2026-07-07
> **Status:** LOCKED
> **Supersedes/relates:** `wiki/decisions/2026-07-06-brand-safety-caption-storage.md` (the C-wave epic this surfaced under); Kabir C2 review LOW-2 (`wiki/errors/brand-safety-endpoint-C2-security-review.md`); Kavya C3 QA reject (`wiki/errors/wave-c-task-c3-java-brandsafety-client-qa-review.md`)

---

## Context

Wave C task C3 (Vikram's `BrandSafetyAiClient` / `BrandSafetyScoreService`) was
QA-rejected by Kavya on a CRITICAL auth-wiring finding. On investigation the
finding is **not a C3 defect** — C3's code is architecturally correct and
mirrors the established `StreamTokenService` pattern exactly. It exposed a
**pre-existing gap in this codebase's cross-service auth design** that also
affects the already-shipped Meera stream-token flow.

There are two internal-auth directions between the two services. They are NOT
symmetric in maturity:

- **Direction 1 — Python → Spring** (`/internal/meera/*`): **fully hardened,
  works in prod.** Dual-credential: an HS256 JWT service token
  (`InternalServiceTokenFilter`, `aud=influora-internal`, `iss=meera-python`,
  TTL-capped) **plus** an independent per-request HMAC (`X-Meera-Signature` via
  `InternalRequestVerifier`, replay + tamper protection). Two distinct secrets.
  Not in scope for this ADR.

- **Direction 2 — Spring → Python** (`/internal/brand-safety` [C3], **and the
  pre-existing** Meera stream token for `/chat`, `/analyze-site`, `/voice/*`):
  **no working production path exists.**

### Why Direction 2 has no prod path (grounded)

- `influora-ai/app/auth/service_token.py` verifies these tokens via **JWKS in
  prod** and hard-restricts `ALLOWED_ALGS = ("RS256", "ES256")` — HS256 is
  rejected on the JWKS path **by design** (asymmetric signing means Python only
  ever needs Spring's *public* key; a shared HS256 secret would give Python key
  material capable of forging Spring-issued tokens). The HS256 branch is only
  reachable when the source is literally a `StaticDevJwksSource` — a **type
  check** (`isinstance(source, StaticDevJwksSource)`), not a config flag —
  selected only when `SPRING_JWKS_URL` is unset (local dev).
- Spring signs **HS256 only**. `StreamTokenService`,
  `BrandSafetyServiceTokenService`, `JwtService`, and
  `InternalServiceTokenFilter` all use `Keys.hmacShaKeyFor`. There is **no
  asymmetric keypair, no `kid`, and no `/.well-known/jwks.json`** anywhere in
  `influora-api` (grep-confirmed: no `KeyPair`/`RSAKey`/`.well-known` artifact).
- The only path that functions at all is Python's `StaticDevJwksSource` HS256
  dev-fallback (`DEV_SHARED_JWT_SECRET`), explicitly commented "never in prod"
  in `influora-ai/app/config.py`.

**Net effect today:** every Direction-2 call (C3 brand-safety **and** the
existing Meera stream token) can only authenticate via the local-dev HS256
fallback. There is no staging/prod path. This is a **rollout/availability gap,
not an integrity vulnerability** — the state fails *closed*, not open (see
Security analysis).

Note: `SecretsStartupValidator` (influora-api) already **refuses to boot in any
non-`dev` env** if `BRAND_SAFETY_SERVICE_TOKEN_SECRET` or the Meera stream
secret is missing/weak/default/duplicated. So Spring treats these as real prod
secrets — but Python cannot consume them in prod. That contradiction is the gap.

---

## Options considered

- **(a)** Accept as a known, explicitly-scoped launch blocker: Direction-2
  flows ship staging-only, tracked as a HARD Wave E7 launch-approval blocker;
  does NOT block continued Wave C/D agent work.
- **(b)** Fast-track a minimal Spring JWKS endpoint now (RSA/EC keypair,
  `/.well-known/jwks.json`, RS256/ES256 signing).
- **(c)** Alternative — mTLS between the services, or relax Python's
  `ALLOWED_ALGS` to accept a shared HS256 secret.

---

## Decision: (a) — Accept as a known, E7-gating launch blocker. LOCKED.

Direction-2 (C3 brand-safety **and** the pre-existing Meera stream-token flow)
is **staging/local-dev-only** until an asymmetric-signing path lands. This is
logged as a **hard Wave E7 launch-approval blocker** (see below). It does **NOT**
block Wave C/D agent work from continuing, and it does **NOT** block C3 from
proceeding through the normal review pipeline.

### Why (a), not (b)-now

Option (b) is the correct **eventual** fix (see next section) but it is **real,
tracked scope, not a Wave-C-tail slot-in**. A "minimal" JWKS endpoint is not
minimal in the ways that matter for launch discipline: it introduces an
asymmetric keypair + `kid` + a **new public, unauthenticated `GET
/.well-known/jwks.json` surface** (⇒ Kabir load-bearing review, not routine),
requires switching **both** `BrandSafetyServiceTokenService` **and**
`StreamTokenService` to RS256/ES256, and drags in key-lifecycle/rotation and
boot-time key-integrity concerns. Squeezing that between C3 and Wave D — exactly
the anti-pattern the `REMAINING_WORK_PLAN.md` sequencing warns against ("C is an
epic, not a task; give it a focused pass, don't squeeze it") — trades a
well-contained, fail-closed known gap for rushed net-new crypto surface on the
critical path. The gap is safe to hold precisely because it fails closed
(nothing works in prod ⇒ nothing is exploitable in prod). Better to close it as
its own focused, properly-reviewed task in the launch-hardening wave.

### Security analysis (Kabir, Red-Team Lead — consulted before locking)

- **Q1 — is (a) defensible?** YES. The state is **fail-closed, not fail-open**:
  `ALLOWED_ALGS` rejects HS256 on the JWKS path via a type check (not a config
  flag that could be misset), and `SecretsStartupValidator` refuses prod boot
  without real distinct secrets. *"No exploitable prod endpoint exists because no
  prod endpoint works at all — this is an availability/rollout gap, not an
  integrity vulnerability."* Kabir will **not** block Wave C/D on it.

- **Q2 — fix ranking:** **(b) JWKS/asymmetric ≫ mTLS ≫ relax-to-HS256
  (explicitly rejected).** Reasoning: in Direction 2, Spring is the
  higher-authority / system-of-record service authenticating *to* Python, the
  **lower-trust, larger-attack-surface** service (it processes untrusted model
  I/O, arbitrary URL analysis, and voice input). A shared HS256 secret would
  hand Python the literal key material to **forge Spring's identity**. Asymmetric
  signing means Python only ever holds Spring's **public** key — it can verify
  but never forge. This containment is the difference in lateral-movement
  blast-radius **if the AI service is ever popped**. JWKS is preferred over mTLS
  because it needs **zero changes to Python's already-hardened verifier**. This
  is why the asymmetric-only `ALLOWED_ALGS` restriction is **correct and worth
  preserving**, not over-engineering — even for an all-internal mesh.

---

## HARD CONDITION (binding — Kabir, verbatim)

> "The eventual fix MUST be asymmetric (RS256/ES256 via a Spring-published JWKS,
> or mTLS). ALLOWED_ALGS in `influora-ai/app/auth/service_token.py` MUST NOT be
> relaxed to accept HS256 on any non-`StaticDevJwksSource` path, and the
> `StaticDevJwksSource` HS256 branch MUST NOT be reachable in any prod/staging-
> facing profile. Closing this gap by giving Python a shared HS256 secret is
> explicitly disallowed."

Additional binding conditions:

1. **Both flows, one fix.** The E7 launch-blocker MUST explicitly cover **both**
   C3 brand-safety **and** the pre-existing Meera stream-token flow (`/chat`,
   `/analyze-site`, `/voice/*`) — same root cause. A brand-safety-only fix that
   leaves the stream path forgotten does NOT close this blocker.
2. **Key gets boot-time protection.** When JWKS lands, Spring's asymmetric
   **private key** MUST get `SecretsStartupValidator`-equivalent boot-time
   protection (fail-closed on missing/weak/default in non-dev), consistent with
   every other credential surface in this codebase.
3. **`ALLOWED_ALGS` is CTO+CISO-gated.** Any future proposal to relax
   `ALLOWED_ALGS`, or to make the `StaticDevJwksSource` branch reachable outside
   local dev, requires explicit re-approval from both me and Kabir. Do not
   quietly "unblock" this by weakening the verifier.

---

## Consequences

- **Local dev works** once the two placeholder secrets are aligned (Kavya's
  BLOCKER 1 — `BRAND_SAFETY_SERVICE_TOKEN_SECRET` == `DEV_SHARED_JWT_SECRET`,
  byte-for-byte). That is a separate 5-minute fix owned by Vikram, not gated by
  this ADR.
- **C3 is UNBLOCKED** to continue through the normal pipeline under the "known
  gap, does not block Wave C progress" framing (see directive below).
- **Wave C4** (`BrandSafetyBadge` UI) can proceed — graceful degradation already
  shows "not yet available" when scores are absent, which is exactly the
  staging-only reality.
- **Wave E gains a tracked task** (below). E7 (Swapnil's launch approval) cannot
  be granted until it is closed.

### New tracked launch-hardening task (add to `REMAINING_WORK_PLAN.md` Wave E)

> **E-JWKS | Owner: Vikram | Spring asymmetric service-token signing.** Add an
> RSA/EC keypair + `kid` + public `GET /.well-known/jwks.json` to influora-api;
> switch **both** `BrandSafetyServiceTokenService` **and** `StreamTokenService`
> to RS256/ES256; point influora-ai's `SPRING_JWKS_URL` at it; private key gets
> `SecretsStartupValidator`-equivalent boot protection. **Kabir load-bearing**
> (net-new public surface + crypto). Closes this ADR's E7 blocker for BOTH
> Direction-2 flows. Do NOT satisfy via HS256 shared secret (HARD CONDITION).
> **E7 launch approval is gated on this.**

---

## Directive to Arjun

**C3 is CLEARED to proceed to Kabir's normal load-bearing security review** under
the "known gap, does not block Wave C progress" framing. Specifically:

1. Vikram closes Kavya's **BLOCKER 1** (align the two `.env.example` placeholder
   secrets so local dev works) — trivial, not gated by this ADR.
2. **BLOCKER 2 is now closed by this ADR** (the production gap is formally
   documented and decided here). Reference this file, not a new `wiki/errors/`
   note.
3. **BLOCKER 3 is resolved:** Kabir's strategy sign-off is **option (a)** with
   the hard condition above. C3 proceeds to his *normal* C3 load-bearing
   security/workspace-isolation review (first live wiring of C1 caption storage +
   C2 endpoint into a scoring decision, per his C1 MED-1 condition) — that review
   is unchanged and still required. The auth-*strategy* question is settled; the
   auth-*implementation* review of C3's actual code is not waived.
4. **Log the E7 blocker** (task E-JWKS above) in `REMAINING_WORK_PLAN.md` Wave E,
   covering BOTH C3 brand-safety AND the Meera stream-token flow. Flag to Swapnil
   as a launch gate (alongside E5/E7's existing human gates).

C3 does **not** need (b)/(c) to land before it merges. It ships behaving exactly
as designed — staging-only, fail-closed in prod — which is the accepted posture.
