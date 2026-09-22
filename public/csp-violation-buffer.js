/*
 * F-1789 -- early buffer for Content-Security-Policy violations.
 *
 * WHY THIS FILE EXISTS: the reporter (src/lib/csp-violation-reporter.ts) is installed from the
 * app bundle, which is a module script at the END of <body> and is evaluated only after its whole
 * import graph loads. /site-tags.js is a defer script EARLIER in the document, so it runs first
 * and injects Google Tag Manager, whose tags can fire before the app bundle has run. A violation
 * raised then (e.g. GTM Custom HTML tag_id 3's inline Meta Pixel script, the one violation known
 * today) would fire with no listener attached and never be reported.
 *
 * This file is loaded as a plain blocking script at the TOP of <head>, before anything else, so a
 * listener exists from the first moment. It only BUFFERS: no network access, no reporting. The
 * app's reporter drains the buffer when it installs and sets the ready flag, after which this
 * listener ignores events.
 *
 * It must be an external file, not an inline <script>: an inline script is exactly what the
 * policy blocks.
 *
 * PRIVACY: copies only the fields the reporter reads. documentURI and referrer are NEVER copied --
 * they carry full URLs with query strings. blockedURI/sourceFile are copied raw here and sanitised
 * by the reporter before anything is sent; this buffer is page memory only and is never sent as-is.
 *
 * ES5 on purpose: it runs before any polyfill or bundle.
 */
(function () {
  'use strict';
  var MAX_BUFFERED = 20;
  var buffer = [];
  window.__INFLUORA_CSP_BUFFER__ = buffer;

  document.addEventListener('securitypolicyviolation', function (e) {
    try {
      if (window.__INFLUORA_CSP_REPORTER_READY__) return;
      if (buffer.length >= MAX_BUFFERED) return;
      buffer.push({
        disposition: e.disposition,
        effectiveDirective: e.effectiveDirective,
        violatedDirective: e.violatedDirective,
        blockedURI: e.blockedURI,
        sourceFile: e.sourceFile,
        lineNumber: e.lineNumber,
        columnNumber: e.columnNumber,
        statusCode: e.statusCode,
        sample: e.sample
      });
    } catch (err) {
      /* a buffer must never throw */
    }
  });
})();
