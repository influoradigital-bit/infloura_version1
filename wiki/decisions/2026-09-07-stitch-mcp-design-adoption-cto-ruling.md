# CTO RULING — Stitch MCP designs: layout, images, colour

**Date:** 2026-09-07
**Author:** Priya (CTO)
**Question from:** Swapnil, via Arjun
**Question:** *"The pages we have — can we use the layout & images from the Stitch MCP, also with the color code? Does this work?"*
**Status:** RULING. Assessment only — no code written, no files changed outside this document.

---

## 0. Verdict in one line

**LAYOUT: YES (partial — reference, not source).**
**COLOUR: NO to adopting the Stitch palette; YES to adopting the Stitch *token architecture*.**
**IMAGES: NO. Do not ship any of the 31.**

And a fourth answer nobody asked for, which outranks all three: **the copy in these designs cannot ship**, for reasons in §6. That is the actual blocker, not the CSS.

---

## 1. Correction to the brief before anything else

I was handed this as a given:

> "Only `redesigned-homepage.html` has this tailwind.config block — the other 10 have NO custom config and use raw Tailwind defaults."

**That is inverted, and the inversion changes the recommendation.** Verified:

```
$ for f in html/*.html; do echo "$(basename $f) cfg=$(grep -c 'tailwind.config' $f)"; done
72-hour-hype-blitzes.html                     cfg=1
about-us-leadership.html                      cfg=1
blog-insights-hub.html                        cfg=1
deal-room-feature-os.html                     cfg=1
festival-box-mumbai-festive-edition-2026.html cfg=1
how-it-works-for-brands.html                  cfg=1
how-it-works-for-creators.html                cfg=1
meera-for-creators-ai-pr-manager.html         cfg=1
redesigned-homepage.html                      cfg=1
transparent-pricing-economics.html            cfg=1
transparent-pricing-free-pro-plans.html       cfg=1
```

All 11 carry a config. The split is **10 vs 1, the other way round**:

- **10 files** (`deal-room-feature-os.html:5-107` is representative) inline the *full* Material 3 designTheme as Tailwind colours — `primary`, `on-surface`, `surface-container-lowest`, `outline-variant`, all 47 — **plus** a semantic type scale (`fontSize.display-hero`, `.headline-lg`, `.body-md`, `.label-strong`, `.badge-micro`, `.data-mono`) at `deal-room-feature-os.html:91-104` and a semantic spacing scale (`spacing.space-2xs … .space-4xl`, `gutter-desktop`, `container-max`) at `:63-76`. Fonts: Plus Jakarta Sans + JetBrains Mono (`:4`).
- **1 file** — `redesigned-homepage.html:9-40` — is the outlier: a bespoke `brand.*` indigo ramp, `slate` overrides, Inter, and no M3 at all.

The markup follows the config. The 10 M3 files use **semantic classes**, not raw palette:

```
deal-room-feature-os.html:107
  <body class="bg-surface font-body-md text-on-surface antialiased">
  ...<a class="... bg-primary-container hover:bg-primary text-on-primary
               font-label-strong text-label-strong px-space-lg py-space-sm rounded-full ...">
```

Top utilities per file confirm it — `primary` (90), `on-surface-variant` (78), `on-surface` (56), `surface-container-lowest` (26) in deal-room; the same shape in the other nine. `redesigned-homepage.html` is the only file whose top utilities are `white` (92), `slate-900` (60), `slate-600` (41), `brand-600` (30).

Raw-palette leakage into the 10 M3 files is **small and concentrated**, not "sprinkled everywhere":

| file | raw-palette utilities |
|---|---|
| meera-for-creators-ai-pr-manager | 46 |
| deal-room-feature-os | 22 |
| 72-hour-hype-blitzes | 16 |
| transparent-pricing-economics | 1 |
| transparent-pricing-free-pro-plans | 1 |
| about-us, blog, festival-box, hiw-brands, hiw-creators | **0** |

86 utilities across 10 files, ~84% of them in three files, and almost all of them `emerald-*` doing one job: the success/paid state.

