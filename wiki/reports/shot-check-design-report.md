# Shot Check: coaching a phone before the take, not after it

> **Internal design report — no measured results yet.**
> Influora · creator platform engineering · 23 September 2026
> Subject: pre-capture guidance for phone-shot vertical video (Reels, YouTube Shorts)
> Status: Level 1 and Level 2 in build on `feature/creator-content-knowledge`
> Audience: Swapnil Maruti (CEO), Priya (CTO), build team
> Web version: the same report is published as an Artifact for reading and printing.

## Abstract

India's nano and micro creators shoot on one phone, alone, with no lights and no crew. The
dominant hidden cost in their week is not ideas — our platform already supplies those — but the
*reshoot*: they film, watch it back, find it dark or crooked or framed wrong, and set up again.
We describe a two-level system that moves that feedback to before the take. Level 1 measures the
camera preview on the device itself — brightness, focus, subject framing, tilt, background clutter,
and the gap between voice and room noise — at four samples a second, with no network and no cost,
and speaks its corrections aloud so a creator standing away from the phone can hear them. Level 2
sends a single still, on an explicit tap, to a vision model that names up to three specific fixes a
meter cannot see, such as which object behind the shoulder pulls the eye. Level 1's thresholds are
craft heuristics, not learned from our own creators; we state them explicitly and describe the
calibration that would replace them. We report design, cost and privacy decisions in full, and an
evaluation protocol with pre-declared success and kill criteria. **We report no results: nothing
here has been measured with creators, and the system has not run on a real handset.**

## 1. The problem, stated concretely

A creator with 8,000 followers films a 35-second recipe reel on a mid-range Android phone. She
props it against a jar, steps back to the stove, and records. Watching it back she finds her face
darker than the wall behind her, because the window was behind her; the phone leaned six degrees;
and in the close-up her hands left the frame. She films the whole thing again. That second setup
costs more of her week than choosing the idea did.

Three properties make this addressable. The fault is usually visible *before* the take. It is
usually one of a small set of recurring faults. And the phone already holds every signal needed to
detect most of them, in the preview frame it is showing anyway.

The constraint that shapes the whole design: the creator is not holding the phone. They are two to
three metres away, standing where they will perform, unable to read a 5-inch screen. Any guidance
that only appears as text has already failed for the case it was built for.

## 2. What this plugs into

Shot Check is not standalone. Our creator assistant already produces a shot-by-shot script:
numbered beats, each with a named camera angle, a duration, a spoken line and on-screen text, drawn
from a 109-row content dataset of structures, hooks, camera angles and narrative principles. It
also produces a seven-day plan with dated festival days and, where Instagram is connected, the
day-parts that creator's own posts have performed best in.

That matters for one reason: **the correct advice depends on the shot.** "Come closer" is right for
an extreme close-up and wrong for a wide shot; "lift the phone so your eyes are higher" is nonsense
for an overhead of the hands, where no face should be in frame at all. Because the script declares
the angle per beat, Shot Check can check the right thing at the right moment.

## 3. Design principles

1. **Arithmetic on the device; judgement in the model.** Anything computable from pixels is
   computed locally, free and instantly. Only what needs interpretation goes to a model, once, on
   request.
2. **No media leaves the device unless the creator taps.** Level 1 uploads nothing. Level 2 sends
   one still, holds it in memory, and never writes it anywhere.
3. **Advice, never verdicts.** "The light is behind you" is a fact about the frame. "This will get
   more views" is a claim we cannot support and do not make.
4. **Every reading is falsifiable.** Each measure is a named function of the frame with a stated
   threshold, unit-tested against synthetic inputs.
5. **Degrade, never blank.** No camera permission, no face detector, no internet: the screen still
   gives the same advice as text.

## 4. Level 1: measuring the preview

Each sample draws the preview into an offscreen canvas at 160x90 and runs six pure functions over
the pixel buffer. At four samples a second that is about 57,600 pixels of work per second —
trivial for a low-end phone, deliberately so.

### 4.1 Brightness

```
Y  = 0.2126*R + 0.7152*G + 0.0722*B        (Rec. 709 luma)
L  = mean(Y) over all sampled pixels, scaled to 0..100
Lf = mean(Y) inside the subject box, when one is known

dark          if  L  < 22
backlit       if  L >= 22  and  (L - Lf) > 18
ok            otherwise
```

The second test is the one that matters: the frame is bright enough while the subject is far
darker — the signature of a window behind the creator. A single average calls that frame fine.

### 4.2 Focus

```
G   = grayscale(frame)
Lap = G convolved with [[0,1,0],[1,-4,1],[0,1,0]]
S   = variance(Lap)                        (focus measure)

unknown   if  L < 22          <- never judged in the dark
soft      if  S < 90
ok        otherwise
```

