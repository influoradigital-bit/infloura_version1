/*
 * gates/_nested_component.js — origin: F-0335 (gate-is-name-locked), repairing
 * gates/F-0050-static-view-components.sh.
 *
 * WHAT F-0050 ACTUALLY IS. A component-shaped binding declared INSIDE another
 * component's render body and then mounted as a JSX element is a NEW function
 * identity on every render of the parent. React compares element types by
 * identity, so it does not update that subtree — it unmounts the old tree and
 * mounts a fresh one. Every piece of state below it is thrown away, focus is
 * lost, inputs reset mid-typing, and effects re-run.
 *
 * WHY THIS FILE EXISTS. The F-0050 gate enumerated the three identifiers the
 * original record happened to name (BoardView / ListView / TimelineView). It was
 * sound against a regression of that INSTANCE and blind to the CLASS: renaming
 * the same defect to KanbanView walked straight past it at exit 0. Reproduced at
 * .proof-os/tasks/T-F0335/F-0335.inject.log.
 *
 * "Declared inside a render body" is a SCOPE question, and a scope question is
 * not answerable by grep. This analyser therefore uses the TypeScript compiler
 * the project already ships, the same way gates/_pii_guard.js does.
 *
 * THE DECISION, stated precisely. A finding is reported when ALL of:
 *   1. the binding's initialiser is a FUNCTION OR CLASS LITERAL — an arrow, a
 *      function expression, a function declaration, or a class. NOT an alias
 *      (const StageIcon = stage.icon), NOT the result of a call (useMemo(...),
 *      memo(...), forwardRef(...)), because those do not mint a new identity on
 *      every render;
 *   2. it is declared inside an enclosing FUNCTION THAT CONTAINS JSX — i.e. a
 *      render body. Module scope is not a render body, and a component defined
 *      at module scope is ordinary, correct React;
 *   3. its name is MOUNTED AS A JSX TAG somewhere inside that same enclosing
 *      scope. The mount is what makes it a component rather than a helper: a
 *      render helper invoked as boardView(), a render prop handed to a child,
 *      and a map callback returning JSX are all called as plain functions and
 *      produce no separate fiber identity.
 * No identifier is enumerated anywhere in this file. The name is read off the
 * AST, not compared against a list.
 *
 * COMMENTS. A comment is not an AST node, so a comment quoting the defect it
 * describes cannot produce a finding (F-0266). The gate additionally feeds this
 * analyser the gates/_code.sh stripped view, so both mechanisms agree.
 *
 * Usage: node _nested_component.js <file.tsx> [tsdir] [--display <path>]
 * Exit 0 = no in-render component identity - 1 = at least one - 2 = cannot analyse.
 *
 * BLIND SPOTS, stated rather than silently exceeded:
 *  - a nested component PASSED AS A PROP and rendered by the child
 *    (<Route element={Panel} />) is not mounted as a tag here and is not seen;
 *  - a nested component built by a call this analyser cannot see through
 *    (const P = React.memo(() => <div/>) inside a render body) is treated as
 *    stable. memo around a fresh arrow IS still unstable; excluding calls is a
 *    deliberate trade for zero false reds on the ordinary memoised patterns;
 *  - a component defined in a custom hook that does not itself contain JSX;
 *  - resolution is lexical-by-name inside the enclosing function, not a real
 *    symbol table: a shadowed name in a sibling scope could be mis-attributed.
 *    That direction produces a false RED, never a false green.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
let FILE = null, TSDIR = null, DISPLAY = null;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--display') { DISPLAY = args[++i]; continue; }
  if (FILE === null) { FILE = args[i]; continue; }
  if (TSDIR === null) { TSDIR = args[i]; continue; }
}
if (!FILE) { console.log('usage: _nested_component.js <file.tsx> [tsdir] [--display path]'); process.exit(2); }
TSDIR = TSDIR || 'node_modules/typescript';
DISPLAY = DISPLAY || FILE;

let ts;
try { ts = require(path.resolve(TSDIR)); }
catch (e) { console.log('cannot load the typescript compiler from ' + TSDIR + ': ' + e.message); process.exit(2); }

let src;
try { src = fs.readFileSync(FILE, 'utf8'); }
catch (e) { console.log('cannot read ' + FILE + ': ' + e.message); process.exit(2); }

const sf = ts.createSourceFile(DISPLAY, src, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
if (!sf || !sf.statements) { console.log('typescript produced no AST for ' + DISPLAY); process.exit(2); }
const line = (n) => sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1;

// --------------------------------------------------------------------------
// containsJsx(node) — is there any JSX anywhere in this subtree? Memoised: the
// question is asked once per enclosing function.
// --------------------------------------------------------------------------
const jsxMemo = new Map();
function containsJsx(node) {
  if (jsxMemo.has(node)) return jsxMemo.get(node);
  let found = false;
  (function walk(n) {
    if (found) return;
    if (ts.isJsxElement(n) || ts.isJsxSelfClosingElement(n) || ts.isJsxFragment(n)) { found = true; return; }
    ts.forEachChild(n, walk);
  })(node);
  jsxMemo.set(node, found);
  return found;
}

const isFunctionLike = (n) =>
  ts.isFunctionDeclaration(n) || ts.isFunctionExpression(n) ||
  ts.isArrowFunction(n) || ts.isMethodDeclaration(n);

// The literal shapes that mint a NEW identity every time the enclosing body runs.
const isIdentityMintingLiteral = (n) =>
  !!n && (ts.isArrowFunction(n) || ts.isFunctionExpression(n) || ts.isClassExpression(n));

// tagRoot: <Foo/> and <Foo.Bar/> both root at "Foo".
function tagRoot(tagName) {
  if (!tagName) return null;
  if (ts.isIdentifier(tagName)) return tagName.text;
  if (ts.isPropertyAccessExpression(tagName)) return tagRoot(tagName.expression);
  return null;
}

// mountsOf(scope, name) — JSX tags inside scope whose root identifier is name.
function mountsOf(scope, name) {
  const hits = [];
  (function walk(n) {
    if (ts.isJsxSelfClosingElement(n) || ts.isJsxOpeningElement(n)) {
      if (tagRoot(n.tagName) === name) hits.push(n);
    }
    ts.forEachChild(n, walk);
  })(scope);
  return hits;
}

// --------------------------------------------------------------------------
// the sweep
// --------------------------------------------------------------------------
const findings = [];
const seen = new Set();

function consider(name, declNode, kindLabel, fnStack) {
  if (!name || !/^[A-Z]/.test(name)) return;   // a lowercase tag is an intrinsic element
  // (2) some enclosing function must be a render body
  let scope = null;
  for (let i = fnStack.length - 1; i >= 0; i--) {
    if (containsJsx(fnStack[i])) { scope = fnStack[i]; break; }
  }
  if (!scope) return;
  // (3) the name must actually be mounted as a JSX tag inside that scope
  const mounts = mountsOf(scope, name);
  if (mounts.length === 0) return;
  const key = name + '@' + declNode.getStart(sf);
  if (seen.has(key)) return;
  seen.add(key);
  findings.push({
    name: name,
    kind: kindLabel,
    declLine: line(declNode),
    scopeName: (scope.name && scope.name.text) || '(anonymous render body)',
    scopeLine: line(scope),
    mountLines: mounts.map(line),
  });
}

(function walk(node, fnStack) {
  if (ts.isFunctionDeclaration(node) && node.name && fnStack.length) {
    consider(node.name.text, node, 'function declaration', fnStack);
  } else if (ts.isClassDeclaration(node) && node.name && fnStack.length) {
    consider(node.name.text, node, 'class declaration', fnStack);
  } else if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && fnStack.length) {
    if (isIdentityMintingLiteral(node.initializer)) {
      const k = ts.isClassExpression(node.initializer) ? 'class expression'
        : ts.isArrowFunction(node.initializer) ? 'arrow function' : 'function expression';
      consider(node.name.text, node, k, fnStack);
    }
  }
  const nextStack = isFunctionLike(node) ? fnStack.concat([node]) : fnStack;
  ts.forEachChild(node, function (c) { walk(c, nextStack); });
})(sf, []);

if (findings.length === 0) {
  console.log('clean: no function or class literal declared inside a render body is mounted as JSX');
  process.exit(0);
}
for (const f of findings) {
  console.log(
    DISPLAY + ':' + f.declLine + ': ' + f.name + ' (' + f.kind + ') is declared inside ' +
    f.scopeName + ' (line ' + f.scopeLine + ') and mounted as <' + f.name + '> at line ' +
    f.mountLines.join(', ') +
    ' — a new component identity on every render, so React remounts its whole subtree and loses its state'
  );
}
console.log(findings.length + ' in-render component identit' + (findings.length === 1 ? 'y' : 'ies'));
process.exit(1);
