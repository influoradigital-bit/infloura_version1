# CEO → CTO — answers to questions 35–50 (sections D and E)

**From:** Priya (CTO) · **To:** Swapnil Maruti (CEO) · **Date:** 2026-09-08
**Scope:** questions 35–50 only. Questions 1–34 are another reader's.

Every `file:line` below was opened by me in this pass. Where a javadoc or a wiki
document contradicts the code, I say so and cite the line.

Paths are relative to the repository root
`C:\Users\Sage world\Downloads\New Influora Ai\New Influora`.

---

## D. Affiliate — does a creator actually get paid? (35–46)

### 35. From "a sale is attributed" to "money in a creator's wallet" — where does the chain stop?

**Verdict: WIRED up to the wallet posting, then it stops dead. In effect the money leg is ABSENT.**

The chain, each hop opened:

1. A signed store delivery resolves a workspace and calls redeem —
   `influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java` (and the Woo twin) →
   `RedemptionService.redeem` → `RedemptionWriter.doRedeem`.
2. `RedemptionWriter.doRedeem` saves the `CouponRedemption`, increments coupon usage, writes the
   `COUPON_REDEEMED` money-audit row, and **publishes** an event —
   `influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:109`, `:113`,
   `:132`, `:174`.
3. `AffiliateEarningRecordingListener.onCouponRedeemed` fires at `AFTER_COMMIT` and calls
   `affiliateEarningsService.recordEarning(...)` —
   `influora-api/src/main/java/com/influora/service/tracking/AffiliateEarningRecordingListener.java:93`
   (the `@TransactionalEventListener(AFTER_COMMIT)`), `:108` (the call). This creates an
   `AffiliateEarning` row in status **PENDING**, at a 10% flat default rate unless the campaign
   overrides it — `influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java:102`.
4. The monthly `AffiliateSettlementJob` sweeps every creator with `PENDING`/`FAILED` earnings —
   `influora-api/src/main/java/com/influora/job/AffiliateSettlementJob.java:106` (settleable
   statuses), `:131` (`@Scheduled(cron = "0 0 5 1 * *")`), `:167` (the sweep), `:245` (the call into
   the writer).
5. `AffiliateSettlementWriter.doSettleCreator` marks each earning `SETTLED` and then calls
   `creditCreatorWallet` — `influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java:76-82`.
6. `creditCreatorWallet` posts `platform clearing wallet (DEBIT) → creator wallet (CREDIT)` with
   `WalletTransactionType.AFFILIATE_COMMISSION` / `TxnReferenceType.AFFILIATE_EARNING` —
   `influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java:130-142`.

**Step 6 cannot execute against the real database.** `wallet_transactions.type` is a MySQL `ENUM`
that does not contain `AFFILIATE_COMMISSION`, and `reference_type` does not contain
`AFFILIATE_EARNING` — `influora-api/src/main/resources/db/migration/V8__wallet_transactions.sql:6-7`
and `:12`. I grepped every file under `db/migration/` for both tokens: **zero hits**. Under MySQL
strict mode the insert raises 1265, the `@Transactional` write on `doSettleCreator`
(`AffiliateSettlementWriter.java:75`) rolls back, and `AffiliateSettlementJob`'s per-creator
defensive catch (`AffiliateSettlementJob.java:178-187`) swallows it into a log line and moves on.

So the chain stops at the ledger insert. Everything upstream — attribution, the earning row, the
creator-facing earnings page — works and shows numbers. Nothing downstream ever happens.

**Doc that lies:** `AffiliateSettlementJob.java:30-33` states settlement credits "the settled amount
to the creator's Influora wallet ... the same `WalletLedgerService` double-entry path". That is the
intent, not the behaviour. The code wins: the column definition at `V8__wallet_transactions.sql:6`
makes that posting impossible.

---

### 36. Has any creator ever been credited affiliate commission in production?

**No.** Verdict: **ABSENT.**

Proof from the code, not from a database I cannot reach:

- The *only* write of an affiliate commission to `wallet_transactions` in the entire backend is
  `AffiliateSettlementWriter.java:132-142`. Grepping all of `influora-api/src` for
  `AFFILIATE_COMMISSION` returns exactly two hits: the enum declaration
  (`influora-api/src/main/java/com/influora/domain/enums/WalletTransactionType.java:16`) and that
  one ledger post.
- That post is unexecutable because the column will not accept the value —
  `V8__wallet_transactions.sql:6-7`. No migration widens it.
- There is no older or alternate path. `AffiliateEarningReconciliationJob` only *creates* earning
  rows; it never credits a wallet.

So a creator's `AffiliateEarning` rows may exist and render at `GET /creator/affiliate-earnings`
(`influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java:182`), but no rupee
has ever moved. What that page shows is a promise, not a balance.

---

### 37. Settlement schedule, where configured, when it next runs.

`@Scheduled(cron = "0 0 5 1 * *")` on `runMonthlySettlement()` —
`influora-api/src/main/java/com/influora/job/AffiliateSettlementJob.java:131`. Hard-coded in the
annotation; **not** externalised to `application.yml`, so it cannot be retimed or disabled without a
redeploy. Wrapped in `@SchedulerLock(name = "AffiliateSettlementJob", ...)` at `:132` so only one
node runs it.

05:00 UTC on the 1st of each month; the period settled is the **previous** calendar month —
`AffiliateSettlementJob.java:136` (`Instant.now()...minusMonths(1)`).

Next run from today (2026-09-08): **2026-10-01 05:00 UTC**, settling period `2026-09`.

Verdict: **WIRED** — the trigger is real and will fire; the run itself is a no-op that logs errors
(Q41).

---

### 38. When a commission settles, which wallet is DEBITED, and whose money is in it?

The **platform clearing wallet** — `AffiliateSettlementWriter.java:130`
(`platformWalletService.requireClearingWallet()`), passed as the debit leg at `:133`.

