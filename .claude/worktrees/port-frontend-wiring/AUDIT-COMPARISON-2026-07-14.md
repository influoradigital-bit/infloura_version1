# Comparison — My Audit vs. `INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md`

**Prepared:** 14 Jul 2026. Both documents are source-only production-readiness audits of the same product. This note explains *why they disagree*, reconciles the overlapping findings, and — most importantly — flags where the older report will actively mislead you about the branch you are working on today.

---

## TL;DR

The two reports mostly **agree on the facts**. They differ because **they were run against different code**, and because the older report carries a *remediation layer* that was **never merged into the branch now on your disk**.

- **Older report** ran against `feature/analytics-platform` @ `a49db3f`, then a multi-agent "remediation pass" tagged **37 items `[FIXED]`** — but those fixes were made in a throwaway git **worktree** (`influora-prod-readiness-audit-bc5269`) that is now **`prunable`, i.e. abandoned and unmerged**.
- **My report** ran against your **current** working tree: branch **`feature/d14-invoicing` @ `26ea857`** (confirmed via `git worktree list`).
- **Net effect:** *some* of the older report's `[FIXED]` items really did land (they were merged earlier, before this branch forked); **several critical ones did not.** Anyone reading the older report's "37 FIXED · overall now much healthier" framing would over-estimate the current branch.

**My headline score was 60/100; theirs was 32/100. The truth is in between — closer to the mid-40s/low-50s** — and the reason for each number is explained below.

---

## 1. Why the numbers look so different

| | Older report | My report |
|---|---|---|
| Code audited | `feature/analytics-platform` a49db3f (+ unmerged worktree fixes) | `feature/d14-invoicing` 26ea857 (current disk) |
| Method | 8 specialist agents | 4 specialist agents + direct re-verification |
| Issues raised | 19 C / 32 H / 28 M / 17 L (**96**) | 6 C / 15 H / 22 M / 14 L (**57**) |
| Overall score | **32 / 100** | **60 / 100** |
| Single biggest score driver | "**Backend does not compile**" (their C-1) → *0% deployable* | Backend compiles → scored on integration gaps |

The 28-point score gap is almost entirely explained by one thing: **their #1 catastrophe (the six missing-symbol compile breaks, C-1) is genuinely fixed in your current branch.** I verified every one of the six symbols now exists:

- `Campaign.campaignType` → present (`Campaign.java:94`)
- `EscrowService.adminReleaseForDispute / adminRefundForDispute / adminSplitForDispute` → present (`EscrowService.java:472,520,572`)
- `User.softDelete()` / `getDeletedAt()` → present (`User.java:252,268`)
- `CreatorDeliverableService.markPosted()` → present (`:324`)

So a build should no longer fail on C-1. That's why I did not raise "doesn't compile," and why my deployability score is far higher. **Caveat their report is right about:** there is still no CI running `mvn test`, so "compiles" is inferred from symbol presence, not proven. That risk is real in both audits.

The breadth gap (96 vs 57) is the other half: their 8-agent pass dug deeper into backend business logic than my 4-agent pass. Where they went deeper and I was thinner, **their findings should be treated as still-live** unless independently fixed (see §4).

---

## 2. The important part — older `[FIXED]` items that are STILL BROKEN on your branch

I re-verified these directly on `feature/d14-invoicing`. Each is tagged `[FIXED]` in the older report but the fix is **not in your tree** (it lives in the abandoned worktree). These line up 1:1 with findings in my report:

| Older report (tag) | Claimed status | Reality on current branch | My report |
|---|---|---|---|
| **C-15** Creator auth is a mock | `[FIXED]` "→ real `api.auth.creatorLogin`" | **Still mock** — `creator-login.tsx:39` writes literal `'mock_creator_token'`; `creatorLogin` never called | **C1 (Critical)** |
| **C-8** SecurityConfig 401s webhooks/JWKS/portfolio | `[FIXED]` "permitAll added" | **Still broken** — only `POST /webhooks/razorpay` is `permitAll`; shopify/woo/conversion/redemption/`track/click`/`jwks.json` still fall to `authenticated()` | **H3 (High)** |
| **C-4** Escrow funding double-charges | `[FIXED]` "wallet-only under lock" | **Still present** — `initiateFund` requires wallet balance **and** mints a Razorpay order; `confirmFunded` still debits the wallet | **H12 (High)** |
| **C-17** TrendSpark + brand-safety AI dead | `[FIXED]` "config + `complete_text` + routers registered" | **Still dead** — `config.py` has no `TRENDSPARK_MODEL`; `claude.py` has no `complete_text`; `main.py` registers only 3 of 5 routers | **C5 (Critical)** |
| **C-6 / H-25** No AI spend gate on `/chat` | `[FIXED]` "gate + `record_spend` added to `/chat`" | **Still absent** — `chat.py` imports nothing from `app.costs`; only the two dead routes gate | **C6 (Critical)** |
| **H-20** Frontend money surfaces are hardcoded mocks | `[PARTIAL]` "dashboard KPIs fixed" | **Still mock** — brand campaigns list, both wallets, brand contracts, both deal rooms still render `mock*` arrays | **C3, C4, H4, H5, H6, H7** |
| **C-11** Notification/email facade; `@EnableAsync` missing | `[FIXED]` "`@EnableAsync` added" | **`@EnableAsync` still absent** in `src/main/java` (async listeners run synchronously) | (I flagged the money-surface half; agrees) |

**Bottom line for you:** do **not** treat the older report's `[FIXED]` / "37 fixed" tally as describing `feature/d14-invoicing`. The remediation worktree was never merged, so on this branch these are open Critical/High issues. My report reflects the branch as it actually is.

---

## 3. Older `[FIXED]` items that DID land (present in your current tree)

Not everything was lost — several fixes were merged before this branch forked, which is exactly why my audit didn't re-raise them:

- **C-1** six compile symbols — **present** (verified above). Their biggest finding is resolved.
- **C-9** `Deliverable` rows are now materialized — a `new Deliverable/builder()` site now exists (prior grep found 0).
- **C-14** multipart limits — `application.yml` now sets `max-file-size: 500MB` / `max-request-size: 1GB`.
- **C-7** subscription webhooks — `subscription.*` handling + `applySubscriptionWebhookUpdate` are now wired (referenced by `AdminBillingController`, jobs, `InvoiceService`).
- **C-16(a)** Meera transport — `meera-api.ts` now uses `fetch(...)` rather than the broken `EventSource` GET.
- **admin dispute escrow methods** — the `adminRelease/Refund/SplitForDispute` trio exists.

So the platform is meaningfully further along than the older report's 32/0% suggests — its money rails, deliverable pipeline, and subscription billing partially recovered.

---

## 4. Where the older report is deeper than mine (likely still true — worth keeping)

Their 8-agent pass surfaced backend business-logic breaks my 4-agent pass didn't chase. Several are tagged `[PARTIAL]`/untouched (so still open) and are **not compile-dependent**, meaning they most likely still stand on your branch. I'd fold these into the working defect list:

- **C-10** Collaboration lifecycle dead-ends at `TERMS_AGREED` → **reviews permanently impossible** (their own follow-ups #1/#2 confirm this was *deferred*, not fixed). High impact; my audit missed it.
- **C-11/C-12/C-13** Notification + transactional-email + password-reset + MSG91 config-prefix facade — I only caught the notification-bell wiring (my H15); they mapped the whole email outage.
- **C-3** Wallet top-up ledger entry point — their remediation claims a clearing-wallet exemption; I did **not** independently confirm the exemption code, though my backend pass saw top-up working, so this is *plausibly* resolved. Needs a live money-path test to settle.
- **H-9** Instagram Graph called with the internal ULID as the IG account id; **H-10** discovery ranking has no production writer; **H-11** brand can record the creator's signature; **H-12** contract terms never stored. None are in my report.
- Several I *did* independently corroborate: distributed job locking (their H-13 = my M3), spoofable XFF (H-4 = M8), no-op malware scan (H-18 = M9), payout webhook no-op/reversal (C-6 = my M1), admin revenue hardcoded ₹0 (M-28 = my M2), orphan `payouts` table (L-4 = my M10), fail-open secret validation (H-1 = my H1/H2), `[v0]` console logs (H-27 = my L3), Next.js dead carcass (L-17 = my L9).

The overlap is high enough that the two audits **cross-validate** each other on ~20 distinct issues.

---

## 5. Where my report adds something theirs couldn't

- **It describes the branch you're actually on.** Their code state is two branches back plus an unmerged worktree.
- **The D14 invoicing feature is new on this branch** (`feat(invoicing): D14 invoice UI … tax identity form`). I flagged that `creatorTaxIdentity.submit` still rejects `NOT_IMPLEMENTED` (my M18) — i.e. the invoicing UI this branch adds is **still not backed** end-to-end. The older report predates this feature entirely.
- **Re-verification on current HEAD**: every headline claim in my report was re-grepped against `feature/d14-invoicing`, so it's an accurate "as-of-today" snapshot rather than an as-of-`a49db3f` one.

---

## 6. On the committed secrets (reconciling their C-2 with my finding)

Their C-2 said real keys were committed and tagged it `[FIXED]` ("no real secret committed; gitignore hardened"). On your current tree both things are simultaneously true:

- `influora-ai/.env` **is** now git-ignored (`git check-ignore` confirms) → **not in git history from here forward** ✓ matches their fix.
- **But the real keys are still physically in the working file on disk**: `ANTHROPIC_API_KEY=sk-ant-api03…`, `GEMINI_API_KEY=AIzaSyC8m_tZ…`, `SARVAM_API_KEY=sk_r0vltyzk_…`.

So the git-leak is closed, but the keys are live secrets sitting in plaintext on disk. **Both reports converge on the same action: rotate all three keys** (they were exposed in earlier history and are readable to anyone with the folder).

---

## 7. Reconciled scorecard (my best estimate of the *current* branch)

| Metric | Older (a49db3f) | Mine (d14) | Reconciled current-state view |
|---|---|---|---|
| Overall health | 32 | 60 | **~48** — compiles (↑ from 32) but deep money/notification/lifecycle breaks from the unmerged worktree persist (↓ from 60) |
| Backend | 45 / 0% deployable | 80 | ~62 — builds now; lifecycle (C-10) + email facade still open |
| Frontend | 55 | 55 (wired) | ~55 — both agree: shell good, transaction surfaces mock |
| AI integration | 40 | 55 | ~45 — chat/site real; trendspark + brand-safety still dead on this branch |
| Database | 62 | 90 | ~78 — schema sound; drift fixes merged; one orphan table |
| Security | 52 | 70 | ~60 — strong primitives; env-gate + webhooks + committed keys still open |
| Production readiness | 18 | 42 | ~35 — no CI/`mvn test`, unmerged fixes, money path unproven E2E |

---

## 8. What I'd do with both documents

1. **Trust my report for *what is broken on `feature/d14-invoicing` today*** — it's the current branch and was re-verified on HEAD.
2. **Mine the older report for *depth*** — its C-10, C-11/12/13, H-9/10/11/12 findings are real work items my pass didn't chase, and most aren't fixed on this branch.
3. **Distrust the older report's `[FIXED]` tags** unless a specific item also appears resolved in §3. The remediation worktree is `prunable`/unmerged; re-confirm each `[FIXED]` against HEAD before believing it (the seven in §2 are confirmed *not* fixed here).
4. **Highest-leverage next step (both reports agree):** wire a CI job that runs `mvn test` + `pytest` + `vitest` + `tsc` on this branch, then either merge or rebuild the lost remediation for the §2 items — creator auth, webhook/JWKS permitAll, escrow funding model, the two dead AI routes, the AI spend gate, and the frontend money surfaces.

---
*Both audits are source-only; this comparison was verified against `feature/d14-invoicing @ 26ea857` on disk. Where I could not re-confirm an older-report claim on the current tree (e.g. C-3's clearing-wallet exemption), I said so rather than assume.*
