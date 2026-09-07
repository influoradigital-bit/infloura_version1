# Stitch Designs — Content & Imagery Assessment

**From:** Ishaan (Content) · **For:** Swapnil, routed via Nisha
**Date:** 2026-09-07
**Scope:** Copy and imagery only, across all 11 Google Stitch designs. Layout/UI/component decisions are not mine to call — this is "is the *word* and *picture* usable, and what has to change before either touches production."
**Builds on:** Swapnil's confirmed findings (escrow ban, fabricated regulatory status, fabricated entity name, fabricated traction, fee contradiction, tax overclaim, Meera page as the honesty model). I did not re-litigate those — I cite the exact lines they show up on and add what else breaks on a copy/imagery read.

---

## 0. Bottom line

**None of the 11 pages are ready to ship as-is.** Every one of them uses banned "escrow" vocabulary, most fabricate specific stats and named testimonials, three of them print three *different* and mutually contradictory company registration numbers, and one (`transparent-pricing-economics`) invents a 7% platform fee that a standing CTO ruling explicitly forbids printing anywhere on the site.

**But the batch is not worthless.** `redesigned-homepage` and `transparent-pricing-free-pro-plans` are close enough to our live structure and even our live wording that they're the fastest real editing job in the batch, not a rewrite. `festival-box-mumbai-2026` is a near-verbatim lift of a real, Tejas-approved deck — the program is real, only the automation-that-doesn't-exist-yet claims need to go. `meera-for-creators` already has the best honesty scaffolding in the whole set (Coming Soon / waitlist / "every line is scripted") and should be the template other pages get pulled toward, not the other way round.

| Page | Copy salvage | Verdict |
|---|---:|---|
| `redesigned-homepage` | ~70% | Best in batch — mirrors live landing.tsx almost line for line. Edit, don't rewrite. |
| `transparent-pricing-free-pro-plans` | ~80% | Nearly matches pricing.tsx's actual tier data already. Edit, don't rewrite. |
| `festival-box-mumbai-2026` | ~55% | Real, CMO-approved program underneath. Strip automation-not-built-yet claims + escrow. Route through Tejas. |
| `meera-for-creators` | ~45% | Best honesty framing in the batch. Cut two fabricated testimonials, strip vault language, keep the rest. |
| `how-it-works-for-brands` | ~30% | Chapter structure and pacing map onto live's 6 steps. Every number and testimonial must go. |
| `how-it-works-for-creators` | ~30% | Same as above. |
| `72-hour-hype-blitzes` | ~25% | Hour-by-hour narrative device is reusable if the specific hour thresholds get verified or softened. |
| `blog-insights-hub` | ~20% | Topic taxonomy is a usable content calendar. Every post, byline, and stat is fabricated. |
| `about-us-leadership` | ~15% | Section skeleton only. Traction numbers, "100% payout," ISO cert, dual-entity listing all contradict live facts. |
| `transparent-pricing-economics` | ~10% | Invents a whole different, forbidden pricing model. Don't use as a copy source — use the sibling file instead. |
| `deal-room-feature-os` | ~10% | Contrast-pair structure is reusable. Every stat, every testimonial, and most feature-specific numbers are unverified or fabricated. |

---

## 1. Imagery

**31 AI-generated photographs, several paired with named testimonials carrying specific follower counts (e.g. "Priya Sharma @priyacreates · 84K"). My read: the unnamed atmosphere shots are a lower-risk category than the named-face-plus-quote-plus-stat combination, and the second category should not ship at all in its current form.**

### The core problem isn't "AI-generated," it's "AI-generated presented as evidence"

A synthetic photo used as pure atmosphere — someone filming a reel in a sunlit apartment, hands holding a phone showing a UPI success screen, a team collaborating in an office — carries the same risk as any stock photo: none, as long as it isn't captioned as a specific real person or claimed as a real event. That's most of the 31.