**Why the correction matters:** the brief's version implies "the HTML throws away the design system, so you're porting hand-rolled utility soup." The truth is the opposite — **10 of 11 pages are already written against a semantic token layer that is structurally the same shape as our shadcn layer**. That makes the port a *token-name mapping*, which is mechanical, instead of a *value-by-value rewrite*, which is not. It also means `redesigned-homepage.html` — the one page Swapnil is most likely to care about — is the **least** portable of the eleven, not the most.

---

## 2. LAYOUT — **YES, partial. As reference, not as source.**

### What is genuinely good

I rendered and read the hero regions of `redesigned-homepage.png` and `deal-room-feature-os.png`. The compositional work is real: an asymmetric hero with a live Deal-Room card as the right-hand proof object; a proof-strip of three claims (`₹0` / `On approval` / `Nano → macro`) that — and this matters — **matches `src/components/site/proof-points.ts:48-57` exactly**, i.e. Stitch was fed our post-F-0342 honest proof points and respected them; a trust bar; a stepped section rhythm. The deal-room page's floating campaign-status card over a photo is a good pattern we do not currently have.

**Adopt the composition. This is the answer to Swapnil's question.**

### What cannot be adopted

Structure, not aesthetics:

1. **Tailwind v3 play-CDN vs our Tailwind v4 CSS-first.** `redesigned-homepage.html:8` loads `cdn.tailwindcss.com?plugins=forms,container-queries`; the M3 files load `cdn.tailwindcss.com` bare. Our config is `@import 'tailwindcss'` + `@theme inline { … }` at `src/app/globals.css:1` and `:191`. A v3 `tailwind.config` JS object has no meaning in our build. The v3→v4 gap here is concrete, not theoretical:
   - `theme.extend.colors` → `@theme { --color-* }` (different file, different syntax).
   - `theme.extend.fontSize` with the `[size, {lineHeight, letterSpacing, fontWeight}]` tuple form used at `deal-room-feature-os.html:91-104` — v4 splits these into `--text-*`, `--text-*--line-height`, `--text-*--letter-spacing`, `--text-*--font-weight`. Every one of the 12 type steps needs rewriting.
   - `theme.extend.spacing` custom names (`space-md`, `gutter-desktop`) → `--spacing-*`.
   - Opacity syntax: v3 `bg-surface/80` (`deal-room-feature-os.html:107`) survives, but v3 config-declared colours must be real CSS colours in v4 for the `/` modifier to work — hex is fine, so this one is a non-issue. Flag it only so nobody "fixes" it.
   - Renamed utilities in the markup (`shadow-sm`→`shadow-xs`, `rounded-sm`→`rounded-xs`, `outline-none`→`outline-hidden`, bare `ring`→`ring-3`). Present in this HTML.
2. **Zero `var(--*)` in any of the 11 files.** Every colour resolves through the v3 config or a literal hex. Nothing themes.
3. **No components.** Flat markup. Our marketing pages are React + shadcn: `SiteHeader.tsx:131`, `SiteFooter.tsx:57`, `FunnelCta.tsx`, `FaqSection.tsx`, `StickyCta`, `src/components/motion/` (`FadeUp.tsx:19`, `StaggerContainer`, `StaggerItem`) — all consumed by `about.tsx:1-12` and every sibling page. Stitch models none of it.
4. **Material Symbols icon font** (`deal-room-feature-os.html:4`, used as `<span class="material-symbols-outlined">bolt</span>`). We are on `lucide-react` (`SiteHeader.tsx:3`). Adopting the font is a new render-blocking Google font request and a second icon system — **rejected**; every icon must be re-picked in lucide.
5. **Emoji as icons.** `redesigned-homepage`'s trust bar ships 💳 ✍️ 📊 🧾 as the icon layer. Visible in the render. Not shippable.
6. **A real layout defect in the source.** In `deal-room-feature-os.png` the floating campaign card overlaps the hero photograph and clips the headline behind it — "…paign" is cut mid-word. Stitch output is not pixel-correct; it is a mood reference.
7. **No responsive proof.** Single desktop render per page. `SiteHeader.tsx:24-28` documents that the mobile nav had to be rebuilt into a Sheet because links vanished below `lg` — a defect class Stitch cannot show us.

