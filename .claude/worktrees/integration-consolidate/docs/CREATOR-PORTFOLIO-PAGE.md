# Influora Creator Portfolio Page
**Document Type:** Product Design Specification  
**Version:** 1.0  
**Date:** 2026-05-18  
**Status:** Draft for Review

---

## The Core Idea in One Line

> Every creator on Influora gets a **free, public, verified portfolio page** — a living media kit that brands, agencies, and followers can discover anywhere on the internet.

---

## Why This Is Better Than Linktree & Wishlink

| Feature | Linktree | Wishlink | **Influora Portfolio** |
|---|---|---|---|
| Public URL | ✅ | ✅ | ✅ |
| Custom links | ✅ | ✅ | ✅ |
| Product shop | ❌ | ✅ | Optional |
| Follower stats | Self-reported | ❌ | **OAuth-verified — cannot be faked** |
| Brand collaboration history | ❌ | ❌ | **Verified deal records** |
| Avg brand rating | ❌ | ❌ | **4.8 ★ — from real brands** |
| On-time delivery rate | ❌ | ❌ | **95% — verifiable** |
| "Invite to Campaign" CTA | ❌ | ❌ | **Direct brand acquisition funnel** |
| Media kit download | Paid plan | ❌ | **Free — auto-generated PDF** |
| Platform analytics | ❌ | ❌ | **Synced via Instagram/YouTube API** |

**The one thing no other tool can copy:**  
The verified campaign history and brand ratings. Influora owns that data because it ran the deals. That's the moat.

---

## The Public URL

```
https://influora.com/@priyacreates
```

- Every creator gets a URL at signup
- Username = their chosen handle (editable once every 90 days)
- Works without login — fully public, no paywall
- Indexable by Google (SEO-friendly, no `noindex`)
- Short enough to put in an Instagram bio

**Alternate custom domain (future roadmap):**
```
https://priyacreates.com  →  CNAME to influora.com/@priyacreates
```

---

## Page Layout — Top to Bottom

---

### SECTION 1 — Hero / Above the Fold

This is what a brand manager or follower sees in the first 3 seconds.

```
┌─────────────────────────────────────────────┐
│  [Cover photo — 1400×400px, customisable]   │
│                                              │
│  [Avatar]  Priya Creates              ✅ Verified
│            Fashion & Lifestyle · Mumbai      │
│  ─────────────────────────────────────────  │
│  "Fashion creator helping brands tell        │
│  stories through aesthetic content."         │
│                                              │
│  [Instagram 125K] [YouTube 50K]              │
│                                              │
│  [📩 Invite to Campaign]  [⬇ Media Kit PDF] │
└─────────────────────────────────────────────┘
```

**Elements:**
- **Cover photo** — customisable banner (like LinkedIn cover)
- **Avatar** — pulled from Influora profile, creator can upload separately
- **Verified badge** — only shows if PAN/Aadhaar KYC is completed on Influora
- **Display name + niche tags** — e.g. "Fashion & Lifestyle · Beauty · Travel"
- **City** — Mumbai, Delhi, etc.
- **Bio** — 160 chars, creator writes it
- **Platform pills** — Instagram 125K · YouTube 50K (live numbers from OAuth sync — **not self-reported**)
- **Invite to Campaign** button — takes brand to Influora signup/login, then auto-creates an invitation to this creator
- **Media Kit PDF** — auto-generated from their Influora data, downloadable by anyone

---

### SECTION 2 — Trust Signals Bar

A horizontal strip of 4 stats. This is the section Wishlink and Linktree fundamentally cannot replicate.

```
┌────────────┬────────────┬────────────┬────────────┐
│  45         │  4.8 ★     │  95%        │  12         │
│  Brand      │  Avg Brand  │  On-Time    │  Repeat     │
│  Collabs    │  Rating     │  Delivery   │  Brands     │
└────────────┴────────────┴────────────┴────────────┘
```

**Source of data:** All figures pulled live from Influora's deal history — not entered by the creator. The badge "Verified by Influora" appears below the strip.

**Creator visibility control:** Creator can hide this entire section if they prefer. Off by default for new creators with < 3 collabs.

---

### SECTION 3 — Badges / Achievements

A clean row of earned badges. Earned automatically by platform activity.

```
  ⭐ Top Creator     🕐 Fast Responder     ✅ On-Time Delivery     🏆 Brand Favorite
  Top 5% engagement   <2hr response time    95%+ delivery rate      12 repeat brands
```

**Badges available at launch:**
| Badge | Criteria |
|---|---|
| ⭐ Top Creator | Engagement rate in top 5% of platform |
| 🕐 Fast Responder | Average reply < 2 hours |
| ✅ On-Time Delivery | 95%+ on-time delivery rate |
| 🏆 Brand Favorite | 10+ repeat collaborations |
| 🔰 Rising Star | < 6 months on platform, 5+ completed deals |
| 💎 Premium Creator | 50+ deals, avg rating ≥ 4.5 |

---

### SECTION 4 — Platform Stats (Verified)

Detailed per-platform breakdown. The ✅ icon means the number was pulled directly from the platform API — not self-reported.