The problem is the subset stapled to a name, a handle, a follower count, and a quote in first person: `Priya Sharma @priyacreates · 84K`, `Tanvi Shah`, `Rohan Verma @rohan.techlife`, `Deepika R., Founder & CEO, SkinBloom Mumbai`, `Arjun Sen, Tech & Lifestyle Creator (320k followers), Bengaluru`, `Kabir Mehta @kabirtech`. Every one of these is a fabricated person given a fabricated Instagram handle, a fabricated follower count, and words they never said, about a product several of them (Meera-for-Creators specifically) haven't even launched yet. That's not a tone problem, it's the same category of defect as the fabricated traction numbers already flagged — a specific, checkable claim with no backing — except a face is *more* checkable than a stat. Anyone can reverse-image-search the photo or search the handle and find nothing. On a testimonial specifically, Indian advertising self-regulation (ASCI's influencer/endorsement guidelines) requires a testimonial to reflect a genuine user's actual, current opinion; a synthetic face with an invented quote and an invented follower count fails that test outright. This is worth a look from whoever owns legal/compliance review, not just a brand-voice fix.

The single worst instance: `meera-for-creators-ai-pr-manager.txt` correctly frames the whole page as "In the Works · Coming Soon" with "Join the Waitlist" and even discloses "Every line in this demo is scripted" for the chat mockup — and then, a few sections later, runs a first-person testimonial from a named creator ("Rhea Sengupta") describing results from using the product. A page that is honest enough to disclose its chat demo is scripted should not turn around and present a user testimonial for a product that doesn't exist yet as genuine.

### What to do instead

- **Keep** unnamed, uncaptioned atmosphere/lifestyle photography (or good stock) for hero backgrounds, feature illustrations, and mockup context — the same way `landing.tsx`'s own `DealRoomHeroThread` mockup already ships fictional UI with numbers, labeled "Illustrative Deal Room flow · Amounts shown are example figures." Anything that's clearly a mockup, clearly labeled as illustrative, and attached to no real name is fine.
- **Cut** every named-face-plus-quote-plus-stat testimonial in the batch. Don't replace them with different invented names or different invented numbers — that's the same defect with new details. Ship the page without a testimonial section until there's a real customer willing to be quoted, the same way `about.tsx` already handles this (a "verified profiles · licensed payment partner" trust line, no client logos, no customer count, per the F-0342/F-0343 removal Swapnil already ordered once).
- **If and when** real brand or creator customers exist and consent to being featured, use their real photo and their real words. That's the only fix that isn't itself a fabrication.

### Resolution: 1376×768 is workable for cards, not for hero-sized full-bleed images

