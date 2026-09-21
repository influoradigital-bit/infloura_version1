# Ash: AI review of Meera for Creators B0 (paste a brief, get a summary, a price and risk flags)

Asked by: Priya (CTO). Date: 2026-09-18. Reviewer: Ash (AI/ML code review).
Code: B0 worktree `influora-b0` at commit df20091. All paths below are relative to that worktree unless stated.
Every answer comes from the code. Labels: BUILT = in the code, SPEC-ONLY = only in a spec, UNKNOWN = the code does not say.

## What I ran (scratch copy under `.../scratchpad/ash-b0`, nothing edited in the repo)

- `python -m pytest tests/routes/test_brief_extract.py tests/tools/test_brief_extraction_schema_valid.py -q` gave `28 passed in 15.08s` (Python 3.13.3, installed anthropic SDK 0.125.0; `requirements.txt:7` pins 0.42.0).
- Every one of those tests mocks the provider. No test and no eval makes a real model call, and `influora-ai/evals/` has no dataset for brief reading. So how accurate the model is on real briefs is **UNKNOWN**.
- Probe script `scratchpad/ash-b0/probe.py`. It calls `parse_and_validate_extraction` directly and its output is quoted under Q3 and Q4.

---

## 1. Model, provider and parameters: BUILT

| Item | Value | Where it is set |
|---|---|---|
| Provider | Anthropic, through `anthropic.AsyncAnthropic` | `influora-ai/app/providers/claude.py:116` |
| Model | `BRIEF_EXTRACT_MODEL`, which defaults to `TRENDSPARK_MODEL` = `claude-haiku-4-5-20251001` and can be overridden by the env var `BRIEF_EXTRACT_MODEL` | `app/config.py:211`, `app/config.py:177` |
| Call shape | A single non-streaming `messages.create` with one tool that the model is forced to call (`tool_choice` = `extract_brief`) | `app/providers/claude.py:392-399`, `app/routes/brief_extract.py:452-458` |
| Temperature | **Not set.** The API default applies, and the code does not state that value | `claude.py:392-399` (no `temperature` argument) |
| max_tokens | **300**, borrowed from `creator_copilot_max_tokens` (env `CREATOR_COPILOT_MAX_TOKENS`), a setting written for the one-line suggestion route | `brief_extract.py:456` -> `config.py:468-470`; the same setting is used at `routes/creator_suggestion.py:297` |
| Python timeouts | connect 3s, read 30s, write 30s | `config.py:234-236`, applied at `claude.py:118-122` |
| Python retries | Not passed, so the SDK default applies. The installed 0.125.0 reports `DEFAULT_MAX_RETRIES = 2`; I did not check the pinned 0.42.0. `RetryPolicy.max_retries=2` (`config.py:286`) is not wired into this client | `claude.py:116-123` |
| Circuit breaker | Yes. If the breaker is open, the call returns `ok=False` | `claude.py:124-127`, `:386-389` |
| Java side | connect timeout 5s and request timeout 15s (`application.yml:294-295`), **no retry** (every failure returns `BriefResult.unavailable()`) | `influora-api/.../integration/ai/MeeraBriefAiClient.java:108`, `:175`, `:184-191` |

Findings:
- **F1 (HIGH): max_tokens=300 is too small for this tool call.** The schema has 22 properties plus 3-5 summary lines of up to 120 characters each (`app/tools/schemas.py:700-858`). I built a realistic full answer (4 English summary lines) and its tool-input JSON came to **814 characters**. Hinglish or Devanagari text uses more tokens per character. I did not have the tokenizer, so the exact count is **UNKNOWN**. My estimate is that a full answer comes near or over 300 tokens.
  - When the model runs out of tokens, the call either returns no `tool_use` block (`claude.py:421-431`) or returns cut-off lines that fail the 3-line minimum (`brief_extract.py:367-368`). Either way the creator gets the rule-based fallback.
  - The call is still billed (`brief_extract.py:461-471`).
  - `stop_reason` is not logged on this path. So in production this failure looks exactly like any other `extraction_failed`.
- **F2 (LOW): the two sides' timeouts do not match.** Java gives up after 15s (`application.yml:295`). Python can keep reading for up to 30s (`config.py:236`), and the SDK retries on top of that. The creator can see the fallback while Python finishes and bills a call nobody reads.

## 2. The extraction prompt: BUILT

The prompt is at `app/prompt/brief_extract.py:31-45`. The tool schema descriptions add to it at `app/tools/schemas.py:702-855`. The brief is wrapped as untrusted data at `brief_extract.py:55-67`.

