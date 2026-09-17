# Subscription, credits and Meera — questions for Priya (2026-09-17)

**From:** Arjun (for Swapnil). **Answered by:** Priya, from code only: every answer cites `file:line`
she opened, and says **"not in code"** when something exists only in a doc, spec or pricing page.
**Answer files:** `subscription-answers-0917-brand.md`, `-creator.md`, `-technical.md` (same folder).

Every answer starts with one of **YES / NO / PARTLY / NOT BUILT / UNKNOWN (why)**, then the evidence.
"The spec says" is not an answer; the spec is what the question is being checked against.

---

## Part 1 — Brand point of view (subscription)

### 1A. Is the subscription useful? What does the AI give me for paying?
- **BR-1** As a brand on Free, what exactly can I do with Meera (the AI) today, and what do I lose compared to Pro? List every AI feature and its Free/Pro difference as enforced in code, not as written on `/pricing`.
- **BR-2** Which AI features are blocked at the server for Free brands, and which are only hidden in the UI (reachable by calling the API directly)?
- **BR-3** When I hit a Pro-only feature, does the product tell me why and show me how to upgrade (the structured 402 and `<UpgradeGate>`), or do I get a generic error? Name every surface that does each.
- **BR-4** Does Pro make Meera measurably better for me (more credits, better model, more tools), or only allow more of the same?

### 1B. Limitations
- **BR-5** List every Free-plan limit that is actually enforced (campaigns, saved/tracked creators, AI credits, exports, seats, anything else), with the number, where it is checked, and what I see when I hit it.
- **BR-6** Which limits shown on `/pricing` are not enforced anywhere in code?
- **BR-7** When my limit resets, is it on the 1st of the month or on my billing anniversary? Is it the same for credits and for usage counters?
- **BR-8** If I downgrade from Pro to Free mid-cycle, what happens to data over the Free limit (e.g. 30 saved creators when Free allows fewer)? Blocked, hidden, deleted, or kept?

### 1C. Price
- **BR-9** What is the Pro price in code (plan seed / Razorpay plan id), and does it match ₹4,999 on the pricing page? Monthly only, or yearly too?
- **BR-10** Is the 7% platform fee applied for Pro and the Free fee for Free on every path that takes a fee (campaign publish, deal funding, direct hire), or can a path skip it?
- **BR-11** Is GST added on the subscription charge, and does the brand get an invoice for it?
- **BR-12** Is there a free trial anywhere in code, despite the "no trial" ruling?