1376×768 (≈1.79:1) is fine for the smaller uses in these designs — blog grid thumbnails, testimonial avatars, the ~300–400px feature-card images — where the container is well under the source size. It is **not** enough for the full-bleed hero treatments several designs use it for (`about-us-leadership`'s founder portrait, `deal-room-feature-os`'s balcony shot at `h-[460px]` `object-cover`, `how-it-works-for-brands`'s full-width background at reduced opacity). `object-cover` crops, it doesn't add resolution — stretched across a 1440–1920px desktop viewport, or viewed on any 2×/3× pixel-density screen (which is most modern phones and laptops), a 1376px source will visibly soften. If any of this AI atmosphere photography survives into a hero-sized slot, it needs to be regenerated or upscaled to at least ~2400×1350 first. Anything staying in a card-sized slot is fine as-is on the resolution axis.

---

## 2. Page-by-page rewrite brief

For each page: what's good enough to keep close to verbatim, what has to be cut outright, and what the corrected replacement claim is. I did not invent replacement numbers anywhere below — where the design states something unbacked, the fix is either "cut" or "use the live page's existing hedged line," never a new number.

### 2.1 `redesigned-homepage` (compare: `src/pages/landing.tsx`)

**Keep near-verbatim** — these already match live word for word or are a strict improvement:
- Hero headline "Where brands and creators sign real deals" and sub "Discover creators, negotiate in one Deal Room, and pay with protection built in — from a single reel to a 100-creator Hype blitz." — identical to live.
- "Everything between the DM and the payout" and "100 creators. One sound. 72 hours." section headers — identical to live.
- The FAQ *question set* is the same six questions live already runs and answer-engine-optimizes for — reuse the questions, but swap every answer for live's actual `FAQS` copy (see below).

**Cut and replace:**
| Design line | Problem | Replacement |
|---|---|---|
| "Escrow Active" badge in the Deal Room hero mock (line 25), inconsistently followed later by "Funds Secured" in the same thread | Banned word, and internally inconsistent even within the mock itself | Drop the status badge from the mock entirely, or use "Funds Secured" only — match `DealRoomHeroThread`'s existing copy |
| "Funds lock into escrow before any work begins" | Banned word | "Funds lock before work starts and release on approval." (live `FEATURES` copy, verbatim) |
| "Automated invoice generation showing recorded 194J TDS deductions" | Hard-codes Section 194J; actual withholding section depends on the creator's registration (194J vs 194C) | "Invoices generated with any recorded TDS shown" (live, verbatim — never name a section in customer copy) |
| "Works out an optimal budget and per-reel rate from your target ROAS" | "Optimal" and "target ROAS" are unbacked — the product doesn't track a target ROAS input anywhere documented | "Works out a budget and per-reel rate from your goal" (live, verbatim) |
| "Proposes the funding escrow steps" | Banned word | "Proposes the funding step" (live, verbatim) |
| Pricing strip: "✓ Standard Deal Room escrow protection" (Free), "✓ Escrow guaranteed payouts on every deal" (Creator) | Banned word both places | "Payment protection on every deal" / "Payment protection before you start work" (live phrasing) |
| Pricing strip: "✓ Unlimited Hype Campaigns" (Pro) | Not a real Pro differentiator — live `pricing.tsx`'s Pro tier list has no Hype-campaign cap or unlock; this is an invented feature | Cut, unless Product confirms Hype campaigns are actually tier-gated (they don't appear to be) |
| FAQ #3 answer: "Influora locks your payment with our RBI-authorized escrow gateway rail before content production begins." | Banned word + misattributes the partner's RBI authorization to Influora itself (already flagged) | Use live's FAQ answer verbatim: "...the brand deposits the deal amount with a licensed payment partner when the contract is e-signed, the creator delivers, the brand approves, and only then does the payment release..." |
| Footer: "Powered by seamless escrow and verifiable analytics" | Banned word | Drop the line or replace with a plain one-liner matching `SiteFooter`'s actual tagline |
| Footer entity block: "Influora Digital Private Limited / CIN: U74999MH2024PTC418921 / GSTIN: 27AAKCI1283M1Z2" | Company name is correct, **but this CIN and GSTIN do not match the numbers used in the other 8 designs that also claim to be our registration** (see §4) | Pull the real CIN/GSTIN from Legal once, use it everywhere, and stop letting page designs re-type it by hand |

Everything else on this page — the feature grid, Meera section shape, Hype spotlight, sales-tracking scoreboard framing, portfolio card, "Creators earn three ways" — is structurally identical to live and needs word-level cleanup only, not a rewrite.

### 2.2 `about-us-leadership` (compare: `src/pages/about.tsx`)

Live About is a plain mission/before-after page with zero traction numbers by deliberate CEO directive (F-0342, F-0343 — this exact defect class was already found and removed once). This design resurrects it with a much bigger, still-fabricated number.

**Cut outright — direct product contradictions, not just tone:**
- "creators keep 100% of what they earn," "100% Payout Retention," "0% creator fee clawbacks," "Zero Commission Markups" — **false.** Live pricing states a 15% creator commission on every tier, unchanged. This isn't unbacked marketing puffery, it's a stated number that contradicts a published number elsewhere on the same site.
- "850+ Verified D2C Brands / 14,000+ Creators Empowered / <3s Instant UPI Settlement / ₹0 Hidden Intermediary Fees" — exactly the traction-block pattern Swapnil already ordered removed from this page (F-0342/F-0343). Do not bring it back with a different number.
- "ISO 27001 Certified" — a real, auditable certification claimed without one on file is a materially different and more serious risk than a puffery line; treat as a compliance flag, not a copy edit.
- "Automated Section 194J & 194R TDS deductions" — hard-codes sections again; live hedges this deliberately.

**Structural/naming problem — flag separately, this is the worst instance in the batch:** the page lists **two different registered entities** ("Influora Digital Private Limited" *and* "Influora Technologies Private Limited") under a plural heading "Registered Corporate Entities," each with its own CIN/GSTIN, in the same section — and then a **third**, different CIN/GSTIN pair for "Influora Technologies Private Limited" in the footer template shared across 8 other pages. Only one company exists (Influora Digital Private Limited). This needs a full stop before anyone edits copy around it — see §4.

