/*
 * gates/_dispute_wire.js — origin: F-0329 (token-proxy gates), repairing F-0242.
 *
 * F-0242 was a CAPABILITY gap: brand-disputes.tsx told the brand to open a dispute "in the
 * relevant deal room" while no control existed there. The old gate proved that with
 * `grep -q "brandDisputes.open"` over the deal room — a token check that a single dead
 * statement, or a call sitting in a function nothing renders, satisfies forever.
 *
 * This asks the structural question instead: is the call to `api.brandDisputes.open` inside a
 * function that some rendered onClick actually reaches? And is the CREATOR variant absent from
 * the brand surface (wrong JWT slot)?
 *
 * Still static — it proves a wire exists in the source, not that a user can click it. See the
 * gate's NOT CHECKED.
 *
 * Usage: node _dispute_wire.js <file.tsx> <path/to/node_modules/typescript>
 * Exit 0 = wired · 1 = not wired / wired to the creator variant · 2 = could not analyse.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const FILE = process.argv[2];
const TSDIR = process.argv[3] || 'node_modules/typescript';
if (!FILE) { console.log('usage: _dispute_wire.js <file> <tsdir>'); process.exit(2); }

let ts;
try { ts = require(path.resolve(TSDIR)); }
catch (e) { console.log('cannot load the typescript compiler from ' + TSDIR + ': ' + e.message); process.exit(2); }

let src;
try { src = fs.readFileSync(FILE, 'utf8'); }
catch (e) { console.log('cannot read ' + FILE + ': ' + e.message); process.exit(2); }

const sf = ts.createSourceFile(FILE, src, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
if (!sf || !sf.statements) { console.log('typescript produced no AST for ' + FILE); process.exit(2); }

const findings = [];
const at = (n) => sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1;

const calls = [];
const jsxAttrs = [];
(function walk(n) {
  if (ts.isCallExpression(n)) calls.push(n);
  if (ts.isJsxAttribute(n)) jsxAttrs.push(n);
  ts.forEachChild(n, walk);
})(sf);

const calleeText = (c) => c.expression.getText(sf).replace(/\s+/g, '');

// --- 1. the creator variant must not be called from this brand surface at all.
for (const c of calls) {
  if (/(^|\.)creatorDisputes\.open$/.test(calleeText(c))) {
    findings.push('line ' + at(c) + ': this brand surface calls creatorDisputes.open — the `role` ' +
      'option selects which session JWT is attached, so this sends the creator token slot (F-0242)');
  }
}

// --- 2. find the brand open call(s).
const opens = calls.filter((c) => /(^|\.)brandDisputes\.open$/.test(calleeText(c)));
if (opens.length === 0) {
  findings.push('no call to brandDisputes.open anywhere in this file — the brand has no way to ' +
    'open a dispute from the deal room, which is F-0242 itself');
}

// --- 3. each open call must sit in a function some onClick reaches.
//        Named enclosing function -> is that name referenced from a JSX onClick?
function enclosingNamedFunction(node) {
  let p = node.parent;
  while (p) {
    if (ts.isFunctionDeclaration(p) && p.name) return p.name.text;
    if ((ts.isArrowFunction(p) || ts.isFunctionExpression(p)) &&
        p.parent && ts.isVariableDeclaration(p.parent) && ts.isIdentifier(p.parent.name)) {
      return p.parent.name.text;
    }
    if (ts.isJsxAttribute(p)) return '@inline:' + (p.name && p.name.getText ? p.name.getText(sf) : '?');
    p = p.parent;
  }
  return null;
}

const clickHandlerNames = new Set();
let onClickCount = 0;
for (const a of jsxAttrs) {
  const an = a.name && a.name.getText ? a.name.getText(sf) : '';
  if (!/^on(Click|Submit|Press)$/.test(an)) continue;
  onClickCount++;
  (function collect(n) {
    if (ts.isIdentifier(n)) clickHandlerNames.add(n.text);
    ts.forEachChild(n, collect);
  })(a);
}
if (onClickCount === 0) {
  findings.push('this file renders no onClick/onSubmit at all — nothing here can be clicked');
}

for (const c of opens) {
  const fn = enclosingNamedFunction(c);
  if (fn === null) {
    findings.push('line ' + at(c) + ': brandDisputes.open is called from no named function and no ' +
      'inline handler — it cannot be reached by a control');
  } else if (fn.startsWith('@inline:')) {
    // called straight from an onClick={...} — reachable by construction.
  } else if (!clickHandlerNames.has(fn)) {
    findings.push('line ' + at(c) + ': brandDisputes.open is called inside `' + fn + '`, and no ' +
      'onClick/onSubmit in this file references `' + fn + '` — the call exists but nothing the ' +
      'brand can press reaches it (F-0242: the control is what was missing, not the method)');
  }
}

if (findings.length) {
  for (const f of findings) console.log('  x ' + f);
  console.log('  ' + findings.length + ' finding(s); brandDisputes.open call sites: ' + opens.length +
    '; click handlers seen: ' + onClickCount);
  process.exit(1);
}
console.log('  ok - ' + opens.length + ' brandDisputes.open call site(s), each inside a function a ' +
  'rendered onClick/onSubmit references; creatorDisputes.open is not called here');
process.exit(0);
