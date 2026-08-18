/*
 * gates/_pii_guard.js — origin: F-0329 (token-proxy gates), repairing F-0235.
 *
 * THE QUESTION F-0235 ACTUALLY ASKS. A fabricated person's name, phone and street address
 * exist in this file on purpose, as an offline demo fixture. The defect is not that they
 * exist — it is that a LIVE-mode code path can read them and put them in front of a brand
 * as their creator's real delivery details, and POST them to the real logistics endpoint.
 *
 * So the property is a REACHABILITY property, not a text property: every read of the
 * fixture must be dominated by a "not live" guard. No grep can express that. This does,
 * with the TypeScript parser the project already ships.
 *
 * Conservative by construction: a reference whose guard this analyser cannot PROVE is
 * reported as live-reachable. An unprovable guard is not a passing guard.
 *
 * Usage: node _pii_guard.js <file.tsx> <path/to/node_modules/typescript>
 * Exit 0 = every read of the fixture is demo-only · 1 = at least one is live-reachable
 *      · 2 = could not analyse.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const FILE = process.argv[2];
const TSDIR = process.argv[3] || 'node_modules/typescript';
if (!FILE) { console.log('usage: _pii_guard.js <file> <tsdir>'); process.exit(2); }

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

// The address literals F-0235 was opened for. This is a FLOOR, not the whole check:
// it survives someone renaming the fixture binding out of the MOCK_ namespace.
const HISTORIC = ['Sea View', 'Carter Road', '9876543210'];

// ---------------------------------------------------------------------------
// liveness(expr) — LIVE | NOTLIVE | UNKNOWN. Only shapes it can actually prove.
// ---------------------------------------------------------------------------
function liveness(expr) {
  if (!expr) return 'UNKNOWN';
  if (ts.isParenthesizedExpression(expr)) return liveness(expr.expression);
  if (ts.isPrefixUnaryExpression(expr) && expr.operator === ts.SyntaxKind.ExclamationToken) {
    const i = liveness(expr.operand);
    return i === 'LIVE' ? 'NOTLIVE' : i === 'NOTLIVE' ? 'LIVE' : 'UNKNOWN';
  }
  if (ts.isCallExpression(expr) && expr.arguments.length === 0) {
    if (/(^|\.)(isApiLive|isLive)$/.test(expr.expression.getText(sf).trim())) return 'LIVE';
  }
  if (ts.isBinaryExpression(expr)) {
    const k = expr.operatorToken.kind;
    const eq = k === ts.SyntaxKind.EqualsEqualsEqualsToken || k === ts.SyntaxKind.EqualsEqualsToken;
    const ne = k === ts.SyntaxKind.ExclamationEqualsEqualsToken || k === ts.SyntaxKind.ExclamationEqualsToken;
    if (eq || ne) {
      const l = liveness(expr.left);
      const rk = expr.right.kind;
      if (l !== 'UNKNOWN' && (rk === ts.SyntaxKind.TrueKeyword || rk === ts.SyntaxKind.FalseKeyword)) {
        const wantTrue = rk === ts.SyntaxKind.TrueKeyword;
        let res = ((l === 'LIVE') === wantTrue) ? 'LIVE' : 'NOTLIVE';
        if (ne) res = res === 'LIVE' ? 'NOTLIVE' : 'LIVE';
        return res;
      }
    }
  }
  return 'UNKNOWN';
}

function exitsUnconditionally(stmt) {
  if (!stmt) return false;
  if (ts.isReturnStatement(stmt) || ts.isThrowStatement(stmt)) return true;
  if (ts.isBlock(stmt)) return stmt.statements.some((s) => ts.isReturnStatement(s) || ts.isThrowStatement(s));
  return false;
}

// demoGuarded(node) — is this node provably unreachable while isApiLive() is true?
function demoGuarded(node) {
  let child = node;
  let p = node.parent;
  while (p) {
    if (ts.isConditionalExpression(p)) {
      const c = liveness(p.condition);
      if (child === p.whenTrue && c === 'NOTLIVE') return true;
      if (child === p.whenFalse && c === 'LIVE') return true;
    } else if (ts.isIfStatement(p)) {
      const c = liveness(p.expression);
      if (child === p.thenStatement && c === 'NOTLIVE') return true;
      if (p.elseStatement && child === p.elseStatement && c === 'LIVE') return true;
    } else if (ts.isBinaryExpression(p) && child === p.right) {
      const k = p.operatorToken.kind;
      if (k === ts.SyntaxKind.AmpersandAmpersandToken && liveness(p.left) === 'NOTLIVE') return true;
      if (k === ts.SyntaxKind.BarBarToken && liveness(p.left) === 'LIVE') return true;
    }
    // early-return form: `if (isApiLive()) return;` earlier in the same block makes
    // everything after it demo-only.
    if (ts.isBlock(p) || ts.isSourceFile(p)) {
      const idx = p.statements.indexOf(child);
      for (let i = 0; i < idx; i++) {
        const s = p.statements[i];
        if (ts.isIfStatement(s) && !s.elseStatement &&
            liveness(s.expression) === 'LIVE' && exitsUnconditionally(s.thenStatement)) return true;
      }
    }
    child = p; p = p.parent;
  }
  return false;
}

// ---------------------------------------------------------------------------
// walk
// ---------------------------------------------------------------------------
const strings = [];      // every string-ish literal node
const identifiers = [];  // every identifier node
(function walk(n) {
  if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) strings.push(n);
  if (ts.isIdentifier(n)) identifiers.push(n);
  ts.forEachChild(n, walk);
})(sf);

// module-level `const X = ...` declarations, by name
const moduleDecls = new Map();
for (const st of sf.statements) {
  if (!ts.isVariableStatement(st)) continue;
  for (const d of st.declarationList.declarations) {
    if (d.name && ts.isIdentifier(d.name)) moduleDecls.set(d.name.text, d);
  }
}
function enclosingModuleDecl(node) {
  for (const [name, d] of moduleDecls) {
    if (node.getStart(sf) >= d.getStart(sf) && node.getEnd() <= d.getEnd()) return { name, decl: d };
  }
  return null;
}

// --- 1. FLOOR: the historic address literals may live ONLY inside a MOCK_-prefixed
//        module constant. Anywhere else is fabricated PII loose in the component.
const fixtureNames = new Set();
for (const s of strings) {
  if (!HISTORIC.some((h) => s.text.includes(h))) continue;
  const owner = enclosingModuleDecl(s);
  if (!owner) {
    findings.push('line ' + at(s) + ': fabricated address literal ' + JSON.stringify(s.text) +
      ' is not inside any module-level constant - it sits directly in component code');
  } else if (!/^MOCK_/.test(owner.name)) {
    findings.push('line ' + at(s) + ': fabricated address literal ' + JSON.stringify(s.text) +
      ' lives in `' + owner.name + '`, which is not MOCK_-prefixed - nothing marks it demo-only');
  } else {
    fixtureNames.add(owner.name);
  }
}
if (fixtureNames.size === 0 && findings.length === 0) {
  console.log('no fabricated address fixture found at all - F-0235 has no subject in this file');
  console.log('(if the fixture was deleted outright that is a fix, but this analyser cannot');
  console.log(' distinguish it from the file being restructured past recognition)');
  process.exit(2);
}

// --- 2. REACHABILITY: every read of a fixture binding must be demo-guarded.
let reads = 0;
for (const id of identifiers) {
  if (!fixtureNames.has(id.text)) continue;
  const d = moduleDecls.get(id.text);
  if (d && d.name === id) continue;                 // the declaration itself
  if (ts.isPropertyAccessExpression(id.parent) && id.parent.name === id) continue; // `x.MOCK_...`
  reads++;
  if (!demoGuarded(id)) {
    findings.push('line ' + at(id) + ': `' + id.text + '` is read on a path this analyser cannot ' +
      'prove is unreachable in live mode - fabricated PII can reach the brand and the ' +
      'shipment POST (F-0235)');
  }
}
if (reads === 0) {
  findings.push('the fixture ' + [...fixtureNames].join(', ') + ' is never read - either the demo ' +
    'flow is dead, or the address now reaches the form by some route this analyser cannot see');
}

// --- 3. the real data source the live path is supposed to use.
const hasLiveFetch = identifiers.some((id) =>
  id.text === 'get' && ts.isPropertyAccessExpression(id.parent) && id.parent.name === id &&
  /(^|\.)shipments$/.test(id.parent.expression.getText(sf).trim()));
if (!hasLiveFetch) {
  findings.push('no `shipments.get(...)` call - the live path has no real address source, so a ' +
    'fixture fallback is the only thing that could be feeding the form');
}

// --- 4. the contact/postal values of the fixture must not be re-inlined elsewhere.
//        Derived from the fixture object itself, so it cannot go stale when the demo
//        values change. Restricted to phone/address/landmark: fullName, city and state
//        are legitimately shared with unrelated demo data in the same file.
const PII_FIELDS = /^(phone|mobile|addressLine1|addressLine2|landmark|street)$/;
for (const name of fixtureNames) {
  const d = moduleDecls.get(name);
  if (!d || !d.initializer || !ts.isObjectLiteralExpression(d.initializer)) continue;
  for (const prop of d.initializer.properties) {
    if (!ts.isPropertyAssignment(prop) || !prop.name || !ts.isIdentifier(prop.name)) continue;
    if (!PII_FIELDS.test(prop.name.text)) continue;
    const v = prop.initializer;
    if (!ts.isStringLiteral(v) || v.text.length < 6) continue;
    for (const s of strings) {
      if (s === v || !s.text.includes(v.text)) continue;
      const owner = enclosingModuleDecl(s);
      if (owner && fixtureNames.has(owner.name)) continue;
      findings.push('line ' + at(s) + ': the fixture ' + prop.name.text + ' value ' +
        JSON.stringify(v.text) + ' is re-inlined outside the fixture');
    }
  }
}

if (findings.length) {
  for (const f of findings) console.log('  x ' + f);
  console.log('  ' + findings.length + ' finding(s); fixture bindings analysed: ' +
    ([...fixtureNames].join(', ') || '(none)') + '; reads checked: ' + reads);
  process.exit(1);
}
console.log('  ok - fixture ' + [...fixtureNames].join(', ') + ': ' + reads +
  ' read(s), every one provably demo-only; live path fetches a real shipment record');
process.exit(0);
