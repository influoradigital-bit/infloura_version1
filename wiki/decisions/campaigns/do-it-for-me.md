# "Do It For Me" — Managed Campaign Tier

**Owner:** Tejas (CMO)
**Date:** 2026-09-12
**Status:** PROPOSED — needs Swapnil approval before any build
**Decision needed from:** Swapnil (go/no-go, fee, staffing), Priya (eligibility enforcement)

---

## The problem

Some brands do not want to run a campaign. They want to state a budget and a number of creators and have it handled. Today Influora's entire brand funnel is built for the opposite buyer — one who wants to pick creators, negotiate, and approve each step.

| | Operator brand | Outcome brand |
|---|---|---|
| Wants | Control | A result |
| Decisions they will make | 20+ | 1 |
| Our funnel today | built for this | dies at step 2 |

---

## RECOMMENDATION

**Sell 2–3 campaigns by hand, then build. Meera carries most of the tier already.**

> **This recommendation was revised.** The first version of this memo said "run
> 10 manually before building anything." That was written before checking what
> Meera can already do. Meera has six tools covering ask -> draft -> price ->
> show creators -> take payment -> launch, and `NotificationService` +
> `EmailWorker` already deliver progress emails. With that much in place the
> manual burden is small, and staffing ten campaigns to learn what to build is
> no longer the cheapest way to learn it. See the REVISION section below for the
> Meera-orchestrated design this recommendation now assumes.

Two or three hand-run campaigns are still worth doing first — not to size the
work, but to see **where brands hesitate**: which question stalls them, whether
they argue with the fee, what they ask for that we did not anticipate. That is
information no amount of design produces, and three campaigns surface it as well
as ten.

Ten would still answer "will they pay?" more conclusively. But ten also turns us
into an agency for a month and delays a build that is now mostly small pieces.
Three is the better trade.

### Sequence

| When | Do | Code? |
|---|---|---|
| Now | Fix requirements enforcement (Ruling 1) | Yes, medium |
| Weeks 1–2 | Sell and hand-run 2–3 campaigns. Watch where brands hesitate. | No |
| Then | Build the REVISION steps 3–7 (post-payment shipping question, address-at-claim, milestone triggers, CSV builder, line-up confirm) | Yes, mostly small |
| Then | Auto-approve timer (Ruling 4) | Yes, medium |
| Later | Logistics via brand's own store / Amazon MCF | Yes |
| Never | Reimbursement. Influora holding stock. | — |

### The risk, named

Hand-running campaigns makes us an agency. Fine as a short experiment, dangerous
as a destination — agency margin is capped by headcount. **Three campaigns, then
build.** If brands will not pay the fee at three, they will not pay it at ten.

---

## The entry point

Three lanes, shown when the brand clicks **New Campaign** — NOT as a popup on page load. A modal on dashboard load blocks the brand who logged in to check a running campaign, and trains people to close our modals reflexively.

This REPLACES the existing Open/Direct/Hype tile screen at `src/pages/brand-new-campaign.tsx`. Two choosers back to back is worse than either alone.

| # | Tile | Why this order |
|---|---|---|
| 1 | **Do it for me** — "Tell us what you're selling. We build it, fill it, you approve once." | First tile takes ~60% of clicks. This is the tile that answers the objection. |
| 2 | **Build it with Meera** — "Answer a few questions in chat. Meera drafts it, you edit anything." | |
| 3 | **I'll set it up myself** — "Full control over every field." | Smallest, last. |

The existing question ("what KIND of campaign?") is a taxonomy question a founder cannot answer. The new question ("how much help do you want?") is one they can answer in one second. Campaign type is then INFERRED, never asked.

---

## The five questions

```
1  What are you promoting?
   [ paste product URL ]   or   [ it's a service ]
   -> Meera reads the page: name, price, images, description

2  How many creators?          20
   -> 20 x Rs 6,000 = Rs 1,20,000

3  Who should they be?
   Min followers   15,000
   Min engagement  1.5%
   Location        Maharashtra
   Language        Marathi, Hindi

4  (product only) How does it reach them?
   o I'll courier it from my own stock
   o I'll order from my store / Amazon to their address
   o It needs to be bought at retail    <- only this adds product cost

5  Anything we should avoid?   [ free text ]

6  Campaign name   [ pre-filled by Meera, editable ]
```

