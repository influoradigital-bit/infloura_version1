# Influora — Image Prompt Template (Gemini)
### Per-asset IMAGE prompts for optional visual fallbacks and marketing

> Fork sections into `prompts/INFLUORA-IMAGE-PROMPTS-FILLED.md` when generating.  
> Style: `INFLUORA-BRAND-STYLE-RULES.md`

---

## File output map

```
public/images/
├── auth/
│   ├── hero-static.png          # reduced-motion auth fallback
│   └── hero-static-creator.png   # sky-tinted variant
├── discover/
│   ├── hero-static.png           # mobile + reduced-motion discover
│   └── empty-creators.png        # no search results
├── campaigns/
│   ├── empty-state.png
│   └── hero-banner.jpg           # optional dashboard strip
├── deals/
│   └── empty-inbox.png
├── wallet/
│   └── empty-transactions.png
└── portfolio/
    ├── hero-default.png          # public portfolio fallback
    └── og-default.png            # social share 1200×630
```

---

## Auth — hero-static.png

```markdown
**hero-static.png** (Brand login reduced-motion fallback)

[Zone A block from BRAND-STYLE-RULES]

Subject: Abstract floating soft spheres in lilac and lavender, frosted glass atmosphere, subtle depth of field — matches LoginScene3D blob aesthetic but frozen/static
Shot: 16:9 landscape, center-weighted, safe crop for split auth layout (form left, image right)
Colors: #9b8cf2, #c4b5fd, #f0ebfa, #7ec8e8 accents
Overlay text: NONE
Output: public/images/auth/hero-static.png
Fallback in code: CanvasFallback + img src
```

---

## Auth — hero-static-creator.png

```markdown
**hero-static-creator.png**

[Zone A block]

Subject: Same abstract sphere composition with more #7ec8e8 sky accent — creator portal variant
Output: public/images/auth/hero-static-creator.png
```

---

## Discover — hero-static.png

```markdown
**hero-static.png** (Discover page mobile + reduced-motion)

[Zone C block from BRAND-STYLE-RULES]

Subject: Abstract creator network — soft nodes connected by thin glowing lines, platform-agnostic social graph, no logos
Shot: 21:9 wide strip OR 16:9 — will crop to ~360px height hero band
Colors: #7ec8e8 nodes, #9b8cf2 connections, #f0ebfa fade at edges
Mood: Discovery, searchable creator ecosystem
Output: public/images/discover/hero-static.png
```

---

## Discover — empty-creators.png

```markdown
**empty-creators.png**

[Zone C block]

Subject: Friendly abstract magnifier or filter icon formed from soft particles — empty search state
Shot: 1:1 square, centered illustration
Mood: "Try different filters" — helpful not empty
Output: public/images/discover/empty-creators.png
```

---

## Campaigns — empty-state.png

```markdown
**empty-state.png**

[Zone B block]

Subject: Abstract megaphone or campaign ribbon formed from pastel geometric shapes — no readable text
Shot: 4:3 illustration for empty campaigns list
Output: public/images/campaigns/empty-state.png
```

---

## Deals — empty-inbox.png

```markdown
**empty-inbox.png**

[Zone B block]

Subject: Soft envelope or chat bubble abstract — waiting for first deal
Shot: 1:1
Output: public/images/deals/empty-inbox.png
```

---

## Wallet — empty-transactions.png

```markdown
**empty-transactions.png**

[Zone B block]

Subject: Abstract ledger lines fading into mist — no currency symbols readable
Shot: 16:9 wide shallow
Output: public/images/wallet/empty-transactions.png
```

---

## Portfolio — hero-default.png

```markdown
**hero-default.png** (Public portfolio when no custom banner)

[Zone D block]

Subject: Soft spotlight gradient stage, minimal pedestal for creator content — professional portfolio header
Shot: 16:9, top third brightest for text overlay in React
Output: public/images/portfolio/hero-default.png
```

---

## Portfolio — og-default.png

```markdown
**og-default.png** (Open Graph 1200×630)

[Global Influora block]

Subject: Influora brand atmosphere — lilac mist abstract, NO logo text in image (OG title added via meta tags)
Shot: Exactly 1200×630 composition, center safe zone
Output: public/images/portfolio/og-default.png
```

---

## Generation script notes

```javascript
// Concept — wire in scripts/generate-influora-images.mjs (optional)
// Model: gemini-2.5-flash-image
// Delay: 3000ms between calls
// Prompt = GLOBAL_BLOCK + section above + GLOBAL_NEGATIVES
```

---

## Wiring in React (after generation)

| Asset | Component | Usage |
|-------|-----------|-------|
| `auth/hero-static.png` | `CanvasFallback` | `useReducedMotion` on auth |
| `discover/hero-static.png` | `creator-discovery.tsx` | mobile hero + reduced motion |
| `campaigns/empty-state.png` | `campaigns-list.tsx` | empty state `<img>` |
| `portfolio/hero-default.png` | `creator-portfolio-public.tsx` | default banner |

---

*Image template v1.0 — fill and run when ready for Gemini batch*