Whose money: **every brand's escrowed funds.** Its own class javadoc says so —
`influora-api/src/main/java/com/influora/service/PlatformWalletService.java:12-15`: "the 'other
side' of a brand's escrow hold (brand wallet DEBIT -> platform clearing wallet CREDIT)". It is
explicitly **not** platform revenue; that is a separate wallet,
`PLATFORM_REVENUE_WALLET_OWNER_ID`, `PlatformWalletService.java:31`.

Worse: the clearing wallet is the single account **exempted from the non-negative balance check** —
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:131-139`. An affiliate
commission debit against it therefore never fails for want of funds; it just drives the pool that
owes every brand their escrow balance further negative, silently.

---

### 39. Is the brand that owes the commission debited anywhere in the flow?

**No. Confirmed absence.** Verdict: **ABSENT.**

`doSettleCreator` (`AffiliateSettlementWriter.java:76-82`) does exactly two things per earning:
`earning.markSettled(...)` + `save`, then `creditCreatorWallet(earning)`. `creditCreatorWallet`
(`:114-143`) makes exactly one `walletLedgerService.post(...)` call and its debit leg is the
platform clearing wallet (`:133`), never a workspace wallet. The only `walletService` use in the
file is `requireOrCreateUserWallet(earning.getCreatorId())` at `:131` — the creator side. The
constructor takes `AffiliateEarningRepository, WalletLedgerService, WalletService,
PlatformWalletService` (`:58-62`); there is no workspace-wallet resolution anywhere in it.

No brand-side posting exists elsewhere either: `AFFILIATE_COMMISSION` occurs in only two files in
`influora-api/src` (Q36), and neither is a brand debit.

Net effect if the enum were widened tomorrow: the creator is credited, the brands' omnibus escrow
pool is debited, and the brand who ran the campaign pays nothing. That is commission funded out of
other customers' escrowed money.

---

### 40. `wallet_transactions.type` is an ENUM. Does it contain `AFFILIATE_COMMISSION`? Does any migration add it?

**No, and no.** Verdict: **ABSENT.**

```
type  ENUM('DEPOSIT','WITHDRAWAL','ESCROW_HOLD','ESCROW_RELEASE',
           'ESCROW_REFUND','PLATFORM_FEE','PAYOUT','ADJUSTMENT') NOT NULL,
```
— `influora-api/src/main/resources/db/migration/V8__wallet_transactions.sql:6-7`

`reference_type` is equally short of `AFFILIATE_EARNING` —
`V8__wallet_transactions.sql:12`.

I grepped the entire `influora-api/src/main/resources/db/migration/` tree for both
`AFFILIATE_COMMISSION` and `AFFILIATE_EARNING`. **Zero hits.** No `ALTER TABLE ... MODIFY type` on
`wallet_transactions` exists anywhere in the migration set (the only other files mentioning
`wallet_transactions` are `V9__escrow_holds.sql`, `V10__contracts_and_milestones.sql` and
`V20260709155921__wallet_topups.sql`, all of which only add foreign keys pointing at it).

Meanwhile the Java enums *do* carry both values —
`influora-api/src/main/java/com/influora/domain/enums/WalletTransactionType.java:16` and
`influora-api/src/main/java/com/influora/domain/enums/TxnReferenceType.java:11` — and
`WalletTransaction` maps both with `@Enumerated(EnumType.STRING)`
(`influora-api/src/main/java/com/influora/domain/entity/WalletTransaction.java:34-36` and `:51-53`).

Note for the "surely a gate catches this" instinct: `spring.jpa.hibernate.ddl-auto: validate`
(`influora-api/src/main/resources/application.yml:47`) does **not** catch it. Hibernate's schema
validation checks column *types*, and JDBC reports a MySQL `ENUM` as `CHAR`; it never compares the
permitted value set. The application boots clean with this defect in place.

---

### 41. What happens when the settlement job runs, and what has it been doing every month since it shipped?

Every month, at 05:00 UTC on the 1st, this sequence:

1. A new `affiliate_settlement_batches` row is created —
   `influora-api/src/main/java/com/influora/job/AffiliateSettlementJob.java:163-165`.
2. Every creator holding a `PENDING`/`FAILED` earning is swept — `:167`.
3. For each, `AffiliateSettlementWriter.doSettleCreator` marks the earnings `SETTLED`
   (`AffiliateSettlementWriter.java:78-79`) and then calls `creditCreatorWallet` (`:80`).
4. `creditCreatorWallet` posts through `WalletLedgerService.post(...)` with
   `type = AFFILIATE_COMMISSION` (`:132-142`). The INSERT into `wallet_transactions` carries a value
   the column's ENUM does not permit. Under MySQL 8.0's default `sql_mode`
   (`STRICT_TRANS_TABLES` — I checked every compose file: `docker-compose.yml:4`,
   `deploy/hostinger/docker-compose.hostinger.yml:64`,
   `deploy/utho/docker-compose.utho-shared.yml:96`; **none** overrides `sql_mode`) that raises
   error 1265 and the statement fails.
5. `doSettleCreator` is `@Transactional` (`AffiliateSettlementWriter.java:75`) and is genuinely
   proxied, so the whole unit rolls back — including the `markSettled` from step 3. The earnings
   revert to `PENDING`.
6. The exception propagates out of `settleOneCreator` (its only catches are
   `AlreadyCompletedException` / `AlreadyInProgressException` —
   `AffiliateSettlementJob.java:248` and `:252`), is caught by the per-creator catch-all at `:178`,
   incremented into `failedCount`, and logged at ERROR (`:182`).
7. Because `failedCount > 0`, the batch is marked `FAILED` (`:192`) and the audit event
   `AFFILIATE_SETTLEMENT_BATCH_COMPLETED` is written with outcome `FAILED` (`:198-211`).

**So every month since this shipped, the job has: created a batch, tried every creator, failed
every creator, marked the batch FAILED, and written one audit row.** No money moved; no earning
changed state. The `PENDING` backlog has been growing monotonically the entire time.

Verdict: **WIRED but inert.** The scheduler works. The settlement does not.

---

### 42. Do the two defects cancel out? What happens if someone "fixes" only the enum?

**Yes, today they cancel — by accident, not by design.** F-0738 (nobody funds the commission) is
harmless only because F-0739 (the enum was never migrated) makes the posting unexecutable. That is
the *entire* reason no money has drained.

If someone widens the enum alone — one `ALTER TABLE wallet_transactions MODIFY type ENUM(..., 'AFFILIATE_COMMISSION')`
plus the matching `reference_type` widening — here is precisely what happens on the next 1st of the
month:

1. `creditCreatorWallet` now succeeds. Debit leg = platform clearing wallet
   (`AffiliateSettlementWriter.java:130`, `:133`).
2. The clearing wallet is the **omnibus account holding every brand's escrowed funds**
   (`influora-api/src/main/java/com/influora/service/PlatformWalletService.java:12-15`) and is the
   one wallet **exempted from the insufficient-balance check**
   (`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:131-139`). So the
   debit cannot fail, and the pool goes negative against its own liabilities without a single error.
3. Nothing debits the brand. There is no brand-side leg anywhere (Q39).
4. **And it is not a trickle — it is the whole backlog at once.** `settleOneCreator` loads
   `affiliateEarningRepository.findByCreatorIdAndStatusIn(creatorId, SETTLEABLE_STATUSES)` —
   `AffiliateSettlementJob.java:227-228`. That query is **not filtered by period**. The
   `periodYearMonth` argument appears only in the idempotency key
   (`:276-278`), never in the query. So the first successful run settles *every*
   `PENDING`/`FAILED` earning ever recorded, in one transaction per creator, in one batch.

The clean-looking one-line migration therefore converts a dormant accounting hole into a lump-sum
withdrawal of the entire historical commission backlog from the account that owes every brand
their escrow balance. It is the single most dangerous one-line change available in this repository
right now.

---

### 43. Is there a gate preventing exactly that? Would it actually fire?

Yes, there is one: `.proof-os/gates/affiliate-commission-funded-before-enabled.sh`. I read it in
full. Its intent is exactly right and its analysis (lines 6–36) matches what I independently found.
**It would fire on the naive migration.** But it is a grep gate, and it is breakable four ways.

How it works: `ENUM_WIDENED` = does `grep -rqE "AFFILIATE_COMMISSION|AFFILIATE_EARNING"` hit
anything under `db/migration/` (`:55`). `FUNDING_LEG` = does `grep -q "requireWorkspaceWallet"` hit
`AffiliateSettlementWriter.java` (`:64`). Widened-without-funding ⇒ exit 1 (`:70-77`).

**Would it fire on the exact hazard it names?** Yes. A migration file containing the literal
`AFFILIATE_COMMISSION` with no brand-side leg trips `:70` and exits 1. Good.

**Four realistic ways it reports PROVED over the broken behaviour:**

1. **A comment is enough to "fund" the commission.** `:64` is a bare
   `grep -q "requireWorkspaceWallet"` on the file. Adding
   `// TODO: fund this via requireWorkspaceWallet` to `AffiliateSettlementWriter.java` sets
   `FUNDING_LEG=1`, and the gate falls through to `:89-91` and prints *"PROVED: the affiliate
   settlement path debits the owing brand workspace before crediting the creator"*. It has already
   happened twice in this repository that a grep gate matched its own explanatory comment. This one
   is worse: the false positive is a green "money is funded" claim.
