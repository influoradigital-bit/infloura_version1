#!/usr/bin/env node
// F-1787 deploy gate: does the CSP snippet still cover every inline script in the build?
//
//   node deploy/utho/nginx/csp-inline-hashes.mjs <snippet.conf> <dist-dir-or-html-file>
//
// Run it on EVERY frontend deploy, against the exact dist/ that will be copied to
// /var/www/influora (after `npm run build`, which also runs prerender.mjs and writes one
// HTML file per marketing route). It:
//   1. finds every executable inline <script> (no src, type absent / module / a JS MIME type)
//      in every .html file and prints its 'sha256-...' source expression;
//   2. parses script-src (or default-src) out of the snippet's CSP line and FAILS (exit 1) if
//      any printed hash is missing from it;
//   3. FAILS on inline event-handler attributes (onload=, onclick=, ...) and javascript: URLs,
//      which no hash in script-src can allow.
// JSON-LD (<script type="application/ld+json">) is data, is never executed, and is skipped.
// Exit 0 with "0 executable inline scripts" is the expected result for the 2026-09-22 build.
//
// To fix a failure: add each missing 'sha256-...' to script-src in BOTH snippet files
// (influora-security-headers.conf and .enforce.conf), re-run until it exits 0, install the
// snippet on the server, and only then deploy the frontend.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const [snippetPath, target] = process.argv.slice(2);
if (!snippetPath || !target) {
  console.error('usage: node csp-inline-hashes.mjs <snippet.conf> <dist-dir-or-html-file>');
  process.exit(2);
}

const snippet = fs.readFileSync(snippetPath, 'utf8');
const cspLine = snippet
  .split(/\r?\n/)
  .map((l) => l.trim())
  .find((l) => /^add_header\s+Content-Security-Policy(-Report-Only)?\s+"/.test(l));
if (!cspLine) {
  console.error(`no Content-Security-Policy add_header line in ${snippetPath}`);
  process.exit(1);
}
const policy = cspLine.slice(cspLine.indexOf('"') + 1, cspLine.lastIndexOf('"'));
const directives = new Map(
  policy
    .split(';')
    .map((d) => d.trim())
    .filter(Boolean)
    .map((d) => {
      const [name, ...vals] = d.split(/\s+/);
      return [name.toLowerCase(), vals];
    }),
);
const scriptSrc = directives.get('script-src-elem') || directives.get('script-src') || directives.get('default-src') || [];
const allowsUnsafeInline = scriptSrc.includes("'unsafe-inline'");

function htmlFiles(p) {
  const st = fs.statSync(p);
  if (st.isFile()) return p.toLowerCase().endsWith('.html') ? [p] : [];
  return fs.readdirSync(p).flatMap((n) => htmlFiles(path.join(p, n)));
}

const EXEC_TYPES = new Set(['', 'module', 'text/javascript', 'application/javascript', 'application/ecmascript', 'text/ecmascript']);
const SCRIPT_RE = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
const HANDLER_RE = /<[a-z][^>]*\s(on[a-z]+)\s*=/gi;
const JSURL_RE = /\b(?:href|src|action|formaction)\s*=\s*["']?\s*javascript:/gi;

let failures = 0;
let inlineCount = 0;
const files = htmlFiles(path.resolve(target));
if (files.length === 0) {
  console.error(`no .html files under ${target}`);
  process.exit(1);
}

for (const file of files) {
  const html = fs.readFileSync(file, 'utf8');
  const rel = path.relative(process.cwd(), file);
  for (const m of html.matchAll(SCRIPT_RE)) {
    const attrs = m[1];
    if (/\bsrc\s*=/i.test(attrs)) continue;
    const typeMatch = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs);
    const type = typeMatch ? typeMatch[1].toLowerCase() : '';
    if (!EXEC_TYPES.has(type)) continue;
    inlineCount++;
    // CSP hashes the script's text AS THE BROWSER SEES IT: the HTML parser normalises CRLF and
    // lone CR to LF before the element exists, so hashing raw file bytes gives a wrong hash for
    // any file with CRLF endings (the live index.html has mixed CRLF/CR/LF). Whitespace is kept.
    const text = m[2].replace(/\r\n?/g, '\n');
    const hash = `'sha256-${crypto.createHash('sha256').update(text, 'utf8').digest('base64')}'`;
    const covered = allowsUnsafeInline || scriptSrc.includes(hash);
    if (!covered) failures++;
    console.log(`${covered ? 'ok     ' : 'MISSING'} ${hash}  ${rel}  (${text.length} chars: ${text.trim().slice(0, 50).replace(/\s+/g, ' ')})`);
  }
  for (const m of html.matchAll(HANDLER_RE)) {
    failures++;
    console.log(`BLOCKED inline handler ${m[1]}= in ${rel} - move it to an external script`);
  }
  for (const _ of html.matchAll(JSURL_RE)) {
    failures++;
    console.log(`BLOCKED javascript: URL in ${rel}`);
  }
}

console.log(`${files.length} html file(s), ${inlineCount} executable inline script(s), ${failures} problem(s)`);
process.exit(failures === 0 ? 0 : 1);