**Hard cap: "Do it for me" asks 5 questions.** If it asks as many as the manual lane, the brand notices within 15 seconds that they got the same work with a nicer label, and trusts the next promise less. The gap IS the product.

### Design rules

- **Never ask for a budget.** Ask how many creators; show the money as an output. This is the whole pitch. Brands cannot price an influencer campaign, so asking forces a guess, and a guess feels like risk.
- **Product URL first.** One paste yields name, price, images, category — it writes most of the brief and removes three other questions.
- **Campaign name last, pre-filled.** Naming is our job now.
- **Location and language are not optional in India.** Marathi creators in Maharashtra is a completely different campaign from Hindi creators nationally.
- **Five questions is too many for a modal.** Modals hold one decision. Pick the lane in the modal, then land on a page or a Meera chat. Do not grow the popup.

---

## Pricing display

Product cost is COGS. Creator fees are marketing spend. A brand's accountant treats them differently. **Never merge them into one number.**

Brand ships their own product:

```
Creator fees     20 x Rs 6,000      Rs 1,20,000
Product          you're shipping              -
Managed fee      15%                  Rs 18,000
                                    -----------
Total                               Rs 1,38,000
```

Retail purchase needed:

```
Creator fees     20 x Rs 6,000      Rs 1,20,000
Product          20 x Rs 1,500        Rs 30,000
Managed fee      15%                  Rs 18,000
                                    -----------
Total                               Rs 1,68,000
```

The managed fee is shown openly as a line item. Hiding it inside the per-creator rate is refused — "they were taking a cut we couldn't see" is the one story that kills a marketplace.

---

## Logistics

### Product cost only counts when someone buys at retail

A brand selling hair oil at Rs 1,500 makes it for perhaps Rs 250. Charging Rs 1,500 x 20 for their own product is nonsense and they will spot it.

| How it reaches the creator | Product cost in budget |
|---|---|
| Brand couriers own stock | Rs 0 — their COGS |
| Brand orders from own store / Amazon MCF | Rs 0 — their fulfilment |
| Bought at retail | price x N — real money moves |

### The MCF unlock — zero backend change

`ShipmentService.markShipped` records **carrier, tracking number, tracking URL** as free text. Amazon MCF returns exactly those three things.

So MCF works today with no integration, no Amazon API, no new backend. It is a label and an instruction.

**What we build is one CSV** — every creator address in Amazon MCF bulk-upload format. Brand does one download, one upload. Amazon ships 20 parcels.

> UNVERIFIED: the exact Amazon India MCF bulk-upload format and which seller plans support it. Must be confirmed by ops/Vikram before this appears in any brand-facing copy.

### We coordinate. We never own the parcel.

Holding stock means warehousing, inventory risk, returns, damaged goods, and GST across states. That is a logistics company — a different business with different margins.

Mechanically it also does not work today: `markShipped` requires a brand principal, and there is no admin or ops path to it. If we ship, we cannot even record it.

Market evidence supports this: Aspire collects shipping details and tracks order status but still requires brands to fulfil manually. Grin automated it only by integrating with the brand's own e-commerce store — not by warehousing.

### Reimbursement is refused

There is no reimbursement or expense money path anywhere in the backend (zero references). Building one means a new payment type, new invoice, new GST treatment, new dispute surface.

Worse: the creator fronts cash. Nothing damages creator trust faster than "I spent Rs 649 three weeks ago and I'm still waiting." And it is an obvious fraud hole — buy, claim, return to Amazon.

**Substitute, needing zero code:** add the product cost to the creator's fee. Rs 6,000 + Rs 649 = Rs 6,649, "this includes the product, please buy it." The money rail already exists — it is the fee. Works for products under ~Rs 2,000 that are easily available online. Anything expensive or exclusive: brand ships.

---

## Slot claiming — consent and address are ONE action

The creator's "yes" IS the address form.

