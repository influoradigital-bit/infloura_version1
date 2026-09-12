/*
 * Google Tag Manager (GTM-K7LNG26G) — sitewide tag loader.
 *
 * Ruling: Swapnil, 2026-09-12. Tags fire for every visitor on every route, with no consent
 * gate. This is a deliberate reversal of the "rest of influora.in stays tracker-free" stance
 * that docker/nginx.conf.template and public/_headers used to enforce with a path-scoped CSP.
 * That scoping has been deleted in the same ruling — the container's Meta Pixel runs on every
 * route, so the /festival-box/* branch that once existed to contain it granted nothing the
 * default did not. See also the DPDP note at the bottom of this file.
 *
 * WHY THIS IS A FILE AND NOT THE INLINE <script> GOOGLE HANDS YOU
 * The production CSP (docker/nginx.conf.template, `map $uri $influora_csp`) sets
 * `script-src 'self' ...` with NO 'unsafe-inline'. Pasting GTM's inline bootstrap into
 * index.html would have been blocked by the browser on every real deploy while working
 * perfectly on `vite dev` (which sends no CSP at all) — the exact failure that left the
 * Festival Box pixel dead in production for a whole release. An external same-origin file
 * satisfies 'self', so this loads under the existing policy without weakening it to
 * 'unsafe-inline' sitewide on a platform that moves money.
 *
 * The logic below is byte-for-byte the behaviour of Google's official snippet — only the
 * delivery mechanism changed. Microsoft Clarity rides along inside the container; see the
 * note on it below.
 *
 * CONSEQUENCE YOU MUST KNOW ABOUT WHEN BUILDING TAGS IN THE GTM UI
 * Because 'unsafe-inline' is still absent, GTM **Custom HTML** tags will be blocked by CSP.
 * Built-in tag templates (GA4, Clarity, Google Ads, Floodlight, most vendor templates) inject
 * a `<script src>` and work fine, provided that vendor's origin is added to `script-src` in
 * docker/nginx.conf.template. Adding a Custom HTML tag and watching it silently do nothing is
 * not a bug in this file — it is the policy doing its job. If Custom HTML becomes genuinely
 * necessary, the correct fix is a per-request CSP nonce (nginx `ssi` + `sub_filter`), NOT
 * 'unsafe-inline'.
 */
(function () {
  'use strict';

  /** Container from Swapnil, 2026-09-12. */
  var GTM_CONTAINER_ID = 'GTM-K7LNG26G';

  /*
   * Microsoft Clarity is NOT loaded here, on purpose.
   *
   * It is already installed INSIDE the GTM container. Verified live on 2026-09-12 against
   * localhost:3000 with this loader in place: the container injected
   * `https://www.clarity.ms/tag/yh2rsgpkvq?ref=gtm` (note `ref=gtm`) and `window.clarity`
   * was a function on first paint, with no Clarity code in this repo at all.
   *
   * So the install Swapnil asked for already exists — it just arrives via GTM-K7LNG26G rather
   * than via a snippet. Adding a second loader for the SAME project id would load
   * clarity.ms/tag/yh2rsgpkvq twice per page and re-define window.clarity under the running
   * instance, which risks duplicate sessions and double-counted events. One source of truth.
   *
   * IF YOU EVER WANT CLARITY INDEPENDENT OF GTM (e.g. so pausing the container does not also
   * blind session replay), the change is: delete the Clarity tag in the GTM UI first, then add
   * Microsoft's snippet back here with id 'yh2rsgpkvq'. The CSP already permits it —
   * docker/nginx.conf.template carries https://*.clarity.ms in BOTH script-src and connect-src,
   * so no infrastructure change is needed either way. The wildcard is load-bearing, not tidiness:
   * clarity.ms/tag/<id> then pulls scripts.clarity.ms and reports to r.clarity.ms, so listing
   * only www.clarity.ms blocks Clarity in production while dev looks fine.
   * Do NOT do both halves at once.
   */

  /*
   * Do not fire for automation.
   *
   * `scripts/prerender.mjs` loads every marketing route in headless Chrome at build time and
   * snapshots `document.documentElement.outerHTML`. Without this guard each `npm run build`
   * would (a) send ~11 synthetic pageviews and one Clarity session from the build machine into
   * real reporting, and (b) bake whatever GTM injected into the prerendered HTML of every
   * marketing page. The Playwright suite in e2e/ would do the same on every run.
   *
   * `navigator.webdriver` is true in both Puppeteer and Playwright and is false in every real
   * browser, including private windows. This is the whole guard on purpose: a heuristic that
   * tried to sniff user agents would eventually drop real visitors, and silently under-counting
   * is worse than a little build noise.
   */
  if (navigator.webdriver === true) return;

  // ---- Google Tag Manager ---------------------------------------------------------------
  // dataLayer is created before gtm.js loads so anything pushed during startup survives.
  (function (w, d, s, l, i) {
    w[l] = w[l] || [];
    w[l].push({ 'gtm.start': new Date().getTime(), event: 'gtm.js' });
    var f = d.getElementsByTagName(s)[0],
      j = d.createElement(s),
      dl = l != 'dataLayer' ? '&l=' + l : '';
    j.async = true;
    j.src = 'https://www.googletagmanager.com/gtm.js?id=' + i + dl;
    f.parentNode.insertBefore(j, f);
  })(window, document, 'script', 'dataLayer', GTM_CONTAINER_ID);

  /*
   * DPDP ACT 2023 — WHAT IS OUTSTANDING, RECORDED HERE SO IT IS NOT LOST
   *
   * Clarity records session replay: cursor movement, scrolling, clicks and interaction with
   * form fields. On this platform that covers logged-in /brand, /creator and /admin screens,
   * which display campaign budgets, payout amounts, contracts and KYC state. Under the ruling
   * above it runs with no prior consent and no page-level exclusion.
   *
   * Two things a reviewer will ask for, neither of which is done:
   *   1. Clarity's own masking. Clarity masks input VALUES by default, but "Mask sensitive
   *      content" must be confirmed as Strict in the Clarity dashboard, and money/KYC regions
   *      need the `data-clarity-mask="true"` attribute to be masked in the replay image itself.
   *   2. The privacy policy must name Microsoft Clarity and Google as processors and describe
   *      session recording. It currently names neither.
   *
   * The existing consent machinery to build on, if this is revisited, is
   * src/components/site/PageConsentBar.tsx (`usePageConsent`) — it already stores a versioned,
   * timestamped, scoped record, which is what DPDP §6 demonstrability requires.
   */
})();
