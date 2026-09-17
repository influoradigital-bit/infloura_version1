# Subscription & Credits — Sage Team Assignments

> **Arjun Kapoor, Engineering Lead** — 2026-09-17
> **Source:** Priya's code-only answers to `subscription-questions-0917.md`:
> `subscription-answers-0917-brand.md`, `-creator.md`, `-technical.md` (same folder).
> **Companion file:** `assignments-0917.md` (the other lane: L1–L16). This file never re-assigns an
> item that one holds; where the work touches a file one of its lanes owns, it says **"append to Lx"**.
> **Routed through proof-os** — each lane lists the `/proof-os:work` line to start it.
> **done_when (this file):** every gap Priya found has exactly one owner, one done_when, and no file
> is claimed by two lanes.

Arjun spot-checked 3 claims on HEAD before assigning — all true:
comp expiry unenforced (`AdminBillingController.java:84-92` says so itself);
unknown Razorpay plan id → Pro (`SubscriptionService.java:837-847`);
price stored in paise (`V55__seed_billing_plans.sql:32` = 499900) and shown by `formatCurrency` as rupees
(`brand-billing-settings.tsx:377`, `:683`).

---

## Where the subscription actually stands (from the answers)

| Area | Verdict | One line |
|---|---|---|
| Brand subscription | **Not safe to charge** | Pay → may stay Free (no reconciliation); pay → no AI credits until the 1st; comps never expire; ₹4,99,900 on screen |
| Brand AI top-up | **Not built** | A brand at 0 credits can only wait or upgrade — and upgrading doesn't refill |
| Creator subscription | **Does not exist** | By design; creators pay via credit packs |
| Creator credits + packs (₹149/249/649) | **Not built anywhere** | CREDITS-SPEC has no code on any branch |
| Creator Meera limit | **Hidden $0.75 USD cap** | Not credits, not $2.00, dead-end message, no reset date |
| Phase B0 | **Unmerged branch + uncommitted worktree** | Tracked as L13 in the other file |
| Tests on money paths | **3 of 12 YES** | Signature check, checkout→webhook contract and credit guard all survive deletion green |

---

## Lane A update (supersedes the 0915 file)

| ID | Status | Change |
|---|---|---|
| A1 Push | ✅ DONE 2026-09-17 | `1921786` pushed; Backend CI, Frontend Checks, TrendSpark green |
| A2 Razorpay keys on VPS | ⏳ Swapnil to run the check command | unchanged |
| A3 Backfill on prod | ⏳ after A2, needs a deploy | unchanged |
| **A4 Comp 27 brands** | ⛔ **BLOCKED on S2** | A comp's expiry is never enforced; the renewal job extends it forever. Comping today = permanent free Pro |

---

## P0 — Before any brand is charged or comped

### S1 · Vikram (+Ananya FE) · `SubscriptionService.java`, `RazorpayWebhookController.java`, new `SubscriptionReconcileJob`
| Gap | Work | Source |
|---|---|---|
| Pay → stay Free | At checkout, store a PENDING row with `razorpay_subscription_id`. Reconcile against Razorpay (`subscriptions.fetch`) on return from checkout and in a daily job for rows with no webhook. | brand N2, tech N1 |
| Dropped webhook | An unmatched webhook is acked 200 and lost (`RazorpayWebhookController.java:314-324`). Log to a retry table or 5xx so Razorpay retries. | tech B1 |
| Double subscription | Block checkout when the row is Pro PAST_DUE/HALTED (`SubscriptionService.java:343-348`); FE shows "retry payment" or "cancel", not Upgrade. | brand N4, tech N8 |
| Foreign webhook → Pro | Unknown `plan_id` must be refused (log + ack), not default to Pro. The Razorpay account is shared with Snapsby. | brand N14, tech N9 |

