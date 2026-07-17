# MEMO: AI B-Roll Product — CEO & CFO Review

**Date:** 10 July 2026
**To:** Swapnil Maruti (CEO)
**From:** Rohan Sharma (CFO)
**Re:** Proposed product — user uploads 1-min video, AI keeps original voice, generates matched B-roll + graphics. Split-screen raw/edited UI with chat editing. Claude Sonnet 5 writes prompts → Higgsfield MCP → Gemini Omni.
**Status:** 🟡 **CONDITIONAL GO — with three mandatory changes**

---

## Executive summary

This plan is **much better than the last one.** It sidesteps the fatal flaw in the previous stack: Claude never has to watch video. It reads a transcript and writes prompts. Text in, text out. That is Claude's home turf.

Three things are true at once:

1. ✅ **The pipeline is real and buildable today.** Higgsfield shipped an official hosted MCP on 30 April 2026 at `mcp.higgsfield.ai/mcp`. It exposes 30+ models (Sora 2, Veo 3.1, Kling 3.0, Seedance 2.0), does 15s video, 4K images, character consistency via Soul training, and it explicitly supports Claude Cowork and Claude Code. No API keys to manage. You can prototype this next week.

2. ⚠️ **Two funded incumbents already ship this product.** OpusClip's AI B-Roll does contextual B-roll insertion in "1 click, under 1 minute." Submagic does captions + contextual B-roll + auto-zoom and claims 4M+ registered users and 5,000–10,000 signups a day in early 2026. Both start at **$12–$20/month.**

3. 🔴 **At $12–20/month you would lose money on the second video.** Our COGS is $4–$10 per video. Their pricing is not a price we can match. See Rohan's section.

**The wedge exists, but it is not "AI B-roll."** It is one specific word in your example: *"AI beating the coder."*

---

# Part 1 — Swapnil (CEO): the business call

## What you actually have

OpusClip and Submagic insert B-roll from a **stock library**. They do lookup: transcript says "coding," fetch a stock clip of hands on a keyboard.

Your example is *"AI beating the coder."* **That shot does not exist in any stock library.** It never will. It is a concept, not a noun.

That is the entire product:

> **Stock B-roll answers "what object is being mentioned." Generated B-roll answers "what idea is being argued."**

Incumbents can retrieve. They cannot visualise an argument. That is a generation problem, and it is the one thing our stack does that theirs doesn't.

**Positioning:** not "add B-roll to your video." It's **"visualise your argument."** Opinion content, thought-leadership, explainers, founder POV videos, debate clips. Content where the value is the *claim*, not the *object*.

## Three mandatory changes to the plan

### 1. Kill the 10-second split. Split on meaning.

Cutting at a 10-second clock will land B-roll inserts mid-sentence. It will look broken and it will look cheap. Segment on the **transcript's sentence and idea boundaries** — Claude reads the transcript, finds where each claim starts and ends, and places inserts there. Variable length, 2–6 seconds, wherever the idea lives.

A 10s clock is an engineering convenience. It is a product defect.

### 2. Do not use Gemini Omni for delivery.

Omni Flash is **capped at 10 seconds and 720p. No 1080p. No 4K.** The user is uploading their own footage — almost certainly 1080p. Cutting 720p generated B-roll into 1080p source footage is instantly visible and instantly cheap-looking. It kills the product.

**Use Higgsfield MCP for delivery** (15s, higher res, Soul character consistency). Keep Omni for **cheap 720p previews** inside the chat loop — that's where its $0.10/sec and conversational editing actually earn their place. Preview on Omni, deliver on Higgsfield.

### 3. Make Claude a *router*, not just a prompt writer.

This is the change that makes the unit economics work, so read it twice.

For every insert, Claude classifies:

| Claim type | Example | Source | Cost |
|---|---|---|---|
| **Literal** | "developers type code all day" | Stock library | ~$0 |
| **Conceptual** | "AI will end the coder's job" | **Generate** | ~$1.30 |
| **Data / structural** | "60% of juniors say…" | Motion graphic template | ~$0 |

Most inserts in a 1-minute video are literal or data. Only 2–3 are genuinely conceptual. Generating all 8 costs $8.36/video. Generating only the 2–3 that *need* it costs **$2.16–$3.20/video.**

**Claude's real job is knowing which shots deserve to be generated.** That classification is free. The generation is not. That decision is the margin.

## Pricing call

**Do not sell a $15/month subscription.** You will be underwater immediately and you will be competing on the exact axis where Submagic's 4M users and OpusClip's funding win.

