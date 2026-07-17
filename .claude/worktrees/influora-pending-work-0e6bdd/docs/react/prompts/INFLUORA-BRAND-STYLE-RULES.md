# Influora — Brand Style Rules (Visual Assets)
### Apply to EVERY Gemini image and video prompt for Influora

> Used by: `INFLUORA-IMAGE-PROMPT-TEMPLATE.md`, `INFLUORA-VIDEO-PROMPT-TEMPLATE.md`

---

## How prompts are structured

```
FINAL_PROMPT = GLOBAL_INFLUORA_BLOCK + ZONE_BLOCK + ASSET_TYPE_BLOCK + SCENE_SPECIFIC + GLOBAL_NEGATIVES
```

Never send scene-specific text without the global Influora block.

---

## Global Influora block (all assets)

```
Brand: Influora — B2B influencer marketing platform, Creator Collaboration OS
Audience: Indian brands and content creators, professional SaaS (not consumer social app)
Palette: Lilac Mist — soft lavender #f0ebfa backgrounds, primary accent #9b8cf2, sky accent #7ec8e8
Lighting: Soft diffused key light, gentle shadows, premium SaaS product feel — not harsh studio
Atmosphere: Trust, collaboration, creativity, modern startup — calm and confident
Feel: Apple-meets-Notion meets creator economy — clean, airy, pastel, never neon cyberpunk
Typography in image: NONE — all text added in React UI
Avoid: Stock photo clichés, fake UI screenshots with lorem ipsum, dark hacker aesthetic, pure black voids
Technical: 4K or high-res, sharp, suitable for web hero and card crops
```

---

## Zone A — Auth & marketing (warm lilac)

Use for login backgrounds, empty states, optional auth stills:

```
Surface:    Soft gradient mesh, frosted glass, subtle floating spheres (match LoginScene3D blobs)
Colors:     #9b8cf2, #c4b5fd, #7ec8e8, #f0ebfa — no orange, no corporate navy
Lighting:   Ethereal, upper-left soft key, purple-pink ambient
Mood:       Welcome, secure, premium onboarding
Avoid:      Literal login forms in image, passwords, email fields, human faces unless diverse creator collage (optional)
```

---

## Zone B — Brand dashboard context (productivity)

Use for campaign empty states, dashboard banners:

```
Surface:    Clean desk-adjacent abstract — charts as soft shapes, not readable data
Colors:     White cards on lavender mist, primary #9b8cf2 accents
Mood:       Organized, scalable campaigns, ROI confidence
Avoid:      Cluttered dashboards, unreadable fake metrics, Excel screenshots
```

---

## Zone C — Creator / discover (sky + lilac)

Use for discover hero fallbacks, creator cards background textures:

```
Surface:    Abstract network nodes, connection lines, platform icons as soft bokeh (no trademark logos)
Colors:     #7ec8e8 dominant with #9b8cf2 highlights
Mood:       Discovery, reach, authentic creators
Avoid:      Instagram logo reproduction, influencer stereotype beach poses
```

---

## Zone D — Creator portfolio (public page)

Use for `/@username` hero static fallback:

```
Surface:    Minimal stage for creator content — spotlight gradient, portfolio frame abstract
Colors:     Balanced lilac + sky, creator content area left neutral
Mood:       Professional creator brand, bookable, credible
Avoid:      Fake follower counts in image, text handles burned in
```

---

## Global negatives (all divisions)

```
NEVER include:
- Text overlays, watermarks, logos burned into image
- Influora wordmark in generated pixels (SVG in code only)
- Lorem ipsum UI mockups
- Cold #000 backgrounds
- Aggressive red error UI unless asset is explicitly "error state illustration"
- Human hands on keyboards (unless specific marketing brief)

ALWAYS include:
- Coherent Lilac Mist art direction
- Soft professional lighting
- Web-safe crop (center-weighted for responsive crop)
```

---

## Asset type modifiers

Append after zone block:

### `hero-static.png` — reduced motion fallback for 3D heroes

```
Shot: Wide 16:9 or 3:2, abstract motion frozen — blurred spheres or network
Use: CanvasFallback, mobile discover hero
Output: public/images/discover/hero-static.png
```

### `empty-state.png` — campaigns, deals, inbox

```
Shot: 1:1 or 4:3 illustration, friendly abstract, single focal object
Mood: "Nothing here yet" — inviting not sad
Output: public/images/campaigns/empty-state.png
```

### `card-texture.jpg` — subtle card background optional

```
Shot: Very subtle grain or gradient, tile-safe
Opacity in UI: 5–15% overlay max
```

---

## Division mapping (Influora portals)

| Portal | Zone | Primary accent in prompts |
|--------|------|---------------------------|
| Brand auth | A | #9b8cf2 |
| Brand app | B | #9b8cf2 + white |
| Discover | C | #7ec8e8 |
| Creator app | C | #7ec8e8 |
| Public portfolio | D | balanced |

---

*Style rules v1.0 — Influora*
