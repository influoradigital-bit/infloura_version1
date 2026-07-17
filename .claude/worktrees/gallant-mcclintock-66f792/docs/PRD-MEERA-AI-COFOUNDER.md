# 🤖 INFLUORA — PRODUCT REQUIREMENTS: MEERA, THE AI COFOUNDER

> Feature owner: Swapnil Maruti × Claude | Date: 2026-07-04
> Status: Approved concept — pre-build. Stress-tested with Arjun (Eng Lead) and Rohan (CFO).
> Milestone: **M2.5** — ships right after escrow money rails (M2) are live.
> Grounded in `BUSINESS-BLUEPRINT.md` and `BUSINESS-IDEA-THAT-STANDS.md`.

---

## 0. ONE-LINE

**Meera is Influora's AI cofounder — a named, conversational marketing partner who analyzes a brand's website, recommends the right campaign, matches creators, sets the budget, and takes the brand from "I want to promote X" to a funded, live campaign in a single chat.**

Positioning: *"Influora — Your AI Marketing Cofounder. Other platforms give you tools. We give you a partner."*

This makes Influora the **first AI-first influencer marketing platform in India** — a category-creating wedge, inspired by Alippo's "AI Cofounder that builds and runs your store," applied to creator campaigns instead of ecommerce stores.

---

## 1. WHY THIS FEATURE (Business Rationale)

### The problem
Brands hate writing campaign briefs. Filling out fields — objective, deliverables, budget, targeting, creator criteria — is friction that kills activation. Every competitor (Collabstr, Insense, Aspire, Qoruz) is a **forms-and-filters tool**. Insense openly tells brands to expect a 2–3 week ramp. Aspire has a real learning curve. Nobody has made campaign creation feel like *talking to a marketing partner*.

### The insight
Brands don't want a tool. They want a cofounder who already understands their business and just *handles it*. Alippo proved the appetite in ecommerce — "AI launches your store in 60 seconds." We apply the same conversational, AI-does-the-work model to influencer campaigns.

### Why it wins
| Competitors | Influora + Meera |
|---|---|
| Fill out a campaign brief | "Tell me what you're launching" |
| Discovery tool | Marketing cofounder |
| Dropdowns and filters | Conversation |
| 2–3 week ramp | Live campaign in <10 minutes |
| Same for everyone | Personalized per brand |

### The moat angle
Meera's recommendations get better with our **proprietary per-creator performance data** (Instagram OAuth-verified stats + past campaign results). Alippo has no supply-side data. We do. Every campaign makes Meera smarter at matching — a compounding advantage nobody can copy with a broadcast list.

### Where it sits in the funnel
Meera is the **brand activation wedge**. It turns signup → funded campaign from a multi-day, multi-form journey into one conversation. Faster activation, higher conversion, premium "wow" positioning.

---

## 2. HOW IT WORKS (End-to-End Flow)

### Phase 1 — Signup + Website Analysis (background, 30–60s)
1. Brand signs up, enters website URL (required field).
2. Backend fires an async job:
   - Scrape site (products, pricing, images, brand voice signals).
   - Classify niche (beauty, food, fashion, D2C, etc.).
   - Extract product catalog.
   - Detect brand voice / tone profile.
   - Detect competitors where possible.
   - Store in `brand_profiles`.
3. Analysis complete → Meera chat unlocks. Loading copy: *"Analyzing your business…"*

### Phase 2 — The Meera Conversation
Meera opens already knowing the brand's site, products, pricing, niche, and voice.

**Example (happy path):**
```
Meera:  Hey Priya! Just went through BeautyByPriya.com — the kumkumadi
        range looks great. ₹899–₹1,499 is a sweet spot for Instagram
        impulse buys. What are you launching today?

Brand:  The new vitamin C serum we just dropped

Meera:  Got it — Vitamin C Brightening Serum, ₹899. For a fresh launch
        I'd run a Hype Campaign: 15 micro-creators, 72-hour burst,
        reactions + unboxing + "I tried it" formats, ₹15,000 pool
        (₹1,000/creator). Sound good, or want to adjust?

Brand:  Can we do Mumbai creators mostly?

Meera:  Done — filtered to Mumbai. Found 38 skincare creators, pulling
        the top 15 by engagement. [right panel updates live]

Brand:  Looks good

Meera:  Here's the breakdown: pool ₹15,000 + platform fee ₹2,250 =
        ₹17,250. Adding to your wallet now. [Pay ₹17,250 →]

Brand:  [pays via Razorpay]

Meera:  You're live! Inviting 40 matched creators now — first 15 to
        accept get the slots. I'll ping you when they start accepting,
        usually 2–4 hours.
```

