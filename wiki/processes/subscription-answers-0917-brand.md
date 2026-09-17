# Subscription answers, Part 1 (brand) and Part 4 summary

**Answered by:** Priya (CTO), from code only. **Date:** 2026-09-17
**Questions:** `wiki/processes/subscription-questions-0917.md`

**Code state.** Every citation is to COMMITTED code. HEAD is `e2dba1c`, not `1921786` as the brief
said. The two commits in between (`e4a357b` docs, `e2dba1c` deploy compose) touch no billing,
credit, Meera-brand or pricing file, so the answers hold for both. I checked the billing/credit
files against the working tree (`service/billing`, `security`, `job`, `service/meera`,
`BillingController`, `integration/razorpay`, `pricing.tsx`, `brand-billing-settings.tsx`,
`meera-api.ts`). Only `src/lib/api.ts` has uncommitted edits, in the portfolio section at lines
4684-4821. They do not touch the billing block (4252-4395) cited below.

Paths are shortened. `api/` means `influora-api/src/main/java/com/influora/`, and `mig/` means
`influora-api/src/main/resources/db/migration/`.

---

## 1A. Is the subscription useful?

### BR-1: PARTLY. Meera is the same product on Free and Pro. The only difference in code is how many credits you get.
- The one place Meera charges credits is a brand chat turn, which costs 1: `api/service/meera/MeeraSessionService.java:84`, `:388`. Nothing in Meera checks the plan. The only code that calls `getActivePlanForWorkspace` is billing, fee, seat, gate and job code (git grep of `getActivePlanForWorkspace|PlanCode|RequiresPlan` over `api/`). No Meera class calls it.
- Tools are the same six on both plans: `api/domain/enums/MeeraToolName.java` (create_campaign, request_payment, confirm_launch, show_creators, calculate_budget, get_campaign_performance). `influora-ai` has no plan or tier logic (git grep of `plan_code|plan_tier|subscription|is_pro` finds only a Sarvam header).
- Free gets 100 credits a month (`mig/V55__seed_billing_plans.sql:23`), or 150 after the first funded launch (`api/service/meera/AICreditService.java:75`, `:276-285`). Pro gets 400 (`V55:33`).
- Both plans get a 30-day unlimited window after a funded launch: `api/service/meera/tool/ConfirmLaunchExecutor.java:115`, `:380-381`; `BrandAiCredit.isUnlimited`.
- Both plans share the 500-actions-per-day cap: `AICreditService.java:82`, `:133-138`.
- Voice speak and transcribe cost no credits on either plan: `api/web/MeeraController.java:237-242`, `:279-307` (no `creditService` call).

### BR-2: NO. The server does not block any AI feature by plan, and the UI does not hide any.
- The only AI-related check on the server is the credit ledger (`AICreditService.tryConsume:149-155`, 402 `CREDITS_EXHAUSTED`). That check is identical for Free and Pro.
- The server does block plan-gated non-AI features: export (`api/web/ReportExportController.java:36`), creating a template (`api/web/CampaignTemplateController.java:53`), seats (`api/service/WorkspaceMemberService.java:421-437`) and creator analytics views (`api/security/AnalyticsUsageCapInterceptor.java:98-110`). None of them relies only on the UI.

### BR-3: PARTLY. Every gate returns a 402 with a code, but the body is not structured and `<UpgradeGate>` does not exist.
- The 402 body is only `code` + `message` (`api/common/GlobalExceptionHandler.java:42-46`; `ApiErrorBody.java:17-29` has no entitlement, limit or used field). git grep for `UpgradeGate` in `src/` finds nothing.
- Per surface:
  - **Analytics:** `src/hooks/analytics/useCreatorMetrics.ts:34` shows fixed upgrade text, with no button.
  - **Export:** `src/pages/brand-campaign-detail.tsx:621` shows a "Pro feature" toast, with no button.
  - **Templates:** `src/components/brand/campaigns/campaigns-list.tsx:400-404` shows a generic "Could not save template" toast with the server message.
  - **Seats:** `src/components/brand/settings/team-members-panel.tsx:135-140` shows a generic "Could not send the invite" toast.
  - **AI credits:** `src/components/feature/meera/MeeraChatPanel.tsx:921`, `:957`, `:1097` show `CreditPaywall`. Its copy is "Fund your first campaign to unlock me fully / or I'm back on the 1st", with a "Fund a campaign" button (`src/data/meera-copy.ts:104-108`). There is **no upgrade link**. Pro brands, and brands that have already funded a campaign, see the same copy.
