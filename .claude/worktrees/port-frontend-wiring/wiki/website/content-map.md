# Influora.in Website Content Map
**Phase 1 — GEO/SEO Optimized Rebuild**
**Owner:** Nisha Patel (Content Lead)
**Last Updated:** 2026-07-13
**Status:** Draft for Swapnil approval

---

## 1. CORE PAGES

### 1.1 Homepage (`/`)
**File:** `src/pages/landing.tsx` (enhancement)
**Purpose:** Convert — drive brand and creator signups
**Target Persona:** Both (dual CTA approach)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer marketing platform India"

**Key Sections:**
- Hero: "Where brands and creators sign real deals" + dual CTA (brand vs creator)
- Stats bar: 8915+ creators, ₹4.26Cr+ paid, 24h avg payout
- Platform features (6 cards): Discover, Deal Room, Contracts, Escrow, Payouts, Hype
- Escrow flow scroll animation (trust builder)
- Hype Campaigns spotlight (100 creators, 72 hours)
- Final dual CTA: "Sign your next deal on Influora"

**Enhancement Needs:**
- Add trust signals: client logos, testimonials
- Add "How It Works" teaser linking to dedicated pages
- Expand footer: feature links, blog, compliance links

**Primary CTA:** "Launch a campaign" (brands) / "Join as a creator" (creators)

---

### 1.2 About Us (`/about`)
**File:** New — `src/pages/about.tsx`
**Purpose:** Trust — establish credibility and mission
**Target Persona:** Both, leaning brand (trust research phase)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer marketing escrow India"

**Key Sections:**
1. **Mission Statement**
   - 2-3 sentences: Why Influora exists (replacing WhatsApp chaos, escrow trust, India-first)
   
2. **The Problem We Solve**
   - Before Influora: spreadsheets, payment chasing, no contracts, ghosting
   - After Influora: one platform, escrow protection, clear terms
   
3. **Our Story**
   - Brief origin (when founded, what gap we saw)
   - Milestone stats (deals closed, money moved, creators on platform)
   
4. **How We're Different**
   - Escrow on every deal (not just marketplace fee)
   - India-native (TDS, UPI, local compliance)
   - Built for both DTC and B2B export brands
   
5. **Team** (optional, if leadership wants visibility)
   - Headshots + 2-line bios OR generic "backed by" if stealth
   
6. **Trust Signals**
   - Compliance badges (if applicable: payment gateway partners, data security)
   - Client logos (if permission secured)

**Primary CTA:** "Start your first campaign" (brands) / "Browse creator profiles" (creators)

---

### 1.3 How It Works — For Brands (`/how-it-works/brands`)
**File:** New — `src/pages/how-it-works-brands.tsx`
**Purpose:** Inform + Convert — walk brands through the platform flow
**Target Persona:** Brand marketers, DTC founders, export business owners
**SEO Target Keyword:** [Aditya to supply] — placeholder: "how to hire influencers in India"

**Key Sections:**
1. **Step 1: Create Campaign**
   - Set goals (reach, engagement, sales)
   - Choose campaign type (one-off collab, Hype Campaign, seasonal)
   - Define deliverables (reel, carousel, story, YouTube integration)
   
2. **Step 2: Discover Creators**
   - Search by niche, followers, engagement rate, past work
   - Verified Instagram profiles + rate cards
   - Review past collaboration history
   
3. **Step 3: Negotiate in Deal Room**
   - Chat + proposals in one thread
   - No WhatsApp juggling
   - Counter-offers, revision limits
   
4. **Step 4: Sign Contract + Fund Escrow**
   - Auto-generated contract (usage rights, exclusivity, revisions)
   - E-sign
   - Escrow lock (funds safe until approval)
   
5. **Step 5: Creator Delivers, You Approve**
   - Creator uploads deliverable
   - Request revisions (within contract limits) OR approve
   