2. **A real-looking but wrong funding leg passes unchecked.** Even with a genuine
   `requireWorkspaceWallet` call, the gate never checks the *amount*, the *workspace*, the
   *direction*, whether the debit is in the same transaction as the credit, or whether it is inside
   an `if (false)`. `walletService.requireWorkspaceWallet(x)` on a line by itself, result discarded,
   is indistinguishable from a correct posting to this gate.
3. **The enum can be widened outside `db/migration/`.** The gate only looks at that directory
   (`:42`, `:55`). The live database on the Utho box is reachable directly; a one-off ops
   `ALTER TABLE` run against production widens the column with no repository change at all. The
   gate then reports the `ENUM_WIDENED=0 / FUNDING_LEG=0` branch — *"PROVED: the drain is closed —
   wallet_transactions cannot record an affiliate commission"* (`:79-86`) — while the drain is in
   fact wide open. The gate reads the repo; the money lives in the database.
4. **A permissive `sql_mode` opens the drain with the schema untouched.** The gate's whole premise
   is that the insert *errors*. That is true only under `STRICT_TRANS_TABLES`. If `sql_mode` is
   ever loosened (a tuning change, a managed-MySQL migration, a restored dump with a different
   server config), MySQL inserts the empty string `''` for the invalid ENUM value and only raises a
   warning. The commission then posts successfully, unfunded, with `type=''` — and the gate,
   which never looks at `sql_mode` and never runs a settlement, still exits 0 with "the drain is
   closed". Nothing in the repository pins `sql_mode`: I checked `docker-compose.yml`,
   `deploy/hostinger/docker-compose.hostinger.yml`, `deploy/hostinger/docker-compose.test.yml` and
   `deploy/utho/docker-compose.utho-shared.yml` — none sets it.

Verdict on the gate: **useful, and I want it kept**, but it is a static text check standing in for a
money invariant. It proves a string is absent from a directory. It does not prove the ledger cannot
move unfunded money.

---

### 44. Could a brand fabricate orders into its own workspace? What would it cost them, and us?

**Yes.** Verdict: **WORKS (as an attack) on WooCommerce; WORKS-with-friction on Shopify.**

