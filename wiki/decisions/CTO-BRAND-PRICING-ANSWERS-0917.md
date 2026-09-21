# CTO Answers: Brand-Side Pricing and Charging (36 questions)

**From:** Priya (CTO) **To:** Swapnil (CEO) **Date:** 2026-09-17 **Branch read:** `feat/meera-creator-phase-e` (read-only; billing/metrics files mid-edit by another session, read as-is)

## EXECUTIVE SUMMARY
1. **The brand commission invoice claims more money than we took.** It prints "Total = fee + 18% GST, Status PAID" (`CommissionInvoicePdfService.java:139-141`), but the wallet was debited the fee alone (`BrandCampaignFeeService.java:205-215`). The GST is never collected, and every invoice says 18% of the fee more than the ledger shows.
2. **The fee a brand sees is not the fee they pay.** The server copy hardcodes "10%" (`BrandPlatformFeeService.java:35-36`) and ignores the Pro rate of 7% (`BrandCampaignFeeService.java:108-114`). The Publish screen shows no fee at all (`campaign-form.tsx`, 0 matches for "fee"). A campaign **created** straight as ACTIVE pays no fee and has no funds secured (`CampaignService.java:146-230`).
3. **Tax and custody wording that the code does not back.** Invoices print "TCS @ 1%" that is never withheld (`CampaignServiceInvoiceService.java:207-209`). TDS is only a number an admin types in (`AdminFinanceService.java:179-184`). Marketing and policy copy says an "RBI-authorized Payment Aggregator" holds the funds, but the code keeps them in our own internal ledger wallets (`LedgerEscrowBackend.java:12-18, 50-69`).
**Verdict: No. A brand cannot trace every rupee they see to a source.** Some figures are estimates, some are hardcoded, some are never charged, and one (invoice GST) is not money that moved.

> Path shorthand: Java = `influora-api/src/main/java/com/influora/...`; Python = `influora-ai/app/...`; FE = `src/...`. The fee config values below are **Flyway seed defaults**, stored in one DB row that admins can edit. `application.yml` values are **defaults**. The live Utho stack runs from a compose file that is not in this repo, so I cannot see any live config or live row value. Every "live value" is **UNVERIFIED**.

---

## A — Where does the AI get its numbers?

**Q1. Every input Meera reads.**
Bad news first: two of these inputs are model-supplied and can be invented: `product_price` (a required field in the tool call) and the product price stored on a draft.
- **Brand typed:** chat text, stored as `AiMessage` (`MeeraSessionService.java:391-402`).
- **Scraped:** `BrandProfile` product catalog and niche tags, whose only writer is the analyze-site callback (`BrandProfile.java:165-175`). It can be wrong or empty.
- **Our DB:** workspace, templates, campaigns, collaborations, escrow holds, deliverable metrics, UTM rows, credits and creator metrics (`MeeraContextService.java:98-110`), plus the cross-tenant rate band (`BrandContextAssembler.java:301`).
- **Model:** `product_price` is required input to `calculate_budget` (`schemas.py:119,131`) and is persisted by `create_campaign` (`CreateCampaignExecutor.java:216,292`). This is model-supplied data that can be invented.
→ Brand sees: a confident assistant with no label saying which of its facts came from them, from a scrape, or from the model.

**Q2. Product price after the analyze_site outage.**
Bad news: the model **must** send a `product_price` to call `calculate_budget` (`schemas.py:131`), so when no catalog exists it has to make one up. That price is then saved to `campaign_intents` (`CreateCampaignExecutor.java:292`).
- **What still guards the brand today:** the server labels any price not matched to the scraped catalog as "inferred" (`CalculateBudgetExecutor.java:304-337`). On the no-band path, a prompt instruction forbids saying any rupee figure (`CalculateBudgetExecutor.java:175-182`; `persona.py:136-155`).
- **What does not guard it:** there is no output-side price filter on brand chat. `has_invented_price` exists but only the trendspark and creator-suggestion routes call it (`validators.py:104`; `trendspark.py:151`; `creator_suggestion.py:193`).
- **Latent risk:** if `request_payment` is ever re-enabled, the charge is `product_price × creator_count + fee` (`AmountDerivationService.java:67-72`). That is the same invented-price multiplication, now as a real charge.
→ Brand sees: no price, if the model obeys the prompt. Nothing on the server stops a spoken invented price.