### Phase 3 — Meera Keeps Working (proactive updates)
- "8 creators accepted so far, 7 slots open."
- "All 15 filled! Here's who's creating for you."
- "3 submissions in, ready for review. @skincare_priya's reel is already at 2.3K views."
- "You approved @skincare_priya — ₹1,000 released instantly. 12 to go."

---

## 3. MEERA — PERSONA & PERSONALIZATION

### Design decision: one consistent character, adaptive register
Meera is **always Meera** — same name, same face, same core identity (sharp, warm, knows Indian creator marketing, honest, never pushy). But she **reads the room** and mirrors each brand's voice.

Human analogy: a great account manager is the same person with a CEO or a college founder — she just adjusts her register. Consistent soul, adaptive tone.

- **Named character** → memorable, marketable ("Talk to Meera"), a brand asset Influora owns.
- **Voice-mirroring** → each brand feels understood, not talked-at.

Name rationale: an Indian name for Indian founders (Nagpur, Mumbai, Delhi). "Meera" is warm, easy, works in Hindi and English.

### The personalization stack (all fed into Meera's prompt per message)
| Layer | Source | Example |
|---|---|---|
| Identity | signup + website | "Hey Priya" · brand-color avatar |
| Context | website scrape | "your kumkumadi serum" · "₹899 is great for impulse buys" |
| Tone | voice detection | luxury → refined; Gen-Z → punchy; sweets → festive warmth |
| Memory | past campaigns | "Last time 10 creators got you 45K reach" |
| Live signals | real-time data | "3 skincare creators just hit 50K in Mumbai" |

Layers 1–2 = launch version. Layers 3–5 = after we have data.

### How voice-mirroring works
Website scrape produces a tone profile:
```json
{ "formality": 0.3, "energy": 0.8, "emoji_ok": true, "cultural_context": "festive-north-india" }
```
Injected as a "register dial" in Meera's system prompt. Meera stays Meera; only how she speaks shifts.

### Statelessness (why it scales)
Meera doesn't "remember" internally. The backend feeds fresh brand context into each Claude call. That's what lets her scale to thousands of brands at ~₹17–31 each per month.

### Proactivity (with guardrails)
Meera reaches out on her own — but every proactive message must reference the brand's **specific data**, never generic "come back!"
| Trigger | Message | Cap |
|---|---|---|
| Festival/seasonal | "Priya, Diwali's 3 weeks out — beauty brands are booking now." | 1 / major festival |
| Creator opportunity | "3 skincare creators just hit 50K in Mumbai." | 1 / week |
| Results ready | "Your last campaign pulled 62K reach. Repeat it bigger?" | event-driven |
| Dormant re-engagement | "Your serum reels are still getting saves. Round 2?" | 1 / month |

Hard rule: proactive outreach only to brands with ≥1 past campaign. Never spend credits nudging a never-converted brand.

---

## 4. UI / UX SPEC — THE MEERA WORKSPACE

### Structure: fixed 50/50 split
- **Left panel:** Meera — persistent chat, brand-color themed avatar, quick-reply chips, message input.
- **Right panel:** the **Living Canvas** — morphs through stages as the conversation moves. This is the trust engine: the brand *watches the campaign build itself*.

The right panel is proof. When Priya says "make it Mumbai," she *sees* creators filter live — she's not filling a form and hoping.

### The Living Canvas — 5 stages
| Stage | Conversation moment | Canvas shows |
|---|---|---|
| 1 | Talking | Brand snapshot — their site, products, colors |
| 2 | Recommending | Campaign card assembling, numbers counting up |
| 3 | Matching | Creators fly in one-by-one, filters apply live |
| 4 | Funding | Escrow wallet fills, lock clicks shut |
| 5 | Live | Campaign dashboard, invites going out |