**Keep, once rewritten:** the Before/After structural device (agency markup pain → one platform) mirrors live About's own "Before Influora / After Influora" card pattern — reuse the shape, swap in live's plainer wording. "Founded in Mumbai" and the founder name/title are factual and fine to keep. The team-photo section is low risk if the underlying claim (in-house Mumbai team) is true; "100% IN-HOUSE" as a stat chip reads like unnecessary chest-thumping and can just be cut.

### 2.3 `blog-insights-hub` (no live blog copy compared — page didn't exist to read against; general policy applied)

**Keep:** the topic taxonomy (Creator Economics / Tax & Legal Compliance / D2C Growth Playbooks / 72h Blitz Strategies / Brand Case Studies) is a genuinely usable content calendar structure — hand it to Aditya and me as a real editorial plan once real posts exist.

**Cut everything else:**
- Every byline (Rohan Mehta, Pooja Malhotra, Aditya Kashyap, Kavya Sharma, Vikram Sengupta, Arjun Sen) is a fictional author for a post that doesn't exist. Three of these first names collide with real Sage Digital staff (Kavya, Vikram, Arjun) — worth a specific check before anything ships, this is the kind of thing that looks bad if anyone notices.
- "Join 4,200+ D2C founders... every Thursday" — fabricated subscriber count and an unconfirmed cadence commitment. Don't publish a specific number or a specific day until there's a real newsletter running.
- "14 Playbooks / 9 Guides / 22 Frameworks / 11 Case Studies" — fabricated inventory of content that doesn't exist.
- Every post title leans on escrow/vault/sovereign language — same fix as everywhere else, but worth noting titles get indexed and linked, so this is a "write it right the first time" case, not a quick swap later.

### 2.4 `deal-room-feature-os` (no direct live equivalent page read; compared against `landing.tsx`'s Deal Room framing and known product facts)

**Keep:** the "WhatsApp Nightmare vs Influora Peace of Mind" contrast-pair structure mirrors live About's Before/After pattern and the honest "Secure funds" framing already approved in `wiki/decisions/campaigns/brand-lifecycle-film.md`. "Where loose WhatsApp promises turn into legally bound deals" is a strong hook once escrow is stripped out.

**Cut:** the testimonials (Deepika R./SkinBloom with a specific ₹85,000 figure, Arjun Sen at "320k followers") and every headline stat ("99.8% Dispute-Free Deal Completion Rate," "14,280+ DEALS," "28+ hours saved per campaign cycle") — all fabricated per the standing findings.

**Flag, don't assume either way — needs Product/Priya confirmation before use:** the specific "5-day / 120-hour auto-approval window" and the "2-revision cap + ₹5,000–6,000 add-on fee for a 3rd revision" are stated as concrete product mechanics. I have no page in front of me that confirms or denies either number is real. Don't publish a specific hour count or a specific rupee add-on fee until someone who owns `EscrowService`/the contract terms confirms it — and if it is real, it should also show up on the live `/features` page it belongs to, not just in a design nobody's checked.

### 2.5 `festival-box-mumbai-2026` (compare: `wiki/decisions/FESTIVAL-BOX-CMO-ASSESSMENT.md`, Tejas's 2026-09-02 assessment of the underlying deck)

This design is close to a direct lift of a real deck Tejas already reviewed — the Queen Bee model, the three sponsorship tiers (₹20K / ₹25–40K / ₹50–75K), the 5-act shoot flow, and the Day 0→20 timeline all match his notes almost exactly. **The program is real and CMO-approved as a pilot.** But Tejas's own assessment is explicit that most of what makes it work is *manual*, not platform automation, and gives a specific list of banned phrasing this design ignores entirely.

**Cut — contradicts Tejas's infrastructure reality check directly:**
- "Direct Shopify & WooCommerce Synced," "Live Coupon Attributed," "Webhook 200 OK: ₹4,890 GMV," any live/automated scoreboard framing — Tejas's assessment states plainly: *"Coupon tracking DOESN'T EXIST... Live sales scoreboard DOESN'T EXIST... If not, Edition 01 will track sales via manual coupon code reporting."* This design presents automation that is explicitly not built yet as already working.
- "Strictly 6 Brand Slots" — Tejas's recommendation is 3–5 brands max for Edition 01. Use his number, not this one, or get an updated ruling before publishing either.
- Any framing that implies the discovery/hub page already exists — Tejas: *"This page doesn't exist yet... 2-3 weeks of frontend + backend work."*
- The words "automated," "at scale," "proven," and any escrow/RBI-vault language — all specifically on Tejas's own "what NOT to say" list for this exact program.

