# Creator AI Co-pilot: Theme Taxonomy Expansion Strategy

> **Author:** Nisha Patel (Content Lead)  
> **Date:** 2026-07-22  
> **Status:** DESIGN PHASE → Pending Aditya (SEO keyword validation) → Pending Vikram (implementation)  
> **Source:** [wiki/ai-review/creator-copilot-india-events-calendar-2026.md](../ai-review/creator-copilot-india-events-calendar-2026.md)

---

## EXECUTIVE SUMMARY

The deep-research workflow delivered a comprehensive India events calendar with **40+ new taxonomy tags** needed to power the Creator AI Co-pilot's trend-matching engine. This document defines:

1. **Naming conventions** — How we structure tag identifiers
2. **Hierarchy & groupings** — How tags relate to each other
3. **Multi-tag activation strategy** — How cross-cutting moments fire
4. **Vernacular mapping** — How regional creators get state-specific triggers
5. **Maintenance plan** — How we keep this fresh beyond 2026

**The strategic thesis:** India's content calendar is **year-round**, not just Diwali. 60–65% of festive shoppers are Tier-2+ cities, and **vernacular/regional is the dominant 2026 trend**. We need every state's moment to trigger the right creator, in the right month, with the right content type.

---

## 1. TAG NAMING CONVENTIONS

### Format: `kebab-case`, language-neutral, searchable

**Rule:** All tags use lowercase `kebab-case` (e.g., `makar-sankranti`, `tamil-nadu`, `monsoon`).

**Why kebab-case?**
- Matches existing theme-taxonomy.json structure (`content-ideas.json` uses this pattern)
- URL-safe (future: `/trends/makar-sankranti` routes)
- Search-friendly (grep, DB queries, Aditya's SEO keyword matching)
- Human-readable (no camelCase/snake_case cognitive load)

### Language neutrality: English romanization for festivals

**Examples:**
- `diwali` not `दिवाली`
- `onam` not `ഓണം`
- `durga-puja` not `দুর্গা পূজা`

**Rationale:** The co-pilot's LLM interface is English-first, and SEO keyword research (Aditya's domain) uses English search volumes. Regional vernacular is captured in the **content output**, not the tag identifiers.

### Multi-word festivals: hyphenated, no abbreviations

- ✅ `eid-ul-fitr` not `eid` or `eid_ul_fitr`
- ✅ `ganesh-chaturthi` not `ganpati` (regional variant) or `gc`
- ✅ `raksha-bandhan` not `rakhi` (colloquial)

**Exception:** Common short-forms that dominate search:
- `ipl` not `indian-premier-league` (IPL is the brand)
- `ott-release` not `over-the-top-release`

### Regional tags: state-level, not city-level (with 3 exceptions)

**Format:** `{state-name}` in kebab-case

- ✅ `tamil-nadu`, `kerala`, `maharashtra`, `west-bengal`
- ✅ `telangana-ap` (combined for Ugadi/Telugu New Year shared moment)
- ✅ `bihar-up` (combined for Chhath shared across state lines)
- ✅ `northeast` (Assam, Meghalaya, Nagaland, Tripura, Arunachal, Manipur, Mizoram, Sikkim grouped — **no creator differentiates beyond "Northeast"** in practice)

**3 city-level exceptions (only if creator demand emerges):**
- `delhi-ncr` (Republic Day parade, Budget Day local impact)
- `mumbai` (Ganesh Chaturthi is hyper-local to Mumbai pandals)
- `kolkata` (Durga Puja is synonymous with Kolkata pandal culture)

**Why state-level?** Creator niches align to state identity (Tamil food creators, Bengali fashion, Punjabi comedy), not cities. Over-granularity (Jaipur, Lucknow, Bengaluru) fragments tags without improving match quality.

### Season tags: event-aligned, not calendar months

- ✅ `monsoon` (Jun–Sep SW monsoon)
- ✅ `ne-monsoon` (Oct–Nov Northeast monsoon — distinct for Tamil Nadu/coastal South creators)
- ✅ `summer` (Mar–May)
- ✅ `winter` (Dec–Feb)
- ✅ `heatwave` (May peak — activates hydration/travel/cooling content)
- ✅ `harvest` (Jan: Pongal/Makar Sankranti/Lohri cluster; Apr: Baisakhi/Bihu)