**WooCommerce — trivially.** The per-site webhook secret is a value the *brand supplies at connect
time*; the platform stores and decrypts it
(`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:224`) and verifies
the delivery against it (`:225`). A brand that knows its own secret can hand-craft any payload,
sign it correctly, set `X-WC-Webhook-Source` to its own site (`:195`, resolved to the workspace at
`:213-222`) and POST it directly. No order need exist. Cost to the brand: nothing — no Shopify
order, no WooCommerce order, no HTTP call to their own store.

**Shopify — bounded by an Admin API check.** Since 2026-09-07,
`ShopifyOrderOwnershipVerifier.orderBelongsToShop` does a real
`GET https://{shop}/admin/api/{v}/orders/{id}.json?fields=id` with the stored access token —
`influora-api/src/main/java/com/influora/integration/shopify/ShopifyOrderOwnershipVerifier.java:62-79`.
That forces the order to actually exist in the brand's own store. But the brand owns that store: a
zero-value draft order carrying the creator's coupon, created and completed in their own admin,
satisfies the check. The `orderAmount` that drives the commission comes from the payload, and
`RedemptionWriter.doRedeem` only rejects a *negative* amount
(`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:85-88`) — it never
compares the claimed amount against what the Admin API says the order was worth. So the verifier
proves the order *exists*; it does not prove what it was *for*.

