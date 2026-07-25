# Live Verification — Vikram (Backend) — 2026-07-23

**Target:** http://200.141.1.6/ (live influora-test deploy)
**Commit under test:** b6b0677
**Method:** curl only (no login credentials available — see Blocked section)

## Result Summary

| # | Test | Expected | Actual | Verdict |
|---|------|----------|--------|---------|
| 1 | `GET /api/v1/nonexistent` (unauthenticated) | 404 | **403** (empty body) | ⚠️ MISMATCH |
| 2 | `GET /api/v1/campaigns` (no auth) | 401 | **403** (empty body) | ⚠️ MISMATCH |
| 3 | `POST /api/v1/auth/login` (nonexistent sub-path, but under permitAll `/auth/**`) | 404 | **404**, proper JSON envelope | ✅ PASS |
| 4 | `POST /api/v1/health` / `DELETE /api/v1/health` (real 405 case, public endpoint) | 405 | **405**, `{"error":{"code":"METHOD_NOT_ALLOWED",...}}` | ✅ PASS |
| 5 | `GET /api/v1/workspaces/slug-check` missing `slug` param (public endpoint) | 400 | **400**, `{"error":{"code":"MISSING_PARAMETER",...}}` | ✅ PASS |
| 6 | Brand wallet auto-provision | 200 w/ balance | **BLOCKED** — no demo login token | ⏳ BLOCKED |
| 7 | Featured creators JSON cast fix | 200 | **BLOCKED** (403 unauthenticated; needs brand token) | ⏳ BLOCKED |
| 8 | Creator profile real identity | 200 real data | **BLOCKED** — no demo login token | ⏳ BLOCKED |
| 9 | Creator wallet real balance | 200 | **BLOCKED** — no demo login token | ⏳ BLOCKED |
| 10 | Shopify OAuth (#8, routed to Vikram) | 200/redirect not 500 | 403 unauthenticated (endpoint is behind auth; can't reach the 500 bug without a token) | ⏳ BLOCKED |

## Detail

### Issue #7 — GlobalExceptionHandler (4xx mapping)

The 400/404/405 mappings **do work correctly** — but only for requests that reach Spring MVC dispatch. I proved this three ways using endpoints in `SecurityConfig`'s `permitAll` list:

- `GET /api/v1/health` → 200 baseline
- `POST /api/v1/health`, `DELETE /api/v1/health` → **405**, JSON body `{"code":"METHOD_NOT_ALLOWED","message":"HTTP method 'POST' is not supported for this endpoint"}` — no stack trace, no leakage.
- `GET /api/v1/workspaces/slug-check` (no `slug` param) → **400**, `{"code":"MISSING_PARAMETER","message":"Required parameter 'slug' is missing"}`
- `POST /api/v1/auth/login` (path doesn't exist — real login routes are `/auth/brand/login` / `/auth/creator/login`) → **404**, proper JSON envelope, not empty.

All three confirm `GlobalExceptionHandler.java`'s mappings are live and working, with no info leakage. **This part of the fix is genuinely verified PASS.**

**However**, two of the CEO directive's literal test cases don't land where expected, and this is a real gap worth flagging:

- `GET /api/v1/nonexistent` (unauthenticated, not in any permitAll matcher) → **403 Forbidden, empty body** — not 404, and not JSON. `SecurityConfig.java`'s `anyRequest().authenticated()` rule blocks the request in the Spring Security filter chain **before it ever reaches the DispatcherServlet**, so `GlobalExceptionHandler` (a `@RestControllerAdvice`, MVC-layer) never gets a chance to run. Not a 500, but not what the directive expects either.
- `GET /api/v1/campaigns` without auth → same story: **403**, not **401**. Root cause: `SecurityConfig.java` has no custom `AuthenticationEntryPoint` configured, so Spring Security falls back to the default `Http403ForbiddenEntryPoint` for anonymous+unauthenticated requests instead of returning 401. This is a pre-existing convention gap, not a regression from b6b0677 — but it means the literal "401 not 500" expectation in the directive doesn't hold on this deployment; it's 403 not 500, which is still safe (no leakage) but not spec-matching REST semantics.

**Recommendation:** if 401-vs-403 semantics matter to a client integration, add an `exceptionHandling().authenticationEntryPoint(...)` bean that returns 401 with the same JSON envelope for anonymous requests, reserving 403 for authenticated-but-forbidden. Low priority, not a security bug (no leakage either way) — flagging as a fast-follow, not a blocker.

### Issues #2, #3, #4, #5 (Wallet auto-provision, Creator profile, Creator wallet, Featured creators cast fix)

**Could not verify — blocked on credentials.** All four require a real Bearer token for `demo.brand@influora.com` or `demo.creator@influora.com`. Per `SHARED_CONTEXT.md` and `wiki/reports/brand-creator-final-report.md`, these are **live DB-provisioned accounts with no source-controlled password** — the passwords are said to be "in secure vault," which I don't have access to. I will not guess/brute-force credentials against a live deployment.

What I *did* confirm unauthenticated (sanity, not the real test):
- `GET /api/v1/wallet` → 403 (correctly gated, no leakage)
- `GET /api/v1/me/creator-profile` → 403 (correctly gated, no leakage)
- `GET /api/v1/creators/featured?niche=fashion&minFollowers=10000` → 403 (correctly gated, no leakage — but this means I cannot exercise the Hibernate JSON-column cast fix in `CreatorProfileSpecifications.java` without an authenticated brand token)

**Needed to unblock:** the actual demo.brand / demo.creator passwords, or a pre-issued token, from whoever holds the secure vault (Arjun/Priya per the escalation chain, or the user directly).

### Issue #8 — Shopify OAuth (routed to Vikram, NOT claimed fixed in b6b0677)

`GET /api/v1/shopify/oauth/authorize` unauthenticated → 403 (endpoint requires auth, not in permitAll). Can't reach the reported 500 without a token. This issue remains **open and unverified either way** — it needs the same credential unblock, and separately still needs the actual fix (Shopify app credentials configured or feature disabled) per the routing map, which was never claimed as done in b6b0677.

## Bottom line

- ✅ **GlobalExceptionHandler fix (Issue #7) is real and working** for 400/404/405 in all cases I could reach through the security layer — no 500s, no info leakage.
- ⚠️ Two of the directive's literal test cases (`nonexistent` unauthenticated, `campaigns` unauthenticated) return **403 instead of 404/401** — not a regression, not a security hole, but a spec mismatch worth a fast-follow ticket.
- ⏳ **Cannot verify Issues #2, #3, #4, #5, #8** (wallet auto-provision, creator profile, creator wallet, featured-creators cast fix, Shopify OAuth) without demo account credentials or a pre-issued JWT. Blocking on secure-vault access.
