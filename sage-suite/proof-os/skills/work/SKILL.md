---
name: work
description: The door to proof-os, the trust layer. Use on /work, "show the project map", "what's broken", "start a task with proof", or before any team task. Renders the derived graph, dispatches by registry jurisdiction, enforces gates (proved vs believed), journals every change with who/what/when, and cleans working files on close. If no .proof-os/ exists in the project, run /os-setup first.
---
# /work — bootloader. You contain no judgment; the OS does.

All state lives in <project>/.proof-os/ (set PROOF_OS_DIR to override).
Plugin scripts: ${CLAUDE_PLUGIN_ROOT}/scripts. Rules: ${CLAUDE_PLUGIN_ROOT}/rules — read
FLOW.rules.md and RETENTION.rules.md once per session and obey them literally.

1. **Boot**: if no .proof-os/registry.json → invoke /os-setup and stop.
   Else: `python3 ${CLAUDE_PLUGIN_ROOT}/scripts/scan.py [--project .]`
2. **Render**: `python3 ${CLAUDE_PLUGIN_ROOT}/scripts/work.py` (--html map.html for the user).
   NEEDS YOU first. Silent when green.
3. **Task given** → confirm screen BEFORE any work: files to touch (graph),
   owners (registry jurisdiction), prior failures there (ledger), proposed
   done_when (one sentence). done_when is the ONLY question. Wait for approval.
   Create .proof-os/tasks/<id>/ for all working .md files.
4. **Dispatch** per registry: gates first (proved), judgment second (believed
   ceiling), caps enforced. oracle:model NEVER renders proved.
5. **Every claim** exits through its gate; keep exit codes. exit 2 = believed,
   never green. Verdicts go through validate.py — self-scored reports are rejected.
6. **Journal every change**: `journal.py add --who <service> --what <edit|create|delete|verdict> --file <f> --task <id> --stage <s>`. No anonymous writes.
7. **Every failure** → ledger record with missed_by. Close ONLY via
   `promote.py <id> <gate>` (refuses nonexistent gates) or --unautomatable with a name.
8. **On final verdict**: report PROVED/BELIEVED/BROKEN/LEARNED vs done_when in
   VERDICT.md voice. Then `cleanup.py close <task> --who <name>` — working .md
   archived, auto-purged in 30 days. Journal and ledger keep the facts forever.
