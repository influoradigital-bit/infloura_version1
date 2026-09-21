# India pricing proposals: Tejas vs Rohan (chair's report)

**Date:** 2026-09-19. **Chair:** neutral. **Market:** India only. All prices are in Rs, GST is 18%, and every customer is an Indian creator or an Indian SMB/D2C brand. Global platforms are not used as anchors. USD figures appear only where an Indian vendor publishes no Rs price, and they are marked 3P.
**Inputs:** India research for the brand side and the creator side (sources listed below), the CEO's latest direction (see `wiki/reports/pricing-meeting-2026-09-19.md`), and two proposals followed by one round of cross-checks.
**Evidence labels:** PAGE means read on the vendor's own page. SELF means the vendor's help or blog pages. 3P means a third party. UNVERIFIED means no public figure was found. **ESTIMATE** means a modelled number, not a measured one.
**Copy rules:** customer copy must never say "escrow" and must never claim that Influora or any regulator holds an RBI PA or escrow licence (F-0851). The approved wording is "payment protection" or "held for the campaign".

**Influora today (verified in code on 2026-09-19):**
- Creator commission is 15% at payout.
- Brand fee is charged at go-live on the committed budget: 10% on Free and 7% on Pro.
- Pro costs Rs 4,999/month and includes 400 credits and 5 seats.
- Free brands get 100 credits, rising to 150 after their first funded campaign.
- 1 turn costs 1 credit. AI cost is about Rs 0.56-0.65 per credit.
- Creator Meera is free, has no tools yet, and is capped at $0.75/month.
- Creator plans and credit packs are not built.
- Influora absorbs the Razorpay charges.

**Two code blockers, both re-checked by the chair today:**
- `influora-api/src/main/resources/application.yml:407` still reads `platform-fee-percent: ${PLATFORM_FEE_PERCENT:15.00}`.
- `BrandPlatformFeeService.java:36` still hard-codes the label `"Platform fee (10%) — charged only when your campaign goes live."`

No percentage can be published until both are fixed.

---

## 1. India market snapshot

### 1a. Brand side: what Indian platforms charge brands

