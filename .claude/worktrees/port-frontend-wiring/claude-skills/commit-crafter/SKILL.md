---
name: commit-crafter
description: >-
  Write clean Conventional Commit messages from staged changes or a diff. Use
  when the user asks to "write a commit message", "commit this", "what should
  the commit say", or shares a git diff and wants it summarized for version
  control. Focuses on the why, not a line-by-line what.
allowed-tools: Bash, Read, Grep, Glob
---

# Commit Crafter

Turn changes into a tight Conventional Commit message.

## How to work

1. If nothing is provided, run `git diff --staged` (and `git status`) to see
   what is being committed. If nothing is staged, look at `git diff`.
2. Group the change into a single intent. If it spans unrelated concerns, say
   so and suggest splitting into separate commits.
3. Write the message in Conventional Commits format.

## Format

```
<type>(<optional scope>): <subject>

<optional body: why the change, not what line moved>

<optional footer: BREAKING CHANGE / issue refs>
```

Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `build`, `ci`,
`chore`, `style`, `revert`.

## Rules

Subject in imperative mood ("add", not "added"). Lowercase after the colon.
No trailing period. Keep the subject ≤ 50 characters. Wrap body at ~72.

Explain *why* in the body when the reason isn't obvious from the subject. Skip
the body for trivial changes.

Add `BREAKING CHANGE: <what breaks>` in the footer when the change is not
backward compatible.

## Example

```
fix(auth): reject tokens on exact expiry boundary

Expiry check used `<` so a token was still accepted in the same second
it expired. Use `<=` to close the one-second window.

Refs #418
```