Variance of the Laplacian is a standard focus measure [1]. The guard matters: in low light, sensor
noise raises high-frequency energy and an out-of-focus dark frame scores as sharp. Below the
darkness threshold we report `unknown` and say "too dark to tell". Where a subject box is known the
measure is taken inside it, so a sharp background behind a soft face reads correctly as soft.

### 4.3 Framing

Where the browser exposes a face detector we take the face box; where it does not we fall back to
subject-agnostic targets and say so. Framing is two judgements: vertical placement and distance.

| Shot type | Expects a face | Face height (frame %) | Eye line from top | Typical advice |
|---|---|---|---|---|
| Extreme close-up | yes, or the subject | 45–85 | 25–45% | come closer / too close |
| Medium | yes | 18–35 | 20–38% | lift the phone / step back |
| Wide | optional | 6–18 | 15–35% | step into the middle |
| Overhead, hands | **no** | — | — | centre the work surface / phone not level |

*Table 1 — targets per shot type. The script supplies the shot type; with no script the medium
target is used.*

### 4.4 Tilt

From the device orientation sensor, not the image: rotation about the viewing axis, in degrees,
flagged beyond ±3°. Where the sensor is unavailable the reading is `unknown` — never 0, which would
silently assert a level phone.

### 4.5 Background clutter

```
E = |Sobel_x(G)| + |Sobel_y(G)|
C = mean(E over pixels OUTSIDE the subject box), scaled 0..100

busy   if  C > 46
ok     otherwise
```

A proxy, and a weak one: a patterned wall scores as clutter while a plain wall with one distracting
object may not. It is phrased as a hint ("a plainer background reads better"), and it is precisely
the judgement Level 2 exists to make properly.

### 4.6 Voice against the room

```
rms(dBFS) from a WebAudio analyser on the same stream
noise_floor = 10th percentile of rms over the last 3 s
gap         = rms_speaking - noise_floor

far    if  rms < -38 dBFS
noisy  if  gap < 12 dB
ok     otherwise
```

Loudness alone is the wrong measure; intelligibility tracks the margin between voice and
background. A fan or traffic raises the floor, and the honest advice is "the room is noisy, come
closer to the phone" rather than "speak up".

> **Where these numbers come from.** Every threshold above is a craft heuristic — standard framing
> convention, established focus measures, and the signal margins used in speech work — *not* a
> value learned from our own creators' results. They live as constants in one file, with that
> caveat in the code. Section 9 describes the calibration that would replace them.

## 5. Speaking the corrections

The creator is away from the phone, so guidance is spoken. Three decisions follow from cost and
latency rather than preference:

- **On-device speech, not our paid voice service.** The assistant's chat voice uses a commercial
  text-to-speech API; live cues through it would mean a billed network call every few seconds, to a
  creator who may have weak signal. The browser's built-in speech synthesis is free, immediate,
  works offline and supports Hindi on most Android devices. The paid service stays where quality
  matters and there is one call per reply.
- **Speak on change, not on schedule.** One utterance only when a verdict changes, at most one
  every three seconds: "thoda peeche jao... haan, wahin ruko", not a loop.
- **Language follows the creator's setting** — Hindi, Hinglish or Indian English — the same setting
  the assistant already uses for text.

## 6. Level 2: one frame, three fixes

A meter cannot say *which* object is distracting. A vision model can. The trade is latency and
cost, so the call is deliberate, never automatic.

```
capture   -> one still, downscaled to <=800 px wide, JPEG q~0.7  (~100-150 KB)
transport -> HTTPS multipart, with the creator's category and current shot label
gate      -> consent check -> spend reservation against the daily cap
model     -> one image block + a constrained prompt
parse     -> strict JSON: fixes[<=3], settings[<=3], ok[<=3]; fences stripped;
             unparseable -> one plain "try again" line, never a 500
discard   -> bytes held in memory only; never written, never logged
```

The prompt is constrained by policy as much as by task. It may not comment on appearance, body,
clothing or skin; may not guess age, gender or identity; must state plainly when more than one
person is visible and then analyse only the creator; may invent no numbers and promise no outcomes;
and must say a photo is unusable rather than fabricate fixes for it.

## 7. Cost model

| Item | Per use | 1,000 active creators / month | Notes |
|---|---|---|---|
| Level 1 meter | ₹0 | ₹0 | On-device arithmetic; no network |
| Spoken cues | ₹0 | ₹0 | Browser speech, not the paid API |
| Level 2 frame check | ≈₹0.50–1.00 | ≈₹3,000–6,000 | At 6 checks per active creator per month |
| Storage | ₹0 | ₹0 | No images, no video, by design |