```
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│  📷 Instagram  ✅ Verified       │  │  ▶ YouTube  ✅ Verified          │
│  @priya_creates                  │  │  Priya Creates                   │
│                                  │  │                                  │
│  125K  Followers                 │  │  50K  Subscribers               │
│  4.2%  Engagement Rate           │  │  25K  Avg Views per Video        │
│  180K  Avg Reel Views            │  │  3.8%  Engagement Rate           │
│                                  │  │                                  │
│  [View Profile ↗]                │  │  [View Channel ↗]                │
└─────────────────────────────────┘  └─────────────────────────────────┘
```

**Sync frequency:** Auto-refreshed every 24 hours. Creator can manually sync once per hour.  
**Future platforms:** Support for YouTube Shorts, LinkedIn, X/Twitter, Snapchat, Pinterest.

---

### SECTION 5 — Past Brand Collaborations

This is the portfolio section. Brands creator has worked with, shown as logo tiles or cards.

**Display mode A — Logo Wall (default)**
```
  Worked with:
  [BrandCo] [Nykaa] [Myntra] [Mamaearth] [Boat] [Bewakoof]
```

**Display mode B — Collaboration Cards (expanded)**
```
┌────────────────────────────────────┐
│ [BrandCo India logo]               │
│ Summer Fashion Campaign · Jun 2026 │
│ 2 Reels + 4 Stories · Instagram    │
│ ★★★★★  "Exceptional work!"        │
└────────────────────────────────────┘
```

**Privacy controls (creator chooses per collaboration):**
- Show brand name + logo ✅
- Show brand name only (no logo)
- Show "Fashion Brand" (anonymised category)
- Hide entirely

**Brand opt-out:** Brand can also request their name not appear. Privacy respected both ways.

---

### SECTION 6 — Content Portfolio / Highlights

Creator pins their best content pieces here — embedded directly from Instagram or YouTube.

```
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│ Reel 1 │ │ Reel 2 │ │ Video  │ │  Post  │
│  ▶     │ │  ▶     │ │  ▶     │ │  🖼    │
│ 180K   │ │ 220K   │ │ 85K    │ │ 12K    │
│ views  │ │ views  │ │ views  │ │ likes  │
└────────┘ └────────┘ └────────┘ └────────┘
```

- Creator manually pins 4–12 posts from their connected platforms
- Posts embed directly (Instagram oEmbed, YouTube iframe) — no re-upload needed
- Shows view/like count under each post
- Creator can add a caption: "Collaboration with @BrandCo — Summer 2026"

---

### SECTION 7 — Custom Links (Link-in-Bio Core)

The classic Linktree feature — but included as one section, not the whole product.

```
  🛒  [My Amazon Wishlist]         → amazon.in/...
  🎓  [My Photography Course]      → teachable.com/...
  📧  [Book Me for Events]         → calendly.com/...
  💼  [My LinkedIn]                → linkedin.com/...
  🌐  [My Website / Blog]          → priyacreates.com
```

- Up to 10 custom links (no paywall)
- Creator adds icon + label + URL
- Drag to reorder
- Each link has a click counter visible only to creator in dashboard

---

### SECTION 8 — Rate Card (Optional — Creator Controls)

Creator can choose to show or hide their rate range publicly.

```
  💰 Collaboration Rates
  ─────────────────────────────────
  Instagram Reel         ₹25,000 – ₹45,000
  Instagram Post         ₹15,000 – ₹30,000
  YouTube Integration    ₹40,000 – ₹75,000
  Story Series (4)       ₹12,000 – ₹20,000
  ─────────────────────────────────
  Currency: INR  ·  Rates exclude GST
```

**Visibility options:**
- Public (anyone can see)
- Brands only (requires Influora brand login to view)
- Hidden (not shown at all)

---

### SECTION 9 — Languages & Audience Regions

Simple tags showing what languages the creator produces content in and which regions their audience is from.

```
  Languages:  [Hindi] [English] [Marathi]
  
  Top Audience:  Mumbai · Delhi · Pune · Bangalore
```

Audience location data pulled from connected platform insights (Instagram/YouTube).

---

### SECTION 10 — "Work With Me" Contact CTA

Bottom of page. Two clear CTAs.

```
┌─────────────────────────────────────────────────────┐
│          Ready to collaborate with Priya?            │
│                                                      │
│  [📩 Invite to Campaign on Influora]                 │
│     → For brands already on Influora                 │
│                                                      │
│  [✉ Send a Message]                                  │
│     → Opens a contact form (email forwarded to       │
│       creator's registered email, no spam)           │
└─────────────────────────────────────────────────────┘
```

**Invite to Campaign flow:**
1. Brand clicks button
2. If logged in → goes to "Select Campaign" → creator invited
3. If not logged in → "Sign up as a brand to invite Priya" → registration flow → creator invited

This button is **Influora's primary brand acquisition funnel from organic traffic.**

---

## What the Creator Sees — Dashboard Controls

In their Influora dashboard under **Profile → Public Page**, creator gets:

```
  Your public page:  influora.com/@priyacreates   [Copy Link] [View Page ↗]
  
  ─── Visibility Controls ──────────────────────────────
  
  [✅] Trust Signals Bar (collabs, rating, OTD)
  [✅] Badges
  [✅] Platform Stats
  [✅] Past Brand Collabs     →  [Manage which brands show]
  [✅] Content Portfolio       →  [Manage pinned posts]
  [✅] Custom Links            →  [Add / Reorder]
  [❌] Rate Card               →  [Set rates] [Visibility: Hidden ▼]
  [✅] Contact Form
  
  ─── Page Style ───────────────────────────────────────
  
  Cover Photo:    [Upload ↑]
  Accent Color:   [●] Purple  [●] Rose  [●] Teal  [●] Gold  [Custom #]
  Layout:         [Minimal] [Bold] [Magazine]
  
  ─── Analytics (last 30 days) ─────────────────────────
  
  Page Views:      1,247  (+18% vs last month)
  Profile Clicks:  342
  Link Clicks:     89    (top: Amazon Wishlist → 34)
  Brand Inquiries: 6     (via Contact Form)
  Media Kit DLs:   23
```

---

## Auto-Generated Media Kit PDF

Any visitor can click **"Download Media Kit"** — a PDF is auto-generated containing:

**Page 1 — Profile Overview**
- Name, photo, bio, city
- Platform stats (verified, with ✅ Verified by Influora badge)
- Badges earned

**Page 2 — Audience Insights**
- Age/gender breakdown
- Top locations
- Peak engagement times

**Page 3 — Past Work**
- Brand logo wall
- Collaboration count and ratings

**Page 4 — Rate Card**
- Only included if creator set rates to "Public"

**Page 5 — Contact**
- Influora profile link, email (optional)

> PDF is generated server-side, branded with Influora watermark on footer: *"Verified by Influora · influora.com/@priyacreates"*

---

## SEO & Discoverability

The public page is fully SEO-optimised so creators show up when brands search their name.

**Page title:**
```
Priya Creates — Fashion & Lifestyle Creator | Mumbai | Influora
```

**Meta description:**
```
Priya Creates is a verified fashion & lifestyle creator based in Mumbai with 125K Instagram followers and 45 brand collaborations. Hire for Instagram Reels, YouTube integrations, and more.
```

**Structured data (JSON-LD):**
```json
{
  "@type": "Person",
  "name": "Priya Creates",
  "jobTitle": "Content Creator",
  "address": { "addressLocality": "Mumbai" },
  "sameAs": ["https://instagram.com/priya_creates", "https://youtube.com/@priyacreates"]
}
```

**Result:** Brands searching "Priya Creates Instagram creator Mumbai" find the Influora page in top 3 results. That's free organic brand acquisition.

---

## What Makes It Shareable

The creator puts `influora.com/@priyacreates` in:

| Placement | Value |
|---|---|
| Instagram bio | Every profile visitor sees it |
| YouTube "About" tab | Every channel visitor sees it |
| LinkedIn headline | Professional brand managers see it |
| Email signature | Every pitch email includes it |
| WhatsApp status | Casual sharing |
| Business card | Offline events |

**One link. Everything a brand needs to decide to hire them.**

---

## Feature Roadmap (Phase 2 Ideas)

| Feature | Value |
|---|---|
| **Testimonial quotes** | Brand can leave a short public quote (separate from private rating) |
| **Campaign case studies** | Creator writes a before/after for a specific collab (with brand approval) |
| **Availability calendar** | "I'm booked till June 10 — next slot: June 11" |
| **Custom domain** | `priyacreates.com` → CNAME to Influora page |
| **Creator packages** | Pre-defined bundles: "Reel Pack — 2 Reels + 4 Stories — ₹55,000" |
| **Collab request widget** | Embed the "Invite me" button on their own website |
| **Dark/Light mode toggle** | Visitor preference |
| **Portfolio password protection** | For creators who want private review before publishing |

---

## What This Is NOT

To keep the product focused:

- ❌ **Not a storefront.** No checkout, no Razorpay integration, no product catalog. (That's a separate feature decision — see Commerce discussion.)
- ❌ **Not a full website builder.** No drag-and-drop sections, no blog, no subdomain hosting. It's a page, not a site.
- ❌ **Not a social network.** No following, no feed, no DMs between creators.
- ❌ **Not a booking tool.** No calendar scheduling, no invoice generation (Phase 2 consideration).

The page does one thing extremely well: **gives brands everything they need to decide to hire a creator, with verified data they can trust.**

---

## Summary

```
Public URL:     influora.com/@username  →  shareable everywhere
Differentiator: Verified stats + campaign history (no other tool has this)
Creator gets:   Professional media kit, brand inbound, link-in-bio
Influora gets:  Organic brand acquisition, SEO traffic, creator stickiness
Cost to build:  One public route + visibility controls + PDF generator
```

---

*Product Specification v1.0 — Influora Creator Portfolio Page*  
*Designed for: Influora B2B Influencer Marketing Platform*  
*Related docs: BACKEND-API-SPEC.md (Section 25), UI-UX-IMPROVEMENT-PLAN.md*