| Platform | Pricing | Who pays | Evidence | Source |
|---|---|---|---|---|
| Influish | 0% commission on collabs. Free Lite plan. Paid brand plans are priced only in-app, so no public Rs figure exists (/pricing returns 404). Managed campaign price is shown before activation. | Brand pays a subscription or a managed campaign fee. No commission on either side. | PAGE | https://influish.com/ |
| OPA (opa.marketing) | Instagram nano costs Rs 1,000 / 900 / 800 / 700 per influencer, depending on follower band. YouTube costs Rs 1,500 / 1,300 / 1,200 / 1,000. Product trials cost Rs 400-250. All prices exclude GST. Minimum 100 influencers. No subscription or commission. | Brand pays an all-in rate per influencer. | PAGE | https://www.opa.marketing/ |
| Zefmo One | Rs 9,999/month including GST (introductory). Discovery across 4M+ profiles. | Brand or agency subscription | 3P | https://mediabrief.com/zefmo-unveils-zefmo-one/ |
| ClanConnect | Prepaid packages of Rs 50,000 to Rs 2.5 lakh per campaign, covering creators, CPV, QC and reporting. | Brand prepays | 3P | https://mediabrief.com/clanconnect-rolls-out-prepaid-influencer-marketing-packages-to-simplify-campaigns-for-brands-of-all-sizes/ |
| Qoruz | Free search plan. Premium and Enterprise require a demo. A third party quotes $600/month; no Rs price is published. | Brand or agency | PAGE | https://qoruz.com/pricing |
| Wobb | Startup, Growth and Enterprise tiers with a 14-day trial. A third party quotes $99 / $299 / $499 per month. Managed service costs 10% of spend. No Rs figure verified. | Brand subscription, plus 10% on managed campaigns | 3P | https://wobb.ai/pricing |
| Kofluence | No public Rs pricing. Negotiated creator fees plus CPC/CPA/CPM/CPV models. | Brand pays creator fees plus a margin | PAGE | https://www.kofluence.com/influencer-marketing-platform-faqs/ |
| Hobo.Video | Custom quotes only. Its blog lists nano Rs 2k-10k, micro Rs 10k-60k, macro Rs 60k-3L and mega Rs 3-25L per post. | Brand (creator fee plus margin) | 3P | https://hobo.video/blog/the-real-cost-of-influencer-marketing-in-india-2025/ |
| One Impression | Custom pricing only | Brand | UNVERIFIED | https://www.capterra.com/p/10019193/One-Impression/ |
| Good Creator Co (Plixxo/Winkl) | No public brand pricing. Winkl offers up to Rs 30L of revenue-based financing. | Brand (managed) | UNVERIFIED | https://apai.winkl.co/c/get-up-to-rs-30-lakh-for-your-influencer-marketing-efforts-with-fast-and-flexible-revenue-based-financing |
| Chtrbox | Free campaign launch. No public Rs fee. | Brand (managed) | UNVERIFIED | https://brands.chtrbox.com/ |
| Grynow (agency) | Rs 50k-5L per project. Rs 1.5-6L/month retainer, or 15-25% of creator spend. | Brand | SELF | https://www.grynow.in/blog/influencer-marketing-cost-in-india.html |
| Indian agencies (range) | Rs 50k-5L per campaign. Rs 1.5-6L/month retainer. 15-25% commission. Self-serve platforms charge Rs 25k-3L/month or 8-15% of spend. | Brand | 3P | https://upgrowth.in/influencer-marketing-pricing-india-2026/ |
| Creator rates per Reel | Nano Rs 1k-12k. Micro Rs 8k-80k. Mid Rs 50k-3.5L. Macro Rs 2-12L. Mega Rs 8L to Rs 1Cr+. | Brand pays creator | 3P | https://www.identitykit.in/blog/influencer-marketing-cost-india-2026 |
| Usage-rights premiums | +20-40% for 30 days, +40-75% for 90 days, up to +100-200% for perpetual use | Brand | 3P | https://www.storyboard18.com/influencer-marketing/end-of-the-free-ride-influencers-now-charge-separate-steeper-fees-for-commercial-usage-rights-109399.htm |
| UGC video | Rs 1,500-4,000 organic. Rs 3,000-8,000 with ad rights. Up to Rs 25k+ for top creators. | Brand | 3P | https://www.ugccontent.in/learn/guides/ugc-creator-earnings-india |
| Interakt (SaaS anchor) | Free, Rs 2,799 or Rs 3,799/month, all + GST. 8% off quarterly, 20% off yearly. | SMB brand | PAGE | https://www.interakt.shop/pricing/ |
| Wati (SaaS anchor) | Rs 2,499 / 5,999 / 16,999 per month (3P). 25% off annual. Rs 999 pay-as-you-go start (PAGE). | SMB brand | 3P | https://chatarmin.com/en/blog/wati-pricing |
| Shiprocket (SaaS anchor) | Free, Rs 199, Rs 499 or Rs 799/month, before GST. The fee is refunded once shipment volume hits a threshold. | D2C brand | PAGE | https://www.shiprocket.in/pricing/ |
| Zoho Social (SaaS anchor) | About Rs 600/month billed annually. 33% off annual. | SMB brand | 3P | https://www.zoho.com/social/pricing.html |

### 1b. Creator side: what Indian platforms charge creators

