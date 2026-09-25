# AI Review: Shoot coaching, answered from a creator's side

> Ash, 2026-09-25. Swapnil wrote 20 questions as a creator (beauty in a small Mumbai bedroom,
> food in a home kitchen, art at a desk). Every answer below is checked against the code on
> `release/0924` (`2eefc493`), not assumed.
>
> **Snapshot note (added 2026-09-25):** this review describes the code BEFORE the "Plan my shoot"
> build that followed it (coach question bank, grounded photo-check steps, tap answers). The
> photo check can now receive the planned shot as `shot_context`, but nothing in the app sends
> it yet: the Shoot Check page does not receive the script's shots until the shot plan is wired to it.

## How it works today (traced flow)

**Shoot Check photo check (Level 2).** The page `src/pages/creator-shoot-check.tsx` →
`ShootCheckPanel` grabs ONE frame from the live browser camera (front camera by default,
`facingMode = 'user'`, `useShootCheck.ts:132`), shrinks it to 800 px wide JPEG
(`capture-frame.ts:7-8`) → Java proxy `CreatorMeeraController#checkFrame` (1.5 MB cap, 35 s
timeout, 15 checks per creator per 60 s, `AuthRateLimitFilter.java:345`) adds the saved phone →
`influora-ai` `POST /ai/shoot-check/frame` (`app/routes/shoot_check.py`) → Claude (Sonnet 4.5,
`SHOOT_CHECK_MODEL`, max 1,024 output tokens) with the system prompt from
`app/prompt/frame_check.py` (rules + the whole shooting and placement knowledge, about 35k
characters) → reply parsed defensively into at most 3 `fixes`, 3 `settings`, 3 `ok` lines of at
most 220 characters.

**What the model is told about the shot:** only a free-text `shot_label` (untrusted) and the saved
phone. It is **not** told the script beat, the planned camera angle, the prop or the action.

**What happens to the photo:** it is never written to disk (a test guards this) and is logged as
a shape only; it is sent to Anthropic for the one call. Credits: **0 today** (metered, not
charged, `shoot_check_credit_cost_credits`). The monthly per-creator AI cap still applies.

**Meera chat** never sees the photo or its result: the frame check and the chat do not talk.

**Knowledge about actions:** `contextual_action` rows list actions per category (Beauty:
"Arrange products", "Apply a small amount"; Food: "Pour tea", "Mix ingredients"). Nothing says
which hand, where the prop sits in the frame, or when in the beat to use it.

## The 20 questions

Legend: ✅ works today · 🔧 possible with the change named · ❌ cannot, honestly.

### Where to sit, stand and put the phone

**1. One photo → exactly where to sit and where the tripod goes?**
✅ partly: the model sees the photo and the placement knowledge (window at 30-45°, fix the scene
before settings, left/right from your side), so it can say "turn so the window is on your left,
move the chair 30 cm toward it".
❌ exact distances: one photo has no depth, so "move 70 cm" is an estimate. It says "about".
🔧 **Two-photo flow:** photo 1 = the room from where the phone will stand (placement), photo 2 =
from the phone in position (framing). And a structured answer: `where_to_sit`, `phone_position`,
`light_side`, each with its reason. Today it is 3 free sentences.

**2. Window behind me: will it say "shift the table, face that wall"?**
✅ It can see a bright window behind you, and the rules tell it to change the geometry first
("move so the window is beside you"). 🔧 It only names furniture moves if it can see the
furniture. The two-photo flow fixes this: the room photo shows the table.

**3. Tiny 8×10 room, can't step back: realistic options?**
✅ partly: the knowledge has the small-room recipe (one large soft source + a white wall bounce,
reframe, move away from the wall where possible). 🔧 Only if it knows the room is small. Add the
"where" intake answer (bedroom, small) to the photo check, and a rule: "never suggest stepping
back more than the room allows; offer the 0.5x/1x reframe instead".

**4. Clutter (clothes on a chair, cables): what exactly to move, before I shoot?**
✅ Yes, if it is in the photo. This is what one photo does well: it names visible objects
("clothes on the chair behind your left shoulder"). The background-repair rules are already in
the prompt. ❌ Only what the 800 px photo shows; a cable hidden in shadow may be missed.