### Ruling

**Layout is reusable as a design reference at the section-composition level.** Ananya reads the PNG and the DOM, then builds the section in our components with our tokens. **Nobody imports, pastes, or converts this HTML.** There is no automated v3→v4 conversion path I will sign off on; the conversion cost exceeds the rebuild cost once icons, motion, responsive and a11y are counted.

---

## 3. COLOUR — **NO to the palette. YES to the architecture.**

Three systems, as the brief said. My assessment of each.

### (a) The M3 47-token theme — architecturally good, chromatically wrong

`primary #3525cd`, `background/surface #faf8ff`, `on_background/on_surface #131b2e`, `surface_container #eaedff`, `tertiary #00505f`, `outline #777587`.

Structurally this is a **better-specified** token layer than ours in one respect: it has an explicit `on-*` pair for every container, so foreground/background contrast is guaranteed by construction. Our shadcn layer has that for the big roles (`--primary`/`--primary-foreground`) but not for every surface step.

Chromatically it is a different brand. Measured:

| pair | ratio | verdict |
|---|---|---|
| white on our `--primary #6d5ae6` | **4.93:1** | AA pass |
| white on M3 `primary #3525cd` | **9.14:1** | AAA |
| white on HTML `brand-600 #6366f1` | **4.47:1** | **AA FAIL** |
| white on HTML `brand-700 #4f46e5` | 6.29:1 | AA pass |
| `#131b2e` on `#faf8ff` | 16.29:1 | AAA |
| `emerald-600 #059669` on white | **3.77:1** | **AA FAIL for text** |
| our `--success-foreground #2f7a54` on white | 5.21:1 | AA pass |

**`#6366f1` — the colour the homepage design puts on its primary CTA — fails WCAG AA at 4.47:1.** That is the third time this exact failure mode has come round. `src/app/globals.css:6-13` records the last one verbatim: the "Lilac Mist" palette shipped `~2.9:1`, "failing WCAG AA on every CTA," and the current `#6d5ae6` exists *specifically* as the fix. Adopting `#6366f1` re-opens a closed ticket. **Hard no on `#6366f1` regardless of any other decision on this page.**

`emerald-600` at 3.77:1 is the same problem in the success role, and `emerald` has no counterpart in our palette anyway — our success is the pale-bg/strong-fg pair `--success #ddf5e8` / `--success-foreground #2f7a54` (`globals.css:31-32`). Stitch's `text-emerald-700 bg-emerald-50` usages map cleanly onto that; its `text-emerald-600` usages do not and must be dropped, not translated.

### (b) What Stitch does not model at all

Stitch's 47 tokens cover a marketing site. Our token layer covers a product. The gap, from `globals.css` and verified consumer counts:

| our token family | source | files consuming | Stitch equivalent |
|---|---|---|---|
| `--stage-*` (12 stages × bg/fg/border = 36 tokens) | `globals.css:64-97`, mapped `:236-268` | **50** | **none** |
| `--chart-1..5` | `globals.css:36-40` | 5 | **none** |
| `--sidebar-*` (8) | `globals.css:55-62` | 6 | **none** |
| `--meera-*` (22, incl. LOAD-BEARING trust colours) | `globals.css:104-127` | — | **none** |
| `--hype-*` (4) | `globals.css:99-102` | — | **none** |
| `--success/--warning/--info` + fg | `globals.css:31-36` | — | `emerald`/`error` only, partial |
| `--app-header-h` (CR-17 anti-drift token) | `globals.css:52` | — | **none** |

That is ~75 tokens Stitch has no opinion about, several of them load-bearing. `--meera-escrow #12A150` is commented **"LOAD-BEARING. Never themed per-brand."** (`globals.css:118`). `--app-header-h` exists because a hardcoded height overflowed the deal-room layout by 8px (`globals.css:44-51`).

