# Nisha — copy last call on U-7 "Saved briefs" (RULINGS-U-0917.md Round 3 §3 item 5)

**From:** Nisha (Content Lead)
**Tree:** `influora-b0`. Read only — no Maven, no vitest, no `git stash`, `New Influora` tree not touched.
**Read:**
- `src/components/creator/MeeraSettingsSection.tsx` (`SavedBriefsSection`, L737-924, and the "My Meera Conversations" table, L644-734, for tone comparison)
- `src/components/creator/MeeraSettingsSection.saved-briefs.test.tsx` (checked which strings a test locks in place)
- `KABIR-CONSENT-0917.md` — U-7 review (L262-305) and "Last call — U-6 words" (L309 onward)
- `RULINGS-U-0917.md` — Round 3 §3, §4, §7 (L207-411)
- `NISHA-CONSENT-0917.md` — my own Case A / Case B final text, for voice
- `ASSIGN-PENDING-0917.md` — U-7 UI status ("Copy: with nisha for approval")

---

## Verdict: CHANGES REQUIRED

Five of the eight items are fine as built. Two lines need a wording fix and one needs a punctuation fix, all in the confirm dialog and the rate-limit error. Exact replacement text is in the code block at the end.

| # | String(s) | Verdict |
|---|---|---|
| 1 | Heading / description | **APPROVE**, unchanged |
| 2 | Empty / loading states | **APPROVE**, unchanged |
| 3 | Cap note | **APPROVE**, unchanged |
| 4 | Row labels | **APPROVE**, unchanged |
| 5 | Confirm dialog title | **APPROVE**, unchanged |
| 6 | Confirm dialog body — PLATFORM | **CHANGES REQUIRED** — "reading" is unattributed |
| 6 | Confirm dialog body — PASTED | **CHANGES REQUIRED** — over-promises total erasure |
| 7 | Errors | **CHANGES REQUIRED** — one missing period |

---

## 1. Does the PASTED dialog's "permanently deletes" over-promise?

**Yes.** Kabir's fact (`KABIR-CONSENT-0917.md` L264-273): deleting a brief erases only the `creator_briefs` row. A chat reply that paraphrased it, or a brief pasted straight into chat, survives in `ai_messages.content` until that conversation is deleted separately — a different action, from a different table.

"This permanently deletes this brief. This cannot be undone." is true only about the brief record, but it doesn't say that's the scope. Two absolute claims back to back ("permanently," "cannot be undone") with nothing narrowing them read as "everything about this brief is gone" — which is exactly the misreading Kabir flagged and fixed in Case B (`NISHA-CONSENT-0917.md` L141: the old one-directional sentence "was true but... easy to misread as 'the two are linked'"). The confirm dialog is the more consequential surface than the notice — it's the moment she commits to the delete — so it should carry the same fact, not a weaker version of it.

**Fix:** reuse Kabir's own approved Case B sentence verbatim, in the same voice, placed between the two existing sentences so it reads as a scope note, not a hedge:

> This permanently deletes this brief. Your briefs and your conversations are deleted separately. This cannot be undone.

I did not add this to the PLATFORM branch. That branch already says the brief itself isn't gone for good ("will bring it back" / my rewording below), so there's no erasure claim to over-scope — adding the same sentence there would just be noise.

## 2. Is "Reading it again from the deal will bring it back" clear?

**No.** "Reading" has no subject in that sentence — a creator has no way to know whether *she* is meant to re-read something, or whether it means the page, or Meera. Everywhere else this product's copy uses "reads" with an explicit actor: Case B says "**Meera's AI** reads all of it." This line drops the actor and turns the verb into a gerund with nothing in front of it.

**Fix**, keeping "reads" and "saves" — both already established in Case B — and naming Meera as the one doing it:

> This deletes your saved copy of this brief. Meera saves a new copy automatically the next time she reads that deal.

This also stays inside Kabir's requirement that PLATFORM copy must not say "gone for good" (L273) — if anything it's clearer than the original that the deletion is not final.

## 3. Tone vs. "My Meera Conversations"

Matches, with one gap. Both tables use the same structure: a one-line description under the heading, "No … yet." for empty, "Loading …" for loading, a title-cased `Delete this ___?` confirm title, two short declarative sentences in the confirm body, and the same button labels ("Cancel" / "Delete" / "Deleting…"). The full-sentence, period-terminated pattern used everywhere else in this file (`Could not load Meera settings.`, `Could not load your saved briefs.`, `Could not delete this brief.`) is broken by one string:

> Too many deletes, try again in a minute

No period, alone among four error strings that all have one. **Fix:** add the period —

> Too many deletes, try again in a minute.

**Test note for Ananya:** `MeeraSettingsSection.saved-briefs.test.tsx` L161 does an exact match, `findByText('Too many deletes, try again in a minute')` — no period. That assertion needs the period added in the same commit as the source change, or it goes red. (L153's check on the 404 path uses a regex, `/too many deletes/i`, which doesn't care either way.)

No test locks the confirm-dialog body text (checked both the PLATFORM/PASTED strings and "Reading it again" — neither appears in the test file), so item 1 and item 2's fixes don't touch any test.

## 4. Hindi and Marathi

**English only, and it should stay that way for this change.** This whole settings section — both the existing "My Meera Conversations" table and the new "Saved briefs" table — is static JSX text with no i18n wrapper: no `t()`, no keyed copy record, nothing conditioned on `draft.creator_language`. The "My language" selector on this same page (L484-498) only sets the language *Meera writes to brands in* — it has no effect on the settings chrome around it. `ConsentScreen.tsx` is the one surface in this codebase that does key copy by language (`hi-IN` / `en-IN`, confirmed in `NISHA-CONSENT-0917.md` L10-14 and independently by Kabir, L350), and even there **no Marathi key exists** — a Marathi-language creator already reads the consent notice in English by fallback. Inventing Hindi or Marathi for a table whose neighbor has neither would be new scope no one asked for and a translation with no review path. Leave Saved briefs English-only, matching its neighbor.

---

## Exact replacement text

`src/components/creator/MeeraSettingsSection.tsx`, `SavedBriefsSection`:

**L807** — inside `handleDeleteBrief`'s 429 branch:
```tsx
        setRowError({ id: target, message: 'Too many deletes, try again in a minute.' });
```

**L903-908** — the confirm dialog description:
```tsx
            <AlertDialogDescription>
              {deleteTargetBrief?.source === 'PLATFORM'
                ? 'This deletes your saved copy of this brief. Meera saves a new copy automatically the next time she reads that deal.'
                : 'This permanently deletes this brief. Your briefs and your conversations are deleted separately. This cannot be undone.'}
            </AlertDialogDescription>
```

Everything else in Ananya's L754-758 list — heading, description, empty/loading states, cap note, row labels, dialog title, and the two other error strings — ships as written, no change.

## Confirmation against the brief's rules

- Plain words: yes, in the fixed lines and the unchanged ones.
- No "escrow": confirmed absent throughout.
- No promise the code doesn't keep: this is the fix itself — the PASTED dialog no longer implies total erasure when a copy can survive in chat, and the PLATFORM dialog now says who does the rereading instead of leaving it unattributed. Nothing else in the section claims anything beyond what U-7 ships (identity-only gate, 100-row cap, 20/window rate limit, silent-404-on-repeat-delete) — I checked the row labels, cap note and errors against those exact mechanics and found no gap.

**Companion fix owed alongside this, not mine to make:** `MeeraSettingsSection.saved-briefs.test.tsx` L161 needs its exact-match string updated to include the trailing period when the source change lands, or that test goes red on an unrelated punctuation fix.
