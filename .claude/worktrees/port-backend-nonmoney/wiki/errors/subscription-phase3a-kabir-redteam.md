# Red-Team Review: Subscription Billing PHASE 3a (Task 21 — Per-Plan Fee Override)
**Date:** 2026-07-14
**Reviewer:** Kabir (Red-Team / Offensive Security Lead)
**Scope:** Authorized internal security review of Sage Digital's own codebase (Influora), money-path fee-calculation change.
**Status:** ❌ **FAIL — CRITICAL FINDING, BLOCKS PIPELINE**

---

## VERDICT SUMMARY

| Severity | # | Finding |
|---|---|---|
| **CRITICAL** | 1 | Brand platform publish fee (10%/7%) is **never charged at all** when a campaign is activated via the Meera AI `confirm_launch` tool — a 100% fee bypass on that path, not merely a 7%-vs-10% miscalculation. |
| **Medium** | 1 | Stale/false documentation (class javadoc + test comments) asserts the fee IS charged on the AI path, which will mislead future reviewers (including this review, until verified against the actual call graph) into believing coverage exists where it doesn't. |
| **Low** | 1 | Theoretical TOCTOU window between a Subscription-state-changing webhook and a same-workspace campaign-publish transaction; not practically exploitable by a brand for gain. |
| **Informational** | 1 | `resolveBrandFeeBps` is annotated `@Transactional(readOnly = true)` but is always invoked while already inside `chargeOnPublish`'s writable transaction — the annotation is misleading (harmless due to Spring's propagation semantics) but should be removed to avoid a future footgun if ever called standalone. |

Everything Vikram/Kavya specifically verified about **`resolveBrandFeeBps`'s internal PRO-vs-Free/fallback logic** (the code Task 21 actually touched) is correct — see PASS items below. The CRITICAL finding is not a miscalculation inside that method; it's that an entire second, live, production-reachable campaign-activation code path never calls it (or `chargeOnPublish`) at all.

---

## CRITICAL-1: Meera AI `confirm_launch` path bypasses the brand publish fee entirely

### The bug

Exactly two places in the codebase transition a `Campaign` to `ACTIVE`:

1. **`CampaignService.update()`** (`influora-api/src/main/java/com/influora/service/CampaignService.java:202-278`) — the human/brand PATCH-driven publish path. Gated correctly: `chargeOnPublish` is called at line 273, inside the same `@Transactional` method, right after the status flip, exactly per the class's atomicity contract.
2. **`ConfirmLaunchExecutor.doExecute()`** (`influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java:285`) — the Meera AI-confirmed launch path (`confirm_launch` tool, C-tier per `06-MEERA-PERMISSIONS-MATRIX.md`). Line 285 does `campaign.setStatus(CampaignStatus.ACTIVE); campaignRepository.save(campaign);` directly — **there is no call to `BrandCampaignFeeService.chargeOnPublish` or `resolveBrandFeeBps` anywhere in this class.** Confirmed by `grep -rn "chargeOnPublish\|resolveBrandFeeBps" influora-api/src/main/java` — the only matches are `BrandCampaignFeeService.java` itself and `CampaignService.java:273`.

`confirm_launch` is a real, wired, production-reachable flow — it verifies a genuine `EscrowStatus.FUNDED` hold from the DB, invites creators, binds escrow, resets AI credits, and records an audit-logged, idempotent tool-call. It is not a stub or dead code. Any brand whose campaign reaches this path (create via Meera → fund escrow → AI confirms launch) gets their campaign flipped DRAFT→ACTIVE and **pays 0% platform fee**, versus 10% (Free) or 7% (Pro) if the exact same activation happened through the human `PATCH /campaigns/{id}` endpoint. This is not a Free-vs-Pro rate confusion — it is a complete bypass of the entire brand-fee mechanism on a live code path.

### Why this wasn't caught by Kavya's QA or Vikram's own claims

Task 21's scope, and Kavya's 7 checks, were both framed around "does `resolveBrandFeeBps` compute the right percentage" — and that logic is in fact correct (see PASS items below). Neither Kavya's review nor the task's own audit note ("Wire the new signature at all `chargeOnPublish` call sites (currently only `CampaignService.java:228`)") questioned whether that was the *only* call site that should exist. It should not be — `ConfirmLaunchExecutor` also flips status to ACTIVE and needs the same charge.

### The documentation actively asserts this is covered — it is not

This is what makes the bug dangerous rather than merely incomplete. Three separate places in the (uncommitted) working tree claim the fee IS handled on the AI path:

- `BrandCampaignFeeService.java:33-40` (class javadoc, new/untracked file): *"MUST be called from inside the same `@Transactional` method that flips the campaign's status to `ACTIVE` (`CampaignService.update` for the human/brand-initiated publish path, `ConfirmLaunchExecutor.doExecute` for the Meera AI-confirmed launch path)."* — **False for the second half of that sentence**, as written today.
- `ConfirmLaunchExecutorTest.java:56-58` (class javadoc): references "the `transitioningToActive` flag that already gated the brand publish fee" as if it's a settled, present-tense fact for this class.
- `ConfirmLaunchExecutorTest.java:105-108` — the test's own `@DisplayName` reads *"Real DRAFT -> ACTIVE transition: **charges the fee once**, invites creators, binds funded holds, resets AI credits once..."* — but the test body has **no fee assertion whatsoever**; it contains the bare comment `// NOTE: brandCampaignFeeService removed from constructor` at line 133 and asserts nothing about a charge.
- `ConfirmLaunchExecutorTest.java:288-293` — explains the history: *"the wallet-balance publish-fee charge (`brandCampaignFeeService.chargeOnPublish`) was relocated off this Meera executor to `CampaignService` (see `CampaignService.java:228`); this executor's constructor no longer takes a fee service... Equivalent coverage lives in `CampaignServiceTest`."* This confirms the fee-charging call was deliberately **removed** from `ConfirmLaunchExecutor` at some prior point (tagged "P3-20 Vikram fix") on the assumption that `CampaignService`'s copy would cover it. It does not — `ConfirmLaunchExecutor.doExecute` never calls into `CampaignService` at all; they are two independent code paths that both happen to flip the same enum value on the same entity.

Net effect: anyone reading the code today (including a future Kabir pass, or Priya's sign-off) has three separate, confident-sounding assertions telling them the AI path is covered. It is not. Fix the code, then fix (or delete) all three of these claims so they match reality.

### Fix required before this can pass

`ConfirmLaunchExecutor.doExecute` must charge the brand publish fee at the same DRAFT/PAUSED/PENDING_APPROVAL → ACTIVE transition point (line 285), inside the same `@Transactional` method, using the same `workspaceId` it already has server-side (from the on-behalf-JWT-resolved caller, not AI input) — mirroring `CampaignService.update()`'s pattern exactly: charge-then-save, so a fee failure (insufficient balance) rolls back the whole launch including the status flip, the invites, and the escrow-hold binding. `BrandCampaignFeeService` needs to be reinjected into `ConfirmLaunchExecutor`'s constructor (it was apparently removed as part of "P3-20"), and `ConfirmLaunchExecutorTest` needs a real assertion (`verify(brandCampaignFeeService).chargeOnPublish(...)`) replacing the current `// NOTE: ... removed from constructor` placeholder comments (4 occurrences) and the misleading `@DisplayName`/javadoc claims.

**Route back to Vikram with this specific fix. This blocks the pipeline — do not proceed to Meera/Priya until this is closed and re-verified.**

---

## VIKRAM'S 5 CLAIMS — VERIFIED INDEPENDENTLY

### ✅ 1. `plan.getCode() == PRO` check (not value-based)

Confirmed at `BrandCampaignFeeService.java:111`: `if (plan != null && plan.getCode() == PlanCode.PRO && plan.getFeeBps() != null)`. This is a genuine enum-identity check, not a nullability/value heuristic — agrees with Kavya's CHECK 2. Confirmed this is the right design: `PlanRepository`/`PlanService` have no client-reachable write path (Task 25 admin console isn't built yet), so `PlanCode` per row is immutable outside a DB migration — not fragile to seed-data changes.

### ✅ 2. PAST_DUE/CANCELLED/HALTED Pro → 10% fallback

Traced independently at `SubscriptionService.java:93-100` (`getActivePlanForWorkspace`): `.filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)` — any non-ACTIVE status (including PAST_DUE/HALTED/CANCELLED) fails the filter and falls to `orElseGet(planService::getFreePlan)`. From `BrandCampaignFeeService`'s perspective this Free plan then fails the `PlanCode.PRO` check, landing on the global 10%. Also independently confirmed the `Plan.isActive()` guard on line 98 — a subscription pointing at a deactivated Plan row also correctly falls back to Free. This is the correct, kill-switch-safe design. Test coverage (`BrandCampaignFeeServiceTest.java:98-113`) is real (`assertEquals(1000, bps)`, not a no-throw check).

### ✅ 3. Fail-open direction is safe as claimed

