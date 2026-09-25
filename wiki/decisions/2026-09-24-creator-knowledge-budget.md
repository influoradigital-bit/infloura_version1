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

## 2026-09-24 later: the lookup tool exists

Swapnil approved the lookup design the same day (PROMPT_VERSION `meera-2026.09.24.13`).

- `get_creator_knowledge` is a LOCAL creator tool: influora-ai's tool loop answers it from
  `LOOKUP_TEXT` in `content_knowledge.py`, never Spring, no Java change. It is offered on every
  creator turn (warn-only included, it reads no creator data) and never on a brand turn. It is
  kept out of `CREATOR_TOOL_NAMES` in its own `CREATOR_LOCAL_TOOL_NAMES`.
- Topics: `audio`, `moving_between_spots`, `delivery_examples`. The 12 `delivery_example` rows
  moved out of the always-sent block into `delivery_examples`; dataset 8's 42 audio and
  movement rows went in behind the tool only. The always-sent block ends with a short
  "More on request" list naming each topic.
- The budget now counts what is ALWAYS SENT, not the file: always-sent block under 125,000
  characters and at most 320 always-sent rows (295 of 349 today, about 99.6k characters);
  frame check still under 40,000 characters and unchanged (about 35.3k). The file may grow
  behind the tool. Tests: `tests/prompt/test_creator_lighting_placement.py`,
  `tests/prompt/test_creator_knowledge_lookup.py`, `tests/tools/test_creator_knowledge_tool.py`.
- `narrative_principle` and `camera_angle` (point 3's other candidates) are still always-sent.

## 2026-09-25: what moves next, and what never moves (Priya, lookup review)

Point 3 above named `camera_angle` as a candidate. Moving it whole would break script writing:
every script beat names an angle, and the frame check depends on the placement vocabulary.

- **Move next, as lookup topics:** `narrative_principle`, `persuasion_principle` and
  `marketing_concept` (theory, only for "why does this work" questions); `brand_deal_practice`
  (a `brand_deals` topic); `platform_export_setting` and `night_video_setting` (settings
  questions); and the detail of `camera_angle` rows (the why and how of each angle).
- **Always-sent, never moved:** a one-line index of camera angle names (name plus when to use
  it), `storytelling_structure`, `structure_selection_rule`, `hook_template`,
  `length_guideline`, `category_playbook`, `contextual_action`, the delivery guardrails and
  rules, and the whole placement section (the frame check is one photo call with no tool loop).
- The 125,000-character, 320-row and 40,000-character frame-check caps stay. Every move lands
  together with a lookup-topic test like `tests/prompt/test_creator_knowledge_lookup.py`.
- A new topic touches only `LOOKUP_TOPICS`, its renderer and the frontend label: the persona's
  "Look it up first" rule and the tool description both follow the topic list, and
  `src/lib/meera-api.creator-tools-in-sync.test.ts` fails if the app's topic list drifts.
- A lookup is not an answer: `routes/chat.py` does not count a creator local tool's result as
  delivered output, so a turn whose answer then fails is refunded
  (`tests/routes/test_chat_money_path.py`).
- `get_creator_knowledge` accepts only `{"topic"}`; extra fields are refused.