The reverse gap matters too: **M3's `on_primary`, `primary_container`, `primary_fixed`, `primary_fixed_dim`, `on_primary_fixed`, `on_primary_fixed_variant`, `inverse_primary`, `surface_tint` have no 1:1 shadcn equivalent.** We have `--primary` and `--primary-foreground`. M3 has eight roles in that family. Mapping is lossy in both directions:

| M3 | our nearest | lossless? |
|---|---|---|
| `surface #faf8ff` | `--background` | yes |
| `on_surface #131b2e` | `--foreground` | yes |
| `surface_container_lowest #ffffff` | `--card` | yes |
| `on_surface_variant #464555` | `--muted-foreground` | yes |
| `outline_variant #c7c4d8` | `--border` | yes |
| `primary #3525cd` | `--primary` | value differs |
| `on_primary #ffffff` | `--primary-foreground` | yes |
| `primary_container #4f46e5` | — | **no** — nearest is `--accent`, wrong role |
| `surface_container` / `_low` / `_high` / `_highest` (4 steps) | `--muted` + `--secondary` (2) | **no** — 4→2 |
| `primary_fixed` / `_dim` / `on_*` / `inverse_primary` / `surface_tint` (6) | — | **no** |
| `tertiary #00505f` | — | **no** — teal, no role in our brand |

Roughly **13 of 47 M3 tokens have no home**. The honest description: Stitch's theme is not a superset or a subset of ours. It is a different, overlapping system, and it is missing the half of ours that the product actually runs on.

### (c) Ruling on colour

**Reject the Stitch colour values. Keep our tokens.** Specifically:

- `--primary` stays `#6d5ae6`. See §4.
- `#6366f1` is **banned** on any CTA — WCAG AA failure, previously-closed defect.
- `#3525cd` is *technically* excellent (9.14:1) and is a legitimate brand option, but it is a brand decision, not mine. See §4.
- `emerald-*` → `--success`/`--success-foreground`. `text-emerald-600` dropped outright.
- M3 `tertiary #00505f` (teal) → **not adopted**. We already have a teal-adjacent role: `--hype-solid #06b6d4` (`globals.css:102`). Two teals is one too many.

**What I *do* endorse adopting: the M3 type and spacing scales.** `deal-room-feature-os.html:63-104` gives us a 12-step named type scale with line-height/tracking/weight baked per step, and a named spacing scale. Our marketing pages currently have no named type scale — they hand-pick `text-4xl md:text-6xl` per page, which is exactly how nine pages drift apart. **Porting those two scales into `@theme` as `--text-*` and `--spacing-*` is the single highest-value, lowest-risk thing in this entire package.** It is additive: it breaks nothing, it costs one edit to `globals.css`, and it gives Ananya a vocabulary instead of magic numbers. Font family stays Inter unless Tejas rules otherwise — Plus Jakarta Sans is a brand call, and swapping the body font is a bigger visual change than swapping `--primary`.

---

## 4. Is changing `--primary` mine to sign, or Swapnil's?

**Swapnil's. I will not sign it.** Two independent reasons.

**Reason 1 — it is a brand decision, not a technical one.** `#6d5ae6` is a soft violet; `#3525cd` is a deep saturated indigo. That is a change in what the company looks like. `globals.css:6-13` records that the *last* palette change was made under an explicit CEO directive ("CEO directive: palette read as dull/washed-out"). The precedent is set: the CEO owns this token. My authority covers whether a value is *safe* (contrast, token plumbing, blast radius). It does not cover whether the brand should read as soft or severe.

**Reason 2 — the blast radius is not "the marketing pages".** Measured:

```
files using primary-family utilities (bg/text/border/ring/from/to/via-primary*) : 110
total occurrences of those utilities                                            : 532
routes registered in src/App.tsx                                                :  93
```