**What it costs us.** Today: nothing, because the commission never posts (Q35/Q41). The moment the
enum is widened: the fabricated commission is credited to the creator wallet out of the omnibus
escrow pool (Q38/Q42), and — see Q45 — that balance is self-serve withdrawable to a real bank
account. The attack is not "a brand inflates its own analytics"; it is a brand and a cooperating
creator minting real money out of other brands' escrowed funds. The gate's own text names this
(`.proof-os/gates/affiliate-commission-funded-before-enabled.sh:74`, "a fabricated redemption
(F-0727) mints real money") and I agree with it.

---

### 45. Is a payout from a creator wallet automatic or gated? What stands between a fabricated commission and real money?

**Self-serve, not human-gated.** Verdict: **WIRED** (the RazorpayX call is real code; whether it is
provisioned in production is a separate, already-known blocker).

`POST /wallet/withdraw` — `influora-api/src/main/java/com/influora/web/WalletController.java:116-117`
→ `WalletService`. The checks it enforces, all automatic:

- An `Idempotency-Key` header — `influora-api/src/main/java/com/influora/service/WalletService.java:255-258`.
- Sufficient balance under a pessimistic row lock — `WalletService.java:262-278`.
- At most 3 withdrawals per calendar day — `WalletService.java:69`, enforced at `:282-288`.
- Minimum ₹500, maximum ₹1,00,000 — `WalletService.java:66`, `:68`, enforced at `:580-590`.
- A primary bank/UPI account on file — `WalletService.java:290-299` (`BANK_ACCOUNT_NOT_FOUND`).
- Identity KYC not `UNVERIFIED`/`REJECTED` — `WalletService.java:360-368` (`IDENTITY_KYC_REQUIRED`).

Then `doProcessWithdrawal` debits the creator wallet through the ledger and calls
`razorpayXClient.initiatePayout(...)` — `WalletService.java:373-400`.

**Nothing else.** There is no admin approval step, no hold period, no anomaly check, and — this is
the part that matters here — **no distinction by source of funds**. A wallet balance that arrived as
`AFFILIATE_COMMISSION` is indistinguishable at withdrawal time from one that arrived as
`ESCROW_RELEASE`. So the complete list of what stands between a fabricated commission and real money
leaving is: (a) the un-migrated enum, (b) waiting until the 1st of the month, (c) completing KYC,
(d) adding a bank account, (e) a ₹500 floor and 3-per-day cap. Only (a) is load-bearing, and (a) is
an accident.

---

### 46. Does any document define who funds affiliate commission?

**No. Confirmed gap.** Verdict: **ABSENT.**

The one document on this subject is
`wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md`. I read all 46 lines. It is
CFO-signed (`:46`, "Sign-off: Rohan · 2026-07-12") and it specifies the **rate** exhaustively —
the column (`:12-17`), the default/override rule (`:21-23`), a 0–30% cap (`:24`), who may set it
(`:26-29`), the audit metadata (`:35`), seven acceptance criteria (`:37-44`). It is **completely
silent on the funding leg**. The word "escrow" appears once, at `:24`, and only as a rate
comparison to the 15% brand-side platform fee. Nothing says whose balance the money comes out of.

Two further things worth your attention:

- The doc's own basis citation is **a file that does not exist**. Line 4 says it builds on
  `wiki/decisions/budget-proposals/2026-07-07-affiliate-commission-rate-and-settlement-cost-review.md`.
  I listed `wiki/decisions/budget-proposals/` — it contains only
  `2026-07-12-ai-spend-ceiling-and-killswitch.md` and `razorpay-escrow-2026-07-25.md`. A repo-wide
  `find` for any path matching `*affiliate*` returns no such file. So the funding question was not
  answered in a prior document either; the chain of authority terminates in a citation to nothing.
- The user-facing feature doc `docs/docs/features/affiliate-coupons.md:43` lists "wallet
  (money-event recording)" as a dependency — *recording*, not funding. Even the documentation
  describes this as bookkeeping.

The gate's claim that this is a Rohan/Swapnil ruling and not an engineering default
(`.proof-os/gates/affiliate-commission-funded-before-enabled.sh:25-29`) is the one assertion in this
whole area I can confirm is both true and correctly scoped.

---

## E. Adversarial — break what was built on 2026-09-08 (47–50)

### 47. Five gates under `.proof-os/gates/`. For each, one realistic change that breaks the guarded thing while the gate still reports PROVED.

First, a correction to the premise: **there are six, not five.** Sorted by mtime, the gates written in
this session are `shopify-connect-is-completable.sh` (14:35), `coupon-lookup-workspace-scoped.sh`
(14:02), `shopify-authorize-requires-config.sh` (13:50) on 2026-09-08, plus
`affiliate-commission-funded-before-enabled.sh` (19:33), `shopify-webhook-shop-binding.sh` (19:11)
and `store-webhook-terminal-outcome-acked.sh` (18:52) carried in on 2026-09-07 by commit `f3a30c5`.
The last three are dated the 7th but belong to this work. I read all six in full.

The structural pattern across all of them: each pairs a `grep` on a source file with a `mvn test`
run. The `mvn test` half is real. **The `grep` half is decorative in at least three of them**, and
in each case the gate's own header claims the grep is what makes call-site deletion visible.

---

**(1) `shopify-authorize-requires-config.sh` — the structural check is already dead.**

Line 70: `if ! grep -q "isConfigured" "$CONTROLLER"`. The gate's header at `:19-23` claims this
"makes deletion of the CALL SITE red on its own."

**It does not, today.** `ShopifyConnectController.java:83` is a *comment* — "…no generate-env.sh
entry (F-0729) — so `isConfigured()` is false in every environment today". Delete the actual guard
at `ShopifyConnectController.java:94-99` and `grep -q "isConfigured"` still matches line 83. The
structural half goes green over a deleted guard. This repository has shipped a grep gate that
matched its own explanatory comment twice before; this is the third, and this time the comment
collision is *already present at the moment the gate was written*.

Realistic breaking change: **move the guard from `/authorize` to `/callback`.** The grep passes
(the word is still in the file), and only `ShopifyConnectControllerTest` stands between that and
green. Note that `/callback` has **no** `isConfigured` check at all today
(`ShopifyConnectController.java:112-113` — the mapping and signature, with no guard between them
and the body), which is exactly what the gate's `:26-29` rationale asserts is unnecessary.

---

**(2) `shopify-connect-is-completable.sh` — the delivery address is never checked.**

The gate proves two things: the connect controller calls `registerOrderWebhooks` (`:67`), and the
`redirect-uri` default path equals the `App.tsx` route path (`:79-98`). I ran its extraction by hand
against the real files: both sides yield `/brand/settings/shopify/callback`
(`influora-api/src/main/resources/application.yml:469`, `src/App.tsx:394`). That half works.

**What it never looks at is the webhook delivery address**, which is the whole point of F-0730.
`ShopifyWebhookRegistrar.deliveryAddress()` builds it as `influora.api.public-url` +
`"/webhooks/shopify"` — `ShopifyWebhookRegistrar.java:27`, `:56-70`. There is no context path
appended. It is correct in production only because every deploy file happens to bake the context
path into the variable: `INFLUORA_API_PUBLIC_URL: https://${API_DOMAIN}/api/v1`
(`deploy/utho/docker-compose.utho-shared.yml:151`,
`deploy/hostinger/docker-compose.hostinger.yml:116`).

That is not what the variable is documented to mean. `application.yml:134-136` says it is the
"Public origin of THIS API" and that consumers append `server.servlet.context-path` themselves —
and one consumer does exactly that: `Msg91EmailClient.java:213` returns
`apiPublicUrl + contextPath + "/notifications/unsubscribe-link"`, which against the deployed value
produces a **doubled** `/api/v1/api/v1/…`. So the two consumers of this key disagree and one of
them is broken right now.

Realistic breaking change: **someone fixes the unsubscribe link by setting
`INFLUORA_API_PUBLIC_URL` to the bare origin.** Shopify's delivery address silently becomes
`https://api.influora.in/webhooks/shopify` — a 404 on every order, forever. The gate still reports
PROVED: it checks a redirect path in a yaml default and a call site in a controller, and touches
neither the registrar nor the deploy files.

Second break, same gate: the redirect-uri comparison reads only the **default** half of
`${INFLUORA_SHOPIFY_REDIRECTURI:${influora.web-base-url}/…}` (`:79`). Set that env var in
production to any other path and Shopify redirects merchants somewhere the SPA does not serve,
while the gate compares two strings neither of which is the one in effect.

---

**(3) `coupon-lookup-workspace-scoped.sh` — the banned pattern is one character from useless.**

Line 77 bans `\.findByCode(` outside `PlanRepository`/`PlanService`, and its header (`:19-24`)
argues carefully for the dot-and-paren precision. The precision cuts both ways.

Realistic breaking change: **reintroduce the global finder under any other name.** Add
`findByCodeIgnoreCase(String code)` returning `Optional<CouponCode>` to `CouponCodeRepository` and
call it from `RedemptionWriter`. `\.findByCode(` does not match `.findByCodeIgnoreCase(` — the paren
is in the wrong place. The gate's second check (`:85`, `grep -q "findByWorkspaceIdAndCode"` in
`RedemptionWriter.java`) would still pass so long as the scoped finder remains called *anywhere* in
that file for any reason — and it is a bare substring match, so it would equally be satisfied by a
comment. Today that string occurs exactly once, at `service/tracking/RedemptionWriter.java:201`, the
real call — so this half is honest right now, but it is one explanatory comment away from the same
collision that has already killed two gates in this repo, and the F-0728 javadoc immediately above
it (`:184-198`) is exactly where someone would write that comment. Both structural checks green;
shadowing restored.

---

**(4) `shopify-webhook-shop-binding.sh` — a permanent condition is asserted to be transient.**

The grep half (`:78`, `orderBelongsToShop` in `ShopifyWebhookController.java`) currently has no
comment collision — I checked all five occurrences in that file (lines 6, 58, 107, 114, 188) and
none is a comment naming the method. It is safe by luck; one explanatory comment breaks it.

The real problem is what the gate **pins as correct**. Its
`receive_ownershipCheckTransientFailure_surfacesNon2xx` test (`:27-30`) asserts that an unhappy
Admin API must surface as non-2xx. `ShopifyOrderOwnershipVerifier.java:138-152` implements that:
**only** a 404 is a rejection; "Includes 401/403 (token revoked shop-side) and every 5xx" throws
`ShopifyApiException` → non-2xx.

