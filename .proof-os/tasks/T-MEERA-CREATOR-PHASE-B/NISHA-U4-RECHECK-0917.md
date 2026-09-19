# Nisha — U-4 re-check (2026-09-17)

Read-only pass over `influora-b0`. No source touched. Reviewed against
`ASSIGN-PENDING-0917.md` U-4 row, `RULINGS-U-0917.md` R-U1, and my own three
rules: plain words, never "escrow", no drafting/sending promise.

## 1. My requested changes — landed verbatim

`git diff` on both files confirms every line I asked for is in the working
tree exactly as written, at the line numbers given:

**`src/pages/meera-for-creators.tsx`**
- L73 — `'Meera brings a list of brands that fit you, and says why each one fits.'` ✓ (no send claim)
- L91 — `<Seo description>` reads `"…Meera reads it and suggests a rate. Coming soon on Influora."` ✓ ("drafts the reply" gone)
- L123 — `"Paste a brand brief. Meera reads it and suggests what to charge."` ✓ (the "send button is always yours" line is gone along with it, correctly — nothing to send now)
- L188 — `"Five jobs. Meera advises; you decide."` ✓
- L247–248 — `"You decide. Meera tells you what she thinks. What you say to a brand is up to you."` ✓
- The "Brands will know… drafted with Meera, approved by you" bullet is removed and replaced with an explanatory JSX comment (L250–252) instead of dead markup. Correct call.
- JSON-LD description (L98) also has "approval-gated replies" removed — I didn't call out this line number but it's the same claim (item 5 in her own file-header note) and it's gone. Confirmed.

**`src/remotion/script.ts` (Hinglish)**
- `Level 0`: `'Main batati hoon, decide aap'` ✓
- paste-scene close: `'Yeh raha mera read. Brand ko kya kehna hai, aap decide karo.'` ✓ (replaces the "reply draft kar rahi hoon" line)
- `intro.line`: `'Main padhti aur yaad rakhti hoon.'` ✓
- `outro.bullets[1]`: `'Aapka floor, aapka faisla'` ✓
- The whole `draft` card / `Approve` tap / "Bhej diya" beat is deleted from both the `send` scene and the `find` scene (cold-email pitch), not just reworded. That's the correct fix — QA-TECH-0912's MEDIUM ("demo video still shows 'Drafted with Meera · approved by Riya'") and my own earlier CHANGES-REQUIRED note ("the demo film has a whole draft, approve and 'Sent' scene") are both closed by this, not papered over.

Verdict on this section: **matches exactly, nothing to change.**

## 2. Ananya's own lines — ruling each one

| Where | Ruling | Notes |
|---|---|---|
| `send` chapter — "Paisa secure · tab kaam shuru" / "Money secured · then work begins" / "पैसे secure · मग काम सुरू" | **APPROVE** | This is mine from the table (marked "yours"), confirmed landed identically in all three files. |
| `send` headerSub — "Paisa secure ho gaya" / "Funds secured" / "पैसे secure झाले" | **APPROVE** | Her judgment call, and it's the right one. It states a fact the very next card proves (`Funds secured: done`), it's plain language, and "secure"/"funds secured" is the house term — not "escrow". No drafting/sending implied. |
| paste-scene close — "Yeh raha mera read…" / "Here is my read on it…" / "हे माझं वाचन…" | **APPROVE** | Mine, confirmed landed. |
| Level 0 — "Main batati hoon, decide aap" / "I advise, you decide" / "मी सांगते, निर्णय तुमचा" | **APPROVE** | Mine, confirmed landed. |
| intro — "Main padhti aur yaad rakhti hoon." / "I read, and I remember." / "मी वाचते, आणि लक्षात ठेवते." | **APPROVE** | Mine, confirmed landed. |
| outro bullet 2 — "Aapka floor, aapka faisla" / "Your floor, your decision" / "तुमचा floor, तुमचा निर्णय" | **APPROVE** | Mine, confirmed landed. |
| `find` chapter — "…list Meera ki, faisla aapka" / "…the decision is yours" / "…निर्णय तुमचा" | **APPROVE** | Not requested, but the right instinct: the old "shabd aapke"/"the words are yours" line was written for a scene where Meera drafted a cold-email pitch in the creator's voice. Now that the draft/approve/sent beats are cut from `find`, "the words are yours" would dangle — there are no words of Meera's left to contrast against. "The decision is yours" is accurate to what the scene now shows (a brand list, nothing drafted). |

## 3. `intro.say` / `outro.say` — checked in all three files

Read `INTRO`/`OUTRO` in `script.ts`, and the inline `intro`/`outro` objects in
`script.en.ts` and `script.mr.ts`, end to end.

