# vikram-work — answers (read from the tree, gates run 2026-09-04)

## Gate runs I actually performed

`.proof-os/gates/vikram-backend-wave-verified.sh` — exit 0. The gate itself runs `mvn -o -q`, so its
own grep for the surefire summary printed nothing; I re-ran the identical 11-class command without
`-q` to get real numbers:

```
mvn -o clean -Dtest=<the 11 classes in the gate's CLASSES var> -f influora-api/pom.xml test
```

Per-class, verbatim from the surefire output:

| class | Tests run |
|---|---|
| com.influora.job.CreatorAffiliateEarningSettlementTest | 3, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.ops.ProvisionSuperAdminRunnerTest | 2, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.admin.AdminAuthServiceTest | 3, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.BrandDeliverableServiceTest | 24, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.CampaignActivationGatesTest | 3, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.CampaignServiceTest | 35, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.ContractCancelAmendTest | 15, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.DealServiceBudgetTest | 3, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.DealServiceCollaboratorCapVerificationTest | 2, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.EscrowServiceReleaseOutcomeTest | 6, Failures: 0, Errors: 0, Skipped: 0 |
| com.influora.service.portfolio.PortfolioServiceRateCardTest | 5, Failures: 0, Errors: 0, Skipped: 0 |

Aggregate line, verbatim: `Tests run: 101, Failures: 0, Errors: 0, Skipped: 0` then `BUILD SUCCESS`.

`.proof-os/gates/vikram-wave-ab-verified.sh` covers a strict subset (4 of those 11 classes), so its
five rows are proved by the same run.

---

## 1. Escrow double-release under a concurrent race

Not prevented by the outcome tracking. The outcome record is a reporting change only, and I want to
be plain about that: `ReleaseOutcome` tells the caller whether money moved, it arbitrates nothing.

What actually prevents a double release is a pre-existing pessimistic row lock plus a status guard,
both older than this wave:

- `influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java:160` — "LockModeType.PESSIMISTIC_WRITE"
- `influora-api/src/main/java/com/influora/service/EscrowService.java:1613` — ".findByIdForUpdate(escrowHoldId)"
- `influora-api/src/main/java/com/influora/service/EscrowService.java:875` — "if (hold.getStatus() == EscrowStatus.RELEASED) {"

Both racing transactions take the SELECT ... FOR UPDATE on the same hold row; the loser blocks, then
re-reads a RELEASED row and returns the idempotent no-op. Below that there is a second belt: the
ledger posting carries a per-hold idempotency key derived server-side from the hold id, at
`influora-api/src/main/java/com/influora/service/EscrowService.java:902` — "milestone.markReleased(outcome.releaseTxnId(), idempotencyKey);"

Honest limit: the only test in this wave touching that path is single-threaded and mocks the
repository, so it proves the guard's LOGIC, not the lock —
`influora-api/src/test/java/com/influora/service/EscrowServiceReleaseOutcomeTest.java:267` — "tryReleaseOnApprovalIdempotentOnAlreadyReleasedHold"
A Mockito stub returns whatever it was told to return; it cannot exercise MySQL row locking. So: the
answer is "definitively prevented" on a reading of the code and the DB semantics, NOT on evidence
produced by this batch's tests. See Q9.

## 2. Cancel mid-flight with partial deliverables approved

There is no escrow release/refund CALCULATION at all on the contract-cancel path — it is a pure
status flip, and it cannot reach the scenario as worded.

`Contract.canCancel()` only admits two pre-execution states:
`influora-api/src/main/java/com/influora/domain/entity/Contract.java:244` — "return status == ContractStatus.DRAFT || status == ContractStatus.PENDING_SIGNATURES;"

A contract with approved deliverables is past those states, so F-0403's cancel refuses it outright
with CONTRACT_NOT_CANCELLABLE. And where cancel does apply, it touches only the contract row:
`influora-api/src/main/java/com/influora/service/ContractService.java:942` — "contract.setStatus(ContractStatus.CANCELLED);"