Traced `tryResolvePlanFeeBps` (`BrandCampaignFeeService.java:108-126`): catches `RuntimeException`, logs via `log.error` with workspaceId + full exception (not a silent swallow), returns `null`, and the caller (`resolveBrandFeeBps`, line 100-106) unconditionally re-reads `requireConfig().getBrandFeeBps()` fresh from `PlatformFeeConfigRepository` — there is **no cached/guessed Plan value** on the exception path, and no path where an exception could be misread as "resolution succeeded with value 700." Confirmed no intermediate variable retains a stale Pro rate across the catch boundary — `planFeeBps` is scoped inside the try, and the catch block's `return null` is the only value that survives to the caller.

One boundary case worth naming precisely: the catch is `catch (RuntimeException e)`, not `catch (Throwable e)`. An `Error` (e.g. `StackOverflowError`, `OutOfMemoryError`) inside `getActivePlanForWorkspace` would propagate uncaught through `resolveBrandFeeBps` and **block** the publish (fail-closed), not silently undercharge. That is the opposite of a security hole — a legitimate publish could theoretically be blocked by an `Error`, but no brand can ever be charged less than intended by this path. Not a finding; noting it because "never blocks a legitimate publish" was one of Vikram's claims and this is the one edge where it's technically not absolute (matches "publish is blocked," not "publish underpays").

**Priya/Kabir sign-off on the fail-open direction: approved.** The design correctly biases toward "worst case, overcharge a Pro brand once" rather than any path that could undercharge.

### ✅ 4. Free-tier cannot reach the Pro fee path; Plan/Subscription integrity holds

