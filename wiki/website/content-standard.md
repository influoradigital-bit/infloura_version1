# Influora content standard — SEO, AEO, GEO and blog craft

Owner: tejas (CMO). Writers: nisha, ishaan. Last updated 2026-08-20.

This is the rulebook for every page and post on influora.in. It is grounded in what this repo
actually does — the frontmatter parser, the schema helpers, the prerender step and the redirect
gate — not in general SEO advice.

---

## 0 · The four objectives, kept distinct

They are not synonyms and they need different things from the same article.

| | Goal | Who consumes it | What wins |
|---|---|---|---|
| **SEO** | Rank in Google's blue links | Crawler + ranking system | Crawlability, intent match, internal links, depth |
| **AEO** | Be the extracted answer | AI Overviews, People Also Ask, featured snippets | A short, direct, self-contained answer near the top |
| **GEO** | Be cited by ChatGPT, Claude, Perplexity, Gemini | LLM retrieval + generation | Quotable factual sentences, clear attribution, structure |
| **Blog craft** | Be worth reading | A human | Specificity, real numbers, no filler |

Writing only for SEO produces long articles nobody quotes. Writing only for AEO produces thin
answer boxes that never rank. Every post does all four.

---

## 1 · What the codebase already gives you (do not rebuild)

- **Frontmatter contract** — `src/lib/blog/posts.ts`, `BlogFrontmatter`
- **Categories** — `brands` · `creators` · `industry` · `updates` (`BLOG_CATEGORIES`)
- **Prerendering** — `scripts/prerender.mjs` via puppeteer in `postbuild`, so posts are crawlable
- **Sitemap** — `scripts/generate-sitemap.mjs`, also `postbuild`
- **Redirect safety** — `scripts/marketing-routes.mjs` (`PERMANENT_REDIRECTS`) enforced by
  `.proof-os/gates/F-SEO-redirects-resolve.sh`, which FAILS THE BUILD on a missing rule
- **Structured data** — `src/lib/seo/schema.ts` ships nine helpers:
  `Organization · Website · Article · FaqPage · BreadcrumbList · SoftwareApplication · HowTo ·
  QaPage · WebPage`
- **AI crawler brief** — `public/llms.txt` (already written, already positions the product)
- `public/robots.txt`

### Two known gaps (2026-08-20)

1. **`FaqPage` schema is built but never emitted on blog posts.** `src/pages/blog/post.tsx`
   emits `Article` + `BreadcrumbList` only. FAQ schema is the single highest-value AEO addition
   available and the helper already exists. **Engineering task, not a writing task.**
2. **`wiki/website/content-map.md` is cited by `src/lib/blog/posts.ts:29` and does not exist.**
   The category taxonomy currently lives only in code. Either write the map or fix the citation.

---

## 2 · Frontmatter — hard rules

The parser is deliberately minimal: it runs `JSON.parse` on the value half of each `key: value`
line. That means **double quotes only**, no single quotes, no unquoted scalars, no YAML anchors,
no multi-line strings. A malformed value throws at load and the post disappears.

```markdown
---
title: "How Much Do Micro Influencers Charge in India? (2026)"
slug: "micro-influencer-pricing-guide-india-2026"
excerpt: "One or two sentences. This is the meta description and the card blurb."
category: "creators"
author: "Influora Team"
publishedAt: "2026-07-13"
updatedAt: "2026-07-13"
readingMinutes: 8
keywords: ["primary keyword", "secondary", "long tail variant"]
featuredImageAlt: "Describe the image for a reader who cannot see it"
---
```

Rules:

- **`slug` MUST equal the filename** (without `.md`). Nothing checks this yet — get it wrong and
  the URL and the canonical disagree.
- **Never change `slug` after publish.** If a URL must change, add a 301 to `PERMANENT_REDIRECTS`
  in `scripts/marketing-routes.mjs` — the gate will fail the build if you forget.
- **Never change `publishedAt` on an existing post.** Update `updatedAt` instead. Moving a publish
  date forward reads as new content and can cost the ranking the post already has.
- `readingMinutes` is a bare number, not a string.
- `excerpt` is the meta description. Write it for a human deciding whether to click, 140–160 chars.

---

## 3 · Post structure — the house template

```markdown
# H1 — matches the title, contains the primary keyword naturally

## Quick Answer
2–4 sentences. Self-contained. Answers the title question completely
even if the reader stops here.

## [Context / definitions]
## [The substance — H2 per sub-question]
### [H3 for detail]

## Frequently Asked Questions
### Question phrased exactly as a person would type it?
Direct answer in the first sentence, then detail.

## [Closing — what to do next]
```