**done_when:** a test where the webhook never arrives ends with the brand on Pro after reconcile; a PAST_DUE brand's checkout is refused; an unknown plan id leaves the row untouched — each goes red when its guard is deleted, compiled from `git archive` of the commit.
**Gate after:** Kabir (money path) → Kavya → Priya.
```
/proof-os:work S1 subscription reconciliation, PAST_DUE checkout guard, unknown-plan refusal — Vikram. done_when: Kabir and Kavya approve; each guard falsified
```

### S2 · Vikram · `SubscriptionRenewalResetJob.java`, `SubscriptionDunningJob.java` — **must land before A4**
| Gap | Work | Source |
|---|---|---|
| Comps never expire | Revert a comp to Free at `compExpiresAt`. Exclude comp rows from the renewal sweep. | brand N3 |
| Unpaid Pro extended | Before extending a lapsed ACTIVE Pro row, fetch its status from Razorpay; don't extend a PENDING or HALTED one. | tech N2, tech B2 |
| Free rows swept | Skip Free and non-Razorpay rows (period drift, extra credit reset, audit noise). | brand N7 |

**done_when:** a comp past its expiry is Free after one job run, and a lapsed Pro whose Razorpay status is `halted` is not extended — both red with the guard removed.
**Gate after:** Kabir → Kavya. **Then** Swapnil runs A4.
```
/proof-os:work S2 enforce comp expiry and stop the renewal job extending unpaid Pro — Vikram. done_when: Kabir and Kavya approve; A4 unblocked
```

### S3 · Vikram · **append to L3** (`AICreditService.java`, `BrandAiCredit.java`, `AICreditResetJob.java`) — same files as L3, same owner, one lane
| Gap | Work | Source |
|---|---|---|
| Pay → no credits | On Free→Pro (webhook), set `creditsRemaining` to the new allotment, not only `monthlyAllotment` (`AICreditService.java:294-298`). | brand N1, blocker 1 |
| Pro cut to 150 | `applyEscrowFundedReset` overwrites a Pro allotment with 150 (`:278-282`). Fold into L3's column split. | brand N8 |
| Reset runs twice | `AICreditResetJob` idempotent (guard on `lastReset` month) + catch-up on startup. | tech N3 |
| Credit race | Two turns at 1 credit both succeed (full-row save before the conditional decrement). `@Version` or move the daily counter into the conditional UPDATE. | tech N4, T-5 |

**done_when:** L3's done_when plus: upgrade at 0 credits leaves Pro allotment available immediately; the reset job run twice in one month changes nothing the second time; the race test (Testcontainers, run by Meera in CI — see S7) shows one success at 1 credit.
**Coordinate:** tell the L3 owner before starting; this lane does not open these files in parallel.
```
/proof-os:work L3+S3 credit column split, refill on upgrade, idempotent reset, debit race — Vikram. done_when: Kabir, Kavya, Meera approve
```

### S4 · Ananya · `src/pages/brand-billing-settings.tsx` (+ its test)
| Gap | Work | Source |
|---|---|---|
| ₹4,99,900/month | `priceInr` and `invoice.amount` are paise; divide by 100 at display (`:377`, `:683`). | brand N6, tech N7 |
| `undefined` limits | `PlanDto` is NON_NULL, so omitted limits arrive `undefined`. Handle it (or drop NON_NULL — Vikram, one line). | tech N7 |

**done_when:** a vitest fed a real Pro-shaped payload renders "₹4,999" and no "undefined"; red against today's code.
```
/proof-os:work S4 billing page shows paise as rupees — Ananya. done_when: Kavya approves; test red on current code
```

### S5 · Kavya · tests only (no production code)
| Gap | Work | Source |
|---|---|---|
| Signature check untested | `WebhookSignatureVerifierTest` with a known Razorpay HMAC vector, good and bad; a controller test with the real verifier. Today a verifier that accepts anything passes 74 tests. | tech N5, B4 |
| Wire format untested | `RazorpayClient.createPlan`/`createSubscription` with a real client + `MockRestServiceServer`: amount in paise, `notes.workspaceId` present. | tech N6 |
| Reverse entitlement gate | Fail when a `Plan` limit is shown without an `Entitlement` constant (catches `trackedCreatorLimit`). Priya writes the rule first. | tech N10 |

