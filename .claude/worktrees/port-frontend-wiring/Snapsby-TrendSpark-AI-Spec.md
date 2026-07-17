# Snapsby Trend-Spark AI — Architecture Spec (v1)

**Owner:** Priya (CTO) · **AI layer:** Ash · **Marketing:** Tejas · **Pipeline:** Arjun
**Approved model:** Rule-based matching first, AI-scored later (Swapnil's call)
**Date:** 2026-07-12

---

## 1. What we are building (one line)

An AI inside Snapsby that watches what's trending, connects it to a brand's category, and — at the right moment — nudges the brand toward a **ready-to-buy UGC video from our own catalog** that fits the trend.

The trend creates the desire. Snapsby fills it instantly. That is the whole loop.

```
Trend detected  →  matched to brand category  →  matched to campaign type
      →  matched to real Snapsby videos  →  AI nudges brand  →  purchase
```

---

## 2. The two brains (why it stays cheap AND feels smart)

| Job | Who does it | Cost |
|-----|-------------|------|
| Decide WHAT to suggest (trend → brand → video match) | **Rules** (lookup tables) | ~₹0 |
| Decide HOW to say it (natural, warm conversation) | **AI** (one short call) | small |

Rules are the logic. AI is the voice. This is the key to keeping it affordable at scale.

---

## 3. The six data sources

### Tier 1 — free, reliable, build first

**1. Google Trends** — what India is searching right now, region-filtered. The pulse.
**2. NewsAPI / GNews** — headlines by category (entertainment, sports, business). "What launched today."
**3. TMDb** — official movie / OTT release calendar. This is how we know a release DATE *before* it happens.
**4. YouTube Trending API** — trending videos in India (official, free). Social signal, safe.
**5. Festival & Event Calendar** — pre-loaded file: Diwali, Eid, Holi, cricket fixtures, big sale days. Known in advance, no API needed.

### Tier 0 — our own goldmine

**6. Snapsby catalog** — our 500+ videos, each tagged by niche, language, format, theme. This is what every trend suggestion resolves INTO. Without this, the AI is just talk. With it, every nudge ends in a purchasable product.

### Deliberately excluded from v1
- **X/Twitter** — API now paid (₹8,000+/mo). Add after revenue justifies it.
- **Instagram / TikTok scraping** — against their terms, breaks constantly, legal risk. Only via official partner API, later.

---

## 4. How the daily trend pull works (Dev builds in n8n)

```
Every morning, 6 AM:
  n8n workflow fires
    → calls Google Trends, NewsAPI, TMDb, YouTube
    → merges raw trends
    → auto-tags each trend with THEMES (strength, family, festive, luxury...)
    → stamps each with dates + peak window
    → writes to `trends` table in MySQL
All day:
  AI reads the ready `trends` table — never calls sources live
```

Every trend record looks like this:

```json
{
  "trend": "Salman Khan — new action film",
  "source": ["tmdb", "google_trends"],
  "detected_date": "2026-07-12",
  "peak_window_days": 3,
  "expires": "2026-07-15",
  "themes": ["strength", "action", "energy", "masculinity"],
  "campaign_type": "hype"
}
```

`peak_window` drives the campaign type: movie = 3 days (HYPE), festival = 3 weeks (SEASONAL), cricket = 1 day (PRIDE). After `expires`, n8n auto-deletes it so nothing stale is ever suggested.

---

## 5. The rule-based matching engine (3 tables)

**Table A — Trend themes** (auto-tagged on ingest)
```
Salman Khan action film → [strength, action, energy]
Diwali                  → [family, festive, light, tradition]
India cricket win       → [pride, victory, energy]
```

**Table B — Brand profile themes** (set once per brand at signup)
```
Fitness supplement brand → [strength, health, energy, discipline]
Saree / apparel brand    → [festive, tradition, elegance]
Skincare brand           → [beauty, glow, self-care]
```

**Table C — Campaign-type rulebook**
```
Big movie / celeb, short window  → HYPE campaign
Festival                          → SEASONAL campaign
Sports win                        → PRIDE campaign
Evergreen health trend            → EDUCATIONAL campaign
```

**Match logic (plain English):**
```
1. For today's trend, read its themes.
2. Compare to the brand's profile themes → count overlap = score.
3. If score ≥ threshold → trigger. Else stay silent.
4. Read trend's campaign_type from the rulebook.
5. Query Snapsby catalog for videos matching (brand niche + campaign theme).
6. Hand the top 2–3 videos + the angle to the AI voice layer.
```

**The silence rule matters:** if a leather-bag brand meets a cricket win and themes don't overlap, score is low → AI says nothing. This is the feature protecting the user from spammy, irrelevant nudges.

---

## 5b. The anti-spam gate — Snapsby is a GAP-FILLER, not always-on

**Rule (Swapnil):** The AI does NOT push Snapsby every time. Its default job is to help
the brand use THEIR OWN content for a trend. Snapsby is only suggested when the brand
has a **content gap**.

```
Trend matches brand
      ↓
Content-gap check:
  Does the brand have their own recent / matching content?
      ↓
  YES → AI helps them use THEIR content. Snapsby NOT mentioned.
  NO  → AI suggests a ready Snapsby UGC video to catch the trend fast.
```

**A "content gap" = at least one of these is true:**
- Brand hasn't posted in N days (default 4+)
- Brand has no video matching this trend's theme in their own library
- Trend window is closing fast (hype, 1–3 days) and they have nothing ready
- Brand is new / empty catalog

If none are true → stay in "use your own content" mode, never push the marketplace.

**Why:** when the AI *does* surface Snapsby, it lands as a genuine rescue at the
highest-intent moment (empty shelf + closing trend) — not as an ad. Trust + conversion,
minus the spam.

---

## 6. The AI voice layer (Ash's domain)

Only ONE small AI call happens, and only after the rules have already decided everything. The AI's job is narrow: turn a structured match into a warm, natural, non-robotic message.

**Prompt shape (Ash's spec — structured in, structured out):**
```
SYSTEM: You are Snapsby's friendly campaign assistant. Given a brand,
a trend, a campaign angle, and 2-3 matching videos, write ONE short,
warm nudge (max 2 sentences) that connects the trend to the brand and
points to the videos. Never pushy. Indian, conversational tone.

INPUT (all from the rules — AI invents nothing factual):
  brand: "GlowStrength fitness supplement"
  trend: "Salman Khan action film, strength theme, 3-day buzz"
  campaign_type: "hype"
  videos: [{id, title, price, language}, ...]

OUTPUT: { "message": "...", "video_ids": [...] }
```

**Ash's non-negotiables:**
- **Cheap model** for this (Haiku-class) — it's phrasing, not reasoning. Don't pay Opus prices.
- **Structured JSON out**, parsed defensively (strip fences, try/catch, fallback message on failure).
- **AI never invents facts** — trend, price, video IDs all come from the rules. AI only writes the sentence. This kills hallucination risk.
- **Guardrail:** if the AI call fails or returns junk → fall back to a plain templated message. User never sees an error.
- **Data flywheel from day one:** log every nudge + whether the user clicked/bought. That log becomes our eval set and, later, our few-shot examples.

---

## 7. Example — full flow, one brand

```
Brand: GlowStrength (fitness supplement) — themes [strength, health, energy]

6 AM:  n8n ingests "Salman Khan action film" → themes [strength, action, energy]
Match: "strength" + "energy" overlap → score HIGH → trigger
Type:  short window → HYPE campaign
Catalog query: Snapsby videos WHERE niche=fitness AND theme=strength
        → returns 3 videos (₹2,000 each, Hindi)
AI voice: "Salman's new film is all raw strength this week — same energy
        your supplement sells. I found 3 ready fitness videos you could
        launch today while the buzz is hot. Want a peek?"
User: "show me" → previews → buys one → campaign live same day
```

---

## 8. Phased roadmap — small to advanced

### Phase 1 — MVP (v1): "Manual spark" (2 weeks)
- Sources: **festival calendar + Google Trends + TMDb** only (3 easiest)
- Rule-based matching, single small AI call for phrasing
- Trigger point: when brand **opens Snapsby** (not idle-timer yet — simpler)
- Resolves to Snapsby catalog videos
- Log every nudge + click + purchase
- **Goal:** prove brands click and buy from a trend nudge. Cost ≈ ₹0 extra.

### Phase 2 — Live signals (v2): "Always fresh" (add ~2 weeks)
- Add **NewsAPI + YouTube Trending** feeds
- Add **idle-timer trigger** (the 15-sec creative-spark you wanted)
- Add brand-profile theme auto-suggestion at signup
- A/B test 2–3 nudge styles, keep the winner
- **Goal:** higher trigger accuracy, more coverage, measure engagement lift.

### Phase 3 — Smart engine (v3): "AI-scored" (only after data proves demand)
- Upgrade matching from rules → **AI-scored** for the tricky cases rules miss
   (e.g. "soldier film → your body's defence system → immunity supplement")
- Use the Phase-1/2 purchase log as the eval set to prove AI-scoring actually beats rules before switching
- Optional: **creator-side nudge** — tell creators "strength content is trending, upload now" to keep catalog fresh ahead of demand
- Add paid sources (X/Twitter) if ROI is there
- **Goal:** creativity rules can't reach, without changing what the user sees.

---

## 9. Who builds what (Arjun's breakdown)

| Agent | Task |
|-------|------|
| **Priya** | Lock architecture, approve schema, set API-key security (.env only) |
| **Dev** | n8n daily trend-pull workflow + theme auto-tagging |
| **Vikram** | Backend: `trends` table, catalog-match query, AI-call route, nudge-log table |
| **Ananya** | Frontend: nudge UI card, preview handoff, idle-timer (Phase 2) |
| **Ash** | Prompt design, model choice, output validation, eval loop, flywheel logging |
| **Nisha / Tejas** | Theme taxonomy, campaign-type rulebook, nudge tone guidelines |
| **Kavya → Meera → Kabir** | QA → local verify → security audit before ship |
| **Rohan** | Track API costs; flag Swapnil before any free-tier limit is crossed |

---

## 10. Cost posture (Rohan)

- Phase 1 sources: **all free tier** → ~₹0 incremental.
- Only per-interaction cost: one cheap AI phrasing call per nudge. Capped and logged.
- Flag to Swapnil **before** crossing any free-tier limit or adding a paid source.

---

## 11. Open decisions for Swapnil

1. **Trigger point for v1** — on-open (simpler) or idle-timer (your original idea)? *Recommendation: on-open first, idle-timer in Phase 2.*
2. **Who is the AI aimed at first** — brands (nudge to buy) or creators (nudge to make trending content)? *Recommendation: brands first, creators in Phase 3.*
3. **Persona/voice** — the warm conversational agent you wanted to build (the "rename Meera" question). Decide the name + tone and it becomes this nudge voice.

---

*v1 spec — rule-based, Snapsby-integrated. Locked pending Swapnil's three decisions above.*