So funds genuinely CAN sit in an unreleased state, by design and not by accident. Once the
collaboration is CANCELLED, release is blocked
(`influora-api/src/main/java/com/influora/service/EscrowService.java:872` — "assertReleaseNotBlockedByCancellation(collaboration);")
and the only way money moves again is a manual, brand-initiated, OWNER/ADMIN-only refund:
`influora-api/src/main/java/com/influora/service/EscrowService.java:919` — "public EscrowStatusResponse refund(AuthPrincipal principal, String workspaceId, String escrowHoldId) {"

Nothing calls refund automatically on cancel. There is no netting of "already paid out vs should be
refunded" anywhere. That is a real, unclosed operational gap in this batch, not something F-0403 fixed.

## 3. maxCollaborators 50, funded for 10

The escrow-funded gate does NOT block that. It is a presence check, not an amount check — one FUNDED
hold of any size satisfies it:
`influora-api/src/main/java/com/influora/service/CampaignService.java:707` — ".anyMatch(h -> h.getStatus() == EscrowStatus.FUNDED);"
`influora-api/src/main/java/com/influora/service/CampaignService.java:369` — "requireFundedEscrow(campaign.getId());"

So a campaign capped at 50 with a single small funded hold activates. What actually limits exposure
is the separate F-0400 accept-time cap and the F-0399 cumulative-budget gate, both in DealService,
which run per-hire rather than at activation:
`influora-api/src/main/java/com/influora/service/DealService.java:1844` — "if (committedOthers + 1 > cap) {"

Creators already hired before the gate existed are entirely unaffected. The gate fires only on the
transition edge, and an already-ACTIVE campaign never enters it:
`influora-api/src/main/java/com/influora/service/CampaignService.java:274` — "campaign.getStatus() != CampaignStatus.ACTIVE && newStatus == CampaignStatus.ACTIVE;"

Two residual weaknesses worth stating rather than glossing: a PAUSED campaign whose escrow has since
been fully released can no longer be resumed (same edge catches PAUSED to ACTIVE), and the cap is
not re-checked when a brand PATCHes it downward — the code says so itself at
`influora-api/src/main/java/com/influora/service/DealService.java:1821` — "downward. A campaign already at N committed creators can still be patched to a lower cap;"

**Independent scrutiny verdict on F-0503:** the gate is real, server-side, reads fresh from the
repository, consults nothing client-supplied, and is enforced on BOTH paths to ACTIVE (this PATCH
and `ConfirmLaunchExecutor.doExecute`). Three tests pin it, including the negative PENDING-hold case:
`influora-api/src/test/java/com/influora/service/CampaignActivationGatesTest.java:116` — "testHumanPatchToActiveRejectedWithOnlyPendingEscrow"
It is as strong as claimed for what it claims — "at least one funded hold exists". It is NOT, and was
never, a solvency check.

## 4. Non-party invoking contract cancel / amend

Caller checks are enforced in the SERVICE layer, not the controller, and they are sufficient — a
guessed contractId does not help, because the tenant scope is applied to the lookup itself.

Brand cancel and amend both require workspace membership plus a role tier before the contract is
even loaded, and then load it scoped to that workspace:
`influora-api/src/main/java/com/influora/service/ContractService.java:921` — "WorkspaceMember member = brandContext.requireMember(principal, workspaceId);"
`influora-api/src/main/java/com/influora/service/ContractService.java:443` — "WorkspaceMember member = brandContext.requireMember(principal, workspaceId);"

The creator path is scoped to the authenticated creator's own id:
`influora-api/src/main/java/com/influora/service/ContractService.java:930` — "creatorContext.requireCreator(principal);"

A stranger therefore gets 404, not 403 — the contract is invisible outside its workspace. Amend has
no creator-side entry point at all.

One documented, still-open weakness on this exact surface, in the author's own words: there is no row
lock on the Contract row, so cancel racing recordSignature is not closed —
`influora-api/src/main/java/com/influora/service/ContractService.java:912` — "concurrent {@link #recordSignature}/{@link #recordSignatureForCreator} for the SAME contract"
That is an authorization-adjacent integrity gap, not an authorization hole.

## 5. Affiliate commission rate vs portfolio rate card

The portfolio rate card is never consulted. Commission is derived from the CAMPAIGN's configured
rate (or a flat default), multiplied by the redemption's own order amount:
`influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java:383` — ".map(Campaign::getCommissionRate)"
`influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java:388` — "redemption.getOrderAmount().multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);"