**done_when:** each test goes red on the mutation Priya used (accept-any-signature, renamed note key, ₹49.99 plan), falsified in an archive copy.
```
/proof-os:work S5 webhook signature, Razorpay wire-format and reverse entitlement tests — Kavya. done_when: Priya approves; each test falsified
```

---

## P1 — Complete the brand product

### S6 · Vikram (BE) + Ananya (admin FE) · `AdminBillingService.java`, `AdminBillingController.java`, email templates
| Gap | Work | Source |
|---|---|---|
| No admin cancel/end-comp | Admin action to cancel a subscription and end a comp early, audited. | brand N10 |
| Silent cancellation | Cancellation email to the brand, alert to admin. | brand N10 |

**done_when:** admin ends a comp → row Free + audit row + email queued; click-tested in the admin UI.
**Depends on:** S2 (same job semantics).
```
/proof-os:work S6 admin cancel and end-comp with audit and emails — Vikram + Ananya. done_when: Kavya approves; Neha click-tests
```

### S7 · Meera · CI with Docker — **append to L2** (L2 already adds a Docker CI job)
| Gap | Work | Source |
|---|---|---|
| No DB-backed money tests | Run webhook replay, out-of-order status, and S3's credit race against real MySQL in CI. | tech N4, N11 |
| Native SQL unverified | Add `avgFirstAdminReplyMinutes` repository test (C4 follow-up Priya accepted). | C4 review |

**done_when:** the CI log shows these classes with `Skipped: 0`.
```
/proof-os:work L2+S7 Docker CI job runs the money-path and repository tests — Meera. done_when: CI log shows Skipped 0
```

### S8 · Ananya + Nisha · `<UpgradeGate>` and copy — **fold into D1** (0915 file)
| Gap | Work | Source |
|---|---|---|
| Zero-credit dead end | The zero-credit paywall is wrong for Pro and already-funded brands and has no upgrade path. | brand N11 |
| Wrong "coming soon" | Pricing page says export and templates are coming soon; both are built and Pro-gated. | brand N12 |

```
/proof-os:work D1+S8 UpgradeGate on the four gates and the zero-credit paywall; fix pricing copy — Ananya + Nisha. done_when: Neha real-click test passes
```

### S9 · Vikram · `BrandContextService.requireBrandWorkspace` — **fold into C5** (0915 file)
`requireBrandWorkspace` does not check workspace type, so `/billing/plan` lazily creates rows for AGENCY (brand N13). Blocked on B1 (Agency ruling).

---

## P2 — Creator side (routes through the Phase B lane)

Not assigned here if `assignments-0917.md` already holds it (L13 B0 strand, L14 copy, L15 tests,
L16 cap/holdout/credits rulings). New from Priya's creator answers:

| ID | Owner | Work | Depends on | done_when |
|---|---|---|---|---|
| S10 | Vikram (API) + Ananya (FE) + Rohan (pack price, GST line) | **Build CREDITS-SPEC K1–K10**: balance and ledger, packs, order route, webhook branch, reset job, 500/day cap, 402 with pack CTA. Behind `CREATOR_CREDITS_ENABLED=false`. | B0 merged (D1) + Swapnil's O1–O5 + S1's webhook hardening | spec §14 ten questions answered YES by Priya; Kabir approves the money path |
| S11 | Vikram + Ananya | Brand-facing "Drafted with Meera" stamp (`influora.meera.brand-facing-stamp`, `brand-chat.tsx`). A B0 item missing even from the B0 worktree. | L13 | brand chat shows the label; property off strips it; tested |
| S12 | Vikram (influora-ai) + Ananya | Cap message: `resets_on` in `_creator_cap_response` + the three "what still works" links on **both** `MeeraCopilotChat` error paths | L16 CAP ruling | test asserts reset date and 3 links on stream and non-stream paths |
| S13 | Meera + Neha | Prove Phase A is live on Utho: read live `MEERA_CREATOR_ENABLED` / `AI_CREATOR_MONTHLY_CAP_USD`, run one consented creator turn, record the rows | — | rows recorded in the ledger |
| S14 | Tara + Meera (DB), Kavya (metric-2 sample) | B0 gate measurement: queries for metrics 1/3/4/5, hand-review protocol for metric 2, `B0-METRICS.md` template (+ `edited` column on `meera_drafts`) | L13 | template exists; each query runs on a copy of prod |
| S15 | Tejas | Remotion films show an unbuilt "Drafted with Meera" label (`src/remotion/script.ts:197`, `script.en.ts:113`, `script.mr.ts:113`) | — | label removed or S11 shipped first |

```
/proof-os:work S13 prove Meera creator Phase A is live on Utho — Meera + Neha. done_when: live turn rows recorded
```

---

## P1c — Work from the 2026-09-17 rulings

Rulings: `wiki/decisions/CMO-PRO-DUNNING-GRACE-0917.md` (Tejas) and
`wiki/decisions/CFO-BRAND-FEE-GST-TOPUP-0917.md` (Rohan). Tejas's "comparable products" examples (Zoho,
Freshworks) were not checked by Arjun and are cited as market context, not evidence.