**Q3. Completed collaborations per niche; is the k-anon floor met anywhere?**
**UNVERIFIED for production row counts.** I cannot see the Utho database from source. The repo seeds zero collaborations: no migration inserts into `collaborations`, and `DevSeedCreatorsRunner` is `@Profile("dev")` and seeds only profiles (`DevSeedCreatorsRunner.java:44,102`). The code decides the outcome anyway:
- **The brand has no niche.** The band needs brand `niche_tags`, whose only writer is analyze-site (`BrandProfile.java:165-175`). With no tags it returns null before running any query (`CalculateBudgetExecutor.java:227-235`).
- **Match format is unverified.** Tags are free-text Gemini strings (`gemini.py:43,88-91`), matched exactly against creator `categories` (`CollaborationRepository.java:73`). Whether the two formats ever match is UNVERIFIED.
- **Floor needs one follower tier.** At least 5 distinct creators and 5 distinct workspaces, all in the same tier bucket (`BrandContextAssembler.java:415-432`).
- **"Completed" is not "money-settled".** COMPLETED means every deliverable is resolved, including REJECTED (`CollaborationLifecycleService.java:109-115, 283-285`). The band never checks for a RELEASED payment.
- **To close:** `SELECT` count of distinct creators and workspaces per category and tier over `status='COMPLETED'` on the Utho DB, plus a count of brand profiles with non-empty `niche_tags`.
→ Brand sees: almost certainly **never a suggested creator price**. Meera asks "what do you usually pay?"

**Q4. With a null band, what rupee figure appears before the brand types a budget?**
From Meera: none by design (`CalculateBudgetExecutor.java:141-144, 246-256`). Rupee figures still reach the brand through three other paths:
- **Meera, after the brand gives numbers:** the HYPE flow says "₹rate × slots = ~₹total" (`persona.py:259-262`).
- **Discovery "Avg Rate":** the midpoint of the creator's self-declared min and max (`CreatorMapper.java:44,101-110`). It shows **₹0** when unset (`creator-discovery.tsx:1518,1594`).
- **Campaign detail "Platform Fee (X%, est.)":** calculated in the browser (`brand-campaign-detail.tsx:2066-2068`).
→ Brand sees: no AI rate, but a non-AI "Avg Rate" (sometimes ₹0) and a fee estimate.

**Q5. Does Meera use training-data knowledge of Indian influencer costs?**
Nothing in the tooling can stop it. Only prompt text forbids it: "not from anything you know about the market" (`persona.py:140-141`) and "Never state a rupee budget ... from your own head" (`persona.py:88-93`). There is no output validator on brand chat (see Q2).
Separately, **hardcoded market tables live in code.** `RateEstimationService` holds rupee bands per tier, e.g. MICRO 5,000-25,000 (`RateEstimationService.java:35-42`). Those bands feed the **creator** agent's default floors (`CreatorAgentPreferencesService.java:136-141`), not brand Meera.
→ Brand sees: whatever the model says, filtered only by instruction-following.

**Q6. Followers, engagement, reach: source, freshness, self-reported?**
Bad news: `total_followers` adds up the latest snapshot per platform **without checking its source** (`PlatformStatsAggregationJob.java:151-167`). That total mixes Meta-verified numbers with `CREATOR_REPORTED` numbers from `declarePlatform` (`PortfolioService.java:443-447,496`). The mixed total drives the rate-band tier (`BrandContextAssembler.java:415-419`) and discovery.
- **Freshness:** the rollup runs daily at 03:45 (`PlatformStatsAggregationJob.java:85`). The per-platform verified badge is honest (`PlatformStatsAggregationJob.java:190-197`).
- **Campaign metrics** carry an explicit `source` of `CREATOR_REPORTED` or `PLATFORM_VERIFIED` (`DeliverableMetric.java:33-36`).
→ Brand sees: a follower total that can be partly typed by the creator, with no label on the total.