6. **Step 6: Escrow Releases, Creator Posts**
   - On approval, escrow auto-releases
   - Creator posts within campaign window
   - You track performance
   
**Visual Element:** Flow diagram or timeline graphic (coordinate with Zara)

**Primary CTA:** "Launch your first campaign"

---

### 1.4 How It Works — For Creators (`/how-it-works/creators`)
**File:** New — `src/pages/how-it-works-creators.tsx`
**Purpose:** Inform + Convert — walk creators through earning flow
**Target Persona:** Instagram creators (10k-500k followers), YouTube creators exploring brand deals
**SEO Target Keyword:** [Aditya to supply] — placeholder: "get paid for Instagram collaborations India"

**Key Sections:**
1. **Step 1: Create Profile + Connect Instagram**
   - Link verified IG account
   - Set your rate card (per reel, per carousel, per story)
   - Showcase past work (optional portfolio)
   
2. **Step 2: Get Discovered OR Join Hype**
   - Brands search and find you
   - OR: Browse live Hype Campaigns (one-tap accept, 72-hour window)
   
3. **Step 3: Negotiate in Deal Room**
   - Brand sends proposal
   - Chat, negotiate rate, counter-offer
   - All in one thread (no lost DMs)
   
4. **Step 4: Accept Contract (Escrow Funded)**
   - E-sign the generated contract
   - Funds already locked in escrow (guaranteed payment)
   
5. **Step 5: Create + Submit Deliverable**
   - Upload your reel/carousel/video
   - Brand reviews (may request revisions per contract)
   
6. **Step 6: Get Paid, Then Post**
   - Brand approves → escrow releases to you
   - TDS handled automatically
   - Post within campaign window
   - See payment in 24h (average)
   
**Visual Element:** Creator journey timeline (coordinate with Zara)

**Primary CTA:** "Join as a creator"

---

### 1.5 Pricing (`/pricing`)
**File:** New — `src/pages/pricing.tsx`
**Purpose:** Convert — transparent pricing, remove friction
**Target Persona:** Brands (decision-making phase)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer marketing platform fees India"

**Key Sections:**
1. **Headline:** "Pay only when deals close"
   
2. **For Brands:**
   - Free to browse creators
   - Free to send proposals
   - **Platform fee:** X% per deal (check with Swapnil/Rohan for actual rate)
   - **Escrow fee:** Covered by platform fee OR separate line if applicable
   - **What's included:** Deal Room, contracts, escrow, TDS handling, dispute resolution
   
3. **For Creators:**
   - Free to join
   - Free to accept deals
   - **Commission:** X% deducted from payout (check with Swapnil/Rohan)
   - **What's included:** TDS invoice, UPI/bank payout, escrow protection, deal contracts
   
4. **Hype Campaign Pricing** (if different structure)
   - Flat per-reel rate set by brand
   - One-tap accept, auto-escrow
   
5. **No Hidden Fees**
   - Transparent breakdown
   - No monthly subscription (unless enterprise tier exists)
   
6. **FAQ:**
   - When do I pay? (brand: after approval; creator: 24h average)
   - What if deal falls through? (escrow returns to brand)
   - TDS handling? (auto-deducted, invoice provided)

**Primary CTA:** "Start free" (brands) / "Join free" (creators)

---

### 1.6 Contact/Support (`/support`)
**File:** New — `src/pages/support.tsx`
**Purpose:** Trust + Service — low-friction help access
**Target Persona:** Both (existing users + prospects with questions)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer marketing support India"

**Key Sections:**
1. **Get Help**
   - Contact form (Name, Email, User Type [Brand/Creator/Other], Message)
   - OR: Email link (support@influora.in if exists)
   
2. **FAQ** (top 10-15 questions)
   - How does escrow work?
   - What if creator doesn't deliver?
   - What if brand doesn't approve?
   - How long until payout?
   - TDS questions
   - KYC requirements
   - Dispute process
   
3. **Documentation Links** (if/when exists)
   - Brand guide
   - Creator guide
   - API docs (for enterprise)
   