| Platform | Pricing | Who pays | Evidence | Source |
|---|---|---|---|---|
| Influish Creator | Rs 499 per **year** for Auto-DM, collabs, AI Studio and invoices. 0% commission. Free Lite plan. | Creator pays the subscription. Brand fee reaches the creator in full. | PAGE | https://influish.com/creator |
| OPA (Wondrlab) | Rs 0 to creators: no subscription, no commission, no transaction fee | Brand side only | PAGE | https://opa.marketing/influencers/ |
| Wobb | No creator fee published. Brand SaaS reported from about $249/month. | Brand | 3P | https://www.influencer-hero.com/blogs/top-10-wobb-alternatives-for-effective-influencer-marketing-pricing-reviews |
| Kofluence | No creator commission published. Claims creators earn Rs 20k-2L/month. | Brand | SELF | https://www.kofluence.com/blog/how-much-money-indian-creators-really-make/ |
| Hobo.Video | Nothing published | Brand | UNVERIFIED | https://hobo.video/blog/tag/earnings/ |
| Plixxo (GCC) | Nothing published | Brand | UNVERIFIED | https://www.globalcosmeticsnews.com/good-glamm-spins-off-plixxo-missmalini-winkl-and-vidooly-to-create-the-good-creator-co/ |
| Topmate | 10% on own-link sales, 20% on marketplace sales. Custom plan above Rs 10L/month. | Creator | PAGE | https://topmate.io/pricing |
| Exly | Free plan at 10%. Rs 2,500/month at 6%. Rs 9,000/month at 3%. | Creator | PAGE | https://exlyapp.com/pricing |
| SuperProfile | Free plan at 10%. Rs 11,999/year at 5%. Rs 49,999/year at a custom rate. | Creator | SELF | https://help.cosmofeed.com/portal/en/kb/articles/superprofile-plans-passion-pro |
| Cosmofeed | 10% + GST, which includes the gateway fee | Creator | SELF | https://help.cosmofeed.com/portal/en/kb/articles/cosmofeed-platform-fee |
| TagMango | Rs 0 + 10%. Rs 5,000/month + 5.5%. Rs 15,000/month + 3.5%. Rs 30,000/month + 0%. All + GST. | Creator | PAGE | https://tagmango.com/pricing |
| Graphy | Rs 24,999 / 49,999 / 99,999 per year at 10% / 7.5% / 5% | Creator | PAGE | https://graphy.com/pricing/ |
| Instamojo | Free plan at 5% + Rs 3. Rs 6,999/year at 5% + Rs 3. Rs 14,999/year at 2% + Rs 3. GST applies on the fee. | Creator | PAGE | https://www.instamojo.com/pricing/ |
| Zorcha | Free. Rs 1,199 / 2,999 / 8,999 per month, or Rs 799 / 1,999 / 5,999 per month billed annually. Digital-product fee of 8% down to 1%. | Creator | PAGE | https://zorcha.com/in/pricing/ |
| ReplyKaro | Free. Rs 99 / 299 / 449 / 399 per month. | Creator | PAGE | https://www.replykaro.com/pricing |
| SuperProfile AutoDM | Free. About Rs 499/month (Rs 99 for the first month). Conflicts with a $29 figure elsewhere. | Creator | 3P | https://superprofile.bio/in/auto-dm |
| Kwikzy | Free (50 DMs). Rs 399 or Rs 999 per month. | Creator | SELF | https://www.kwikzy.com/blogs/instagram-automation-tools-india-under-1000 |
| LinkPlease | Rs 499/month | Creator | 3P | https://www.kwikzy.com/blogs/instagram-automation-tools-india-under-1000 |
| QuickDM / UnlockDM / Hypello | Rs 399/month / Rs 299 per campaign / about Rs 408/month | Creator | 3P | https://www.tryunlockdm.com/blog/instagram-dm-automation-india-2026 |
| LinkDM (USD reference only) | $19/month, about Rs 1,961 after forex and GST | Creator | 3P | https://setsmart.io/blog/linkdm-pricing |
| Creator per-post tiers | Nano Rs 2k-8k. Micro Rs 8k-80k. Mid Rs 50k-3.5L. Macro Rs 2-12L. | Brand pays creator | 3P | https://upgrowth.in/influencer-marketing-pricing-india-2026/ |
| Kofluence 2026 report | 88% of creators earn less than 75% of their income from social media. Long-tail creators earn under about Rs 18k/month (the article could not be read). | n/a | 3P | https://www.storyboard18.com/how-it-works/46-creators-are-full-time-influencers-but-most-still-rely-on-side-income-streams-kofluence-report-98018.htm |
| Razorpay | 2% + 18% GST on the fee for domestic payments, UPI included. T+1 settlement. | Merchant or platform | PAGE | https://razorpay.com/pricing/ |
| UPI MDR (NPCI) | From 15 Oct 2026: 0.4% on P2M payments above Rs 2,000, capped at Rs 300 | Merchant | 3P | https://www.scconline.com/blog/post/2026/09/16/npci-released-upi-mdr-faqs-explained/ |
| TDS 194J / Sec 393 | 10% on professional fees. Threshold Rs 50k per payee per year. | Brand deducts | 3P | https://taxguru.in/income-tax/budget-2025-section-194j-tds-threshold-professional-fees-increased.html |
| TDS 194C | 1% for individuals or 2% for others, where the deal is a contract | Brand deducts | 3P | https://www.rekko.in/tds-influencer-payments |
| TDS 194R | 10% on barter benefits above Rs 20k per year | Brand deducts | 3P | https://cleartax.in/s/influencer-tax-tds-on-social-media-influencers-under-section-194r-explained |
| TDS 194-O | 0.1% on gross sales. Individuals with PAN are exempt up to Rs 5L a year. | Platform deducts | 3P | https://inc42.com/buzz/budget-2024-tds-rate-for-ecommerce-operators-cut-to-0-1-from-1/ |

