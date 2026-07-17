# 🔒 Trust-Boundary Remediation — Meera / Influora (pre-`VITE_API_MODE=live`)

> **Owner:** Backend (Vikram) · **Re-audit:** Kabir · **Date:** 2026-07-05
> **Source:** [KABIR-SECURITY-AUDIT.md](./KABIR-SECURITY-AUDIT.md) Part A (A1) + Part B (B1–B4)
> **Scope of this pass:** Harden the client/server trust boundary on the **existing** surface + wire
> the frontend escrow seam. The escrow/payment/SSE backend is **net-new and specced here, not built.**

This is the checklist Kabir re-runs before brands see real money. Status legend: ✅ done ·
🟡 done-but-needs-`mvn compile` (Maven is not installed in the authoring env — Java changes are
written but **not compiled here**) · ⛔ not built (contract only).

---

## What changed in this pass

### ✅🟡 A1 — Refresh token out of JS-readable storage → HttpOnly cookie
Refresh token no longer travels in the JSON body or `localStorage`. It is set as an
`HttpOnly; Secure; SameSite=Strict` cookie, path-scoped to `/auth`, and **rotated on every refresh**
(presented token is revoked, a new one minted — replay-after-rotation fails).

- `influora-api` 🟡 (compile-pending):
  - `security/AuthCookieService.java` (new) — writes/clears/reads the cookie; Secure + SameSite + path
    are config-driven.
  - `web/AuthController.java` — login/register set the cookie and return the body via
    `TokenPair.withoutRefresh()`; `/auth/refresh` reads the cookie (JSON body only as a non-browser
    fallback), rotates, re-sets the cookie, returns access only; logout clears the cookie.
  - `service/AuthService.java` — `refresh(...)` now rotates and returns `RefreshRotation`.
  - `web/dto/auth/AuthDtos.java` — `TokenPair.withoutRefresh()`; `RefreshRequest` relaxed to optional.
  - `application.yml` — `influora.auth.refresh-cookie.*`. **`secure` defaults `false` for local http;
    prod MUST set `AUTH_REFRESH_COOKIE_SECURE=true`.**
- Frontend ✅ (builds clean):
  - `src/lib/auth-session.ts` — stops writing `brand_refresh_token`.
  - `src/lib/api.ts` — `credentials: 'include'` on all fetches so the cookie flows.

> **Re-rate:** A1 was Medium-because-mock; with this in place it does not become High when real JWTs
> flow. The access token is still in `localStorage` (pragmatic) — the CSP below is its XSS backstop.

### 🟡 CSRF — no separate token machinery needed (by design), documented
Authorization is the `Bearer` header (browsers never attach it cross-site) and the **only** cookie is
the refresh token, which is `SameSite=Strict` + path-scoped to `/auth`. So there is no ambient session
cookie to forge and CSRF is neutralized for `/auth/refresh` + `/auth/logout`. `SecurityConfig` keeps
`csrf().disable()` **with a comment forbidding** moving authorization to a cookie without enabling CSRF.

### 🟡 Rate-limiting — auth surface throttled
`security/AuthRateLimitFilter.java` (new): fixed-window, per-IP, per-endpoint limits on
login/register/reset (10/min), OTP send+verify (5/min), refresh (30/min). Emits
`X-RateLimit-Limit/Remaining` + `Retry-After`, returns `429` with the standard error envelope.
**In-memory ⇒ per-instance** — move to Redis/bucket4j or the edge (WAF/gateway) when scaled
horizontally so the limit is global. Config: `influora.auth.rate-limit.*`.

### 🟡 Security headers (B4)
`SecurityConfig` now sets **HSTS** (1y, includeSubDomains), **X-Frame-Options: DENY**,
**Referrer-Policy: no-referrer**, and a restrictive **CSP** (`default-src 'none'; frame-ancestors
'none'; base-uri 'none'`, override via `CONTENT_SECURITY_POLICY`). The **authoritative CSP for the XSS
backstop belongs on the SPA's own host** (it serves the HTML/JS) — this API-side CSP hardens error/HTML
surfaces only. Track that as an edge/hosting task.

### ✅ B1 — Frontend escrow seam de-faked
`src/components/feature/meera/MeeraWorkspace.tsx` `handlePay` no longer does `setTimeout + markPaid()`.
It calls `api.payments.fundEscrow(dealId)` (server-authoritative shape — no client amount, idempotent)
and marks paid **only when the server reports `status === 'FUNDED'`**. The live-mode contract is
documented inline: `isPaid`/"Secured" must ultimately be driven by the server `payment.released` SSE
event, never client code. (In mock mode the visible outcome is unchanged.)

### ✅ B3 — Object-level authz on the **existing** surface: verified clean
Audited every `:id` route that exists today. All enforce tenant isolation server-side:
- `CampaignService.loadOwned(id, workspaceId)` → cross-tenant id ⇒ `404`.
- `CreatorDiscoveryService.invite` → `campaignRepository.findByIdAndWorkspaceId(...)`; `toggleSaved`/
  `get` scope by `workspace.getId()`. (Creator **profiles** are intentionally cross-tenant discoverable
  — that is the product, not an IDOR.)
- All brand routes gate through `BrandContextService.requireBrandWorkspace` + `requireMember`
  (+ `requireRole` for writes).

**No IDOR on the built endpoints.** The B3 risk is entirely on the not-yet-built money endpoints —
see the contract below.

---

## ⛔ NET-NEW, NOT BUILT — escrow / deals / payments / SSE (B1, B2, B4)

The audit assumed `api.ts`'s money authority "must be honored by the backend." **There is no backend
for it yet** — `influora-api` has no `DealController`, `PaymentController`, `WalletController`, escrow
ledger, `/stream` SSE, or webhook handler. `api.ts` calls `/deals/:id/escrow/fund` etc. into the void.
Building this is a feature with product decisions (payment provider, escrow model), not a hardening
pass. Contract for when it is built:

1. **Escrow/"Secured" is server truth.** `POST /deals/:id/escrow/fund` and `.../payout/release` take
   **no client amount**; the backend computes pool/fee/total itself (B2 — never trust `computeFee`).
   Consume the `Idempotency-Key` header to make fund/release single-submit.
2. **`payment.released` (and escrow-funded) is emitted only from the payment-provider webhook** after
   **verifying the webhook signature**, then pushed to the SPA via SSE `/stream`. The client sets
   `isPaid` from that event — never from the POST resolving alone.
3. **Release requires server-side checks:** caller owns the deal **and** the deliverable is approved.
4. **B3 for every new `:id` route** — replicate the `loadOwned(id, workspaceId)` pattern: scope all
   `deals`, `messages`, `wallet`, `contracts`, `deliverables` queries to the authenticated workspace;
   add object-level authz (ASVS V4). Add these paths to `AuthRateLimitFilter` (stricter limits).
5. **Transport:** prod API base is **HTTPS-only** (no plaintext Bearer on the wire).

---

## Not in this pass (owner: Ananya, per Kabir Part A)
- **A2** — compile the `?demo=true` bypass OUT of prod bundles (build-time flag, not `MODE`).
- **A3** — `assertMockAuthAllowed()` already fails closed; keep it wired on every mock auth path.
- **A4** — cap STT transcript length before it enters React state.

## Re-audit gate
Before `VITE_API_MODE=live`: `mvn -f influora-api compile` must pass, then Kabir re-runs the audit
against A1/B1–B4 with the escrow backend built. This pass clears A1 + CSRF + rate-limit + headers on
the existing surface; **B1/B2/B4 stay open until the escrow backend above exists.**