**Keep, close to verbatim once reframed as Edition 01 / founder-led:** the Traditional Gifting Dilemma pain points, the Queen Bee/Bees distribution model, the 5-act shoot flow (Match → Styled Room → Games → Life & Talk → The Drop), the Day 0→20 working timeline, and the tier pricing itself. Tejas's own approved opening line — **"One box in. Six assets out. For a third of the cost."** — is stronger than this design's "One box in. A styled shoot day. Sales tracked back to every creator." and should replace it outright.

**Route this page through Tejas specifically** before it goes further — he owns the underlying program and has already written the exact positioning rules ("Curated, vetted, tracked, contracted, protected... Pilot, Edition 01, founder-led") this design should have used and didn't.

### 2.6 `how-it-works-for-brands` / `how-it-works-for-creators` (compare: `src/pages/how-it-works-brands.tsx`, `how-it-works-creators.tsx`)

Live runs a plain numbered 6-step list per side. Both designs restructure this as a five-"chapter" narrative — Discovery → Negotiation → Payment lock → Hype scale → Compliance closure (brands) and Discovery → Security → Production guardrails → Treasury/Tax → Hype monetization (creators) — which is a reasonable re-sequencing of the same six steps, not a departure from them.

**Cut:**
- Top-strip stats: "100% Escrow Protection / 0 Days Chasing Invoices / 4.2x Faster Brief-to-Live" (brands) and "₹0 Forever / 100% Upfront / <3 Seconds / Zero Ghosting" (creators) — fabricated or absolute claims the product can't guarantee ("0 Days" is a promise, not a fact).
- "Trusted by 500+ Indian brands" / "Join 2,800+ leading Indian brands" (brands page), "Join 12,000+ Indian digital creators" (creators page) — this is precisely the pattern CEO-DECISIONS.md #4 already banned and F-0343 already removed from About. This design reintroduces it with a bigger, still-unsourced number.
- "Zero platform commissions on your fees" (creators page) — directly contradicts the 15% creator commission, same defect class as the About page.
- Testimonials: Deepika R./SkinBloom, Siddharth Menon/Volt Audio, Tanvi Shah, Kabir Mehta — all fabricated per standing findings.
- "0% Ghost-Follower Tolerance / Automated bot-audits flag engagement pods" — **flag for verification, don't assume either way.** If Influora actually runs engagement-pod/bot detection, this belongs on a real feature page with real language; if it doesn't, cut it.

**Keep and adapt:** the chapter sequencing itself, and the creator profile-card mockup (rate card, on-time score, audience geography) is a genuinely useful concrete visualization live's plain step list doesn't have — worth carrying forward once every number in it (`87% India Tier-1`, `98.4% On-Time Score`, `₹12,500 Avg Reel Rate`) is either replaced with an "illustrative example" label matching `landing.tsx`'s own disclosed-mockup convention, or backed by a real aggregate the product can compute.

### 2.7 `meera-for-creators-ai-pr-manager` (best-behaved page in the batch — already flagged as a good model)

**Keep near-verbatim:** "In the Works · Creator Economy Intelligence · Coming Soon," "Join the Waitlist (Free & Priority Access)," and "⚡ Every line in this demo is scripted. Meera negotiates in real-time based strictly on your private rules." This is exactly the register the rest of the batch should be pulled toward. The five-jobs feature breakdown (Your Rules First / Audit a Brief / Know Where the Money Is / Monday Briefing / Brand Discovery) is a clear, honest, not-yet-shipped feature list — keep the structure.

**Cut — the one serious internal contradiction on this page:** the named testimonials, "Priya Sharma @priyacreates · 84K" (with a "100% PAYOUT SECURED" badge) and Rhea Sengupta's first-person results quote. A page that discloses its own demo is scripted should not also run a testimonial implying a real person already used the product and got results — that's a direct contradiction sitting three sections apart on the same page. Cut both; if a testimonial is wanted later, wait for a real waitlist member willing to be quoted once the product actually ships.

