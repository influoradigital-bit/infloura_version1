# Uncommitted Code Status — `feature/d14-invoicing`

**Date:** 14 Jul 2026
**Branch:** `feature/d14-invoicing` @ `26ea857`
**Checked with:** `git status`, `git diff --numstat`, `git diff --ignore-space-at-eol` on the working tree on disk.

---

## Headline

**No code is lost.** Git shows **904 modified files, 0 staged, 0 committed** since the last commit — but ~**894 of those are only a line-ending (CRLF ↔ LF) flip**, not real changes. Only **10 files have genuine content edits**, plus **2 brand-new files** that were never added to git. That handful is your real uncommitted work (the D14 invoicing feature).

| Metric | Value |
|---|---|
| Files git reports modified | 904 |
| Of those: pure line-ending churn (no code change) | ~894 |
| Of those: real content edits | **10** |
| Untracked new source files (real code) | **2** |
| Staged | 0 |
| Committed since last commit | 0 |

---

## Proof it is a line-ending flip, not lost work

- The version stored in git for `src/lib/api.ts` uses **LF** (0 carriage-return characters).
- The copy on disk uses **CRLF** (2,690 carriage-return characters — one per line).
- `git diff` therefore marks the whole 2,690-line file as changed (`2690` added / `2690` deleted).
- Re-running the diff with end-of-line whitespace ignored (`git diff --ignore-space-at-eol`) shows **zero** difference for `api.ts`, `config.py`, and `EscrowService.java`.

Root cause: the repo has **no `.gitattributes`** and `core.autocrlf` is **off**, so nothing normalizes line endings. A save/tool on Windows rewrote every file's endings from LF to CRLF.

---

## The files that ARE genuinely uncommitted

### Real content edits (10)

| File | +added / −deleted | Note |
|---|---|---|
| `influora-api/…/service/CampaignServiceInvoiceService.java` | 9 / 22 | D14 invoicing |
| `src/pages/creator-wallet.tsx` | 1 / 158 | large deletion (mock wallet data removed) |
| `src/pages/creator-settings.tsx` | 1 / 25 | |
| `src/pages/creator-login.tsx` | 3 / 0 | |
| `src/pages/brand-login.tsx` | 3 / 0 | |
| `influora-api/…/db/migration/V20260715130000__campaign_service_invoice.sql` | 2 / 2 | migration |
| `influora-api/…/db/migration/V20260715140000__platform_commission_invoice.sql` | 2 / 2 | migration |
| `influora-api/…/db/migration/V20260715150000__invoice_number_sequences.sql` | 2 / 2 | migration |
| `influora-api/…/db/migration/V20260715160000__hsn_sac_codes.sql` | 2 / 2 | migration |
| `SHARED_CONTEXT.md` | 1 / 35 | doc / handoff note |

### New files never added to git (2)

- `influora-api/src/main/java/com/influora/service/CreatorInvoiceCodeService.java`
- `src/components/shared/demo-access-panel.tsx`

> The other untracked entries — `INFLUORA-CODE-AUDIT-2026-07-14.md`, `AUDIT-COMPARISON-2026-07-14.md`, `_to_delete/_audit_src.tgz` — are audit outputs, not your source code.

---

## Two things blocking a clean commit

1. **Stale lock file:** `.git/index.lock` (0 bytes) exists from an interrupted git process. **Any commit will fail until it is deleted.** It must be removed manually — delete `.git\index.lock` in the project folder.
2. **The CRLF flip** will otherwise turn any commit into a ~900-file diff that buries the 12 files of real work.

---

## Recommended fix (clean, safe order)

Run these in the project root **after** deleting `.git\index.lock`:

```bash
# 1. Add a line-ending policy so this never recurs
printf '* text=auto eol=lf\n' > .gitattributes

# 2. Re-normalize: drops the ~894 phantom line-ending "changes"
git add --renormalize .

# 3. Confirm only the real work remains
git status --short
git diff --cached --stat

# 4. Add the two new files
git add influora-api/src/main/java/com/influora/service/CreatorInvoiceCodeService.java \
        src/components/shared/demo-access-panel.tsx \
        .gitattributes

# 5. Commit the real changes only
git commit -m "feat(d14): campaign-service + creator invoice codes; wallet/settings cleanup"
```

After step 2 the working tree should show only the ~12 real files instead of 904 — a tidy, reviewable commit.

---
*Verified against `feature/d14-invoicing @ 26ea857` on disk. "Pure line-ending churn" means the file is byte-identical to the committed version except for CRLF vs LF at line ends.*