Traced `getActivePlanForWorkspace` end-to-end (see #2). Also traced how a `Subscription` row's `planId` can ever be set:
- `createFreeSubscription` (lazy provisioning) — always `planService.getFreePlan().getId()`.
- `applySubscriptionWebhookUpdate` (`SubscriptionService.java:284-374`) — only reachable from `RazorpayWebhookController`, which HMAC-verifies every request (`WebhookSignatureVerifier`) before any parsing. `resolvePlanForWebhook` (line 376-387) resolves `razorpayPlanId` from the payload against `PlanRepository.findByRazorpayPlanId`, defaulting to Pro if unresolvable (documented as safe because Free never has a Razorpay-side subscription — an unresolvable id can only originate from a genuine Razorpay Pro-plan checkout `initiateCheckout` already created).
- No other write path to `Subscription.planId` exists — Task 25 (admin comp/override) is explicitly unbuilt (`AdminBillingController` doesn't exist yet), so there is no way today for a `Plan` row to be "swapped without going through paid checkout." Confirmed via `SubscriptionRepository`/`PlanRepository` — both are plain Spring Data interfaces (`findByWorkspaceId`, `findByCode`, `findByRazorpayPlanId`, etc.), no native queries, no injection surface, no unscoped bulk-update method.

### ✅ 5. AI-credit allotment precedence (Pro 400 overrides Free 150 loyalty; no free-tier leak)

Traced `AICreditResetJob.applyProAllotmentIfActive` (line 123-128) → gated on `plan.getCode() == PlanCode.PRO`, itself sourced from the same `getActivePlanForWorkspace` contract verified above. `AICreditService.applyPlanAllotment` (line 155-162) is called from exactly one place in the whole codebase (`AICreditResetJob`) — confirmed via grep, no controller or other caller exposes `planAllotment` as a client-suppliable int. A Free-tier brand can only ever reach `resetForNewCycle` (preserves existing 100/150) — `applyPlanAllotment` is unreachable for them. Matches Kavya's CHECK 5 exactly; independently re-verified, no gaps found.

---

## ADDITIONAL CHECKS PERFORMED (per red-team brief)

### Race condition: subscription-state TOCTOU around campaign publish — LOW, not practically exploitable

`resolveBrandFeeBps` runs inside `chargeOnPublish`'s `@Transactional` method, which itself runs inside `CampaignService.update()`'s own `@Transactional` (Spring's default `REQUIRED` propagation joins the same DB transaction — confirmed no `Propagation.REQUIRES_NEW` anywhere in this chain). The campaign row itself is pessimistically locked (`loadOwnedForUpdate`, tagged "[SEC: Kabir fix 2a]" — a prior fix serializing the whole status-flip-plus-charge sequence). The `Subscription` row is **not** locked — its read is a plain `SELECT` under whatever the DB's default isolation is (Postgres `READ COMMITTED`), so a concurrent webhook-driven status change could commit in the gap and be seen or not seen depending on timing.

This is a real TOCTOU window in the abstract, but not one a brand can weaponize for gain:
- `SubscriptionService.cancel()` (self-service cancellation) deliberately leaves `status = ACTIVE` and only sets `cancelAtPeriodEnd = true` — a brand-initiated cancel has **no immediate effect** on the fee rate, so there's no race to win by timing a publish around their own cancel click.
- The only events that actually flip status away from ACTIVE (`subscription.pending`/`halted`/`cancelled`) originate from Razorpay's side (payment failure, dunning), which a brand cannot trigger or time at will to shave a window in their favor.
- Direction of harm, even in the theoretical case: a publish landing in the split-second before a halted/cancelled webhook commits would read stale-ACTIVE-Pro and pay 7% instead of 10% — an undercharge, but bounded to webhook processing latency (milliseconds to low seconds in practice), not brand-controllable, and worth at most one publish's difference (300bps of one campaign's budget).

**Recommendation (non-blocking):** no code change required for Task 21 to ship. If this is ever tightened, the cheap fix is a `SELECT ... FOR SHARE`/pessimistic read on the `Subscription` row inside `resolveBrandFeeBps`'s transaction, but given the analysis above this is not worth the added lock contention for the exposure it closes.

### SQL injection / workspaceId server-derivation / logging hygiene — PASS

- **No SQL injection surface added.** `PlanRepository`, `SubscriptionRepository` are Spring Data derived-query interfaces only (`findByCode`, `findByWorkspaceId`, `findByRazorpaySubscriptionId`, `findByRazorpayPlanId`, `findByStatus`) — no `@Query` with string concatenation, no native SQL, anywhere in the Task 21 diff or the services it touches.
- **workspaceId is server-derived end-to-end**, re-traced independently (not just re-trusting Kavya): `CampaignService.update(AuthPrincipal principal, ...)` → `brandContext.requireBrandWorkspace(principal)` (`BrandContextService.java:34-56`) → reads `principal.getWorkspaceId()` from the JWT-backed `AuthPrincipal`, falling back to a DB lookup keyed by `principal.getUserId()` (also JWT-derived) if the claim is blank. Zero `@RequestBody`/`@PathVariable`/`@RequestParam` involvement in workspace resolution. Confirmed `chargeOnPublish(Campaign, String workspaceId)`'s only caller passes `workspace.getId()` from this chain (`CampaignService.java:273`). Confirmed for the AI path too: `ConfirmLaunchExecutor.doExecute(String workspaceId, ...)` receives `workspaceId` from `MeeraInternalController`'s on-behalf-JWT resolution (`OnBehalfAuthResolver`, per class javadoc) — not from AI tool-call input.
- **Logging hygiene:** the one new log statement (`BrandCampaignFeeService.java:119-122`) logs `workspaceId` (a ULID, not sensitive) and the caught exception at `error` level — no fee amounts, wallet balances, or PII logged. No new log statements elsewhere in the Task 21 diff.

### Creator fee isolation — PASS (re-confirmed)

`PlatformFeeService.resolveCreatorFeeBps()` unchanged, still `requireConfig().getDefaultFeeBps()`, no workspaceId parameter. Independently confirmed via the same file read Kavya cited.

---

## WHAT TO DO NEXT

1. **Route back to Vikram** with CRITICAL-1: wire `BrandCampaignFeeService.chargeOnPublish` into `ConfirmLaunchExecutor.doExecute` at the DRAFT→ACTIVE transition (line 285), same transactional/rollback contract as `CampaignService.update()`. Fix the 3 stale documentation claims (class javadoc, test class javadoc, test `@DisplayName` + the 4 `// NOTE: ... removed from constructor` comments) once the real call is back in place — don't just fix the code and leave the docs lying about a different mechanism.
2. **Do not route to Meera/Priya yet.** This is a Critical finding per the pipeline's own gate rule — it blocks until fixed and re-verified.
3. Everything else audited in this pass (the 5 claims Vikram made, plus the additional standard money-path checks) is **correct and does not need rework**. Once CRITICAL-1 is fixed, a narrow re-check of just that fix (plus its new test) should be sufficient — the rest of this review does not need to be repeated.

---

**Reviewed by:** Kabir, Red-Team / Offensive Security Lead
**Date:** 2026-07-14
**Files reviewed:** `BrandCampaignFeeService.java`, `SubscriptionService.java`, `AICreditResetJob.java`, `AICreditService.java`, `CampaignService.java`, `ConfirmLaunchExecutor.java`, `RazorpayWebhookController.java`, `BrandContextService.java`, `Plan.java`, `Subscription.java`, `PlanCode.java`, `PlanRepository.java`, `SubscriptionRepository.java`, `PlanService.java`, `IdempotencyService.java`, `BrandCampaignFeeServiceTest.java`, `AICreditServiceTest.java`, `ConfirmLaunchExecutorTest.java`, plus `git diff`/`git log` against HEAD to distinguish pre-existing behavior from this diff's changes.
