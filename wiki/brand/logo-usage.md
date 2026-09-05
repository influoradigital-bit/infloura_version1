# Influora Logo Usage Specification

Owner: Zara (Graphics Designer) · Reviewed by: Priya (CTO) · Ruling authority: Swapnil
Date: 2026-09-05
Source of record: `influora logo.png` (repo root) — **do not modify**

---

## 0. Asset status — READ FIRST

**The vector assets described in this document do not exist yet, and were not produced.**

The only supplied artwork is a 500×500 PNG in which the logo occupies 451×83 px. That is not
enough information to build production vector without guessing. Section 8 lists exactly what
must be obtained before `public/brand/` can be populated.

| File | Status |
|---|---|
| `public/brand/logo-lockup.svg` | **BLOCKED** — needs original vector |
| `public/brand/logo-lockup-dark.svg` | **BLOCKED** — needs original vector |
| `public/brand/mark.svg` | **BLOCKED** — needs original vector |
| Favicon / app-icon PNG set | **BLOCKED** — and see §6, a straight downscale will not work |

Everything below (colour, geometry, clearspace, rules) is measured from the source PNG and is
correct. It is the specification the vector will be held to when it arrives.

---

## 1. Colour

### Measured from source

| Role | Hex | HSL | Notes |
|---|---|---|---|
| Wordmark navy | `#1A237E` | H235 S66% L30% | Exactly **Material Indigo 900**. Not a coincidence — treat the Indigo ramp as the sanctioned tint/shade family. |
| Mark orchid | `#9B58B5` | H283 S39% L53% | The only element in the brand pulling toward magenta. |

The source PNG contains 2,943 distinct colours; all but two are anti-aliasing. There are no
gradients, no strokes, no effects. The artwork is two flat fills.

### Dark-mode wordmark — specified

| Role | Hex | Contrast on `#2A2838` |
|---|---|---|
| Wordmark, dark backgrounds | **`#C5CAE9`** | **8.91:1** |

**Derivation.** `#1A237E` is Material Indigo 900. `#C5CAE9` is Material Indigo 100 — the same
hue ramp at the light end. Hue shift is **2.9°**, so the wordmark keeps its indigo identity
instead of becoming generic white text. This is a lightness move within the brand's own ramp,
not an inversion. (A naive RGB inversion of `#1A237E` gives `#E5DC81`, a dull yellow, and is
forbidden — see §7.)

Alternative if Tejas wants more punch: `#E8EAF6` (Indigo 50, 12.02:1). Rejected as the default
because at L84% → L94% it reads as white and surrenders the indigo character that makes the
wordmark recognisable.

### Contrast reference

| Combination | Ratio | Verdict |
|---|---|---|
| `#1A237E` on `#2A2838` (dark bg) | **1.09:1** | Invisible. This is the bug. |
| `#C5CAE9` on `#2A2838` | 8.91:1 | Passes AAA. Specified. |
| `#1A237E` on `#FAF9FD` (light bg) | 12.63:1 | Excellent. |
| `#9B58B5` on `#FAF9FD` | 4.49:1 | Acceptable. |
| `#9B58B5` on `#2A2838` | **3.05:1** | **Survives but is muddy — see below.** |

**Open issue — the mark on dark.** The orchid was assumed to carry over to dark mode unchanged.
Measured, it lands at 3.05:1. Logotypes are exempt from WCAG 1.4.3 so this is not a compliance
failure, but at small sizes on `#2A2838` the mark reads dim and loses its edges. Recommendation:
lighten the mark to approximately `#B07CC6` (4.49:1) on dark backgrounds only. **This changes a
brand colour and therefore requires Tejas/Swapnil approval — it is not applied in this spec.**

---

## 2. Geometry (measured at source scale, 451×83)

```
┌── mark ──┐ gap ┌──────── wordmark ────────┐
   84×83    19px         348×78                 total 451×83  (5.43:1)
```

| Measurement | Value |
|---|---|
| Full lockup | 451 × 83 px — aspect **5.43:1** |
| Mark | 84 × 83 px — square |
| Gap, mark to wordmark | 19 px (= 0.23 × mark height) |
| Wordmark | 348 × 78 px |
| Wordmark ascender height | 75 px |
| Wordmark stem width | 13 px (2.9% of lockup width) |
| Mark height vs wordmark ascender | mark is 83, ascenders are 75 — the mark **overshoots by ~11%** and is intentionally taller than the text |

The mark is a four-fold rotationally symmetric pinwheel: four circles at N/E/S/W plus four
identical hook forms rotated 90°. Measured agreement under 90° rotation is **95.1%** — consistent
with artwork that was *designed* as exactly symmetric and then degraded by rasterisation at
83 px. This is useful to whoever rebuilds it: the construction is `1 hook + 1 circle, rotated 4×`,
not eight independent shapes.

---

## 3. Clearspace

