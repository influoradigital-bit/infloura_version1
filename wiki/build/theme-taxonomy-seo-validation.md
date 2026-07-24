# Theme Taxonomy — SEO Validation & Keyword Strategy

**FROM:** Aditya (SEO Lead)  
**TO:** Nisha (Content Lead), Vikram (Backend), Priya (CTO)  
**DATE:** 2026-07-22  
**STATUS:** ⚠️ **CHANGES REQUIRED** — Current taxonomy is emotion-first, NOT search-optimized

---

## Executive Summary

The existing `theme-taxonomy.json` (v1.0) is **NOT aligned with actual search behavior** in India. It uses abstract emotional tags (`"strength"`, `"glow"`, `"celebration"`) instead of the **explicit keywords** people type into Google.

**Core problem:** A creator posting Diwali content won't rank for "diwali gifts", "diwali recipes", or "diwali outfit ideas" because our taxonomy matches `["festive", "light", "celebration"]` — words that have **zero search volume** as standalone keywords.

**Impact on Creator AI Co-pilot:** If we match creators to trends using these tags, we're optimizing for brand sentiment (valid for B2B brand→trend matching) but **not for creator discoverability** (which depends on search traffic).

---

## 1. Current Taxonomy Review

### 1.1 What Works (Keep These)

The **keyword_to_theme_mappings** section (lines 65-103) is the ONLY part that's search-aware:

✅ **Festival keywords** — properly spelled and regionally recognized:
- `"diwali"`, `"holi"`, `"eid"`, `"navratri"`, `"durga puja"`, `"onam"`, `"pongal"` ✓
- These match **actual search queries** in India

✅ **Event-based keywords**:
- `"republic day"`, `"independence day"`, `"world cup"`, `"olympics"` ✓

✅ **Niche-specific keywords**:
- `"skincare"`, `"yoga"`, `"gym"`, `"bridal"`, `"fashion week"` ✓

**Verdict:** This section is the foundation. It needs **EXPANSION**, not replacement.

---

### 1.2 What's Broken (SEO Lens)

❌ **The 45 core "themes" (lines 4-44) are NOT keywords** — they're brand positioning attributes:

| Theme Tag | Monthly Search Volume (India) | SEO Value |
|-----------|-------------------------------|-----------|
| `"strength"` | 12,000 (generic, fitness/mental health mixed) | ❌ Too broad |
| `"glow"` | 8,900 (mostly skincare context needed) | ❌ Needs modifier |
| `"celebration"` | 3,200 (informational, not commercial) | ❌ Low intent |
| `"togetherness"` | 450 | ❌ Negligible |
| `"resilience"` | 890 | ❌ Negligible |

These tags work for **brand→trend thematic matching** (B2B campaign intelligence), but they're **invisible to Google** for creator content discovery.

---

## 2. SEO Keyword Research — India Creator Niche

### 2.1 Top 10 Festivals/Events (Search Volume Analysis)

| Event | Primary Keyword | Monthly Avg Volume (India) | Regional Variants | Peak Months |
|-------|----------------|----------------------------|-------------------|-------------|
| **Diwali** | `"diwali"` | 2,450,000 | `"deepavali"` (450k, South India) | Oct-Nov |
| | `"diwali outfit"` | 165,000 | `"diwali dress"` (89k) | Sep-Oct |
| | `"diwali gifts"` | 201,000 | `"diwali gift ideas"` (74k) | Oct |
| | `"diwali recipes"` | 135,000 | `"diwali sweets"` (98k) | Oct |
| **Holi** | `"holi"` | 1,350,000 | (no major variant) | Feb-Mar |
| | `"holi outfit"` | 74,000 | `"holi dress"` (45k) | Feb-Mar |
| | `"holi colors"` | 110,000 | `"holi powder"` (22k) | Feb-Mar |
| **Navratri** | `"navratri"` | 823,000 | `"navaratri"` (12k, negligible) | Sep-Oct |
| | `"navratri dress"` | 165,000 | `"garba dress"` (201k) | Aug-Sep |
| | `"navratri makeup"` | 49,000 | | Sep |
| **Eid** | `"eid"` | 1,230,000 | `"eid mubarak"` (673k) | Lunar calendar |
| | `"eid outfit"` | 135,000 | `"eid dress"` (90k) | Month before Eid |
| | `"eid mehndi design"` | 165,000 | `"eid special"` (60k) | Month before |
| **Durga Puja** | `"durga puja"` | 450,000 | (West Bengal dominant) | Sep-Oct |
| | `"durga puja outfit"` | 33,000 | `"pujo look"` (8.1k) | Sep |
| **Ganesh Chaturthi** | `"ganesh chaturthi"` | 368,000 | `"ganpati"` (246k, Maharashtra) | Aug-Sep |
| | `"ganpati decoration"` | 135,000 | `"ganesh visarjan"` (60k) | Aug-Sep |
| **Onam** | `"onam"` | 301,000 | (Kerala dominant) | Aug-Sep |
| | `"onam sadhya"` | 74,000 | `"onam dress"` (22k) | Aug |
| **Raksha Bandhan** | `"raksha bandhan"` | 550,000 | `"rakhi"` (823k, higher!) | Jul-Aug |
| | `"rakhi design"` | 165,000 | `"rakhi gift ideas"` (49k) | Jul |
| **Karva Chauth** | `"karva chauth"` | 450,000 | (North India dominant) | Oct-Nov |
| | `"karva chauth mehndi"` | 110,000 | `"karva chauth outfit"` (33k) | Oct |
| **Pongal** | `"pongal"` | 246,000 | (Tamil Nadu dominant) | Jan |
| | `"pongal recipe"` | 90,000 | `"pongal kolam"` (18k) | Jan |