**Rewrite:** every "vault"/"escrow" mention ("v2.4 Escrow Engine," "RBI-Aligned Protected Vaults," "Bank-Grade Vault," "Protected Safety Vaults") → "Secure Payments" / "secured funds," matching the standing vocabulary rule. The vote-on-pricing mechanic (₹899/₹999/₹1,499 tiers) is an interesting honest way to pre-sell a not-yet-priced product — keep the concept, but confirm the credit-to-cost ratios against whatever real Meera-for-Creators economics exist before publishing a number; I don't have that model in front of me.

### 2.8 `transparent-pricing-free-pro-plans` (compare: `src/pages/pricing.tsx`)

This is the second-best page in the batch on copy accuracy — its Free/Pro card bullet lists and three-group comparison matrix are close paraphrases of `pricing.tsx`'s actual `FREE_INCLUDED` / `PRO_INCLUDED` / `MATRIX_GROUPS` data, right down to keeping the fee word-based ("Included" / "Reduced") rather than printing a percentage, which is exactly what the CTO ruling on that file requires.

**Cut:**
- "guaranteed payment safety vault" (intro line), "RBI-Regulated Safety Vault" badge row, "Sec 194J TDS Compliant" footer chip — banned vocabulary + hard-coded section, same fixes as everywhere else.
- Testimonials (Deepika R./SkinBloom "+88% ROAS Lift," Arjun Sen "₹0 Fee Deducted") — fabricated, cut.
- "Traditional influencer agencies hide 30% to 50% agency margins" — an unbacked claim about competitors' pricing; we have no source for this number and shouldn't publish a specific figure about a third party we can't cite.
- Footer entity block — same fabricated/inconsistent CIN-GSTIN problem as every other page (§4).

**Check specifically:** the line "brands pay our platform fee" (no digit) is close to compliant with the word-based-only rule but should get a second look from whoever owns that CTO ruling — the rule is about not implying a rate at all, and this line is borderline enough to want a yes/no rather than a guess from me.

### 2.9 `transparent-pricing-economics` (compare: `src/pages/pricing.tsx`)

**Do not use this file as a copy source.** It invents an entirely different pricing model — a 7% brand platform success fee, a 4% "negotiated volume floor" for enterprise, fixed Blitz packages at ₹1.25L/₹2.50L/₹4.80L, and a specific GST SAC code (998311) — none of which appears anywhere in `pricing.tsx`, and the 7% figure is the single most dangerous line in the entire batch: `pricing.tsx`'s own file header states, verbatim, *"there is NO sign-off to print 7%/10% anywhere on this page."* This design does exactly that, in six different places, in large type, as a headline number.

Its sibling file (`transparent-pricing-free-pro-plans`, §2.8 above) already gets this right — the fee is word-based only, matching live. Use that file as the base for any pricing-page rewrite and treat this one as a cautionary example, not a starting point. The only structurally reusable idea here (an enterprise/volume tier row, a "vs. traditional agency" comparison table) should be rebuilt with either a real, sanctioned number from Swapnil/Priya or no number at all — never this file's invented 7%/4%.

### 2.10 `72-hour-hype-blitzes` (compare: `landing.tsx`'s Hype section, `HYPE_STEPS`)

**Cut:** every headline stat ("3.8x Velocity," "2.4M Views... in first 36 Hours," "+340% GMV," "120+ Hours Saved," "100% Payout Rate") and both testimonials (Aditya Kashyap/PureSkin Labs, Pooja Sharma) — fabricated per standing findings. Note: "Aditya Kashyap" is also the name of our real SEO lead — same accidental-collision issue as the blog page's fake bylines, worth a specific scan before anything ships. Escrow/vault language ("RBI Vault Regulated," "Scheduled Bank Trustee Protected") — same standing fix.

**Flag, don't assume either way:** the hour-by-hour SLA breakdown (Hour 0–12 drop, 12–48 production, 48–60 review, 60–72 surge, with a specific "12-hour auto-approval" and "48-hour submission deadline") is more granular than anything `landing.tsx`'s own `HYPE_STEPS` states (which only commits to "inside the 72-hour window" with no sub-deadlines). If these specific hour thresholds are real product mechanics, they belong on the live `/features/hype` page first, in Product's language, not invented here. If they're not real, the narrative device (a 4-stage arc building tension toward the drop) is still a strong structure — reuse the shape, drop the specific hour numbers down to what `HYPE_STEPS` already commits to.

