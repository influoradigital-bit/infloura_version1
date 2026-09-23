#!/usr/bin/env node
// F-1787 local proof server. Zero dependencies.
//
//   node deploy/utho/nginx/test-server.mjs <snippet.conf> [bundle-dir] [--print]
//
// Serves a downloaded copy of the LIVE SPA bundle (default:
// .proof-os/tasks/T-SECHDR-0922/live-bundle) on http://localhost:3099 with index.html
// fallback, and sets response headers by PARSING the nginx snippet given as argument 1.
// The policy is never retyped here: a retyped policy only proves a policy someone invented
// works, not the file that will be installed on the server.
//
// Nothing is proxied. /api calls from the page go to https://influora.in (hard-coded in the
// bundle) or fail; CSP is evaluated on the request URL either way, so violations still show.
//
// --print  parse the snippet, print the headers it would send, exit (no server).

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT = 3099;
const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..', '..');

const args = process.argv.slice(2);
const printOnly = args.includes('--print');
const positional = args.filter((a) => a !== '--print');
const snippetPath = positional[0];
const bundleDir = path.resolve(
  positional[1] || path.join(repoRoot, '.proof-os', 'tasks', 'T-SECHDR-0922', 'live-bundle'),
);

if (!snippetPath) {
  console.error('usage: node test-server.mjs <snippet.conf> [bundle-dir] [--print]');
  process.exit(2);
}

// ---- nginx snippet parser -------------------------------------------------------------
// Accepts exactly: add_header <Name> <"value" | 'value'> always;
// Any other non-comment, non-blank line is an error, so a typo in the real file fails here
// instead of being quietly skipped.
function readNginxString(s, i) {
  const q = s[i];
  if (q !== '"' && q !== "'") throw new Error(`expected quoted value at column ${i + 1}`);
  let out = '';
  for (let j = i + 1; j < s.length; j++) {
    const c = s[j];
    if (c === '\\' && j + 1 < s.length) {
      // nginx keeps unknown escapes literally; \" \' \\ collapse to the char.
      const n = s[j + 1];
      if (n === q || n === '\\') { out += n; j++; continue; }
      out += c;
      continue;
    }
    if (c === q) return { value: out, end: j + 1 };
    out += c;
  }
  throw new Error('unterminated quoted value');
}

function parseSnippet(text) {
  const headers = [];
  const lines = text.split(/\r?\n/);
  lines.forEach((raw, idx) => {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) return;
    const where = `${snippetPath}:${idx + 1}`;
    const m = /^add_header\s+([A-Za-z0-9-]+)\s+/.exec(line);
    if (!m) throw new Error(`${where}: not an add_header line: ${line.slice(0, 80)}`);
    const { value, end } = readNginxString(line, m[0].length);
    const rest = line.slice(end).trim();
    if (rest !== 'always;') {
      throw new Error(`${where}: ${m[1]} must end with 'always;' (got '${rest}')`);
    }
    if (value.trim() === '') throw new Error(`${where}: ${m[1]} has an empty value`);
    if (/[\r\n]/.test(value)) throw new Error(`${where}: ${m[1]} value contains a newline`);
    headers.push([m[1], value]);
  });
  const names = headers.map(([n]) => n.toLowerCase());
  const dup = names.find((n, i) => names.indexOf(n) !== i);
  if (dup) throw new Error(`duplicate header in snippet: ${dup}`);
  if (headers.length === 0) throw new Error('snippet contains no add_header lines');
  return headers;
}

let headers;
try {
  headers = parseSnippet(fs.readFileSync(snippetPath, 'utf8'));
} catch (err) {
  console.error(`SNIPPET PARSE FAILED: ${err.message}`);
  process.exit(1);
}

if (printOnly) {
  for (const [n, v] of headers) console.log(`${n}: ${v}`);
  process.exit(0);
}

// ---- static SPA server --------------------------------------------------------------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.mp4': 'video/mp4',
  '.mp3': 'audio/mpeg',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml',
};

if (!fs.existsSync(path.join(bundleDir, 'index.html'))) {
  console.error(`no index.html in bundle dir: ${bundleDir}`);
  process.exit(1);
}

function send(res, status, file) {
  for (const [n, v] of headers) res.setHeader(n, v);
  res.setHeader('Content-Type', MIME[path.extname(file).toLowerCase()] || 'application/octet-stream');
  res.writeHead(status);
  fs.createReadStream(file).pipe(res);
}

const server = http.createServer((req, res) => {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  } catch {
    pathname = '/';
  }
  const candidate = path.resolve(bundleDir, '.' + pathname);
  const inside = candidate === bundleDir || candidate.startsWith(bundleDir + path.sep);
  let file = null;
  if (inside && fs.existsSync(candidate)) {
    const st = fs.statSync(candidate);
    if (st.isFile()) file = candidate;
    else if (st.isDirectory() && fs.existsSync(path.join(candidate, 'index.html'))) {
      file = path.join(candidate, 'index.html');
    }
  }
  // SPA fallback, like the live vhost's try_files ... /index.html (a missing /assets/ file
  // gets index.html too, which is what live does today).
  if (!file) file = path.join(bundleDir, 'index.html');
  console.log(`${req.method} ${req.url} -> ${path.relative(bundleDir, file)}`);
  if (req.method === 'HEAD') {
    for (const [n, v] of headers) res.setHeader(n, v);
    res.writeHead(200);
    res.end();
    return;
  }
  send(res, 200, file);
});

server.listen(PORT, () => {
  console.log(`serving ${bundleDir}`);
  console.log(`headers from ${path.resolve(snippetPath)}:`);
  for (const [n] of headers) console.log(`  ${n}`);
  console.log(`http://localhost:${PORT}/`);
});
