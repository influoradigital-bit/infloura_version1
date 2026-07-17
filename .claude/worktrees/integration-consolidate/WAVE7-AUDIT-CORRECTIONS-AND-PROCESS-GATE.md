# WAVE 7 — Audit Corrections (C1) + Merge Gate (P1)

**Owner:** Priya (CTO) + Arjun (COO) · **Date:** 2026-07-15
**Applies to:** `INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md` and its verified variants.
**Status:** C1 complete. P1 requires Swapnil co-sign.

---

## C1 — Corrections to the audit report

Each correction below was reached by an expert who first tried to *disprove* the finding, verified against the live trunk (`integration/consolidate`). These lower severities or remove false findings; **they do not change the audit's overall verdict** ("not production ready"), which stands.

### Previously documented (from CTO-VERIFICATION-VERDICT.md) — confirmed
1. **Notification response envelope — WRONG.** Backend *does* return the `ApiResponse` envelope; the client's `data.data` read is correct.
2. **`/notifications/{id}/read` 404 — WRONG.** Client posts `/notifications/read` with `{notificationId}`, which matches the backend. Only `/notifications/read-all` truly 404s.
3. **brand-chat "makes no API calls" — OVERREACH.** In live mode it genuinely calls `messages.list/send/markRead` and `deliverables.list/approve/requestRevision`; only deal-list + proposal-send were mock.
4. **creator-wallet "entirely mock" — OVERREACH.** Balance/payouts were mock, but the Invoices tab was already wired to `creatorInvoicing.*`.
5. **Webhooks/JWKS 401 — real but MIS-SEVERED.** Fail-*closed*: an integration/functional breakage, **not** an exploitable vulnerability. (Fixed: S2.)
6. **Escrow-milestone IDOR — real but MIS-SEVERED.** Cross-tenant **info disclosure** (you fund from your own wallet), not fund theft. LOW–MED, not HIGH. (Fixed: S5.)
7. **"Deliverable submit drops media" — imprecise.** Media uploads via a separate multipart route; the real defect is the ignored `fileUrls` field on submit (F9).
8. **Contract generate `milestones[]` "required" — imprecise.** Only `collaborationId` is mandatory.

### NEW corrections found during the 2026-07-15 delta pass
9. **`payouts` table "orphaned" — WRONG.** `Payout.java:18` maps `@Table(name="payouts")`. The table was never orphaned. Only `file_uploads` is genuinely orphaned (no entity, no repository) — D6 still open.
10. **"IdempotencyService money-path merge blocker" — STALE/WRONG.** Widely repeated in trackers and in CTO memory. On the trunk, `IdempotencyService` is fully wired into `DealService`/`ContractService`/`PayoutService` and `mvn -o test-compile` is GREEN. The source doc (`wiki/tech/BRANCH_COMPILE_BLOCKERS_2026-07-12.md`) was about a **different branch** (`feature/analytics-platform`), 3 days stale, and self-marked resolved.
11. **"916 dirty files / working tree unsafe" — STALE.** The tree was clean (3 untracked docs). W0-1's "safeguard 916 files" premise was false.
12. **W0-3's prescribed `git merge` was DANGEROUS, not merely hard.** `claude/…-bc5269` forked early: 3 commits past the merge-base vs **24** for d14. The 353-file diff is 17k insertions / **19k deletions** — a naive merge would have *reverted* D14 invoicing work. Correct route was selective port, which is what was executed.
13. **The plan targeted the wrong trunk.** `integration/consolidate` already existed, strictly ahead of `feature/d14-invoicing` (8 ahead / 0 behind), and had already ported most non-money remediation. Following the plan literally would have duplicated that work and re-created the very side-branch failure the plan condemns.
14. **A prompt-injection hole the audit missed.** Beyond A8's named `_wrap_untrusted` weakness, `assembler.py` Block B interpolated brand-authored `display_name` **raw into a SYSTEM block** — the highest-trust role in the prompt. `untrusted.py`'s own docstring falsely claimed `build_block_b` was hardened (doc/code drift). Fixed in `4fa3c85`.

### Net accuracy (revised)
Direction of the audit: **trustworthy**. Roughly 87–90% of findings confirmed exactly; ~4% outright wrong; ~9% correct-but-overstated. Two *additional* real defects were found that the audit missed (#14, and the `@Value` prefix bug behind D4 that made MSG91 permanently mock regardless of credentials).

### Standing lesson
Trackers in this repo **over-claim**. Three separate "known facts" (916 dirty files, the Idempotency blocker, `payouts` orphaned) were false on the live disk. **Verify against the working tree before acting on any status doc.** Equally: `vite build` passing is not evidence the app works — it skips typecheck and tests, and vitest was 116-failing the whole time the build was "green".

---

## P1 — Merge gate (root-cause fix)

**Root cause of this whole exercise:** remediation was written, then stranded on a side branch (`claude/influora-prod-readiness-audit-bc5269`) plus `stash@{1}`, and never merged. The audit then correctly reported the shipping branch as broken, while the team correctly remembered writing the fixes. Both were right. The process was wrong.

### The gate (binding once Swapnil co-signs)
1. **No remediation on side branches or stashes.** All remediation lands on the designated integration trunk (currently `integration/consolidate`).
2. **Trunk is declared, not assumed.** Exactly one integration branch is the trunk at a time; it is named in `SHARED_CONTEXT.md`. Any doc naming a different trunk is stale until proven otherwise.
3. **PR → shipping requires a green build.** `mvn -o test-compile` + the relevant test suite + `vitest` + `pytest` — actual exit codes pasted into the PR, not asserted.
4. **Supervisor sign-off required to merge** (Kavya QA + Meera build; Kabir for security-tagged; Priya tech).
5. **Money/auth changes require a human checkpoint** before commit, regardless of green tests.
6. **A status doc is not evidence.** Verify claims against the live tree before acting.
7. **Delete stale `claude/*` branches after recovery** — but only once their content is confirmed ported (see below).

### Branch cleanup (deferred — do NOT bulk-delete yet)
13 worktrees / ~15 `claude/*` branches exist. `bc5269` still holds material not yet ported (A4/A5/A6 references, D1 listeners, token-store H-30). **Recommend: keep `bc5269` until Wave 3/4 net-new is complete, then archive.** Deleting now would repeat the original mistake in reverse — destroying stranded work before recovering it.

### Sign-off
- [x] Priya (CTO) — process gate authored, C1 corrections verified against live trunk
- [ ] Swapnil (CEO) — required to make the gate binding

---
*Every correction above is backed by a file:line check against `integration/consolidate` on 2026-07-15. See `WAVE0-COVERAGE-DELTA.md` for the task-by-task open/closed map.*
