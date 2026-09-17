# Pending Work — Sage Team Assignments

> **Arjun Kapoor, Engineering Lead** — 2026-09-17
> Scope: everything still open in the Creator Co-pilot, Profile Metrics, Subscription, and Meera
> Creator Phase B workstreams. Routed through proof-os `/work`.
> **done_when:** every pending item has exactly one owner and one done_when, and no item is also
> claimed by the other live lane.

---

## Rules for every lane

1. **One file, one lane.** Two lanes never edit the same file. That is what collided three times this
   month.
2. **Verify the commit, not the working tree.** Before calling anything done:
   `git archive <commit> | (mkdir -p /tmp/v && cd /tmp/v && tar -x)`, then compile and test there.
   The working tree passed twice while the real commit failed (F-0835).
3. **Clean build before any mutation test.** `mvn -o clean compile`. An incremental build served a
   stale green on a mutant (F-0843, other lane).
4. **Commit only your own files**, with `git commit -- <paths>`. Never `git add -A`.
5. **A fix is not a close.** Tickets close through `promote.py` with a gate on disk.

---

## P0 — Blocks turning the Co-pilot on (Priya's sign-off conditions)

### L1 · Vikram · `CreatorNudgeService.java` + its test
| Ticket | Work |
|---|---|
| F-0832 + F-0837 | Plurals get past the phrase list (`school shootings`, `acid attacks`, `hate crimes`, `lynch mobs`, `high courts`). Fix every multi-word phrase, not just the five that were tried. |
| F-0838 | Move the theme safety check above `callAiSafely`. Today an unsafe theme still pays for an AI call. |
| F-0834 | Add a test that every theme in `theme-taxonomy.json` passes the filter. |
| F-0833 | Log the right field when copy is blocked. |

**done_when:** a generated test asserts the plural of every multi-word phrase blocks, and goes red
then green; a test asserts the AI client is never called on an unsafe theme; the taxonomy test
exists; everything passes when compiled from `git archive` of the commit.
**Gate after:** Kabir re-checks (fresh context, his own bypass strings), then Kavya QA.
**Status:** DISPATCHED 2026-09-17.

### L2 · Meera · deploy files + CI
| Ticket | Work |
|---|---|
| F-0787 | `CREATOR_COPILOT_ENABLED` and `MEERA_CREATOR_ENABLED` are missing from `docker-compose.utho-shared.yml`. Add them, and add a gate that fails when the two Utho compose files forward different keys. |
| F-0822 | The concurrency test has never run (no Docker here). Add a CI job that runs it with Docker. |

**Watch out:** F-0842 (other lane) found that the live server reads
`/usr/local/App/influora/influora.env`, which is not in this repo. A compose fix alone does not prove
the live box is fixed — say so in the report.
**done_when:** the key-diff gate fails on the current file and passes after the fix; the CI job
exists and its run log shows `Tests run: 1, Failures: 0, Skipped: 0` — a skip does not count.
**Status:** DISPATCHED 2026-09-17.

---

## P1 — Money and process

### L3 · Vikram · `BrandAiCredit.java`, `AICreditService.java`, `AICreditResetJob.java`, new migration
| Ticket | Work |
|---|---|
| F-0836 | A brand that was ever on Pro keeps 400 AI credits a month forever after cancelling. Nothing syncs the allowance back down. |
| Audit F-3 | The same column is written by two owners (plan sync and the loyalty bonus). Split it into `planAllotment` and `loyaltyBonus`; make `monthlyAllotment` computed. |

Ruling to follow: `SUBSCRIPTION-MODEL-REDESIGN-0912.md §7 SM-0.2` — the +50 loyalty bonus stacks
on Pro (450 with a funded campaign, 400 without).
**done_when:** a test puts a workspace on Pro, resets, cancels, resets again, and the allowance drops
to the Free value (with any earned bonus kept). Migration applies from `git archive`.
**Gate after:** Kabir (money path), Kavya, Meera.
**Status:** ASSIGNED, starts when L1 lands.

### L4 · Vikram · `Entitlement.java`, `CreatorDiscoveryService.java`
| Ticket | Work |
|---|---|
| SM-0.1 (ruled 2026-09-12) | Saved creators are sold as "5 on Free" but never limited. Add `SAVED_CREATORS` and check the limit when saving. Brands already over 5 keep what they have. |

**done_when:** the conformance test for `SAVED_CREATORS` goes red without the check and green with it.
**Status:** ASSIGNED, starts after L3.

### L5 · Dev · git hooks + `.proof-os/gates/`
| Ticket | Work |
|---|---|
| F-0835 | Add a pre-push check that compiles `git archive HEAD`, not the working tree. This bug has now hidden a broken commit twice. |

