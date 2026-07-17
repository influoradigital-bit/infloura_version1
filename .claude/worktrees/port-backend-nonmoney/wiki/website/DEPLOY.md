# Influora.in — Deployment Handoff

> For whoever manages `influora.in` hosting. This is a **static single-page app** (Vite + React). Deploying = uploading a folder of static files + one routing rule. No Node server, no database needed for the website itself.

---

## 1. What to deploy

Build output folder: **`dist/`** (in the repo root, ~3.3 MB).

Regenerate it any time with:
```bash
npm install        # first time only
npm run build      # if the typecheck gate blocks (see §5), use: npx vite build
```
Then upload the **contents of `dist/`** to the web root that serves `https://influora.in`.

`dist/` already includes `index.html`, hashed JS/CSS in `assets/`, and the SEO files `robots.txt`, `llms.txt`, `sitemap.xml`, plus icons.

---

## 2. ⚠️ CRITICAL — SPA fallback (do this or deep links 404)

This app uses **client-side routing**. The server only has one real HTML file (`index.html`). Every route (`/blog/what-is-escrow-in-influencer-marketing`, `/features/escrow`, `/pricing`, …) must be served `index.html` and let the browser render the route. Without this rule, the homepage works but **every deep link / refresh returns 404**.

Configure the rule for whichever server hosts influora.in:

**Nginx**
```nginx
location / {
  try_files $uri $uri/ /index.html;
}
```

**Apache** (`.htaccess` in web root)
```apache
<IfModule mod_rewrite.c>
  RewriteEngine On
  RewriteBase /
  RewriteRule ^index\.html$ - [L]
  RewriteCond %{REQUEST_FILENAME} !-f
  RewriteCond %{REQUEST_FILENAME} !-d
  RewriteRule . /index.html [L]
</IfModule>
```

**Netlify** — add file `dist/_redirects`:
```
/*    /index.html   200
```

**Cloudflare Pages** — add file `dist/_redirects` (same as Netlify), or it auto-detects SPA. (A `_headers` file is already in `dist/`.)

**Vercel** — add `vercel.json` at repo root:
```json
{ "rewrites": [{ "source": "/(.*)", "destination": "/index.html" }] }
```

**Generic static host / S3+CloudFront** — set the "404 / error document" AND "index document" both to `index.html`.

> Exception: do NOT rewrite `robots.txt`, `sitemap.xml`, `llms.txt`, or files in `/assets/` — the `try_files $uri` / `!-f` conditions above already serve real files first, so those are fine.

---

## 3. Domain & HTTPS

- Point `influora.in` (and `www.influora.in`) at the host; redirect `www` → apex (or vice-versa) — pick one canonical host.
- **HTTPS required** (the canonical URLs and OG tags all use `https://influora.in`).
- Confirm the canonical host matches `https://influora.in/` (that's what the `<link rel=canonical>` and `sitemap.xml` use).

---

## 4. Caching (recommended)

- `/assets/*` (hashed filenames) → cache long, immutable: `Cache-Control: public, max-age=31536000, immutable`
- `index.html`, `robots.txt`, `sitemap.xml`, `llms.txt` → short/no cache so updates show: `Cache-Control: no-cache` (or max-age=300)

---

## 5. 🚦 Pre-deploy gate (do NOT skip)

1. **Fix `src/pages/creator-profile.tsx`** first — it's currently broken by unrelated concurrent work (missing `mockProfile` definition, dangling references). It doesn't affect the marketing pages, but it ships in the same bundle and would crash the authenticated creator-profile page. Finish that edit or revert the file, then rebuild. (Tracked as a separate task.)
2. **Rebuild** after that fix: `npx vite build`.
3. The full `npm run build` typecheck also trips on ~11 pre-existing `.test.tsx` files that reference `vitest` (not a declared dependency). That's a separate known cleanup — `npx vite build` produces a correct bundle regardless.

---

## 6. Post-deploy verification checklist

After the files are live on `https://influora.in`, confirm:

- [ ] `https://influora.in/` — homepage loads, hero stats show real numbers (not 0)
- [ ] `https://influora.in/blog/what-is-escrow-in-influencer-marketing` — **deep link loads on refresh** (proves the SPA fallback works)
- [ ] `https://influora.in/features/escrow` and `/pricing` load directly
- [ ] `https://influora.in/robots.txt` — serves, lists AI crawlers + sitemap
- [ ] `https://influora.in/sitemap.xml` — serves
- [ ] `https://influora.in/llms.txt` — serves
- [ ] View source of a blog post → `<script type="application/ld+json">` Article schema present
- [ ] `https://influora.in/grievance` — loads and has `<meta name="robots" content="noindex, nofollow">` (legal pages must stay noindex until counsel signs off)
- [ ] Footer shows CIN + GSTIN + info@influora.in + phone
- [ ] Submit `sitemap.xml` in Google Search Console + Bing Webmaster Tools

---

## 7. Still pending before legal pages flip to indexable

The 8 legal pages ship **`noindex`** intentionally. Before making them indexable:
- Provide **registered office address** + **Grievance Officer name** (currently placeholders).
- Get **Indian legal counsel + a CA** to validate the money/KYC/TDS/DPDP policies.
- Then remove `noindex` on the approved pages and add them to `sitemap.xml`.

The public marketing site (home, blog, features, how-it-works, pricing, about, contact) has **no such gate** — it's ready to index now.