```
Slot card shows: brand, product, what to post, fee, deadline
        -> [ Claim this slot ]
        -> [ address form, pre-filled from profile ]
        -> [ Confirm — I'm in ]
        -> slot filled
```

| Worry | Why it goes away |
|---|---|
| Do they actually want this collab? | They tapped Claim on a card showing brand, product, fee, deadline. Stronger consent than any confirmation email. |
| Can we give the brand the address? | The brand only ever receives addresses from creators who already said yes. |
| What if they agree but never send an address? | **That state cannot exist.** No address = no claim. Designed out. |
| Privacy | The creator sees exactly who gets their address before typing it. |

This is creator-initiated — claiming a listed, paid slot, not applying and hoping. Entirely different from a stranger asking for a home address.

### The fill window

Addresses are held by the PLATFORM, not forwarded instantly.

```
Campaign live, N slots open
   -> creators claim (brand sees names, handles, stats — NOT addresses)
   -> brand may remove up to 2 creators, no reason needed
      (their address is deleted; the brand never saw it)
   -> 72h window closes
   -> addresses released for the FINAL list only
   -> one file -> Amazon MCF / courier
```

Brand gets ONE file. Partial fill is clean: 17 of 20 claimed means ship to 17 and Rs 18,000 returns automatically — which is also the best marketing line we have ("you only pay for creators who actually took the slot").

### Skip the negotiation ladder

`CollaborationStatus` has 13 states (`APPLIED -> SHORTLISTED -> IN_NEGOTIATION -> TERMS_AGREED -> CONTRACT_PENDING -> ...`). That ladder IS the headache. Slots go straight to `CONTRACTED` — the terms were on the card, and claiming them is agreeing.

---

## The trade-off, stated in the copy

**"Do it for me" brands give up per-creator approval. That is the deal.**

They set the rule once — 15K, Marathi, Maharashtra — and the platform enforces it 20 times. What they do not get is a veto on each individual creator, because that veto IS the headache they said they did not want.

A brand who wants to hand-pick uses Manual or Open. Those lanes still exist.

**Two free swaps** during the fill window are the safety valve — enough to remove someone who feels off, not enough to rebuild manual approval inside the automated lane. A brand asking for swap #6 is on the wrong tier and should be moved.

On the tile, plainly:

> **Do it for me**
> You set the bar — followers, language, location. We fill it with creators who meet it. Swap up to 2, no questions asked.

---

## REVISION — Meera runs it, no new UI (Swapnil, 2026-09-12)

Supersedes the "three-lane chooser modal" as the v1 build. The chooser stays a
later nicety; the tier ships as a Meera conversation plus the notification
system, because both already exist.

Meera already has six tools: `CreateCampaignExecutor`, `CalculateBudgetExecutor`,
`ShowCreatorsExecutor`, `RequestPaymentExecutor`, `ConfirmLaunchExecutor`,
`GetCampaignPerformanceExecutor`. That covers ask -> draft -> price -> show
creators -> take payment -> launch. `NotificationService` + `EmailWorker`
(outbox with leases) already deliver the progress emails.

### The sequence

```
1  Meera asks the 5 questions in chat          [tools exist]
   - product or service is asked HERE (one tap, it changes the price)
   - HOW you ship is NOT asked here
2  Meera shows the plan + price -> brand approves & funds   [tools exist]
3  ONLY NOW: "How will the product reach creators?"          [new]
   asked after payment, when it is a logistics detail, not a barrier
4  Slots open. Creators claim; address is captured AT CLAIM  [new]
5  Email: "20 of 20 filled"                                  [new trigger]
6  Brand confirms the line-up (2 free swaps)                 [new]
7  Address CSV assembled from claims -> in-app download      [new]
   email NOTIFIES, it does not attach
8  Reels go live -> auto-approve at 48h unless flagged       [Ruling 4]
```

### Ruling 7 — Meera proposes. The brand approves anything that spends money.

Meera must never approve creators, release funds, or confirm a line-up on the
brand's behalf. She prepares and presents; a human clicks. This is both a
liability position and a trust position, and it is not negotiable for the
managed tier.

