/*
 * gates/_member_src.js — origin: F-0329 (token-proxy gates), repairing F-0272.
 *
 * THE ANCHOR PROBLEM. The F-0272 gate isolated `contracts.get` with an awk range whose
 * terminator patterns assumed a 4-space indent while the real code is indented 6 and 8. It
 * therefore never terminated: the "window" ran 3781 lines, from contracts.get to
 * `export default api;`. Its `grep -A 22` fallback never fired either, because a 3781-line
 * string is not empty. Two of that gate's three assertions were being answered by unrelated
 * code thousands of lines away. That is the F-0296 class — a fixed-size or drifting text
 * window — in its widest form.
 *
 * A line window is the wrong instrument. This prints the EXACT source text of one member of
 * one exported object, located by the TypeScript parser. It cannot drift with indentation,
 * reformatting, comment length, or a neighbour being added or removed, because it is not
 * looking at lines at all.
 *
 * Usage: node _member_src.js <file.ts> <exportedConstName> <memberName> <tsdir> [--as NAME]
 * Prints `const NAME = <initializer source>;` on stdout.
 * Exit 0 = found · 3 = the export or the member does not exist · 2 = could not analyse.
 * Exit 3 is distinct so a caller can tell "the thing is gone" (often a finding) from
 * "I could not look" (never a finding).
 */
'use strict';
const fs = require('fs');
const path = require('path');

const [, , FILE, EXPORT_NAME, MEMBER_NAME, TSDIR_IN, ...rest] = process.argv;
const TSDIR = TSDIR_IN || 'node_modules/typescript';
let asName = MEMBER_NAME;
const asIdx = rest.indexOf('--as');
if (asIdx !== -1 && rest[asIdx + 1]) asName = rest[asIdx + 1];

if (!FILE || !EXPORT_NAME || !MEMBER_NAME) {
  console.error('usage: _member_src.js <file> <export> <member> <tsdir> [--as NAME]');
  process.exit(2);
}

let ts;
try { ts = require(path.resolve(TSDIR)); }
catch (e) { console.error('cannot load the typescript compiler from ' + TSDIR + ': ' + e.message); process.exit(2); }

let src;
try { src = fs.readFileSync(FILE, 'utf8'); }
catch (e) { console.error('cannot read ' + FILE + ': ' + e.message); process.exit(2); }

const sf = ts.createSourceFile(FILE, src, ts.ScriptTarget.Latest, true,
  /\.tsx$/.test(FILE) ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
if (!sf || !sf.statements) { console.error('typescript produced no AST for ' + FILE); process.exit(2); }

let obj = null;
for (const st of sf.statements) {
  if (!ts.isVariableStatement(st)) continue;
  for (const d of st.declarationList.declarations) {
    if (!d.name || !ts.isIdentifier(d.name) || d.name.text !== EXPORT_NAME) continue;
    if (d.initializer && ts.isObjectLiteralExpression(d.initializer)) obj = d.initializer;
  }
}
if (!obj) {
  console.error('no exported object literal named `' + EXPORT_NAME + '` in ' + FILE);
  process.exit(3);
}

for (const p of obj.properties) {
  const nm = p.name && ts.isIdentifier(p.name) ? p.name.text
           : p.name && ts.isStringLiteral(p.name) ? p.name.text : null;
  if (nm !== MEMBER_NAME) continue;
  let text = null;
  if (ts.isPropertyAssignment(p)) {
    // getText() spans exactly the initializer node, comments and all — no line arithmetic.
    text = p.initializer.getText(sf);
  } else if (ts.isMethodDeclaration(p)) {
    const params = p.parameters.map((x) => x.getText(sf)).join(', ');
    text = 'function (' + params + ') ' + (p.body ? p.body.getText(sf) : '{}');
  } else if (ts.isShorthandPropertyAssignment(p)) {
    text = p.name.text;
  }
  if (text === null) {
    console.error('`' + EXPORT_NAME + '.' + MEMBER_NAME + '` is a property kind this extractor ' +
      'does not know how to lift (' + ts.SyntaxKind[p.kind] + ')');
    process.exit(2);
  }
  process.stdout.write('const ' + asName + ' = ' + text + ';\n');
  process.exit(0);
}

console.error('`' + EXPORT_NAME + '` has no member `' + MEMBER_NAME + '`');
process.exit(3);