- `intro.say` (Hinglish, Devanagari): `"...मैं पढ़ती और याद रखती हूँ।"` — matches `intro.line`'s new "padhti" (reads), not the old "likhti" (writes). Correct.
- `intro.say` (English): identical to `line` since English has no separate voice script. `"...I read, and I remember."` Correct.
- `intro.say` (Marathi): `"...मी वाचते, आणि लक्षात ठेवते."` — matches. Correct.
- `outro.say` in all three: each one's "Aap approve karo, tab hi jaata hai" / "You approve, only then it goes" / "तुम्ही approve करता, तेव्हाच जातं" clause is replaced with the floor/decision line, consistently across all three languages. Correct — she updated the voice track, not just the on-screen bullet, everywhere.

No mismatch between what's on screen and what the voice says in any of the three locales.

## 4. `PasteBriefCard.tsx` button (~L230–249)

Read the component and traced the click handler into `creator-copilot.tsx`
(`askMeeraAboutBrief`, L168–195) and its tests.

Copy:
- Button: "Ask Meera about this brief" / "Meera se is brief ke baare mein poochho"
- Caption: "Opens Meera's chat with this brief. Meera doesn't draft or send anything — you decide." / "Meera ke chat mein yeh brief khul jaayega. Meera kuch likhti ya bhejti nahi — aap decide karte ho."