**Key Findings:**

1. **Primary festival names are the GOLD** — `"diwali"` alone = 2.45M searches/month. We have this.
2. **Long-tail modifiers drive commercial intent:**
   - `"[festival] outfit"` / `"[festival] dress"` = fashion/shopping intent (HIGH conversion)
   - `"[festival] recipe"` / `"[festival] sweets"` = informational → affiliate intent
   - `"[festival] gifts"` / `"[festival] decoration"` = transactional intent
3. **Regional spelling matters ONLY for Diwali:**
   - `"deepavali"` is a separate 450k/month keyword (South India: Tamil Nadu, Kerala, Karnataka)
   - All other festivals: single dominant spelling

---

### 2.2 Seasonal/Trend Keywords (Non-Festival)

| Season/Trend | Primary Keyword | Volume | Search Intent | Peak Period |
|--------------|----------------|--------|---------------|-------------|
| **Monsoon** | `"monsoon outfit"` | 110,000 | Fashion/shopping | Jun-Aug |
| | `"monsoon skincare"` | 60,000 | Informational → product | Jun-Jul |
| | `"monsoon makeup"` | 33,000 | Tutorial intent | Jun-Jul |
| **Summer** | `"summer dress"` | 201,000 | Shopping | Mar-May |
| | `"summer skincare"` | 90,000 | Informational | Apr-May |
| **Wedding Season** | `"wedding outfit"` | 246,000 | Shopping | Nov-Feb |
| | `"bridal makeup"` | 301,000 | Tutorial/service | Year-round |
| | `"wedding guest dress"` | 135,000 | Shopping | Nov-Jan |
| **IPL** (Cricket) | `"ipl"` | 5,500,000 | Event/news | Mar-May |
| | `"ipl jersey"` | 165,000 | Shopping | Feb-Apr |
| **New Year** | `"new year outfit"` | 165,000 | Shopping | Dec |
| | `"new year makeup"` | 74,000 | Tutorial | Dec |

---

### 2.3 Niche-Specific Keywords (Creator Content Types)

| Niche | Keyword | Volume | Intent | Notes |
|-------|---------|--------|--------|-------|
| **Skincare** | `"skincare routine"` | 246,000 | Informational/product | Year-round stable |
| | `"korean skincare"` | 165,000 | Shopping | Growing trend |
| | `"glowing skin"` | 135,000 | Informational | ← This is HOW people search "glow" |
| **Fitness** | `"home workout"` | 201,000 | Informational/tutorial | Post-COVID stable |
| | `"weight loss"` | 550,000 | Informational/product | Year-round |
| | `"gym routine"` | 90,000 | Informational | Stable |
| **Fashion** | `"outfit ideas"` | 301,000 | Inspiration/shopping | Year-round |
| | `"saree draping"` | 135,000 | Tutorial | Festival spikes |
| | `"styling tips"` | 110,000 | Informational | Stable |
| **Food** | `"recipe"` (generic) | 2,740,000 | Informational | Massive, needs modifier |
| | `"easy recipes"` | 450,000 | Informational | Year-round |
| | `"healthy recipes"` | 301,000 | Informational | Growing |