4. **Social/Community**
   - Links to Instagram, LinkedIn (if active)

**Primary CTA:** "Submit question" (form) OR "Browse FAQ"

---

## 2. FEATURE PAGES

### 2.1 Escrow Protection (`/features/escrow`)
**File:** New — `src/pages/features/escrow.tsx`
**Purpose:** Trust — deep-dive on the core differentiator
**Target Persona:** Brands (concerned about payment risk) + Creators (concerned about non-payment)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer payment escrow India"

**Key Sections:**
1. **What Is Escrow?**
   - Plain-English explanation (funds held by neutral third party)
   
2. **How It Works on Influora**
   - Brand funds escrow when contract signed
   - Funds locked until brand approves deliverable
   - On approval: auto-release to creator
   - On dispute: frozen until resolution
   
3. **Why Escrow Matters**
   - **For brands:** No advance payment risk (creator must deliver first)
   - **For creators:** Guaranteed payment (funds already secured before work starts)
   
4. **Escrow Flow Diagram**
   - Reuse the scroll animation from landing page OR static graphic (Zara)
   
5. **Security**
   - Partner payment gateway (RazorpayX or equivalent)
   - Compliance (if applicable: RBI, payment partner licenses)
   
6. **What Happens in a Dispute?**
   - Escrow freezes
   - Influora mediation
   - Resolution: funds released OR returned per outcome

**Primary CTA:** "See how it works" (link to How It Works) OR "Start a campaign"

---

### 2.2 Deal Room (`/features/deal-room`)
**File:** New — `src/pages/features/deal-room.tsx`
**Purpose:** Inform — highlight negotiation efficiency
**Target Persona:** Both (brands tired of WhatsApp, creators tired of lost DMs)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer collaboration platform India"

**Key Sections:**
1. **The Old Way**
   - Instagram DM → WhatsApp → Email → Lost proposal → Re-negotiate
   
2. **The Deal Room Way**
   - One thread: chat, proposal, counter-offer, contract, deliverable
   - All history saved
   - No switching apps
   
3. **What's Included**
   - In-thread messaging
   - Proposal builder (deliverable type, rate, timeline, revisions)
   - Counter-offer flow
   - Contract generation + e-sign
   - Deliverable upload + approval
   
4. **Screenshots** (coordinate with Zara for mockups OR real UI screenshots)
   - Deal Room interface
   - Proposal card
   - Contract view

**Primary CTA:** "Try Deal Room" (signup)

---

### 2.3 Hype Campaigns (`/features/hype`)
**File:** New — `src/pages/features/hype.tsx`
**Purpose:** Convert — unique selling point for brands wanting scale
**Target Persona:** Brands (movie launches, product drops, festivals)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer blitz campaign India"

**Key Sections:**
1. **What Is a Hype Campaign?**
   - 72-hour window
   - 100 creators (or custom slot cap)
   - One source reel/audio
   - Flat per-reel rate
   - Creators accept with one tap
   
2. **Perfect For:**
   - Movie launches
   - Product drops
   - Festival campaigns (Diwali, Holi, etc.)
   - Trend-jacking (viral moments)
   
3. **How It Works:**
   - Brand uploads source reel + sets rate + slot cap
   - Live countdown (72 hours)
   - Creators browse, one-tap accept
   - Escrow auto-funds per accepted slot
   - Creators post before window closes
   - Auto-payout on post verification
   
4. **Live Hype Example** (demo card, like landing page)
   - Hashtag, rate, slots filled/total, hours left
   
5. **Case Study** (if available — placeholder for now)
   - "Brand X got 85 reels in 48 hours for ₹Y, reached Z million impressions"

**Primary CTA:** "Launch a Hype Campaign"

---

### 2.4 Contracts & Compliance (`/features/contracts`)
**File:** New — `src/pages/features/contracts.tsx`
**Purpose:** Trust — legal protection for both sides
**Target Persona:** Brands (risk-averse, compliance-focused) + Creators (want clear terms)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer contract template India"