Let **X = 0.25 × mark height** (≈ 21 px at source scale; ≈ the 19 px internal gap).

Minimum clear area on all four sides of the lockup bounding box = **X**.
Preferred = **2X**.

Nothing enters this zone: no text, no rules, no photographic detail, no other logos, no
container edge. When the lockup sits in a coloured band or card, X is measured from the artwork
bounding box, not from the container padding.

---

## 4. Minimum sizes

| Asset | Digital minimum | Absolute floor | Print minimum |
|---|---|---|---|
| Full lockup | **120 px wide** | 96 px | 30 mm wide |
| Mark alone | **24 px** | see §6 | 8 mm |

Below 120 px the lockup's 2.9% stems fall under ~3.5 px and the wordmark starts to fill in.
Below 96 px it is illegible and the mark must be used alone instead.

**Never use the full lockup as a favicon.** At 32 px the wordmark is ~5 px tall. Use the mark.

---

## 5. Which variant on which background

| Background | Variant |
|---|---|
| `#FAF9FD` app light, white, any surface L > 80% | `logo-lockup.svg` — navy `#1A237E` + orchid `#9B58B5` |
| `#2A2838` app dark, any surface L < 35% | `logo-lockup-dark.svg` — wordmark `#C5CAE9` + orchid |
| Mid-tone surfaces L 35–80% | Neither. Place the logo on a clearspace-respecting solid panel of an approved background instead. |
| Photography | Solid panel only. Never place the logo directly on an image. |
| Single-colour reproduction (fax, embossing, one-colour print) | All-`#1A237E` on light, all-white on dark. A one-colour version must be requested from the designer — flattening the two-colour art loses the mark's internal channels. |

---

## 6. Favicon and app icons — the downscale will not work

This corrects the assumption that the full icon set can be generated by rasterising `mark.svg`
at each size.

The mark's interlocking pinwheel is separated by thin white channels. Measured at source scale
the structural channels are **2–5 px wide against an 83 px mark**, i.e. 2.4–6% of the artwork.
Scaled down:

| Icon size | Channel width |
|---|---|
| 512 px | ~12 px — fine |
| 180 px (`apple-icon`) | ~4 px — fine |
| 64 px | ~1.5 px — marginal |
| 32 px | **~0.8 px — sub-pixel, channels close** |
| 16 px | **~0.4 px — mark renders as a solid blob** |

At 32 px and below the negative space disappears and the four-figure "network of people" idea
is lost entirely — it becomes an undifferentiated purple lozenge.

**Required:** a separate, optically-corrected small-size mark with widened channels and
possibly fewer elements, supplied as its own vector. Standard practice; it is a second drawing,
not a setting. Request it alongside the main vector (§8).

---

## 7. Prohibited — never do these

1. **Never place the navy wordmark on a dark background.** 1.09:1. This is the defect this spec exists to fix.
2. **Never invert the logo colours algorithmically.** Inverting `#1A237E` yields `#E5DC81`, a dull yellow that is not a brand colour. Use the specified `#C5CAE9`.
3. **Never re-space the lockup.** The 19 px gap and the mark's 11% overshoot are part of the drawing.
4. **Never recolour the mark and wordmark independently** outside the two sanctioned variants in §5.
5. **Never stretch, squash, skew, rotate or arc** the lockup. Aspect is locked at 5.43:1.
6. **Never add effects** — no drop shadow, glow, bevel, outline, or gradient fill.
7. **Never re-typeset the wordmark.** It is not a font the team owns; substituting a lookalike is visible and wrong.
8. **Never ship the logo upscaled from `influora logo.png`.** The source is 451×83; anything larger is soft.
9. **Never place the logo on a mid-tone or photographic background** without a solid panel.
10. **Never use the full lockup below 96 px wide,** or as any favicon.
11. **Never re-derive assets by tracing the PNG.** The whole point of this exercise.

---

## 8. What must be obtained before `public/brand/` can be filled

Request from whoever originally designed the logo:

1. **The original vector** — `.ai`, `.svg`, `.eps`, or `.pdf` with live paths. This is the single blocking item.
2. **The wordmark font** — name, foundry, and licence, *or* the wordmark supplied with text already converted to outlines. Required either way; we cannot re-typeset legally or accurately without it.
3. **A small-size mark variant** for ≤32 px use (§6).
4. **A one-colour version** of the mark (§5).
5. **Confirmation of the intended dark-mode treatment**, if the designer already specified one — it supersedes the `#C5CAE9` proposal in §1.

If the original vector is genuinely unavailable, the correct path is a paid redraw by the
designer against this specification — not an internal trace.

---

## 9. Open ruling required — palette collision

The logo introduces two colours that do not currently exist in the app, and one of them
collides with the existing accent.

| | Hex | Hue | Role |
|---|---|---|---|
| Logo navy | `#1A237E` | 235° | wordmark |
| Logo orchid | `#9B58B5` | 283° | mark |
| App `--primary` (existing) | `#6D5AE6` | 248° | every button, link and interactive accent |