A shop-side token revocation is not transient. It is permanent until the merchant reinstalls. Every
subsequent order carrying a coupon returns non-2xx, Shopify retries, and after ~48h Shopify removes
the subscription — **the exact F-0725 failure mode the sibling gate exists to prevent.** So a
realistic change is not even needed: the guarded behaviour is already capable of self-disconnecting
a store, and this gate asserts that it should. Both gates report PROVED simultaneously.

---

**(5) `store-webhook-terminal-outcome-acked.sh` — the terminal set is a closed list of four, and the
split is applied to only part of the handler.**

`StoreWebhookRedemptionOutcome.java:8-9` hardcodes `INVALID_CODE, CODE_EXPIRED,
CODE_LIMIT_REACHED, UNSUPPORTED_DISCOUNT_TYPE`. Anything else is treated as transient and retried.

Realistic breaking change: **add a fifth business rejection to redemption.** Someone adds a
per-customer usage cap (`RedemptionWriter`'s own javadoc at `:92-94` flags that as a known gap:
"Per-user limit: NOT implemented") throwing `PER_USER_LIMIT_REACHED`. That is as body-determined and
as permanent as `CODE_LIMIT_REACHED`, but it is not in the set, so every such order retries to
disconnection. The gate is green: `StoreWebhookRedemptionOutcome.java` exists, and both suites pass
because neither knows the new code exists.

And one that is already true: `ORDER_AMOUNT_INVALID`
(`service/tracking/RedemptionWriter.java:85-88`) is reachable from the store webhook path, is purely
a function of the order body, and is **not** in `TERMINAL_CODES`. It retries forever today.

Separately, the split only wraps the `executeOnce` call —
`web/ShopifyWebhookController.java:198-243`. Four permanent conditions are thrown **before** that
try and keep their non-2xx unconditionally: `INVALID_WEBHOOK_SIGNATURE` (`:139`),
`MISSING_SHOP_DOMAIN` (`:144`), `SHOP_NOT_CONNECTED` (`:151-158`), and `SHOP_ORDER_MISMATCH`
(`:190-194`) — plus `INVALID_WEBHOOK_PAYLOAD` and `SHOPIFY_NOT_CONNECTED` raised inside the verifier
(`ShopifyOrderOwnershipVerifier.java:102-105`, `:107-115`). A brand who disconnects in our UI while
the subscription still exists on their store gets `SHOP_NOT_CONNECTED` 404 on every order until
Shopify gives up. The gate's parameterized test covers four codes; it does not cover the six paths
that never reach the classifier at all.

---

**(6) `affiliate-commission-funded-before-enabled.sh`** — answered in full at Q43. Summary of its
four breaks: a comment containing `requireWorkspaceWallet` flips it to "PROVED: funded"; a
syntactically present but semantically wrong funding leg passes; an `ALTER TABLE` run directly
against the production database bypasses the `db/migration/` grep entirely; and a permissive
`sql_mode` opens the drain with the schema untouched.

---

### 48. `assertThrows` → HTTP 200 on the cross-tenant tests: genuine fix or weakened test?

**Genuine fix. I would have made the same change.** But the test is weaker than it needs to be in
one specific, fixable way, and I want that closed.

What the test still asserts, read at
`influora-api/src/test/java/com/influora/web/ShopifyWebhookControllerTest.java:317-380` (the
WooCommerce twin is at `WooCommerceWebhookControllerTest.java:341`):

- `verify(redemptionService, times(1)).redeem(eq(WORKSPACE_ID), eq("BRAND_B_SUMMER25"), …)` —
  `:370-377`. The controller passed **its own** resolved workspace, not the coupon's.
- `verify(redemptionService, never()).redeem(eq(OTHER_WORKSPACE_ID), …)` — `:378-379`.
- `RedemptionService` is stubbed to throw `INVALID_CODE` for that pairing (`:337-344`), i.e. it is
  told to behave the way the real, F-0728-scoped service behaves.

The security property was never the status code. The cross-tenant coupon is rejected **inside**
`RedemptionService` by the workspace-scoped query (`RedemptionWriter.java:199-203`); the usage
increment (`RedemptionWriter.java:113`) and the commission event (`:174`) are downstream of a throw
and cannot run. The status code the attacker receives is irrelevant to whether Brand B's coupon was
touched — and returning 200 is, if anything, *better* for the no-enumeration property this codebase
deliberately holds (`RedemptionWriter.java:195-197`: a foreign code reports `INVALID_CODE`,
identical to "no such code"). Meanwhile the old non-2xx had a cost the old test could not see: it
was the loudest failure that disconnected the store.

So: not a weakening. But two things I want fixed:

1. **The `never()` verify is defeatable by a null.** `:378-379` uses
   `never()).redeem(eq(OTHER_WORKSPACE_ID), anyString(), anyString(), any(), any(), anyString())`.
   Mockito's `anyString()` is a *type* matcher and does **not** match `null`. A regression that
   called `redeem(OTHER_WORKSPACE_ID, code, null, …)` — a null orderId is entirely reachable from a
   malformed payload — would leave this `never()` vacuously satisfied. This repo has already been
   bitten by exactly that. Replace all six with `any()`.
2. **The two twins do not assert the same thing.** The WooCommerce version carries one extra
   assertion the Shopify version does not —
   `WooCommerceWebhookControllerTest.java:398`: `verify(redemptionService, never()).redeem(anyString(), anyString(), any(), anyString(), anyString())`,
   i.e. the legacy global 5-arg overload was never called. `ShopifyWebhookControllerTest.java:378`
   stops at the `OTHER_WORKSPACE_ID` check. A Shopify-side regression that fell back to the global
   overload would be caught on WooCommerce and missed on Shopify. Copy line 398 across.
3. **It proves the wrong workspace was not used; it does not prove no other workspace was used.**
   `times(1)` on the correct pairing plus `never()` on one specific other id is not the same as
   "redeem was called exactly once, with this workspace". A `verifyNoMoreInteractions` — or a
   `times(1)` on `redeem(anyString(), …)` — would be strictly stronger and costs one line.