### 1c. Pricing norms in India

1. **Brand-deal marketplaces charge creators 0%.** OPA charges creators nothing, and Influish charges 0% but sells a Rs 499/year plan. These platforms earn from brands through SaaS, per-influencer rates or managed-campaign margins. Influish is the closest direct comparison, but it is not the only one.
2. **Brands can be charged in five ways:**
   - a free marketplace;
   - a per-influencer rate of Rs 700-1,500 excluding GST (OPA);
   - a prepaid package of Rs 50k-2.5L (ClanConnect);
   - a discovery SaaS from free up to about Rs 9,999/month;
   - a managed fee of 8-15% of spend on platforms (Wobb 10%) or 15-25% at agencies.
3. **Almost nobody publishes Rs prices.** "Book a demo" is the norm, so a public, GST-labelled Rs rate card would stand out.
4. **Creator-monetisation SaaS charges about 10% on the free tier.** Topmate, Exly, SuperProfile, Cosmofeed and TagMango all sit there. Paid tiers bring it down to 3-6% for Rs 2,500-15,000/month.
5. **Entry creator tools cost Rs 99-499/month.** Freemium is standard, and annual prepay usually saves about 33%. **No Indian survey measures willingness to pay;** the evidence is price clustering only.
6. **SMB marketing tools cost Rs 2,000-10,000/month.** Quarterly or annual discounts run 8-33%.
7. **GST display:**
   - B2B tools quote "+ GST" so that registered businesses can claim ITC (Interakt, OPA, Shiprocket).
   - Some SMB launches quote GST-inclusive prices (Zefmo One).
   - Creators must register for GST above Rs 20L turnover (Rs 10L in special-category states).
8. **Deal sizes:**
   - Per Reel, nano pays Rs 1k-12k and micro pays Rs 8k-80k.
   - D2C brands start at Rs 50k-1.5L/month.
   - Rs 25k and Rs 1L are realistic median tickets.
9. **Gateway and tax:**
   - Razorpay charges 2% + GST on the fee.
   - TDS works as follows:
     - 194J at 10% above Rs 50k/year and 194R at 10% on barter above Rs 20k are the brand's to deduct.
     - 194-O at 0.1% applies to the platform, but individuals with PAN are exempt up to Rs 5L/year.
     - From 1 Apr 2026 all of these map to Sec 393 of the Income Tax Act 2025.

### 1d. Unverified: do not quote these

