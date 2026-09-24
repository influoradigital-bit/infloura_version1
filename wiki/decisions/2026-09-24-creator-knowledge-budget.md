# Creator knowledge budget: v7 is the last always-sent addition

**Date:** 2026-09-24 · **Decided by:** Priya (CTO review of knowledge v7), accepted by Swapnil
**Applies to:** `influora-ai/app/prompt/content_knowledge.py`, `influora-ai/app/prompt/frame_check.py`

## Context

Meera's creator knowledge (`app/prompt/knowledge/video_content_concepts.jsonl`) is rendered in
full into a cached system block on every creator chat turn, and its shooting section is also
rendered into the Shoot Check frame-check prompt. Knowledge v7 (the lighting and positioning
guide) took the file from 240 to 315 rows. That passed the threshold set earlier the same day:
move to filtering or lookup past about 300 rows or about 45k tokens.

## Decision

1. v7 is accepted as always-sent: the block is about 108k characters (about 27-31k tokens),
   cached for an hour, and the lighting section reads as one instruction set.
2. It is the last always-sent addition. The tests fail the next one:
   - creator knowledge block: under 125,000 characters and at most 320 rows
     (`tests/prompt/test_creator_lighting_placement.py::test_knowledge_block_stays_under_the_size_budget`);
   - frame-check system prompt: under 40,000 characters (about 35k today), because it is sent
     uncached with every Shoot Check photo
     (`test_frame_check_prompt_stays_under_its_budget`).
3. Anything more needs the lookup design first: Meera calls a tool that returns only the rows a
   question needs. First candidates to move behind it (each serves one kind of question):
   `delivery_example`, `narrative_principle`, `camera_angle`.
4. The placement section ("Placing the creator, the phone and the light") stays always-sent,
   because the frame check is one photo call with no tool loop.

## Deferred, on purpose

- **Frame-check prompt trimming.** Leaving out "Why it works" (physics) and "Portrait lighting
  patterns" from the frame check would save about 1.8k tokens per photo. Not needed at today's
  size; the 40k-character cap stops it growing unnoticed.
- **Caching the frame-check system block** (`cache_control` on the image call).
- **One row per situation.** Older shooting rows (dataset 5) and the v7 placement rows still say
  some things twice (window behind you, mixed light). They now agree; merging them into one row
  each is a later clean-up.
- **Photographer terms in dataset 7 rows** ("key", "axis", "negative fill", "practical").
  Meera is told to speak plainly; rewording the rows is a later clean-up.
