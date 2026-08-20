# T-DOMAIN-0820 — Consolidate onto influora.in, retire WordPress and influora.io

Opened 2026-08-20. Decision by Swapnil: **influora.in is the single domain — marketing site AND
platform. WordPress is retired. influora.io is abandoned, not renewed.**

## done_when

`.proof-os/gates/F-SEO-redirects-resolve.sh` exits 0 with every retired WordPress URL present in
`PERMANENT_REDIRECTS`, AND every URL in section 2 below returns 200 or 301 (never 404) against
the new deployment.

---

## 1 · Why this is safe

The repo already carries the machinery this migration needs:

- `scripts/marketing-routes.mjs` — canonical `PERMANENT_REDIRECTS` + `PRERENDER_ROUTES`
- `public/_redirects` — the emitted rules, SPA wildcard last
- `.proof-os/gates/F-SEO-redirects-resolve.sh` — **fails the build** if a rule in the canonical
  list is missing from `_redirects`
- `scripts/prerender.mjs` (puppeteer) + `scripts/generate-sitemap.mjs` in `postbuild` — marketing
  pages are prerendered, so this is not a naked client-rendered SPA and is crawlable

So the risk is not the architecture. The risk is **losing URLs nobody wrote down.** Section 2 is
that list, captured 2026-08-20 from the live Yoast sitemap while WordPress was still up.

---

## 2 · The WordPress URL inventory (captured before deletion — do not lose this)

### Pages (19)
```
/                              /about/                      /checkout/
/shop/                         /cart/                       /my-account/
/home/                         /brand/                      /brand-html/
/influencer-html/              /home-2/                     /home-3/
/influencer-link-power-2-2/    /influencer-link-power/      /contact/
/home-4/                       /brand-2/                    /creators/
/privacy-policy/
```

### Posts (12)
```
/hello-world/
/how-you-should-collaborate-influencer-in-pune/
/a-beginners-guide-to-local-influencer-marketing-in-pune/
/top-niches-to-target-when-working-with-pune-based-influencers/
/beyond-follower-counts-the-ultimate-guide-to-vetting-micro-influencers-in-india-for-maximum-roi/
/instagrams-creator-marketplace-gets-an-upgrade-but-is-it-enough-why-influora-still-reigns-supreme-for-savvy-marketers/
/how-food-influencers-in-pune-help-local-restaurants-grow/
/influencer-marketing-in-pune-a-guide-for-local-businesses/
/the-vernacular-revolution-why-your-brand-is-losing-money-by-ignoring-small-cities-creators/
/ai-in-influencer-marketing-is-here-are-you-using-it-for-more-than-just-discovery/
/5-clauses-you-must-have-in-your-next-brand-collaboration-agreement/
/the-ultimate-kolkata-biryani-challenge-ratings-reactions-a-micro-creators-journey/
```

Also present: `/category-sitemap.xml`, `/post_tag-sitemap.xml`, `/author-sitemap.xml` — enumerate
these too before deletion if any category/tag page has backlinks.

---

## 3 · Triage

**MIGRATE the content, don't just redirect.** Redirecting a ranked article to a hub page throws
away the ranking. These 11 posts (all except `/hello-world/`) are real assets and several are
Pune-local long-tail, which matches the micro-creator positioning:

- 5 Pune / local-influencer posts — genuine local SEO, hard to rebuild
- vetting micro-influencers for ROI — on-message for the outcome/Score story
- Instagram Creator Marketplace commentary — directly relevant to T-IGDISCOVERY-0820
- collaboration-agreement clauses — supports the usage-rights work
- vernacular / small-city creators — on-message

Copy each into `src/content/blog/` at the SAME slug so the URL never changes and no redirect is
needed. That is the cheapest possible migration for the highest-value pages.

**REDIRECT (301) — real pages whose URL changes**
| Old WordPress URL | New route |
|---|---|
| `/brand-2/` | `/how-it-works-brands` |
| `/brand/` | `/how-it-works-brands` |
| `/creators/` | `/how-it-works-creators` |
| `/about/` | `/about` |
| `/contact/` | `/contact` |
| `/privacy-policy/` | `/privacy` |
| `/influencer-link-power/` | pick the closest feature page |
| `/influencer-link-power-2-2/` | same as above |
| `/home/` | `/` |

**410 or redirect-to-root — theme demos and WooCommerce leftovers with no value**
`/home-2/` `/home-3/` `/home-4/` `/brand-html/` `/influencer-html/` `/shop/` `/cart/`
`/checkout/` `/my-account/` `/hello-world/`

These carry no ranking worth keeping. Redirect to `/` rather than 404 so any stray backlink still
lands somewhere.

---

## 4 · Cutover steps

1. **Stop the bleeding first (F-0370).** WordPress Log in / Sign up currently point at
   `https://app.influora.io/...` which does not resolve. Repoint or remove before anything else.
2. Migrate the 11 blog posts into `src/content/blog/` at identical slugs.
3. Add every row from section 3 to `PERMANENT_REDIRECTS` in `scripts/marketing-routes.mjs`.
4. `npm run build` — `F-SEO-redirects-resolve.sh` must exit 0. It fails the build if a canonical
   rule is missing from `public/_redirects`, which is the gate for this whole task.
5. DNS: `influora.in` -> the app deployment; `api.influora.in` -> Spring API. No `app.` subdomain
   is needed under this decision.
6. Config: `VITE_API_BASE_URL=https://api.influora.in/api/v1`; `META_REDIRECT_URI=https://influora.in/creator/settings/meta/callback`.
   Fix **F-0369** in the same pass — the WooCommerce setup screen hardcodes
   `https://api.influora.com/webhooks/woocommerce` instead of deriving it from the API base.
7. Meta app dashboard: Privacy `https://influora.in/privacy`, ToS `https://influora.in/terms`
   (currently the placeholder `https://www.facebook.com/`), App Domains `influora.in`, OAuth
   redirect as above, Data Deletion callback on `api.influora.in` once F-0356 is built.
8. Google Search Console: submit the new sitemap, keep the old property until the redirects are
   crawled. Do NOT delete the WordPress install until GSC shows the 301s picked up.
9. Let `influora.io` lapse. Nothing may reference it after step 1.

---

## 5 · Related ledger

- **F-0370** — live CTAs on influora.in point at the dead `app.influora.io`. Losing signups now.
- **F-0369** — hardcoded `api.influora.com` webhook URL shown to brands.
- **F-0366** — Meta app registered against the dead `app.influora.io` privacy URL; likely cause of
  the Jan 2026 App Review rejection. Closed by step 7.

## 6 · NOT CHECKED

Whether `/category-`, `/post_tag-` and `/author-` sitemaps contain URLs with real backlinks — only
the page and post sitemaps were enumerated. Whether the 11 blog posts' content can be exported
cleanly from WordPress (no export was attempted). Whether prerendering covers a blog route added
at a new slug — `discoverBlogRoutes()` was read but not run. Whether influora.in's current
rankings survive: redirects preserve signal, they do not guarantee position.