- Doc drift: `SUBSCRIPTION-MODEL-REDESIGN-0912.md:189` says export 402s "have no handler at all". Export does have a 402-specific toast (`brand-campaign-detail.tsx:621`).

### BR-4: NO. Pro only gives more of the same, and in practice not even that right away.
- Same model and same tools (BR-1). The only difference is 400 credits instead of 100 or 150.
- **Paying does not add credits when you upgrade.** The webhook path calls `reconcileAiCreditAllotment` (`api/service/billing/SubscriptionService.java:718`, `:745-757`). That calls `applyPlanAllotment`, which writes only `monthlyAllotment` (`AICreditService.java:294-298`). `creditsRemaining` is only refilled by `AICreditResetJob`, on the 1st (`api/job/AICreditResetJob.java:53`, `:93`). A brand that upgrades on the 2nd with 0 credits left gets no AI until the next 1st.
- **A Pro brand's first funded launch cuts its allotment to 150.** `applyEscrowFundedReset` sets `monthlyAllotment = 150` and `creditsRemaining = 150` (`AICreditService.java:278-282`). It does not look at the plan. The allotment only goes back to 400 at the next 1st (`AICreditResetJob.java:125-129`).

---

## 1B. Limitations

### BR-5: PARTLY. Six limits are enforced. Tracked creators, saved creators and campaign count are not.

| Limit | Free / Pro | Checked at | What the brand sees |
|---|---|---|---|
| Seats (active members + pending invites) | 1 / 5 (`V55:23,33`) | `WorkspaceMemberService.java:421-437`, called at `:169`, `:242` | 402 `UPGRADE_REQUIRED`, generic toast |
| Creator analytics views (distinct creators per period) | 1 / unlimited | `AnalyticsUsageCapInterceptor.java:98-110`, registered at `api/config/PlanGateWebConfig.java:38-40` | 402, upgrade text, no CTA |
| AI credits | 100 (150 loyalty) / 400 | `AICreditService.java:149-155` | 402 `CREDITS_EXHAUSTED`, fund-a-campaign paywall |
| AI daily actions | 500 / 500 | `AICreditService.java:133-138` | 429 `DAILY_ACTION_LIMIT_EXCEEDED` |
| Export | off / on | `ReportExportController.java:36` through `api/security/PlanGateInterceptor.java:50-61` | 402, "Pro feature" toast |
| Create a campaign template | off / on | `CampaignTemplateController.java:53` | 402, generic toast |
| Brand fee | 10% global / 7% | `api/service/BrandCampaignFeeService.java:108-121` | wallet debit at publish |

- **Not enforced: tracked creators (5).** `UsageMetric.TRACKED_CREATOR` is read at `api/web/BillingController.java:128` and written nowhere in `src/main`.
- **Not enforced: saved creators.** `api/service/CreatorDiscoveryService.java:433-450` saves with no check (`POST /creators/{creatorId}/save`, `CreatorController.java:166`).
- **No limit on campaign count** exists anywhere.

### BR-6: PARTLY. The pricing page and the code disagree in three places.
1. "5 tracked creators" (`src/pages/pricing.tsx:156`, `:237`) is not enforced (BR-5). The billing page shows it as "Tracked/Saved Creators 0/5" from a meter that never moves (`src/pages/brand-billing-settings.tsx:325-327`).
2. **The reverse case.** Pricing marks export and templates `comingSoon: true` (`pricing.tsx:164-165`) and says they are "in active development" (`:388-391`). In code both are built and gated to Pro (BR-5).
3. The brand-fee endpoint hard-codes the global rate and the copy "Platform fee (10%)" for every brand, Pro included (`api/service/BrandPlatformFeeService.java:35-36`, `:53`). The pricing page (`:366`) says "your workspace's current rate is shown". It is not shown for Pro.

