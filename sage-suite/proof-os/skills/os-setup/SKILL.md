---
name: os-setup
description: First-run setup for proof-os. Use on /os-setup, "install the trust layer", "set up proof-os on this project", or when /work finds no .proof-os directory. Scans the user's skills and plugins, proposes a registry mapping every skill to a service role with trust ceilings, and creates the per-project data directory after the user approves.
---
# /os-setup — first run. Propose, never impose.

1. `mkdir -p .proof-os/ledger .proof-os/tasks`
2. Copy ${CLAUDE_PLUGIN_ROOT}/templates/registry.template.json → .proof-os/registry.json
3. `python3 ${CLAUDE_PLUGIN_ROOT}/scripts/scan.py --project .` — discovers every
   user skill and plugin skill on this machine.
4. For each discovered user skill, PROPOSE a registry entry (kind, jurisdiction,
   may_claim, caps) based on what its SKILL.md declares. Rules of thumb:
   - runs real commands / real browser → oracle, may_claim: proved
   - reviews/approves by reading → judgment, may_claim: believed
   - creates artifacts → producer, believed, caps.retries: 2
   - routes/orchestrates → scheduler · cost/limits → governor · reports → syslog
   - generic capability (docx, pptx…) → libraries list, no entry
5. Show the full proposed registry AS A TABLE. The human approves or edits.
   Trust is assigned by the human, never self-asserted (RETENTION rule 3).
6. Write approved registry.json. Journal it: who=human:<name>, what=create.
7. Run work.py and show the first map. Point at NEEDS YOU.