**Flag — a product/pricing decision, not a content one:** the ₹1,25,000 / ₹2,50,000 / ₹4,80,000 packaged pricing for 25/50/100 creators doesn't exist anywhere on live `/pricing`, which deliberately keeps Hype pricing as "a flat per-reel rate... no back-and-forth on price," no headline package tiers. If Sage Digital wants to sell pre-packaged Hype volume tiers, that's Swapnil/Tejas's call to make first — Content shouldn't import Stitch's invented numbers as if they were already decided.

---

## 3. Additional findings

Beyond what Swapnil already confirmed, four things worth flagging before this goes further:

1. **Three different, mutually inconsistent company registrations appear across the batch, not just one wrong name.** `redesigned-homepage.txt` prints "Influora Digital Private Limited / CIN: U74999MH2024PTC418921 / GSTIN: 27AAKCI1283M1Z2." The shared footer template used across 8 other designs (`about-us-leadership`, `blog-insights-hub`, `deal-room-feature-os`, `how-it-works-for-brands`, `how-it-works-for-creators`, `meera-for-creators`, `transparent-pricing-economics`, `72-hour-hype-blitzes`) prints "Influora Technologies Private Limited / GSTIN: 27AAACI1681G1ZM / CIN: U72900MH2023PTC402911." And `about-us-leadership.txt`'s own body copy lists **both** names under a plural "Registered Corporate Entities" heading with a *third* CIN/GSTIN pair (`U73100MH2024PTC434321` / `27AAHCI9032N1Z2`) for the first one. Only one company exists. This is a fabricated-government-registration-number problem, not a wording problem — worth a legal/compliance look before anyone edits copy around it, because the fix isn't picking the "right" one of these three, it's pulling the one real CIN/GSTIN from Legal and making sure every page template references it from one place instead of getting hand-typed per design.

2. **ASCI / testimonial-advertising exposure.** Every named-face testimonial in the batch (see §1) pairs a synthetic photo with an invented handle, follower count, and quote. Beyond the brand-voice problem, this is the specific pattern India's advertising self-regulator (ASCI) targets under its influencer/endorsement guidelines — a testimonial has to reflect a genuine user's actual opinion. Worth a look from whoever owns compliance review, not just a copy fix.

3. **"Sovereign" as a house adjective.** "Sovereign creators," "Sovereign Brand Operating System," "Sovereign Creator Charter," "Sovereign Creator Economy" — used 20+ times across the batch. It never appears in our live voice anywhere I read, and it's an odd register for a payments product (it reads like a Silicon Valley pitch-deck word aimed at investors, not the plain, hedged language our actual India D2C marketing-manager audience gets everywhere else on the site). Recommend adding it to the banned-word list alongside "escrow" if any of this copy moves forward.

4. **Hard-coded TDS sections and specific tax-credit claims read like tax advice.** "Section 194J" is stated as *the* applicable section in nearly every design, when withholding actually depends on the creator's registration (194J vs 194C) — live correctly never names a section for this exact reason. Several designs go further and state brands can "claim complete 18% Input Tax Credit on every single platform invoice" — a categorical claim about a third party's (the brand's) tax position that edges into giving tax advice. Recommend a standing rule: never hard-code a TDS section or make a categorical ITC claim in customer-facing copy, full stop, regardless of which page it's on.

One smaller item: the About-us design lists a "Direct Corporate Desk" phone number, `+91 80 6957 8296`. I have no way to confirm whether that's real. If it's used anywhere, it needs a human check first — a wrong published phone number is its own support problem, separate from everything above.

---

## 4. What I did not evaluate

Layout, component structure, animation, accessibility beyond the alt-text issue below, and whether any of this should ship at all — those are Swapnil's call and the design team's lane, not mine. One thing that does sit on the copy/imagery line, though: every `data-alt` and `alt` attribute in the Stitch HTML is the literal AI image-generation prompt ("no 3D animation, crisp focus," "authentic candid lifestyle photography of...") rather than a real accessibility description. Whichever images survive need real alt text written for them regardless of which copy fixes above get applied — that's a rewrite job on its own, not something that falls out of the copy fixes automatically.