### Props and actions matched to the script

**5. "Apply the serum" at beat 3: which hand, where the bottle stays in frame, when to pick it up?**
🔧 Not today. The knowledge has the action ("Apply a small amount") but no rules for hand,
placement or timing, and the photo check does not know beat 3 exists. Needs (a) a small set of
prop/action rules (see roadmap) and (b) the beat sent to the photo check.
✅ Once those exist the model can do this well: "hold the bottle in your non-dominant hand at
chest height, label to camera, apply with your dominant hand after the line".

**6. Food: stir, then look up and speak. How many seconds, when to stop, where to look?**
🔧 Same gap. The delivery rules cover gestures on the stressed phrase, but not a "do-then-say"
action pattern. A rule set like "action first (2-3 s, no talking), stop, eyes to lens, say the
line" belongs in the new prop/action rows.

**7. Art: write a word while I speak. Which word, how big, where on the paper, pause while writing?**
🔧 The script already stresses one phrase per beat, so "write the stressed word" is a natural
rule. Size and placement ("large, in the upper third of the page, facing camera") need the same
new rows. The writing itself is timing: stop talking while the pen moves, then say it.

### Checking the set in one photo

**8. Cream label facing away: will it catch that?**
✅ Probably, if the jar is large enough in the photo; vision models read label orientation.
❌ Small print at 800 px may be unreadable. 🔧 Tell the model what to check: send the beat's prop
("serum bottle, label to camera") so it looks for it instead of hoping to notice.

**9. My hand covers the product: photo or video?**
✅ In the photo, if the photo is taken at that moment (hold the pose, then check).
❌ If it happens only while you move, one photo cannot see it; that needs a clip (the deferred
"Take Check").

**10. Close-up, prop out of frame: how does it know what's "in frame" from one photo?**
✅ The photo IS the camera view when it is taken from the phone in its final position: Shoot Check
grabs a frame from the live camera. ❌ But Shoot Check uses the **front** camera by default; if you
then film on the rear camera in the phone's own app, the frame differs. 🔧 Let the creator pick
the rear camera in Shoot Check, and say "take this photo from where the phone will record".

### Being systematic, not guessing

**11. What does it ask first, or does it guess my room, light and phone?**
Photo check: it asks nothing; it uses the photo, the saved phone and a free-text label.
Chat: the question-first "Plan my shoot" intake is agreed but **not built yet** (next step).
🔧 Build it, and pass the intake answers (where, light, on camera, sitting/walking) into the photo
check.

**12. How do I know it's based on my photo and not a guess?**
✅ partly: an unusable photo gets "too dark to judge" instead of generic advice (a rule), and a
broken reply falls back to "try again". ❌ There is no "what I saw" line. 🔧 Add two fields:
`what_i_see` (one line: "window behind you, bed on the left, phone at chest height") so you can
check it understood, and `cant_tell` (what one photo cannot show). A wrong `what_i_see` tells you
to retake the photo.

**13. Things it can't tell from a photo (audio, tripod stability): will it say so?**
✅ partly: rules say invent nothing and phrase settings as "if your camera app has…". There is no
explicit "audio cannot be judged from a photo" line. 🔧 Put it in `cant_tell`, and point audio
questions to Meera's audio notes (the `get_creator_knowledge` audio topic).

### Time, credits, privacy, language, phone

**14. 15 minutes before my baby wakes: plan + check in time?**
✅ A photo check takes seconds. 🔧 The intake must stay short: at most 5 taps and a "skip, just
give me the plan" option, which the persona already has for idea intake ("jaldi batao").

**15. Credits: how many photos before I can shoot?**
Today: **free** (0 credits, only metered), up to 15 checks a minute, inside your monthly AI cap.
🔧 When a price is set: charge per check, and make the re-check cheap by telling it what changed
("I moved the lamp; check again").

**16. My messy bedroom on a server?**
✅ The photo is never stored by Influora (a test guards it) and is logged by size only; it goes to
the AI provider for that one call. ❌ **Gap:** the privacy policy and the consent screen do not
mention the camera, microphone or voice at all. This must be fixed before promoting the feature.

