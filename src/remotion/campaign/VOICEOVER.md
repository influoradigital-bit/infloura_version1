# "How to create a campaign" — voiceover

Composition: `CampaignDemo` (1920×1080, 30fps, 2834 frames ≈ **94.5s**).
Direction: Tejas (CMO). Copy: Ishaan. Narration: Sarvam `bulbul:v3`, speaker **neha**,
`en-IN`, pace 1.0, temperature 0.6 — the same voice and settings as the Meera demo
(`scripts/gen-meera-voice.ts`), so both videos sound like the same product.

## Regenerating

```bash
SARVAM_API_KEY=... npx vite-node scripts/gen-campaign-voice.ts --force
```

Clips land in `public/campaign-voice/<scene id>.wav` and the lengths are written to
`src/remotion/campaign/voice-manifest.ts`. Edit a line in `script.ts`, then regenerate
just that one with `--only <scene id>` (drop `--force` to reuse the clips you have).

## Why the video got longer

The copy was written to ~150 wpm, which put it at 73s. Sarvam's Indian-English voice
reads slower than that, so the real narration is 85s of speech. `scene-meta.ts` treats
the authored `seconds` in `script.ts` as a **floor** and stretches any scene to
`10 frames lead-in + clip + 18 frames tail`. Without that, lines get cut off mid-word
at the scene boundary. Total went 75.5s → 94.5s.

To pull it back under 90s, shorten the copy in `script.ts` and regenerate — do not
shorten the scene durations, they are derived.

| # | Scene id | In / out | Spoken | Line |
|---|---|---|---|---|
| 1 | `hook` | 0:00–0:06.7 | 5.7s | Every campaign on Influora starts here, on the New Campaign screen. |
| 2 | `type` | 0:06.7–0:16.5 | 8.9s | First, pick a campaign type. An Open Campaign posts your brief publicly, so creators apply to you. |
| 3 | `basics-a` | 0:16.5–0:26 | 8.5s | On the Basics step, give your campaign a Title and a short Description: what you need, and who you are looking for. |
| 4 | `basics-b` | 0:26–0:37.1 | 10.2s | Fill in the End Brand Name and Category, then choose your Campaign Objectives, like Product Launch or Drive Sales. |
| 5 | `content` | 0:37.1–0:47.4 | 9.4s | Move to Content. Pick your Target Platforms, and the Content Types you want, from Reels to Static Posts. |
| 6 | `budget` | 0:47.4–0:57.3 | 9.0s | In Budget, set your Start and End Date, drag the Budget Range slider, and set your Maximum Collaborators. |
| 7 | `requirements` | 0:57.3–1:08.1 | 9.8s | On Requirements, list what creators must include, add your Campaign Hashtags, and describe your Target Audience. |
| 8 | `review` | 1:08.1–1:17 | 7.9s | The Review step shows everything at a glance, and suggests creators who match your campaign. |
| 9 | `publish` | 1:17–1:24.3 | 6.4s | Hit Publish Campaign, and your brief goes live for creators to apply to. |
| 10 | `outro` | 1:24.3–1:34.5 | 9.2s | When you hire, Secure Payments holds the funds until the work is delivered. Create your campaign on Influora today. |

## Accuracy notes

- **Scene 9 is deliberately scoped.** Publishing a campaign creates it and opens it to
  applications — it moves no money. The earlier draft said funds are secured at publish;
  that is not what the code does, so the Secure Payments promise was moved to the outro
  where it describes the hiring step instead.
- **Scene 8 says "suggests", not "hire from here".** The Suggested Creators card is
  informational: `campaign-form.tsx:1622` notes no selection is wired into submission.
- **"Escrow" appears nowhere.** Vocabulary is Secure Payments / secured funds.
- Every on-screen field label is the literal string from
  `src/components/brand/campaigns/campaign-form.tsx`. Only the demo brand
  ("Bloomveda") and its sample values are invented.