- Paid brand plan prices at Influish and Influish's /pricing page (404).
- Wobb Rs pricing (only 3P USD figures). The Qoruz Premium price ($600, 3P).
- Kofluence, One Impression, Hobo.Video, Chtrbox, GCC, Opportune, Influencer.in, Pepper and influe.in all publish no brand pricing.
- The Zoho Social INR price and Wati INR prices (both 3P). The upGrowth self-serve range "Rs 25k-3L or 8-15%" is not tied to a named vendor.
- SuperProfile AutoDM price (conflicting sources, page returned 429). Graphy older figures (conflicting). Topmate payout speed and fees. Instamojo T+3 settlement.
- The Kofluence figure of under Rs 18k/month (article returned 403). The UPI 0.4% MDR (secondary coverage only; Razorpay's page still shows 0% MDR plus 2%).
- The 194C thresholds and the Sec 393 mapping (advisory sites, not the Act itself). The ISB/Hashfame PDF could not be parsed.
- **There is no willingness-to-pay data for Rs 99, 199 or 499 creator plans.**

---

## 2. Tejas's proposal (CMO)

**Core idea:** Influora should win in India on transparency and low-friction entry, not on the lowest take rate. Influish is at 0%, so a race to zero cannot be won. Influora should charge for the work the AI does: full rate on self-sourced deals, a lower rate when Meera sources the deal.

**Opening position:**
- **Brand Free:** 10% of committed budget at go-live. 50 credits at signup, then +50 when a campaign is funded (not merely created). Top-ups at Rs 499 for 50 credits, Rs 899 for 120 and Rs 2,999 for 500, all GST-inclusive.
- **Brand Pro:** Rs 4,999/month + GST, or Rs 49,999/year + GST. 400 credits and 5 seats. Fee of 7% on self-sourced deals and 5% on Meera-sourced deals only.
- **Creator:** commission drops from 15% to 10% now, as a config change. Free Meera stays at 40 credits/month.
- **"Meera Pitch" plan:** Rs 199/month + GST (Rs 1,999/year + GST) with 150 credits. Top-ups at Rs 99 for 25 credits and Rs 299 for 100. 5% commission only on Meera-pitch-sourced deals.
- **Billing:** none until the tool ships. Until then it is listed as a "founding price".
- **GST:** subscriptions shown ex-GST, top-ups shown GST-inclusive.

**Final position after the cross-check:**
- He adopted Rohan's Phase 0 code fixes and the cap of 2 grants/month.
- He dropped the Rs 499 minimum fee because it is not benchmarked in India.
- Creator commission GST is to follow "each creator's own registration status".
- The Pro fee stays at 7% self-sourced / 5% Meera-sourced.

**Rollout:**
- **Phase 1:** code fixes, brand page, credit rules and packs.
- **Phase 2:** creator commission cut to 10%.
- **Phase 3:** deal-source attribution tag, followed by both 5% discounts.
- **Phase 4:** build Meera Pitch. The waitlist starts in Phase 1.

## 3. Rohan's proposal (CFO)

**Core idea:** every line must earn a positive contribution at Indian ticket sizes, with nano deals at Rs 5-25k and micro at Rs 25k-1L. The brand pays an ex-GST percentage of the funded budget plus a low-cost SaaS tier. The creator pays as little as the market allows, because Indian marketplaces charge creators 0%. Free credits are granted only on revenue events. A percentage is only ever lowered for the deals that caused the cut.

**Final position:**
- **Brand Free:**
  - Rs 0/month. 10% of the funded budget, ex-GST.
  - **Minimum fee of Rs 499 + GST per funded campaign.**
  - 50 credits for **new signups only**, while existing Free brands keep their 100. +50 per funded campaign, up to 2 grants a month.
- **Brand Pro:**
  - Rs 4,999/month + GST, or Rs 49,990/year + GST.
  - **Flat 5%**, following the CEO, so the invoice shows one number.
  - 400 credits, 5 seats, the same floor and the same grants.
- **Brand top-ups:** ex-GST and non-expiring. Rs 499 for 150 credits, Rs 999 for 350, Rs 1,999 for 800. That is Rs 2.50-3.33 per credit against a cost of Rs 0.65.
- **Creator Free:**
  - 10% commission, **GST-inclusive**, so the creator sees 90% "before applicable taxes".
  - 40 credits/month, no rollover.
  - Top-ups at Rs 99 for 60 credits and Rs 249 for 200, both GST-inclusive.
- **Meera Pro (creator):**
  - Rs 199/month or Rs 1,499/year, both GST-inclusive. 150 credits, with a PR turn costing 2 credits.
  - **5% only on deals the PR tool sourced**, with a 30-day first-contact lookback.
  - Waitlist or founding price only, until both the tool and server-side attribution are proven live.

**Rollout:**
- **Phase 0:** fix the code blockers and add per-deal fee and commission snapshot columns.
- **Phase 1:** 10% / 5%, the Rs 499 floor, creator commission at 10%, credit rules and brand packs, all in the same week as the credit cut.
- **Phase 2:** creator top-ups, annual Pro and the Rs 199 waitlist.
- **Phase 3:** Meera Pro once attribution is proven.
- **Phase 4:** an enterprise conversation once a brand passes Rs 10L/month.

## 4. Cross-check (chair's verdict on each challenge)

| Challenge | Raised by | Chair verdict |
|---|---|---|
| The Rs 499 floor is not benchmarked in India, and Rohan misreads OPA (its floor is per influencer: Rs 700+ × 100 influencers = Rs 70k+ per campaign) | Tejas | **Partly right.** OPA does not prove the floor is "cheap". It shows that Indian nano vendors enforce much larger floors, so a small floor is normal rather than invented. The floor stands or falls on **cost** grounds, and the chair checked those: on **Free** the floor only binds below Rs 4,990 of budget (10% of Rs 5,000 is Rs 500, which is already above Rs 499). So Rohan's line "Rs 5k nano ~Rs 250 only because of the floor" is **wrong for Free**. On **Pro at 5%** the floor binds below Rs 9,980, where it does protect the margin. |
| Commission GST should follow the creator's registration status | Tejas | **Flawed.** Influora's commission is Influora's own supply. Influora owes GST on it whether or not the creator is registered; registration only decides whether the creator can claim ITC. The real choice is **inclusive or "+ GST", applied the same way to everyone**. CA to confirm. |
| Tejas's margins (82-88%) ignore GST on commission, GST on the gateway fee, and ops time | Rohan | **Right.** On a common basis, a Rs 1L Free deal contributes about Rs 15.7k (about 15.7% of budget), not Rs 17.6k. See section 6. |
| Two Pro rates (7%/5%) need brand-side attribution that does not exist, and Indian SMB tools publish one number | Rohan | **Right on the dependency.** A blanket 5% does cost about Rs 1,950 per Rs 1L compared with 7%. This is a genuine CEO decision. |
| Tejas's brand packs at Rs 9.98/credit are "above the Rs 12.50/credit implied by Pro" | Rohan | **Arithmetic slip.** Rs 9.98 is *below* Rs 12.50. The underlying point stands: Rs 499 for 50 credits is 15x cost and steep next to creator packs at Rs 1.65-3.96. |
| Tejas's headline says "drops to 5% when Meera pitches" for brands | Chair | **Inconsistent.** Under his own numbers, Free brands stay at 10% on every deal. The headline must be scoped to Pro. |

---

## 5. Side-by-side: every price line

| Price line | Today (code) | CEO direction | Tejas final | Rohan final |
|---|---|---|---|---|
| Brand fee, Free | 10% at go-live | 10% | 10% of funded budget + GST | 10% of funded budget + GST |
| Brand fee, Pro | 7% | 5% (round 3: scope 5% to Meera-pitch deals) | 7% self-sourced / 5% Meera-sourced | 5% flat |
| Pro subscription, monthly | Rs 4,999 | Rs 4,999 (unchanged) | Rs 4,999 + GST | Rs 4,999 + GST (Rs 5,899 incl.) |
| Pro subscription, annual | none | none | Rs 49,999 + GST | Rs 49,990 + GST (2 months free) |
| Pro credits and seats | 400 / 5 | 400 / 5 | 400 / 5 | 400 / 5 |
| Free brand credits | 100 | 50, then top-ups | 50 at signup | 50 for new signups only; existing brands keep 100 |
| + grant per campaign | +50 after the first funded campaign (to 150) | +50 per campaign (round 3: on funded, not created) | +50 per funded campaign, max 2/month | +50 per funded campaign, max 2/month |
| Brand top-ups | not built | "top-ups", no prices given | Rs 499/50, Rs 899/120, Rs 2,999/500 (GST-incl.) | Rs 499/150, Rs 999/350, Rs 1,999/800 (+ GST) |
| Creator commission | 15% | 10%, and 5% on the Rs 199 plan (round 3: Meera-pitch deals only) | 10%, 5% on Meera-tagged deals only | 10% GST-incl., 5% on PR-tool-sourced deals only (30-day lookback) |
| Creator free AI | free, $0.75/month cap | 40 credits/month | 40 credits/month | 40 credits/month, no rollover |
| Creator paid plan | not built | Rs 199/month with a credit limit | Rs 199/month + GST or Rs 1,999/year + GST, 150 credits, founding price with no billing until built | Rs 199/month incl. GST or Rs 1,499/year incl. GST, 150 credits (PR turn = 2 credits), waitlist until built and attribution proven |
| Creator top-ups | not built | "top-ups" | Rs 99/25, Rs 299/100 (GST-incl.) | Rs 99/60, Rs 249/200 (GST-incl.) |
| Minimum fee | none | none | none (dropped) | Rs 499 + GST per funded campaign |
| GST display | not specified | not specified | Subscriptions ex-GST. Top-ups incl. GST. Commission "per creator status" (flawed, see §4). | Brand side all ex-GST. Creator side all incl. GST. |

---

## 6. Unit economics (all figures ESTIMATE, chair's common basis)

**Common basis.** The chair used one method for every version so the numbers can be compared. Neither proposer's own figures are used.
- **Gateway:** Razorpay 2% on the full amount the brand pays (budget + fee + GST on the fee). GST on the gateway fee is recovered as ITC.
- **Payout and AI:** Rs 60 per deal.
- **Ops:** Rs 500 per deal allocated.
- **Commission GST:** treated as **inclusive**, so net commission is commission ÷ 1.18. This is conservative. If commission is charged "+ GST" instead, add back about Rs 1,525 per Rs 1L deal and about Rs 381 per Rs 25k deal.
- **Brand fee GST:** passes through.
- **Subscriptions:** excluded from deal figures.

### Rs 1,00,000 micro deal

| Scenario | Brand pays (incl. GST on fee) | Creator gets | Platform gross take | Net after GST | Gateway | Contribution |
|---|---|---|---|---|---|---|
| Today: Free 10% + 15% commission | Rs 1,11,800 | Rs 85,000 | Rs 25,000 | Rs 22,712 | Rs 2,236 | **~Rs 19,900** |
| CEO / Tejas / Rohan: Free 10% + 10% commission | Rs 1,11,800 | Rs 90,000 | Rs 20,000 | Rs 18,475 | Rs 2,236 | **~Rs 15,700** |
| CEO / Rohan: Pro 5% + 10% | Rs 1,05,900 | Rs 90,000 | Rs 15,000 | Rs 13,475 | Rs 2,118 | **~Rs 10,800** |
| Tejas: Pro 7% self-sourced + 10% | Rs 1,08,260 | Rs 90,000 | Rs 17,000 | Rs 15,475 | Rs 2,165 | **~Rs 12,750** |
| Free brand + creator 5% (Meera-sourced; all three) | Rs 1,11,800 | Rs 95,000 | Rs 15,000 | Rs 14,237 | Rs 2,236 | **~Rs 11,450** |
| Pro 5% + creator 5% (Meera-sourced; all three) | Rs 1,05,900 | Rs 95,000 | Rs 10,000 | Rs 9,237 | Rs 2,118 | **~Rs 6,550** |

If the CEO's original blanket 5% for Rs 199 subscribers were applied, the "creator 5%" rows would cover **every** deal a subscriber does. That costs about Rs 4,240 per Rs 1L on deals Influora did not source. This is the release-month arbitrage both proposers flag.

### Rs 25,000 nano deal

| Scenario | Brand pays | Creator gets | Gross take | Net after GST | Gateway | Contribution |
|---|---|---|---|---|---|---|
| Today: Free 10% + 15% | Rs 27,950 | Rs 21,250 | Rs 6,250 | Rs 5,678 | Rs 559 | **~Rs 4,560** |
| Free 10% + 10% (all three) | Rs 27,950 | Rs 22,500 | Rs 5,000 | Rs 4,619 | Rs 559 | **~Rs 3,500** |
| Pro 5% + 10% (CEO / Rohan) | Rs 26,475 | Rs 22,500 | Rs 3,750 | Rs 3,369 | Rs 530 | **~Rs 2,280** |
| Pro 7% + 10% (Tejas, self-sourced) | Rs 27,065 | Rs 22,500 | Rs 4,250 | Rs 3,869 | Rs 541 | **~Rs 2,770** |
| Free + creator 5% (Meera-sourced) | Rs 27,950 | Rs 23,750 | Rs 3,750 | Rs 3,559 | Rs 559 | **~Rs 2,440** |
| Pro 5% + creator 5% (Meera-sourced) | Rs 26,475 | Rs 23,750 | Rs 2,500 | Rs 2,309 | Rs 530 | **~Rs 1,220** |

The Rs 499 floor does not bind at Rs 25k in any version.

**Smallest deals (chair's check):**
- A Rs 5,000 deal on Free contributes about **Rs 250** in every version; the floor does not bind.
- A Rs 5,000 deal on Pro at 5% has a Rs 250 fee, which the floor raises to Rs 499. Contribution is about Rs 250 with the floor and about Rs 0 without it.
- Deals below about Rs 5k lose money under every version.

**Subscriptions and packs (ESTIMATE, AI cost at Rs 0.65/credit):**
- **Pro:** Rs 4,999 ex-GST, less Rs 260 of AI at full use, less Rs 118 gateway, leaves about Rs 4,620 before ops.
- **Creator Rs 199:**
  - Rohan's GST-inclusive version nets Rs 168.64. At full use of 150 credits it contributes about Rs 67; at 50% use about Rs 116.
  - Tejas's "+ GST" version nets Rs 199 but costs an unregistered creator Rs 234.82.
  - Either way the plan **drives commission; it is not a profit line**.
- **Free creator Meera:** 40 credits cost at most about Rs 26/month, within the $0.75 cap.
- **Free brand grants:** 50 credits cost about Rs 32.50 each.
- **Price per credit, ex-GST:**
  - Tejas: brand packs Rs 5.08-8.46, creator packs Rs 2.53-3.36.
  - Rohan: brand packs Rs 2.50-3.33, creator packs Rs 1.06-1.40.
  - Every pack is above cost.

**Take-away:** moving creator commission from 15% to 10% gives up about **Rs 4,200 per Rs 1L deal** on the chair's basis, or about Rs 2,700-4,200 depending on the ops allocation. It is the price of being credible against Indian marketplaces that charge creators 0%. The other main lever is the Pro fee: 5% instead of 7% costs about Rs 1,950 per Rs 1L.

---

## 7. Where they agree and where they disagree

**They agree on:**
- Fixing both code blockers **before** any percentage is published (Phase 0), with per-deal fee snapshots (Rohan) so in-flight deals are not repriced.
- Creator commission of **15% → 10% now**, as a config change.
- Free brands at **10%** of the funded budget.
- **Pro at Rs 4,999/month + GST**, with 400 credits and 5 seats, plus an annual option at about Rs 49,990-49,999 + GST.
- Free Meera for creators at **40 credits/month**.
- **+50 credits on funded, not created**, capped at 2 a month.
- The creator's 5% applies **only to Meera-sourced deals**, never to every deal a subscriber does.
- **No billing of the Rs 199 plan** until the PR tool and attribution exist, with a founding-price waitlist in the meantime.
- **A public, GST-labelled Rs rate card is the differentiator in India.**
- No "escrow" and no licence claims.

**They disagree on:**
1. **Pro brand fee:** Tejas wants 7% self-sourced / 5% Meera-sourced. Rohan wants a flat 5%, following the CEO.
2. **Minimum fee:** Rohan wants Rs 499 + GST. Tejas says none.
3. **Brand top-up price:** Tejas prices at Rs 5-8.5/credit ex-GST, GST-inclusive. Rohan prices at Rs 2.5-3.3/credit, ex-GST.
4. **Existing Free brands' 100 credits:** Tejas implies everyone moves to 50. Rohan keeps existing brands at 100.
5. **Commission GST:** Rohan says inclusive ("keep 90%"). Tejas says "per status", which the chair considers unworkable.
6. **Creator plan GST and annual price:** Tejas says Rs 199 + GST and Rs 1,999/year. Rohan says Rs 199 inclusive and Rs 1,499/year.
7. **Creator top-up size:** Tejas offers Rs 99 for 25 credits. Rohan offers Rs 99 for 60.
8. **GST display on brand top-ups:** Tejas says inclusive. Rohan says ex-GST, following the B2B norm.

---

## 8. Decisions for Swapnil

1. **Approve Phase 0 now.** Change `PLATFORM_FEE_PERCENT` from 15 to 10 at `application.yml:407` and move the label at `BrandPlatformFeeService.java:36` into config. Add per-deal fee and commission snapshots, and audit the copy for "escrow" and licence claims. Nothing gets published before this lands.
2. **Pro brand fee:** choose between a **flat 5%** (Rohan, and your own original) and **7% self-sourced / 5% Meera-sourced** (Tejas, and your round-3 note). *Chair's lean:* flat 5% at launch, because brand-side attribution does not exist and one number avoids disputes. It costs about Rs 1,950 per Rs 1L compared with 7%. Revisit once attribution ships.
3. **Creator commission of 10%:** decide between **GST-inclusive** ("you keep 90% before applicable taxes"; Influora nets 8.47%) and **10% + GST** (creator sees 88.2%). *Chair's lean:* inclusive, pending a CA sign-off. "Per creator status" is not an option.
4. **Rs 499 + GST minimum fee:** adopt it, *framed as a cost floor*. It only binds below Rs 4,990 on Free and Rs 9,980 on Pro. Or reject it.
5. **Brand top-up price card:** Tejas (Rs 499 for 50 credits) or Rohan (Rs 499 for 150 credits, ex-GST), or a price in between. Credits must be buyable in the **same release** as the cut from 100 to 50.
6. **Existing Free brands:** keep them on 100 credits (Rohan) or move everyone to 50.
7. **Creator Rs 199 plan:**
   - GST-inclusive or + GST.
   - Annual price of Rs 1,499 or Rs 1,999.
   - Confirm it runs as a **founding-price waitlist with no billing** until the PR-finder tool and server-side pitch attribution (30-day lookback) are live.
8. **Creator top-ups:** Rs 99 for 25 credits or Rs 99 for 60 credits.
9. **Annual Pro:** confirm Rs 49,990 + GST (2 months free).
10. **Headline copy:** the brand promise "drops to 5%" must name Pro. Free stays at 10%.