### S18 · Vikram (BE) + Ananya (FE) + Nisha (copy) · Pro stays on during 7-day grace — **append to S2 then S1, and S4**
| Item | Work | Files (owner lane) |
|---|---|---|
| Grace | PAST_DUE counts as active Pro for 7 days from the first failed charge; HALTED ends it. The 7% fee applies during grace. | `SubscriptionService.getActivePlanForWorkspace` (S1), `SubscriptionDunningJob` (S2) |
| End of grace | HALTED → Free: templates and export paused, data kept, allotment 100 at next reset | same |
| Banner | Yellow "payment failed, retry" banner during grace (copy in Tejas's ruling) | `brand-billing-settings.tsx` (S4) |
| Emails | Payment-failed and halted emails use the ruling's copy | email templates |
| Metric | Recovery rate within grace (target >70%) — a query, not a dashboard, for now | Tara |

**done_when:** a PAST_DUE brand on day 3 is charged 7% and can export; on day 8 (HALTED) is charged 10% and cannot; both red with the grace check removed.

### S19 · Vikram · fee shown = fee charged (10% Free / 7% Pro)
| Item | Work |
|---|---|
| Plan-blind copy | `BrandPlatformFeeService` reads the workspace's real `fee_bps`, not a hardcoded "10%" |
| Stray 15% | Remove `PLATFORM_FEE_PERCENT` (15.00) or point `AmountDerivationService` at the plan-aware fee |
| Pricing page | `pricing.tsx` shows 10% Free / 7% Pro (Ananya) |

**Before starting:** check the other session's fee tickets (F-0840–F-0851, `CTO-BRAND-PRICING-ANSWERS-0917.md`) — if one already owns `BrandPlatformFeeService`, append there instead.
**done_when:** a Pro brand's fee endpoint returns 7% and a Free brand's 10%; no `15` fee constant remains; test red on current code.

### S17 · Vikram (BE) + Ananya (FE) + Kabir (gate) · Brand AI top-up packs
Catalogue **proposed by Rohan, prices need Swapnil**: 50 credits ₹999 · 150 ₹2,499 · 400 ₹5,999 (GST-inclusive; Pro stays cheaper per credit at ₹12.50). Purchased credits never expire and are used only after the monthly allotment.
| Item | Work |
|---|---|
| Balance | `purchasedBalance` bucket on `brand_ai_credits`; debit monthly first, then purchased; refunds return to their bucket |
| Orders | pack catalogue + `brand_credit_orders` migrations; Razorpay one-time order; webhook branch credits exactly once |
| Routes | `GET` packs, `POST` order, `GET` order status |
| FE | "Buy credits" next to "Fund a campaign" on the zero-credit paywall (`CreditPaywall`, `meera-copy.ts`, `MeeraChatPanel.tsx`) |

**Depends on:** S3 (same credit files — after L3/S3 lands), S1 (webhook hardening), S5 (signature test), Swapnil confirming prices.
**done_when:** duplicate webhook credits once; a failed order credits nothing; a brand at 0 credits buys a pack and its next turn succeeds; Kabir approves the money path.
```
/proof-os:work S17 brand AI credit top-up packs — Vikram + Ananya. done_when: Kabir and Kavya approve; duplicate webhook credits once
```

### Still open for Swapnil
- **GST on the campaign fee.** Invoices print fee + 18% GST, but only the fee is debited. Rohan recommends **the fee is GST-inclusive, and the invoice backs it out** (no extra charge to brands). The alternative adds +₹900 (Free) or +₹630 (Pro) on a ₹50,000 campaign. Blocks the invoice fix.
- **Confirm the top-up pack prices** above.

---

## P3 — Live proof (last)

### S16 · Swapnil (payer) + Meera (DB checks)
Run Priya's T-12 live test with a real ₹4,999: sidebar Billing → Upgrade → Razorpay → webhook → Pro row → credits available → Pro limit applies → cancel. Check the DB rows after each step (list in `subscription-answers-0917-technical.md` T-12).
**Depends on:** A2, A3, S1, S2, S3, S4. **done_when:** every row matches; recorded in the ledger.

---

## Decisions only Swapnil can make (new; D1–D3 and B1–B3 are in the other files)

| # | Question | Ruling (Swapnil, 2026-09-17) | Blocks |
|---|---|---|---|
| R1 | Should Pro stay on during the 7-day dunning grace? Code drops to Free at the first failed charge. | **Delegated to Tejas, market-based** → `wiki/decisions/CMO-PRO-DUNNING-GRACE-0917.md` | S2 |
| R2 | Brand fee number: Pro brands are told 10%, Meera quotes 15%, pricing says 7%. | **10% Free, 7% Pro.** Rohan formalises → `wiki/decisions/CFO-BRAND-FEE-GST-TOPUP-0917.md` | fee copy fix |
| R3 | Is ₹4,999 GST-inclusive or ₹4,999 + GST? | **GST-inclusive** (matches current code). GST on the *campaign fee* is still open — Rohan to recommend | S16, invoices |
| R4 | Brand voice costs no credits on any plan. Meter it? | **Keep it free.** No work; Rohan records the cost exposure | — |
| R5 | Brand AI top-up packs? | **Yes, build them.** Rohan proposes the pack catalogue and prices | new lane S17 |

---

## Order

```
S2 ──→ A4 (comp 27)
A2 → A3 ─┐
S1, S3, S4, S5 ──┴→ S16 (live ₹4,999)
S6 after S2 · S7 alongside S3 · S8 with D1 · S9 with C5
S10 after B0 merge + O-rulings + S1
```

## File ownership (no two lanes on one file)

| Lane | Files |
|---|---|
| S1 | `SubscriptionService.java`, `RazorpayWebhookController.java`, new reconcile job |
| S2 | `SubscriptionRenewalResetJob.java`, `SubscriptionDunningJob.java` |
| S3 = L3 | `AICreditService.java`, `BrandAiCredit.java`, `AICreditResetJob.java` |
| S4 | `brand-billing-settings.tsx` |
| S5 | new test files only |
| S6 | `AdminBillingService.java`, `AdminBillingController.java` (after S2) |

S1 and S2 are both Vikram and both touch subscription state. **Run S2 first, then S1**, not in parallel.
