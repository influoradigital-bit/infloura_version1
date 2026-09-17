# CMO Ruling: Pro Plan Dunning Grace Period
**Ruling by:** Tejas Mehta (CMO)  
**Date:** 2026-09-17  
**Context:** Swapnil-requested market ruling on subscription retry behavior  
**Code reference:** `wiki/processes/subscription-answers-0917-brand.md` BR-19

---

## Ruling Summary

| Question | Decision | Reasoning |
|----------|----------|-----------|
| **Pro features during retries** | Fully ON | Indian payment failures are infrastructure-based, not intent. Harsh shutoff damages trust and drives churn. |
| **Grace period length** | 7 days | Aligns with Razorpay retry schedule and `SubscriptionDunningJob`. Standard in Indian SaaS market (Zoho, Freshworks). |
| **Campaign fee during grace** | 7% (Pro rate) | Brand paid last month; subscription is active-but-retrying. Charging 10% punishes a technical issue. |
| **Metric to watch** | Recovery rate within grace | Target >70%. If <50%, grace too short. If >90%, UX working well (don't shorten - better retention worth it). |

---

## 1. Pro stays FULLY ON during the 7-day grace

**Current code (BR-19):** Pro features stop immediately when status becomes PAST_DUE. The 7-day window only decides when a second email goes out and status becomes HALTED.

**Market context:**
- UPI Autopay and card mandate failures are **common and technical** in India: expired cards, bank downtime, mandate limits hit
- Comparable products (Zoho, Freshworks, Razorpay Dashboard) give 5-7 day grace with features on
- Our customers: 27 brands, none paying yet. Beta retention is critical.
- ₹4,999/month is meaningful spend for Indian D2C brands - they notice a failure

**Ruling: Pro stays fully on for 7 days.**

Why not partial (some features on, some off)?
- Too complex to communicate ("your templates work but export doesn't")
- Confusing UX, damages trust
- No Indian SaaS comp does this

**What stays on:**
- 7% campaign fee (see #3)
- 400 AI credits/month allotment (current balance unchanged)
- Templates: view, use, create
- Export: on
- Analytics: unlimited creators
- Team seats: existing members stay, can add up to 5
- Campaigns in progress: continue uninterrupted

---

## 2. Grace period: 7 days from first failed charge

**Aligns with:**
- Current `SubscriptionDunningJob` HALTED threshold (7 days)
- Razorpay's retry schedule (multiple attempts over ~1 week)
- Indian SaaS market standard (Zoho ~5 days, Freshworks 7 days)

**Why not 3 days?** Too short. Indian banking delays (weekend + public holidays + UPI clearing) mean brand may not see email and fix issue in time.

**Why not 14 days?** Too generous. Abuse risk, and no comp does this. 7 days is enough to:
1. Notice the failure email
2. Contact bank or update UPI mandate
3. Retry goes through

**Assumption:** Razorpay retries daily during this window. If retry frequency is lower (e.g. every 3 days), we may extend grace to 10 days. Needs verification from Razorpay docs or Vikram.

---

## 3. Campaign fee during grace: 7% (Pro rate)

**Scenario:** Brand is PAST_DUE (payment retrying). They publish a campaign on day 3 of grace.

**Ruling: Charge 7% Pro rate.**

**Reasoning:**
- They paid last month. Subscription is "active but retrying", not "cancelled and freeloading".
- Charging 10% feels like punishment for a technical payment issue (which may be Razorpay's clearing delay, not brand's fault).
- **If Pro features are on, fee must match.** Charging Free fee while they see Pro UI is deceptive.

**At end of grace (HALTED):**
- Future campaigns (published after day 7) are charged 10% Free rate
- Campaigns published during grace that were charged 7% stay at 7% (no retroactive surcharge)

---

## 4. What the brand sees and receives

### In-app banner (days 1-7, shown on every page while PAST_DUE)

**Visual:** Yellow warning banner at top of dashboard, not dismissible

**Copy:**
```
⚠️ Payment retry in progress — We couldn't process your Pro subscription payment (₹4,999). We're automatically retrying your payment method. Your Pro features remain active for 7 days while we sort this out. [Update Payment Method]
```

**CTA:** "Update Payment Method" → billing page with Razorpay retry/update flow

Never use the word "escrow" (per project ruling). Never blame the customer ("your card was declined"). Neutral, solution-focused tone.

---

### Email 1: Sent immediately on first failure (PAST_DUE)

**Subject:** Payment retry underway for your Influora Pro plan

**Body:**
```
Hi [Brand Name],

We tried to charge your Influora Pro subscription (₹4,999/month) but the payment didn't go through. This happens sometimes with UPI mandates or card updates — we see it often in the Indian market.

Your Pro features stay active for the next 7 days while we automatically retry your payment method.

What to do:
• Nothing, if your card or UPI mandate is still valid — we'll retry automatically over the next week
• Update your payment method if your card expired or the mandate needs renewal: [Update Payment Method link]

Your campaigns, Pro benefits (7% platform fee, templates, export, 400 AI credits) and team access continue as normal during this time.

Questions? Reply to this email.

Thanks for being an Influora Pro member,  
Team Influora
```

**Tone:** Reassuring, not alarming. Acknowledge Indian payment infrastructure issues. Customer is still "a Pro member".

---

### Email 2: Sent at day 7 if still HALTED

**Subject:** Pro plan paused — update needed to restore access

**Body:**
```
Hi [Brand Name],

We've tried several times to process your Influora Pro subscription payment over the past week, but it hasn't gone through.

Your Pro plan has been paused. You're now on our Free plan:
• Platform fee: 10% (was 7%)
• AI credits: 100/month refresh (was 400)
• Templates and export: paused

Your saved data is safe: all campaigns, team members, creator lists, and analytics history are kept. You can restore your Pro features anytime by updating your payment method here: [Update Payment Method link]

Need help? Reply to this email or contact us at [support contact].

Team Influora
```

**Tone:** Clear consequences, no blame. Emphasize data safety (retention concern). Door open to reactivate.

---

## 5. What happens at the end of grace (day 7, status HALTED)

**Immediate changes:**
- Status: HALTED (treated as Free plan)
- Campaign fee: **future** campaigns charged 10% (campaigns published during grace that were 7% stay at 7%)
- Templates: existing ones kept, can view and use, **cannot create new**
- Export: blocked with 402 and "Pro feature paused" toast
- Analytics: unlimited views **stop**; new creator views blocked at 1/period
- Team seats: existing members stay active, **cannot add new** (limit 1 total)

**NOT changed (data preservation):**
- AI credits: `creditsRemaining` balance unchanged until next monthly reset (1st)
- AI credits allotment: drops to 100 only at next 1st, not immediately at HALTED
- Campaigns: all active and past campaigns stay, can still view/edit
- Saved creators: all kept (no enforcement of Free's supposed 5-creator limit per BR-5)
- Campaign history, analytics data, wallet ledger: all kept

**Rationale:** We want them to come back. Deleting data or crippling active work drives churn. Pausing *new writes* over limits is enough.

**If payment succeeds during grace (day 1-7):**
- Status: back to ACTIVE
- Email: "Your Pro subscription is back on track — thanks for updating your payment method."
- No disruption, continue as normal

---

## 6. Metric to watch: Recovery rate within grace

**Primary KPI: % of PAST_DUE subscriptions that return to ACTIVE within 7 days**

**Target: >70%**
- If recovery rate <50%: grace period may be too short, or payment update UX is broken. Consider extending to 10 days or simplifying Razorpay retry flow.
- If recovery rate >90%: UX working well. Do NOT shorten grace — better retention is worth the 7-day window.

**Secondary metrics:**
1. **Median time to recovery:** Should cluster around 1-3 days. If it's consistently 6-7 days, brands are waiting until the last minute (may indicate email urgency is wrong).
2. **Abuse rate:** Brands who hit PAST_DUE >2 times in 6 months. Target <5%. Above 10% signals intentional non-payment pattern.
3. **Churn at HALTED:** % of HALTED brands who never reactivate within 30 days. Lower is better. High churn here means our downgrade messaging or reactivation UX failed.

**How to track:** Vikram adds to `BillingMetricsService` or new `SubscriptionHealthJob` that logs weekly:
- Count of PAST_DUE → ACTIVE transitions within 7 days
- Count of PAST_DUE → HALTED
- Median days to recovery
- Repeat-offender count

---

## Comparable market practice (Indian SaaS)

**Zoho (Subscriptions, CRM):**
- 5-7 day grace period with features on
- Multiple retry attempts via Razorpay
- Email at failure + reminder before cutoff

**Freshworks (CRM, Helpdesk):**
- 7-day grace, account stays active during retries
- Standard in their Indian market pricing tier

**Razorpay Dashboard (ironic but true):**
- Their own subscription product gives grace on failed renewals
- Acknowledges Indian payment infrastructure issues

**Clevertap, WebEngage (marketing automation, similar price point):**
- Grace periods standard, typically 5-7 days
- Features stay on to avoid mid-campaign disruption

**Global comparison (Stripe Smart Retries):**
- Retries over ~2 weeks with features on
- More generous than our 7 days, but Stripe's in 40+ countries with varied banking speeds
- Our 7 days is appropriate for India-focused product

---

## What engineering must build (gaps in current assignments)

**Current state (BR-19):** PAST_DUE is treated as "not ACTIVE", so Pro features drop immediately.

**New work required:**

1. **Treat PAST_DUE as "active Pro" for 7 days:** Modify `SubscriptionService.getActivePlanForWorkspace` to return the Pro plan when status is PAST_DUE and `updatedAt` (status change timestamp) is within 7 days. After 7 days, HALTED behavior kicks in (already built).

2. **In-app banner component:** New React component `<SubscriptionRetryBanner>` shown on brand dashboard when status is PAST_DUE. Yellow warning, not dismissible, with "Update Payment Method" CTA → billing page.

3. **Email copy updates:** 
   - Payment-failed email (exists, sent at PAST_DUE): replace with Email 1 copy above
   - Dunning email (exists, sent at HALTED): replace with Email 2 copy above

4. **Fee rate during grace:** Verify `BrandCampaignFeeService.java:108-121` charges 7% for PAST_DUE brands (may already work if PAST_DUE is treated as Pro per #1 above; needs gate test).

5. **Graceful allotment drop at HALTED:** When status becomes HALTED, do NOT touch `creditsRemaining`. Only set `monthlyAllotment = 100`. Next monthly reset (1st) will apply the 100 limit. (Current code may already do this per `applyPlanAllotment`; verify in `SubscriptionService.java:745-757`.)

6. **Recovery rate dashboard:** Add to admin billing console or weekly email report. Track PAST_DUE → ACTIVE vs PAST_DUE → HALTED counts, median recovery time.

**Assumption:** Items 1-3 are NOT in `wiki/processes/assignments-0917-subscription.md` S1/S2 tickets. If they are, mark as already assigned.

---

**Ruling confidence:** HIGH. Based on standard Indian SaaS market practice, retention principles, and Influora's beta-stage need to maximize customer lifetime value.

**Revisit trigger:** If recovery rate <50% after first 20 Pro subscriptions go live, extend grace to 10 days.