**Q7. Can the AI see another brand's prices, and can a small niche leak one rate?**
**Yes, it can leak.** The k-anon check counts distinct creators and workspaces (`BrandContextAssembler.java:421-432`). But the band returns **exact `min` and `max`, which are single real deals** (`:444-445`), and an exact `sampleSize` (`:454`).
- **Differencing:** the band is recomputed live on every call. A brand that asks before and after one competitor deal completes sees min, max or median move, which reveals that deal.
- **Domination:** rows are not deduplicated per workspace, so one workspace with many deals can drag the median.
- Only aggregates, never individual rows, reach the prompt (`CollaborationRepository.java:32-39`).
→ Brand sees: "₹X–₹Y across N creators". X and Y are each one competitor's actual contract value.

## B — What does a campaign cost the brand?

**Q8. Every rupee for one campaign, and the file that computes it.**
1. **Wallet top-up:** the exact amount, no gateway surcharge (`WalletTopUpService.java:99-168`). The `razorpay_absorbed_by_platform` flag is never read by money code (only `PlatformFeeAdminService.java:214,228`).
2. **Publish fee:** `budgetMax × brandFeeBps / 10000`, debited at go-live (`BrandCampaignFeeService.java:173-215`).
3. **GST on that fee:** **invoiced but not charged** (`CommissionInvoiceService.java:117`; the debit is fee only at `BrandCampaignFeeService.java:208`).
4. **Secured funds:** either the milestone amount or `budgetMax` for a campaign-level pool (`EscrowService.java:344-373`).
5. **Creator fee:** comes out of the brand's secured amount at release, so the brand does not pay it on top (`PlatformFeeService.java:79-99`).
→ Brand sees: top-up amount, then a publish debit, then secured-funds debits. The invoice total does not equal the sum of those debits.

**Q9. Percentage, flat, or tiered? Where configured? Changeable without deploy?**
- **Type:** percentage in basis points, one global row. Seeds: brand 1000 (`V42…sql`), creator 1500 (`V41…sql`).
- **Tiers:** Pro overrides with `plans.fee_bps` = 700 (`V55__seed_billing_plans.sql:32`; `BrandCampaignFeeService.java:116-122`).
- **Changeable live:** yes, by SUPER_ADMIN PUT, effective immediately (`PlatformFeeAdminService.java:120-125`).
- **Needs a deploy:** the brand copy "Platform fee (10%)" is a Java literal (`BrandPlatformFeeService.java:35-36`).
- **Third, stray source:** `PLATFORM_FEE_PERCENT` default 15.00 (`application.yml:407`), used only by the dormant `AmountDerivationService.java:68`. Live values UNVERIFIED.
→ Brand sees: "10%" copy, whatever the row actually says.