**Key Sections:**
1. **Why Contracts Matter**
   - Usage rights clarity (organic-only vs paid ads)
   - Exclusivity terms (can't work with competitor for X days)
   - Revision limits (2 rounds, not infinite)
   
2. **Auto-Generated Contracts**
   - Influora generates contract from deal terms
   - Both parties e-sign
   - Legally binding (disclaimer: not legal advice, consult lawyer if needed)
   
3. **What's Covered:**
   - Deliverable specs (format, duration, posting window)
   - Payment terms (amount, TDS, payout timing)
   - Usage rights (organic, paid ads, duration)
   - Exclusivity (if applicable)
   - Revision policy
   
4. **E-Signature Flow**
   - Brand signs first (after funding escrow)
   - Creator signs to accept
   - Contract stored in Deal Room
   
5. **Compliance**
   - TDS auto-deducted (Section 194H/194J for Indian creators)
   - Invoice generated for creator
   - KYC (Aadhaar/PAN for creators, GST/company docs for brands)

**Primary CTA:** "See sample contract" (link to PDF OR inline preview) OR "Start a deal"

---

## 3. TRUST PAGES

### 3.1 Terms of Service (`/terms`)
**File:** New — `src/pages/legal/terms.tsx`
**Purpose:** Legal compliance
**Target Persona:** Both (legal research, dispute reference)
**SEO Target Keyword:** N/A (not optimizing for search)

**Key Sections:**
1. Acceptance of Terms
2. User Accounts (brand vs creator)
3. Platform Fees
4. Escrow Terms
5. Prohibited Conduct
6. Intellectual Property
7. Dispute Resolution
8. Limitation of Liability
9. Termination
10. Governing Law (India)

**Content Source:** Legal counsel OR template adapted (flag to Swapnil if legal review needed)

**Primary CTA:** None (reference page)

---

### 3.2 Privacy Policy (`/privacy`)
**File:** New — `src/pages/legal/privacy.tsx`
**Purpose:** Legal compliance + GDPR/data trust
**Target Persona:** Both (privacy-conscious users)
**SEO Target Keyword:** N/A

**Key Sections:**
1. What Data We Collect (email, Instagram handle, payment info, KYC docs)
2. How We Use It (platform operations, payments, compliance)
3. Who We Share With (payment partners, tax authorities)
4. Data Security (encryption, access controls)
5. User Rights (access, deletion, correction)
6. Cookies (if applicable)
7. Contact for Privacy Questions

**Content Source:** Legal counsel OR template adapted

**Primary CTA:** None (reference page)

---

### 3.3 Creator Guidelines (`/guidelines/creators`)
**File:** New — `src/pages/legal/creator-guidelines.tsx`
**Purpose:** Trust + Compliance — set quality standards
**Target Persona:** Creators (onboarding, reference)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer guidelines India"

**Key Sections:**
1. **Eligibility**
   - Verified Instagram account
   - Minimum followers (if applicable — check with Swapnil)
   - Real engagement (no fake followers)
   
2. **Profile Requirements**
   - Accurate rate card
   - Authentic portfolio
   - KYC compliance (PAN, Aadhaar)
   
3. **Deal Conduct**
   - Deliver on time
   - Follow brief
   - Respect revision limits
   - Disclose brand partnerships (FTC/ASCI compliance)
   
4. **Prohibited Content**
   - Hate speech
   - Illegal products
   - Misleading claims
   
5. **Payout Requirements**
   - Valid bank account
   - KYC verified
   - TDS applicability
   
6. **What Happens If You Violate**
   - Warning
   - Suspension
   - Permanent ban

**Primary CTA:** "Create creator account"

---

### 3.4 Brand Guidelines (`/guidelines/brands`)
**File:** New — `src/pages/legal/brand-guidelines.tsx`
**Purpose:** Trust + Compliance — set expectations
**Target Persona:** Brands (onboarding, reference)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "brand influencer marketing guidelines India"

**Key Sections:**
1. **Eligibility**
   - Registered business OR individual brand owner
   - KYC compliance (GST if applicable, company docs)
   
2. **Campaign Requirements**
   - Clear brief (deliverable specs, timeline, usage rights)
   - Fair pricing (no lowball offers that violate creator standards)
   - Respect contract terms (revision limits, posting window)
   
3. **Prohibited Products/Services**
   - Illegal goods
   - Misleading claims
   - Adult content (if policy excludes it)
   
4. **Payment Terms**
   - Escrow funded before work starts
   - Approval within X days (check with product for SLA)
   - Dispute process if rejection
   
5. **What Happens If You Violate**
   - Warning
   - Account suspension
   - Escrow forfeiture (if bad faith rejection)

**Primary CTA:** "Create brand account"

---

## 4. BLOG SECTION

### 4.1 Blog Index (`/blog`)
**File:** New — `src/pages/blog/index.tsx`
**Purpose:** SEO + Thought Leadership — drive organic traffic
**Target Persona:** Both + broader marketing audience
**SEO Target Keyword:** [Aditya to supply] — placeholder: "influencer marketing tips India"

**Key Sections:**
1. **Latest Posts** (reverse chronological, 10 per page)
2. **Categories** (filter)
   - For Brands
   - For Creators
   - Industry News
   - Platform Updates
3. **Search** (if resources allow)

**Blog Post Card Template:**
- Thumbnail image (Zara creates featured images per post)
- Title
- Excerpt (first 2 sentences OR custom meta description)
- Category badge
- Publish date
- Author (if applicable — or generic "Influora Team")

**Primary CTA:** N/A (click-through to posts)

---

### 4.2 Blog Categories

#### 4.2.1 For Brands (`/blog/category/brands`)
**Sample Topics:**
- "How to choose the right Instagram creator for your DTC brand"
- "5 mistakes brands make in influencer contracts"
- "What is escrow and why it protects your marketing budget"
- "Hype Campaigns 101: Launch a 100-creator blitz in 72 hours"
- "How Indian export businesses use influencers to reach global buyers"

**Content Cadence:** 2 posts/month (coordinate with Ishaan for writing)

---

#### 4.2.2 For Creators (`/blog/category/creators`)
**Sample Topics:**
- "How to set your Instagram rate card in 2026"
- "What to include in your influencer portfolio"
- "Understanding TDS: What Indian creators need to know"
- "How to negotiate brand deals without losing the opportunity"
- "Hype Campaigns: Earn fast with one-tap deals"

**Content Cadence:** 2 posts/month

---

#### 4.2.3 Industry News (`/blog/category/industry`)
**Sample Topics:**
- "Instagram algorithm changes: What creators need to know"
- "India's influencer marketing industry grows to ₹X crore in 2026"
- "New FTC disclosure rules for influencers"
- "How brands are using AI to find creators"

**Content Cadence:** 1 post/month (news-driven)

---

#### 4.2.4 Platform Updates (`/blog/category/updates`)
**Sample Topics:**
- "Introducing Hype Campaigns"
- "New feature: Deal Room chat improvements"
- "We've added YouTube creator support"

**Content Cadence:** As needed (when features ship)

---

### 4.3 Individual Post Template

**Structure for Ishaan to follow:**
- **Title:** H1, 60 chars max (SEO)
- **Meta Description:** 150 chars (Aditya reviews)
- **Featured Image:** 1200x630px (Zara creates)
- **Intro:** 2-3 sentences (hook + what reader will learn)
- **Body:** 400-1500 words
  - H2 subheadings every 200-300 words
  - Bullet points for scannability
  - Examples/case studies where possible
  - Screenshots/graphics (coordinate with Zara)
- **Conclusion:** 2-3 sentences + CTA
- **CTA:** Context-dependent
  - Brands post → "Launch a campaign"
  - Creators post → "Join as a creator"
  - Platform update → "Try the new feature"
- **Author Bio:** "Written by the Influora Team" (or specific name if we use author attribution)
- **Related Posts:** 3 links (manual curation OR auto by category)

**SEO Checklist (Aditya reviews before publish):**
- Target keyword in title
- Keyword in first 100 words
- Alt text on all images
- Internal links to feature pages/how-it-works
- External links to credible sources (where applicable)

---

## 5. LEGAL/COMPLIANCE PAGES

### 5.1 KYC Process Explanation (`/kyc`)
**File:** New — `src/pages/kyc.tsx`
**Purpose:** Inform + Reduce friction — explain why KYC is required
**Target Persona:** Both (onboarding phase)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "KYC for influencers India"

**Key Sections:**
1. **Why KYC Is Required**
   - Legal compliance (payment processing, tax reporting)
   - Trust (verified users only)
   
2. **What We Need**
   - **Creators:** PAN, Aadhaar, bank account details
   - **Brands:** GST (if applicable), company registration docs, PAN, authorized signatory ID
   
3. **How It Works**
   - Upload docs during onboarding (or at first campaign creation for brands)
   - Verification within 24-48 hours
   - Notification once approved
   
4. **Is My Data Safe?**
   - Encrypted storage
   - Access restricted
   - Not shared except for tax/payment compliance
   
5. **What If I Don't Complete KYC?**
   - Creators: Can browse, cannot accept deals
   - Brands: Can browse, cannot launch campaigns

**Primary CTA:** "Complete KYC now" (if logged in) OR "Sign up to start KYC"

---

### 5.2 TDS Handling (`/tds`)
**File:** New — `src/pages/tds.tsx`
**Purpose:** Inform — reduce creator confusion, build trust
**Target Persona:** Creators (especially first-time platform users)
**SEO Target Keyword:** [Aditya to supply] — placeholder: "TDS for influencers India"

**Key Sections:**
1. **What Is TDS?**
   - Tax Deducted at Source
   - Applicable to creator payments in India (Section 194H/194J)
   
2. **How Influora Handles It**
   - Auto-deducted from your payout
   - Rate: X% (check with finance/legal for accurate rate)
   - Invoice generated showing gross, TDS deducted, net paid
   
3. **Why You See "Gross" vs "Net"**
   - Gross = full deal amount
   - TDS = tax deducted
   - Net = what hits your bank account
   
4. **How to Claim TDS Credit**
   - TDS certificate provided (quarterly OR per-deal — check process)
   - Use when filing income tax return
   
5. **Do I Need to Pay More Tax?**
   - Depends on your total income (disclaimer: consult CA)

**Primary CTA:** "See sample invoice" OR "Learn more in FAQ"

---

### 5.3 Refund Policy (`/refund-policy`)
**File:** New — `src/pages/legal/refund-policy.tsx`
**Purpose:** Trust + Compliance
**Target Persona:** Brands (pre-purchase research)
**SEO Target Keyword:** N/A

**Key Sections:**
1. **Escrow Refunds**
   - If creator doesn't deliver → full refund (minus escrow processing fee if applicable)
   - If brand rejects within contract terms → refund after dispute resolution
   
2. **Platform Fee Refunds**
   - Non-refundable once deal accepted (unless Influora error)
   
3. **Dispute-Driven Refunds**
   - Mediation process
   - Resolution timeline (X days)
   - Outcome: refund OR payout to creator
   
4. **How to Request Refund**
   - Open dispute in Deal Room
   - Provide reason + evidence
   - Influora review

**Primary CTA:** None (reference page)

---

## 6. SITEMAP & NAVIGATION STRUCTURE

### 6.1 Primary Navigation (Header)
- **Logo** → `/`
- **How It Works** (dropdown)
  - For Brands → `/how-it-works/brands`
  - For Creators → `/how-it-works/creators`
- **Features** (dropdown)
  - Escrow Protection → `/features/escrow`
  - Deal Room → `/features/deal-room`
  - Hype Campaigns → `/features/hype`
  - Contracts → `/features/contracts`
- **Pricing** → `/pricing`
- **Blog** → `/blog`
- **I'm a Creator** (button, ghost) → `/creator/login`
- **For Brands** (button, primary) → `/brand/login`

### 6.2 Footer Navigation
**Column 1: Product**
- How It Works (Brands)
- How It Works (Creators)
- Pricing
- Features

**Column 2: Resources**
- Blog
- Support/FAQ
- KYC Process
- TDS Guide

**Column 3: Legal**
- Terms of Service
- Privacy Policy
- Creator Guidelines
- Brand Guidelines
- Refund Policy

**Column 4: Company**
- About Us
- Contact

**Column 5: Connect** (if social active)
- Instagram
- LinkedIn

**Bottom Bar:**
- Logo
- "© 2026 Influora"
- Payment partner badges (if applicable)

---

## 7. SEO METADATA TEMPLATE

For Aditya to fill per page:

| Page | Title Tag (60 chars) | Meta Description (150 chars) | Target Keyword | H1 |
|------|---------------------|------------------------------|----------------|-----|
| Homepage | [Aditya] | [Aditya] | [Aditya] | Where brands and creators sign real deals |
| About | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| How It Works - Brands | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| How It Works - Creators | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| Pricing | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| Escrow | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| Deal Room | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| Hype | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| Contracts | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| Support | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| Blog | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| KYC | [Aditya] | [Aditya] | [Aditya] | [Aditya] |
| TDS | [Aditya] | [Aditya] | [Aditya] | [Aditya] |

---

## 8. CONTENT PRODUCTION PIPELINE

### Phase 1 (Week 1-2): Core Pages
1. Homepage enhancement (Ananya FE + Ishaan copy edits)
2. About Us (Ishaan writes → Nisha approves)
3. How It Works - Brands (Ishaan writes → Nisha approves)
4. How It Works - Creators (Ishaan writes → Nisha approves)
5. Pricing (Rohan provides numbers → Ishaan writes → Nisha approves)

**Blocker:** Pricing model confirmation (Swapnil/Rohan)

### Phase 2 (Week 3-4): Feature Pages + Trust
1. Escrow (Ishaan)
2. Deal Room (Ishaan)
3. Hype (Ishaan)
4. Contracts (Ishaan)
5. Creator Guidelines (Ishaan)
6. Brand Guidelines (Ishaan)

**Blocker:** Legal review on guidelines (if needed)

### Phase 3 (Week 5): Legal + Compliance
1. Terms of Service (Legal template OR external counsel)
2. Privacy Policy (Legal template OR external counsel)
3. KYC Process (Ishaan, with input from Vikram on backend flow)
4. TDS Guide (Ishaan, with input from Rohan/finance)
5. Refund Policy (Ishaan, with input from legal/product)

**Blocker:** Legal counsel availability (Swapnil to confirm if needed)

### Phase 4 (Ongoing): Blog
- 2 posts/month for brands (Ishaan)
- 2 posts/month for creators (Ishaan)
- 1 post/month industry news (Ishaan)
- Platform updates as needed (Ishaan)

**Review:** All blog posts → Nisha approval → Aditya SEO review → publish

---

## 9. DESIGN ASSETS NEEDED (Zara)

| Asset | Page | Format | Dimensions | Notes |
|-------|------|--------|------------|-------|
| Trust badges | About | SVG/PNG | Varies | Client logos (if permission), payment partner badges |
| Flow diagram | How It Works - Brands | SVG | 1200x800 | 6-step visual |
| Flow diagram | How It Works - Creators | SVG | 1200x800 | 6-step visual |
| Escrow flow graphic | Escrow feature page | SVG | 1200x600 | Reuse landing animation OR static |
| Deal Room screenshot | Deal Room feature page | PNG | 1200x800 | Mockup OR real UI screenshot |
| Hype demo card | Hype feature page | Component | N/A | Reuse from landing |
| Blog featured images | Blog posts | PNG | 1200x630 | Per post (branded template) |
| KYC flow diagram | KYC page | SVG | 800x600 | Upload → verify → approved |
| Invoice sample | TDS page | PNG | 800x1000 | Mockup of TDS invoice |

---

## 10. TECHNICAL REQUIREMENTS (for Ananya/Vikram)

### Frontend (Ananya)
1. **New routes in React Router:**
   - `/about`
   - `/how-it-works/brands`
   - `/how-it-works/creators`
   - `/pricing`
   - `/support`
   - `/features/escrow`
   - `/features/deal-room`
   - `/features/hype`
   - `/features/contracts`
   - `/blog`
   - `/blog/category/:category`
   - `/blog/:slug`
   - `/kyc`
   - `/tds`
   - `/terms`
   - `/privacy`
   - `/guidelines/creators`
   - `/guidelines/brands`
   - `/refund-policy`

2. **Blog CMS Integration:**
   - Option A: Headless CMS (Contentful, Sanity, Strapi)
   - Option B: Static markdown files in repo (simple, no backend needed)
   - Option C: Backend API (`/api/v1/blog/posts`) — Vikram builds

   **Recommendation:** Option B (markdown) for MVP, migrate to CMS if blog scales

3. **SEO Requirements:**
   - React Helmet for meta tags
   - Sitemap.xml generation
   - Structured data (JSON-LD) — Aditya to provide schemas

### Backend (Vikram) — if blog via API
1. **Blog Endpoints:**
   - `GET /api/v1/blog/posts` (list, paginated)
   - `GET /api/v1/blog/posts/:slug` (single post)
   - `GET /api/v1/blog/categories` (list)
   - `GET /api/v1/blog/posts?category=:category` (filter)

2. **Blog Schema:**
   - `blog_posts` table: id, slug, title, excerpt, body (markdown), category, author, published_at, created_at, updated_at
   - `blog_categories` table: id, slug, name

3. **Admin Panel:**
   - Blog post CRUD (if not using external CMS)

---

## 11. CONTENT APPROVAL FLOW

1. **Ishaan writes draft** → saves to `wiki/content-drafts/[page-name].md`
2. **Nisha reviews** → approves OR requests revisions (in SHARED_CONTEXT.md)
3. **Aditya SEO review** (title, meta, keywords, internal links)
4. **Zara creates assets** (if page needs graphics)
5. **Ananya implements** (FE component, routes)
6. **Meera verifies** (build passes, no broken links)
7. **Nisha final approval** → mark DONE in tracker

---

## 12. OPEN QUESTIONS (for Swapnil)

1. **Pricing model:** What are the actual platform fees (brand % and creator %)?
2. **Legal review:** Do we need external counsel for Terms/Privacy, or can we adapt templates?
3. **Blog CMS:** Markdown files OR backend API OR external CMS?
4. **Client logos:** Do we have permission to show client logos on About page?
5. **Author attribution:** Blog posts by "Influora Team" OR individual names (Nisha, Tejas, etc.)?
6. **Minimum follower count:** Is there a creator eligibility threshold?
7. **KYC SLA:** What's the actual verification timeline (24h, 48h, 72h)?
8. **Sample contract:** Can we show a redacted real contract OR need a generic template?

---

## NEXT STEPS

1. **Swapnil approves this content map** (or requests changes)
2. **Aditya delivers SEO keyword map** (fills section 7 table)
3. **Rohan confirms pricing** (for Pricing page)
4. **Ishaan starts Phase 1 writing** (About, How It Works x2, Pricing)
5. **Zara starts design asset queue** (section 9)
6. **Ananya plans FE routes** (section 10)

---

**END OF CONTENT MAP**