**Rationale:** `monsoon` fires **food (chai/pakora), travel (Western Ghats), fashion (rainwear)**. A generic `june` tag doesn't capture that.

### Event tags: brand-name when applicable, not generic

- ✅ `ipl` not `cricket-season`
- ✅ `great-indian-festival` (Amazon's brand) not `festive-sale`
- ✅ `big-billion-days` (Flipkart's brand) not `october-sale`
- ✅ `prime-day` (Amazon) not `july-sale`

**Why?** Creators search Google Trends for "Great Indian Festival", not "festive sale". Aditya's SEO keyword research confirms this.

### Commercial event naming: platform-neutral where possible

- ✅ `gold-buying` (Dhanteras/Akshaya Tritiya — activates jewelry/finance creators regardless of platform)
- ✅ `wedding-season` not `shaadi-com-peak` (activates bridal beauty, ethnic fashion, decor — not one platform)

---

## 2. HIERARCHY & GROUPINGS

### Top-level categories (6 groups)

```
1. festivals/          — Religious, cultural, harvest festivals (28 tags)
2. regions/            — State/region identity tags (14 tags)
3. seasons/            — Weather/climate moments (6 tags)
4. events/             — Non-festival recurring events (9 tags)
5. commercial/         — E-commerce sales, shopping moments (7 tags)
6. content-types/      — Existing (beauty, fashion, food, tech, etc.) — NO NEW TAGS
```

### Why NOT a single flat list?

The co-pilot needs to **combine** tags across categories to fire the right content. Example:

**Trigger:** Onam (Aug 16–26)  
**Activated tags:**
- `festivals/onam`
- `regions/kerala`
- `seasons/monsoon`
- `content-types/food` (Onasadya feast)
- `content-types/fashion` (kasavu saree)
- `content-types/home` (Pookalam flower arrangements)

A flat list can't express "Onam is a Kerala festival during monsoon that activates food+fashion+home". Grouping lets us:
1. Fire **region-specific festivals** (Kerala creator gets `onam`, not `diwali` in August)
2. Layer **seasonal context** (monsoon = chai/pakora content even outside festival windows)
3. Route to **multiple content types** simultaneously (see Section 3)

### Hierarchy structure (proposed)

```json
{
  "festivals": {
    "pan-india": ["diwali", "holi", "navratri", "janmashtami", "eid-ul-fitr", "eid-ul-adha", "raksha-bandhan", "dussehra", "maha-shivratri", "dhanteras", "karwa-chauth"],
    "regional": {
      "south": ["onam", "pongal", "ugadi", "vishu", "puthandu"],
      "west": ["ganesh-chaturthi", "gudi-padwa", "garba"],
      "north": ["lohri", "baisakhi", "chhath"],
      "east": ["durga-puja", "bihu"],
      "multi-region": ["makar-sankranti"]
    },
    "harvest": ["pongal", "makar-sankranti", "lohri", "baisakhi", "bihu"],
    "commercial-festivals": ["dhanteras", "akshaya-tritiya"]
  },
  "regions": {
    "states": ["tamil-nadu", "kerala", "karnataka", "telangana-ap", "maharashtra", "gujarat", "rajasthan", "punjab", "west-bengal", "bihar-up", "goa", "kashmir"],
    "zones": ["northeast", "pan-india"]
  },
  "seasons": {
    "weather": ["winter", "summer", "heatwave", "monsoon", "ne-monsoon"],
    "agri-cycles": ["harvest"]
  },
  "events": {
    "sports": ["ipl", "cricket"],
    "civic": ["budget-day", "republic-day", "independence-day"],
    "education": ["exam-season", "results-season"],
    "lifestyle": ["wedding-season"],
    "entertainment": ["movie-release", "ott-release"]
  },
  "commercial": {
    "e-commerce": ["great-indian-festival", "big-billion-days", "prime-day", "end-of-season-sale", "black-friday", "year-end-sale"],
    "traditional": ["gold-buying"]
  }
}
```

**Note:** This is **conceptual hierarchy for documentation**. The actual `theme-taxonomy.json` implementation (Vikram's task) may flatten this into a tag array with metadata fields (`{ "id": "onam", "category": "festivals", "region": "kerala", "activates": ["food", "fashion", "home"] }`).

---

## 3. MULTI-TAG ACTIVATION STRATEGY

### The problem: Cross-cutting moments fire multiple content types

**Example:** Diwali activates:
- `home` (decor, DIY diyas, rangoli)
- `beauty` (festive glow, GRWM)
- `fashion` (ethnic wear, saree draping)
- `finance` (gold buying, e-comm deals)
- `food` (mithai, recipes)
- `devotional` (puja vidhi, aarti)
- `gifting` (gift guides)

**Question:** Does a **home decor creator** get ONE tag (`home`) or SEVEN tags (`home`, `beauty`, `fashion`, `finance`, `food`, `devotional`, `gifting`)?

### Proposed solution: Tag hierarchy + priority scoring

**Each creator has:**
1. **Primary niche** (e.g., `home` for a home decor creator)
2. **Secondary niches** (e.g., `fashion`, `beauty` for crossover content)

**Each festival/event has:**
1. **Activated content types** (e.g., Diwali → `["home", "beauty", "fashion", "finance", "food", "devotional", "gifting"]`)
2. **Priority scores** per content type (e.g., `home: 10, beauty: 8, fashion: 8, food: 6, finance: 5, devotional: 4, gifting: 3`)

**The co-pilot match logic (Vikram implements):**

```
IF creator.primary_niche IN event.activated_types:
  MATCH = TRUE
  content_angle = event.priority_scores[creator.primary_niche]

ELSE IF creator.secondary_niches INTERSECT event.activated_types:
  MATCH = TRUE (lower priority)
  content_angle = MAX(event.priority_scores[creator.secondary_niches])

ELSE:
  MATCH = FALSE
```

**Example walkthrough:**

| Creator | Primary | Secondary | Diwali Match? | Suggested Angle |
|---------|---------|-----------|---------------|-----------------|
| Home Decor Creator | `home` | `fashion`, `beauty` | ✅ YES (primary) | DIY diyas, rangoli hacks, festive home transformation |
| Fashion Creator | `fashion` | `beauty`, `home` | ✅ YES (primary) | Ethnic wear lookbook, saree draping tutorial, GRWM |
| Tech Creator | `tech` | `gaming`, `finance` | ⚠️ WEAK (secondary via `finance`) | "Best Diwali tech deals", "Budget phone for gifting" |
| Comedy Creator | `comedy` | `food`, `regional` | ❌ NO | (Diwali doesn't activate `comedy` unless "family dynamics" humor is coded separately) |

**Why priority scores?**
- Prevents **tag spam** (a food creator doesn't get 7 Diwali prompts)
- Enables **angle guidance** ("focus on mithai recipes, not home decor")
- Lets us **weight regional moments** (Onam's top priority = `food`, not `fashion`)

### Multi-tag strategy summary

**Rule:** A creator can match **one festival/event** via:
1. Primary niche (high confidence)
2. Secondary niche (medium confidence, different angle)
3. Regional tag (if creator's region matches event's region)

**The co-pilot returns ONE content brief per match**, not multiple briefs per tag.

---

## 4. VERNACULAR MAPPING: Regional Creators Get State Moments

### The strategic imperative

From the research:
> "Vernacular/regional content is the dominant 2026 creator trend. 60–65% of festive shoppers are Tier-2+ cities."

**Translation:** A **Tamil Nadu food creator** should get:
- `pongal` (Jan 14–17) — NOT `makar-sankranti` (generic pan-India harvest)
- `puthandu` (Apr 14, Tamil New Year) — NOT `ugadi` (Telugu/Kannada New Year same day)
- `ne-monsoon` (Oct–Nov, brings 50% of TN annual rain) — NOT just `monsoon` (SW monsoon, Jun–Sep, misses South)

### How regional matching works

**Creator profile includes:**
1. **Primary region** (e.g., `tamil-nadu`, `kerala`, `maharashtra`)
2. **Content language** (e.g., `tamil`, `telugu`, `hindi`, `english`)

**Tag activation logic:**

```
IF event.region == creator.primary_region:
  MATCH = TRUE (regional priority)

ELSE IF event.region == "pan-india":
  MATCH = TRUE (fallback for national festivals)

ELSE:
  MATCH = FALSE (don't show Punjab's Lohri to a Tamil Nadu creator)
```

### Regional tag → Festival mapping (reference table for Vikram)

| Region Tag | Festivals Activated | Season Tags | Notes |
|------------|---------------------|-------------|-------|
| `tamil-nadu` | `pongal`, `puthandu`, `ne-monsoon` | `ne-monsoon`, `summer`, `winter` | Pongal is 4-day (Bhogi→Mattu Pongal); NE monsoon Oct–Nov is TN's primary rain season |
| `kerala` | `onam`, `vishu`, `monsoon` | `monsoon`, `summer`, `winter` | Onam 10-day (Atham→Thiruvonam); Vishu Apr 14 (solar New Year) |
| `karnataka` | `ugadi`, `ganesh-chaturthi` | `monsoon`, `ne-monsoon`, `summer` | Ugadi shared with Telangana/AP; Ganesh big in coastal Karnataka |
| `telangana-ap` | `ugadi`, `ne-monsoon` | `ne-monsoon`, `summer`, `monsoon` | Telugu New Year; coastal AP gets NE monsoon |
| `maharashtra` | `ganesh-chaturthi`, `gudi-padwa`, `dussehra` | `monsoon`, `summer`, `winter` | Ganesh is 10-day; Gudi Padwa is Marathi New Year (Mar 19) |
| `gujarat` | `navratri`, `garba`, `uttarayan` (kite festival = Makar Sankranti) | `monsoon`, `summer`, `winter` | Navratri/Garba 9 nights; Gujarat does kite-flying big on Makar Sankranti |
| `west-bengal` | `durga-puja`, `poila-boishakh` (Bengali New Year, Apr 15) | `monsoon`, `summer`, `winter` | Durga Puja 5-day; Poila Boishakh not in research (add if Bengali creator demand) |
| `punjab` | `lohri`, `baisakhi`, `guru-nanak-jayanti` | `winter`, `summer`, `monsoon` | Lohri Jan 13 (bonfire); Baisakhi harvest (Apr 13–14) |
| `bihar-up` | `chhath`, `durga-puja` (in UP), `makar-sankranti` | `monsoon`, `summer`, `winter` | Chhath 4-day (Bihar/UP/Jharkhand diaspora); Makar Sankranti as harvest |
| `northeast` | `bihu`, `hornbill-festival`, `losar` (if Sikkim coded separately) | `monsoon`, `summer`, `winter` | Bihu 3 types (Rongali/Bohag Apr, Kati Oct, Magh Jan); Hornbill Dec 1–10 Nagaland |
| `goa` | `ganesh-chaturthi`, `christmas`, `carnival` (Feb–Mar, not in research) | `monsoon`, `summer`, `winter` | Ganesh + Christmas both big; Carnival if we add (pre-Lent) |
| `kashmir` | `navreh` (Kashmiri New Year, ~Mar 19), `tulip-festival` (Apr, not in research) | `winter`, `summer` | Navreh same-day as Gudi Padwa/Ugadi cluster; winter is tourism peak |
| `rajasthan` | `teej` (monsoon festival, Aug, not in research), `pushkar-camel-fair` (Oct–Nov, not in research) | `heatwave`, `summer`, `winter`, `monsoon` | Rajasthan gets 45°C+ in May; winter is tourism/wedding season |
| `pan-india` | `diwali`, `holi`, `eid-ul-fitr`, `eid-ul-adha`, `raksha-bandhan`, `janmashtami`, `dussehra`, `maha-shivratri`, `karwa-chauth`, `dhanteras`, `independence-day`, `republic-day`, `budget-day` | `monsoon`, `summer`, `winter` | Fallback for creators with no regional specificity |

**Gaps to fill (not in 2026 research, but high creator-demand):**
- `poila-boishakh` (Bengali New Year, Apr 15) — if Bengali creators emerge
- `carnival` (Goa, Feb–Mar) — if Goa travel/party creators emerge
- `tulip-festival` (Kashmir, Apr) — if Kashmir travel creators emerge
- `pushkar-camel-fair` (Rajasthan, Oct–Nov) — if Rajasthan travel creators emerge
- `teej` (Rajasthan/North, monsoon festival, Aug) — if Rajasthan/Haryana regional creators emerge

**Decision:** Add these only if **creator profiles in the DB show regional concentration**. Don't pre-build tags for zero creators.

### Vernacular content output (not tag naming)

**Tags are English romanization** (`onam`, `pongal`, `chhath`). **Content briefs generated by the co-pilot** can use vernacular:

- Tamil creator → brief includes "Pongal பொங்கல் recipe ideas"
- Bengali creator → brief includes "Durga Puja দুর্গা পূজা outfit trends"
- Punjabi creator → brief includes "Lohri ਲੋਹੜੀ bonfire comedy skits"

**This is Ishaan's responsibility (content writer)**, not the taxonomy. Tags identify **what** to activate; briefs define **how** to say it.

---

## 5. MAINTENANCE PLAN: Keeping the Calendar Fresh Beyond 2026

### The 2027 problem

**Issue:** The research delivered a **2026 calendar**. Hindu/Islamic festivals shift dates yearly (lunar/solar calendars). Some 2026 dates are disputed (sources disagree by 1–20 days due to Adhik Maas leap-month).

**What breaks in 2027:**
- Diwali **Nov 8, 2026** → **Oct 20, 2027** (19-day shift)
- Eid-ul-Fitr **~Mar 20, 2026** → **~Mar 10, 2027** (moon-dependent)
- Navratri **Oct 11–20, 2026** → **Sep 25–Oct 4, 2027**

### Maintenance strategy: Tags stay, dates update

**Taxonomy tags are DATE-AGNOSTIC.**

```json
{
  "id": "diwali",
  "category": "festivals",
  "region": "pan-india",
  "activates": ["home", "beauty", "fashion", "finance", "food", "devotional", "gifting"],
  "priority_scores": {
    "home": 10,
    "beauty": 8,
    "fashion": 8,
    "food": 6,
    "finance": 5,
    "devotional": 4,
    "gifting": 3
  }
}
```

**Date mapping lives in a SEPARATE events table:**

```json
{
  "year": 2026,
  "events": [
    { "tag": "diwali", "start_date": "2026-11-08", "end_date": "2026-11-08", "pre_window_days": 14, "post_window_days": 3 },
    { "tag": "holi", "start_date": "2026-03-04", "end_date": "2026-03-04", "pre_window_days": 7, "post_window_days": 2 },
    { "tag": "onam", "start_date": "2026-08-16", "end_date": "2026-08-26", "pre_window_days": 10, "post_window_days": 5 }
  ]
}
```

**For 2027, we update ONLY the events table**, not the taxonomy.

### Who updates the dates? (Annual responsibility)

**Owner:** Nisha (Content Lead)  
**Timeline:** Q4 every year (Oct–Dec) for next year's calendar  
**Sources:**
1. **Drik Panchang** (drikpanchang.com) — Hindu festival authority
2. **Islamic Finder** (islamicfinder.org) — Eid dates
3. **Government of India gazetted holidays** (mha.gov.in) — Republic Day, Independence Day, Buddha Purnima, Christmas
4. **E-commerce sale announcements** (Amazon, Flipkart PR; typically Aug–Sep for Oct–Nov festive sales)

**Process:**
1. Nisha scrapes/checks sources in **Oct** (for next year)
2. Nisha updates `wiki/processes/events-calendar-2027.json` (new file each year)
3. Vikram (backend) imports the JSON into the DB
4. Aditya (SEO) verifies keyword trends align (e.g., if "Diwali 2027" search volume spikes Oct 20, not Nov 8)

**Verification:** Meera (DB/DevOps) runs a script to check:
- No event.start_date is in the past (relative to current year)
- All tags in events table exist in taxonomy
- No date collisions (two events same day in same region, unless intentional like Ugadi/Gudi Padwa)

### What about NEW festivals/events?

**Trigger for adding a new tag:**
1. **Creator demand** — 10+ creators in a region request a festival (e.g., Poila Boishakh for Bengali creators)
2. **SEO keyword volume** — Aditya flags a festival with >10k monthly searches (e.g., Tulip Festival Kashmir)
3. **Commercial opportunity** — A new sale event launches (e.g., "Nykaa Hot Pink Summer Sale" added in 2026)

**Approval flow:**
1. Nisha proposes new tag → Aditya validates SEO keyword → Tejas approves (CMO strategic fit) → Vikram implements

**Removal criteria:**
- Tag had zero creator matches for 2 consecutive years → deprecate (don't delete; mark `"status": "deprecated"` so old content still references it)

---

## 6. IMPLEMENTATION CHECKLIST (For Vikram)

### Database schema (Postgres/Prisma)

**Table: `theme_taxonomy`**
```sql
CREATE TABLE theme_taxonomy (
  id SERIAL PRIMARY KEY,
  tag_id VARCHAR(100) UNIQUE NOT NULL,  -- e.g., "diwali", "onam", "tamil-nadu"
  category VARCHAR(50) NOT NULL,        -- "festivals", "regions", "seasons", "events", "commercial"
  subcategory VARCHAR(50),              -- "pan-india", "regional", "south", "west", etc.
  region VARCHAR(100),                  -- NULL for pan-india; "tamil-nadu" for regional
  activates JSONB,                      -- ["home", "beauty", "fashion", "finance", "food"]
  priority_scores JSONB,                -- {"home": 10, "beauty": 8, ...}
  status VARCHAR(20) DEFAULT 'active',  -- "active", "deprecated"
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);
```

**Table: `events_calendar`**
```sql
CREATE TABLE events_calendar (
  id SERIAL PRIMARY KEY,
  year INT NOT NULL,
  tag_id VARCHAR(100) REFERENCES theme_taxonomy(tag_id),
  start_date DATE NOT NULL,
  end_date DATE,
  pre_window_days INT DEFAULT 14,       -- Content ideation starts 14 days before
  post_window_days INT DEFAULT 3,       -- Post-event analysis window
  notes TEXT,                           -- "Date disputed; sources vary 1–5 days"
  created_at TIMESTAMP DEFAULT NOW(),
  UNIQUE(year, tag_id)
);
```

### API endpoint (for co-pilot query)

**Endpoint:** `GET /api/creator-copilot/active-trends`

**Query params:**
- `creator_id` (UUID) — to fetch creator's region + niche
- `date` (ISO date) — default = today
- `lookahead_days` (int) — default = 14 (look 14 days ahead for upcoming trends)

**Response:**
```json
{
  "creator_id": "uuid-123",
  "creator_region": "tamil-nadu",
  "creator_primary_niche": "food",
  "creator_secondary_niches": ["home", "devotional"],
  "active_trends": [
    {
      "tag_id": "pongal",
      "category": "festivals",
      "region": "tamil-nadu",
      "start_date": "2026-01-14",
      "end_date": "2026-01-17",
      "match_reason": "region_match + primary_niche_match",
      "suggested_angle": "4-day Pongal recipes: Sakkarai Pongal, Ven Pongal, traditional clay-pot cooking",
      "priority_score": 10,
      "content_types": ["food", "devotional", "home"],
      "days_until_start": 7
    },
    {
      "tag_id": "ne-monsoon",
      "category": "seasons",
      "region": "tamil-nadu",
      "start_date": "2026-10-01",
      "end_date": "2026-11-30",
      "match_reason": "region_match + seasonal_context",
      "suggested_angle": "Monsoon comfort food: hot chai, pakora, kozhukattai (steamed dumplings)",
      "priority_score": 6,
      "content_types": ["food", "home"],
      "days_until_start": 248
    }
  ]
}
```

### Migration script (populate initial taxonomy)

**Location:** `influora-api/src/main/resources/db/migration/` (Flyway or Liquibase)

**Script:** `V99__populate_theme_taxonomy_2026.sql`

**Contents:**
1. Insert 64 tags from research (28 festivals, 14 regions, 6 seasons, 9 events, 7 commercial)
2. Insert 2026 events calendar (month-by-month from research doc)
3. Set priority scores per festival (Nisha provides in `wiki/processes/festival-priority-scores.json`)

**Nisha's action item:** Create `wiki/processes/festival-priority-scores.json` before Vikram implements.

---

## 7. EDGE CASES & OPEN QUESTIONS

### Edge Case 1: Multi-day festivals (10-day Onam, 9-night Navratri)

**Question:** Does the co-pilot fire ONE content brief on Day 1, or multiple briefs across the 10 days?

**Proposed answer:** ONE brief **14 days before start**, with a **multi-day content arc**:

**Example (Onam, 10-day):**
```
Brief: "Onam 10-day content series"
Day 1 (Atham): "Onam countdown begins — Pookalam Day 1 tutorial"
Day 5: "Midpoint — Onasadya ingredient prep checklist"
Day 10 (Thiruvonam): "Grand feast day — full 29-dish Onasadya recipe video"
Post-event (Day 12): "Leftover Onasadya hacks" (engagement bump)
```

**Decision needed:** Does Ishaan (content writer) write the full 10-day arc in ONE brief, or does Nisha break it into separate briefs per phase? → **Nisha decides**, but default = ONE brief with phased content suggestions.

### Edge Case 2: Same-day multi-region festivals (Mar 19: Ugadi, Gudi Padwa, Navreh, Cheti Chand)

**Question:** A **pan-India creator** (no regional tag) gets which festival on Mar 19?

**Proposed answer:** The co-pilot shows **all matching festivals** with a disambiguation note:

```
Active Trends (Mar 19):
- ugadi (Telangana/AP/Karnataka New Year)
- gudi-padwa (Maharashtra/Goa Marathi New Year)
- navreh (Kashmiri Pandit New Year)
- cheti-chand (Sindhi New Year)

Suggested approach: "Regional New Year cluster — create a pan-India carousel comparing how different states celebrate their New Year on the same day."
```

**If the creator HAS a regional tag (e.g., Maharashtra):** Show ONLY `gudi-padwa`, suppress the others.

### Edge Case 3: Commercial events with no regional specificity (Prime Day, Black Friday)

**Question:** Does a **regional creator** (e.g., Tamil Nadu food creator) get Prime Day (tech-heavy, urban-skewed) as an active trend?

**Proposed answer:** YES, but **priority-scored lower** than regional festivals.

**Example (Tamil food creator in July):**
- `ne-monsoon` → priority 10 (regional + seasonal + niche match)
- `prime-day` → priority 3 (niche mismatch: tech ≠ food; but could do "Prime Day kitchen appliance hauls")

**The co-pilot shows both**, ranked by priority. Creator can ignore low-priority suggestions.

### Edge Case 4: Festivals with disputed dates (Onam Aug 26 vs Sep 1; Chhath Nov 14 vs Nov 1)

**Solution:** The `events_calendar.notes` field flags this:

```sql
INSERT INTO events_calendar (year, tag_id, start_date, end_date, notes)
VALUES (2026, 'onam', '2026-08-16', '2026-08-26', 'Main day (Thiruvonam) disputed: Aug 26 per drikpanchang.com, Sep 1 per one source. Using Aug 26 as primary.');
```

**Nisha's action:** For any disputed date, include a note in the brief:
> "Note: Thiruvonam date varies by panchang (Aug 26 vs Sep 1). Pin exact date 2 weeks before for time-sensitive posts."

---

## 8. ADITYA'S SEO KEYWORD VALIDATION (Next Step)

**Task for Aditya:**

Review the 64 proposed tags and validate:

1. **Search volume** — Does the tag match a keyword cluster with >1k monthly searches in India?
   - Example: `diwali` ✅ (1.2M searches/month Oct–Nov)
   - Example: `navreh` ⚠️ (low volume; niche Kashmiri audience — keep if creator demand exists, flag as low-SEO)

2. **Keyword variants** — Should any tag be renamed to match dominant search term?
   - Example: Is `ganesh-chaturthi` or `ganpati` the higher-volume search? (Research used `ganesh-chaturthi`; confirm)
   - Example: Is `raksha-bandhan` or `rakhi` dominant? (Research used `raksha-bandhan`; confirm)

3. **Missing high-volume keywords** — Are there India-event keywords with >10k searches that we DIDN'T tag?
   - Example: `karva-chauth` (research has `karwa-chauth`) — which spelling?
   - Example: `teej`, `tulip-festival`, `poila-boishakh`, `pushkar-fair` — should we add?

**Aditya's deliverable:** `wiki/processes/taxonomy-seo-validation-report.md`

**Format:**
```markdown
## HIGH-CONFIDENCE TAGS (SEO-backed, keep as-is)
- diwali (1.2M searches/mo Oct–Nov)
- holi (800k searches/mo Feb–Mar)
- ...

## RENAME RECOMMENDATIONS
- ganesh-chaturthi → ganpati (60k vs 40k searches; "Ganpati" is colloquial dominant)
- raksha-bandhan → rakhi (50k vs 30k; "Rakhi" is shorter, more common)

## LOW-VOLUME TAGS (keep if creator demand, flag as niche)
- navreh (1.2k searches; Kashmiri-only)
- cheti-chand (3k searches; Sindhi-only)

## MISSING HIGH-VOLUME KEYWORDS (recommend adding)
- teej (45k searches Aug; Rajasthan/North monsoon festival for women)
- poila-boishakh (12k searches Apr; Bengali New Year)
```

**Deadline:** Aditya delivers SEO validation **before Vikram implements** the taxonomy.

---

## 9. VIKRAM'S IMPLEMENTATION CHECKLIST (After Aditya's Validation)

- [ ] Read Aditya's SEO validation report
- [ ] Finalize tag list (apply any renames from Aditya)
- [ ] Create Prisma schema for `theme_taxonomy` and `events_calendar` tables
- [ ] Write Flyway migration script (`V99__populate_theme_taxonomy_2026.sql`)
- [ ] Populate `theme_taxonomy` with 64 tags (or adjusted count post-Aditya)
- [ ] Populate `events_calendar` with 2026 dates from research doc
- [ ] Implement `GET /api/creator-copilot/active-trends` endpoint
- [ ] Write unit tests for region-matching logic
- [ ] Write unit tests for priority-scoring logic
- [ ] Write integration test: "Tamil Nadu food creator on Jan 1 → returns Pongal (Jan 14–17)"
- [ ] Deploy to staging
- [ ] Meera verifies: `npm run build`, `curl` endpoint test, DB migration rollback test
- [ ] Kavya QA: Cross-region collision test (Maharashtra creator doesn't get Onam), date-window test (14-day lookahead works)
- [ ] Nisha content-spot-check: Do returned `suggested_angle` strings make sense for 5 sample creators?
- [ ] Deploy to production (gated on live test pass)

**Estimated effort:** 3–5 days (Vikram)  
**Dependencies:** Aditya's SEO validation (1 day), Nisha's priority-scores JSON (0.5 day)

---

## 10. SUCCESS METRICS (How We Know This Works)

**Q1 2027 (Jan–Mar):**
1. **Coverage:** 80% of active creators receive at least ONE relevant trend suggestion per month
2. **Relevance:** <10% of suggestions are dismissed as "not relevant to my niche" (user feedback)
3. **Regional accuracy:** Tamil Nadu creators get Pongal (Jan 14–17), NOT Lohri (Jan 13) or Makar Sankranti (generic harvest)
4. **Content output:** 30% increase in festival-tagged posts during Jan–Mar 2027 vs Jan–Mar 2026 (baseline = pre-taxonomy state)

**Measure via:**
- Co-pilot analytics: trend-suggestion-view rate, dismiss rate, accept-and-use rate
- Creator content tags: % of posts during Pongal week that include `#Pongal` or Pongal-related captions
- Aditya's SEO tracking: Do Pongal-tagged posts rank for "Pongal recipes 2027" (keyword alignment validation)

---

## CONCLUSION

This taxonomy expansion transforms the Creator AI Co-pilot from a **generic calendar** ("it's October, post about Diwali") into a **regional, niche-aware, year-round content engine** that knows:

- A **Tamil Nadu food creator** gets Pongal (Jan 14–17), not Lohri
- A **Kerala travel creator** gets NE monsoon (Oct–Nov), not SW monsoon (Jun–Sep)
- A **Maharashtra devotional creator** gets Ganesh Chaturthi (10-day, Sep 14), with a phased content arc
- A **pan-India finance creator** gets Dhanteras (Nov 5–6, gold buying), not just "Diwali shopping"

**Next steps:**
1. Nisha creates `wiki/processes/festival-priority-scores.json` (Vikram needs this)
2. Aditya validates SEO keywords (flags renames, missing tags)
3. Vikram implements taxonomy + API (3–5 days)
4. Meera verifies build + DB migration
5. Kavya QA tests region/niche matching
6. Nisha spot-checks content suggestions for 5 sample creators
7. Deploy to production → measure Q1 2027 coverage & relevance

**Owner for 2027 annual update:** Nisha (Q4 2026, Oct–Dec)  
**Maintenance cost:** ~2 days/year (date scraping + JSON update)

---

**Document Status:** DESIGN COMPLETE → Pending Aditya SEO validation → Pending Vikram implementation  
**Last Updated:** 2026-07-22 by Nisha Patel