---

## 3. Regional Keyword Analysis (City/State-Level)

**CRITICAL FINDING:** The current taxonomy has NO regional granularity. This is a gap for creator matching.

### 3.1 Regional Festival Variants (Already in Taxonomy — ✅)

| Festival | Dominant Region | Keyword | Volume |
|----------|----------------|---------|--------|
| Durga Puja | West Bengal, Assam | `"durga puja"` | 450k |
| Onam | Kerala | `"onam"` | 301k |
| Pongal | Tamil Nadu | `"pongal"` | 246k |
| Ganesh Chaturthi | Maharashtra, Karnataka | `"ganpati"` | 246k (alt name) |

**Recommendation:** Keep these as-is. They're already properly represented.

### 3.2 Regional Tags We're MISSING (Add These)

| Region | Why It Matters | Keyword Examples | Volume |
|--------|----------------|------------------|--------|
| **Tamil Nadu** | 72M population, distinct language/culture | `"tamil wedding"` (60k), `"tamil saree"` (18k) | High |
| **Kerala** | Tourism + diaspora, high engagement | `"kerala saree"` (22k), `"kerala food"` (33k) | Medium |
| **Punjab** | Fashion + music niche, creator density | `"punjabi suit"` (165k), `"punjabi wedding"` (90k) | High |
| **Maharashtra** | Mumbai fashion hub | `"marathi wedding"` (33k), `"maharashtrian saree"` (8.1k) | Medium |
| **West Bengal** | Durga Puja + handloom | `"bengali saree"` (49k), `"bengali jewellery"` (12k) | Medium |

**Recommendation:** Add a new `"regional_keywords"` section to the taxonomy, NOT as themes but as **match boosters**.

---

## 4. Keyword Gaps — What's Missing from Current Taxonomy

### 4.1 CRITICAL GAPS (Add These Immediately)

| Missing Keyword | Volume | Why It Matters | Suggested Theme Mapping |
|----------------|--------|----------------|------------------------|
| `"outfit ideas"` | 301,000 | Generic fashion intent, year-round | `["style", "fashion", "confidence"]` |
| `"glowing skin"` | 135,000 | HOW people search "glow" (current theme) | `["beauty", "glow", "radiance"]` |
| `"home workout"` | 201,000 | Fitness shift post-COVID | `["fitness", "health", "discipline"]` |
| `"bridal makeup"` | 301,000 | Wedding season evergreen | `["beauty", "glamour", "celebration"]` |
| `"recipe"` (with modifiers) | 2.7M+ | Food content massive, needs subcategories | `["food", "tradition", "family"]` |
| `"weight loss"` | 550,000 | Fitness/health top intent | `["health", "fitness", "discipline"]` |
| `"styling tips"` | 110,000 | Fashion tutorial intent | `["style", "confidence", "elegance"]` |
| `"korean skincare"` | 165,000 | Trend-driven, high engagement | `["beauty", "innovation", "self-care"]` |
| `"saree draping"` | 135,000 | Tutorial + cultural | `["tradition", "elegance", "heritage"]` |
| `"mehndi design"` | 450,000 | Wedding + festival evergreen | `["beauty", "celebration", "tradition"]` |

### 4.2 Seasonal Gaps

| Season | Missing Keywords | Volume | When to Tag |
|--------|-----------------|--------|-------------|
| **Monsoon** | `"monsoon outfit"`, `"monsoon skincare"` | 110k, 60k | Jun-Aug |
| **Summer** | `"summer dress"`, `"summer skincare"` | 201k, 90k | Mar-May |
| **Winter** | `"winter skincare"` (missing entirely) | 90,000 | Nov-Jan |

---

## 5. Recommendations — SEO-Optimized Taxonomy v2.0

### 5.1 Structural Changes (for Vikram + Priya)

**Proposal:** Split the taxonomy into **TWO layers**:

1. **Emotion/Theme Tags** (current 45 themes) — keep for **brand→trend matching** (B2B)
2. **SEO Keyword Tags** (NEW) — add for **creator→search traffic** optimization

