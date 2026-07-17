# CreatorContextService Isolation Review — Task #11 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)
**Date:** 2026-07-09
**Scope:** `influora-api/src/main/java/com/influora/service/CreatorContextService.java`, its test file, every caller in the codebase, and go/no-go assessment for Task #10 (creator-safe wallet/contract access paths)
**Reference Spec:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.3, §8 (Red Team Checklist — IDOR on profile/wallet/contract endpoints)

---

## Executive Summary

**VERDICT on `CreatorContextService` isolation: PASS.**

`requireCreator(principal)` and `requireCreatorProfile(principal)` derive identity **exclusively** from the authenticated `AuthPrincipal` (`principal.getUserId()`, `principal.getUserType()`). Neither method takes, nor is ever called with, a client-supplied id. Every existing controller/service that calls into `CreatorContextService` (4 call sites) does so with only the `@AuthenticationPrincipal` object — none pass a path/query/body creator id into it. No bypass paths found.

**GO / NO-GO on Task #10 (wallet/contract creator paths): CONDITIONAL GO — NO-GO on `ContractController` until a creator-ownership-scoped repository query is added. GO on `WalletController` provided the implementation follows the pattern documented below.**

- **`WalletController` / `WalletService`: safe to extend today.** `Wallet` is keyed 1:1 by `owner_id`, and `WalletService.requireOrCreateUserWallet(userId)` already exists and is safe *as long as* the new creator-facing controller method passes `principal.getUserId()` — never a request parameter — into it. No code changes needed in `CreatorContextService` for this path.
- **`ContractController` / `ContractService`: NOT safe to extend yet — HIGH finding.** `Contract` has no `creator_id` column and `ContractRepository` has **zero** query method that scopes a contract lookup to the calling creator's identity (only `findByIdAndWorkspaceId`, which is brand-only). If Task #10 is implemented by naively adding a `@GetMapping` that calls `creatorContext.requireCreatorProfile(principal)` and then loads the contract by `contractId` alone (or via the existing brand-only `requireContract` helper with a forged/absent workspaceId), it is a straight IDOR: any authenticated creator could read (and, via `/pdf-download-url`, download) **any other party's contract** by enumerating ULIDs. This must be fixed *in the same PR* that adds the creator path — see Finding H-1 below for the exact fix.