**The navy is not the problem.** At 235° it sits close to the app's existing 247–257° family
and serves a different role (wordmark, not interactive accent). It can coexist.

**The orchid is the problem.** `#9B58B5` and `#6D5AE6` are **35° apart** — two purples, both
reading as "the accent colour", far enough apart to clash and close enough to look like a
mistake rather than a choice. Placing the logo next to any primary button puts them side by side.

`#6D5AE6` is already baked into shipped assets (it is the dominant non-background colour in
`public/og-image.png`), so this is not a free change.

Three options, for Swapnil/Tejas to rule on:

- **A — Logo leads.** Re-token `--primary` toward the logo palette. Most coherent, largest blast radius: every button, link, focus ring, chart colour and marketing asset changes.
- **B — Coexist with separation.** Keep `#6D5AE6` as the interactive accent; the orchid appears *only* inside the logo and never as a UI colour. Cheapest. Requires discipline and an enforced rule, or the two purples drift together over time.
- **C — Reconcile.** Move both toward a single agreed accent. Highest design cost, cleanest end state.

**Not Zara's call.** Flagged for ruling. Until it is ruled, do not introduce `#9B58B5` anywhere
in `src/` outside the logo asset itself.

---

## 10. The v0 scaffold icon — FOUND AND FIXED, 2026-09-05

**Zara's original finding, kept for the record:** the entire shipped icon set was the default
v0 / Vercel scaffold icon — the v0 logotype on a black rounded square — verified by opening each
file. Influora was displaying another company's logo in the browser tab, on iOS home screens,
and as `Organization.logo` in JSON-LD, which is what Google uses for knowledge-panel branding.

**Status now — do not re-open this as an open finding.**

| File | Was | Now |
|---|---|---|
| `public/icon.svg` | v0 logotype (`clip0_7960_43945`) | **deleted** — a live URL would keep resolving; a 404 is loud |
| `public/apple-icon.png` | v0 logotype, 180×180 | Influora mark, 180×180, white ground |
| `public/icon-light-32x32.png` / `-dark-` | v0 logotype | Influora mark, transparent |
| `public/icon-light-16x16.png` / `-dark-` | did not exist | Influora mark, transparent |
| `Organization.logo` (`index.html`, `schema.ts:30`) | `/icon.svg` | `/brand/logo-lockup.png` |

All cut from `influora logo.png` by Priya, colour verified exact at `#9B58B5` (755px at that
value). Light and dark favicons are deliberately identical artwork — per Swapnil's ruling the
logo keeps its own colours, and the orchid reads on both grounds.

**These are interim raster assets and are superseded the moment the vector arrives.** Zara's
small-size warning was directionally right but overstated in degree: at 32px the pinwheel is
fully legible, four nodes and interlocking centre intact; at 16px it degrades to a distinct
four-pointed silhouette rather than the undifferentiated lozenge forecast. A separately drawn,
optically-corrected ≤16px mark is still wanted in the final set — see §3.

**Still open (Vikram, 2026-09-05):** `scripts/generate-og-image.mjs` inlines the v0 `<path>`
data verbatim (`:105`) and bakes it into the shipped `public/og-image.png` — the 1200×630 card
on every share of every URL, where the v0 mark sits directly beside the word "Influora". That
same template also still carries a `TDS handled` bullet (`:160`) which Swapnil ruled must go.
Both are being corrected in a separate dispatch.

---

## SWAPNIL RULING — 2026-09-05

**The logo uses its own colours. The app palette does not change.**

- Logo: `#1A237E` (wordmark), `#9B58B5` (mark). Unchanged from the supplied artwork.
- App: `--primary: #6D5AE6` stays. `public/og-image.png` is unaffected and does not need regenerating.
- The two purples (`#9B58B5` vs `#6D5AE6`, 35° apart) therefore **coexist by decision**, not by oversight. Do not "fix" this later by shifting either one.

**Consequence for usage — this is now a layout constraint, not a colour one.** Because the mark's orchid and the UI accent are close enough to read as a mistake when adjacent, the logo needs clearspace from primary-coloured interactive elements. Do not place the lockup immediately beside a primary button, a filled accent chip, or a focus ring. Header and footer placements are fine; a logo inside a CTA block is not.

**A dark-background variant is still required and is not a palette change.** `#1A237E` on the dark theme's `#2A2838` is ~1.05:1 — invisible. A logo having a light-background and a dark-background lockup is standard brand practice, not a recolour of the brand. Zara's recommendation stands: `#C5CAE9` (Material Indigo 100, same ramp as the navy's Indigo 900, 2.9° hue shift, 8.91:1 on dark). The orchid mark carries over unchanged at 3.05:1 — visible, slightly muddy, and acceptable since logotypes are WCAG-exempt.