**Q10. GST on which leg? Supplier of record? On the invoice?**
- **Platform fee (both legs):** Influora is the supplier and 18% is added on top (`CommissionInvoiceService.java:50,117,192`; supplier block `CommissionInvoicePdfService.java:85-88`). It is **never collected** (see Summary 1).
- **Creator fee (Doc#2):** the creator is supplier, and the invoice has **no GST line even when the creator has a GSTIN** (`CampaignServiceInvoicePdfService.java:74,93-100`). The brand cannot claim input credit from it.
- **Subscriptions:** treated as GST-*inclusive*, back-calculated (`InvoiceService.java:254-255`). That is the opposite convention to commission invoices.
- **GSTIN guard:** prod refuses to boot on the placeholder GSTIN (`CompanyTaxStartupValidator.java:63-65`). Doc#2 missing-invoice: now has a durable retry marker plus a 5-minute retry job (`EscrowService.java:1430-1449`; `CampaignServiceInvoiceService.java:374-380`). The old "silently missing" finding is closed in code.
→ Brand sees: a commission invoice with GST it never paid, and a creator invoice with no GST at all.

**Q11. TDS on creator payouts: actual state.**
Not deducted by code. The only TDS is an **optional number an admin types** when recording a manual bank payout (`AdminFinanceService.java:156,179-184`). It is stored on `Payout.tds_amount` (`Payout.java:80-85`). The ledger debits the full `amount` whatever TDS was typed (`AdminFinanceService.java:216-226`).
**Also:** TCS 1% is computed and printed as a "deduction" but not withheld (`CampaignServiceInvoiceService.java:207-209`; `CampaignServiceInvoicePdfService.java:96-100`). The policy says payouts are "minus … applicable TDS" (`escrow-and-refund-policy.md:26`). "Ruled out pending a CA" is still true in practice.
→ Brand sees: "TCS @ 1% (ECO deduction)" on an invoice for money that was not deducted.

**Q12. Full total before committing money?**
**No.**
- **Publish in the form:** the review step shows no fee or total. The Publish button sits at `campaign-form.tsx:1693-1705`, and "fee" appears 0 times in the file.
- **Publish from the campaign menu:** a DRAFT gets a "Resume Campaign" item that debits the fee with no confirmation. The toast says "Campaign resumed" (`brand-campaign-detail.tsx:907-912, 1124-1129`).
- **Campaign detail page:** shows "Platform Fee (X%, est.)" from the global rate. It is not plan-aware, has no GST, and is computed in the browser (`brand-campaign-detail.tsx:2063-2074`; `BrandPlatformFeeService.java:53`).
- **Deal-room proposal:** shows "Total You Pay = budget + fee + 18% GST", which is not how we charge (`proposal-form.tsx:177-179, 336-352`).
→ Brand sees: the fee in rupees for the first time when the wallet balance drops.

**Q13. Minimum campaign value or per-creator fee?**
None. A campaign needs `budget.min > 0` (`CampaignValidator.java:35`). A deal needs `amount ≥ 0.01` (`DealDtos.java:102,126`), and a counter must be `≥ campaign.budgetMin` (`DealService.java:1685-1690`). The HYPE form allows a total of ₹0.01 (`brand-new-hype-campaign.tsx:216`).
A ₹1 campaign is valid; its publish fee is ₹0.10. The only floor anywhere is creator withdrawal, ₹500 (`WalletService.java:66,580`).
→ Brand sees: nothing stopping a ₹1 campaign.

**Q14. Do plans change the per-campaign charge? Is it live?**
Yes, one lever: an ACTIVE Pro subscription sets the brand fee to 700 bps (`BrandCampaignFeeService.java:108-122`). If plan lookup fails, the brand is charged 10% (`:123-147`). Credits and caps are non-monetary (`pricing.tsx:222-224`).
**But the brand-facing fee endpoint ignores the plan** (`BrandPlatformFeeService.java:53`), so a Pro brand is shown 10% and charged 7%. Whether any Pro subscription is live is UNVERIFIED; `razorpay_plan_id` is NULL in the seed (`V55…sql:32`).
→ Brand sees: Pro says "reduced fee", and every screen still shows the standard rate.

## C — When and how is money charged?

**Q15. When does money leave the brand?**
- **Bank → wallet:** at Razorpay checkout, credited only by webhook (`RazorpayWebhookController.java:190-197`).
- **Wallet → clearing:** when the brand secures funds (`EscrowService.java:213-335`). A milestone requires a fully signed contract (`:403-408`).
- **Wallet → revenue:** the publish fee, when PATCH moves the campaign to ACTIVE (`CampaignService.java:273-274, 376`).
- **Clearing → creator:** on deliverable approval (`EscrowService.java:729-767`).
- **Nothing moves** at campaign creation, creator acceptance, or delivery.
→ Brand sees: two debits (fee, secured funds) that happen at different moments, triggered by different buttons.

**Q16. Can the AI trigger a charge?**
Not today; re-verified. Four layers stop it:
1. **Not offered to the model:** `get_tool_schemas()` filters out commit-tier tools (`schemas.py:456-460`).
2. **Not in the token:** the minted scope is `SCOPE_DEFAULT`, without either tool (`OnBehalfTokenService.java:68-69,105`).
3. **Spring rejects it:** scope and OWNER/ADMIN checks (`MeeraInternalController.java:256-258,278-280`; `OnBehalfAuthResolver.java:195-207`).
4. **Canned decline:** a fixed refusal message if one is ever forwarded (`loop.py:55-68`).
Even if reached, `request_payment` only stages PENDING_CONFIRM (`MeeraInternalController.java:251-253`). Caveat: its amount logic is the invented-price multiply (Q2).
→ Brand sees: Meera redirects to the wallet or deal room.

**Q17. Where do secured funds sit, who controls them, what releases them?**
Bad news: in **our own internal ledger clearing wallet** (`LedgerEscrowBackend.java:50-69`), controlled by Influora code. Razorpay Route is not built (`LedgerEscrowBackend.java:19-29`).
- **Release:** brand approval (`EscrowService.java:729-767`), manual release (`:593-628`), or an admin dispute outcome (`:1247-1428`).
- **Campaign-level pool hold:** **cannot be released to any creator** (`EscrowService.java:649-657`).
- **Physical location:** where settled money sits (merchant account vs nodal) is UNVERIFIED from code. Closing it needs the Razorpay settlement config.
- **Copy contradiction:** copy says a licensed PA holds it (`how-it-works-brands.tsx:96`; `escrow-and-refund-policy.md:5,13`).
→ Brand sees: "held with an RBI-authorized Payment Aggregator". The code shows a ledger row.

**Q18. Creator never delivers: how does the brand get money back?**
Manual, through a dispute. No deadline job refunds anything (`job/` holds no escrow or refund job). A brand-callable refund route exists but deliberately has no UI (`EscrowController.java:138-151,152-166`). The real path is dispute → admin → `adminRefundForDispute` (`DisputeService.java:254`; `EscrowService.java:1286-1305`).
The publish fee is **never refunded** (`BrandCampaignFeeService.java:39-40`).
→ Brand sees: open a dispute and wait for an admin. The fee is gone either way.

**Q19. Cancelled halfway, 2 of 5 delivered.**
- **Keeps paying:** the 2 released milestones stay paid (`EscrowService.java:902-904`), and the fee on the full `budgetMax` is kept (`BrandCampaignFeeService.java:39-40`).
- **Stuck:** the 3 unreleased FUNDED holds stay FUNDED. Campaign CANCELLED triggers nothing (grep: `CampaignStatus.CANCELLED` appears only in `CampaignValidator.java:73`).
- **Can still be paid out:** the release guard checks *collaboration* cancellation, not campaign (`EscrowService.java:1795`). A later approval can still pay those creators.
- **Pool funding:** any campaign-level pool is stranded until an operator refunds it.
→ Brand sees: a cancelled campaign whose secured funds do not come back on their own.

**Q20. ₹1,000 top-up proven live? Payment captured but webhook never arrives?**
- **Live proof:** a live CREDITED ₹1,000 on 2026-09-13 is recorded in team memory, not in code, so it is UNVERIFIED here. To close: pull `order_TbUAbdHd2hVZ` from the Utho `wallet_top_ups` table and the two ledger legs.
- **Missed webhook:** code-verified **no fallback**. `confirmCredited` has one caller, the webhook (`RazorpayWebhookController.java:195`), and the code says so itself (`:187-188`).
- **Reconciliation is read-only:** it flags PENDING vs gateway "paid" as MISMATCH and moves no money (`AdminFinanceService.java:356-366, 442-486`). There is no poll job and no admin credit action.
→ Brand sees: money gone from the bank, wallet unchanged, and nothing on their side to fix it.

**Q21. Charges the brand doesn't see coming.**
- **The fee itself:** the Publish/Resume click charges a fee that no screen shows (Q12).
- **Fee on the maximum:** it is calculated on `budgetMax`, not on spend, and is non-refundable (`BrandCampaignFeeService.java:173,39-40`).
- **Pool funding:** a campaign-level pool (`budgetMax`) that milestones cannot use, so milestones need funding again (`EscrowService.java:253-257,649-657`).
- **AI credits:** consume credits, not rupees (`MeeraSessionService.java:84,388`).
- **Not found in code:** currency conversion, refund fees, gateway fees.
→ Brand sees: the fee in rupees for the first time on the debit, and possibly the same budget locked twice.

## D — Creator price vs what the creator receives

**Q22. Brand agrees ₹10,000: what does the creator get?**
The arithmetic below uses seed rates; live rates are UNVERIFIED.
- **Funding:** milestones must sum to ≤ ₹10,000 (`ContractService.java:279-285`). The brand funds ₹10,000.
- **At release:** fee = 10,000 × 1500 / 10000 = **₹1,500**, net **₹8,500** credited (`PlatformFeeService.java:50-56,79-99`; `LedgerEscrowBackend.java:76-96`).
- **Printed but not deducted:** TCS ₹100 (`CampaignServiceInvoiceService.java:209`). The creator-leg invoice shows ₹1,500 + ₹270 GST (`CommissionInvoiceService.java:192`) while only ₹1,500 was deducted.
- **Bank payout:** manual admin transfer; TDS only if typed (Q11).
- **Proposal form mislabel:** it tells the brand "Creator Payout: ₹10,000" (`proposal-form.tsx:338-340`).
→ Brand sees: "Creator Payout ₹10,000". The creator's wallet gets ₹8,500.

**Q23. Who sets the price? Can the brand pay below the listed rate?**
Negotiation sets it. The last proposal or counter overwrites `agreed_rate` (`DealService.java:279,1305`). Nothing checks the amount against the creator's rate card; only campaign `budgetMin` (`DealService.java:1680-1691`) and the remaining budget at accept (`:1141,1760-1790`) apply.
So **yes, the brand can pay less than the listed rate**. The AI does not set it on the brand side; the creator-side agent works from floors (`CreatorAgentPreferencesService.java:119-142`).
Odd rule: a per-creator counter must be at least the *whole campaign's* minimum budget.
→ Brand sees: any amount it offers is accepted by the system.

**Q24. Per-deliverable rate cards: used by pricing?**
Creators can publish rows (id, label, min, max) (`PortfolioDtos.java:82-83`; `PortfolioService.java:326-334`). Pricing **ignores them**. Only the lowest min and highest max across all rows are copied to the profile (`PortfolioService.java:312-313,1333-1354`).
Discovery then averages those two into "Avg Rate" (`CreatorMapper.java:101-110`). A story's min and a video's max become one fictional price.
→ Brand sees: "Avg Rate" that is not any real deliverable's price.

**Q25. Is `agreed_rate` still presented per-reel anywhere?**
- **Brand AI:** fixed; it is forbidden to present it per reel (`CalculateBudgetExecutor.java:278-281`; `persona.py:133-136,249-254`).
- **Creator agent:** **still does it.** The last completed `agreed_rate` becomes the reel, story and post floor alike (`CreatorAgentPreferencesService.java:112-128`).
- **Discovery invite:** pre-fills a one-REEL deliverable with "Avg Rate" as the budget (`creator-discovery.tsx:549-552`).
- **Emails and invoices:** UNVERIFIED; I did not open email templates.
→ Brand sees: a creator agent that may counter using a whole-deal figure as a per-reel floor.

**Q26. Creator raises the rate after agreement: which wins?**
The agreed number wins. Rate card edits only touch profile `rateMin/rateMax` (`PortfolioService.java:303-314`). Counters are blocked once a contract exists (`Collaboration.java:358-365`; `DealService.java:560-565`), and milestones are capped at `agreed_rate` (`ContractService.java:497-501`).
Before a contract, any counter silently replaces the number (`DealService.java:1305`).
→ Brand sees: price locked once the contract is generated. Before that, the latest counter is the price.

## E — Can a brand trust a number?

**Q27. Which figures are verifiable, and which are unlabelled estimates?**
- **Verifiable:** wallet balance and transactions (ledger), secured amounts (`EscrowService.java:344-373`), agreed rate, publish fee debit.
- **Unlabelled or wrong:**
  - "Avg Rate" (a midpoint, or ₹0)
  - "Creator Payout" (gross, not net)
  - "Total You Pay" (fee plus GST we don't charge, on the wrong base)
  - Invoice GST and TCS lines
  - Copy saying "Platform fee (10%)"
  - Follower totals mixing typed and verified numbers
- **Labelled estimate:** the detail-page fee, "est." (`brand-campaign-detail.tsx:2066-2071`).
→ Brand sees: ledger numbers are real; almost every derived money label around them is not.

**Q28. Self-reported performance/ROI, labelled?**
Yes, and labelled per campaign: "Creator-reported, not platform-verified" versus "Verified by Instagram" (`brand-campaign-detail.tsx:1790-1810`), backed by `DeliverableMetric.source` (`DeliverableMetric.java:33-36`).
Meera's outcome digest uses released spend only (`BrandContextAssembler.java:255-259`). ROI inputs from UTM/Shopify attribution: UNVERIFIED (I did not open the attribution writers). The about page also admits performance is creator-reported (`about.tsx:62`).
→ Brand sees: an honest verified/self-reported banner on performance.

**Q29. Dispute record: quoted, agreed, charged, paid.**
- **Quoted by AI:** assistant replies are saved (`MeeraSessionService.java:640-650`), but the `calculate_budget` audit row stores **no number** (`CalculateBudgetExecutor.java:188-202`).
- **Agreed:** `collaborations.agreed_rate` plus deal messages with amount metadata (`DealMessage.java:74-75`), and contracts and milestones.
- **Charged / paid:** idempotent ledger rows, escrow holds, and commission/service invoices (`BrandCampaignFeeService.java:200`; `EscrowService.java:480`).
- **Not recorded:** a fee *shown* to the brand is never stored.
- **Time to pull:** UNVERIFIED. I found no brand statement export, so it is manual SQL across about 6 tables.
→ Brand sees: we can prove what moved, not what we showed them.

**Q30. Numbers shown that can't be reproduced later.**
- **Rate band quote:** recomputed live and never snapshotted (`BrandContextAssembler.java:411-455`).
- **Detail-page fee estimate and proposal "Total You Pay":** computed in the browser from a rate that may have changed since.
- **"Avg Rate":** overwritten whenever the rate card changes (`PortfolioService.java:312-313`).
- **Invoice GST total:** reproducible from the invoice row, but it matches no ledger movement.
→ Brand sees: pre-commit figures that we cannot re-show them after a dispute.

## F — What breaks

**Q31. Most likely overcharge, and most likely undercharge.**
- **Overcharge:** the publish fee on the **full `budgetMax`**, non-refundable, charged via the form or a "Resume" click that shows no fee (`BrandCampaignFeeService.java:173,39-40`; `brand-campaign-detail.tsx:1124-1129`). A campaign that spends 40% or is cancelled still pays 10% of the maximum. Close second: the campaign-level pool funded at `budgetMax` cannot pay creators, so milestones are funded again (`EscrowService.java:253-257,649-657`).
- **Undercharge:** **create with `status=ACTIVE` skips both the funded-secure check and the fee** (`CampaignService.java:146-230`; checks exist only in `update`, `:369,376`). The HYPE form always does this (`brand-new-hype-campaign.tsx:223,273`), and campaign-form "Publish" does it on a new campaign (`campaign-form.tsx:662-663,1693-1696`). The conformance gate covers `update` and `confirm_launch` only (`BrandFeePublishPathConformanceTest.java`).
→ Brand sees: a fee they didn't expect, or no fee and no secured funds on a campaign that says "Full budget is secured at launch" (`brand-new-hype-campaign.tsx:623`).

**Q32. Floating point or rounding leaks.**
- **Ledger:** `BigDecimal`, 2 decimal places, HALF_UP (`BrandCampaignFeeService.java:182-185`; `PlatformFeeService.java:51-55`). The GST split absorbs its odd paisa (`GstSplitUtil.java:32-36`).
- **Floats:**
  - Reconciliation uses `double` with a 0.01 epsilon (`AdminFinanceService.java:85,443`)
  - `feePercent` is a double (`BrandPlatformFeeService.java:54-57`)
  - Browser fee/GST math (`proposal-form.tsx:177-179`; `brand-campaign-detail.tsx:2068`)
  - Rate estimates (`RateEstimationService.java:89-142`)
- **Rounding bias:** HALF_UP per posting favours the platform by at most ₹0.005 per transaction.
- **Crash, not leak:** a top-up with more than 2 decimals throws (`RazorpayClient.java:107`).
→ Brand sees: a fee preview that can differ from the debit by a rupee or so, from browser rounding.

**Q33. Pricing paths never exercised by a real payment on live.**
UNVERIFIED from code; it needs Utho ledger queries. The only real-money proof on record is the ₹1,000 top-up (team memory, 2026-09-13). I have no evidence of a live real-money run of:
- the publish fee debit
- escrow fund and release, including the creator fee
- Doc#2/Doc#3 invoices
- dispute refund or split
- manual payout with TDS
- Pro subscription at 7%
To close: `SELECT type, COUNT(*) FROM wallet_transactions GROUP BY type` on Utho, excluding test workspaces.
→ Brand sees: they would be the first real payer on almost every money path.

**Q34. Marketing and pricing claims the code does not back.**
- **Fee before live:** "your workspace's current rate is shown on the campaign before you take it live" (`pricing.tsx:366`). The form shows none, and the detail page is not plan-aware.
- **Funds held by a PA:** "deposited with a licensed, RBI-authorized Payment Aggregator" (`how-it-works-brands.tsx:96`; policy `:5,13`). The code uses an internal ledger.
- **HYPE refunds:** "Unfilled slots are refunded" (`brand-new-hype-campaign.tsx:623`; `persona.py:261`). No job exists.
- **Rate cards:** "shown … as a rate card, so a brand sees the price" (`landing.tsx:86`). Brands see a midpoint.
- **TDS:** "minus … applicable TDS" (policy `:26`). Nothing is deducted.
→ Brand sees: promises about custody, refunds and fee visibility that the code does not keep.

**Q35. Raise the platform fee tomorrow: who is affected?**
- **Brand fee:** read at publish (`BrandCampaignFeeService.java:181`), so ACTIVE campaigns are untouched and every DRAFT pays the new rate. The estimate the brand saw earlier is silently stale.
- **Creator fee:** read **at release** (`PlatformFeeService.java:79`), so **every already-signed, unreleased milestone** pays the new rate. Contract, milestone and hold store no fee (grep: no fee field in `Contract`, `PaymentMilestone`, `EscrowHold`).
- **Effective time:** `effectiveAt = now` (`PlatformFeeAdminService.java:120`).
→ Brand sees: no change on live campaigns. Their creators get less than they agreed to, which becomes a brand problem.

**Q36. The one thing that would embarrass us in a finance audit.**
Our brand commission tax invoice states "Total: fee + 18% GST — PAID" (`CommissionInvoicePdfService.java:139-141`; `CommissionInvoiceService.java:117-128`). Our ledger debited only the fee (`BrandCampaignFeeService.java:205-215`). Every commission invoice therefore declares GST we never collected, marked PAID, under our GSTIN.
→ Brand sees: an invoice for more money than left their wallet.
