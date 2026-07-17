# DPF-1 Kabir Red-Team Audit — GET /deliverables/{id} (brand file-view endpoint)

**Status: PASS with 1 non-blocking finding.** This is the audit that was fast-tracked past and never actually run before DPF-1 was marked CLOSED. Ran it for real against current source.

Scope: `BrandDeliverableController.getDetail` → `BrandDeliverableService.getDetail`/`requireBrandDeliverable`/`resolveDownloadUrl` → `DeliverableRepository.findByIdAndWorkspaceId`.

## 1. IDOR / cross-tenant — PASS

`workspaceId` is never client-supplied. It comes from the JWT claim set at login (`JwtAuthenticationFilter.java:37`, `claims.get("workspaceId", ...)`) into `AuthPrincipal`, which is itself produced only by the trusted filter chain (signed token, `SecurityContextHolder` populated server-side). `BrandContextService.requireBrandWorkspace` (`BrandContextService.java:34-56`) resolves the workspace strictly from `principal.getWorkspaceId()`/`principal.getUserId()` — no request param, header, or body field feeds it. A client cannot spoof another workspace.

`DeliverableRepository.findByIdAndWorkspaceId` (`DeliverableRepository.java:32-37`) is parameterized JPQL (`:id`, `:workspaceId` bound, no string concatenation — no injection vector) that joins `Deliverable → Collaboration (collaborationId) → Campaign (campaignId) → Workspace (workspaceId)`. A deliverable belonging to workspace B can never match a query bound to workspace A's id; the join simply returns empty. Verified in `BrandDeliverableServiceTest.testGetDetailForeignWorkspaceRejected` (foreign workspace → `DELIVERABLE_NOT_FOUND`, and `r2StorageService.presignGet` is asserted **never called** — confirms no file resolution even attempted for a cross-tenant probe).

`requireBrandDeliverable` (`BrandDeliverableService.java:91-101`) — every one of `getDetail`/`approve`/`requestRevision` routes through this single chokepoint. No alternate path to `toDetailResponse` exists that skips the workspace-scoped lookup.

## 2. Presign-key confusion — PASS

`resolveDownloadUrl`/`toObjectKey` (`BrandDeliverableService.java:140-170`) only ever operate on `file.url()`/`file.thumbnailUrl()` extracted from `readFilesJson(deliverable.getFilesJson())` — i.e., fields embedded in the **already workspace-verified** `Deliverable` row returned by step 1. There is no client-supplied key/id parameter anywhere in this path; the brand cannot pass an arbitrary R2 key or a different deliverable's key to be presigned. A manipulated `deliverableId` either resolves to the correct (own-workspace) deliverable or 404s before any key is ever read — it cannot cause a wrong-tenant key to be presigned.

## 3. Enumeration — PASS (uniform, confirmed against actual code)

Both "no such id" and "exists but different workspace" hit the exact same code path: `findByIdAndWorkspaceId` returns `Optional.empty()` → `orElseThrow(() -> new ApiException("DELIVERABLE_NOT_FOUND", "Deliverable not found", HttpStatus.NOT_FOUND))` (`BrandDeliverableService.java:94-100`). `GlobalExceptionHandler.handleApi` (`GlobalExceptionHandler.java:16-20`) serializes only `ex.getCode()`/`ex.getMessage()` — no stack trace, no distinct error code, identical body/status for both cases. Confirmed by the verify agent's claim being accurate here (unlike the rest of the claimed audit, which never ran). No separate "does it exist" pre-check query exists that would create an extra round-trip/timing oracle — it's a single query in both cases.

## 4. R2-unavailable fallback — FINDING (non-blocking, track as fast-follow)

`resolveDownloadUrl` (`BrandDeliverableService.java:140-153`) falls back to returning the raw `stored` value when `r2StorageService.isAvailable()` is false, when `toObjectKey` can't resolve a key, or when `presignGet` throws any `RuntimeException`. Per the test fixture's own comment ("Real uploads persist raw R2 object keys — never public URLs", `BrandDeliverableServiceTest.java:277-278`) and `testGetDetailFallsBackWhenR2Unavailable` (lines 347-361), when R2 is down the API returns the **bare internal object key** (e.g. `deliverables/{collabId}/v1/reel-abc.mp4`) in the `url` field, mislabeled as a download URL.

This is not a cross-tenant leak (it's still the requesting brand's own file, correctly scoped) but it is:
- An internal path-disclosure — leaks the R2 key naming convention and embeds the raw `collaborationId` in a client-facing field.
- A functional bug — the frontend expects a fetchable URL and gets a non-URL string; video/thumbnail rendering silently breaks during any R2 outage, with no distinguishable error signal for the brand user.

Recommendation for Vikram: on R2-unavailable/presign-failure, throw a clean `SERVICE_UNAVAILABLE`/friendly error instead of returning the raw stored value. Not a blocker for DPF-1 close — no exploitable disclosure across tenants — but should be tracked (separate from DPF-2b's retry-cap issue, which is a different failure mode).

## 5. Role confusion (creator hitting brand endpoint) — PASS

`BrandContextService.requireBrand` (`BrandContextService.java:27-32`) checks `principal.getUserType() == UserType.BRAND`, throwing `WRONG_USER_TYPE` / 403 otherwise, and is called first inside `requireBrandWorkspace` before any deliverable lookup runs. A CREATOR-role JWT (`userType` claim set at creator login) hits this guard and 403s before touching `DeliverableRepository`. `SecurityConfig` has no public-access exemption for `/deliverables/**` (falls under `anyRequest().authenticated()`), so an unauthenticated request also fails at the JWT filter (401) before reaching the controller.

## Verdict: ✅ PASS

No exploitable IDOR, presign-key confusion, or cross-tenant leak in `GET /deliverables/{id}`. Route to Kavya → Meera → DPF-1 can be closed for real this time, with finding #4 tracked as a non-blocking fast-follow (not a condition of closing DPF-1).

## Process gap flag for Arjun

DPF-1's Kabir gate was fast-tracked past and **genuinely never ran** before this — confirmed no `DPF-1-kabir-*.md` existed in `wiki/errors/` prior to this file, despite the epic scoreboard in `SHARED_CONTEXT.md` repeatedly stating "DPF-1 ✅ CLOSED" with a full Kabir/Kavya/Meera/Priya chain. Correct the historical record.

Spot-check of siblings in the same epic: `wiki/errors/DPF-3-kabir-redteam.md` and `wiki/errors/DPF-8-kabir-redteam.md` **do exist** on disk — those two gates appear to have genuinely run. I did **not** find a `DPF-2-kabir-*.md` or `DPF-4-kabir-*.md`. DPF-2 is frontend-only UI (plausibly out of red-team scope by role definition), but **DPF-4 is the `PaymentMilestone.releaseCondition` schema change** — money-adjacent — and its scoreboard entry says "Kavya QA PASS (migration+entity+builder all correct)" with no Kabir mention at all. Recommend Arjun confirm explicitly whether DPF-4 was ever in Kabir's scope (spec row check against `wiki/tech/deliverable-payment-flow-spec.md`) rather than assuming it's fine because no false "CLOSED+Kabir" claim was made about it — an absent gate that was never claimed is a different (lesser) risk than DPF-1's false claim, but still worth a one-line confirmation before the money-spine tasks (DPF-5/6/7) build on top of it.
