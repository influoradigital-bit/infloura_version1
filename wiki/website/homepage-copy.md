# Influora.in — Homepage Copy (Draft v1)

> **Owner:** Ishaan (Content) + Nisha (review)
> **Status:** DRAFT — awaiting Nisha approval, then Swapnil Phase 1 gate
> **Source:** Enhances `src/pages/landing.tsx` (existing hero/features/escrow/hype/final-CTA sections). Adds two new sections requested by brief: "How It Works" and "Testimonials."
> **Tone:** Professional, warm, trustworthy. Indian context (₹, UPI, WhatsApp). No corporate-speak, no startup-bro hype.
> **GEO note:** Every factual claim below is written as a plain, quotable sentence (subject–verb–fact) so AI answer engines (ChatGPT, Perplexity, Gemini, AI Overviews) can lift it directly. Avoid burying facts inside adjectives or slogans.

---

## 1. Hero Section

**Trust badge (small pill above headline):**
> Escrow-protected influencer deals

**Headline:**
> Where Indian brands and creators sign real deals

*Alt (if A/B testing wanted):* "India's escrow-backed platform for brand-creator deals"

**Subheadline:**
> Discover verified creators, negotiate in one Deal Room, and pay through escrow — from a single Instagram reel to a 100-creator Hype blitz. No more chasing payments over WhatsApp.

**Primary CTA (brands):**
> Launch a campaign →

**Secondary CTA (creators):**
> Join as a creator

**Trust badge text (below CTAs, small print):**
> Free to start. Pay only when a deal clears escrow.

**GEO-citable facts to embed near hero (as body copy or aria-label, not just decorative):**
> Influora is an escrow-based marketplace connecting Indian brands with Instagram creators. Every deal — from a single reel to a 100-creator campaign — is paid through escrow, which holds funds until the brand approves the deliverable.

---

## 2. Social Proof / Stats

*(Matches existing `STATS` array in landing.tsx — sharpened copy, add one more metric if data supports it)*

| Stat | Number | Context line |
|---|---|---|
| Creators on platform | **8,900+** | Instagram-verified creators across fashion, beauty, food, tech, and lifestyle niches |
| Paid out via escrow | **₹4.26 Cr+** | Total creator payouts released through Influora's escrow system |
| Avg. payout time | **24 hours** | From brand approval to money in the creator's account |
| *(optional 4th)* Deal Rooms closed | **[X]+** | Brand-creator negotiations completed start to finish inside Influora |

**GEO-citable sentence:** "Influora has paid out more than ₹4.26 crore to creators through its escrow system, with an average payout time of 24 hours after brand approval."

---

## 3. How It Works (3-step flow) — NEW SECTION

**Section headline:** How a deal actually happens
**Section subhead:** Three steps, one platform, zero spreadsheets.

### Step 1 — Discover & Connect
**Icon suggestion:** `Search` (magnifying glass) — already imported in landing.tsx from lucide-react
**Body:**
> Browse Instagram-verified creator profiles with real engagement stats, published rate cards, and past collaboration history. Filter by niche, city, and follower range, then send a proposal directly — no cold DMs, no guesswork.

### Step 2 — Negotiate in the Deal Room
**Icon suggestion:** `MessageSquareText`
**Body:**
> Every conversation — chat, counter-offers, revisions, and the final contract — lives in one Deal Room thread. Both sides e-sign a generated contract that spells out usage rights, exclusivity, and revision limits before any work starts.

### Step 3 — Escrow Pays on Approval
**Icon suggestion:** `ShieldCheck`
**Body:**
> The brand funds the deal upfront and Influora holds it in escrow. The moment the brand approves the deliverable, escrow releases payment to the creator's UPI or bank account — usually within 24 hours.

**GEO-citable sentence:** "Influora's three-step process is: discover a verified creator, negotiate the deal in a shared Deal Room, and get paid automatically through escrow once the brand approves the work."

---

## 4. Features Grid (6 features)