### Ruling 8 — Split the product question in two.

"Product or service?" is asked up front, because it changes the number the brand
is approving. "How will you ship it?" is asked AFTER payment. Asking logistics
before the money is a barrier at the exact moment we want conversion; asking it
after is a detail.

### Ruling 9 — Meera does not chase creators for addresses.

The address is captured at slot claim (see "Slot claiming" above), so there is
nothing to chase and no conversational step to build. Note also that creator-side
Meera sits behind `MEERA_CREATOR_ENABLED` and is not deployed — do not design a
flow that depends on it.

### Ruling 10 — The CSV is never an email attachment.

Twenty creators' home addresses and phone numbers must not sit in an inbox that
can be forwarded, breached, or synced to a personal device. The email notifies;
the brand downloads it in-app, behind their login.

### What this revision still needs built

| Piece | Size |
|---|---|
| Post-payment shipping question | small |
| Address captured at slot claim | small |
| Milestone notification triggers (slots full, CSV ready) | small |
| Address CSV builder + in-app download | small |
| Line-up confirm with 2 swaps | medium |
| Eligibility enforcement (Ruling 1) | medium |
| Auto-approve timer (Ruling 4) | medium |

No new brand UI. No chooser modal. No forms.

---

## RULINGS

### Ruling 1 — Requirements must enforce. BUILD THIS NOW, independent of the tier.

Campaign `requirements` is stored as a JSON list of strings and read in exactly three places (`CampaignMapper`, `CreatorCampaignMapper`, `CampaignTemplateService`) — all of which convert it to a display list. **Nothing reads it to make a decision.**

`CreatorCampaignService.browse()` filters on campaign status, deadline, budget, platform and niche. The creator's own follower count is never consulted. `apply()` checks only that the campaign is ACTIVE, the deadline has not passed, and they have not already applied.

**So a 5K creator can apply to a campaign requiring 15K, and nothing stops them.** The only thing standing between the mismatch and the brand is a human reading applications — which is exactly what this tier deletes.

This is a **live expectation gap today**, not a future feature gap. Brands are typing requirements now and reasonably assuming they are enforced.

Fix: structured eligibility (`minFollowers`, `minEngagement`, `location`, `language`), enforced at browse, at claim, and server-side at fill. The data already exists — `PlatformStat` carries followers, engagement rate, verified flag and handle, synced from the creator's connected account, not self-declared.

Result: a 5K creator never sees the slot, never applies, is never rejected, never gives an address.

### Ruling 2 — Never reject a creator after taking their address.

Quality bar goes BEFORE the Claim button. Rejection-after-address is the sequence that gets us screenshotted. Design it out now.

### Ruling 3 — Guarantee delivery, never performance.

All campaign analytics on this platform are creator-reported, not independently verified. Any copy implying guaranteed views, reach or ROI is **rejected at the CMO desk.**

"20 creators will post" is a promise we can keep. "20 creators will get you 500K views" is one we cannot, and it is the exact claim that ends in a chargeback.

What we do promise: slots unfilled at window close are released back automatically. You only pay for creators who actually posted. No agency in India offers that, because none of them hold the money in a ledger.

### Ruling 4 — "Approve once" must be true.

Today approval is one click per deliverable — `BrandDeliverableService.approve()` takes a single deliverable id and releases that creator's money individually. 20 creators = 20 approvals. Selling "zero headache" and delivering 20 clicks is a broken promise.

**Approve by silence:** reels auto-approve 48 hours after going live unless the brand flags one. She only ever touches exceptions.

This is the category norm, not a risky idea — Billo gives brands 3 business days to review before auto-approval, and expires unapproved applications at 14 days.

Needs Priya sign-off (releases money on a timer) and Kabir review.

### Ruling 5 — Do not scrape Amazon for price verification.

Prices change hourly, scrapers break constantly, and it runs against Amazon's terms. The brand types the price, we hold that amount plus a ~15% buffer, and the real invoice trues it up. Ten minutes of policy instead of a scraper we maintain forever.

### Ruling 6 — Brand-facing copy says "Slots", never "Hype".

