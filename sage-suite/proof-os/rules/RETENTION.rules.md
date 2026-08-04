# RETENTION — what is remembered, what is deleted, and by whom

## 1. Change journal (append-only, forever)
Every event → .proof-os/journal.jsonl:
  {"ts","who","what","file","task","stage","gate_result"}
who = service name (registry) or "human:<name>". No anonymous writes.
Reports (tara) are QUERIES on this file, never hand-compiled prose.

## 2. Working-file cleanup (on task close)
When a task's verdict is final:
  1. EXTRACT  → failures to ledger/, changes already in journal
  2. ARCHIVE  → .proof-os/_archive/<task>/ with a purge date (+30 days)
  3. PURGE    → scripts/cleanup.py --purge deletes archives past date
KEEP FOREVER: journal.jsonl · ledger/ · gates/ · registry.json · graph.json
NEVER touch: repo source files, anything outside .proof-os/tasks & _archive.
Why: stale .md prose re-read into sessions is a permanent token tax, and
stale prose mistaken for truth is worse. Facts go to structured stores;
prose is distilled, then dies.

## 3. Who may change what
| Artifact         | Changed by          | Rule |
|------------------|---------------------|------|
| registry.json    | human only          | trust is assigned, never self-asserted |
| gates/           | any, via promote.py | must carry origin failure id header |
| ledger/          | append via scripts  | closes only via promotion law |
| journal.jsonl    | scripts only        | append-only, no edits, no deletes |
| graph.json       | scan.py only        | never hand-edited |
| rules/*.md       | human only          | version-controlled with the plugin |

## 4. Closure semantics (ruling, 0.1.5)
promoted_to closes the DETECTION of a class — the scanner/gate will catch it
forever. It does NOT mean the live instances were repaired. When instances
remain in code after promotion, open a separate repair record via ledger.py
(class: <original>-repair) listing every site. The map's NEEDS YOU shows
repair records until the sites are actually fixed. "We'll always catch it"
and "it's gone" are different claims; the ledger keeps them separate.

## 5. Intake (ruling, 0.1.5)
New failures enter ONLY via scripts/ledger.py add — from tasks, audits, or
findings outside any task. promote.py is the only exit. journal.py records
the add. Hand-editing failures.jsonl remains a violation of rule 3.
