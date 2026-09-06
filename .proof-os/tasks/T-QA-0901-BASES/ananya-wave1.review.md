# Priya (CTO) — Fresh-Context Review: Ananya Wave 1 (5 P0 frontend findings)

**Date:** 2026-09-04
**Reviewer:** Priya, CTO — fresh context, no agent report taken at face value
**Branch:** `fix/f0390-money-flags-build-pipeline` @ `143ca1e`
**Scope:** F-0459, F-0460, F-0461, F-0462 + F-0634/0635/0636, F-0465

---

## TL;DR

| Finding | Verdict | Citation |
|---|---|---|
| F-0459 | **FIXED** (pre-existing; wave added test only) | `src/App.tsx:92`, `:148` |
| F-0460 | **FIXED** | `src/components/brand/brand-layout.tsx:216-242` |
| F-0461 | **FIXED** | `src/lib/upload.ts:61` |
| F-0462 | **FIXED** (revert-probed) | `src/pages/brand-settings.tsx:254-263` |
| F-0634 | **FALSE FINDING** (downgrade to feature gap) | `UserService.java:45-59` |
| F-0635 | **FALSE FINDING** | `src/pages/brand-settings.tsx:359-365` |
| F-0636 | **FIXED** | `src/pages/brand-settings.tsx:100-125`, `:973-987` |
| F-0465 | **FIXED** — the "can't fix FE-only" framing does **not** apply; it *was* fixed frontend-only | `src/admin/services/websocket.ts:283` |

**Tests:** 6 files / 18 tests — **18 passed, 0 failed** (I ran vitest myself, fresh).
**Typecheck:** ❌ **`npx tsc --noEmit` FAILS — 1 error, introduced by this wave.** See Blocker 1.

**Two blockers and one new defect below. Do not merge as-is.**

---

## Structural finding that reframes this whole wave

**Every one of the five source-code fixes was already committed in `e8dc897`.** The working tree
for this wave contains, in `src/`:

- `src/App.tsx` — **`export` keywords only.** No behavioral change.
- `src/pages/brand-settings.tsx` — F-0636 only (+ explanatory comments for F-0634/0635).
- `src/components/brand/brand-layout.tsx` — **unmodified.**
- `src/lib/upload.ts` — **unmodified.**
- `src/admin/services/websocket.ts` — **unmodified.**

Verified via `git log -- <file>` on each: all five last landed in `e8dc897`.

So this wave is **"write regression tests for already-fixed code"**, not "fix five defects". That
is legitimate work — the ledger entries were still `open`, and the `missed_by` field on each
finding literally asks for these tests. But it means the honest verdict on four of five is
*"defect confirmed fixed by an earlier commit; this wave supplied the missing proof"*, and any
report claiming this wave fixed them is overstating. I checked each fix on its merits anyway,
below, because a pre-existing fix is not automatically a correct one.

---

## BLOCKER 1 — `npx tsc --noEmit` fails (this wave broke it)

```
src/__tests__/creator-protected-route.test.tsx(34,5): error TS2578: Unused '@ts-expect-error' directive.
TSC_EXIT=1
```

This is the **only** error in the entire project, and it is in a file this wave created
(untracked, `src/__tests__/creator-protected-route.test.tsx`). Baseline was clean. Exactly the
failure mode predicted for five agents editing independently.

The `@ts-expect-error` at line 34 guards a `window.matchMedia` polyfill assignment that in fact
type-checks fine, so the directive has nothing to suppress.

**Fix:** delete line 34 (`// @ts-expect-error - minimal jsdom polyfill, not a real MediaQueryList`).
If the assignment does need widening on some TS config, use `(window as unknown as { matchMedia: unknown }).matchMedia = ...`
rather than a directive that flips between used and unused.

Note this is invisible to `vitest` (esbuild strips types without checking) and to `vite build`.
Only `tsc --noEmit` catches it — consistent with the standing project note that `vite build` skips
typecheck.

---

## BLOCKER 2 — NEW DEFECT: F-0459's fix makes creator logout leak in mock mode

This is the "a fix that made a related problem worse" pattern, and nobody flagged it.

**Chain, all verified in code:**

1. `src/pages/creator-login.tsx:42` — `api.auth.setToken('creator', result.token, rememberMe)`
   runs in **both** modes (it is outside any `isApiLive()` branch).
2. `src/lib/api.ts:378-379,404` — `tokenStorage('creator')` routes to **`sessionStorage`** when
   `rememberMe` is false.
3. Both creator logout paths gate the server call behind `isApiLive()`:
   - `src/components/creator/creator-layout.tsx:179-181`
   - `src/pages/creator-settings.tsx:245-247`
   In mock mode `api.auth.logout('creator')` is never called, so `http.clearToken` — the **only**
   code that does `sessionStorage.removeItem(TOKEN_KEYS[role])` (`api.ts:410`) — never runs.