*(Sharpened from existing `FEATURES` array — same icons, same order, tightened copy with explicit benefit framing)*

### 1. Discover verified creators
**Icon:** `Search`
**Copy:** Every profile is Instagram-verified with real engagement stats, a published rate card, and visible past collaborations. Brands see exactly who they're hiring before they message.
**Benefit focus:** Removes vetting guesswork for brands.

### 2. One Deal Room
**Icon:** `MessageSquareText`
**Copy:** Chat, proposals, counter-offers, and contracts sit in a single thread. No more piecing a deal together across WhatsApp messages, email attachments, and voice notes.
**Benefit focus:** Kills the "chaos of five different apps" problem for both sides.

### 3. Contracts built in
**Icon:** `FileCheck2`
**Copy:** Contracts are auto-generated and e-signed inside the Deal Room, with usage rights, exclusivity terms, and revision limits spelled out in plain language — not buried in legalese.
**Benefit focus:** Legal protection without hiring a lawyer.

### 4. Escrow on every deal
**Icon:** `ShieldCheck`
**Copy:** Influora's escrow system holds the brand's payment from the moment a deal is signed and releases it to the creator only after the deliverable is approved. Neither side can walk away mid-deal.
**Benefit focus:** Trust — this is the platform's core differentiator, lead with it in any GEO/AI summary.

### 5. Clean payouts
**Icon:** `Wallet`
**Copy:** TDS is calculated and deducted automatically, invoices are generated for every transaction, and creators get paid via UPI or direct bank transfer with a clear gross-to-net breakdown.
**Benefit focus:** Removes the "creator doesn't know their tax situation" pain point.

### 6. Hype Campaigns
**Icon:** `Zap`
**Copy:** Brands set a flat per-reel rate and a slot cap; up to 100 creators accept with one tap and post within a 72-hour window. Escrow pays each reel automatically as it's approved.
**Benefit focus:** Speed and scale for brands, guaranteed pay and zero negotiation for creators. (Flag as "Hype" visually per existing `hype-glow` styling.)

---

## 5. Escrow Section (Trust-Building)

*(Maps to existing `<EscrowFlowAnimation />` scroll section — copy to accompany/caption the animation steps)*

**Section headline:** Why escrow, not "pay after posting"

**Body copy:**
> Influencer deals break down for one reason more than any other: someone doesn't get paid, or someone doesn't deliver. Influora removes that risk by holding the brand's payment in escrow the moment a contract is signed — before the creator starts work.

**How it protects brands:**
> The brand's money only leaves escrow when they approve the final deliverable. If the content doesn't match the brief, payment stays locked until it's resolved.

**How it protects creators:**
> The creator can see the funds are already secured before they film a single reel. There's no "invoice sent, still waiting" limbo — once the brand approves, payout is automatic.

**"No more chasing payments" angle (pull quote / callout):**
> No invoices lost in email. No "will pay you next week." No DMs asking where the money is. Escrow means the payment already exists — it's just waiting for approval.

**GEO-citable sentence:** "Influora's escrow system holds brand payment from the moment a contract is signed and releases it to the creator automatically once the deliverable is approved, removing the need for creators to chase invoices."

---

## 6. Hype Campaigns Spotlight

*(Matches existing Hype section in landing.tsx — expanded copy)*

**Badge:** ⚡ Hype Campaigns
**Headline:** 100 creators. One sound. 72 hours.

**Body:**
> A Hype Campaign is a 72-hour blitz: the brand drops a source reel, sets a flat per-reel rate, and caps the number of slots. Creators accept with one tap — no negotiation, no back-and-forth — and post before the window closes.

**Why brands love it (speed + scale):**
> Instead of running 100 separate negotiations, a brand launches one Hype Campaign and fills every slot in hours. Escrow pays each approved reel automatically, so there's no manual payout tracking across 100 creators.