### BR-7: PARTLY. Credits and usage counters reset on different schedules, and the Free schedule drifts.
- **AI credits:** reset for every BRAND workspace on the 1st at 02:00 UTC (`AICreditResetJob.java:53`, `:85`, `:93`), on both plans. A Pro renewal does not reset credits (`SubscriptionService.java:745-757` sets only the allotment).
- **Usage counters (analytics views):** keyed to `subscription.currentPeriodStart` (`api/service/billing/UsageCounterService.java:194-200`).
  - For Pro, that date comes from Razorpay (`SubscriptionService.java:702-704`), so the counter resets on the billing anniversary.
  - For Free, the row starts on the 1st (`SubscriptionService.java:253-259`). After that, `SubscriptionRenewalResetJob` extends every lapsed ACTIVE row by the *previous* period's length (`api/job/SubscriptionRenewalResetJob.java:118-126`, `:175-181`). Sep 1→Oct 1 is 30 days, so the next period is Oct 1→Oct 31. **The Free reset drifts away from the 1st.**
  - Each such renewal also calls `resetForNewCycle` (`SubscriptionService.java:795`), so a Free brand gets an **extra credit reset** on the drifted date. It also writes a `SUBSCRIPTION_RENEWAL_SAFETY_NET` money-audit row (`SubscriptionRenewalResetJob.java:192-203`) for every Free brand every month.

### BR-8: PARTLY. Nothing is deleted or hidden. New writes over the limit are blocked, and some limits are not checked at all.
- Pro stops right away when the status is not ACTIVE (`SubscriptionService.java:217-223`).
- **Seats:** existing members stay. New invites are refused while count ≥ 1 (`WorkspaceMemberService.java:430-437`).
- **Saved and tracked creators:** no limit, so all 30 stay and more can be added (BR-5).
- **Analytics:** creators already viewed this period are still allowed (dedup, `UsageCounterService.java:119-123`). New creators are refused.
- **Templates:** existing custom templates can still be listed and used (`CampaignTemplateController.java` GET handlers have no gate). Only POST is refused.
- **Export:** refused.
- **Credits:** `creditsRemaining` keeps whatever Pro balance is left until the 1st (`applyPlanAllotment` does not touch it).

---

## 1C. Price

### BR-9: PARTLY. ₹4,999 a month matches the pricing page. There is no yearly plan, and the Razorpay plan id does not exist until the first checkout.
- `price_inr = 499900` (paise), `billing_cycle = 'MONTHLY'`, `razorpay_plan_id = NULL`: `mig/V55__seed_billing_plans.sql:32`. No migration updates it (git grep of `mig/`).
- The Razorpay plan is created lazily at the first checkout: `SubscriptionService.java:503-521`, `api/integration/razorpay/RazorpayClient.java:160-185`. `plans.razorpay_plan_id` in production is therefore NULL until someone clicks Upgrade.
- git grep finds no YEARLY or ANNUAL plan. The pricing page says monthly only (`pricing.tsx:356`), which matches.
- **Finding:** the billing page shows the price in paise as if it were rupees. It calls `formatCurrency(currentPlan.priceInr)` (`brand-billing-settings.tsx:377`), and `formatCurrency` does not divide by 100 (`:47-53`). A Pro brand sees **₹4,99,900 per month**. Invoice rows have the same bug (`:683`, where `amount` is paise per `BillingController.java:241-250` and `InvoiceService.java:207`). The upgrade card hard-codes `formatCurrency(4999)` (`:554`), so it looks right.

### BR-10: PARTLY. The charged fee follows the plan on both publish paths. The fee brands are shown or quoted ignores the plan.
- Both paths that make a campaign live charge through `chargeOnPublish`, which reads the plan: `api/service/CampaignService.java:376` and `ConfirmLaunchExecutor.java:339`. The fee resolves to 7% for Pro, otherwise the global config (`BrandCampaignFeeService.java:108-121`). Brand fee config default is 1000 bps (`mig/V42__platform_fee_config_brand_fee_razorpay.sql:24`); the Free plan's `fee_bps` is NULL (`mig/V57__free_plan_fee_bps_null.sql:14`). A publish-path gate exists: `BrandFeePublishPathConformanceTest`.
- If plan lookup fails, the brand is charged the global rate (`BrandCampaignFeeService.java:123-147`). A Pro brand is overcharged, and the only trace is a log alert.
- Deal funding (`api/web/EscrowController.java:68-88`) takes **no brand fee**, by design (charged at publish). A direct hire needs a published campaign (`CreatorDiscoveryService.invite`), which has already paid.
- **Ignores the plan (finding):**
  - Meera's `request_payment` quotes a total with `PLATFORM_FEE_PERCENT`, default **15%** (`api/service/AmountDerivationService.java:68`, `resources/application.yml:407`, `RequestPaymentExecutor.java:128`). This is a third rate, unrelated to 7% or 10%.
  - `GET` brand-fee shows 10% for Pro (BR-6).