The gate that pins this test (`store-webhook-terminal-outcome-acked.sh:29-34`) explicitly records
that the status change was deliberate so nobody "restores" it believing they are re-hardening
security. That is the right instinct and I endorse it.

---

### 49. Is HEAD internally consistent after `f3a30c5`? Verify, do not assume.

**Yes, HEAD (`f3ae0dc`) is internally consistent. `f3a30c5` was not, and one of the gates would have
reported PROVED over it.** I verified rather than assumed:

- `mvn -o -q test-compile` inside `influora-api/` — **exit 0**. Main and test sources both compile
  at HEAD.
- Call sites present at HEAD: `ShopifyWebhookController.java:188` calls `orderBelongsToShop`;
  `:227` and `:230` call `StoreWebhookRedemptionOutcome`; `WooCommerceWebhookController.java:279`
  and `:282` do the same; `ShopifyConnectController.java:147` calls
  `webhookRegistrar.registerOrderWebhooks`.
- Working tree clean for `influora-api/`, `src/` and `.proof-os/gates/` except one untracked file,
  `.proof-os/gates/creator-conversation-persists.sh` — a gate for F-0751 that exists on disk and is
  **not in HEAD**. It will not exist for anyone who clones this repo. That is the "untracked file
  invisible to local gates" pattern; it should be `git add`ed.

Now the part that matters. At `f3a30c5` itself:

- `git show f3a30c5:influora-api/.../ShopifyWebhookController.java | grep -c "orderBelongsToShop\|StoreWebhookRedemptionOutcome"` → **0**. The two new classes were committed with no caller.
- `shopify-webhook-shop-binding.sh` **caught it**: its structural check at `:78` greps
  `ShopifyWebhookController.java` for `orderBelongsToShop` and would have exited 1. Credit where due.
- `store-webhook-terminal-outcome-acked.sh` **did not**. Its only structural check is that
  `StoreWebhookRedemptionOutcome.java` exists (`:64`), which it did. It then runs
  `ShopifyWebhookControllerTest,WooCommerceWebhookControllerTest` — and at `f3a30c5` those were the
  *old* suites: `git show f3a30c5:…/ShopifyWebhookControllerTest.java | grep -c "terminalRedemptionOutcome"`
  → **0**, same for the WooCommerce one (both are 1 at HEAD). Old tests against old controllers pass.
  **The gate would have printed "PROVED: terminal redemption outcomes are acknowledged 200" over a
  controller with no terminal handling whatsoever.**

That is a live demonstration of the failure mode all six gates' headers warn about, produced by the
same session that wrote the warning. It is also a direct answer to the CEO's framing: a gate
reporting PROVED over broken behaviour is not hypothetical here — it happened on 2026-09-07.

One more inconsistency, in the record rather than the code: `f3a30c5`'s commit message says "Also
adds three gates" and names `cors-origin-wellformed.py`, `allowlist-domain-owned.py`,
`api-base-url-served.py`. `git show --stat` says it added **six** — those three plus
`affiliate-commission-funded-before-enabled.sh`, `shopify-webhook-shop-binding.sh` and
`store-webhook-terminal-outcome-acked.sh` — and two production classes
(`ShopifyOrderOwnershipVerifier.java`, `StoreWebhookRedemptionOutcome.java`) it does not mention at
all, under a subject line about a frontend API base URL. **The commit message is wrong.** Anyone
auditing this history by reading messages would not know the ownership verifier exists.

---

### 50. Of F-0725, F-0726, F-0728, F-0730, F-0731, F-0732 — which would I refuse to call fixed?

**I would sign off two, and refuse four.**

**Signed off:**

- **F-0728 (cross-workspace coupon shadowing).** The fix is at the query, not in a post-hoc filter:
  `RedemptionWriter.java:199-203` calls `findByWorkspaceIdAndCode`, backed by
  `UNIQUE(workspace_id, code)`, and the unscoped caller reports `AMBIGUOUS_COUPON_CODE`
  (`:222-227`) instead of a bare 500. The ambiguity path is genuinely unreachable from the store
  webhooks (they always pass a workspace, `:234`), which is correct. This one is done.
- **F-0732 (authorize refuses when unconfigured).** `ShopifyConnectController.java:94-99` throws
  before any state is minted. Behaviourally right. The *gate* is illusory (Q47.1) but the code is
  not.

**Refused, in order of how much they worry me:**

- **F-0726 (unsigned tenant selector) — REFUSE.** Two blockers. (a) The Admin API check has **never
  run against a real Shopify store**; the gate says so itself (`shopify-webhook-shop-binding.sh:43-46`)
  and no credentials exist in any environment. Whether Shopify answers 404 for an order not in a
  shop is an assumption. (b) Worse, the failure classification reintroduces F-0725: a shop-side
  token revocation yields 401/403, which `ShopifyOrderOwnershipVerifier.java:138-152` deliberately
  treats as transient, producing a permanent non-2xx that will make Shopify remove the subscription
  in ~48h. **Required before shipping:** a live smoke test against a real store proving the 404
  contract, and a `SHOPIFY_TOKEN_REVOKED` terminal path that marks the integration revoked and
  acknowledges 200 instead of retrying forever.
- **F-0725 (retry storm) — REFUSE, narrowly.** The core fix is right and well-tested. But the split
  covers only the `try` around `executeOnce` (`ShopifyWebhookController.java:198-243`), and six
  permanent conditions are thrown outside it (Q47.5), of which `SHOP_NOT_CONNECTED` is the one a
  real merchant will hit — disconnect in our UI, subscription still live on the store, non-2xx
  forever. `ORDER_AMOUNT_INVALID` is inside the try and still missing from the terminal set.
  **Required:** classify every path in the handler, not just the redemption call, and make
  `TERMINAL_CODES` an explicit exhaustive mapping with a test that fails when a new redemption error
  code is introduced without a classification.