Sell **per-video, or as an agency retainer**, at **$30–40 per finished minute.** At $2.14 COGS that's a **94% gross margin.** Sell it to brands and founders who need their *argument* visualised, not creators who need stock clips.

This also fits Sage Digital's existing book — our export clients (spices, textiles, handicrafts) need exactly this: founder-to-camera videos with generated visuals of process, origin, and craft that no stock library carries.

## Kill criteria

Kill it if, after the 3-week spike:

- Claude's literal-vs-conceptual routing is wrong **> 25%** of the time — the margin evaporates
- Generated B-roll needs **> 2.5 retries average** to be usable — COGS doubles
- Higgsfield Soul cannot hold visual consistency across the 2–3 inserts in a single video — the video looks like three different videos
- OpusClip or Submagic ship generated B-roll first — **assume a 12-month window, not 36**

---

# Part 2 — Rohan (CFO): the numbers

## The budget problem, stated plainly

**Current company fixed cost: $133/month.** My approval ceiling is $50 without your signature.

The plan as written does not fit inside $133/month. It doesn't fit inside $233. This needs your signature before Arjun assigns a single task.

## COGS per finished 1-minute video

Assumes 8 inserts × ~4 seconds = 32 seconds of B-roll.

| Line item | Cost |
|---|---|
| Claude Sonnet 5 — transcript → plan → prompts, 5 chat turns | **$0.10** |
| Whisper transcription (Groq) | ~$0.001 |
| Gemini Omni preview pass, 720p | $3.20 |
| Higgsfield 1080p delivery, all 8 generated, 2× retry | $8.26 |
| ffmpeg composite / render | compute only |

**Naive plan (generate everything): ~$8.36–$9.90 per video.**
**Routed plan (generate 2–3 conceptual only): ~$2.16–$3.20 per video.**

Note the shape: **Claude is 1–5% of COGS. Generation is 95%+.** Same as the last report. The intelligence is nearly free; the pixels are the whole bill. Every dollar of margin in this business comes from *not generating* something.

## Subscription throughput — the hard ceiling

Higgsfield credits are prepaid. At 1080p (~3 credits/sec):

| Plan | Price | Credits | Videos/mo (all-generated, 2× retry) |
|---|---|---|---|
| Plus | $49 | 1,000 | **5** |
| Ultra | $129 | 3,000 | **15** |
| Ultra (annual) | $99 | 3,000 | **15** |

**Fifteen videos a month for $129.** That is the ceiling on the naive plan, and it consumes essentially our entire company budget. With the routing change (2–3 inserts), the same $129 buys roughly **50–60 videos/month.**

Routing isn't an optimisation. It's the difference between a hobby and a business.

## Margin scenarios

| COGS | Price | Gross margin | Gross per video |
|---|---|---|---|
| $9.90 | $12 (match Submagic) | **17%** | $2.10 |
| $9.90 | $35 | 72% | $25.10 |
| $2.14 | $20 | 89% | $17.86 |
| **$2.14** | **$35** | **94%** | **$32.86** |

Row 1 is why we cannot price like Submagic. At $12/video — and they charge $12 a *month*, not per video — we make $2.10 and one heavy user bankrupts the plan.

## Budget request

To run the 3-week spike I need your signature on:

| Item | Cost | Note |
|---|---|---|
| Higgsfield Ultra (monthly, not annual) | **$129** | Do not commit annual until the spike passes |
| Claude API — Sonnet 5 (spike usage) | ~$15 | Promo pricing $2/$10 per 1M, **expires 31 Aug 2026** |
| Gemini Omni API (preview passes) | ~$30 | Metered, capped |
| **Total incremental** | **~$174** | Over my $50 ceiling. **Escalating to you.** |

New monthly run-rate during spike: **$133 + $174 = $307.** Alert level would be **RED** against current budget. I am not authorised to approve this. It needs `wiki/decisions/budget-approvals.md` and your sign-off.

## Two cost risks I want on the record

1. **Sonnet 5's $2/$10 pricing is promotional and expires 31 Aug 2026.** Post-promo rates are unpublished. Our exposure is small (Claude is <5% of COGS) but model it.
2. **Higgsfield credit-per-clip rates come from third-party guides, not Higgsfield's own docs.** Before we commit $129, someone verifies actual credit burn on a real 1080p 4-second generation from our own account. **Day one task.**

---

# Part 3 — The pipeline (for Arjun)