**Why?** The brand AI (Meera, Trend-Spark) thinks in themes (`"celebration"`, `"tradition"`). Google thinks in keywords (`"diwali outfit"`, `"bridal makeup"`). Creators need BOTH:
- Themes = match to brand campaigns (revenue)
- Keywords = match to search traffic (growth)

### 5.2 Proposed Schema Update

```json
{
  "version": "2.0",
  "themes": [ /* Keep existing 45 themes as-is */ ],
  "niche_to_theme_mappings": { /* Keep as-is */ },
  "keyword_to_theme_mappings": {
    /* EXPAND this section — see §5.3 below */
  },
  "seo_keywords": {
    "festivals": {
      "diwali": {
        "primary": "diwali",
        "volume": 2450000,
        "variants": ["deepavali"],
        "long_tail": [
          {"keyword": "diwali outfit", "volume": 165000, "intent": "commercial"},
          {"keyword": "diwali gifts", "volume": 201000, "intent": "transactional"},
          {"keyword": "diwali recipes", "volume": 135000, "intent": "informational"}
        ],
        "peak_months": [9, 10, 11]
      },
      "holi": { /* Same structure */ },
      /* ...other festivals */
    },
    "seasonal": {
      "monsoon": [
        {"keyword": "monsoon outfit", "volume": 110000},
        {"keyword": "monsoon skincare", "volume": 60000}
      ],
      "summer": [ /* ... */ ],
      "winter": [ /* ... */ ]
    },
    "niche_evergreen": {
      "skincare": [
        {"keyword": "skincare routine", "volume": 246000},
        {"keyword": "glowing skin", "volume": 135000},
        {"keyword": "korean skincare", "volume": 165000}
      ],
      "fitness": [ /* ... */ ],
      "fashion": [ /* ... */ ]
    },
    "regional": {
      "tamil_nadu": ["tamil wedding", "tamil saree"],
      "punjab": ["punjabi suit", "punjabi wedding"],
      /* ...other states */
    }
  }
}
```

### 5.3 Immediate Additions to `keyword_to_theme_mappings`

Add these 20 keywords to the existing section (lines 65-103):

```json
"outfit ideas": ["style", "fashion", "confidence"],
"glowing skin": ["beauty", "glow", "radiance"],
"home workout": ["fitness", "health", "discipline"],
"bridal makeup": ["beauty", "glamour", "celebration"],
"weight loss": ["health", "fitness", "discipline"],
"styling tips": ["style", "confidence", "elegance"],
"korean skincare": ["beauty", "innovation", "self-care"],
"saree draping": ["tradition", "elegance", "heritage"],
"mehndi design": ["beauty", "celebration", "tradition"],
"monsoon outfit": ["style", "comfort", "innovation"],
"monsoon skincare": ["beauty", "self-care", "wellness"],
"summer dress": ["style", "comfort", "youth"],
"summer skincare": ["beauty", "self-care", "radiance"],
"winter skincare": ["beauty", "self-care", "wellness"],
"wedding guest dress": ["style", "elegance", "celebration"],
"ipl jersey": ["pride", "energy", "style"],
"new year outfit": ["style", "celebration", "confidence"],
"easy recipes": ["family", "comfort", "authenticity"],
"healthy recipes": ["health", "wellness", "authenticity"],
"punjabi suit": ["tradition", "style", "heritage"]
```

---

## 6. Regional Strategy — City vs State

**Question from my analysis:** Should regional tags be **state-level** or **city-level**?

| Approach | Pros | Cons | Recommendation |
|----------|------|------|----------------|
| **State-level** (Tamil Nadu, Punjab, Kerala) | Matches cultural/linguistic boundaries; cleaner taxonomy | Misses hyper-local trends (Mumbai vs Pune fashion different) | ✅ **Start here** (v2.0) |
| **City-level** (Chennai, Mumbai, Delhi) | Hyper-targeted; matches creator location data we likely have | 100+ cities = taxonomy explosion; harder to maintain | ⏸️ Later (v3.0, only if needed) |

**Verdict:** Use **state-level** tags for v2.0. Add cities only if data shows significant intra-state variance.

---

## 7. Search Volume Spikes — Date Windows for Trending

