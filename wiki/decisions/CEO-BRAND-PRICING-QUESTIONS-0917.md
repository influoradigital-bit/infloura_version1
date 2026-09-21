# Brand-Side Questions: How Does Influora Price a Campaign?

**From:** Swapnil Maruti (CEO), asking as a brand would
**To:** Priya (CTO)
**Date:** 2026-09-17
**Answers go to:** `wiki/decisions/CTO-BRAND-PRICING-ANSWERS-0917.md`

---

## Why this set exists

A brand pasted a ₹4,000 rice cooker and Meera quoted ₹318 per creator off an invented ₹5,300 price.
We fixed that (`1446f9d`, `6b2f94b`). But it exposed that nobody can explain, end to end, where any
money number a brand sees actually comes from. A brand is about to hand us real money. Every figure
they see must have a source we can name.

**Rules for your answers:**
1. Cite `file:line` for every claim. Only cite code you opened. If you could not open it, write `UNVERIFIED`.
2. Lead with the bad news: if a number is invented, hardcoded, estimated, stale, or never shown, say so first.
3. Separate three things that get blurred together: what the AI **suggests**, what the system **charges**, and what the creator **receives**.
4. After each answer, one line: `→ Brand sees:` what a brand actually experiences.
5. Max ~8 lines per answer.

---

## A — Where does the AI get its numbers?

**Q1.** When Meera builds a campaign, list every input she reads. For each: where it comes from (brand typed it, scraped from their site, our database, the model's own knowledge), and whether it can be wrong.

**Q2.** Product price: after the `analyze_site` outage, where does Meera get a product price today? Can she still invent one, and does anything stop that number reaching the brand?

**Q3.** Creator rates: the rate band comes from completed collaborations. How many completed, money-settled collaborations exist in production right now, per niche? Is the k-anonymity floor (5 creators × 5 workspaces) met for **any** niche?

**Q4.** If the band is null for every niche, what number does a brand ever see before they type their own budget? Is there any path where the AI still produces a rupee figure?

**Q5.** Does Meera use anything from the model's training data about "what influencers cost in India"? What in the prompt or tooling prevents that?

**Q6.** Creator follower counts, engagement rate, reach — where do they come from, how fresh are they, and are any self-reported by the creator rather than verified from Meta?

**Q7.** Is there any place the AI sees another brand's prices, budgets, or deals? Walk the k-anonymity guard and tell me whether a small niche could leak a single competitor's rate.

## B — What does a campaign actually cost the brand?

**Q8.** Break down every rupee a brand pays for one campaign: creator fee, platform fee, GST, payment gateway fee, anything else. For each line, the file that computes it.

**Q9.** Is the platform fee a percentage, a flat amount, or tiered by plan? Where is it configured, and can it change without a deploy?

**Q10.** Is GST charged on the creator fee, the platform fee, or both? Who is the supplier of record for each, and is that reflected in the invoice?

**Q11.** Is TDS deducted on creator payouts? Earlier audits said TDS was ruled out pending a CA — what is the actual state in code today?

**Q12.** Does the brand see the full total including fees and tax **before** they commit money, or only after? Show me the screen and the code that builds the figure.

**Q13.** Is there a minimum campaign value or minimum per-creator fee anywhere? If not, can a brand create a ₹1 campaign?

**Q14.** Do subscription plans change what a brand pays per campaign — fee discounts, credit bundles, caps? Where is that logic, and is it live?

## C — When and how is money actually charged?

**Q15.** Walk the money timeline: at what exact moment does money leave the brand's account? At campaign creation, at creator acceptance, at funding, at delivery, at approval?

**Q16.** Can the AI ever trigger a charge? Confirm that `request_payment` and `confirm_launch` are still unreachable by the model, and name every layer that enforces it.

**Q17.** When a brand "secures funds" for a campaign, where does that money sit, who controls it, and what releases it?

**Q18.** If a creator never delivers, how does the brand get the money back? Is it automatic, manual, or not built?

**Q19.** If a campaign is cancelled halfway — two of five creators delivered — what does the brand pay and what comes back?

**Q20.** Wallet top-ups: is a ₹1,000 credit proven end to end on live? What happens to a payment that succeeded at Razorpay but never reached our webhook?

**Q21.** Are there any charges the brand does not see coming — AI credit consumption, per-creator fees, currency conversion, refund fees?

## D — Creator price vs what the creator receives

**Q22.** A brand agrees ₹10,000 with a creator. What does the creator actually receive, after every deduction? Show the arithmetic from the code.

**Q23.** Who sets the creator's price — the creator's rate card, the brand's offer, the AI, or negotiation? Can the brand pay less than the creator's listed rate?

**Q24.** Rate card: can a creator publish per-deliverable prices (reel, story, post)? Does the campaign price use them, or ignore them?

**Q25.** `agreed_rate` is a whole-collaboration figure. Is anywhere in the product — UI, AI, email, invoice — still presenting it as a per-reel price?

**Q26.** If a creator raises their rate after a brand has already agreed a price, which number wins?

## E — Can a brand trust and verify a number?

**Q27.** For every rupee figure a brand can see, can they trace it to a source? Which figures are verifiable and which are estimates presented without a label?

**Q28.** Campaign performance and ROI: are any of these numbers self-reported by creators? Where they are, is that labelled to the brand?

**Q29.** If a brand disputes a charge, what record exists to prove what was quoted, agreed, charged and paid — and how long does it take to pull?

**Q30.** Is there any number shown to a brand that we cannot reproduce from the database afterwards?

## F — What breaks, and what we cannot yet claim

**Q31.** What is the single most likely way a brand is overcharged today? And the most likely way they are undercharged, costing us money?

**Q32.** Are there any money calculations that use floating point instead of exact decimals? Any rounding that could leak paise at scale?

**Q33.** Which pricing and charging paths have **never** been exercised on live by a real payment?

**Q34.** What pricing claims does our marketing site or pricing page make that the code does not back?

**Q35.** If we raised the platform fee tomorrow, which existing campaigns would be affected, and would any brand be charged a price they did not agree to?

**Q36.** What is the one thing about how we price and charge that would embarrass us if a brand's finance team audited it before we did?

---

*Answers to `wiki/decisions/CTO-BRAND-PRICING-ANSWERS-0917.md`.*
