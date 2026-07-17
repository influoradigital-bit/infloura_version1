# Kabir Red-Team — Brand Tracker P1-#1 (Contracts, GET /contracts brand-scoped list)

**Scope:** `ContractService.listForBrand`, `ContractController.list()` GET /contracts brand branch,
`sign()` regression check, `pdfDownloadUrl()` IDOR check, new unit test coverage.

**Verdict: PASS**

## 1. Workspace scoping in `listForBrand` — genuinely enforced, first check
`ContractController.list()` (ContractController.java:46-55): for brand callers, `workspace =
brandContext.requireBrandWorkspace(principal)` resolves the workspace **server-side** from the JWT
principal (`BrandContextService.java:34-56` — principal.getWorkspaceId() or membership lookup by
principal.getUserId(); never a client-supplied param). There is no `workspaceId` request param
anywhere on `GET /contracts`. `listForBrand(principal, workspace.getId())` then calls
`brandContext.requireMember(principal, workspaceId)` (ContractService.java:505) as the literal first
statement, before `contractRepository.findByWorkspaceId(workspaceId)` is reached. No client-request
manipulation is possible because the workspace id is never attacker-controlled input in the first
place — same discipline as the E1-audited `generate()` resolve-then-scope fix (lines 119-137) and the
existing `get()` method immediately above (lines 495-500).

## 2. Pattern match against already-audited siblings — confirmed, no weakening
`listForBrand` is byte-for-byte the same shape as `get()` (requireMember → repository call scoped to
workspaceId → toResponseWithMilestones), and matches the pattern SHARED_CONTEXT already recorded as
audited for `BrandDisputeController`/`DisputeService.listForBrand` ("workspace resolved via
BrandContextService.requireBrandWorkspace (server-side, never path-param) — no cross-tenant leak
found"). `ContractRepository.findByWorkspaceId` is a direct derived-query match on `Contract.workspaceId`
(a real column set at `generate()` time, ContractService.java:158) — no join ambiguity, no possibility
of matching another workspace's rows.

## 3. `sign()` flow — no regression, previously audited
Untouched by this cycle. Extensive in-code documentation (ContractService.java:183-247) records prior
Kabir findings already fixed:
- **E2 finding #10 (HIGH)** — already-signed idempotent no-op guard (`doRecordSignature`,
  lines 324-334) prevents duplicate PDF/email on retry.
- **E2 LOW-3** — true-concurrency race closed via `IdempotencyService.executeOnce` keyed
  `contract-sign:{contractId}:{ROLE}` (lines 269-289).
- **E2 LOW-4** — role-forgery: `role=CREATOR` signing on behalf of the creator now requires elevated
  `OWNER/ADMIN/MANAGER` membership (line 263), same gate as `generate()`. Residual risk (brand relays
  creator's out-of-band assent — no real creator cryptographic signature) is explicitly flagged in the
  javadoc as a product decision, not silently swallowed.
- Frontend's new `handleSignContract` call (`contracts-and-deliverables.tsx:1306-1320`) only sends
  `{signerRole:'BRAND'}` — the low-risk, non-forgeable branch — and provides no path to invoke the
  CREATOR-role branch, so this cycle doesn't even reach the residual-risk code path.
No new sign()-adjacent regression found. Citing prior audit rather than re-doing it, per task
instructions.

## 4. `pdfDownloadUrl` — correctly workspace/contract-scoped, no enumeration
`getPdfDownloadUrl(principal, workspaceId, contractId)` (ContractService.java:549-555) calls
`requireContract(contractId, workspaceId)` → `contractRepository.findByIdAndWorkspaceId(contractId,
workspaceId)` (line 590-595) — a brand cannot fetch another workspace's contract PDF by guessing/
enumerating contract ids; a mismatched contractId+workspaceId 404s with `CONTRACT_NOT_FOUND` before any
presigned URL is minted. The presigned URL itself (`r2StorageService.presignGet`) is scoped to the
specific object key `contracts/{contractId}.pdf` already bound to that (validated) contract. Frontend
opens the returned URL directly (`window.open(..., 'noopener,noreferrer')`) — no additional client-side
trust issue.

## 5. New unit test coverage — real, not happy-path-only
`ContractServiceTest.java:486-504` (`testListForBrandReturnsOwnWorkspaceContracts`) — asserts
`requireMember`/`findByWorkspaceId` called with the right workspace id AND explicitly asserts
`contractRepository, never()).findAll()` (guards against an unscoped-query regression, not just a
trivially-true assertion).
`ContractServiceTest.java:512-532` (`testListForBrandRejectsNonMemberOfOtherWorkspace`) — stubs
`requireMember` to throw FORBIDDEN for `OTHER_WORKSPACE_ID`, asserts the exception propagates, AND
`verifyNoInteractions(contractRepository)` — proves the repository is never even queried on the
rejected path, i.e. genuine cross-workspace IDOR coverage, not just a status-code check.

## Overall
No Critical/High/Medium findings. Scoping is server-derived end-to-end (client never supplies a
workspace id on this route), matches the codebase's existing audited pattern exactly, sign() has no
regression (prior E2 fixes intact, cited not re-audited), PDF download is contract+workspace scoped,
and the two new tests cover the actual adversarial case (cross-workspace access attempt), not just the
happy path.

**PASS.**