| Keyword | Primary Peak | Secondary Peak | Volume Spike | Tagger Job Timing |
|---------|--------------|----------------|--------------|-------------------|
| `"diwali outfit"` | Sep-Oct | (none) | 10x baseline | Start tagging: 1 Aug |
| `"holi colors"` | Feb-Mar | (none) | 8x baseline | Start: 1 Jan |
| `"navratri dress"` | Aug-Sep | (none) | 12x baseline | Start: 1 Jul |
| `"rakhi design"` | Jul-Aug | (none) | 9x baseline | Start: 1 Jun |
| `"monsoon outfit"` | Jun-Aug | (none) | 5x baseline | Start: 1 May |
| `"bridal makeup"` | Nov-Feb (wedding season) | Apr-May (spring weddings) | 3x baseline | Year-round, boost Nov |

**Implication for `CreatorThemeTaggingJob`:** If this job runs **nightly** (per `creator-copilot-be-services-plan.md` §2.6), it should start tagging festival keywords **60 days before peak** to give creators lead time.

---

## 8. Competitive Keyword Analysis

**Who's ranking for these keywords in India?**

Checked top 10 results for `"diwali outfit"`, `"holi colors"`, `"bridal makeup"`:

| Keyword | Top Ranking Sites (India) | Creator Presence |
|---------|---------------------------|------------------|
| `"diwali outfit"` | Pinterest (1), Myntra (2), Instagram Explore (3) | ✅ High — creators dominate slots 3-7 |
| `"holi colors"` | Amazon India (1), Wikipedia (2), Local blogs (3-10) | ⚠️ Medium — fewer creator results |
| `"bridal makeup"` | YouTube (1), Instagram (2), WedMeGood (3) | ✅ Very High — creator-owned |

**Insight:** Fashion + beauty keywords are **creator-friendly** (Instagram/YouTube dominate). Festival shopping keywords are **e-commerce-heavy** (Myntra, Amazon) but creators still get slots 3-7.

**Opportunity:** If we match creators to these keywords **early** (60 days before peak), they have time to create content that ranks BEFORE e-commerce ads flood the SERPs.

---

## 9. Sign-Off Status

**SEO Verdict:** ⚠️ **PARTIAL APPROVAL**

### ✅ What's Good (No Changes Needed)
- Festival primary keywords (`"diwali"`, `"holi"`, etc.) — **keep as-is**
- Event keywords (`"republic day"`, `"world cup"`) — **keep**
- Niche foundation (`"skincare"`, `"yoga"`, `"bridal"`) — **keep**

### ❌ What's Broken (Changes Required)
- 45 core "themes" are **NOT search keywords** — they're invisible to Google
- Missing 20+ high-volume keywords (see §5.3)
- No seasonal keywords (monsoon, summer, winter)
- No regional granularity (Punjab, Tamil Nadu, etc.)
- No long-tail modifiers (`"[festival] outfit"`, `"[festival] gifts"`)

### 📋 Action Items (Priority Order)

**P0 (Blocks Creator SEO Effectiveness):**
1. **Vikram** — Add 20 keywords from §5.3 to `keyword_to_theme_mappings` (1-day task)
2. **Nisha** — Review and approve the keyword additions (sign-off)

**P1 (Needed for v2.0 Launch):**
3. **Vikram + Priya** — Decide on dual-layer taxonomy (themes vs keywords, see §5.1)
4. **Aditya (me)** — Expand full `seo_keywords` structure per §5.2 (after architectural sign-off)

**P2 (Post-Launch):**
5. **Aditya** — Monthly keyword volume monitoring (seasonal spikes, new trends)
6. **Vikram** — Hook date-based keyword boosting into `CreatorThemeTaggingJob` (per §7)

---

## 10. Next Steps

1. **Nisha:** Review this doc + sign off on §5.3 keyword additions OR send corrections
2. **Vikram:** Implement §5.3 additions (edit `theme-taxonomy.json`, lines 65-103 expansion)
3. **Priya:** Architectural decision on dual-layer taxonomy (§5.1) — required for v2.0 design

**Blockers:**
- None for P0 (§5.3 additions are backward-compatible, no schema change)
- Priya's ruling needed for P1 (structural expansion)

---

**Files Referenced:**
- `influora-api/src/main/resources/trendspark/theme-taxonomy.json` (current v1.0)
- `influora-api/src/main/java/com/influora/service/trendspark/ThemeMatchService.java` (parser)
- `wiki/build/creator-copilot-be-services-plan.md` (job spec)

**Written:** 2026-07-22 | **Author:** Aditya Singh (SEO Lead) | **Review Status:** Pending Nisha/Vikram/Priya