**What it asks the model to produce:** the commercial terms, as the `extract_brief` tool input:
- brand, product, category
- deliverables with a count for each
- budget and `budget_stated`
- barter, deadline (ISO date), usage months and channels, exclusivity, revisions, payment terms
- two hint flags (off-platform payment, hidden disclosure)
- claims, regulated category, vague deliverables
- 3-5 factual summary lines of 120 characters or less (rule 5, line 40)

**What it forbids:**
- following instructions found inside the brief (line 33)
- inferring, estimating, rounding or filling defaults (rule 1, line 36)
- writing any number the brief does not contain (rule 2, line 37)
- setting `budget_stated` from "we'll discuss budget" (rule 3, line 38)
- values outside the enums (rule 4, line 39)
- advice or opinion on the offer (rule 6, line 42)
- pet names (rule 7, line 43)

**Where it is weak for a real Indian brief** (WhatsApp style, Hinglish, "15k", "1.5L", "1 reel + 3 stories"):
- **No Indian money shorthand.** Nothing in the prompt or schema explains "15k", "1.5L", "lakh" or "crore". `budget_inr` only says "the fee in INR" (`schemas.py:750-756`). Rule 2 ("never write down a number the brief does not contain") works against the conversion it needs: 15000 is not written in "15k". The model has to guess what we want. The Java fallback does handle these units (`BriefFallbackExtractor.java:60-61`); the AI prompt does not.
- **"3 stories" is ambiguous and the prompt does not settle it.** `STORY_SET` is priced as one unit at weight 0.50 (`QuoteDeliverableType.java:37`), and `qty` means "how many of this deliverable" (`schemas.py:744`). "3 stories" can come back as `STORY_SET x3`, which prices 1.5 reel-equivalents, or `x1`. What the spec intends here is **UNKNOWN**.
- **Relative dates.** For "post by Diwali" or "next Friday", the schema wants an ISO date (`schemas.py:773`) but gives the model no current date and no rule to omit a relative date. That invites the model to invent a year (see Q3, probe P2).
- **No language instruction at all** (see Q4). There are also no Hinglish or WhatsApp examples.
- **Category enum has 9 values** (`schemas.py:662-672`), with no parenting, auto, home, finance or D2C-food value. Off-list answers are dropped (`brief_extract.py:179-187`). That is safe, but many real briefs will come back with no category.

## 3. Hallucination guards: BUILT, with gaps I proved