**done_when:** the hook blocks a push when a committed file calls a method that exists only as an
uncommitted edit (build that case deliberately and show it blocked).
**Status:** ASSIGNED.

---

## P2 — Profile metrics and the brand view

### L6 · Ananya · `src/pages/brand-creator-profile.tsx`
| Ticket | Work |
|---|---|
| F-0795 + F-0796 | The brand page hard-codes audience data to empty, citing a comment that is now false. The endpoint exists. Wire it. Before a collaboration exists, show bands only (ruling `2026-09-15-brand-preconsent-visibility.md`). |

**done_when:** a click test shows bands before connection and exact numbers after; the stale
comments are removed.

### L7 · Vikram · `CreatorDtos.java`, `MetaConnectionService.java`, `MeeraContextService.java`
| Ticket | Work |
|---|---|
| F-0792 (Task 2.2) | Add post count, average views, likes, reach, saves, shares, watch time and `statsAsOf` to the platform stats. **Blocked by L8's Task 0.4.** |
| F-0794 (Task 2.4) | Fetch metrics when a creator connects, not up to 24 hours later. |
| F-0797 | Give Meera more than a follower count: post count is already stored and never read. |
| F-0798 | The creator's own analytics already exist and Meera never calls them. |

**Status:** ASSIGNED, starts after L4.

### L8 · Priya · decisions and docs
| Item | Work |
|---|---|
| Task 0.4 | Answer §8 of `profile-data-model.md` and move it from DRAFT to LOCKED. Unblocks F-0792. |
| F-0783 | Choose how non-English captions get matched (transliteration, per-language keywords, or a model). |
| F-0821 | Decide whether losing plural matches in theme tagging is acceptable. |
| F-0824 / F-0839 | Close or re-word F-0824; it describes a bug `d3a2491` already fixed. |

### L9 · Kavya · tests and promotion gates
| Ticket | Work |
|---|---|
| F-0775, F-0776, F-0777, F-0784, F-0785, F-0786, F-0790, F-0793, F-0825, F-0826, F-0827 | Fixed in code but still open. Write a gate for each so it can close through `promote.py`. |
| F-0819 | Verified closed on 2026-09-17 (HEAD test-compiles in isolation). Promote once L5's gate exists. |
| F-0820 | Test the "taxonomy failed to load" path and a keyword containing a regex character. |

---

## P3 — Trend supply and categories (after the Co-pilot is safe to enable)

### L10 · Vikram · new `TrendPullJob`, `TrendRepository.java`
| Ticket | Work |
|---|---|
| F-0774 | Nothing we deploy writes the `trends` table. Build the job from `job-design.md`. |
| F-0778 | Use soft expiry. Do not use `ON DELETE CASCADE`. |
| F-0781 | The job must log which news sources ran and which were skipped. |
| F-0823 | `ThemeMatchService.parseThemeJson` does not check themes against the known list. Harmless today (only n8n writes themes, and it filters). Fix it before this job adds AI-recovered themes. |

### L11 · Vikram + Ananya · category field
| Ticket | Work |
|---|---|
| F-0782 | Add a declared creator category (ruling `2026-09-15-creator-category-declared.md`) and fetch news per category. |

---

### L12 · Vikram · `influora-ai/app/prompt/creator_suggestion.py`
| Ticket | Work |
|---|---|
| F-0828 | The AI prompt says nothing about avoiding deaths, crimes or riots. The copy is now filtered after the fact (`d3a2491`), but the model is still asked to hype whatever headline it gets. Add the instruction. |

**Coordinate:** the other lane's F-0841 covers output validators in `chat.py`. Different file, same idea — check with them before adding a second validator.
**Status:** ASSIGNED, P2.

---

## Decisions only Swapnil can make

| # | Question |
|---|---|
| D1 | Phase B0 is finished on its own branch (`feat/meera-creator-phase-b0`, 44 commits ahead of `main`) and has never been merged. Merge it, and when? |
| D2 | Phase B1 (Secure and Send) waits on 14 days of live B0 numbers (spec §12 #4). B0 is not live, so that clock has not started. |
| D3 | Ratify the published prices (SM-0.3): ₹4,999/month, 7% fee, Free limits, no trial. They are already live. |

---

## Carried on purpose — not assigned

- **F-0829, F-0830, F-0831** — gaps in coverage breadth. Priya ruled them fine to carry.
- **F-0817** — a commit message that misdescribes its contents. History cannot be edited; logged only.

## Belongs to the other lane — not assigned here

F-0789, F-0791, F-0800 to F-0816, F-0840 to F-0851 (brand Meera, fees, tax, custody, deploy env).
Checked 2026-09-17: nobody has uncommitted edits on any file assigned above.