4. `src/lib/auth-session.ts:200-201` — `clearCreatorSession()` does
   `localStorage.removeItem('creator_token')` **only**. It never touches `sessionStorage`.
5. `src/App.tsx:92,148` — the F-0459 fix makes `CreatorProtectedRoute` read **both** stores.

**Result:** in mock mode, remember-me unchecked → click Log out → `creator_token` survives in
`sessionStorage` → `readAuthToken` finds it → the guard still lets the user into
`/creator/dashboard`. Before F-0459 the stale token was inert because the guard read
`localStorage` only. **F-0459 made a previously-dormant leak live.**

Severity: scoped to `VITE_API_MODE !== 'live'` (dev/demo builds), so not a production-auth P0 —
but it is a real logout bypass on every demo build, and demo builds are what get shown to clients.

**Fix (one line, at the root, per the CR-06 CTO note the file itself cites):**
```ts
// src/lib/auth-session.ts, clearCreatorSession()
localStorage.removeItem('creator_token');
sessionStorage.removeItem('creator_token');   // <-- add
```

**Latent sibling (not a defect today, flagging so it doesn't become one):**
`persistBrandSession` (`auth-session.ts:69`) writes `brand_token` straight to `localStorage`,
bypassing `http.setToken` entirely, and `brand_remember_me` is **never written by anything** —
I grepped: only `creator-login.tsx` passes a `remember` flag. So brand has no remember-me at all,
`tokenStorage('brand')` always resolves to `localStorage`, and F-0460's localStorage-only clear is
correct **today**. The day someone adds a brand remember-me checkbox, brand logout silently starts
leaking the same way. Same applies to `hasBrandToken()` (`auth-session.ts`, last function),
localStorage-only.

---

## Per-finding detail

### F-0459 — storage-split-breaks-guard → **FIXED** (pre-existing)

The behavioral fix is at `src/App.tsx:92`:
```ts
export const readAuthToken = (key: string): string | null =>
  localStorage.getItem(key) ?? sessionStorage.getItem(key);
```
`git log -L 84,96:src/App.tsx` shows this landed in **`e8dc897`**, not this wave. This wave's diff
adds `export` to `readAuthToken` and to `CreatorProtectedRoute` (`:148`) so the test can import
the real component instead of a reimplementation — a legitimate and correct testing choice.

**"Another call site still has the bug" check — cleared.** `AdminProtectedRoute` (`App.tsx:162`)
still uses bare `localStorage.getItem('admin_token')`. I verified this is **correct, not a miss**:
`REMEMBER_ME_KEYS` (`api.ts:196-199`) has only `brand` and `creator` — no admin — and
`admin-login.tsx:60` writes `admin_token` unconditionally to `localStorage`. Admin has no storage
split to be broken by.

**"Test passes against old code" check — nuanced.** Against the true pre-`e8dc897` code the test
is a genuine falsifier. Against this wave's immediate parent (HEAD) it passes trivially, since only
`export` changed. That is inherent to test-after-fix work, not misconduct — but it means the test
proves the *current* behavior, not this wave's contribution.

Helper the fix genuinely belongs to: **`src/App.tsx`** is the right home for the guard-side read.
The *root-cause* asymmetry lives in `src/lib/auth-session.ts` (see Blocker 2) and is still open.

### F-0460 — incomplete-logout → **FIXED**

`src/components/brand/brand-layout.tsx:216-242`. Verified against the security bar you set:

- **Server endpoint genuinely called:** `:225` `await api.auth.logout('brand')` — a real call, not
  a comment. Traced into `api.ts:1044-1052`: live mode does `POST /auth/logout` then
  `.finally(() => http.clearToken(role))`.
- **Failed call still clears local state:** yes, two ways. The `try/catch` at `:224-229` swallows
  the rejection and falls through to `logout()` + the eight `removeItem` calls at `:230-241`; and
  independently `http.clearToken` is in a `.finally()`, so it runs on rejection too — and *that* is
  what clears `sessionStorage`.
- **Every key login wrote is cleared:** cross-checked `:234-241` against `persistBrandSession`
  (`auth-session.ts:69-86`). All eight match — `brand_token`, `brand_user_id`, `brand_email`,
  `brand_display_name`, `brand_company`, `brand_workspace_id`, `brand_onboarding_complete`,
  `onboarding_complete`. Nothing left behind.
- **Ordering:** server call before local clear, so the server can still resolve the principal from
  the still-present token. Correct, and the test asserts the ordering explicitly.

Test (`brand-layout-logout.test.tsx:158-194`) asserts both the happy path *and* `:179` "still
clears local state and navigates away even when the server call fails". This is the right test.

### F-0461 — mock-in-production-path → **FIXED**

`src/lib/upload.ts:61` — `const { url, key } = await api.uploads.upload(file, 'brand');`

**Caught and cleared a doc/code mismatch.** The file header says the call is made "with no
`purpose`", but the code passes a second argument `'brand'` — which reads like a purpose. I checked
the signature at `api.ts:4132`: `upload(file, role: Role = 'brand', purpose?: UploadPurpose)`. The
second parameter is **role**, not purpose. `purpose` is `undefined`, so `api.ts:4141-4142` takes the
public branch (`http.upload`), which is what returns a real persistable R2 URL. The comment is
accurate and the call is correct. No private-preview-URL leak into `workspaces.logo_url`.

Single call site confirmed: `onboarding-steps.tsx:917`. No second caller still on a mock.

Two non-blocking notes: (a) the `folder` argument is accepted and silently discarded — callers pass
`'brand-logos'` believing it namespaces the object; the doc comment is honest about it, but it is a
lie in the type signature. (b) In mock mode `api.uploads.upload` returns
`URL.createObjectURL(file)` — a `blob:` URL that, if ever persisted, is the same class of defect as
F-0461. Pre-existing and mode-scoped; noting it, not blocking on it.

### F-0462 — full-replace-patch-clears-fields → **FIXED** (this is my revert-probe)

`src/pages/brand-settings.tsx:254-263` carries `industry`/`companySize`/`description`/`logoUrl`
forward from the `loadedWorkspace` snapshot (`:185`) on every save.

**"Guard the real path never reaches" check — cleared, and this was the real risk.** If
`loadedWorkspace` were `null` at save time, all four fields serialize to `undefined` and the
full-replace PATCH wipes them — reintroducing the exact defect. `loadedWorkspace` is null in mock
mode and whenever the initial GET failed. I verified **both** guards:
- handler-level, `:231`: `if (workspaceInfoLoading || workspaceInfoSaving || (liveApi && (!workspaceInfoLoaded || workspaceInfoLoadError))) return;`
- button-level, `:748`: `disabled={... (liveApi && (!workspaceInfoLoaded || !!workspaceInfoLoadError))}`

In live mode `loadedWorkspace` can only be null while `workspaceInfoLoaded` is false, and both
layers block the save. Sound.

**End-to-end contract check — the fix depends on the server actually returning those fields:**
- `WorkspaceMemberDtos.java:54-65` — record carries `description` and `logoUrl`. ✅
- `WorkspaceController.java:82-95` (`toReadResponse`) — both genuinely populated from the entity. ✅
- `api.ts:1068-1085` — `WorkspaceMeResponse` declares both, field order matches the Java record. ✅

This is the one place a "FE type asserts a field the server never sends" bug would have silently
reintroduced the wipe. It doesn't.

**REVERT-PROBE (my own, on this finding):** I removed the four carried-forward lines from the
`updateMe` call, ran the test, restored the file:
```
× a save that only edits Workspace Name still sends the loaded industry/companySize/description/logoUrl
  → expected undefined to be 'D2C Fashion'
Test Files 1 failed (1) | Tests 1 failed (1)
```
The test fails against reverted code with the precise expected assertion, and passes against the
fix. **It is a genuine falsifier, not a test that would green either way.** File restored and
verified (`industry: loadedWorkspace` present at `:255`, diffstat unchanged at 87 insertions).

### F-0634 — dto-field-partially-bound → **FALSE FINDING** (downgrade)

The agent's reasoning checks out and I verified it independently against the Java, not the report.
`UserService.updateProfile` (`influora-api/.../UserService.java:45-59`) applies every field under
`if (req.x() != null)` — a genuine partial merge, structurally unlike `WorkspaceMeUpdatePayload`'s
full-replace. Sending `{ phone }` alone therefore **cannot** wipe the other five.

Precisely: the finding's literal claim ("five fields have no frontend caller") is *true*, but the
implied defect (data loss, F-0462-style) does not exist. This is a **missing profile-editing UI**,
not a bug in the phone flow. Correct call not to invent an avatar uploader and timezone picker as a
side effect of a phone ticket. **Recommend: close as FALSE FINDING, re-open as a product backlog
item for a brand profile editor.**

### F-0635 — write-trusts-response-not-reread → **FALSE FINDING**

`setSavedPhone(updated.phone)` uses the server's own `UserProfileMeResponse` for that exact write,
on the same authenticated connection, in the same request/response pair. A follow-up GET answers no
question the 2xx doesn't already answer, and adds a second failure mode with no sensible user-facing
meaning. Agreed — this is defensive-programming theatre, not a defect. Close as FALSE FINDING.

### F-0636 — collapsed-error-states → **FIXED**

`classifyAccountPhoneLoadFailure` (`:100-125`) splits auth / server / offline / unknown, and the
render at `:973-987` swaps the affordance: `retryable: false` (401/403) renders **Sign In →
`/brand/login`**; everything else renders **Retry**. That is the distinguishing action the finding
asked for, not just different copy. `role="alert"` added at `:974`.

The `offline` branch is correctly reached via the *absence* of `ApiError` — verified that
`HttpClient#request` does not wrap a raw `fetch` `TypeError`, so that branch is live, not dead.

---

## Test run (mine, not reported)

```
npx vitest run src/__tests__/creator-protected-route.test.tsx \
  src/components/brand/brand-layout-logout.test.tsx \
  src/lib/__tests__/upload-real-endpoint.f0461.test.ts \
  src/admin/services/websocket.f0465.test.ts \
  src/pages/__tests__/brand-settings-account-phone-load-errors.test.tsx \
  src/pages/__tests__/brand-settings-workspace-field-preservation.test.tsx

Test Files  6 passed (6)
     Tests  18 passed (18)
  Duration  19.41s
```

Real pass — no stale-artifact false-green (vitest transforms from source; I also proved the F-0462
test is falsifiable by reverting the fix and watching it fail).

Non-blocking: `brand-settings-account-phone-load-errors.test.tsx` emits repeated React
`not wrapped in act(...)` warnings. Tests pass, but these mask real async-state regressions —
wrap the retry interactions.

---

## F-0465 — token-in-url → **FIXED frontend-only. I reject any "can't be fixed FE-only" framing.**

You asked me to verify rather than accept a "cannot be fixed" claim. The claim does not survive:
**it already is fixed, in the frontend, and correctly.**

`src/admin/services/websocket.ts:283`:
```ts
this.ws = new WebSocket(this.cfg.url, [token]);
```
The token travels as the WS subprotocol, which the browser emits as `Sec-WebSocket-Protocol` on the
opening handshake — a request header, never the request line. I grepped every `token`-and-URL
occurrence in the file: the only remaining hits are prose in the doc comment. `resolveAdminSocketUrl`
(`:182-195`) builds the URL from `VITE_ADMIN_WS_URL` / `VITE_API_BASE_URL` and never appends a
credential. The URL is clean; the defect (token in proxy logs, access logs, browser history) is gone.

**I verified the backend handshake claim myself rather than trusting the file's comment.** Result:
```
grep -rn "EnableWebSocket|WebSocketHandler|STOMP|SockJS|WebSocketConfig|@ServerEndpoint|WebSocketMessageBroker" influora-api/src/main/java/   → 0 hits
grep -n "websocket" influora-api/pom.xml                                                              → 0 hits
```
There is **no backend WebSocket endpoint and no `spring-boot-starter-websocket` dependency at all.**
So there is no server handshake that could reject the subprotocol — nothing to be incompatible with.
The client is additionally gated behind `VITE_ADMIN_WS_ENABLED` (default off, `:263-267`) and
refuses to construct a socket without a token (`:268-272`).

**Carried-forward backend obligation (mine to enforce, not a blocker on this wave):** whoever builds
the Phase 2 endpoint MUST (a) read the token from `Sec-WebSocket-Protocol`, never `req.url`, and
(b) **echo the selected subprotocol back in the handshake response** — a server that omits it makes
the browser close the connection immediately, which will look like a mysterious client bug. I am
recording this in `wiki/tech/architecture.md` as a binding constraint on the admin WS endpoint.

---

## Verdict

**NOT MERGEABLE as-is.** Two items must land first:

1. **Blocker 1** — delete the unused `@ts-expect-error` at
   `src/__tests__/creator-protected-route.test.tsx:34`. `tsc --noEmit` must exit 0.
2. **Blocker 2** — add `sessionStorage.removeItem('creator_token')` to `clearCreatorSession()`
   (`src/lib/auth-session.ts:201`), plus a test that logs in with remember-me off in mock mode,
   logs out, and asserts the guard bounces to `/creator/login`.

Once both land: F-0459 / F-0460 / F-0461 / F-0462 / F-0636 / F-0465 close as **FIXED**;
F-0634 / F-0635 close as **FALSE FINDING**.

**Ledger actions:** all eight are still `status: "open"` in `.proof-os/ledger/failures.jsonl` —
none were updated. Update on merge, and open a new finding for Blocker 2
(class: `storage-split-breaks-logout`, where: `src/lib/auth-session.ts`).

**Quality note on the wave:** the code judgment was sound — the two FALSE FINDING calls are both
correct and well-argued from the actual backend source, and refusing to build a profile editor
inside a phone ticket was the right instinct. The misses were both in the seams *between* the
findings, not inside any one of them: the F-0459 ↔ `clearCreatorSession` interaction, and a
typecheck nobody ran. That is the predictable shape of parallel single-file assignments, and it is
on the review layer — me — to catch it, which is what happened here.

— Priya, CTO