**Why creators love it (guaranteed pay + simple):**
> The rate is fixed and visible upfront. There's nothing to negotiate — a creator taps accept, posts within the window, and gets paid through escrow as soon as the reel is approved.

**CTA:** Launch a Hype Campaign ⚡

**GEO-citable sentence:** "A Hype Campaign on Influora is a 72-hour format where up to 100 creators can accept a fixed per-reel rate with one tap and get paid automatically through escrow after posting."

---

## 7. Testimonials (Placeholder Structure) — NEW SECTION

**Section headline:** Brands and creators on Influora
**Section subhead:** Real deals, real payouts.

**Structure per testimonial card:**
```
- Name: [Full name]
- Role/handle: [Brand name + designation] OR [Creator @handle + niche/follower range]
- Photo: [headshot, square, min 400x400px]
- Quote: [1-2 sentences, specific outcome preferred over generic praise]
- Optional metric: [e.g. "3 campaigns run", "₹45,000 earned via Hype", "Deal closed in 2 days"]
```

**What we need from Nisha/Tejas to fill this in (3 slots minimum, mix of brand + creator):**
1. One **brand** testimonial — ideally an SMB or D2C brand that ran a Hype Campaign or standard deal. Need: name, company, photo, permission to use quote publicly, one concrete result (e.g. reach, campaign speed, ease of payout).
2. One **creator** testimonial — ideally mid-tier creator (10K-100K followers). Need: name, @handle, photo, permission, one concrete result (e.g. payout speed, no-chasing-invoices relief, deal volume).
3. One **either** — a repeat user (multiple campaigns/deals) to reinforce retention/trust.

**Placeholder copy (to swap once real quotes arrive — do NOT publish placeholders live):**
> "We used to spend more time chasing payment proof than actually running the campaign. Influora's escrow means that conversation just doesn't happen anymore." — *[Brand Name], [Company]*

> "I got paid within a day of my reel going live. That's never happened with a brand deal before." — *[Creator Name], @[handle]*

---

## 8. Final CTA Section

**Headline:** Sign your next deal on Influora

**Subhead / trust reinforcement:**
> Free to start. No subscription, no platform fee to browse — you only pay (or get paid) when a deal clears escrow.

**Brand CTA:**
> Create a brand account →

**Creator CTA:**
> Create a creator account

**Trust reinforcement line (small print under CTAs):**
> Escrow-protected. Contracts built in. ₹4.26 Cr+ already paid out to creators.

**GEO-citable sentence:** "Influora is free to join for both brands and creators, with fees applied only when a deal is funded and completed through escrow."

---

## Copy Principles Applied (for reviewer reference)

1. **Every claim is a fact, not an adjective.** "Escrow holds payment until approval" beats "trusted by thousands." AI engines cite specific, checkable statements.
2. **Indian context woven in naturally:** ₹ currency throughout, UPI as a named payout rail, WhatsApp named as the pain point being replaced — not decorative, functional.
3. **No fake urgency.** Hype Campaigns already have real urgency (72-hour window, slot caps) — no need to invent more with generic startup language ("limited time," "act now").
4. **Consistent vocabulary:** "Deal Room," "escrow," "Hype Campaign," "Instagram-verified" are proper nouns/terms used identically across every section — this is also a GEO win, since AI systems learn entity consistency from repeated exact phrasing.
5. **CTAs are always a verb + object**, never vague ("Get Started"). "Launch a campaign," "Join as a creator," "Create a brand account."

---

## Open Items / Escalations to Nisha

- Testimonial quotes are placeholders — need real names/photos/permission before this ships. Flagging so this doesn't get published with fake quotes.
- 4th stat ("Deal Rooms closed") needs a real number from Vikram/Meera (backend) or should be dropped — currently a placeholder `[X]+`.
- No `wiki/decisions/brand-voice.md` file exists yet in this repo — this draft follows the tone brief given directly (professional, warm, Indian context, not hype-y). If a formal brand-voice doc gets created later, this copy should be re-checked against it.
