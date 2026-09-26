"""Runtime configuration for the Meera AI reasoner service.

Loads everything from environment variables (a secrets manager in prod injects these
as env vars; locally `.env` via `.env.example` as a template). This module holds NO
real secrets — only the shape of what's required and safe non-secret defaults.

Kabir guardrail #6: this service's only secrets are the Claude / Gemini / Sarvam API
keys plus the internal HMAC key and the Spring JWKS URL. It never holds Razorpay or
DB credentials — blast-radius isolation from the money core.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from functools import lru_cache


def _get_bool(name: str, default: bool) -> bool:
    val = os.getenv(name)
    if val is None:
        return default
    return val.strip().lower() in ("1", "true", "yes", "on")


def _get_float(name: str, default: float) -> float:
    val = os.getenv(name)
    if val is None or val == "":
        return default
    try:
        return float(val)
    except ValueError:
        return default


def _get_int(name: str, default: int) -> int:
    val = os.getenv(name)
    if val is None or val == "":
        return default
    try:
        return int(val)
    except ValueError:
        return default


def _get_optional_float(name: str) -> float | None:
    """Like `_get_float`, but returns None (not a numeric default) when the
    env var is unset/empty -- used for opt-in thresholds where "unset" and
    "0" must be distinguishable (app.costs.gate's per-workspace hard cap,
    Kabir red-team FIX 3)."""
    val = os.getenv(name)
    if val is None or val == "":
        return None
    try:
        return float(val)
    except ValueError:
        return None


# EV-006: markers every committed placeholder/dev value in env.example carries. No real generated
# key contains one. Mirrors SecretsStartupValidator.PLACEHOLDER_SENTINELS on the Spring side.
_PLACEHOLDER_SENTINELS = ("replace_with_", "replace_me", "change-me", "change-in-production")


def _is_placeholder(value: str) -> bool:
    lower = value.strip().lower()
    return any(marker in lower for marker in _PLACEHOLDER_SENTINELS)


# ---------------------------------------------------------------------------
# Pinned model / prompt versions — this constant IS the P0 fix. Do not point
# this at gemini-2.0-flash again; that model id is deprecated.
# ---------------------------------------------------------------------------
# gemini-2.5-flash-lite was retired by Google ("no longer available", 404), which
# broke every Gemini call (analyze_site classify, trendspark, brand-safety). Moved
# to the current stable gemini-2.5-flash (verified 200 against the live API).
GEMINI_MODEL = "gemini-2.5-flash"
CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-5-20250929")
PROMPT_VERSION = "meera-2026.09.25.9"
# ^ .25.9 = merge of feat/meera-intelligence (slices 1-2) into the release line (2026-09-26,
# Swapnil: Meera must see the creator's own posts). Adds the Spring-backed read tool
# get_my_content_patterns (own best/weak posts and what beats their usual, thin-data guarded), the
# creator persona block "Their own results (what works for them)" with "Your own recommendations",
# the goal line in creator Block B, CREATOR_KNOWLEDGE_VERSION, and the write-back's
# metadata.recommendations (app/recommendations/record.py). Every release rule (.25.1-.25.8: Plan my
# shoot, photo checks, framing, reel_formats, ASCI) is kept. BRAND prompt unchanged.
#
# Previously (.25.8):
# ^ .25.8 = explainer Reel formats (Swapnil 2026-09-26): lookup-only reel_format and
# reel_format_rule rows (topic reel_formats, from the 10 transcribed Reels of a creator-education
# Reel audit pack; structure only, never the script); one creator persona line "Reel formats";
# the always-sent block gains only its "More on request" line. BRAND prompt unchanged.
#
# Previously (.25.7):
# ^ .25.7 = shoot guide grid (spec v2 2026-09-26, Phases 4-6, one bump for the whole branch).
# Knowledge: dataset 9's 161 framing and shot-planning rows (8 new types) join the file as
# lookup-only topics -- shot_planning plus one framing_<category> topic per composition category,
# with a fixed playbook-category -> topic table; the always-sent block is byte-identical except
# its "More on request" list (the new topics) and the Instagram Reels / YouTube Shorts export
# row, whose safe zones now match app/shoot/safe_zones.json in words (top 14%, text above the
# 65% line, 65-78% a short CTA on the left only, below 78% covered, about 4% sides, clear of the
# right-side buttons, check your app preview; was top 15% / bottom 25%). 161, not the spec's 162:
# dataset 9's TikTok platform_safe_zone_fact row is not taken (the file never names TikTok; CTO
# accepted 2026-09-26), six rows' "TikTok" reads "the app", and the five examples dataset 9
# credits to TikTok carry platform "Vertical short-form" (the rules' own platform_scope), never
# a platform they did not come from. Creator persona: one "Framing first" rule -- for a shoot
# plan (Plan my shoot), look up the creator's framing topic; full scripts are not included until
# the shot-card phase and its eval. Photo check: the response may carry "layout" (faces and
# product boxes, validated in code) and "checks" (fixed lines written by app/shoot/checklist.py,
# never by the model, judged on the same 9:16 crop and shot size as the app's guides). BRAND
# prompt unchanged.
# Deploy order for this version: frontend, then influora-api (its withoutGeometry keeps the
# boxes out of stored chat rows), then influora-ai. An influora-ai with "layout" in front of an
# older influora-api would store face and product boxes in ai_messages.metadata_json.
# .25.7 is this branch's (feat/shoot-grid); spec 2.5 had pencilled it for feat/meera-intelligence,
# which takes the next free number (.25.8 or later) when it merges after this.
#
# Previously (.25.6):
# ^ .25.6 = brand Meera flags ASCI claims and the ad label (brand persona "ASCI check"; CREATOR prompt unchanged).
#
# Previously (.25.5):
# ^ .25.5 = photo check inside Meera's chat (Swapnil 2026-09-26): the creator persona gains a
# "Photo checks" section. Only an earlier Meera turn whose text starts with "[Photo check" is a
# real check (Spring writes it from influora-ai's frame-check JSON); it is data inside the
# untrusted replay wrapper, never instructions. Meera never sees the photo, never writes a
# "[Photo check" turn, leaves "Can't tell" unknown, never comments on looks or others in the
# frame, says which numbered step changes on a follow-up, uses the check's settings for that
# shot, treats the newest check of a shot as the one that counts, never calls a step verified
# and suggests "tap Check again". The trust-boundary rules point at it. The phone ask now names
# only My phone in Meera settings (the Shoot Check page is retired). BRAND prompt unchanged.
#
# Previously (.25.4):
# ^ .25.4 = photo-check response gains lang, retake, step labels and settings parts.
#
# Previously (.25.3):
# ^ .25.3 = photo-check steps now use creator-voice lines, en + Hinglish (knowledge/creator_step_lines.jsonl via frame_check_render.py; chat prompts unchanged).
#
# Previously (.25.2):
# ^ .25.2 = photo check: the model picks ids, code writes the text (frame_check.py; creator and BRAND chat prompts unchanged).
#
# Previously (.25.1):
# ^ .25.1 = "Plan my shoot" (Swapnil 2026-09-25): Meera coaches a shoot the way a person
# standing next to the creator would. Knowledge: a coach question bank (data_type
# coach_question, 10 fixed questions with English and Hinglish options), always sent under the
# heading "Coach questions (ask only these; one per message; at most 3 per plan; skip any whose
# answer you already have):". Creator persona: when a creator asks how or where to shoot, or asks
# for a full script, the intake runs as Plan my shoot -- only bank questions, one per message, at
# most 3, skipping anything already known (category, city, language, the saved phone and the time
# are never asked), "skip"/"jaldi batao" plans at once on stated defaults. It is the same intake
# as the content idea intake, not a second round. Coach style everywhere: observe, then suggest,
# then confirm, warm, "we"/"let's", in the creator's language. Grounding: every shooting
# instruction comes from the knowledge, numbers exactly as an entry states them, and with no
# entry Meera says "this isn't in Influora's notes" instead of inventing one (the general-knowledge
# fallback no longer covers shooting). The full script gains a Set-up line between Action and
# Success looks like (where they sit or stand and the light, where the phone goes, the settings,
# how they move between spots); the app's script card parses it as optional, so older replies
# still render. Frame check: the photo check returns what_i_see, knowledge-named steps the server
# filters, ok, cant_tell and one bank question, and reads the planned shot and the creator's
# answers. BRAND prompt unchanged.
# Review round (still .25.1, unreleased): fixed the intake budget (at most 3 questions in total per shooting plan or full script, one bank question after an idea intake, "the time of day", plan capped at 5 steps) and the grounding checks (the general fallback excludes shooting instructions).
#
# Merged in by .25.9 -- feat/meera-intelligence's own notes. Its numbers (.25.2, .25.4) were that
# branch's only and never released; they are not the release line's .25.2/.25.4 above.
# ^ (intelligence) .25.4 = Meera intelligence v1, slice 2 (recommendation -> outcome record, 2026-09-25). The
# creator persona's "Their own results" block gains the "Your own recommendations" rule (count
# only the ones they posted, say how many it rests on, a recommendation they did not post is
# never their failure), get_my_content_patterns' model copy carries `followed_recommendations`
# (trusted, evidence.post_ids dropped), and content_knowledge.py gains CREATOR_KNOWLEDGE_VERSION
# (a hash of the rendered knowledge, stamped on each recorded recommendation). The creator
# write-back now carries metadata.recommendations from app/recommendations/record.py
# (deterministic parsers, no model call). .25.3 is already used, uncommitted, on
# feat/camera-knowledge-phone (C:/ph), so this branch skips to .25.4. BRAND prompt unchanged.
#
# Previously (intelligence .25.2):
# ^ (intelligence) .25.2 = Meera intelligence v1, slice 1 + goal memory (2026-09-25). A new Spring-backed read tool,
# get_my_content_patterns (the creator's own settled-post baseline, best/weak posts and what beats
# their usual, every claim with its sample size; the model copy drops evidence.post_ids), its
# capability bullet, the persona block "Their own results (what works for them)", and the goal line
# in creator Block B (content_goal / weekly_time_band / equipment / content_dislikes, fixed words).
# .25.1 is already taken on release/0924 (Plan my shoot), so this branch takes .25.2, not the
# spec's ".14" (the N counter restarts per date).
#
# Previously (.13):
# ^ .13 = the creator knowledge lookup tool (Swapnil 2026-09-24, the lookup design the .12 note
# below says anything past v7 needs). A new LOCAL creator tool, get_creator_knowledge, run
# in-process by the tool loop: no Spring route, no JWT on the wire, no Java change, and kept out
# of CREATOR_TOOL_NAMES (the Spring-backed list Java and the frontend sync against) in its own
# CREATOR_LOCAL_TOOL_NAMES. It takes one topic from three -- audio (mic choice, mic distance, lav
# placement by clothing, fan/AC/traffic noise, 10-second audio tests, phone audio features),
# moving_between_spots (cut vs walk-and-talk, walking setups, light and sound changes between
# spots, safety) and delivery_examples (worked stress/pause/pace examples for a line) -- and
# returns Influora's own notes for it; an unknown topic is an error naming the three. It is
# offered on EVERY creator turn, warn-only included (it reads no creator data, so tools_enabled
# does not gate it) and NEVER on a brand turn (the loop's per-turn offer gate refuses it there).
# The 12 delivery_example rows MOVED out of the always-sent knowledge block into the
# delivery_examples topic; the block now ends with a short "More on request" section naming each
# topic, and the creator persona adds a "Look it up first" rule: for those three subjects, call
# the tool before answering and answer from what it returns, never from general knowledge.
# Dataset 8's 42 audio and movement rows live ONLY behind the tool, at medium confidence, pending
# the source document. BRAND prompt unchanged.
#
# Previously (.12):
# ^ .12 = the go-live line's .11 merged with knowledge v7 (lighting and positioning). The
# v7 work was numbered .5 on its branch; it lands after .11 on release/0924, so it takes .12
# rather than going backwards. v7 as written on its branch:
# ^ bumped for knowledge v7 (dataset 7 + the creator lighting and positioning guide,
# 2026-09-24): 75 lighting and positioning rows (key-light angles from the face, window
# light, Indian home lights, phone height, background repair, portrait patterns, the Indian
# scene checklist, left/right and physics notes, plus the guide's fix order, looks by
# category, mixed light colours and sunset-to-night steps), rendered as "Placing the
# creator, the phone and the light" at the end of the shooting section, so the creator
# knowledge block and the frame check both carry it. The creator persona and the frame
# check now give instructions in one order -- creator, phone, light, then settings -- and
# say left/right from the creator's view as they face the phone; a look is picked by the
# creator's category and is a convention, not a promise. The midday lighting row is
# corrected to a shadow cue. Review fixes in the same version: older shooting rows that
# contradicted the placement order or the distance-not-lens rule are reworded (move first,
# then the telephoto; 1x by default for walking vlogs; one face light, never the ceiling
# alone), the duplicate Silhouette portrait pattern is dropped, a look's category fit is
# labelled Influora's suggestion, and the shooting heading no longer points at Phone notes
# the frame check does not carry. v7 is the last always-sent knowledge addition: the block
# must stay under 125,000 characters and 320 rows, and anything more needs the lookup
# design first (delivery_example, narrative_principle, camera_angle behind a lookup tool;
# the placement section stays always-sent). Round-3 review: night street/neon rows now 25fps
# at 1/50 (24fps/1/48 banded under 50Hz light), left/right is "always" the creator's own, the
# ceiling-light fallback adds a bounce, and caveats a heading carries are no longer repeated per
# row. The frame-check prompt is capped at 40,000 characters. Ruling and deferrals:
# wiki/decisions/2026-09-24-creator-knowledge-budget.md. BRAND prompt unchanged.
#
# Previously (.11):
# ^ go-live fixes on release/0924 (Swapnil 2026-09-24), from the live phone screenshots and Ash's
# review of the parallel launch merge: ONE script format (the rich Full script format, shown as a
# card; the old Phase C SCRIPT shape is gone, the REVIEW card shape stays), no markdown in it, a
# fixed start and end; one intake rule; hooks in the reply language; the comment ask goes to the
# caption; an imagined viewer is never their audience; Meera follows the ONE reason an audience /
# account line gives (a connected creator is never told to connect); engagement figures say their
# basis (per follower vs per reach); the "Your account" line (account insights). Knowledge: 8
# same-idea duplicates kept once, 5 hooks reworded (guide wording / claims about others),
# a borrowed 80% figure and "duets" removed. .11, not .5: .5-.9 stay free for the other session's
# next knowledge bumps, and .10 was used on the launch line.
#
# Previously (.24.4):
# ^ bumped for knowledge v6.1: the 12 delivery examples are rebuilt from the raw
# training_examples_seed.jsonl -- each shows the line split into parts ("/"), why the phrase is
# stressed, where to pause (a word, never seconds), the pace and an honesty note -- in the same
# Stress/Pause shape a full-script beat carries. Markdown bold stripped; ex09 stresses "stop"
# (its target) not a stray bold "form"; ex07's first-person result is marked "only when it is
# the creator's own experience". The 12 delivery rules carry their source numbers from
# delivery_rules.json (the reference list itself has not been supplied yet). BRAND unchanged.
#
# Previously (.24.3):
# ^ bumped for knowledge v6 (dataset 6, 2026-09-24): 5 outdoor-light rows (harsh midday sun,
# golden hour, cloudy, shade under trees, a daylight talking-head setting) and the 25 delivery
# rows (12 rules reworded as creator advice, 5 guardrails, 12 synthetic examples with TikTok /
# LinkedIn replaced by Reels / Shorts), rendered as "How to deliver the lines". The full-script
# beat gains "Stress:" and "Pause:" (a word, never seconds), and a persona rule answers delivery
# questions from the guardrails. Dataset 6's other 142 rows are dataset 5 and were not re-taken.
# BRAND prompt unchanged.
#
# Previously (.24.2):
# ^ bumped for camera knowledge v5 (dataset 5, 2026-09-24): 38 shooting rows join the
# knowledge file (settings by situation, night video, light, background, positioning,
# failure fixes, standing rules, India's 50Hz flicker, export, five checked OPPO phones),
# rendered as "Shooting and camera settings" in the creator knowledge block and, without
# the phone notes, into the frame-check system prompt. The creator's saved phone
# (`phone_model`, Spring's creator context and the frame-check form) is named in Block B
# and in the frame-check user message: a phone in our notes is described from our row, any
# other name is wrapped untrusted and gets any-phone advice. New persona rule: ask which
# phone once when the answer depends on it. BRAND prompt unchanged.
#
# Previously (.24.1):
# ^ bumped for the creator-AI audit fixes, lane B (audit 2026-09-24, ai.md H1 and M1-M10):
# the week plan now shows each festival on ONE day (its post day, with post_by the festival's
# own date) and the persona's week-plan rule says so; KEEP IT SHORT names the week plan as an
# exception next to the full script; the two hook templates that open with a comment ask are
# marked CTA RULE in the knowledge block (content_knowledge.py) so the ask moves to the last
# beat or the caption; the intake no longer offers "carousel" (short video only, said
# plainly); "saved as a draft, tap to send" now rides in the draft_reply bullet, rendered only
# when that tool is offered; with no tool giving the date Meera says she cannot see it and
# asks; and four knowledge rows no longer model invented statistics or invented first-person
# results, with a persona rule against scripting results the creator has not told her.
# BRAND prompt unchanged.
#
# Previously (.23.3):
# ^ bumped for the Level 2 "frame check" route (T-SHOOTCHECK-L2, one photo in,
# three fixes out -- app/routes/shoot_check.py, app/prompt/frame_check.py): a
# NEW cached system block (`build_system_prompt` in frame_check.py) reaches
# the model for the first time on this bump, and `prompt_version` is a
# component of `cache_key_for` (assembler.py) / every `ai_spend` log line, so
# without this bump the frame-check route's own turns would be logged/cached
# under a version number that, until now, only ever meant the chat/voice
# persona text. This route is independent of the chat Block A/B/C persona
# (it never touches assembler.py), but PROMPT_VERSION is service-global per
# this file's own rule above (see the .09.20.1 entry: "the version is global,
# so brand cache keys roll over too") -- one file's prompt content changing
# means the constant that names "which prompt text is live" must move,
# regardless of which route owns that content.
#
# Previously (.23.2):
# ^ bumped for plan_my_week (Swapnil 2026-09-23): a 7-day plan built from the server's dates,
# the festival calendar in app/planner/events.jsonl, today's topics and the creator's own
# posting pattern. The plan format forbids inventing a date or a festival day, and says the
# timing is a suggestion until their own posts support it. BRAND prompt unchanged.
#
# Previously (.23.1):
# ^ bumped for get_todays_topics (Swapnil 2026-09-23): admin-curated daily topics reach
# creator Meera through a tool, never the cached prompt. Two rails land with it, both from
# Ash's AI review (wiki/ai-review/daily-topics-week-plan-ai-review.md): the model is told it
# does NOT know the date and must read it from the tool, and topic text rides inside
# `<untrusted_editorial>` because it is typed straight into the database. BRAND prompt
# unchanged.
#
# Previously (.22.6):
#
# Previously, on release/0922 (.22.7):
# ^ bumped for release/0922, which merges feature/creator-content-knowledge (.22.1-.22.6)
# onto the 2026-09-21 go-live line (.21.3). Both sides are Swapnil rulings and both are
# kept: the go-live brand-deal rows, category playbooks, 30 book-derived rows and English
# hooks, AND v4's actions per category, lengths per goal and structure-selection rules.
# The one rule that could not be kept twice: the number rule. The .22.4 STATISTIC rule
# is used, because its own note says it replaces the broader NUMBER rule of .21.3.
# Three narrative/framework rows existed on both sides with different wording
# (write for one hyper-specific person, single target emotion per piece, Grab-Story-CTA);
# the go-live wording is kept because it carries the book-source attribution.
# BRAND prompt unchanged.
#
# Previously, on the go-live line (.21.3):
# ^ bumped for the go-live creator knowledge additions (Swapnil 2026-09-21):
# video_content_concepts.jsonl gained 5 brand_deal_practice rows (ad label,
# endorse only what you used, the Influora draft-to-payment flow, disclosed
# expertise for health/finance, disclosure even for genuine reviews), one
# category_playbook per creator category, 2 current platform rows and English
# versions of the 6 Hinglish hooks; rows 21, 22, 31 and 42 lost their invented
# number examples and "earlier research" leftovers. content_knowledge.py renders
# the playbooks and brand deals first and checks every playbook names a real
# structure and camera shot; creator_persona.py gained the "Use the category
# playbook" and "Brand-deal questions" rules. BRAND prompt text unchanged.
# Same bump also carries 30 book-derived rows (ideas paraphrased by Influora from
# Master Shots, The New Rules of Marketing & PR, Schroder's storytelling thesis
# and the Breezy Content guide): 10 camera_angle, 6 marketing_concept,
# 4 content_characteristic, 10 narrative_principle.
# Also: the CREATOR Block B metrics line "Reach (30 days)" is now "Avg reach per post" --
# the value was always CreatorMetric.avgReachPerPost, never a 30-day total (Priya 2026-09-21).
# And creator_persona.py gained the "Ask first, only what's unknown" rule: before growth or
# content advice, one goal question when the goal is not clear from the conversation, at most
# three questions, never re-asking what the context already holds (Swapnil 2026-09-21).
#
# Previously (.2): bumped for creator audience knowledge (feature/creator-content-knowledge,
#
# And on feature/creator-content-knowledge (.22.6 back to .22.1):
# ^ bumped for the script review (Swapnil 2026-09-22): one call to action
# matched to the goal, no absolute promises, hashtags optional (at most 2),
# and the plan names the on-camera action (with a hands-only fallback) and
# what success looks like. BRAND prompt unchanged.
#
# Previously (.22.5):
# ^ bumped for content knowledge v4 (Swapnil 2026-09-22): 109 entries (+11
# actions to film per category, +6 lengths per goal, +8 situation -> structure
# rules, the two undefined structures mapped onto PAS and Three-act) and the
# full script format in creator_persona.py (plain-text beats, only on request).
# BRAND prompt unchanged.
#
# Previously (.22.4):
# ^ bumped because the .22.3 numeric rule was too broad: it also restricted the
# creator's own durations ("sirf [duration] minute") and tip counts ("Ye
# [number] galtiyan"). It now restricts only invented statistics and claims
# about other people's results (STATISTIC RULE marker, has_statistic_slot in
# content_knowledge.py). BRAND prompt unchanged.
#
# Previously (.22.3):
# ^ bumped for content knowledge v3 (Swapnil 2026-09-22): 84 entries (17 from
# his v3 + 5 narrative principles), the no-invented-number rule made GENERIC
# (any [number]/[statistic]/[duration]-style slot, detected by slot name in
# content_knowledge.py), a never-suggest-TikTok rule (banned in India) and an
# ideas-only guard on outrage/status content. The .22.2 question-first intake
# is unchanged. BRAND prompt unchanged.
#
# Previously (.22.2):
# ^ bumped because Swapnil (2026-09-22) wants creator Meera to ask before she
# ideates, like a manager: the content section's "Content idea intake" asks at
# most 3 questions in one round (goal, format, past work; plus category when
# there are several), never asks what the context already holds, and has a
# skip / "jaldi batao" override that answers at once on defaults. It replaces
# .22.1's "do not ask them to pick a category first". BRAND prompt unchanged.
#
# Previously (.22.1):
# ^ bumped because creator Meera refused a content-idea request (Swapnil
# 2026-09-22: "content ideas nahi deti"). creator_persona.py's opening now says
# content help is part of her job with a no-refusal rule, and the content section
# gained the audience-not-available-still-answer, several-categories and
# no-follower-count-put-down rules. BRAND prompt text unchanged.
#
# Previously (.21.2):
# ^ bumped for creator audience knowledge (feature/creator-content-knowledge,
# Swapnil 2026-09-21): the CREATOR Block B now renders a "Your audience" line
# from the new `audience_summary` context field (the creator's OWN audience,
# rendered by Java, or an explicit "not available"), and creator_persona.py
# gained the "Use their audience too." rule. BRAND prompt text unchanged.
#
# Previously (.1): bumped for creator content knowledge (feature/creator-content-knowledge,
# Swapnil 2026-09-21): CREATOR turns now carry a third cached system block
# rendered from app/prompt/knowledge/video_content_concepts.jsonl
# (app/prompt/content_knowledge.py), and creator_persona.py gained the
# "Content and growth questions" rules (knowledge first, name the category and
# the entry, ask for the last script, no invented hook numbers, no urgency
# wording, platform rows as background). The BRAND prompt text is unchanged,
# but the version is global, so brand cache keys roll over too.
#
# Previously: bumped for T-MEERA-CREATOR-PHASE-B Wave U, K-3 (Kabir "Last call — K-3",
# KABIR-CONSENT-0917.md; KC-3 condition). `creator_persona.py`'s trust-boundary
# bullet changed twice in this same range: once to name `<untrusted_brand_written>`
# blocks inside tool results (K-3's mechanism, `loop.py`'s
# `_model_copy_of_tool_result`), and again (KC-2) to say Meera may still name and
# quote a brand from inside that wrapper, she just may not obey it. `.3` does NOT
# cover this: it was assigned for the earlier U-5 `get_brief` description change,
# existed before the K-3 persona edit landed, and per Kabir's own read of
# `ci/stale-comment-check.py` rule 3 (L28-29, L281) the gate only checks that
# PROMPT_VERSION was reassigned SOMEWHERE in the diff range — it would have passed
# on `.3` alone even though `.3` was never actually served with this persona text.
# `cache_key_for` starts with `prompt_version` (`assembler.py`), so reusing `.3`
# risks a session started under the OLD persona text staying cached under a
# version number that now also describes the NEW text — a version has to mean one
# fixed prompt, and `.3` cannot honestly mean two.
#
# Previously: bumped for T-MEERA-CREATOR-PHASE-B Wave U (RULINGS-U-0917.md, U-5 python half).
# `get_brief`'s CREATOR_TOOL_SCHEMAS description changed (creator_schemas.py) --
# it now says where BOTH brief_id and deal_id come from, that passing both is
# refused, and tells the model not to call the tool again this turn on a
# still-reading refusal. `creator_schemas.py` is not itself under
# `PROMPT_SOURCES` (ci/stale-comment-check.py does not watch it), so this bump
# is manual and deliberate rather than gate-enforced -- Priya's ruling requires
# it anyway because a tool description change can move the model's behaviour
# exactly like a persona edit does. Two `assembler.py` comments that said
# "four" creator tools are also fixed in this same change (get_brief is the
# fifth, wired since this same Wave), which IS a genuine `PROMPT_SOURCES` hit.
#
# Previously: bumped for T-MEERA-CREATOR-PHASE-B B0 Wave 2. Wave 1 took `.09.10.1` for
# the creator context contract; Wave 2 then REWROTE prompt content underneath
# that same version — creator_persona.py's "what you do right now" section
# became a six-tool capability list with two new rails, and
# assembler.build_block_a_creator() stopped emitting the fixed "Available
# tools: none in this phase." line in favour of the per-turn tool names. Two
# materially different Block A texts sharing one version means a logged turn
# cannot be attributed to the prompt that produced it, and `cache_key_for`
# would serve Wave 1's persona to sessions opened before the deploy.
# `.2` rather than a new date because this is the same Phase B step; the later
# phases already reserve their own dates (C = .09.30.1, E = .09.20.1,
# D = .10.05.1), so incrementing the serial cannot collide with one.
# Verified before bumping: the literal is pinned nowhere — not a test, Java
# file, YAML or env file. `tests/eval/test_tenant_isolation.py` carries a
# hard-coded "meera-2026.07.05" but as a log-record fixture value, never
# compared against this constant.
#
# Previously: bumped for ME-2 (BrandF.md §115): the request_payment/confirm_launch tool
# bullets in Block A used to tell Meera to "propose a payment"/"propose
# launching" via those tools — but get_tool_schemas() (schemas.py) no longer
# offers either (they're scope-gated out for every real caller today), so the
# old copy described capabilities Meera didn't actually have. Rewritten to
# point the brand at the direct wallet/campaign controls instead, and to
# explicitly forbid claiming she took a funding/launch action she didn't.
#
# Previously: bumped for the 2026-08-08 deep-audit fixes. Persona text changed
# (F-17: the money caveat rail referenced `price_confidence`, a field that
# exists nowhere in the repo — the real field is `price_source`, so the rail
# never fired and Meera quoted model-guessed prices as confirmed facts). Block
# C assembly also changed (F-08/F-15: replayed history no longer produces
# native tool_use /
# tool_result blocks). Both are part of Block A/C, and prompt_version is a
# component of `cache_key_for`, so without this bump sessions cached under `.12`
# would keep serving the stale persona and the old replay shape.
# ^ bumped for the Meera campaign-completion flow build (Vikram, 2026-07-24,
# wiki/build/meera-completion-flow-2026-07-23.md): persona.py gained a
# "Completing a campaign after create_campaign" section (STANDARD Option B --
# derive the next field to ask from the DRAFT STATE the tool returned, one
# field per turn, budget-then-dates, honest "drafted, not live" completion
# CTA; full conversational HYPE -- explicit-signal-only, compose hashtag +
# format lanes, then sourceReelUrl -> perReelRate -> slotCap -> confirm 72h
# window one field per turn, say the rate x slots math out loud once as
# advisory copy, "open it to launch the blitz" CTA, never "it's live"). The
# create_campaign tool schema gained two FLAT optional content fields,
# source_reel_url and format_lanes (no combinators, no money/date fields --
# per_reel_rate/slot_cap stay human-only, enforced in
# CreateCampaignExecutor.java not JSON Schema). Both persona and schema are
# part of Block A's cached prefix, so this bump is required per this file's
# own rule above.
#
# Prior bump: the create_campaign draft-completeness fix (Tier 0 #2 + Tier 1,
# Ash-approved plan 2026-07-23): campaign_type is now OPTIONAL on the
# create_campaign tool schema (added STANDARD, dropped it from `required` —
# this is the root-cause fix for the HYPE-default invisible-draft bug) and 7
# new optional content-composition inputs were added (title, description,
# objectives, platforms, content_types, hashtags, target_audience). The
# persona text changed alongside it: a draft-is-never-live honesty rule, a
# compose-the-draft instruction for create_campaign, and an anti-leak rule
# against reading internal tool-instruction phrasing aloud. Both the tool
# schema and the persona are part of Block A's cached prefix, so this bump is
# required per this file's own rule above — without it, sessions cached under
# `.9` would keep serving the stale schema/persona pair.
#
# Prior bump: Phase 2 item 2.1 (outcome digest) — Block B gains the
# `outcome_digest` section (campaign_outcomes[] + niche_rate_band), and
# prompt_version is a component of `cache_key_for` — without the bump,
# sessions cached under `.8` would keep serving a stale Block B with no
# digest, and eval deltas across that change couldn't be cleanly attributed
# (Ash's Q3 ruling, wiki/build/phase2-ash-review.md).
# Prior bump: Creator AI Co-pilot Tier-1 creator-tone prompt
# (app/prompt/creator_suggestion.py). Single global constant, reused (not
# split) per Priya's R1 ruling §5.1 — the per-row
# `creator_nudge_log.prompt_version` column (Java-side, stamped by Vikram's
# CreatorNudgeService) gives audit granularity, so a second Python constant
# would add a second source of truth for no additional audit power.

# Trend-Spark (T8) — cheap Haiku-class model for the ONE phrasing call. Never
# Opus/Sonnet (schema-lock §4 / cost §1). Pinned the same way as CLAUDE_MODEL
# above; overridable via env for a future Haiku bump.
TRENDSPARK_MODEL = os.getenv("TRENDSPARK_MODEL", "claude-haiku-4-5-20251001")
# Trend-Spark LLM Recovery Tagger — the "smart AI" recovery pass over the
# deterministic n8n tagger (app/routes/trend_tag.py). Same cheap Haiku class as
# the nudge; defaults to TRENDSPARK_MODEL so both share one pricing row
# (app/costs/pricing.py). Overridable via env for an independent bump.
TREND_TAG_MODEL = os.getenv("TREND_TAG_MODEL", TRENDSPARK_MODEL)
# Persona name injected into the Trend-Spark system prompt — a single config
# constant (schema-lock §6) so it's never hardcoded in app/prompt/trendspark.py.
TRENDSPARK_PERSONA_NAME = os.getenv("TRENDSPARK_PERSONA_NAME", "Meera")

# Creator AI Co-pilot Tier-1 (POST /internal/creator-suggestion) — same cheap
# Haiku-class model as Trend-Spark for the ONE phrasing call. Defaults to the
# EXACT TRENDSPARK_MODEL string (not a separate literal) so it shares
# TRENDSPARK_MODEL's PRICING_TABLE row automatically via the same
# _resolve_rate fallback pattern pricing.py already has for TREND_TAG_MODEL /
# BRAND_SAFETY_MODEL -- overridable via env for an independent bump, per
# wiki/build/creator-copilot-ai-route-plan.md §5.2.
CREATOR_COPILOT_MODEL = os.getenv("CREATOR_COPILOT_MODEL", TRENDSPARK_MODEL)

# Brief extraction (POST /internal/brief-extract, T-MEERA-CREATOR-PHASE-B §7.5)
# — the ONE forced-tool call that turns a creator's pasted DM/email into the
# structured BriefExtraction of SPEC §2.11.
#
# Lives HERE rather than in the route module, beside CREATOR_COPILOT_MODEL
# above, for the same reason that one does: a model id is deployment
# configuration, and a route file is not where an operator looks for it.
#
# The default is the EXACT TRENDSPARK_MODEL string, never a fresh literal.
# app/costs/pricing.py keys PRICING_TABLE by literal model id, so a new
# Haiku-class literal here would miss the table and `estimate_cost_usd` would
# raise at billing time on the first real extraction — the pricing tests are
# what catch that, and inheriting TRENDSPARK_MODEL's priced row is what avoids
# it. Overridable via env for an independent bump, which must be to a model the
# table already prices.
BRIEF_EXTRACT_MODEL = os.getenv("BRIEF_EXTRACT_MODEL", TRENDSPARK_MODEL)

# Brand-safety GARM classification model (Wave C task C2) — pinned the same
# way as TRENDSPARK_MODEL above. The default is DELIBERATELY Sonnet
# (CLAUDE_MODEL), not a Haiku-class model: GARM labeling is bounded,
# schema-locked classification and Ash's AI review (wiki/ai-review/
# partial-fixes-batch-ai-review.md P1 #2) flags Sonnet as the biggest
# avoidable cost once the brand-safety fan-out scales past its current
# capped/disabled state -- but the flip to Haiku is gated on an A/B against
# a GARM golden set (same doc, "Data & Training Roadmap") so quality is
# proven, not assumed. Do not point this at a Haiku-class model without that
# eval. Overridable via env for that future (evaluated) bump.
BRAND_SAFETY_MODEL = os.getenv("BRAND_SAFETY_MODEL", CLAUDE_MODEL)

# Level 2 "frame check" (T-SHOOTCHECK-L2, POST /ai/shoot-check/frame) — the
# first route in this service that sends an IMAGE to a model
# (`ClaudeProvider.complete_with_image`). Defaults to the full CLAUDE_MODEL
# (Sonnet), not a Haiku-class model: judging framing/light/background from a
# photo and staying inside the appearance/identity rules in
# app/prompt/frame_check.py needs real vision quality, and unlike
# TREND_TAG_MODEL/BRAND_SAFETY_MODEL/CREATOR_COPILOT_MODEL this is not yet
# evaluated against a cheaper model at all -- overridable via env for a future
# (evaluated) bump, same pattern as BRAND_SAFETY_MODEL above.
SHOOT_CHECK_MODEL = os.getenv("SHOOT_CHECK_MODEL", CLAUDE_MODEL)

# India / approved regions only (Kabir guardrail #3) — informational; enforced by
# provider client base URLs / region config below.
APPROVED_LLM_REGIONS = ("asia-south1", "ap-south-1", "in")


@dataclass(frozen=True)
class ProviderTimeouts:
    """Per-provider connect/read timeouts, seconds. Tight per §6 of the AI service spec."""

    claude_connect: float = 3.0
    claude_first_token: float = 8.0
    claude_read: float = 30.0

    gemini_connect: float = 3.0
    gemini_read: float = 20.0

    sarvam_connect: float = 3.0
    sarvam_stt_read: float = 10.0
    # Batch REST /text-to-speech (NOT the streaming WS API) takes ~2.5-4s+ for a
    # 400-500 char Hindi-capable reply; 8.0s was too tight and produced
    # `sarvam speak failed: ReadTimeout`, which drops the reply to the browser
    # fallback voice AND (on repeats) trips the TTS circuit breaker, so voice
    # stays dead through the recovery window. 15s gives a normal reply room to
    # finish. Voice is additive (the text reply is already on screen), so a
    # longer read ceiling costs nothing on the critical path. If TTS latency
    # becomes the bottleneck, move to Sarvam's streaming WebSocket TTS.
    sarvam_tts_read: float = 15.0

    spring_connect: float = 2.0
    spring_read: float = 5.0

    # F1 HIGH (Kavya, Wave U last-call review of get_brief; Priya ruling
    # RULINGS-U-0917.md Addition B) -- a NAMED setting, not a literal, and NOT
    # `spring_read`, because `get_brief` is not a pure read. On a deal's first
    # read it can create a PLATFORM brief row and spend AI money
    # (CreatorBriefService.ensurePlatformBrief -> analyse -> MeeraBriefAiClient),
    # a blocking round trip bounded on the Spring side by
    # CREATOR_COPILOT_AI_CONNECT_TIMEOUT_SECONDS (application.yml default 5s)
    # plus CREATOR_COPILOT_AI_REQUEST_TIMEOUT_SECONDS (default 15s) plus
    # CreatorBriefService.STILL_READING_SLACK_SECONDS (10s) = a 30s
    # analysisBudget() by default. Python cannot read Spring's own environment,
    # so this cannot be derived at runtime -- it must clear that whole budget
    # plus its own margin, or influora-ai gives up on a brief Spring is still
    # about to finish. 40s = Spring's 30s budget + 10s. If Spring's three
    # numbers above are ever retuned, this must be revisited by hand.
    #
    # The safety property this fix rests on is REMOVING THE RETRY
    # (CREATOR_NO_RETRY_TOOLS in app/tools/creator_schemas.py), not the exact
    # relationship between the two timeouts: without a retry, a timeout here
    # reaches the model as a plain `network_error` it can relay, never as a
    # false "clean brief". Every OTHER creator read tool keeps the 5s
    # `spring_read` default and its retry -- this override is get_brief-only.
    get_brief_read: float = 40.0

    scrape_total: float = 30.0


@dataclass(frozen=True)
class RetryPolicy:
    """Retries only for idempotent GET-like calls. Never blind-retry money-tool forwards."""

    max_retries: int = 2
    backoff_base_seconds: float = 0.25
    backoff_jitter_seconds: float = 0.15


@dataclass(frozen=True)
class CircuitBreakerConfig:
    failure_threshold: int = 5
    recovery_seconds: float = 30.0
    half_open_max_calls: int = 1


@dataclass(frozen=True)
class Settings:
    # --- Service identity / environment ---
    # F-10: this defaulted to "dev". A prod deploy manifest that dropped or
    # typo'd APP_ENV booted successfully into the HS256 dev auth path, where
    # anyone holding the one symmetric secret can mint a token for ANY
    # workspace_id. Both "defense-in-depth" guards in auth/service_token.py
    # compare against this same field, so they were never two gates — they were
    # one gate read twice, and an unset env var opened both at once.
    #
    # Fail-safe direction is "prod": an unset APP_ENV must break local dev
    # loudly (set APP_ENV=dev), never silently weaken production auth.
    env: str = field(default_factory=lambda: os.getenv("APP_ENV", "prod"))
    service_name: str = "influora-ai"
    log_level: str = field(default_factory=lambda: os.getenv("LOG_LEVEL", "INFO"))

    # --- Provider API keys (secrets-manager injected; never committed) ---
    anthropic_api_key: str = field(default_factory=lambda: os.getenv("ANTHROPIC_API_KEY", ""))
    gemini_api_key: str = field(default_factory=lambda: os.getenv("GEMINI_API_KEY", ""))
    sarvam_api_key: str = field(default_factory=lambda: os.getenv("SARVAM_API_KEY", ""))

    # --- Spring auth integration ---
    spring_jwks_url: str = field(default_factory=lambda: os.getenv("SPRING_JWKS_URL", ""))
    spring_jwks_cache_seconds: int = field(
        default_factory=lambda: _get_int("SPRING_JWKS_CACHE_SECONDS", 300)
    )
    # F-09: PyJWKClient's default urlopen timeout is 30s and HttpJwksSource
    # passed none, so one unauthenticated request with an attacker-chosen `kid`
    # could park the event loop for 30 seconds. Tight, explicit, overridable.
    spring_jwks_timeout_seconds: float = field(
        default_factory=lambda: _get_float("SPRING_JWKS_TIMEOUT_SECONDS", 3.0)
    )
    # F-09: minimum seconds between two JWKS refetches triggered by an UNKNOWN
    # kid. Without this, `kid` is fully attacker-controlled and every miss is one
    # outbound HTTPS GET to Spring — N req/s of unauthenticated traffic becomes
    # N req/s of amplified load on the auth server. Inside the cooldown an
    # unknown kid is rejected with zero network I/O.
    spring_jwks_refetch_cooldown_seconds: float = field(
        default_factory=lambda: _get_float("SPRING_JWKS_REFETCH_COOLDOWN_SECONDS", 10.0)
    )
    # F-09: a `kid` longer than this, or carrying characters outside the JWK
    # thumbprint charset, is rejected before any lookup.
    spring_jwks_max_kid_length: int = field(
        default_factory=lambda: _get_int("SPRING_JWKS_MAX_KID_LENGTH", 128)
    )
    spring_expected_iss: str = field(
        default_factory=lambda: os.getenv("SPRING_JWT_ISSUER", "influora-api")
    )
    service_token_aud: str = field(
        default_factory=lambda: os.getenv("SERVICE_TOKEN_AUD", "influora-internal")
    )
    stream_token_aud: str = field(
        default_factory=lambda: os.getenv("STREAM_TOKEN_AUD", "meera-stream")
    )
    # Fallback / dev-only symmetric verification key. In prod, JWKS (asymmetric) is used;
    # this is only consulted when SPRING_JWKS_URL is unset (local dev), never in prod.
    dev_shared_jwt_secret: str = field(
        default_factory=lambda: os.getenv("DEV_SHARED_JWT_SECRET", "")
    )

    # --- Internal request signing (Python -> Spring /internal/meera/*) ---
    internal_hmac_key: str = field(default_factory=lambda: os.getenv("INTERNAL_HMAC_KEY", ""))
    internal_hmac_key_id: str = field(
        default_factory=lambda: os.getenv("INTERNAL_HMAC_KEY_ID", "v1")
    )

    # --- X-Meera-Service-Token minting (Python -> Spring /internal/meera/*) ---
    # DISTINCT secret from internal_hmac_key above -- this signs the service-token
    # JWT itself (iss=meera-python, aud=influora-internal, HS256, exp-iat<=60s);
    # internal_hmac_key signs the X-Meera-Signature request HMAC. Must match
    # Spring's `influora.internal-service-token.signing-secret` byte-for-byte
    # (InternalServiceTokenProperties.signingSecret / InternalServiceTokenFilter).
    service_token_signing_key: str = field(
        default_factory=lambda: os.getenv("SERVICE_TOKEN_SIGNING_KEY", "")
    )

    # --- Spring internal base URL ---
    # Must include the /api/v1 context-path -- influora-api's
    # server.servlet.context-path applies to every controller, including
    # MeeraInternalController's /internal/meera/* routes.
    spring_internal_base_url: str = field(
        default_factory=lambda: os.getenv("SPRING_INTERNAL_BASE_URL", "http://localhost:8080/api/v1")
    )

    # --- SSRF guard egress allow-list (analyze-site) ---
    ssrf_allowed_schemes: tuple[str, ...] = ("https",)
    ssrf_max_redirects: int = field(default_factory=lambda: _get_int("SSRF_MAX_REDIRECTS", 2))
    ssrf_max_response_bytes: int = field(
        default_factory=lambda: _get_int("SSRF_MAX_RESPONSE_BYTES", 5_000_000)
    )
    ssrf_fetch_timeout_seconds: float = field(
        default_factory=lambda: _get_float("SSRF_FETCH_TIMEOUT_SECONDS", 15.0)
    )

    # --- Tool loop ---
    tool_loop_max_iterations: int = field(
        default_factory=lambda: _get_int("TOOL_LOOP_MAX_ITERATIONS", 6)
    )
    # Output-token ceiling per Meera chat turn. IMPORTANT: this is a BACKSTOP,
    # not the mechanism that shapes reply length -- Meera's persona (see
    # persona.py's "HARD LENGTH LIMIT" rails) is what keeps spoken replies to
    # ONE-to-TWO short sentences, and does the real work. This constant only
    # needs to be wide enough that a legitimate tool call never gets cut off
    # mid-JSON.
    #
    # P1 BLANK TURN defect (2026-07-24, wiki/ai-review/meera-blank-turn-ai-
    # review.md): 384 was picked purely as a spoken-length ruler and was never
    # re-derived once create_campaign grew to 15 properties (schemas.py). Tool
    # JSON is never spoken, so a narration-sized ceiling is the wrong ruler for
    # it -- a fully-composed HYPE create_campaign call is ~245-365 output
    # tokens of JSON alone, which sat 384 exactly on the truncation boundary.
    # A max_tokens cut mid `input_json_delta` silently discarded the entire
    # tool call (claude.py) with no error, no log -- ~28% of turns went
    # completely blank. 1536 clears every known tool payload with headroom;
    # F1 (claude.py) + F2 (loop.py) make a cut structurally non-silent even if
    # this ceiling is later outgrown again. MUST be pinned explicitly in
    # env.example and every deploy env artifact -- do not let it silently fall
    # back to a code default again.
    # 2026-09-22 (Swapnil, cost fix 1): cache TTL for the two creator system blocks that are
    # the SAME for every creator (Block A persona+tools, and the content knowledge block).
    # "1h" keeps them warm through quiet stretches -- on a 5-minute TTL a quiet platform
    # re-wrote ~18k tokens at 1.25x on nearly every first message (~Rs 6.5 a message).
    # A 1-hour write costs 2x instead of 1.25x but is paid about once an hour. The
    # per-creator Block B stays on 5 minutes. Set "5m" to go back.
    ai_creator_shared_cache_ttl: str = field(
        default_factory=lambda: os.getenv("AI_CREATOR_SHARED_CACHE_TTL", "1h").strip().lower()
    )
    meera_chat_max_tokens: int = field(
        default_factory=lambda: _get_int("MEERA_CHAT_MAX_TOKENS", 1536)
    )
    # Wider one-shot ceiling for loop.py's single server-side retry after a
    # truncated tool-planning turn comes back empty (F2). Tool JSON is not
    # spoken, so the narration-length rationale above does not apply to a
    # retry that exists purely to let one tool call finish. This retry is
    # provably pre-tool-forward (no tool_start emitted yet, nothing sent to
    # Spring, no Idempotency-Key consumed), so widening the ceiling here
    # cannot double-execute or double-spend anything.
    meera_chat_max_tokens_retry: int = field(
        default_factory=lambda: _get_int("MEERA_CHAT_MAX_TOKENS_RETRY", 2048)
    )

    # --- SSE ---
    sse_heartbeat_seconds: float = field(
        default_factory=lambda: _get_float("SSE_HEARTBEAT_SECONDS", 15.0)
    )

    # --- Trend-Spark nudge (T8) — input caps + output shape for the one cheap
    # phrasing call. Defaults per app/prompt/trendspark.py's tone-guide rules
    # (<=2 sentences, short brand/trend inputs, small catalog per call).
    trendspark_max_brand_name_chars: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_BRAND_NAME_CHARS", 80)
    )
    trendspark_max_trend_text_chars: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_TREND_TEXT_CHARS", 200)
    )
    trendspark_max_videos: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_VIDEOS", 5)
    )
    trendspark_max_message_chars: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_MESSAGE_CHARS", 300)
    )
    trendspark_max_tokens: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_TOKENS", 300)
    )

    # --- Creator AI Co-pilot Tier-1 (POST /internal/creator-suggestion) ---
    # Input cap + output shape for the one cheap phrasing call. Mirrors the
    # trendspark_* caps above; no CREATOR_COPILOT_MAX_CAPTION_CHARS -- R1
    # dropped `caption_snippet` from the request contract entirely, so
    # there's nothing left to cap (see app/prompt/creator_suggestion.py).
    creator_copilot_max_trend_text_chars: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_TREND_TEXT_CHARS", 200)
    )
    creator_copilot_max_headline_chars: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_HEADLINE_CHARS", 120)
    )
    creator_copilot_max_content_idea_chars: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_CONTENT_IDEA_CHARS", 300)
    )
    creator_copilot_max_tokens: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_TOKENS", 300)
    )

    # --- Trend-Spark LLM Recovery Tagger (POST /internal/trendspark/tag) ---
    # Static shared secret for the n8n ingest caller (DELIBERATE exception to the
    # service-token rule — see app/routes/trend_tag.py). Empty => the endpoint
    # fails closed with 503 and never runs open. NOT added to require_boot_secrets:
    # the tagger is opt-in, and the whole service must not refuse to boot just
    # because this one recovery pass is unconfigured.
    trend_tag_ingest_secret: str = field(
        default_factory=lambda: os.getenv("TREND_TAG_INGEST_SECRET", "")
    )
    trend_tag_max_trend_text_chars: int = field(
        default_factory=lambda: _get_int("TREND_TAG_MAX_TREND_TEXT_CHARS", 200)
    )
    trend_tag_max_themes: int = field(
        default_factory=lambda: _get_int("TREND_TAG_MAX_THEMES", 6)
    )
    trend_tag_max_tokens: int = field(
        default_factory=lambda: _get_int("TREND_TAG_MAX_TOKENS", 80)
    )
    # Per-process trailing-60s cap. Blunts brute-forcing the static secret and
    # caps runaway token spend. <=0 disables (tests). n8n's real volume is low.
    trend_tag_rate_limit_per_minute: int = field(
        default_factory=lambda: _get_int("TREND_TAG_RATE_LIMIT_PER_MINUTE", 120)
    )

    # --- Brand-safety GARM classification (Wave C task C2) ---
    brand_safety_max_items_per_call: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_ITEMS_PER_CALL", 25)
    )
    brand_safety_max_caption_chars: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_CAPTION_CHARS", 2000)
    )
    brand_safety_max_meta_field_chars: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_META_FIELD_CHARS", 200)
    )
    brand_safety_max_tokens: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_TOKENS", 4096)
    )

    # --- AI spend ceiling + kill-switch (P2-17, Rohan budget proposal
    # 2026-07-12) — defaults exactly as specified in that proposal §3.5. ---
    ai_daily_spend_ceiling_usd: float = field(
        default_factory=lambda: _get_float("AI_DAILY_SPEND_CEILING_USD", 30.0)
    )
    ai_spend_kill_switch: bool = field(
        default_factory=lambda: _get_bool("AI_SPEND_KILL_SWITCH", False)
    )
    # Chat-only soft cap (WARNING-only, not blocking) — see routes/chat.py.
    ai_workspace_daily_soft_cap_usd: float = field(
        default_factory=lambda: _get_float("AI_WORKSPACE_DAILY_SOFT_CAP_USD", 3.0)
    )
    # Kabir red-team FIX 3 — BLOCKING per-workspace daily cap enforced by
    # app.costs.gate.check_spend_gate(). Distinct from
    # ai_workspace_daily_soft_cap_usd (which never blocks) — see
    # app/costs/gate.py's module docstring.
    #
    # EV-044 (2026-09-20): this used to be `_get_optional_float(...)` — unset
    # meant None meant NO per-workspace blocking at all, and no deploy input
    # set it: not deploy/hostinger/docker-compose.hostinger.yml, not
    # deploy/utho/docker-compose.utho.yml, not
    # deploy/utho/docker-compose.utho-shared.yml (all three declare
    # AI_DAILY_SPEND_CEILING_USD and AI_CREATOR_MONTHLY_CAP_USD and neither
    # this one). So in every production deploy ONE workspace could spend the
    # whole shared AI_DAILY_SPEND_CEILING_USD and take Meera, brand-safety and
    # the creator copilot down for every other workspace.
    #
    # It now carries a SAFE NON-ZERO DEFAULT, so a forgotten env var means
    # "capped at the documented default", never "unlimited". 3.0 USD/day
    # matches the long-standing soft cap above — the number the codebase
    # already considered one workspace's fair daily share — so turning the
    # block on does not silently move the threshold as well.
    #
    # "Unset" and "0" are deliberately NO LONGER the same thing: an explicit
    # `WORKSPACE_DAILY_HARD_CAP_USD=0` (or any value <= 0) disables the
    # per-workspace block, which is the ONLY way to get the old behavior and
    # has to be typed into a deploy on purpose. `_get_float` (not
    # `_get_optional_float`) is what makes unset fall to the default;
    # app.costs.gate normalises <= 0 back to None.
    ai_workspace_daily_hard_cap_usd: float = field(
        default_factory=lambda: _get_float("WORKSPACE_DAILY_HARD_CAP_USD", 5.0)
    )

    # --- EV-044: server-side ceiling on CLIENT-SUPPLIED conversation history ---
    # `routes/chat.py` takes `body["conversation"]` verbatim (see
    # app/prompt/assembler.build_block_c_messages' own docstring: "this block
    # is 100% client-controlled ... there is no server-side copy to check it
    # against"). Nothing bounded it: a browser holding one valid chat:stream
    # token could POST an arbitrarily long history and buy an arbitrarily
    # expensive prompt for a single AI credit, because the send-time charge in
    # influora-api is one credit per TURN regardless of prompt size.
    #
    # Both limits are enforced in the route, on the server, before any prompt
    # is assembled and before any provider client is touched. Neither is a
    # browser-side truncation: the client cannot opt out of them.
    ai_max_history_turns: int = field(
        default_factory=lambda: _get_int("AI_MAX_HISTORY_TURNS", 40)
    )
    ai_max_history_chars: int = field(
        default_factory=lambda: _get_int("AI_MAX_HISTORY_CHARS", 60_000)
    )
    # Request body hard limit for the AI path, enforced by the middleware in
    # app/main.py before FastAPI ever parses (or buffers) the body. 256 KiB is
    # ~4x the history char limit above, which leaves generous room for the
    # rest of a /chat body and for a /internal/brand-safety batch of
    # BRAND_SAFETY_MAX_ITEMS_PER_CALL captions.
    ai_max_request_body_bytes: int = field(
        default_factory=lambda: _get_int("AI_MAX_REQUEST_BODY_BYTES", 262_144)
    )

    # --- Meera for Creators, Phase A (A8) — per-creator MONTHLY cap ---
    # CREATOR-audience chat turns are metered per creator per calendar month
    # (UTC), separately from the per-workspace DAILY counters above. Default
    # USD 2.00/month, raised from 0.75 for launch (cb87f0d6); an active creator
    # costs 25-45 INR/month against a 500-750 INR commission on one deal (plan
    # Part 5). Enforced BLOCKING by
    # `app.costs.spend_tracker.check_creator_spend_gate` before any provider
    # call; the over-cap reply is a friendly message, never a raw 5xx. Set
    # `AI_CREATOR_MONTHLY_CAP_USD=0` to disable the cap entirely.
    ai_creator_monthly_cap_usd: float = field(
        default_factory=lambda: _get_float("AI_CREATOR_MONTHLY_CAP_USD", 2.0)
    )

    # --- Brief extraction's OWN monthly cap (SPEC §14.4.b) ---
    # POST /internal/brief-extract is metered on a SEPARATE per-creator monthly
    # bucket (`f"{creator_profile_id}:brief"`) from the chat cap above, so a
    # chatty creator never starves paste-and-read — which is the one surface
    # that still works when the chat cap is gone.
    #
    # Default 0.25 USD ~= 70 extractions at the credit sheet's INR 0.294/call on
    # Haiku. Enforced by `app.costs.spend_tracker.check_creator_spend_gate`,
    # which is the per-creator MONTHLY gate and the only one taking a `cap_usd`
    # override — NOT `app.costs.gate.check_spend_gate`, the daily workspace
    # ceiling, which takes none and would leave this value bound to nothing.
    #
    # A value <= 0 DISABLES the cap outright (spend_tracker's
    # `check_creator_spend_gate` returns None on `cap <= 0`). That is a
    # deliberate off switch, but it also means a mis-set or empty
    # BRIEF_EXTRACT_MONTHLY_CAP_USD is a disabled cost control that looks
    # configured — see that function and SPEC §14.4.b trap 2.
    brief_extract_monthly_cap_usd: float = field(
        default_factory=lambda: _get_float("BRIEF_EXTRACT_MONTHLY_CAP_USD", 0.25)
    )

    # --- Level 2 "frame check" (T-SHOOTCHECK-L2, POST /ai/shoot-check/frame) ---
    # Upload cap per the route spec: reject an image over this many bytes
    # BEFORE it reaches the model. 1.5 MB is generous for a phone-camera JPEG
    # a creator is checking before filming, and keeps one call's base64
    # payload (~1.33x the raw bytes) comfortably inside a normal request body.
    shoot_check_max_image_bytes: int = field(
        default_factory=lambda: _get_int("SHOOT_CHECK_MAX_IMAGE_BYTES", 1_500_000)
    )
    shoot_check_max_tokens: int = field(
        default_factory=lambda: _get_int("SHOOT_CHECK_MAX_TOKENS", 1024)
    )
    # Swapnil has not yet ruled on whether a frame check costs a creator a
    # credit (creator turns currently charge zero across the board --
    # `creditsCharged(0)` is hard-coded on the creator path in
    # `influora-api/.../MeeraSessionService.java`). This route METERS every
    # check (see app/routes/shoot_check.py's `_log_frame_check_metered`) but
    # does NOT charge anything itself -- there is no credit-debit call
    # anywhere in this route. `shoot_check_credit_cost_credits` is read into
    # every metering log line purely so that WHEN Swapnil rules, wiring a real
    # charge is a config flip plus the (separate, not-yet-built) charge call,
    # not a re-plumb of this route. A value of 0 (the default) means exactly
    # what it says today: this check is free.
    shoot_check_credit_cost_credits: int = field(
        default_factory=lambda: _get_int("SHOOT_CHECK_CREDIT_COST_CREDITS", 0)
    )

    # --- Voice language defaults (A5) ---
    # BRAND-audience voice turns have no per-user language preference yet, so
    # these are the fallbacks when neither the request nor a creator's
    # preferences supply one. CREATOR-audience turns ALWAYS take
    # `creator_language` from the creator's Meera preferences (via the
    # Spring context) for BOTH STT and TTS — see app/routes/voice.py.
    voice_default_stt_language: str = field(
        default_factory=lambda: os.getenv("VOICE_DEFAULT_STT_LANGUAGE", "hi-IN")
    )
    voice_default_tts_language: str = field(
        default_factory=lambda: os.getenv("VOICE_DEFAULT_TTS_LANGUAGE", "en-IN")
    )

    # --- F-05 spend reservations ---
    # Pessimistic per-call estimate held between the gate check and the recorded
    # spend, so concurrent callers see each other's in-flight cost instead of
    # all reading the same stale total. Deliberately generous: an over-estimate
    # briefly under-authorizes (a caller waits), an under-estimate lets the
    # ceiling be overshot, which is the failure this exists to prevent. Set to
    # 0 to disable reservations entirely (restores pre-fix behaviour).
    ai_reservation_per_call_usd: float = field(
        default_factory=lambda: _get_float("AI_RESERVATION_PER_CALL_USD", 0.02)
    )
    # Lowered 0.10 -> 0.02 on Priya's sign-off review. At 0.10 x
    # tool_loop_max_iterations (6) a chat turn held $0.60, so a $15 ceiling
    # admitted only 25 concurrent turns per process AT ZERO ACTUAL SPEND — the
    # 26th user got a 503 AI_SPEND_CEILING_REACHED. The F-05 fix must close a
    # money leak, not convert it into an availability cliff. $0.02 x 6 = $0.12
    # per turn (~125 concurrent turns) and still comfortably exceeds a real
    # Sonnet turn's cost, which is what the reservation needs to cover.
    #
    # How long a reservation is held before it self-expires. The chat tool loop
    # can legitimately run for minutes (SSRF fetch is 15s/hop, Gemini read 20s,
    # up to tool_loop_max_iterations turns), so it needs a longer hold than a
    # single provider call. Under-setting this silently reopens the F-05 race
    # for exactly the slow tail the reservation exists to cover.
    ai_reservation_chat_ttl_seconds: float = field(
        default_factory=lambda: _get_float("AI_RESERVATION_CHAT_TTL_SECONDS", 300.0)
    )

    # --- CORS (browser-direct Meera SSE stream) ---
    # Comma-separated list of exact allowed origins for the browser's cross-origin
    # POST /chat call (Authorization + Content-Type: application/json --
    # src/hooks/useMeeraStream.ts). Empty (default) => CORSMiddleware is never
    # installed at all: internal-only callers (Spring, n8n, tests, curl) are
    # completely unaffected and no CORS headers are ever added -- closed by
    # default. Set to the exact public origin(s) the SPA is served from in any
    # deploy that streams Meera directly from the browser; never "*" (a bearer
    # Authorization header requires an explicit origin echo, not a wildcard).
    meera_allowed_origins: str = field(
        default_factory=lambda: os.getenv("MEERA_ALLOWED_ORIGINS", "")
    )

    @property
    def meera_allowed_origins_list(self) -> list[str]:
        return [origin.strip() for origin in self.meera_allowed_origins.split(",") if origin.strip()]

    # --- Shared spend-tracker store (H-25) ---
    # When unset, app.costs.spend_tracker stays on its per-process in-memory
    # counter (documented Phase-1 limitation above). When set, the tracker
    # persists global/per-workspace daily spend in Redis so the ceiling and
    # kill-switch are enforced correctly across multiple worker processes /
    # instances, not per-process. Optional by design — a Redis outage or a
    # missing var must never block chat; the tracker falls back to in-memory
    # per-call on any connection error (see spend_tracker.py).
    redis_url: str = field(default_factory=lambda: os.getenv("REDIS_URL", ""))

    # --- Grouped configs ---
    timeouts: ProviderTimeouts = field(default_factory=ProviderTimeouts)
    retry: RetryPolicy = field(default_factory=RetryPolicy)
    breaker: CircuitBreakerConfig = field(default_factory=CircuitBreakerConfig)

    def require_boot_secrets(self) -> list[str]:
        """Return a list of missing/weak required secrets. Empty list == OK to boot.

        Per D0 DoD: "Missing/weak key -> refuse boot". main.py calls this at startup.
        """
        missing: list[str] = []
        # EV-006: env.example now ships REPLACE_WITH_YOUR_* for the provider keys. A non-empty
        # placeholder used to satisfy this check, boot clean, and 401 on the first provider call.
        # A placeholder provider key can never authenticate, so it counts as missing everywhere.
        for name, value in (
            ("ANTHROPIC_API_KEY", self.anthropic_api_key),
            ("GEMINI_API_KEY", self.gemini_api_key),
            ("SARVAM_API_KEY", self.sarvam_api_key),
        ):
            if not value:
                missing.append(name)
            elif _is_placeholder(value):
                missing.append(f"{name} (still the env.example placeholder)")
        # EV-006: the committed dev signing keys (dev-...-change-in-production-...) are fine for
        # APP_ENV=dev only; outside dev they are public values and must refuse boot.
        if self.env != "dev":
            for name, value in (
                ("INTERNAL_HMAC_KEY", self.internal_hmac_key),
                ("SERVICE_TOKEN_SIGNING_KEY", self.service_token_signing_key),
            ):
                if value and _is_placeholder(value):
                    missing.append(f"{name} (committed dev/placeholder value outside APP_ENV=dev)")
        # F-10: DEV_SHARED_JWT_SECRET is an acceptable substitute for
        # SPRING_JWKS_URL **only** in env=dev. Accepting it everywhere meant the
        # symmetric-secret path could satisfy the boot check in any environment.
        if not self.spring_jwks_url:
            if self.env == "dev":
                if not self.dev_shared_jwt_secret:
                    missing.append("SPRING_JWKS_URL (or DEV_SHARED_JWT_SECRET for local dev)")
            else:
                missing.append(
                    f"SPRING_JWKS_URL (required when APP_ENV={self.env!r}; the "
                    "DEV_SHARED_JWT_SECRET fallback is accepted only when APP_ENV=dev)"
                )
        if not self.internal_hmac_key:
            missing.append("INTERNAL_HMAC_KEY")
        if not self.service_token_signing_key:
            missing.append("SERVICE_TOKEN_SIGNING_KEY")
        return missing


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