### BR-11: PARTLY. GST is **included in** the ₹4,999, not added on top. An invoice is generated only if the charge webhook arrives.
- Razorpay is asked for exactly `priceInr` (`RazorpayClient.java:170-178`).
- The invoice backs GST out at 18% inclusive: `base = amount / 1.18` (`api/service/billing/InvoiceService.java:252-259`). Net revenue is about ₹4,236 plus ₹763 GST.
- The invoice is created only on `subscription.charged` (`api/integration/razorpay/RazorpayWebhookController.java:128-129`, then `generateInvoiceFromWebhook` `InvoiceService.java:160-215`). If the GST breakup or numbering fails, it is logged and not thrown, and the invoice is left without a number (`:260-266`). If the PDF or R2 step fails, it is logged (`:220-235`). An emailed `InvoiceReadyEvent` goes out when the recipient resolves (`RazorpayWebhookController.java` `generateInvoiceAndNotify` `:385`).
- No ruling I could find says whether ₹4,999 includes or excludes GST. The pricing page does not mention GST.

### BR-12: NO trial.
- No TRIALING status (`api/domain/enums/SubscriptionStatus.java:3-8`; `mig/V54__subscription_billing.sql:38`). Checkout charges immediately.
- Closest thing: the 30-day unlimited-AI window after a funded launch, on both plans (BR-1). It is not a plan trial.

---

## 1D. Credits and top-up

### BR-13: PARTLY. Free gets 100 credits (150 after the first funded launch) and Pro gets 400. Only a chat turn costs anything, 1 credit.
- Allotments: `V55:23`, `:33`; `AICreditService.java:74-75`. A new ledger starts at 100 regardless of plan (`:99-113`).
- Costs:
  - Turn = 1 (`MeeraSessionService.java:84`).
  - Tool calls are part of the turn; there is no separate charge.
  - Voice speak and transcribe = 0 (BR-1).
  - During an unlimited window, turns cost nothing but still count toward the 500 a day (`AICreditService.java:141-147`).
- If a turn fails, it is refunded once (`AICreditService.java:220-268`).

### BR-14: NOT BUILT. Brands cannot buy more AI credits.
- git grep finds no brand credit-pack route, catalogue or order.
- At zero credits, the brand gets 402 `CREDITS_EXHAUSTED` (`AICreditService.java:151-154`). The chat panel then shows the "Fund your first campaign / I'm back on the 1st" paywall, whose button starts `request_payment` (`MeeraChatPanel.tsx:1097`, `meera-copy.ts:104-108`). There is no upgrade or top-up option.

### BR-15: NO. The loyalty bonus does not stack on Pro, and a plan change wipes it.
- Only one column, `monthlyAllotment`, holds both the plan allotment and the bonus (`BrandAiCredit.java:22-23`). `applyEscrowFundedReset` writes 150 into it once (`AICreditService.java:278-281`, gated on `firstCampaignAt == null`). `applyPlanAllotment` overwrites it with 100 or 400 (`:294-298`).
- That overwrite runs on every subscription webhook (`SubscriptionService.java:620`, `:718`), admin grant (`:494`), dunning halt (`SubscriptionDunningJob.java:167`) and lapsed cancel (`SubscriptionService.java:834`).
  - Upgrade: allotment becomes 400, not 450.
  - Downgrade: 100. Because `firstCampaignAt` is already set, the bonus can never be earned again.
- This is ticket C2 in `task-subscription-remaining-0915.md`, not yet built.