- **F-0730 (webhook subscription) — REFUSE.** The registrar is real code
  (`ShopifyWebhookRegistrar.java:45-54`) and connect calls it
  (`ShopifyConnectController.java:147`), so it is **WIRED**. But the delivery address it registers
  is only correct because the deploy files bake `/api/v1` into `influora.api.public-url`, contrary
  to what `application.yml:134-136` says that key means — and the other consumer of that key,
  `Msg91EmailClient.java:213`, appends the context path a second time and is therefore emitting a
  doubled URL in production today. One of the two is wrong and no gate can tell which.
  **Required:** make `ShopifyWebhookRegistrar` append `server.servlet.context-path` itself, define
  `influora.api.public-url` as the bare origin in every deploy file, fix the unsubscribe link, and
  add a gate that asserts the registered address equals the controller's actual mapped path
  including context path. Then a live registration against a real store.
- **F-0731 (dead-end redirect) — REFUSE, cheaply.** The route exists (`src/App.tsx:394`) and matches
  the config default (`application.yml:469`), and the gate compares the two rather than a third
  hardcoded copy, which is good design. But the comparison reads the **default** only; the
  `INFLUORA_SHOPIFY_REDIRECTURI` override is invisible to it, and I have not seen the callback page
  render. **Required:** extend the gate to fail when the override is set to something the SPA does
  not serve, and one browser pass through the real page.

The unifying reason for all four refusals is the same and it is not a code problem:
**`influora.shopify.api-key` and `api-secret` are bound to nothing in any environment**, so not one
line of the Shopify connect, subscribe, deliver or verify path has ever executed against Shopify.
Everything above is WIRED. None of it is WORKS.

---

## The three things I would fix first, in order

**1. Make the affiliate settlement path structurally incapable of paying from the escrow pool —
before anyone touches the enum.**

Today the only thing preventing a drain from the account holding every brand's escrowed funds is
that `wallet_transactions.type` was never migrated
(`influora-api/src/main/resources/db/migration/V8__wallet_transactions.sql:6-7`). That is an
accident, and the "obvious" one-line `ALTER TABLE` converts it into a lump-sum withdrawal of the
entire accumulated commission backlog (Q42 — `AffiliateSettlementJob.java:227-228` is not
period-filtered). The gate holding it shut is a `grep` that a comment can defeat (Q43).

Order of operations: (a) get a funding ruling from you and Rohan — brand wallet, escrow hold, or
invoice; no document decides it, and the one that claims authority
(`wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md:4`) cites a file that does not
exist. (b) Until that ruling lands, replace the grep gate with a hard code guard: make
`AffiliateSettlementWriter.creditCreatorWallet` refuse to post unless a brand-side debit was made in
the same transaction, so the invariant lives in Java where a comment cannot satisfy it. (c) Only
then migrate. And in the meantime tell affiliate creators the truth — the earnings page shows a
balance nobody has ever been paid.

**2. Settle what `influora.api.public-url` means, because two consumers disagree and both are on
customer-facing paths.**

`ShopifyWebhookRegistrar.java:56-70` assumes the value already contains `/api/v1` (which all four
deploy files supply) and appends only `/webhooks/shopify`. `Msg91EmailClient.java:213` assumes it
does **not**, and appends `server.servlet.context-path` itself — so the one-click unsubscribe link
in every transactional email is a doubled `/api/v1/api/v1/…` right now. Whichever way this is
resolved, the other side breaks silently, and the F-0730 gate checks neither. Define the key as the
bare origin in `application.yml` and in all four deploy files, make both consumers append the
context path, and add a gate asserting the Shopify delivery address equals the controller's actual
mapped path.

**3. Classify every terminal condition in both store webhook handlers, not just the four inside the
`try`.**

`StoreWebhookRedemptionOutcome.java:8-9` is a closed list of four codes, applied only to exceptions
raised inside `ShopifyWebhookController.java:198-243`. Six permanent conditions are thrown before
that block and keep an unconditional non-2xx, and a shop-side token revocation is deliberately
classified as transient by `ShopifyOrderOwnershipVerifier.java:138-152`. Any of them makes Shopify
retry until it removes the subscription — the exact F-0725 failure this session says it closed.
Make the classification exhaustive over the whole handler, add a `SHOPIFY_TOKEN_REVOKED` terminal
path that marks the integration revoked, and add a test that fails when a new redemption error code
appears without a classification.

---

**One thing outside the questions that I want on the record.** `WalletService.doProcessWithdrawal`
is `protected` and is invoked as `this.doProcessWithdrawal(...)` from inside a lambda in the same
bean (`influora-api/src/main/java/com/influora/service/WalletService.java:311`; method at `:382`).
Under Spring AOP its `@Transactional` is a silent no-op, so the creator wallet debit and the
`Payout` row are not one unit. A `PayoutOrphanedDebitSweepJob` exists in the tree, so this may be
one of the deliberate money-path no-ops — I am not changing it here. It should be either documented
as intentional at the call site or corrected, not left ambiguous on a withdrawal path.

**Verdict summary, questions 35–50**

| Area | Verdict |
|---|---|
| Attribution → `AffiliateEarning` row (Q35 steps 1–3) | WORKS |
| `AffiliateEarning` → creator wallet credit (Q35 step 6, Q36, Q41) | ABSENT — never once executed |
| Monthly settlement trigger (Q37) | WIRED, inert |
| Brand-side funding leg (Q39, Q46) | ABSENT — and undecided in any document |
| Creator withdrawal to bank (Q45) | WIRED, self-serve, ungated by source of funds |
| Fabricated-order attack surface (Q44) | WORKS (WooCommerce); WORKS-with-friction (Shopify) |
| F-0728, F-0732 (Q50) | Fixed |
| F-0725, F-0726, F-0730, F-0731 (Q50) | WIRED, refused for ship |

Priya · CTO · 2026-09-08