Critically, the amount is computed and FROZEN at earning-record time, then persisted on the earning
row. Settlement re-reads that stored amount and never recomputes:
`influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java:73` — "for (AffiliateEarning earning : settleable) {"

So the timing window in the question does not exist: a rate-card edit between deal creation and
settlement cannot inflate a payout, because (a) the rate card is not an input to this math at all
and (b) settlement uses a frozen amount. F-0498 / PortfolioServiceRateCardTest is about portfolio
display and visibility, a disjoint subsystem.

## 6. Revision counter after a contract amendment

The creator stays locked out. The counter does not reset.

The cap is read per-request from the collaboration and compared against the deliverable's own count:
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:347` — "if (deliverable.getRevisionCount() >= collaboration.getMaxRevisions()) {"

Amend writes a new Contract row and new PaymentMilestone rows and nothing else — I grepped its whole
body (lines 440 through 552 of the file cited just below) for deliverable, maxRevisions and
applyDealTerms and found zero matches. So neither the deliverable revision counter nor the
collaboration cap is touched by an amendment, and extending a deadline does not buy a fourth revision.
The amend body's last write is the milestone insert:
`influora-api/src/main/java/com/influora/service/ContractService.java:538` — "milestoneRepository.saveAll(milestones);"

Whether that is CORRECT is a product question nobody has ruled on. F-0417 shipped the cap; it did not
ship an amend-time reset, and there is no test asserting either behaviour after an amendment. Calling
this "handled" would be overstating it — it is "unhandled, and the unhandled behaviour happens to be
the conservative one."

## 7. Two simultaneous first-admin claims

There is NO database-level uniqueness constraint preventing duplicate SUPER_ADMIN grants. The guard
is an application-level check-then-insert with no lock and no transaction boundary:
`influora-api/src/main/java/com/influora/ops/ProvisionSuperAdminRunner.java:125` — "long existing = adminUserRepository.count();"

The only uniqueness in the schema is on email, which stops two runs using the SAME address but not
two runs using different ones:
`influora-api/src/main/resources/db/migration/V34__admin_tables.sql:16` — "VARCHAR(255) NOT NULL UNIQUE,"

The role column carries no partial-unique index and MySQL would not support one anyway. So yes: two
truly simultaneous runs with different emails could both succeed. In practice this is a one-shot ops
CLI that boots a whole Spring context, requires two env vars, and calls System.exit — the race needs
an operator to launch it twice concurrently on a fresh install. Low likelihood, real mechanism,
untested: `influora-api/src/test/java/com/influora/ops/ProvisionSuperAdminRunnerTest.java:93` — "refusesWhenAdminAlreadyExists"
is sequential and mocks the repository count.

## 8. Does MFA reset invalidate active sessions?

No. A compromised session persists. The reset writes exactly one row and revokes no token:
`influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java:343` — "adminUserRepository.save(target);"
`influora-api/src/main/java/com/influora/domain/entity/AdminUser.java:263` — "this.mfaEnabled = false;"

`AdminRefreshTokenRepository` is injected into that service but is not called anywhere in
resetMfaForAdmin, so the target's refresh tokens survive and their access token keeps working until
natural expiry.

The partial mitigation, which is real but incomplete: role and MFA state are read fresh from the DB
on every privileged call, so a target who is SUPER_ADMIN or ADMIN is locked out of privileged
endpoints on their very next request with MFA_SETUP_REQUIRED:
`influora-api/src/main/java/com/influora/service/admin/AdminContextService.java:125` — "if (mfaRequiredForRole && !admin.isMfaEnabled()) {"

The gap: SUPPORT is deliberately not covered by that check, so resetting a SUPPORT admin's MFA has
no session-side effect whatsoever. And no test in this batch asserts anything about session
invalidation.

**Independent scrutiny verdict on the MFA-reset authorization:** it IS as strong as claimed, and
both flagged concerns are genuinely tested, not self-reported. Caller tier and caller MFA are checked
in one fresh DB read before anything else:
`influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java:334` — "adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);"
Self-reset is rejected against the id loaded from that fresh read, never from the request body:
`influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java:335` — "if (caller.getId().equals(targetAdminId)) {"
Both have their own passing test:
`influora-api/src/test/java/com/influora/service/admin/AdminAuthServiceTest.java:100` — "nonSuperAdminCallerIsRejected"
`influora-api/src/test/java/com/influora/service/admin/AdminAuthServiceTest.java:120` — "selfResetIsRejected"
Both assert the exact code and 403 status. The check is also in the service, not the controller, so
it holds if the route is ever invoked another way. No weakness found in the authorization itself.

## 9. Are these fixes only proved by repository-mocking unit tests?

Yes — every single one. This is the honest headline finding of this review.

All eleven classes in the gate are `@ExtendWith(MockitoExtension.class)` with `@Mock` repositories.
I confirmed this class by class; three representative anchors:
`influora-api/src/test/java/com/influora/service/ContractCancelAmendTest.java:70` — "@ExtendWith(MockitoExtension.class)"
`influora-api/src/test/java/com/influora/service/CampaignActivationGatesTest.java:53` — "@ExtendWith(MockitoExtension.class)"
`influora-api/src/test/java/com/influora/service/admin/AdminAuthServiceTest.java:41` — "@ExtendWith(MockitoExtension.class)"

There is no `@SpringBootTest`, no `@DataJpaTest`, no Testcontainers in any of the eleven. Concretely,
none of these 101 tests can observe: the PESSIMISTIC_WRITE lock in Q1, a unique-constraint violation
in Q7, a transaction rollback, or Flyway/entity schema drift. What they prove is that the branch
logic is correct given the stubbed inputs — which is real value, and is exactly what the gate's own
header claims, but it is strictly weaker than "tested against a real database."

The gate does close one specific false-green mechanism it discovered the hard way, and I honoured it
(I ran clean, twice): `.proof-os/gates/vikram-backend-wave-verified.sh:18` — "revert-probe got a FALSE GREEN from a stale .class file on"

## 10. What is explicitly NOT addressed

All four named items are genuinely not addressed. Item by item:

- **Idempotency keys for escrow operations** — not addressed at the API-request level. `IdempotencyService`
  is never referenced anywhere in EscrowService (grepped, zero hits), so there is no request-replay
  key on release/refund/fund. What exists is a lower, ledger-level key derived from the hold id, which
  stops double money movement but not a duplicated caller request.
- **Audit trails for contract amendments** — not addressed. ContractService references no audit-log
  service at all (grepped for AdminAuditLogService and auditLogService: zero hits in the file). Amend
  supersedes a contract and writes new money-bearing milestone rows with no audit row. Its only
  post-write side effect is a best-effort notification:
  `influora-api/src/main/java/com/influora/service/ContractService.java:543` — "promptCreatorToSignIfPossible(collaboration, amended, workspaceId);"
- **Rate-limit enforcement on MFA reset** — not addressed. `AuthRateLimitFilter` classifies buckets by
  path prefix and has no branch for admin routes at all; its sensitive bucket covers only the
  non-admin prefix: `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:386` — "login, brand/login, brand/register, forgot-password, reset-password, logout"
  The endpoint is POST /admin/auth/mfa/reset/{targetAdminId}, which that filter never buckets.
- **Rollback on partial affiliate settlement failure** — partially addressed, and better than the
  question assumes. The batch loop runs inside a real proxied transaction, so a mid-batch failure
  rolls the whole batch back:
  `influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java:72` — "public void doSettleCreator(List<AffiliateEarning> settleable, AffiliateSettlementBatch batch) {"
  and each wallet credit reuses the earning's own stable unique key so a retry cannot double-credit:
  `influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java:116` — "earning.getIdempotencyKey(),"
  Caveat: no test in this batch exercises a mid-batch failure, so the rollback is proved by the
  annotation and the class's own history note, not by a red-then-green test.

Two further items are NOT closed by this wave and must not be read as closed by a green gate:

- **F-0489** — the backend dispatch is correct; the FRONTEND reachability gap remains open. Backend
  correctness here proves nothing about the finding.
- **F-0418** — blocked on a product ruling (what a missed deliverable deadline should actually do).
  No code was written; it is open.

Also still open and explicitly outside the gate: F-0644 and F-0645, new residual defects from the
amend feature, logged but not fixed.