```
User uploads 1-min video
  ↓ ffmpeg: demux — AUDIO TRACK IS NEVER TOUCHED
  ↓ Whisper → transcript + word-level timecodes
  ↓ Claude Sonnet 5:
      • segment on idea boundaries (NOT a 10s clock)
      • per segment → classify LITERAL | CONCEPTUAL | DATA
      • LITERAL  → stock query
      • DATA     → motion-graphic template + values
      • CONCEPTUAL → write generation prompt
      • emit EDL (edit decision list) as JSON
  ↓ Route:
      stock lookup        (free)
      template render     (free)
      Higgsfield MCP      (paid — conceptual only)
      Omni Flash          (paid — preview only, 720p)
  ↓ ffmpeg composite: B-roll over original video, original audio intact
  ↓ Split-screen UI: RAW | EDITED + chat
  ↓ Chat edit → Claude rewrites one EDL entry → regenerate ONE insert
```

**Why the original voice staying the same is good news:** you never touch the audio track. This is an overlay + EDL problem, not an audio generation problem. It's cheap, it's deterministic, and it removes the entire class of lip-sync and voice-clone failures. Do not let anyone talk you into "improving" the audio.

**Chat-edit design rule:** a chat turn must regenerate **exactly one insert**, never the whole video. One insert = ~$1.30. Whole video = ~$9. If the UI lets a user say "make it all more dramatic" and that triggers 8 regenerations, one frustrated user costs more than their subscription.

## Spike plan (3 weeks, before any budget beyond $174)

| Week | Deliverable | Owner | Gate |
|---|---|---|---|
| 1 | Verify real Higgsfield credit burn. Wire MCP. Generate 10 test inserts. | Dev | Actual $/insert within 20% of estimate |
| 2 | Claude routing: 20 transcripts → LITERAL/CONCEPTUAL/DATA. Score against a human editor. | Vikram + Kavya | **≥ 75% agreement** |
| 3 | End-to-end: 5 real 1-min videos. Measure retries, COGS, wall-clock. | Meera | **≤ 2.5 retries, ≤ $3.50/video** |

Miss any gate → stop. Report to Swapnil. Do not build the UI until week 3 passes — Ananya's split-screen is worthless if the routing doesn't hold.

---

## Bottom line

**Swapnil:** The plan works, but not as described. Fix the 10s split, drop Omni to preview-only, and make Claude route rather than just prompt. Then it's a 94%-margin product aimed at a segment the incumbents structurally cannot serve. Price it per-video at $30–40, never $15/month. Assume a 12-month window before OpusClip ships generated B-roll.

**Rohan:** COGS is $2.14/video if we route, $9.90 if we don't. Claude costs $0.10 of that — the intelligence is free, the pixels are the bill. The spike needs **$174 incremental**, which is over my ceiling and would put us at RED. **I need your signature before Arjun starts.**

---

## Assumptions and open items

- 8 inserts × 4s is an **estimate**. Real insert density on 1-min opinion content is unmeasured. Week 3 measures it.
- 2× retry ratio is **modelled, not measured**.
- Higgsfield credit rates are from third-party guides. **Verify on our own account, day one.**
- Higgsfield MCP tier gating: hosted MCP authenticates via Higgsfield account. Whether MCP access requires Plus/Ultra specifically is **not confirmed**. Check before committing $129.
- Sonnet 5 pricing promotional through 31 Aug 2026.
- Submagic / OpusClip pricing is per-month subscription; exact fair-use caps not verified.

---

## Sources

- [Higgsfield MCP — official](https://higgsfield.ai/mcp)
- [Higgsfield Cloud API](https://cloud.higgsfield.ai/)
- [Higgsfield MCP for Claude Code — TECHSY](https://techsy.io/en/blog/higgsfield-mcp-claude-code)
- [Higgsfield MCP guide — MCP.Directory](https://mcp.directory/blog/higgsfield-mcp-guide)
- [Higgsfield pricing](https://higgsfield.ai/pricing)
- [OpusClip AI B-Roll](https://www.opus.pro/ai-b-roll)
- [Submagic AI B-Roll Generator](https://www.submagic.co/features/b-roll)
- [Opus Clip + Descript + Submagic comparison — Forasoft](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/opus-clip-descript-submagic-captions-ai-video-editor-tools-2026)
- [Gemini Developer API pricing — Google](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini Omni Flash pricing — eesel AI](https://www.eesel.ai/blog/gemini-omni-flash-pricing)
- [Claude Sonnet 5 pricing — Eden AI](https://www.edenai.co/post/claude-sonnet-5-pricing-benchmarks-api-access)
- [Claude models overview — Claude Platform Docs](https://platform.claude.com/docs/en/about-claude/models/overview)