No Critical findings. One High finding (blocking Task #10's `ContractController` half only — `WalletController` half is unblocked). Two Low findings (hardening/defense-in-depth, non-blocking).

---

## 1. `CreatorContextService` Source Review

```21:38:influora-api/src/main/java/com/influora/service/CreatorContextService.java
    public void requireCreator(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }
    }

    public CreatorProfile requireCreatorProfile(AuthPrincipal principal) {
        requireCreator(principal);
        return creatorProfileRepository
                .findByUserId(principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND",
                                        "Creator profile not found",
                                        HttpStatus.NOT_FOUND));
    }
```

- Both methods take a single `AuthPrincipal principal` argument and nothing else — there is no overload, no `String creatorId` parameter anywhere in the class. It is structurally impossible for a caller to pass a client-supplied id into this service; the only identity input is whatever Spring Security resolved from the verified JWT into `AuthPrincipal`.
- `requireCreatorProfile` correctly calls `requireCreator` first (null + wrong-user-type check) before touching the repository — no risk of an NPE on `principal.getUserId()` from a null/brand principal.
- `findByUserId(principal.getUserId())` — the profile is resolved by the authenticated user's own id, then returned as a `CreatorProfile` object; all further ownership derivation by callers (e.g. `profile.getId()`) flows from this trusted resolution, not from anything request-supplied.
- Enumeration-oracle discipline: `CREATOR_PROFILE_NOT_FOUND` / `WRONG_USER_TYPE` responses reveal nothing about *other* users' data — they only describe the caller's own auth state.

**`AuthPrincipal` itself** (`influora-api/src/main/java/com/influora/security/AuthPrincipal.java`) is an immutable value object populated once at JWT-authentication time (`userId`, `email`, `userType`, `workspaceId`), with no setters — confirms there is no mutation path where a downstream filter/interceptor could let a request overwrite these fields after initial authentication.

## 2. Caller Audit (all existing usages)

Searched the full `influora-api/src/main` tree for `CreatorContextService` usages. Four call sites, all clean:

| Caller | Methods used | Identity source |
|---|---|---|
| `service/portfolio/PortfolioService.java` (`getMine`, `updateMine`, `syncPlatforms`, `analytics`, `uploadCover`) | `requireCreatorProfile(principal)` | `principal` only, from `@AuthenticationPrincipal` in `PortfolioController` |
| `service/CreatorProfileService.java` (`getMyProfile`, `patchMyProfile`) | `requireCreatorProfile(principal)` | `principal` only, from `@AuthenticationPrincipal` in `MeCreatorProfileController` |
| `web/MetaOAuthController.java` (`authorize`, `callback`, `status`, `disconnect`) | `requireCreator(principal)`, `requireCreatorProfile(principal)` | `principal` only |
| `CreatorContextServiceTest.java` | unit test | mocked principal, correctly asserts null/wrong-type rejection and correct-id resolution |

Cross-checked the corresponding controllers (`PortfolioController`, `MeCreatorProfileController`, `MetaOAuthController`) route-by-route: every `/me/...` and `/meta/oauth/...` route takes `@AuthenticationPrincipal AuthPrincipal principal` and, where a resource id appears in the signature at all (none do for these routes), it is never forwarded into `CreatorContextService`. The one controller with a `{creatorId}` path variable in the whole creator surface, `CreatorController` (`GET/POST /creators/{creatorId}...`), is an intentional **brand-side discovery** endpoint (a brand looking up a *different* creator's public profile) and correctly does **not** call `CreatorContextService` at all — it goes through `CreatorDiscoveryService`, which is the right design (no conflation of "look up any creator" with "resolve my own creator identity").

**Conclusion: no controller bypasses `CreatorContextService` or feeds it a client-supplied id. Item #1 and #2 of the task scope: PASS.**

## 3. Go/No-Go for Task #10 (Wallet/Contract Creator Paths)

### 3a. `WalletController` / `WalletService` — GO

```26:39:influora-api/src/main/java/com/influora/web/WalletController.java
    @GetMapping("/balance")
    public ApiResponse<WalletBalanceResponse> getBalance(@AuthenticationPrincipal AuthPrincipal principal) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return ApiResponse.ok(walletService.getBalance(workspace.getId()));
    }
```

Today this controller is brand-only (`requireBrandWorkspace`). `Wallet` rows are keyed directly by `owner_id`, which is set to either a `workspaceId` (brand) or a plain `userId` (creator, via `WalletService.requireOrCreateUserWallet(userId)`, already used internally by `EscrowService.release`). Because the lookup is a direct 1:1 keyed fetch (no join/ownership check needed — the id you pass IS the scope), this is safe to extend **provided** the new creator branch is implemented as:

```java
// SAFE pattern for Task #10:
if (principal.getUserType() == UserType.CREATOR) {
    creatorContext.requireCreator(principal);
    Wallet wallet = walletService.requireOrCreateUserWallet(principal.getUserId()); // NEVER a path/body param
    ...
}
```

No changes to `CreatorContextService` or `WalletService` are required for this half. Flagging one Low hardening item (L-1 below) but it does not block.

### 3b. `ContractController` / `ContractService` — NO-GO until fixed (HIGH)

**Finding H-1 (HIGH) — No creator-ownership-scoped query path exists for contracts; naive extension is an IDOR.**

- `Contract` (`domain/entity/Contract.java`) carries `workspace_id` and `collaboration_id`, but **no `creator_id` column of its own** — exactly the same "trust boundary is one hop away" shape the codebase already handles correctly for `Collaboration` (see `CollaborationRepository.findByWorkspaceId`, which joins through `Campaign.workspaceId` because `Collaboration` has no `workspace_id` column either) and for `ContractService.generate`'s campaign-ownership check (`campaignRepository.findByIdAndWorkspaceId(...)`, added per a prior Kabir Wave E1 escalation fix — see inline comment in `ContractService.java:110-128`).
- `ContractRepository` today only exposes:

```8:15:influora-api/src/main/java/com/influora/repository/ContractRepository.java
public interface ContractRepository extends JpaRepository<Contract, String> {

    Optional<Contract> findByIdAndWorkspaceId(String id, String workspaceId);

    List<Contract> findByCollaborationIdOrderByVersionDesc(String collaborationId);

    List<Contract> findByWorkspaceId(String workspaceId);
}
```

  There is **no** `findByIdAndCollaborationCreatorId(...)` or equivalent. All three `ContractService` read/write paths that resolve a contract by id (`get`, `getPdfDownloadUrl`, `recordSignature`, via the shared `requireContract(contractId, workspaceId)` helper) are hard-wired to the brand's `workspaceId` scope only.
- **Exploit scenario if Task #10 is implemented naively:** a creator-facing `GET /contracts/{contractId}` that does `creatorContext.requireCreatorProfile(principal)` (identity check — passes) and then either (a) calls `contractRepository.findById(contractId)` directly, or (b) reuses `requireContract(contractId, someWorkspaceId)` with a workspaceId the creator doesn't actually belong to (there is no code path stopping a creator from passing an arbitrary workspaceId, since `brandContext.requireMember` would reject a creator principal, but a hasty implementation might skip that call entirely for the creator branch) — either way, nothing ties `contractId` back to *this creator's own* collaboration. Any creator could enumerate ULIDs (or get one from a shared link, chat log, etc.) and read another creator's/brand's contract terms, signature status, and — worse — mint a presigned PDF download URL for it via `/pdf-download-url`. This is precisely item "Access other creator's contracts" in the spec's own Red Team Checklist (`12_CREATOR_SECURITY_SPEC.md` §8).
- **Required fix (must land in the same PR as Task #10, not after):**
  1. Add to `ContractRepository`:
     ```java
     @Query("SELECT c FROM Contract c WHERE c.id = :id AND c.collaborationId IN "
             + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId)")
     Optional<Contract> findByIdAndCreatorId(@Param("id") String id, @Param("creatorUserId") String creatorUserId);
     ```
     (mirrors the existing join-through pattern in `CollaborationRepository.findByWorkspaceId`).
  2. In `ContractService`, add a creator-scoped `getForCreator(AuthPrincipal principal, String contractId)` that calls `creatorContext.requireCreator(principal)` and then the new repository method with `principal.getUserId()` — **never** a request parameter — as the ownership key, throwing the same `CONTRACT_NOT_FOUND` (not a distinct "forbidden") on miss, to preserve the enumeration-oracle discipline already used elsewhere in this file.
  3. `getPdfDownloadUrl`'s creator path must route through the same ownership-scoped lookup before minting a presigned URL — this is the highest-value target for an IDOR (direct exfil of a signed legal document), so it should not be treated as a lower-priority follow-up.
  4. `recordSignature`'s creator-facing "I am the creator and I am signing" flow (if Task #10/future work adds one) has a separate, already-documented residual risk — see `ContractService.recordSignature`'s own javadoc (lines 190-237): today *only a brand principal* can ever reach that method, and role=CREATOR is recorded on the creator's behalf by an elevated brand member, not by the creator's own authenticated signature. If Task #10 or a later task introduces a real creator-authenticated signing call, it needs its own dedicated review — flagging this now so it isn't accidentally slipped in as a "safe read-only path" extension.

This is a **structural gap in `ContractService`/`ContractRepository`, not a flaw in `CreatorContextService`** — `CreatorContextService` did its job (proved WHO the caller is); nothing downstream yet proves WHAT that caller is allowed to see for this particular resource. Both facts are needed to safely branch `ContractController`.

## 4. Additional Hardening Findings (non-blocking)

**Finding L-1 (LOW) — No defensive null-profile check pattern for future nested-resource ownership helpers.**
`CreatorContextService` has no equivalent of `BrandContextService.requireMember(principal, workspaceId)` — i.e. no "does this creator own resource X" helper at all today, only "is this principal a creator" / "give me their profile." This is fine for the four current callers, none of which need nested-resource ownership checks. It becomes a gap the moment Task #10 (contracts) or any future creator feature needs to check "does creator X own collaboration/contract/milestone Y" — there is no shared, tested helper to reach for, which is exactly how H-1 above happens organically. **Recommendation:** once the Contract fix in H-1 lands, consider promoting a small `requireOwnedCollaboration(AuthPrincipal, String collaborationId)` helper into `CreatorContextService` itself (parallel to how `BrandContextService.requireMember` centralizes workspace-membership checks) so every future creator-resource endpoint (deliverables, milestones, deals in Task #9) reuses one audited chokepoint instead of each service reinventing its own join query.

**Finding L-2 (LOW) — Test coverage for `CreatorContextService` doesn't cover a mismatched/foreign profile row.**
`CreatorContextServiceTest` covers: null principal (rejected), brand principal (rejected), and a matching profile resolved correctly. It does not have a test asserting that `findByUserId` is called with the principal's id and nothing else (e.g. a test with two `CreatorProfile` fixtures in the mock repository, asserting the wrong one is never returned). Given the class currently has no other input to get wrong, this is a low-value gap today, but worth adding alongside the Task #10 Contract fix so the "identity resolution is single-sourced from the principal" invariant has an explicit regression test, not just an implicit one from reading the source.

## 5. Task #7 (`CreatorCampaignController`/`CreatorCampaignService`) — Not Yet Reviewable

As of this review (2026-07-09, ~11:57 IST), `influora-api/src/main/java/com/influora/web/CreatorCampaignController.java` and `influora-api/src/main/java/com/influora/service/CreatorCampaignService.java` **do not exist yet** (Vikram's Task #7 is still in progress in parallel). Confirmed via repo-wide glob — zero matches for either filename.

**Follow-up (not a blocker on this review):** once Vikram lands Task #7, it must be re-reviewed for the same class of issue as H-1 — specifically: `POST /api/v1/creator/campaigns/{id}/apply` must derive the applying creator's id from `creatorContext.requireCreatorProfile(principal)` (or `principal.getUserId()`) when constructing the `Collaboration`, never from a body field, and the `UNIQUE(campaign_id, creator_id)` duplicate-apply guard mentioned in `TASK_INBOX.md` Task #7 item 4 must key off that same server-resolved id. Will review as soon as the files land; tracked as an open item, not folded into this PASS verdict.

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| H-1 | ~~**HIGH**~~ | `ContractController`/`ContractService`/`ContractRepository` — no creator-ownership-scoped contract lookup exists; naive Task #10 extension = IDOR on contract read + PDF download | **RESOLVED** — Vikram Task #10 landed `findByIdAndCreatorId` + scoped service methods; Kabir re-review **PASS** 2026-07-09 — see `wiki/errors/creator-wallet-contract-T10-kabir-redteam.md` |
| L-1 | LOW | `CreatorContextService` has no reusable nested-resource ownership helper (parallels `BrandContextService.requireMember`) | Open — recommend addressing alongside H-1 fix |
| L-2 | LOW | `CreatorContextServiceTest` lacks an explicit multi-profile "returns only the caller's own row" regression test | Open — non-blocking |

**`CreatorContextService` itself: PASS, no findings.**

## Go/No-Go Decision for Task #10

| Sub-scope | Decision |
|---|---|
| `WalletController` creator-safe balance/summary path | **GO** — implement per the pattern in §3a (derive owner id from `principal.getUserId()` only) |
| `ContractController` creator-safe contract read + PDF download path | ~~**NO-GO**~~ → **GO** — H-1 resolved in Task #10; Kabir re-review PASS (`wiki/errors/creator-wallet-contract-T10-kabir-redteam.md`) |

~~Vikram may proceed on the wallet half of Task #10 immediately. The contract half must include the `ContractRepository`/`ContractService` ownership-scoping fix described in H-1 before merging — this review will re-check that specific diff on request.~~

**Update 2026-07-09:** H-1 fix verified closed. Task #10 full scope cleared for Meera build + Ananya wallet UI.