### Motion: rich & cinematic
- Count-up numbers on reach / budget / creators (Framer Motion `useTransform`, always `Math.round`).
- Creators stagger in during matching: `delay: 0.15 + index × 0.10`; entry `opacity:0, y:12, scale:0.9 → 1`.
- Standard spring: `stiffness: 60, damping: 18`. Entry ease: `[0.23, 1, 0.32, 1]`.
- **Hero moment — the escrow lock:** money flows in → a lock clicks shut → "₹17,250 secured. Released only on your approval." This animation *is* the "Guaranteed" tagline made visual.
- Visible "thinking" states — Meera shows her work ("scanning 300 creators → filtering Mumbai → ranking → done"), never a blank spinner.
- Whole workspace tinted with the brand's accent color pulled from their site.

### Responsive & accessibility (non-negotiable)
- Mobile (<768px): 50/50 collapses to full-screen chat + a "View campaign ↗" tab that slides the canvas up. Same motion, stacked.
- Every animation bypassed by `useReducedMotion()` / `prefers-reduced-motion`.
- One WebGL context per page; `PerformanceMonitor` + CSS fallback for low-end devices; DPR capped at `[1, 1.5]`.

### Stack (per repo TECH-STACK)
React 18 + Vite + TypeScript · Tailwind + shadcn/ui · Framer Motion (`src/components/motion/`) · optional 3D hero (`src/components/3d/`). Motion components to build: `FadeUp`, `StaggerContainer/Item`, `WordReveal`, count-up, escrow-lock sequence.

---

## 5. TECHNICAL ARCHITECTURE

### Backend services (Spring Boot, per repo package pattern)
| Service | Responsibility |
|---|---|
| `WebsiteAnalyzerService` | Scrape URL (Playwright sidecar), extract catalog + tone profile, classify niche |
| `AICofoundService` | Build personalized system prompt, call Claude, manage conversation, function-calling |
| `CreatorMatcherService` | Rank creators by aesthetic fit + engagement + past deliverable quality |
| `BudgetCalculatorService` | Suggest pool + per-reel rate from product price + goal |
| `CampaignAutoCreatorService` | Create campaign from conversation intent, trigger invites |
| `AICreditService` | Enforce the credit model (Section 7) |

### Function-calling (removes ~70% of complexity)
Instead of parsing intent from free text, Meera calls typed functions directly:
```
show_creators(niche, count)          → render matched creators in canvas
calculate_budget(product_price, goal)→ suggest pool + rate
create_campaign(product, type, budget, creators) → build it
request_payment(amount)              → show payment button
confirm_launch()                     → send creator invites
```
Backend executes what Claude returns. No intent parser, no brittle state machine.

### Model routing (see Section 6)
- Meera's conversation → **Claude Sonnet + prompt caching** (quality matters for the cofounder feel).
- Website analysis → **Gemini 2.0 Flash** (bulk, cheap, quality irrelevant).
- Creator-side deliverable pre-screen → **Gemini 2.0 Flash** (volume: 100–500 reels/campaign).

### Database additions
```sql
-- V15__ai_cofounder.sql
CREATE TABLE brand_profiles (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workspace_id BIGINT REFERENCES workspaces(id),
  website_url VARCHAR(500),
  scraped_at TIMESTAMP,
  product_catalog JSON,
  brand_aesthetic JSON,       -- colors, tone profile, target demo
  niche_tags JSON,
  competitor_urls JSON,
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE ai_conversations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  brand_id BIGINT REFERENCES workspaces(id),
  status ENUM('ACTIVE','CAMPAIGN_CREATED','DORMANT') DEFAULT 'ACTIVE',
  created_at TIMESTAMP DEFAULT NOW(),
  last_message_at TIMESTAMP
);

CREATE TABLE ai_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT REFERENCES ai_conversations(id),
  role ENUM('USER','ASSISTANT','SYSTEM'),
  content TEXT,
  metadata JSON,              -- intents, tool calls, actions taken
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE campaign_intents (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT REFERENCES ai_conversations(id),
  campaign_type ENUM('HYPE','DIRECT','REVIEW'),
  product_name VARCHAR(255),
  product_url VARCHAR(500),
  budget INT,
  creator_count INT,
  confirmed BOOLEAN DEFAULT FALSE,
  campaign_id BIGINT REFERENCES campaigns(id),
  created_at TIMESTAMP DEFAULT NOW()
);
```

### Note on Meta approval
We have Meta approval to connect Instagram accounts and fetch their data. This powers verified creator stats (`platform_stats`), Meera's matching quality, and reach telemetry — the proprietary dataset behind the moat.

---

## 6. COST MODEL

