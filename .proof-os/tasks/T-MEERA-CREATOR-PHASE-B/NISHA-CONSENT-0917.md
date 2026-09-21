# Nisha — final consent copy for R-U2 (pasted briefs)

**From:** Nisha (Content Lead)
**Ref:** RULINGS-U-0917.md §R-U2
**Read, not touched:** `src/components/meera/ConsentScreen.tsx` (read-only; no source files edited, no stash, `New Influora` tree untouched)
**Tree:** `influora-b0`

---

## Where the languages live

`ConsentScreen.tsx` keys its copy off a single `CONSENT_TEXT` record with exactly two entries: `'hi-IN'` (L22-27) and `'en-IN'` (L28-33). Each entry has `title`, `body`, `accept`, `decline`. `body` is one string with `\n\n` between paragraphs, rendered with `whitespace-pre-line` (L62). The picker (L46) does `CONSENT_TEXT[language ?? 'en-IN'] ?? CONSENT_TEXT['en-IN']` — anything that isn't `hi-IN` falls back to English.

**There is no Marathi version today.** No `mr-IN` key, no other language branch anywhere in the file. Per the brief, I have not invented one — Marathi is out of scope until the screen actually gains that key.

The new paragraph goes on the end of `body` for both existing keys, after the existing two paragraphs, separated the same way (`\n\n`). Nothing else in the component changes for this text (the `v1`→`v2` bump and the `api.ts` mock update are Vikram/Ananya's side of R-U2, not mine).

---

## Final English (en-IN)

New third paragraph, appended to the existing two, unchanged, with one comma added for house style (see note below):

> When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, or phone numbers in it, and saves it with your briefs. Before you paste, remove anything you don't want Meera to read.

Full `body` value after the change:

```
Meera is your AI manager. She helps you understand your deals, payments, and metrics.

Meera will access your profile data, deal history, payment status, and metrics. You can export or delete your conversations anytime from Settings.

When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, or phone numbers in it, and saves it with your briefs. Before you paste, remove anything you don't want Meera to read.
```

**The one change I made to Priya's sentence:** I inserted the Oxford comma before "or" ("names, emails, or phone numbers") to match the two existing paragraphs, which both use a serial comma ("deals, payments, and metrics"; "profile data, deal history, payment status, and metrics"). This is punctuation only — the four facts are untouched: reads all of it; names, emails or phone numbers; saved with your briefs; remove before pasting. No promise of drafting or sending, no promise of deletion, no banned word. I did not touch "Meera's AI" as the subject (rather than plain "Meera") — see the voice note below; that phrase carries a fact Kabir may be relying on (an automated system reads it, distinct from the chat persona), so I left it for Priya/Kabir to change, not me.

---

## Final Hindi (hi-IN)

I kept all four facts from the draft and tightened the sentence flow to match Priya's English word order ("reads all of it, including ... and saves it") and the register already used in `ConsentScreen.tsx` L24 — Latin script kept for product-specific nouns and verbs that get a Hindi verb ending (`access करेगी`, `export`, `delete`, `Settings`, `conversations`), Devanagari transliteration for general business nouns already established in that string (`डील्स`, `पेमेंट`, `मेट्रिक्स`).

> जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email या phone number समेत पूरा text पढ़ती है, और उसे आपके briefs के साथ save करती है। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।

What changed from the draft in RULINGS-U-0917.md and why: the draft's "पूरा text पढ़ती है, उसमें लिखे नाम, email या phone number भी, और उसे...save करती है" reads as a run-on with "भी" ("also") dangling as an afterthought clause. I moved "उसमें लिखे नाम, email या phone number समेत" ("including the names, email or phone number written in it") in front of the verb, as a single "including X" clause modifying "reads," the same way the English does it. No fact moved, added, or dropped — brand's brief/message as the trigger, "reads all of it," names/email/phone number named explicitly, saved with briefs, remove-before-pasting instruction.

Full `body` value after the change:

```
Meera आपकी AI मैनेजर है। वह आपके डील्स, पेमेंट, और मेट्रिक्स को समझने में मदद करती है।

Meera आपके प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस और मेट्रिक्स को access करेगी। आप कभी भी अपनी conversations को Settings में जाकर export या delete कर सकते हैं।

जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email या phone number समेत पूरा text पढ़ती है, और उसे आपके briefs के साथ save करती है। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।
```

---

## What now reads wrong next to the new paragraph

1. **Deletion sentence sits right before it.** Paragraph 2 ends "You can export or delete your conversations anytime from Settings." — then the very next paragraph is about pasted briefs, which are never deleted (only dismissed/hidden per R-U3, and even that is session-scoped). RULINGS-U-0917 placed the new paragraph *after* that sentence specifically so the deletion promise can't be read as covering briefs — but adjacency cuts both ways: a skimming reader is just as likely to carry "you can delete that" forward into the brief paragraph as to read the boundary correctly. I have not added a disclaimer ("briefs cannot be deleted") because the brief for this task rules that out ("no promise of deletion" — and a negative promise is still a promise). This is the same tension Priya's own open question 2 to Kabir names (whether B0 needs a real delete route). If Kabir says no delete route is coming, I'd flag that this paragraph boundary needs a small explicit line distinguishing "conversations" from "briefs" — but that's a scope call for Priya/Kabir, not a wording fix I should make unilaterally.

2. **"She" vs "Meera's AI."** Paragraph 1 personifies Meera throughout ("Meera is your AI manager. **She** helps you..."). The new paragraph switches the actor to "Meera's AI" rather than "she." It's the same underlying fact (Meera is already introduced as an AI), but the referent shifts from a person-like "she" to an impersonal "Meera's AI" right after two paragraphs of "she/her." I left this as Priya wrote it because it may be doing real work for Kabir's accuracy pass (the thing reading a pasted brief is the extraction pipeline, not necessarily the chat persona in the same sense as "she helps you understand your deals"), so I didn't collapse it to "Meera reads all of it" myself. Flagging it in case Priya/Kabir want it smoothed once accuracy is settled.

3. **"Briefs" is used before it's defined.** This consent screen is shown "the first time a creator opens Meera" (component doc comment, L14-19) — before she's necessarily seen a brief anywhere in the product. Paragraphs 1-2 never use the word "brief." The new paragraph introduces "brief" and "briefs" with no in-notice definition. This is probably fine in practice (the term is defined by the product surface she'll meet right after, e.g. `PasteBriefCard`), but it's a first-encounter comprehension gap worth naming.

None of the three above are fixes I made to the delivered text — they're notes for Priya/Kabir's review, since the brief asked me to flag rather than freelance policy or fact changes.

---

## Confirmation against the brief's rules

- Plain words: yes, both languages.
- No "escrow": confirmed absent, both languages.
- No promise Meera drafts or sends anything: confirmed absent — the paragraph only says "reads" and "saves."
- No promise of deletion: confirmed absent — the only deletion promise in the notice is the existing, unchanged sentence about conversations, which does not extend to briefs.
- Facts preserved from Priya's version: reads all of it; names, emails or phone numbers; saved with your briefs; remove before pasting — all four present in both languages.

---

## Superseded by Kabir's review (KABIR-CONSENT-0917.md) and Priya's ruling (RULINGS-U-0917.md, "Round 3")

Kabir approved the paragraph above with three required changes (C1-C3): drop "saves it with your briefs" (there is no briefs screen to point to), name bank/UPI details and addresses alongside names/emails/phones, and state directly that deleting conversations does not delete the brief copy. Priya's Round 3 ruling confirms Wave U ships Kabir's **Case A** wording under consent **v2** (no delete control exists yet), and a later item (U-7) switches to **Case B** and bumps to **v3** once the saved-briefs delete table ships in Meera settings. Both are final below. My two open notes from above are resolved by this round:

- **Note 1 (deletion sentence adjacency)** is resolved by C1: the new paragraph now states directly that deleting conversations does not delete the brief copy, instead of leaving the reader to infer it. No promise is made either way about ever deleting it (Case A) — a plain fact, not a promise.
- **Note 3 ("briefs" undefined)** is resolved by C1: "saves it with your briefs" is gone. The paragraph now says "Influora saves a copy," which needs no screen to point to.

---

## Ruling: "she" vs "Meera's AI"

Closing my own flag from above. Kabir kept "Meera's AI" as the subject through both Case A and Case B, and his own accuracy review (Q1 item 3, Q2) treats "Meera's AI" as the operative phrase for naming *what* reads the pasted text — his only note on it is that it doesn't name the text as going to an *outside* provider, which he marks recommended-not-required and routes to Priya/Nisha with counsel, not as a reason to drop "AI" from the sentence.

**Ruling: keep "Meera's AI."** Paragraphs 1-2 personify Meera as "she" for the conversational relationship (managing deals, payments, metrics). This paragraph is a DPDP data-processing disclosure, not a description of the chat relationship — precision here (an automated system reads and stores the text) matters more than voice continuity with "she." The two registers sitting side by side in one notice is intentional, not an error: personify the assistant where the copy is about the relationship, name the system plainly where the copy is a processing disclosure. No change to Kabir's wording on this point in either case below.

---

## Final — Case A (v2)

Ships with Wave U. No delete control exists yet, so the paragraph states the fact and promises nothing about erasure.

### English

> When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy, and deleting your conversations does not delete it. Before you paste, remove anything you don't want Meera to read.

Kabir's sentence, unchanged — it already carries the house style's serial comma ("addresses, or bank or UPI details"), so there was nothing for me to adjust for voice.

Full `body` value for `en-IN`:

```
Meera is your AI manager. She helps you understand your deals, payments, and metrics.

Meera will access your profile data, deal history, payment status, and metrics. You can export or delete your conversations anytime from Settings.

When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy, and deleting your conversations does not delete it. Before you paste, remove anything you don't want Meera to read.
```

### Hindi

> जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy save करता है, और conversations delete करने से वो copy delete नहीं होती। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।

One register change from Kabir's draft (`KABIR-CONSENT-0917.md` L51): I transliterated "bank" to "बैंक". Every other business-domain noun already established in this notice is transliterated to Devanagari (ब्रांड, डील्स, पेमेंट, मेट्रिक्स, प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस), and "बैंक" is the ordinary Hindi word, not a specialist term — keeping "UPI" in Latin script is the acronym exception (matching "AI" earlier in the same notice). "पता" (address) is unchanged, already plain Hindi. No fact moved: names, email, phone number, address, bank or UPI details; Influora saves a copy; deleting conversations does not delete it; remove before pasting.

Full `body` value for `hi-IN`:

```
Meera आपकी AI मैनेजर है। वह आपके डील्स, पेमेंट, और मेट्रिक्स को समझने में मदद करती है।

Meera आपके प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस और मेट्रिक्स को access करेगी। आप कभी भी अपनी conversations को Settings में जाकर export या delete कर सकते हैं।

जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy save करता है, और conversations delete करने से वो copy delete नहीं होती। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।
```

---

## Final — Case B (v3, held for U-7; do not ship before the delete route + UI control land)

Per Priya's Round 3, this replaces Case A the same commit U-7 UI ships the saved-briefs table in Meera settings (§3) and bumps consent to `v3`. Writing it now only so U-7 doesn't wait on a copy round-trip; **not for use under v2**.

**Superseded once already.** Kabir revised this after his own U-7 review (`KABIR-CONSENT-0917.md`, "Plan review" → U-7 §2, L275-279): deleting a brief only erases the brief record. A reply that paraphrased it, or a brief pasted straight into chat, survives in chat history until that conversation is deleted separately (his trace, same file L264-273). "Deleting your conversations does not delete it" was true but one-directional and easy to misread as "the two are linked"; the fix states plainly that brief-deletion and conversation-deletion are two separate actions, neither implying the other. That fact is now in both languages below, replacing my previous middle sentence.

### English

> When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy until you delete it. Your briefs and your conversations are deleted separately. Before you paste, remove anything you don't want Meera to read.

Kabir's sentence, unchanged — house-style comma already in place, plain declarative fact, no promise beyond what U-7 actually ships.

Full `body` value for `en-IN` (Case B):

```
Meera is your AI manager. She helps you understand your deals, payments, and metrics.

Meera will access your profile data, deal history, payment status, and metrics. You can export or delete your conversations anytime from Settings.

When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy until you delete it. Your briefs and your conversations are deleted separately. Before you paste, remove anything you don't want Meera to read.
```

### Hindi

> जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy तब तक save रखता है जब तक आप उसे delete नहीं करते। आपके briefs और conversations अलग-अलग delete होते हैं। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।

Same "bank" → "बैंक" register change as Case A, nothing else altered from Kabir's revised Case B draft (`KABIR-CONSENT-0917.md` L279). "अलग-अलग delete होते हैं" ("are deleted separately") is a plural passive matching the two-item subject (briefs and conversations), the same construction pattern as "copy delete नहीं होती" earlier in the paragraph. This version promises deletion is possible ("save रखता है जब तक आप उसे delete नहीं करते") — correct only once U-7's route and UI control both ship; must not go out under `v2`.

Full `body` value for `hi-IN` (Case B):

```
Meera आपकी AI मैनेजर है। वह आपके डील्स, पेमेंट, और मेट्रिक्स को समझने में मदद करती है।

Meera आपके प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस और मेट्रिक्स को access करेगी। आप कभी भी अपनी conversations को Settings में जाकर export या delete कर सकते हैं।

जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy तब तक save रखता है जब तक आप उसे delete नहीं करते। आपके briefs और conversations अलग-अलग delete होते हैं। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।
```