*Table 2 — running cost. Level 2 assumes one 800 px image plus a short reply on our current chat
model at list prices, bounded above by the existing per-workspace daily spend cap.*

A third level — a model watching the video stream continuously — would cost roughly ₹30–60 per
minute filmed and still lag behind the action. Rejected on both grounds; Level 1 is what "live" can
honestly mean at this price.

## 8. Privacy, consent and the rules we will not bend

- **No imagery is stored at either level.** Level 1 uploads nothing; Level 2's frame lives in
  memory for one request.
- **No video, ever, on our servers** — even with an in-app recorder later, the file stays in the
  creator's own gallery.
- **Camera is a new consent category** under India's data protection regime [3], so it needs its
  own line in the existing consent gate and in the privacy policy before release, not after.
- **No appearance judgements.** The model may never comment on body, clothing or skin, or guess who
  someone is.
- **Other people are named, not analysed.** If a second person is in frame the system says so and
  stops; our published platform data policy forbids profiling anyone other than the creator.
- **What we log is the fact and the readings** — that a check ran, and the numeric verdicts. That
  is what tells us whether reshoots fall, and it contains no imagery.

## 9. Evaluation plan

Nothing is measured yet. The protocol is declared in advance so the result cannot be chosen after
the fact.

| Question | Measure | Success | Kill |
|---|---|---|---|
| Does it remove the reshoot? | Self-reported takes per published video, asked weekly in-product | Median takes falls by >=1 | No change after 4 weeks |
| Is it used more than once? | Share of creators running a second check within 14 days | >=40% | <15% |
| Is the advice acted on? | Share of sessions where a red reading turns green before recording | >=60% | <25% |
| Is Level 2 worth its cost? | Frame checks per creator, and repeat rate after the first | >=2 per active creator per month | <0.5 |
| Does output quality improve? | Brand revision and rejection rate, users vs non-users | Any reliable reduction | No difference at n>=200 deals |

*Table 3 — pre-declared metrics. Baselines must be collected before the feature is switched on.*

**Threshold calibration.** Readings are logged per session with the creator's own later performance
on the resulting post, where Instagram is connected. With a few thousand sessions we can compare
each reading's distribution on posts above and below that creator's own median, and move the
thresholds to where they separate the two — replacing craft heuristics with our own evidence, per
category. Until then the constants stand and are labelled as guesses.

## 10. Limitations and threats to validity

- **Unproven on hardware.** Browser test environments have no camera; automated tests prove the
  maths and the refusal paths, not the device path. Real-handset testing on at least one low-end
  Android and one iPhone is a release gate.
- **Platform asymmetry.** Android browsers expose camera controls (brightness, focus, zoom) that
  iOS Safari largely does not, so on iPhone the settings help is advice only. A web page can never
  change the settings of the phone's own camera app or of Instagram.
- **Face detection is not universal.** Where the browser lacks it we ship no library, so framing
  advice degrades to subject-agnostic targets. Megabytes of on-device models before anyone has used
  the feature is a cost we refused.
- **Clutter is a proxy.** Edge density is not distraction; it has a known failure mode (patterned
  walls).
- **Self-report is soft.** "Takes per video" is what we can collect without touching footage, and
  it carries recall bias; deliverable revisions are the slower, harder check.
- **Self-selection.** Creators who choose a shoot-check tool are already trying to improve, which
  will flatter any naive before/after comparison.
- **Model variance.** Level 2 depends on a model's judgement of a photograph, which is neither
  deterministic nor evaluated here. A golden set of annotated frames with expected fixes is the
  missing piece.

## 11. What we will claim, and when

Until the numbers in Table 3 exist, the honest description is: **a fast, private, on-device
checklist that speaks, plus an optional second opinion from a vision model.** Not "AI that makes
your videos better". The first claim is defensible today; the second needs the evaluation.

## 12. Background

Engineering, not a literature review; these are the sources the method leans on directly.

1. J. L. Pech-Pacheco, G. Cristóbal, J. Chamorro-Martínez, J. Fernández-Valdivia, "Diatom
   autofocusing in brightfield microscopy: a comparative study", *Proc. ICPR*, 2000 — the
   variance-of-Laplacian focus measure used in §4.2.
2. ITU-R Recommendation BT.709 — the luma coefficients used in §4.1.
3. Digital Personal Data Protection Act, 2023 (India) — consent and purpose limitation obligations
   shaping §8.
4. Influora internal: the creator content dataset (109 rows) and the shot-by-shot script format
   that supplies the shot targets in Table 1; the platform data policy clause forbidding profiling
   of anyone other than the creator.

---

*No creator has used this system and no result in this document has been measured.*
