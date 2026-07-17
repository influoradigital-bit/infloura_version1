# Custom Claude Code Skills

Four ready-to-use skills, each a folder with a `SKILL.md`. This is the same
format the `caveman` plugin uses — a skill is just a Markdown file with YAML
frontmatter that Claude Code auto-loads when a request matches its
`description`.

## What's in here

| Folder | Skill | Triggers on |
|--------|-------|-------------|
| `terse-mode/` | Short, dense answers (caveman-style) with lite/full/ultra levels | "terse mode", "be brief", "talk like a caveman" |
| `writing-style/` | Consistent house voice for emails, docs, posts | "write", "draft", "make this on-brand" |
| `commit-crafter/` | Conventional Commit messages from a diff | "write a commit message", "commit this" |
| `code-reviewer/` | Prioritized, line-referenced code review | "review this code", "review my PR" |

## How a skill works

```
skill-name/
└── SKILL.md
```

```markdown
---
name: skill-name            # lowercase-with-hyphens, matches the folder
description: >-             # THE key field — how Claude decides to load it.
  What it does and the phrases/situations that should trigger it.
allowed-tools: Read, Bash   # optional — restricts tools; omit for no limit
---

# Human-readable instructions

Everything below the frontmatter is the prompt Claude follows once the
skill activates. Write it like you're briefing a teammate.
```

Two rules that matter most:

1. **The `description` is a trigger, not a summary.** Write it in terms of what
   the user will say or want ("Use when the user asks to…"), because that text
   is matched against each request to decide activation.
2. **The folder name should match `name`.** Keep both lowercase with hyphens.

You can add supporting files (scripts, templates, reference `.md` files) in the
same folder and tell the skill to read them — Claude Code loads the folder, not
just the single file.

## Install (personal — available in every project)

On Windows, your personal skills live in:

```
C:\Users\<you>\.claude\skills\
```

Copy each skill folder there. In PowerShell, from inside this `claude-skills`
folder:

```powershell
$dest = "$HOME\.claude\skills"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item -Recurse -Force .\terse-mode, .\writing-style, .\commit-crafter, .\code-reviewer $dest
```

You should end up with `C:\Users\<you>\.claude\skills\terse-mode\SKILL.md`, and
so on. Start a new Claude Code session and the skills are live.

To scope a skill to **one project instead**, put the folder in that repo's
`.claude/skills/` directory and commit it — everyone who clones the repo gets it.

## Using them

Just talk naturally — Claude loads the matching skill automatically. Or invoke
it by name, e.g. ask Claude to "use the code-reviewer skill on this diff."

## Make them yours

These are starting points. Open any `SKILL.md` and edit the instructions —
especially `writing-style/SKILL.md`, where the **Voice** section is meant to be
replaced with your actual brand voice. Tighten the `description` if a skill
triggers too often or too rarely.