### The Quick Answer block is not optional

It is the AEO surface. Rules:

- Answer in the **first sentence**, before any qualification
- **Self-contained** — no "as discussed above", no pronouns pointing outside the block
- Include the specific number, range or list if there is one
- Bold the key figure so extraction picks it out

Bad: *Pricing depends on many factors, which we explore below.*
Good: *Micro influencers in India generally charge **₹5,000 to ₹50,000 per Instagram post**,
depending on niche, engagement rate and usage rights.*

---

## 4 · SEO rules

- **One primary keyword per post.** If two posts target the same term, one must be merged or
  redirected — they cannibalise each other.
- **H2s are sub-questions**, not labels. "How brands calculate creator rates" beats "Rates".
- **Internal links: 3–5 per post**, to genuinely related posts, with descriptive anchor text.
  Never "click here".
- **Tables for anything comparative.** They rank, they get extracted, and they are easier to read.
- **India-specific detail is the differentiator.** ₹ amounts, TDS, GST, city and language nuance.
  Generic global advice is already written by a thousand sites with more authority than us.
- **Local long-tail is the cheapest win we have.** City + niche + intent
  ("food influencers in Pune for restaurants") has low competition and high intent.
- Length follows the question, not a word count. If it is answered in 900 words, stop at 900.

---

## 5 · AEO rules — being the extracted answer

- **Quick Answer at the top**, per section 3
- **A real FAQ section** at the bottom, 3–6 questions, each phrased the way someone types it
- **One question per H3**, answered directly in the sentence immediately after
- **Do not bury the answer** behind a preamble; answer engines extract the first coherent
  response, not the best one
- Once gap (1) above is fixed, the FAQ section automatically becomes `FaqPage` structured data —
  which is why the FAQ formatting rule matters even before the schema ships

---

## 6 · GEO rules — being cited by AI

Answer engines quote sentences. Write sentences worth quoting.

- **One fact per sentence**, stated plainly, with the number in it. A sentence carrying three
  hedged claims is unquotable.
- **Attribute anything external** — name the source in the sentence, not just a link. A model
  reproducing your sentence carries the attribution with it.
- **Never invent a statistic.** If we do not have the number, say what we do have. A fabricated
  figure that gets cited is a permanent liability, and it has already happened here once — a deck
  shipped with model-invented price anchors that read as sourced.
- **Say what we are and are not.** `public/llms.txt` already does this
  ("a platform, not an agency", "does not sell followers or engagement"). Posts should be
  consistent with it — contradictions confuse retrieval.
- **Define terms on first use.** LLMs retrieve passages out of context; a passage that defines
  its own terms survives the trip.
- **Update `public/llms.txt`** when a post establishes a durable fact worth surfacing.

---

## 7 · Where the product may appear

One mention, near the end, in a section of its own. State what it does factually and move on.
Never open with it, never repeat it, never claim a result we cannot evidence.

Anything phrased as a guarantee must match what the product can actually pay out — see the
guarantee ladder in `.proof-os/tasks/T-ROADMAP-0820/TASKS.md` §5. "Payment held in escrow until
you approve" is true today. "Guaranteed conversions" is not.

---

## 8 · Pre-publish checklist

- [ ] `slug` equals the filename, and is new (or the redirect is in `PERMANENT_REDIRECTS`)
- [ ] Frontmatter parses — all values double-quoted, `readingMinutes` a bare number
- [ ] `category` is one of the four in `BLOG_CATEGORIES`
- [ ] Quick Answer is self-contained and leads with the answer
- [ ] FAQ section present, questions phrased as typed
- [ ] 3–5 internal links with descriptive anchors
- [ ] Every number is real and sourced, or removed
- [ ] India-specific detail present (₹ / TDS / GST / city / language)
- [ ] `excerpt` reads as a meta description, 140–160 chars
- [ ] Product mentioned at most once, factually
- [ ] `npm run build` passes — this runs prerender, sitemap and the redirect gate

---

## 9 · NOT CHECKED

Whether prerendering actually covers a newly added blog slug — `discoverBlogRoutes()` was read but
not executed. Whether `slug`/filename agreement is enforced anywhere (it is not, as far as this
review found). Whether the existing six posts comply with this standard — it was written from
their patterns, not audited against them. Ranking claims here are conventional practice, not
measured on this domain.