93 routes, 110 files. The 11 Stitch designs cover **13 public marketing routes** (`scripts/marketing-routes.mjs` — `INDEXABLE_ROUTES`, 13 entries). So a `--primary` change made "for the website" repaints **every brand dashboard, creator dashboard, deal room, wallet, admin console and Meera surface** in the product, because they all resolve `--primary` from the same `:root`.

Mechanically the swap is trivial — one line in `globals.css:23`, plus `--ring:23`… well, `:35`, `--sidebar-primary:58`, `--sidebar-ring:62`, `--meera-accent:117` and its hover/press/soft/glow siblings, and the `.dark` counterpart at `:139`. Call it ~10 lines. **The risk is not the edit; it is that ~500 usages across 110 files were visually tuned against the old value and nobody has looked at them.** `--meera-accent` in particular is documented as runtime-overridden per brand by `useBrandTheme` (`globals.css:116`), so it interacts with brand theming in ways a static review will not catch.

**My conditions if Swapnil says yes:**
1. The new value must clear 4.5:1 white-on-primary. `#3525cd` clears at 9.14:1. `#6366f1` does not clear and is refused. `#4f46e5` clears at 6.29:1 and is the reasonable middle if `#3525cd` reads too heavy.
2. The change lands in `globals.css` **only** — no component may hardcode the new hex.
3. `--meera-*` trust colours (`--meera-escrow`, `--meera-danger`, `--meera-warning`, `--meera-info`) are **out of scope and unchanged**. They are marked load-bearing.
4. Visual re-review of the 10 highest-traffic app routes before it ships, not just the marketing pages.
5. It ships as its own commit, alone, so it can be reverted without taking a redesign with it.

**My recommendation to Swapnil:** don't. `#6d5ae6` passes AA, was chosen deliberately eight weeks ago in response to a CEO directive, and the redesign's appeal is its *composition and typography*, not its hue. If the pages read as more premium after the rebuild, it will be because of the type scale and the layout — and we will have learned that without spending the blast radius. Revisit `--primary` as a separate decision afterwards, on its own merits.

---

## 5. IMAGES — **NO. Do not ship any of the 31.**

31 unique URLs (13 on `/aida/`, 24 on `/aida-public/`), 0 in `redesigned-homepage.html`, 7 in `blog-insights-hub.html`. Four independent blockers, any one of which is sufficient.

### 5a. Resolution — fails our actual use

1376×768 native, per the brief. Our needs:

- **Hero at 1× on a 1440px viewport**: a full-bleed hero is ~1440 CSS px wide. 1376 is already short. At 2× DPR we need ~2880. **We have 48% of the pixels.** It will read soft on every retina laptop, which is most of our traffic.
- **The floating-card pattern in the deal-room design** crops its photo to roughly a 640×520 CSS box → 1280×1040 at 2×. 1376×768 is wide enough but **272px short vertically**. It would upscale.
- **OG images**: the spec is 1200×630. 1376×768 crops to 1200×630 comfortably. **This is the one use that works.** We currently ship a single `public/og-image.png`; per-page OG art is a genuine gap. But that is a 1200×630 need, not a hero need.
- **Blog cards / avatars / inline figures**: 1376×768 is fine. `blog-insights-hub.html` holds 7 of the 31 and is the strongest technical case.

So on resolution alone: **unusable for heroes, adequate for cards and OG.** There is no retina story for the hero use — `=s0` returns the native asset and there is nothing larger to fetch.

### 5b. Hotlinking — **refused outright**

`https://lh3.googleusercontent.com/aida-public/AB6AXu…` (`blog-insights-hub.html`). These are opaque, unversioned, third-party-controlled URLs on infrastructure we do not own, with no contractual uptime, no cache-control we set, and no guarantee they survive the Stitch project being deleted. Hotlinking them puts the marketing site's above-the-fold imagery under Google's unilateral control and leaks a referrer on every pageview. **If any image is used at all, it is downloaded, optimised, committed under `public/`, and served from our own origin.** Non-negotiable, and it is a security standard, not a preference.

### 5c. Provenance — the real blocker

They are AI-generated photographs of people, presented as real Indian creators and founders. Alt text from `how-it-works-for-creators.html`:

> `alt="Authentic candid photography of a young modern Indian woman content creator in her stylish bright home studio setup holding a tablet with video editing timeline"`

"Authentic candid photography" is in the alt text of a synthetic image. On an influencer-marketing platform whose entire pitch is *verified creators and real deals*, shipping invented humans as our creators is a brand-integrity failure that a competitor or a journalist will find in one reverse-image search.

And one is worse. `about-us-leadership.html`:

> `alt="Swapnil Maruti Shinde - Founder &amp; CEO of Influora"`

**That is an AI-generated portrait captioned as the real, named CEO.** Beyond the integrity problem, it walks straight into a standing directive. `src/pages/about.tsx:16-22` records:

> *"SUPERSEDED IN PART (F-0342, F-0343)… Both are gone — the invented traction block… and the 500+ eyebrow below it. Swapnil directed the removal; nothing on this page now asserts a customer count, a payout total or a creator count."*

We removed invented *numbers* from that page under direct CEO instruction seven weeks ago. Replacing them with an invented *face* is the same failure in a new medium. **Refused.** If we want a founder photo, we take one.

### 5d. Licensing

Rights to Stitch-generated imagery for commercial marketing use are not established in anything I can read here. Unresolved licence + synthetic humans + a named real person = a question for counsel, not for me.

### Ruling on images

**None of the 31 ship.** Commission or licence real photography (Zara + a Mumbai shoot, or a stock licence). The only conditional exception I will entertain: **non-human, non-photographic images** (abstract textures, product-UI mock frames) used as decorative fills on blog cards — self-hosted, alt-texted as decorative, and only after someone confirms no person appears in the frame. That is a handful of the 31 at most, and it is Tejas's call whether it is worth the trouble. My guess is it is not.

---

## 6. The blocker nobody asked about: the copy cannot ship

Assessing "can we use the layout" while ignoring what the layout contains would be a dereliction. The text in these designs violates three standing rulings and makes regulatory claims we cannot substantiate.

**6a. The banned word.** Per the 2026-09-02 ruling, "escrow" was removed from *all* brand- and creator-facing copy, emails, notifications, API messages and the Meera prompt; the sanctioned vocabulary is Secure Payments / secured funds / secure the funds. The Stitch copy uses it **65 times across 9 of the 11 pages** — `how-it-works-for-creators` 20, `how-it-works-for-brands` 13, `redesigned-homepage` 8, four more at 4 each. It is rendered on-screen as a UI badge ("Escrow Active", visible in `redesigned-homepage.png`). Stitch was not told about the ban and could not have been.

**6b. Invented product names.** "Protected Settlement Vault" (3), "Safety Vault" (12), "nodal safety vault" (3), "safety vault" (10) — 28 occurrences of product vocabulary that does not exist in our product or our approved lexicon. **"nodal" is a regulated term** (RBI nodal account). We are a pooled wallet ledger — that is what the 2026-07-27 Razorpay Route audit found. Publishing "nodal safety vault" is a claim about our banking structure.

**6c. Fabricated traction — the exact class F-0342 deleted.** `how-it-works-for-brands.txt:130`:

> "over 14,000 brand collaborations have reached completion without a single legal arbitration filing"

and `transparent-pricing-economics.txt:193`:

> "Trusted by the fastest-scaling consumer brands in India."

F-0342 removed `8,915 creators` and `₹4.26Cr` from the live site under CEO direction. `about.tsx:15` records CEO-DECISIONS #4: *no client logos until written permission exists*. Both lines above are re-additions of the deleted class.

**6d. Regulatory and tax claims we cannot back.** From `deal-room-feature-os.png`, rendered in the hero chrome: *"IT Act 2000 Section 10A Compliant"*, *"RBI Regulated Banking Rails"*, *"Zero 194J TAX NOTICE RISK"*, *"< 3s INSTANT UPI RELEASE"*, *"100% UPFRONT CAPITAL LOCK"*. Per the 2026-07-27 audit, **TDS is unimplemented**. A compliance assertion in a hero is a legal artifact. These require counsel sign-off before they render anywhere, and on today's implementation state at least the 194J one is false.