### BR-16: YES. Wallet money and AI credits are separate ledgers.
- `AICreditService` writes only through `BrandAiCreditRepository` (`AICreditService.java:90-96`). Its only callers are the Meera session, `ConfirmLaunchExecutor`, the two jobs and `SubscriptionService` (git grep of callers). No wallet or ledger service calls it.
- The Pro subscription is charged by Razorpay directly, not from the wallet (`SubscriptionService.java:350-364`).
- The one link between them is a side effect, not a transfer: moving money into escrow at launch resets credits and opens the unlimited window (`ConfirmLaunchExecutor.java:380-381`).

---

## 1E. Does it work?

### BR-17: PARTLY. Every step is built and unit-tested with mocks. None has ever run live, and several have defects.

| Step | Built | Tested | Live |
|---|---|---|---|
| Sidebar "Billing" | `src/components/brand/brand-layout.tsx:140` → `src/App.tsx:414` | `brand-layout-billing-nav.test.tsx` | the entry exists; 0 subscription rows in prod |
| Plan page | `brand-billing-settings.tsx`; `GET /billing/plan` lazily creates a Free row (`BillingController.java:86-93`) | `BillingControllerUsagePeriodTest` | page renders; price shown ×100 (BR-9) |
| Upgrade | `brand-billing-settings.tsx:259-263` → `POST /billing/checkout`, OWNER/ADMIN only (`BillingController.java:180-190`) → `SubscriptionService.java:308-374` | `SubscriptionServiceTest` (19 tests) | never |
| Razorpay checkout | `createSubscription` hosted `short_url`, `notes.workspaceId` (`SubscriptionService.java:358-363`); no `callback_url`, so the brand is not sent back to Influora | `RazorpayClientTest` | never; refuses with 503 if the webhook secret is missing (`:328-334`) |
| Webhook | HMAC check (`RazorpayWebhookController.java:91-95`); `activated`/`charged` → ACTIVE (`:126-129`); per-delivery idempotency (`:328`) | `RazorpayWebhookControllerTest` (8 subscription tests; `SubscriptionService` mocked) | never |
| Pro row | `applySubscriptionWebhookUpdate` (`SubscriptionService.java:572-719`); an unknown Razorpay plan id **defaults to Pro** (`:837-848`) | `SubscriptionServiceTest` | never |
| Pro limits apply | `getActivePlanForWorkspace` (`:217-223`), read by all gates | `PlanGateWiringTest`, `EntitlementServiceTest` | never; credits not refilled on upgrade (BR-4) |

- None of these tests are Testcontainers tests. The `SubscriptionServiceTest` / webhook tests mock the repository and service layers.

### BR-18: NO. If the webhook never arrives, the brand stays on Free with no reconciliation.
- Local state changes only through `applySubscriptionWebhookUpdate`. `RazorpayClient` has no fetch-subscription method (its public methods are `fetchOrder`, `createPlan`, `createSubscription`, `cancelSubscription`: `RazorpayClient.java:133`, `:160`, `:199`, `:242`).
- `SubscriptionRenewalResetJob` only extends ACTIVE rows whose period has lapsed (`SubscriptionRenewalResetJob.java:118-126`). The brand's Free row matches that, so the job **extends it as Free**.
- A second Upgrade click is allowed, because only ACTIVE+Pro is refused (`SubscriptionService.java:342-348`). It creates a second Razorpay subscription.
- The only recovery is an admin comp. That is audited but does not record the payment.

### BR-19: PARTLY. Every state is handled. Pro stops at the first failed charge, and a cancellation sends no notice.
- **Renewal:** `subscription.charged` → ACTIVE plus the new period plus an invoice and email (`RazorpayWebhookController.java:128-129`). Credits are not reset on renewal, only on the 1st (BR-7). If the webhook is missed, the safety net extends by the last cycle length and writes an audit row (`SubscriptionRenewalResetJob.java:171-203`).
- **Failed renewal:** `subscription.pending` → PAST_DUE plus a payment-failed email (`RazorpayWebhookController.java:132-133`, `:370-372`). **Pro features stop immediately**, because PAST_DUE is not ACTIVE (`SubscriptionService.java:217-223`). The 7-day "grace" (`SubscriptionDunningJob.java:80`, `:125-139`) only decides when the status becomes HALTED and a second email goes out (`:155-211`). While PAST_DUE or HALTED, the page shows Free with an Upgrade button (`brand-billing-settings.tsx:321`), and checkout lets the brand start a **second subscription** while Razorpay is still retrying the first (`SubscriptionService.java:342-348`). That risks a double charge.
- **Cancellation:** `POST /billing/cancel`, OWNER/ADMIN only, cancels at cycle end and sets `cancelAtPeriodEnd` (`SubscriptionService.java:381-410`). Pro stays on until `subscription.cancelled` (`RazorpayWebhookController.java:147-148`), or until the safety-net job marks the lapsed row CANCELLED (`SubscriptionRenewalResetJob.java:219-234`). **No email** goes to the brand and nothing alerts an admin (no cancellation event in `NotificationListener.java`; only halted and payment-failed at `:551`, `:573`).
- **Doc check:** pricing's "keep Pro through the end of your current billing period" (`pricing.tsx:361`) matches the code.