**APPROVE as final, not placeholder.** `RULINGS-U-0917.md` assigns this
wording to me by name ("Wording of the button and the prefilled text … nisha
… **nisha**"), so I'm finalizing it here rather than leaving it pending.
It's plain, it names the two things Meera doesn't do (draft, send) in the
same breath as the button that could imply she does, and it doesn't use
"escrow" or any payment-hold word.

Behavior check (not my lane to approve the code, but it has to match the
copy or the copy is a lie): clicking fills the chat composer with `Look at
brief {id}.` / `Brief {id} dekh lo.` and stops — `MeeraCopilotChat.test.tsx`
and `creator-copilot-paste-brief.test.tsx` both assert the prompt sits
unsent until the creator taps Send (`expectPromptUnsent`). Matches the
caption's claim. No note needed for Ananya here.

One gap, not blocking: `askMeeraCopy()` only branches on `language.startsWith('hi')`;
a Marathi-preference creator gets the English button/caption, not a Marathi
one, even though the marketing demo ships all three languages. This is a
product-language-scope question (does the live copilot support Marathi yet,
or only Hindi/English at this wave?), not a copy defect — I don't have
standing to answer it. Flagging for Ananya/Priya to confirm; if Marathi is
in scope for the copilot at this wave, send it back to me for the Marathi
line and I'll turn it around same-day.

## 5. Full script read-through — one surviving send promise found

Read `script.ts`, `script.en.ts`, and `script.mr.ts` end to end, every scene,
looking for anything besides what's listed above where Meera drafts, writes,
sends, or replies for the creator. Found one, present in all three
languages, in the `money` scene's first `wa` beat — untouched by this diff,
so it predates this round, but it's in scope of "B0 does neither" and no one
has ruled on it yet:

- Hinglish: `'Reel live hai. 24 ghante ka snapshot save kar liya, proof ke liye. 72 ghante ka reach brand ko bhej dungi.'`
- English: `'Your reel is live. I have saved the 24-hour snapshot as proof, and I will send the 72-hour reach to the brand.'`
- Marathi: `'तुमचा reel live आहे. चोवीस तासांचा snapshot proof म्हणून save केला आहे, आणि बहात्तर तासांचा reach मी brand ला पाठवेन.'`

"I will send the 72-hour reach to the brand" is a future first-person send
promise, and nothing later in the scene shows a 72-hour reach card — it's a
claim about an action that happens off-screen, exactly the shape of claim
Wave D was cut for. The snapshot card right after it already carries the
visibility fact we need ("Source: Instagram Graph API · brand ko bhi dikhta
hai" / "the brand sees this too" / "brand लाही दिसतं") — the brand can see
the numbers because the platform shows them, not because Meera sent
anything. That line does the honest job this one is trying to do.

**CHANGES REQUIRED — drop the "I will send…" clause.** Exact replacement
(cut the second sentence/clause only, everything else in the beat is
unchanged):

- Hinglish `text`: `'Reel live hai. 24 ghante ka snapshot save kar liya, proof ke liye.'`
  `say`: `'Reel live है। चौबीस घंटे का snapshot save कर लिया, proof के लिए।'`
- English `text`: `'Your reel is live. I have saved the 24-hour snapshot as proof.'`
- Marathi `text`: `'तुमचा reel live आहे. चोवीस तासांचा snapshot proof म्हणून save केला आहे.'`

Everything else in the `money` scene (the snapshot card, the payout
timeline, the closing "paisa bank pahunch gaya" line) is fine as-is — no
send/draft/reply claim in any of it, checked in all three languages.

## Verdict

**CHANGES REQUIRED — one line, three files.** Everything else in this
recheck (my five requested lines in `meera-for-creators.tsx`, my five
requested lines across the three script files, all seven of Ananya's own
judgment calls in the table, both `say` tracks, and the `PasteBriefCard`
button + caption) is **APPROVED as landed**, no further edits.

The one open item is the "72-hour reach" send line in the `money` scene
(§5 above) — replacement text given, three files, one clause each. Once
that's in, re-diff isn't necessary from my side; it's a clean cut with no
knock-on wording.

Not re-raised, per instructions — handled elsewhere:
- The 9 recorded voice files still speaking old lines (silenced pending Swapnil's call on paid regeneration).
- The consent-screen paragraph (with Kabir; see `NISHA-CONSENT-0917.md` in this same folder).

---

## Final (2026-09-17, second pass)

### 1. The "72-hour reach" cut — verbatim check

`git diff` on all three files against the one required change from §5 above.
Landed byte-for-byte:

- `src/remotion/script.ts` — `text: 'Reel live hai. 24 ghante ka snapshot save kar liya, proof ke liye.'`, `say: 'Reel live है। चौबीस घंटे का snapshot save कर लिया, proof के लिए।'` — matches.
- `src/remotion/script.en.ts` — `text: 'Your reel is live. I have saved the 24-hour snapshot as proof.'` — matches.
- `src/remotion/script.mr.ts` — `text: 'तुमचा reel live आहे. चोवीस तासांचा snapshot proof म्हणून save केला आहे.'` — matches.

Nothing else in the beat or the surrounding `money` scene changed. Confirmed.

### 2. Silenced-narration beats — read again as text-only

Read `src/remotion/timing.ts` to see the actual mechanism, not just the
copy: `STALE_VOICE_IDS` forces four ids silent — `intro`, `outro`,
`paste-5`, `money-0` (one set per id, applied across all three languages,
hence "12 stale `.wav` files" in the comment there — four ids, three
languages each, not three ids). `readableFloorFrames()` then raises each
silenced beat's on-screen hold to roughly 1s + 0.25s/word, on top of the
existing typewriter-based minimum. Checked all four:

- **intro** (`INTRO.line`/`.say`, all 3 locales): "Main padhti aur yaad rakhti hoon." / "I read, and I remember." / "मी वाचते, आणि लक्षात ठेवते." — short, complete, present-tense. Sits inside the intro screen's combined reading floor (~15–18 words across eyebrow/title/sub/line, ≈143–165 frames / 4.8–5.5s). Reads fine cold.
- **outro** (`OUTRO.bullets[1]`/`.say`, all 3 locales): "Aapka floor, aapka faisla" / "Your floor, your decision" / "तुमचा floor, तुमचा निर्णय" — a parallel-structure bullet, which is already a text-first form (a list item), not something that leaned on vocal delivery to land. If anything this one reads *better* silent than most spoken lines would. Fine.
- **paste-5** (paste scene's closing `meera` beat): "Yeh raha mera read. Brand ko kya kehna hai, aap decide karo." / "Here is my read on it. What you say to the brand is up to you." / "हे माझं वाचन. Brand ला काय सांगायचं, हे तुम्ही ठरवा." — two short, unambiguous sentences, chat-bubble register (this whole demo is styled as chat bubbles the viewer reads regardless of voice). No word here depends on tone or emphasis to be understood. Fine.
- **money-0** (money scene's first `wa` beat, post-cut): "Reel live hai. 24 ghante ka snapshot save kar liya, proof ke liye." / "Your reel is live. I have saved the 24-hour snapshot as proof." / "तुमचा reel live आहे. चोवीस तासांचा snapshot proof म्हणून save केला आहे." — also checked specifically that the cut left a complete sentence, not a fragment: it did, in all three languages, no dangling conjunction where "...aur..."/"...and..."/"...आणि..." used to continue into the removed clause. Fine.

None of the four rely on a voice actor's inflection, rhythm, or emphasis to be understood — each is a short, grammatically complete, plain-register sentence, and three of the four (`paste-5`, `money-0`, and the outro bullet) were already living inside a fundamentally text-first UI element (chat bubble / WhatsApp message / list item) that most viewers already read rather than listen to. **No rewording needed for silence.**

### 3. Kavya's code-side pass

Noted — claims tests, the silence mechanism (`STALE_VOICE_IDS` / `voiceFor`), and the timing floor (`readableFloorFrames`) are Kavya's lane and out of mine. Nothing in what I read contradicts what she reports; my read of `timing.ts` above was to understand what the four ids actually do on screen, not to re-audit the code.

### Final verdict: **APPROVE**

The one required change from the first pass landed verbatim in all three
files, and none of the four now-silent lines need rewording to work as
text-only. U-4 is clear from Content on both the copy and the silencing.

— Nisha