Guards that exist, in `app/routes/brief_extract.py`:
1. **Invented numbers in summary lines are dropped.** A line whose numbers are not a subset of (the numbers in the brief + the extraction's own numbers) is removed (`:259-272`, `:356-366`; digits are normalised at `:140-152`). Tested by `test_invented_number_line_is_stripped`.
2. **A guessed budget is cleared.** If `budget_stated` is false, `budget_inr` is forced to None (`:302-311`). Java also reads the budget only when it is stated (`RateQuoteService.java:393-395`).
3. **Off-list values are removed.** Every enum and enum list fails closed to absent (`:179-202`, `:316`, `:329-337`, `:350-352`). Unknown deliverable types are dropped, not turned into OTHER (`:239-256`).
4. **Range limits.** Integers and numbers must sit inside a range (`:205-216`; budget 0-1e9, usage 0-600, exclusivity 0-3650, revisions 0-50). Deliverable qty must be 1-100 (`:254`).
5. **Banned words and pet names.** Lines with either are dropped (`:268-271`).
6. **Minimum lines that must survive.** If fewer than 3 lines survive, the whole extraction fails and the fallback is used (`:367-368`; `schemas.py:696-698`). Lines over 120 characters are dropped (`:364`).
7. **Input cap.** The brief is re-cut to 8000 characters (`:105`, `:406`).

Gaps, each run against the real validator in probe.py. The raw brief was "...1 reel + 3 stories chahiye, budget 15k. Post by Diwali. Usage 3 months paid ads.":

- **G1 (HIGH): the structured numbers are never checked against the brief.** Only summary lines are checked. `budget_inr`, `barter_mrp_inr`, `usage_months`, `exclusivity_days` and `max_revisions` pass as long as they are in range.
  - Worse, `_own_numbers` (`:155-176`) adds these same fields to the set of allowed numbers, so one invented field also lets an invented summary line through.
  - Probe P1: model says `budget_inr=50000` for a "15k" brief. The output kept `(50000.0, [..., 'Fee: Rs 50,000.', ...])`.
  - Probe P3: model says `usage_months=12` for "3 months". The output kept `(12, 'Usage: 12 months paid ads.')`.
  - The budget feeds the recommended move (`RateQuoteService.java:322`), so a wrong budget changes the advice she acts on.
- **G2 (MEDIUM): dates pass through unchecked.** `deadline` is only trimmed and cut to 40 characters (`:324`). There is no ISO parse, no year check and no past-date check.
  - Probe P2: for "Post by Diwali" the output kept `2025-10-20`, an invented date that is also in the past.
- **G3 (MEDIUM): brand names are not checked against the text.** This covers `brand_name` (`:314`), `product` and `exclusivity_brands`.
  - Probe P6: model says "Nykaa" for a Glowup brief. The output kept `Nykaa`.
  - `brand_name` feeds risk evaluation (`DealRiskService.java:198`, `:656-657`), so an invented name can cause a wrong blocked-brand or competitor result, or hide a real one.
- **G4 (LOW): the `claims` text list is free text and is not grounded in the brief** (`:347-349`). It feeds `RegulatedCategoryRule.java:60`.
- **G5 (LOW): the pet-name and banned-word checks are English only.** `CREATOR_BANNED_WORDS` has 1 entry (`escrow`). `_has_forbidden_petname` returned False for "didi" and "jaan" and True for "babe".

## 4. Language: BUILT (sent and logged, never used)

Confirmed in the code:
- Java sends it: `CreatorBriefService.java:437` passes `prefs.creatorLanguage()`, and `MeeraBriefAiClient.java:144-146`, `:163` put it in the request body.
- Python only logs it: the only read is inside `log_event` (`brief_extract.py:446`).
- The model never sees it:
  - `build_system_block()` takes no arguments (`app/prompt/brief_extract.py:48-52`).
  - `build_user_message(raw_text)` takes only the brief (`:55-67`).
  - The chat persona's language rule (`creator_persona.py:77-80`) is not imported. Only `CREATOR_BANNED_WORDS` is (`brief_extract.py:72`).

The same language setting drives the "Ask Meera" button copy on the paste card (`PasteBriefCard.tsx:46-50`, `:69-70`). That makes the English-only summary stand out more for a Hindi-preference creator.

Smallest change:
1. Change `build_system_block(creator_language)` to add one rule: "Write summary_lines in the creator's language (<lang>; hi-IN = Hindi or natural Hinglish in Latin script unless the brief is in Devanagari). Keep enum values in English. Write every number with the digits 0-9 exactly as the brief does."
2. Pass `_clean_text(body.get("creator_language"), max_chars=16)` at `brief_extract.py:453`.
3. **Required alongside:** convert Devanagari and other Unicode digits to ASCII in `_numbers_in` (`brief_extract.py:145`). Python's `\d` matches `१५०००`, but the comparison is on the literal characters.
   - Probe: `_numbers_in('फीस ₹१५,००० है')` returned `{'१५०००'}`, which is not equal to `15000`.
   - A 3-line Hindi summary with one Devanagari-digit fee line therefore returned **None**, meaning a full fallback.
   - So without this fix, turning on Hindi output will push creators onto the rule-based path.

Test fixture that would prove it (in `tests/routes/test_brief_extract.py`):
- (a) Send body `{"creator_language": "hi-IN", "raw_text": "Hi! Glowup skincare here. 1 reel + 3 stories chahiye, budget 15k. Post 20 Oct tak. Usage 3 months paid ads."}` with the provider mocked. Assert the captured `system_blocks[0]["text"]` contains `hi-IN`, and that an `en-IN` body does not.
- (b) A validator test: tool input with `budget_inr=15000` and summary lines `["Glowup को 1 reel और 3 stories चाहिए।", "फीस ₹१५,००० है।", "Usage: 3 months paid ads."]` must return 3 lines, not None.
- (c) A live eval (not a mock) over about 20 real Hinglish briefs, scoring the language of the summary. Without it, whether the output language is right in production stays **UNKNOWN**.

## 5. Cost against the US$0.25 monthly brief cap: BUILT (how it is computed); per-call dollar figure UNKNOWN

How spend is measured:
- After every call that returns usage, `estimate_cost_usd(BRIEF_EXTRACT_MODEL, result.usage)` is recorded, even when the model produced no tool call (`brief_extract.py:461-482`).
- The rate for this model is US$1.00 per million input tokens and US$5.00 per million output tokens (`app/costs/pricing.py:106`). Cache-read and cache-write rates exist in the table, but this route sends no `cache_control` (`prompt/brief_extract.py:48-52`).
- Spend is kept per creator per UTC month under its own key `"{creator_profile_id}:brief"`: `brief_extract.py:422-429` and `spend_tracker.py:68-74`, `:142-152`. Storage is Redis, falling back to per-process memory (`spend_tracker.py:1-36`).
- The cap is 0.25 (`config.py:561-562`). Setting it to 0 or below turns the cap off (`config.py:556-560`).
- Each call holds a US$0.02 reservation up front and settles to the real cost afterwards (`config.py:585-587`, `brief_extract.py:427`, `:468-469`).

What one call costs:
- The code states no per-call dollar figure. The only estimate in the code is a comment: "0.25 USD ~= 70 extractions at the credit sheet's INR 0.294/call on Haiku" (`config.py:550`).
- My token counts below are my own guesses. The priced results come from running `estimate_cost_usd` with those counts:
  - The system prompt is 1,812 characters and the tool schema is 4,639 characters. Add the brief, which can be up to 8,000 characters.
  - At 1,500 input + 300 output tokens the call costs **US$0.0030**. At 3,500 input + 300 output it costs **US$0.0050**.
  - Output can never cost more than 300 x $5/M = US$0.0015, because of the 300-token limit from Q1.
  - On those guesses, the US$0.25 cap allows roughly **50-80 briefs a month**, which fits the comment's "~70".

What I cannot know without live data:
- the real `input_tokens` (tool-definition overhead added by Anthropic, the size of real briefs, and how many tokens Hinglish or Devanagari use)
- how often the call hits max_tokens, where it pays and then falls back
- how often SDK retries run
- the USD/INR rate behind the credit sheet

The `ai_spend` log line (`brief_extract.py:472-482`) records `cost_usd` and `creator_month_usd` on every call. That line is where the real figure will come from.

## 6. The rule-based fallback (BriefFallbackExtractor): BUILT

File: `influora-api/src/main/java/com/influora/service/brief/BriefFallbackExtractor.java`.

**Always left empty:** `brand_name`, `product`, `category` (`:159-161`), `usage_channels` (`:170`), `exclusivity_brands` (`:173`), `claims` (`:178`), `regulated_category` (`:179`).

What that costs downstream:
- `RegulatedCategoryRule` (`rules/RegulatedCategoryRule.java:56-60`) can never fire.
- The category check in `ExclusivityLongRule` has nothing to compare against (`rules/ExclusivityLongRule.java:57`).
- The perpetual-usage "all channels" check sees 0 channels (`rules/UsagePerpetualRule.java:41`).

**Filled by regular expressions, in English only:**
- **money:** needs a marker such as inr/rs/₹/budget/fee next to the number, and handles k/L/lakh/crore (`:60-74`).
- **deadline:** ISO format `20xx-mm-dd` only (`:77`, `:167`), so "20 Oct" or "by Diwali" gives null.
- **exclusivity days:** `:85-89`. Only the digits are kept, so "3 months exclusivity" is likely to come back as 3 days. I have not tested this; it needs `daysIn` (`:340-363`) checked.
- **usage months** (`:91-95`), **revisions** (`:97-98`), **barter** (`:100-101`), **off-platform payment** (`:103-104`), **hidden disclosure** (`:111-116`), **payment terms** (`:118-119`).
- **deliverables:** the English words reel, story, short, ugc, post and integration (`:128-134`). Hindi or Hinglish words are not recognised.

**Summary:**
- The first line always says "Read without AI, so check these against the brief" (`:48`, `:193`).
- The remaining lines are templates, for example "Budget found in the text: 15000" (`:195-235`).

**Compared with the AI path:**
- It is honest and it never invents anything.
- It fills about half the fields, with no brand, category or regulated-category risk checks.
- For a WhatsApp or Hinglish brief it will mostly say "No specific deliverables found" and set `vague_deliverables=true` (`:180`). That raises a vague-deliverables flag the creator may not deserve.
- The fallback reaches creators more often than it should because of the max_tokens=300 issue (Q1 F1) and the Devanagari-digit issue (Q4).

## 7. Is the pricing advice grounded in real data, and is the label honest? BUILT; label honest

The price comes from the first source that applies (`RateQuoteService.java:425-458`):
1. **Her own history.** Median of her last 3 or more priced deals: "your last N priced deals" (`:433-435`). With 1-2 deals, that median is blended with the benchmark: "...blended with benchmark" (`:438-450`).
2. **Tier band.** The real median of closed deals in her tier over 90 days, used only if the band passes the 5-deal k-anonymity guard (`:559-603`, `:129-135`). It is relabelled if it is mostly prices Meera itself quoted (`:595`, threshold `:144`).
3. **Benchmark.** Otherwise the midpoint of `RateEstimationService.TIER_BASE_RATES`, a fixed constant table (NANO 1,000-5,000 ... MEGA 5,00,000-25,00,000, `service/scoring/RateEstimationService.java:35-42`), adjusted by engagement, category and quality multipliers (`RateQuoteService.java:638-708`).
   - With no metrics at all, it falls back to her own reel floor, labelled "your floor" (`:664-671`, `:119`).

So for a typical new creator (0 deals, empty tier band) the price is **a constant table, not market data**.

**The label is honest:**
- The provenance string reads "benchmark, not market data" (`RateAddOns.java:47`).
- The frontend matches it exactly (`CreatorToolResultRenderer.tsx:67-77`) and makes it the card subtitle, adding "this is our estimate, not what creators like you have actually closed" (`:337-344`). It titles the price "Opening ask (benchmark)" (`:418-424`).

**Caveats:**
- Where the constants in `TIER_BASE_RATES` come from is **UNKNOWN**; the code gives no source. `CreatorAgentRateCalibrationService.java:36` calls them constants.
- The AI's `category` is not used in pricing. The benchmark uses the profile's categories (`RateQuoteService.java:659`), so a brief outside her usual category is priced at her usual category's multiplier.

## 8. Verdict and the three improvements

**Verdict: partly helpful today.**
- The design is sound. The model only reads the brief, Java does the pricing and risk from her own data, the injection defence works, spend is capped per creator, and the benchmark label is honest.
- But three code facts reduce what a creator actually gets:
  - The AI answer is probably cut off by a 300-token limit borrowed from another route.
  - The structured numbers the price and risk depend on are not checked against the brief (P1 and P3 were accepted).
  - Hinglish and "15k/1.5L" briefs get no help from the prompt and always come back in English.
- There is no live eval, so real-world accuracy is **UNKNOWN**. Every one of the 28 green tests mocks the model.

Ranked by value for effort:

1. **Give brief extraction its own `max_tokens` (for example 1024) and log `stop_reason`.**
   - Where: `app/routes/brief_extract.py:456` (it currently borrows `creator_copilot_max_tokens`=300 from `app/config.py:468-470`); add a `brief_extract_max_tokens` setting next to `config.py:561`, and log `stop_reason` next to the failure log at `brief_extract.py:494-500`.
   - Effort: about 5 lines.
   - Benefit: stops paid calls from silently falling back, and makes this failure measurable.
   - Cost impact: output is at most US$0.005 per call at 1024 tokens (`pricing.py:106`).
2. **Check the numeric and date fields against the brief, and stop `_own_numbers` from vouching for them.**
   - Where: `app/routes/brief_extract.py:155-176`, `:302-311`, `:321-343`, `:356`.
   - Change: keep `budget_inr`, `barter_mrp_inr`, `usage_months` and `exclusivity_days` only when the brief contains that number, allowing for the k / L / lakh / crore conversions (`BriefFallbackExtractor.java:60-61` already does this). Then build the summary allow-list from the numbers in the brief only.
   - Also parse `deadline` as an ISO date and drop it if it is in the past or if the brief contains no digit for it (probe P2).
   - Also require `brand_name` to appear in the brief, ignoring case (probe P6).
   - Effort: about 40 lines plus 4 tests, which can reuse probes P1, P2, P3 and P6 as tests.
3. **Make the prompt understand Indian briefs and write back in the creator's language.**
   - Where: `app/prompt/brief_extract.py:31-52`, the call at `brief_extract.py:453`, and `_numbers_in` at `brief_extract.py:145`.
   - Changes:
     - pass `creator_language` into `build_system_block`
     - add rules for 15k=15000, 1.5L=150000, lakh and crore
     - say what "N stories" means for `STORY_SET` (this needs a product ruling, see Q2)
     - omit relative dates
     - tell the model to use the digits 0-9
     - convert Unicode digits to ASCII in `_numbers_in`
     - bump `PROMPT_VERSION` (see `config.py:70-102`)
   - Effort: about 20 lines plus the Q4 fixtures.
   - Then run a small live eval on about 20 real Hinglish briefs before any accuracy claim is made.