**Consequence for sequencing:** the copy must be rewritten by Tejas/Nisha against the approved lexicon *before* Ananya builds anything, or we will build nine pages of unshippable text and then rebuild them. The design is a *layout* reference. It is not a content reference.

---

## 7. Effort estimate

Assumes Ananya on the build, our components and tokens, copy supplied clean.

| # | Work | Owner | Est. |
|---|---|---|---|
| 1 | Port M3 type + spacing scales into `@theme` in `globals.css` as `--text-*` / `--spacing-*`; no colour change | Ananya, my review | **0.5 d** |
| 2 | Rewrite all 11 pages' copy against the approved lexicon; strip "escrow"/vault/nodal, delete the 14,000 and "trusted by" claims, route every compliance claim to counsel | Tejas + Nisha | **2–3 d** (blocking) |
| 3 | Legal review of remaining compliance/tax claims | counsel | **external** |
| 4 | Rebuild homepage hero + proof strip + trust bar in our components (lucide icons, our motion, mobile) | Ananya | **1.5 d** |
| 5 | Rebuild `how-it-works/brands` + `how-it-works/creators` | Ananya | **1.5 d** |
| 6 | Rebuild `features/deal-room` + `features/hype` | Ananya | **1.5 d** |
| 7 | Rebuild `pricing` — **two** competing Stitch designs; Swapnil picks one first | Ananya | **1 d** |
| 8 | Rebuild `about` (no founder photo), `meera-for-creators`, `festival-box` | Ananya | **2 d** |
| 9 | `blog` index treatment | Ananya | **0.5 d** |
| 10 | Source/licence/shoot real photography; optimise; self-host under `public/` | Zara + Tejas | **3–5 d, parallel** |
| 11 | Responsive + a11y pass across all 9 rebuilt pages (contrast, focus, reduced-motion, mobile nav) | Kavya | **1 d** |
| 12 | Prerender + sitemap verification (`scripts/marketing-routes.mjs`, `prerender.mjs`) | Meera | **0.5 d** |

**Engineering: ~10 working days.** **Wall-clock: 3 weeks**, gated on copy (2) and photography (10), which run in parallel with each other but block 4–9.

For contrast: a literal HTML port — v3→v4 config conversion, 11 files, then re-componentising, re-iconing and making it responsive anyway — I estimate at **12–15 days and a worse result**, because you inherit the emoji icons, the Material Symbols dependency, the clipping defect and none of our motion. **Rebuilding is cheaper than converting.** That is the technical core of this ruling.

---

## 8. Recommended sequencing

**Do this:**

1. **Now, no approval needed:** land the type + spacing scales into `@theme` (item 1). Additive, reversible, and it is the change that will do the most for how the pages read. I sign this off.
2. **Now, blocking:** Tejas owns a copy rewrite of all 11 pages against the approved lexicon. Nothing gets built until copy is clean. Route the compliance claims to counsel in the same pass.
3. **Now, parallel:** Zara sources real photography. Budget for a shoot.
4. **Swapnil decides two things, not one:**
   - (a) Which pricing design — there are two and they conflict.
   - (b) Whether `--primary` moves off `#6d5ae6`. My recommendation is **no, not now**; if yes, `#3525cd` or `#4f46e5`, never `#6366f1`, under the five conditions in §4.
5. **Then:** Ananya rebuilds page by page, homepage first, using the PNG as reference and our components as source. Each page through Kavya before the next starts.
6. **Never:** import the HTML, hotlink `lh3.googleusercontent.com`, ship a synthetic photo of a named person, or put `#6366f1` on a CTA.

**Answer to Swapnil in his own terms:** *the layout, yes — as a reference we rebuild from, and it is good enough to be worth rebuilding from. The colour code, no — ours already passes accessibility and theirs partly does not, though their typography scale is better than ours and we are taking that. The images, no — they are AI-generated fake people, one of them is a fake photo of you, and they are too low-resolution for a hero anyway. And the writing on those pages says "escrow" 65 times, which we banned five days ago, so the words have to be redone before anyone builds.*