**17. Hinglish: "yahan light acha nahi lag raha"?**
✅ partly: the photo check replies in the language of the label you typed. ❌ It cannot tell which
corner "yahan" means from words alone. 🔧 Let the creator tap a region on the photo (top-left,
behind me…) or pass the chat line into the check with the photo.

**18. My phone is a Redmi, not the saved OPPO.**
✅ It uses the SAVED phone. If you shoot on the Redmi, change "My phone" first. For any phone not
in the notes (only 5 OPPO models today) it gives advice that works on every phone and says "if
your camera app has Pro mode". 🔧 Research the top ~20 Indian phones (planned).

**19. Sitting → standing after the photo: new photo or recalculate?**
❌ Today: new photo; the check does not remember the last one. 🔧 Keep the last `what_i_see` for
the session and let the creator say "now standing": framing changes enough (headroom, background)
that a new photo is still the honest advice for close-ups.

**20. What it honestly cannot do, even with a good photo.**
Motion (shake, walking, a hand passing in front), timing (did the action land before the line),
sound (echo, fan noise), focus hunting, exposure changes while recording, colour shifts under
mixed light over time, exact distances. All need a test clip; that is the deferred "Take Check"
(frames + audio).

## Findings (ranked by impact vs effort)

**P1 · Tell the photo check what the shot is meant to be.**
Where: `frame_check.build_user_text`, `ShootCheckPanel` (sends only `shot_label`).
Issue: the model judges a photo with no idea of the beat, angle, prop or action, so it cannot check
"label to camera" or "prop in frame".
Fix: send a small structured beat: `{angle, action, prop, line_stress, where, light}` (from the
shot plan, all untrusted-wrapped), and add a rule: "check the planned prop and action first".
Gain: checks 5-10 become possible; fewer generic fixes.

**P1 · Structured, checkable answer.**
Where: `frame_check.py` response shape.
Fix: add `what_i_see` (1 line) and `cant_tell` (≤3), and split fixes into `move_you`,
`move_phone`, `move_light`, `settings` in that order (the placement rule, enforced by shape).
Gain: the creator can verify it understood (Q12), honest limits every time (Q13, Q20).

**P1 · Cache the frame-check system prompt.**
Where: `app/providers/claude.py` `complete_with_image` sends `system=[{"type":"text","text":...}]`
with no `cache_control`.
Issue: ~35k characters (~9k tokens) sent uncached on every photo: roughly ₹2.5-3 per check at
Sonnet input prices; the knowledge is identical every time.
Fix: add `cache_control` (1-hour TTL, like the chat's shared blocks). Gain: ~90% cheaper input on
repeat checks.

**P2 · Prop and action rules (new data, behind the lookup tool).**
Fix: a small researched set per category: prop, which hand, where in the frame (lower third,
label to camera), do-then-say timing, the angle change (close-up insert). Load behind
`get_creator_knowledge` as a `props_and_actions` topic (budget ruling: new knowledge goes
behind the tool).

**P2 · Rear camera + two-photo flow in Shoot Check.**
Fix: a camera switch, and "photo 1: the room, photo 2: from the phone's spot".

**P2 · Hand the photo result to the chat.**
Fix: after a check, Meera's next turn gets `what_i_see` + fixes as context, so "now standing" or
"yahan" can be answered without a new photo where honest.

## Data & training roadmap
- **Now:** a golden set of 20-30 real set-up photos (consented, varied rooms, skin tones, phones)
  with the correct `what_i_see` and fixes written by a person; run it on every prompt change.
  Log a thumbs up/down per fix (the flywheel).
- **Next:** the prop/action rules; few-shot examples from the best-rated real checks.
- **Later:** the Take Check (frames + audio) for everything a photo cannot show.

## Verdict
Today: **SHIP as-is for what it does** (a quick set-up check), with the privacy wording as a
must-fix before promotion. To answer Swapnil's creator questions 5-13 the check needs the P1s:
know the planned shot, answer in a checkable shape, and cache the prompt.