`HYPE` is an internal `CampaignIntentType`. It means nothing to a founder.

Also: "escrow" stays out of all brand and creator copy per the existing ruling — vocabulary is Secure Payments / secured funds.

---

## OPEN — Swapnil only

1. **Do we sell a managed tier at all?** Everything above is moot without this.
2. **The fee.** 15% of spend is my proposal. Rohan owns the unit economics.
3. **Who staffs it?** Without a named person this is an idea, not a tier. The third tile must not ship before this is answered — a button with no engine behind it produces refunds and a bad first impression, which costs more than not having the tile.
4. **If a product must be bought at retail, who clicks buy?** Brand buying is simple and needs no new money path — recommended for v1. Us buying makes Influora a purchasing agent, with ownership, GST and cancellation questions. That is a CA question, not a product one.

---

## Appendix A — What exists vs what does not

**Already built**

| Piece | Where |
|---|---|
| Meera writes a brief from a few answers | `CreateCampaignExecutor` |
| Budget derived as rate x slots | `CampaignService` L506 |
| Funds held per campaign, released on approval | `EscrowService.tryReleaseOnApproval` |
| Payout to creator bank | `PayoutService` (queues to RazorpayX) |
| Shipment address -> shipped -> received | `ShipmentService` (per creator) |
| Follower / engagement / verified data | `PlatformStat` |
| Contracts with milestones, two-party e-sign | `ContractService` |

**Not built**

| Piece |
|---|
| Three-lane chooser modal |
| Structured eligibility fields + enforcement (Ruling 1) |
| Slots auto-fill (nothing in src/main increments `slotsFilled`) |
| Auto-accept on claim |
| Approve-all / approve-by-silence (Ruling 4) |
| Address held until fill-window close |
| Swap / remove during window |
| Bulk address CSV |
| Product fields on Campaign — no `productUrl`, `productPrice`, `fulfilmentMode` or `isService`; no product catalog (Shopify integration is OAuth + webhooks + order verification only) |
| Saved address on creator profile (addresses are per-collaboration only) |
| Any expiry / timeout job for collaborations or shipments |
| Admin or ops path to `markShipped` |
| A human assigned to run it |

---

## Appendix B — Market evidence

Every mechanic proposed here already exists in the market. We are behind on rules, not on systems.

| Proven move | Who | Our version |
|---|---|---|
| Sell content in packs, price per video | Billo ($99/video), Trend.io ($550 / 6 videos) | rate x slots — already built |
| Vet creators at platform entry | Billo (all 5,000+ vetted before joining) | baseline bar to join Influora |
| Gate who is even allowed to apply | Trend.io | Ruling 1 |
| Auto-approve after N days | Billo (3 business days) | Ruling 4 |
| Expire stale applications | Billo (14 days) | we have no expiry job at all |
| Fulfil through the brand's own store | Grin | Amazon MCF / their store |
| Collect addresses, brand still ships | Aspire | same — we never own the parcel |

**India:** the "just handle it" brand is currently served by agencies — Kofluence, One Impression / OPA, Pulpkey, Confluencr, Grynow. All describe themselves as managed/end-to-end services, not software.

- **Opportunity:** nobody in India sells this as a product with transparent per-creator pricing and money held safely. Agencies quote per campaign, opaquely.
- **Warning:** if we staff this with people, we become an agency with a software skin and inherit agency economics. The tier only works if the human does 30% of the work, not 80%.

> Source caution: much of the above comes from vendor sites and comparison blogs, several written by direct competitors. Help-doc mechanics are reliable; feature claims are marketing. Before any of this enters our positioning, someone should sign up as a brand on Billo and Trend and walk the real flow. Assign to Nisha or Ishaan — half a day, screenshots, actual screens.

---

## Handoff

```
Tejas -> Swapnil   | approve/kill tier, fee, staffing, retail-buy ruling
Tejas -> Priya     | Ruling 1 (eligibility enforcement) + Ruling 4 (timed release)
Tejas -> Vikram    | confirm Amazon India MCF bulk format before any copy ships
Tejas -> Nisha     | competitor teardown: Billo + Trend, real flows, screenshots
```