### The conversational reality
Meera is a conversation, so context (system prompt + brand memory + history) is re-sent every turn. **Prompt caching** is the key lever — identical system/memory across turns costs 0.1x on cached reads, cutting input cost ~65%.

### Token model (per brand)
| Activity | Input | Output |
|---|---|---|
| Website analysis (once) | ~40,000 | ~3,000 |
| Campaign-launch chat (~16 turns) | ~70,000 | ~6,000 |
| Ongoing campaign chatter | ~30,000 | ~3,000 |
| Proactive outreach (4/mo) | ~12,000 | ~1,200 |
| **Per brand / campaign-month** | **~112,000** | **~10,000** |

### Cost (₹, at ~₹85/USD)
| Strategy | Per campaign-month | First month (+onboarding) | Quality |
|---|---|---|---|
| All Sonnet, no cache | ₹42 | ₹56 | High |
| **Sonnet + prompt caching (chosen)** | **₹23** | **₹31** | High |
| Hybrid (Sonnet chat + Gemini scrape) | ₹17 | ₹17 | High |
| All Gemini Flash | ₹1 | ₹1.30 | Lower feel |

### As % of revenue
| Campaign | Revenue | AI cost | % |
|---|---|---|---|
| Small (₹15K pool) | ₹2,250 fee | ₹17 | 0.76% |
| Standard (₹1.5L pool) | ₹42,500 | ₹17 | 0.04% |
| Hype (100 reels) | ₹40,000 | ₹20 | 0.05% |

**Verdict (Rohan): 🟢 GREEN.** AI is not the cost problem — concierge labor and payout fees dwarf it. The real risk is runaway usage from non-converting brands, solved by the credit model below.

---

## 7. CREDIT MODEL (Cost Control)

### Principle
Meter Meera like Alippo meters "AI task capacity." Revenue gates the expensive AI. **1 credit = 1 Meera action** (one chat exchange; a website analysis = 10 credits).

### Allocation
| Brand state | Monthly credits | Buys | Worst-case cost |
|---|---|---|---|
| New / free brand | **100** | ~1 full campaign setup + exploring | ~₹22 |
| Ran out, not live | **0 — Meera pauses** | soft nudge to go live | ₹0 (capped) |
| **Went live (funded)** | **Unlimited** for campaign window + reset to 100 | full cofounder | covered by revenue |
| Campaign ended | Back to credits — **loyalty bump to 150/mo** | keeps them warm | ~₹33 |

### Rules
1. **Unlimited is tied to an ACTIVE campaign window**, not permanent — no launching one tiny campaign then chatting forever.
2. **Going live resets credits immediately** — the moment escrow funds, the meter refills.
3. **Monthly reset** for everyone on the 1st.
4. **Proactive outreach spends from the same pool** — capped 4/month, only to brands with ≥1 past campaign.

### Empty state (decided)
When a free brand runs out without going live, **Meera pauses** with: *"Fund your first campaign to unlock me fully — or I'm back on the 1st."* Cleanest cost control; the wall itself becomes a conversion trigger.

### Schema
```sql
-- V16__ai_credits.sql
CREATE TABLE brand_ai_credits (
  brand_id BIGINT PRIMARY KEY REFERENCES workspaces(id),
  credits_remaining INT DEFAULT 100,
  monthly_allotment INT DEFAULT 100,     -- bumps to 150 after first campaign
  cycle_start DATE,
  unlimited_until TIMESTAMP NULL,        -- = campaign_end + 3d when funded
  last_reset DATE
);
```

### Enforcement logic
```
Every Meera call:
  if (unlimited_until > now)        → allow, no decrement
  else if (credits_remaining > 0)   → allow, decrement
  else                              → pause, show soft paywall

On campaign funded (escrow event):
  unlimited_until   = campaign_end + 3 days
  credits_remaining = monthly_allotment
  monthly_allotment = 150            (loyalty, if first campaign)

Monthly cron (1st):
  credits_remaining = monthly_allotment   (non-live brands)
```

### Exposure — now bounded
| Scenario | Before | With credit model |
|---|---|---|
| 1,000 free brands, none convert | ₹50K–1L/mo (runaway) | **₹22K/mo hard ceiling** |
| Each converting brand | — | ₹22 cost vs ₹2,250+ revenue — negligible |

Forecast formula: `max AI cost = free_brands × ₹22`. Every rupee above that is attached to real revenue.