---

## 9. Sources — files I actually opened

Repo:
- `src/app/globals.css` — `:1` (`@import 'tailwindcss'`), `:6-13` (palette-recalibration comment, prior AA failure), `:16-127` (`:root` tokens), `:44-51` (`--app-header-h`/CR-17), `:64-97` (`--stage-*`), `:99-102` (`--hype-*`), `:104-127` (`--meera-*`, `:118` load-bearing), `:139` (`.dark --primary`), `:191` (`@theme inline`), `:200-292` (token→utility map)
- `package.json` — `:101` `tailwindcss ^4.2.0`, `:85` `@tailwindcss/postcss`, `:76` `tailwind-merge`
- `vite.config.ts` — `:1-60` (Vite 6, React plugin, prod build guards)
- `src/pages/about.tsx` — `:1-12` (component imports), `:14-23` (CEO-DECISIONS #4 / F-0342 / F-0343 directive)
- `src/components/site/SiteHeader.tsx` — `:1-40` (lucide + shadcn imports, `:20-28` shared-nav and mobile-Sheet rationale)
- `scripts/marketing-routes.mjs` — `:23-36` `INDEXABLE_ROUTES` (13 public routes), `:45-60` `PRERENDER_ONLY_ROUTES`
- `.proof-os/tasks/T-FRONTEND-REWORK-0905/facts/verified-state.md` — "Marketing pages that exist", pricing-page facts, `PROOF_POINTS` correction
- Line counts: `landing.tsx` 722, `pricing.tsx` 717, `festival-box.tsx` 1007, `about.tsx` 220, `how-it-works-brands.tsx` 165, `how-it-works-creators.tsx` 172, `features/deal-room.tsx` 256, `features/hype.tsx` 281, `meera-for-creators.tsx` 233, `SiteHeader.tsx` 199, `SiteFooter.tsx` 133
- Counts run over `src/`: 110 files / 532 occurrences of primary-family utilities; 93 `<Route>` in `App.tsx`; 50 files consuming `stage-`; 5 consuming `chart-[1-5]`; 6 consuming `sidebar-`

Stitch artifacts (scratchpad):
- `html/deal-room-feature-os.html` — `:4` (Plus Jakarta Sans, JetBrains Mono, Material Symbols), `:5-62` (M3 colour config), `:63-76` (spacing scale), `:77-90` (fontFamily), `:91-104` (fontSize scale), `:107` (`<body class="bg-surface …">`, header markup)
- `html/redesigned-homepage.html` — `:1-60` (head, `:8` play CDN with plugins, `:9-40` `brand`/`slate` config + Inter, `:43-59` custom styles), `:61-80` (body/header)
- `html/blog-insights-hub.html` — image `src` attributes (`aida-public` URLs)
- `html/about-us-leadership.html` — both `<img>` tags incl. `alt="Swapnil Maruti Shinde - Founder & CEO of Influora"`
- `html/how-it-works-for-creators.html` — `alt="Authentic candid photography…"`
- `html/meera-for-creators-ai-pr-manager.html` — `emerald-*` class contexts
- All 11 `html/*.html` — config presence, utility-frequency tallies, raw-palette-leakage counts, `lh3.googleusercontent.com` URL extraction (31 unique)
- All 11 `txt/*.txt` — "escrow" ×65, vault/nodal ×28, numeric-claim scan; `how-it-works-for-brands.txt:130`, `transparent-pricing-economics.txt:193`, `redesigned-homepage.txt:1-60`
- `stitch/*.png` — dimensions of all 11; hero crops of `redesigned-homepage.png` and `deal-room-feature-os.png` read visually

Contrast ratios in §3 computed by me from the hex values above (WCAG 2.x relative-luminance formula).

---

**Ruling stands until Swapnil overrides it. Items 1 and 5 in §8 I sign off. Item 4(b) — `--primary` — is his.**

— Priya, CTO