### BR-20: PARTLY. An admin can see, comp and extend, and each action is audited. An admin cannot cancel, and a comp's expiry is never enforced.
- **See:** `GET /admin/billing/subscriptions` and `/metrics`, SUPER_ADMIN with MFA (`api/web/AdminBillingController.java:104-117`, `api/service/admin/AdminBillingService.java:85-88`). It lists only rows that exist, which is 0 in prod until V20260912120000 runs.
- **Comp / override:** `POST /admin/billing/comp`, `/override` (`AdminBillingController.java:119-133`), audited as `TIER_ADJUST` / `BUDGET_OVERRIDE` with before and after snapshots (`AdminBillingService.java:277-283`). FE: `src/admin/components/billing/BillingConsole.tsx:179-200`, `:317-349`. Refused for a workspace that already has a Razorpay subscription (`SubscriptionService.java:450-456`).
- **Extend:** only by comping again with a new `expiresAt`.
- **Cancel:** no endpoint (the controller has 4 routes). An admin can override to FREE on a comp row, but cannot touch a paid subscription.
- **Comp expiry is never enforced.** `compExpiresAt` is only stored and displayed. git grep shows nothing in `src/main` reads it for a decision. When a comp's period ends (`SubscriptionService.java:459`), `SubscriptionRenewalResetJob` treats it as a lapsed ACTIVE row and **extends it forever** (`:118-126`, `:171-190`). Lane A4 ("comp with an expiry") cannot expire in code.

### BR-21: PARTLY. An AGENCY workspace runs on Free by fallback, can upgrade, never gets a credit reset, and admins cannot comp it.
- AGENCY is a BRAND workspace whose type was changed at onboarding (`api/service/OnboardingService.java:56-65`).
- **Plan:** there is no row for the 4 older agencies (the backfill covers BRAND only: `mig/V20260912120000__backfill_free_subscriptions.sql:65`). `getActivePlanForWorkspace` falls back to Free, so every Free limit and the 10% fee apply.
- **Visiting the billing page creates a row:** `requireBrandWorkspace` does not check type (`api/service/BrandContextService.java:72-110`), so `GET /billing/plan` lazily creates a Free row for an AGENCY (`BillingController.java:90`). This contradicts the BRAND-only guards at `AuthService.java:234-236` and in the backfill.
- **Credits:** a ledger is created at 100 on the first turn and **never reset**, because `AICreditResetJob` loads BRAND ids only (`AICreditResetJob.java:85`).
- **Upgrade:** yes. Checkout has no type check (`BillingController.java:184-188`).
- **Admin:** comp is refused, `NOT_A_BRAND_WORKSPACE` (`AdminBillingService.java:263-268`). The admin search filters to BRAND names (`:99`).

---

## Part 4. Summary

### 1. Verdicts
| ID | Verdict | ID | Verdict | ID | Verdict |
|---|---|---|---|---|---|
| BR-1 | PARTLY | BR-8 | PARTLY | BR-15 | NO |
| BR-2 | NO | BR-9 | PARTLY | BR-16 | YES |
| BR-3 | PARTLY | BR-10 | PARTLY | BR-17 | PARTLY |
| BR-4 | NO | BR-11 | PARTLY | BR-18 | NO |
| BR-5 | PARTLY | BR-12 | NO | BR-19 | PARTLY |
| BR-6 | PARTLY | BR-13 | PARTLY | BR-20 | PARTLY |
| BR-7 | PARTLY | BR-14 | NOT BUILT | BR-21 | PARTLY |