### 1D. Credits and top-up
- **BR-13** How many AI credits does a Free brand and a Pro brand get per month, and what does each Meera action cost?
- **BR-14** **Can a brand buy more AI credits (top-up) when they run out?** If yes, the route, price and payment path. If no, what does the brand see at zero credits?
- **BR-15** Does the loyalty bonus stack on Pro (Swapnil's ruling) in code today, and can a plan change wipe a bonus that was earned?
- **BR-16** Is the wallet top-up (money for deals) kept separate from AI credits, so a brand can't accidentally spend deal money on AI or the reverse?

### 1E. Does it work?
- **BR-17** Walk the upgrade end to end as a brand: sidebar Billing → plan page → Upgrade → Razorpay checkout → webhook → Pro row → Pro limits apply. For each step: built? tested? ever run live?
- **BR-18** What happens when the payment succeeds but the webhook never arrives? Does the brand stay on Free having paid? Is there any reconciliation?
- **BR-19** Renewal, failed renewal, cancellation: what state does the subscription go to, when do Pro features stop, and is anyone notified?
- **BR-20** Can an admin see, comp, extend and cancel a brand's subscription from the admin console, and is each action audited?
- **BR-21** What does an AGENCY workspace get today: a plan, credits, limits? Can it upgrade at all?

---

## Part 2 — Creator point of view (subscription, credits, Meera)

### 2A. Is there a creator subscription? What is useful?
- **CR-1** Is there any paid plan for creators in code, or is everything free for creators? If there is none, what is the creator's paid product (credit packs)?
- **CR-2** What can a creator do with Meera today for free, in code on this branch, and which of those are behind a feature flag that is off in production?
- **CR-3** How does Meera help a creator earn more (rate quote, deal risk, drafted replies, campaign fit)? For each: built, flagged off, or spec only?

### 2B. Limitations
- **CR-4** What is the creator AI cap in code today: $0.75 or $2.00? Where is it set, and what does the creator see when they hit it (a dead end, or "what still works")?
- **CR-5** Brief extraction's own $0.25 cap (Phase B §14.4.b): built? Can chat usage starve brief extraction today?
- **CR-6** Is there a per-creator daily action cap (500, R7)? What error does the creator get?

### 2C. Price and credits
- **CR-7** Credits model (CREDITS-SPEC): is it built? Turn = 1, voice = 2, brief = 3; 30 signup + 40 monthly. Which of these numbers exist in code, and is `CREATOR_CREDITS_ENABLED` on or off?
- **CR-8** **Can a creator top up credits?** Packs ₹149 / ₹249 / ₹649: do the pack catalogue, order route, Razorpay order and webhook credit exist? Can it be bought on production today?
- **CR-9** Do purchased credits never expire and monthly credits reset on the 1st (R2)? Is debit monthly-first, and is a refund returned to the bucket it came from?
- **CR-10** If a chat turn fails mid-stream, is the credit refunded exactly once? Can a retry or a race double-charge or double-refund?
- **CR-11** Does a voice turn cost 3 (2 at transcribe + 1 for the turn), and is voice refunded when transcription falls back?
- **CR-12** Are credits ever posted to `wallet_transactions` (they must not be — credits are not money)?

### 2D. Meera Creator Phase B spec — questions based on the spec
- **PB-1** Phase B was split into B0 (Paste and Read) and B1 (Secure and Send). Which of B0 is built on this branch, and has any of B1 been started despite the gate?
- **PB-2** Paste a WhatsApp brief: does the creator get a summary, a rate and flags in one request, and does the raw text survive an AI outage (spec §11 Q1)?
- **PB-3** Does every creator Meera tool refuse a brand principal, missing consent, and an out-of-scope level at the server (§11 Q2)?
- **PB-4** Can any tool result, draft, secure link or media kit carry the creator's floor price or agency name to a brand? Where is it stripped, and which test proves it (§11 Q3, `InfoBarrierTest`)?
- **PB-5** Does `draft_reply` refuse a below-floor counter unless the deal is marked strategic, and is the override logged (§11 Q4)?
- **PB-6** Do brands see "Drafted with Meera" on drafted messages (ruling 1), and is `influora.meera.brand-facing-stamp` declared where the spec says?
- **PB-7** Is `campaigns.introduced_by_creator_profile_id` written on secure-link redeem (ruling 2)?
- **PB-8** Rate quote (B3): is the cold-start price a formula (§14.1)? Does it ever quote a price with no data behind it, and does it say so?
- **PB-9** Deal risk rules (B4): are all ten built, each with a firing and a non-firing test, and does severity scale with deal value (§11 Q6)?
- **PB-10** Holdout (B6): does it withhold only the anchor and counter drafts, deterministically, and never for existing creators (§11 Q9)?
- **PB-11** The B0 → B1 gate needs five metrics measured live. Can each one be measured from what the code records today (e.g. the `edited` column on `meera_drafts`)?
- **PB-12** Phase A must be deployed before B0 can be measured. Is Phase A live on production?
- **PB-13** Do the Phase B migrations (`V20260910*`) and credits migrations (`V20260912100*`) exist, and do their versions collide with anything, including `V20260912120000` (the brand backfill)?

---

## Part 3 — Technical testing: does it really work?

### 3A. Test coverage that would catch a break
- **T-1** For each money or limit path — upgrade checkout, subscription webhook, credit debit, credit refund, pack purchase, platform fee, plan-limit gate — name the test that fails if the path is deleted or inverted. Mark any path whose only test mocks the exact layer the bug would be in.
- **T-2** Is the Razorpay webhook signature verified on the subscription and pack events, and is there a test that sends a bad signature and expects rejection?
- **T-3** Is webhook handling idempotent? Deliver the same `subscription.charged` / `payment.captured` event twice: one Pro row, one credit grant, one ledger entry? Which test shows it?
- **T-4** Out-of-order webhooks (`cancelled` arriving before `activated`): what final state results, and is it tested?
- **T-5** Concurrency: two simultaneous turns at 1 credit left. Can both succeed? Which lock or conditional update prevents it, and is it tested against a real DB or only a mock?
- **T-6** Entitlement registry: does the conformance gate go red if someone adds a plan-limited feature without registering it, or calls `requireCapacity` without `consume`?

### 3B. Environment and live
- **T-7** Which of these are proven only by unit tests, which by Testcontainers (which cannot run on this machine), and which were ever exercised on production: brand upgrade, webhook → Pro, credit reset job, renewal reset job, creator pack purchase?
- **T-8** Does production boot refuse to start (not just warn) with placeholder Razorpay keys or webhook secret? Which validator, which line?
- **T-9** Scheduled jobs (`AICreditResetJob`, `SubscriptionRenewalResetJob`, `CreatorCreditResetJob`): do they run on exactly one instance, are they idempotent if run twice in a day, and what happens to a brand whose reset was missed while the server was down?
- **T-10** Are there feature flags that make a green test suite meaningless in production (e.g. `CREATOR_CREDITS_ENABLED=false`, Meera creator feature flag, `VITE_PAYMENTS_IN_ENABLED`)? List each flag, its production value if knowable, and what it turns off.
- **T-11** Frontend: does any subscription or credit screen show a number the API never sends (a TS field with no matching DTO field), or read a field under a different name? Check both `src/lib/api.ts` and `src/lib/meera-api.ts`.
- **T-12** What is the minimum manual live test — real clicks, a real ₹ amount — that would prove the brand subscription and the creator top-up work end to end? List the steps and the DB rows to check after each.

---

## Part 4 — Summary Priya returns at the end of each answer file
1. One line per question id: YES / NO / PARTLY / NOT BUILT / UNKNOWN.
2. **Blockers to charging money** — anything that would take a payment and not deliver, or deliver without payment.
3. **New work found** — gaps with no existing task, each with a suggested owner. Arjun turns these into assignments.