---

## 8. MILESTONE & BUILD SEQUENCE

Meera slots in as **M2.5** — after escrow (so "go live = fund escrow" works) and before the full Hype engine (Meera can launch Standard campaigns first, Hype once M4 lands).

| Milestone | What | Meera dependency |
|---|---|---|
| M1 | Creator auth + Instagram OAuth + Deal Room backend | verified creator pool for matching |
| M2 | Escrow + Razorpay + contracts | "go live" = fund escrow |
| **M2.5** | **Meera AI Cofounder** — website analyzer, chat + function-calling, Living Canvas UI, credit model | this doc |
| M3 | Deliverable pipeline + AI pre-screen | Meera reports submissions |
| M4 | Hype Campaign engine | Meera launches Hype campaigns |
| M5+ | Deeper memory, live-signal proactivity, richer matching | compounding data |

### M2.5 build order (suggested, ~4–5 weeks)
1. **Week 1:** `WebsiteAnalyzerService` (Playwright + Gemini Flash) + `brand_profiles`.
2. **Week 2:** `AICofoundService` — Claude Sonnet + prompt caching + function-calling; `ai_conversations`/`ai_messages`.
3. **Week 3:** Living Canvas UI — 50/50 split, 5 stages, cinematic motion; wire to real APIs.
4. **Week 4:** `AICreditService` + `brand_ai_credits` + escrow-event reset hook; empty-state paywall.
5. **Week 5:** Edge cases, mobile responsive, `prefers-reduced-motion`, QA (Kavya) → build verify (Meera/DevOps) → security (Kabir) → Priya sign-off.

---

## 9. EDGE CASES

| Scenario | Meera behavior |
|---|---|
| Website scrape fails | "I couldn't load your site — paste a product link directly?" |
| Brand has no products | "Looks like your site's new. What are you planning to sell?" |
| Brand wants custom campaign | "Tell me the goal — I'll design something custom." |
| Budget hesitation | "No pressure — start with ₹5,000 for 5 creators, see results first." |
| Payment fails | "Payment didn't go through. Try again or a different method?" |
| No creators accept | "Slots filling slower than usual — I'm expanding the invite pool." |
| Credits exhausted, not live | Pause + "Fund your first campaign to unlock me fully." |
| Brand changes mind mid-flow | "No problem, let's adjust." (function-calling re-runs the relevant step) |

---

## 10. SUCCESS METRICS

| Metric | Target (first 3 months post-launch) |
|---|---|
| Signup → funded campaign conversion | ≥ 25% (vs form-based baseline) |
| Time from signup to live campaign | < 10 minutes median |
| Meera conversations per funded campaign | ≤ 20 turns (efficiency) |
| AI cost per funded brand | ≤ ₹35 / month |
| Free-tier exposure | ≤ ₹22 × free-brand count (hard ceiling holds) |
| Proactive → re-engagement rate | ≥ 15% of dormant brands return |

---

## 11. RISKS & GUARDRAILS

| Risk | Guardrail |
|---|---|
| Runaway AI cost from non-converters | Credit model — hard ceiling, unlimited only after funding |
| Meera makes a bad recommendation | Every suggestion is editable; brand always confirms before pay; function-calling keeps actions explicit |
| Voice-mirroring goes off-tone | Consistent character core + bounded register dial; QA test set across brand archetypes |
| Proactive messages feel like spam | Frequency caps + must-reference-real-data rule + only post-conversion |
| Platform dependency (Instagram OAuth/data) | Escrow + compliance rails stay platform-agnostic; Meera is a layer on top, not the foundation |
| Scrape blocked / SPA sites | Fallback to "paste a product link"; degrade gracefully |

---

## 12. ONE-SLIDE SUMMARY

> **MEERA — Influora's AI Cofounder**
> Brand signs up → Meera analyzes their site → recommends a campaign → matches creators → sets budget → funds escrow → goes live. One conversation. <10 minutes.
> Named, consistent character that mirrors each brand's voice and reaches out proactively.
> Fixed 50/50 workspace: Meera talks left, the campaign builds itself live on the right, escrow-lock as the hero moment.
> Claude Sonnet + caching for chat, Gemini Flash for volume. ₹17–31 per brand-month. Credit model caps free-tier exposure at ~₹22/brand and resets on go-live.
> **The first AI-first influencer marketing platform in India.**