Totals: YES 1, NO 5, PARTLY 14, NOT BUILT 1, UNKNOWN 0.

### 2. Blockers to charging money
1. **Brand pays, gets no AI.** Upgrading does not refill credits. The webhook sets only `monthlyAllotment` (`AICreditService.java:294-298`), and `creditsRemaining` refills on the 1st. A brand at 0 credits pays ₹4,999 and waits up to a month. A Pro brand's first funded launch also cuts its allotment to 150 (`:278-282`).
2. **Brand pays, stays on Free.** Pro is granted only by the webhook, with no reconciliation (BR-18). This also depends on Lane A2: real Razorpay keys and webhook secret on the VPS, never proven for subscriptions.
3. **Pro given away without payment.** Comp expiry is never enforced, so the renewal safety net extends comps forever (BR-20). Running Lane A4 today turns 27 time-limited comps into permanent free Pro.
4. **Brand charged twice.** A PAST_DUE or HALTED brand is shown Upgrade and can open a second Razorpay subscription while the first is still retrying (`SubscriptionService.java:342-348`; BR-19).
5. **Wrong numbers on money screens** (does not block taking money, but hurts trust in it):
   - The billing page shows Pro as ₹4,99,900/month, and invoice amounts ×100 (BR-9).
   - Meera quotes a 15% fee.
   - The brand-fee endpoint tells Pro brands 10% (BR-10).

### 3. New work found (not covered by existing Lane A-E tickets)
| # | Gap | Suggested owner |
|---|---|---|
| N1 | On Free→Pro (and on renewal, if anniversary credits are wanted), set `creditsRemaining` to the new allotment, not only `monthlyAllotment`; add a real-DB test | Vikram |
| N2 | Subscription reconciliation: fetch the Razorpay subscription on return from checkout and in a daily job; set `callback_url` | Vikram |
| N3 | Enforce comp expiry: revert a comp to Free at `compExpiresAt`; exclude comp rows from `SubscriptionRenewalResetJob`. **Must land before Lane A4** | Vikram |
| N4 | Refuse or redirect checkout when the row is Pro PAST_DUE/HALTED; show retry-payment or cancel instead of Upgrade | Vikram (BE), Ananya (FE) |
| N5 | Ruling: should Pro stay on during the 7-day dunning grace? Code drops to Free at the first failed charge | Swapnil, then Vikram |
| N6 | Paise shown as rupees on `brand-billing-settings.tsx:377` and `:683` | Ananya |
| N7 | `SubscriptionRenewalResetJob` should skip Free / non-Razorpay rows (Free period drift, extra credit reset, monthly money-audit noise for every brand) | Vikram |
| N8 | `applyEscrowFundedReset` overwrites a Pro allotment with 150: add to C2's scope | Vikram |
| N9 | Plan-blind fee display: `BrandPlatformFeeService` 10% copy for Pro; Meera `request_payment` quotes `PLATFORM_FEE_PERCENT` 15%. Needs a ruling on which number is real, then a fix | Rohan + Swapnil (ruling), Vikram |
| N10 | Admin cancel / end-comp action, and a cancellation email to the brand plus an admin alert | Vikram (BE), Ananya (admin FE) |
| N11 | Zero-credit paywall is wrong for Pro and already-funded brands, and has no upgrade path: fold into D1 | Ananya, Nisha (copy) |
| N12 | Pricing page says export and templates are "coming soon"; both are built and Pro-gated | Tejas / Nisha (copy), Ananya |
| N13 | `requireBrandWorkspace` does not check type, so `/billing/plan` lazily creates rows for AGENCY: add to B1/C5 scope | Vikram |
| N14 | `resolvePlanForWebhook` maps an unknown Razorpay plan id to Pro: refuse instead | Vikram |
| N15 | GST ruling: code treats ₹4,999 as GST-inclusive (net about ₹4,236); confirm or change to ₹4,999 + GST | Rohan + Swapnil |
| N16 | Brand voice (speak/transcribe) costs no credits on any plan: decide whether to meter it | Ash (cost review), Swapnil |

Already ticketed, not repeated above: saved-creator cap (C1), loyalty-column split (C2), Razorpay blank defaults (C3), dead tracked-creator meter (C4), agency conversion (C5), structured 402 + `<UpgradeGate>` (D1).
