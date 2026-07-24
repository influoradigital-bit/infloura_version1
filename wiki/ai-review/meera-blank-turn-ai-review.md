# AI Review: Meera BLANK TURN (P1 reliability defect)

**Reviewer:** Ash (AI/ML expert & AI code reviewer) · **Date:** 2026-07-24
**Trigger:** Priya (CTO) escalation — neha live E2E, deploy `ab0ec67`/`87d752e`, `http://200.141.1.6`
**Symptom:** ~28% of turns (7/25) return a completely blank assistant bubble. SSE body = `prompt_meta` + `done{"finish_reason":"stop"}`, **113 bytes**, zero `token`, zero `tool_start`, zero `tool_result`. 6 of 7 clustered in ONE HYPE thread at the point `create_campaign` was due; 1 immediately after an `analyze_site` error; a fresh thread ran 7/7 clean.

---

## TL;DR

**Priya's dangling-`tool_use` hypothesis is REFUTED** — with proof. Nothing in this system has ever persisted or replayed a `tool_use`/`tool_result` block. The assembler branches that would handle them are dead code in the live path.

**The real root cause is one line of missing handling in the provider stream parser.** `ClaudeProvider.stream_turn` converts an open `tool_use` content block into a `tool_use` event **only inside `content_block_stop`** (`influora-ai/app/providers/claude.py:163`). If the assistant turn ends without that block closing — which is what a `max_tokens` cut mid-tool-JSON looks like on the wire — the entire tool call is **silently discarded**. `run_tool_loop` then sees zero text and zero pending tool calls and falls straight through to `done finish_reason="stop"` (`influora-ai/app/tools/loop.py:166-171`). The user gets silence; the backend correctly refunds; the flow dead-ends.

The ceiling doing the cutting is **`MEERA_CHAT_MAX_TOKENS = 384`** (`influora-ai/app/config.py:271-273`), unset in `env.example` and unset in every deploy artifact — so the live box is on the 384 default. A fully-composed `create_campaign` HYPE draft is **~260–360 output tokens of tool JSON alone**. 384 is sitting exactly on the boundary. That is the ~28%.

I flagged this exact failure mode 24 hours ago and it was not actioned: `wiki/reports/ash-brand-ai-review-2026-07-23.md` P2 item 7 — *"384 output tokens is a good spoken-length backstop, but pair it with a `done` reason so a truncated tool-planning turn is visible rather than silently short."*

**Verified with a deterministic offline repro that reproduces the observed wire body byte-for-byte (113 bytes).**

---

## 1. ROOT CAUSE

### 1.1 What the wire evidence proves, deductively

Neha's capture is enough to eliminate most of the search space before reading any code:

| Observation | What it rules out |
|---|---|
| No `error` event; `done{stop}` | No `anthropic.APIError`, no 400, no circuit-open, no `ToolLoopCapExceeded`, no `client_disconnected`. All of those emit `event: error` (`chat.py:355-387`). **This kills the "malformed history → API 400" family outright.** |
| Zero `token` events | `assistant_text` was `""` → the model emitted no `text_delta` at all (`loop.py:150-152`). |
| Zero `tool_start` events | `tool_start` is yielded *unconditionally and first* for every pending call (`loop.py:191`). Zero of them ⇒ `pending_tool_calls` was **empty** ⇒ `stream_turn` never yielded a `tool_use` event. |
| `release_turn_credit` fired, not `/internal/meera/messages` | `final_text == ""` and `tool_result_delivered == False` → `chat.py:489` refund branch. Confirms the same. |

So: **the stream opened cleanly, ran to `message_stop`, and produced no text and no *completed* content block.** Only two things can do that:

- **(A)** a content block was **started and never closed** — the parser drops it, or
- **(B)** the model genuinely returned empty content.

### 1.2 The code that turns (A) into silence

`influora-ai/app/providers/claude.py:143-176`:

```python
current_tool: dict[str, Any] | None = None
async for event in stream:
    if event.type == "content_block_start":
        block = event.content_block
        if getattr(block, "type", None) == "tool_use":
            current_tool = {"id": block.id, "name": block.name, "partial_json": ""}
    elif event.type == "content_block_delta":
        ...
        elif getattr(delta, "type", None) == "input_json_delta" and current_tool:
            current_tool["partial_json"] += delta.partial_json   # accumulated, NEVER yielded
    elif event.type == "content_block_stop" and current_tool:     # <-- the ONLY exit
        ...
        yield ClaudeStreamEvent(type="tool_use", ...)
        current_tool = None
    elif event.type == "message_stop":
        final_message = await stream.get_final_message()
        usage = getattr(final_message, "usage", None)             # stop_reason DISCARDED
        yield ClaudeStreamEvent(type="usage", usage={...})
```

Three defects in one block:

1. **`content_block_stop` is the only path out of `current_tool`.** No `message_stop` reconciliation. An unclosed block is dropped on the floor with no log, no event, no error.
2. **`input_json_delta` produces no observable output.** During the *entire* generation of a tool's JSON input, the SSE stream is completely silent — no `token`, no `thinking`, nothing. So a truncation here is invisible from the first byte to the last.
3. **`stop_reason` is read and thrown away.** `final_message.stop_reason` is right there on line 178; only `usage` is taken. `max_tokens` and `end_turn` are therefore indistinguishable everywhere downstream. `loop.py` then hardcodes `finish_reason="stop"` (`loop.py:170`), so the *route* lies to the client too.

I verified against the installed SDK (`anthropic==0.42.0`, `anthropic/lib/streaming/_messages.py:324-372`, `build_events`) that the SDK emits a `content_block_stop` event **only when the raw API sends one** — there is no synthetic close-out at `message_stop`. So an unclosed block on the wire is an unclosed block in this code.

### 1.3 Why the cut happens on `create_campaign` specifically

`MEERA_CHAT_MAX_TOKENS = 384` (`config.py:271-273`). Not overridden anywhere:

```
influora-ai/env.example  -> not present
deploy/                  -> not present
```

384 was chosen as a **spoken-length backstop** (the comment says so: *"Meera's persona wants ONE-to-TWO short sentences… 384 is a hard backstop that still fits narration + a tool_use block"*). That assumption was true when `create_campaign` had 5 fields. It is no longer true. Since the 2026-07-23/24 draft-completeness work the schema carries **15 properties** including a 2–4 sentence `description` and five arrays (`schemas.py:147-289`), and the persona *instructs her to fill them all* (`persona.py:129-150`, `182-209`).

Rough output-token budget for one HYPE `create_campaign` call:

| Part | ~tokens |
|---|---|
| JSON structure + 12–15 field names | 80–120 |
| `description` (2–4 sentences, persona-mandated) | 60–90 |
| `title`, `product_name`, `product_url`, `campaign_type`, `creator_count` | 25–40 |
| `objectives[]`, `platforms[]`, `content_types[]` | 25–35 |
| `hashtags[]`, `target_audience[]` | 25–35 |
| `format_lanes[]`, `source_reel_url` (HYPE only) | 30–45 |
| **tool JSON subtotal** | **~245–365** |
| optional narration first (persona: *"Always narrate what you're doing"*, `persona.py:216-217`) | +20–40 |

**384 lands in the middle of that distribution.** That is precisely a ~25–30% failure rate — not a bug that either always fires or never does, which is exactly the "not deterministic, not random" behaviour neha described.

Every piece of the observed pattern falls out of this:

- **6/7 in the HYPE thread at `create_campaign`** — the single largest payload the model ever emits, made larger by HYPE's extra `format_lanes` + `source_reel_url` + hashtag composition (`persona.py:188-190`).
- **Fresh thread 7/7 clean** — greetings, `analyze_site` (one `url` field, ~15 tokens), short answers. Nowhere near the ceiling.
- **The 7th, right after an `analyze_site` error** — the turn following `analyze_site` is exactly where the model composes the draft from the returned catalog; on the error path it also re-plans. Same large-payload turn.
- **"Conversation-state dependent"** — because it's *content*-dependent, and content is a function of where the thread is.
- **No double-charge** — the refund path is working as designed. This is a UX/reliability defect, not a money defect.

### 1.4 Honest statement of the one unverified link

I could not put a live call through the Anthropic API from this box, so I cannot personally attest that the API **omits** `content_block_stop` when `max_tokens` truncates a `tool_use` block (hypothesis A) rather than the model having returned genuinely empty content (hypothesis B).

I am not going to hand-wave it, so here is exactly how to close it (§2, Repro A — 2 minutes, no code change), and here is why it does not block the fix:

- If **A**: the fix is F1 + F2 + F3.
- If **B**: the fix is F2 (+ F6). F2 alone makes the user-visible symptom impossible either way.
- **F1 and F2 are both correct and both required regardless of which wins.** F1 closes a real hole in the parser that exists on its own merits (an unclosed block is currently discarded in total silence). F2 is Priya's mandated guardrail.

Weight of evidence strongly favours **A**: a 28% rate is three orders of magnitude above the base rate of provider-side empty completions; empty completions are content-independent, and this is sharply content-correlated (`create_campaign`, HYPE, large payload); and the ceiling arithmetic in §1.3 predicts ~28% on its own.

**Predicted co-symptom to confirm A (ask neha):** the *other* half of the truncation distribution — turns where Meera **does** narrate ("building that for you now…") and then nothing ever appears, no card, no draft. Same root cause, text-first ordering instead of tool-first. If neha saw any of those, A is settled.

### 1.5 Priya's hypothesis: REFUTED, with proof

> *"A dangling `tool_use` block with no paired `tool_result` in the replayed conversation history."*

It is a completely reasonable hypothesis and it is the right instinct for this class of bug. It is not what is happening here, for four independent reasons:

1. **A dangling `tool_use` is a 400, not silence.** Anthropic rejects it outright. A 400 raises `anthropic.APIError` → propagates through `run_tool_loop` → `chat.py:377-387` `except Exception` → `event: error {"code":"provider_timeout"}`. Neha observed **no error event**. The symptom is categorically wrong for this cause.

2. **Nothing persists tool blocks. Anywhere.** `AiMessage` (`influora-api/.../domain/entity/AiMessage.java:14-40`) has exactly `id / conversationId / role / content / metadataJson / creditsCharged / createdAt` — no tool-call column. `MeeraSessionService` writes exactly two row shapes: `MessageRole.USER` at send (`MeeraSessionService.java:222-234`) and `MessageRole.ASSISTANT` at write-back (`:417-426`). `MessageRole.TOOL` exists in the enum (`MessageRole.java:7`) and has **zero usages in the entire Java source tree** (verified by grep).

3. **Python never persists tool blocks either.** `chat.py` calls `persist_assistant_message` with `content=final_text` only (`chat.py:454-464`). The tool-result-only branch (`chat.py:473-483`) explicitly persists **nothing**.

4. **The frontend never sends them.** There is exactly **one** call site in the entire SPA that builds the `conversation` payload — `MeeraChatPanel.tsx:443-449` — and it emits `{role, content}` pairs only:
   ```ts
   const history = [
     ...messages.map((m) => ({ role: m.role === 'brand' ? 'user' : 'assistant', content: m.text })),
     { role: 'user', content: text },
   ]
   ```
   No `tool_calls`, no `tool_call_id`, no `role: 'tool'`.

**Therefore `build_block_c_messages`'s `tool_calls` branch (`assembler.py:379-386`) and its `role in ("tool","TOOL")` branch (`:389-393`), and both `_tool_call_content_block` / `_tool_result_content_block` (`:314-359`), are unreachable dead code in the live path.** The comment at `assembler.py:319-320` that Priya flagged is describing a hazard that the current wiring cannot produce. They are correct, well-written, and currently decorative.

Separately: I traced **every** exit in `loop.py` looking for an in-memory orphan anyway — `unknown_tool` (`:196-205`), missing `url` (`:265-269`), `present_options` (`:221-257`), `analyze_site` success/failure (`:304-314`), `SpringCallError` (`:340-354`), `AWAIT_HUMAN_CONFIRM` (`:358-375`). **Every single one appends a paired `tool_result` block before its `continue`.** That code is clean. Vikram got this right.

The one gap: a non-`SpringCallError` exception out of `spring.call_tool_endpoint` (line 329 catches `SpringCallError` only) escapes with the assistant `tool_use` already appended at `:181` and no result — but `messages` is generator-local and discarded, so it produces a mislabelled `provider_timeout` rather than an orphan. Logged below as P2-3.

### 1.6 Secondary defect (independent, real, and it is what *poisons the thread*)

Root cause explains one blank turn. It does not explain 6 in a row in the same thread. This does:

`MeeraChatPanel.tsx:462` creates the assistant bubble with `text: ''` **unconditionally** as a streaming target. On a blank turn `onToken` never fires, so that bubble stays `text: ''` forever — in React state, in the localStorage transcript cache (`:312-315`), and in the next turn's replayed `history` (`:443-449`).

Verified against the live assembler:

```
$ build_block_c_messages([user "hi", assistant "", user "go on"])
[ {"role":"user","content":"<untrusted_user_message>…"},
  {"role":"assistant","content":""},                        # <-- shipped to the API verbatim
  {"role":"user","content":"<untrusted_user_message>…"} ]
```

An empty-content message that is not the final assistant slot is invalid per the Messages API. So after the *first* blank turn, that thread is degraded or hard-broken for **every subsequent turn** — which is exactly the 6-in-one-thread clustering and exactly why a fresh thread was clean. (The precise API-side behaviour — hard 400 vs tolerated-and-dropped — is the other thing Repro A settles. Either way this must not be sent.)

The same code path also drops real signal: a turn that ran a tool and produced no narration renders a card in the UI but replays as `{role:'assistant', content:''}` — the model has **no idea it ever called that tool**. That is its own quality bug on top of the reliability one.

---

## 2. DETERMINISTIC REPRO

### Repro A — live, ~2 minutes, no code change. This is the discriminator.

1. On the influora-ai container set `MEERA_CHAT_MAX_TOKENS=24`; restart.
2. Any Meera thread, send verbatim:
   > `build me a hype campaign for my glow serum — 72h blitz, everyone remixes one reel, flat per-reel rate`
3. **Expected if hypothesis A: ~100% blank turns**, each a 113-byte `prompt_meta`+`done{stop}` body. Every turn where she'd call a tool dies silently.
4. Now set `MEERA_CHAT_MAX_TOKENS=2048`; restart; replay neha's exact HYPE thread from a **fresh** conversation.
5. **Expected: blank rate → 0.**

A clean A/B here proves truncation and closes §1.4. If step 3 does *not* go blank, hypothesis B wins and the fix narrows to F2+F6 — the guardrail is unchanged either way.

### Repro B — offline, deterministic, CI-ready. Already written and green.

**File: `wiki/ai-review/meera-blank-turn-repro.py`** (parked outside `influora-ai/tests/` on purpose — it asserts the *current* buggy behaviour, so collecting it in CI would lock the bug in).

```
cd influora-ai
INFLUORA_AI_ROOT="$PWD" python -m pytest ../wiki/ai-review/meera-blank-turn-repro.py -q -s
```


Three tests driving the real `ClaudeProvider` / `run_tool_loop` / `chat()` against a fake Anthropic stream that emits
`message_start → content_block_start(tool_use "create_campaign") → input_json_delta × N → message_delta(stop_reason="max_tokens") → message_stop`
with **no `content_block_stop`**:

| Test | Asserts | Result |
|---|---|---|
| `test_provider_swallows_truncated_tool_use_and_emits_nothing` | `stream_turn` yields `["usage"]` — no `text`, **no `tool_use`** | ✅ |
| `test_loop_turns_a_dropped_tool_use_into_a_clean_done_stop` | `run_tool_loop` yields exactly `["done"]`, `finish_reason == "stop"` | ✅ |
| `test_route_emits_prompt_meta_plus_done_only_and_refunds` | full `chat()` SSE body, `persist_assistant_message` not called, `release_turn_credit` awaited once | ✅ |

Route-level captured body:

```
'event: prompt_meta\ndata: {"prompt_version": "meera-2026.07.24.12"}\n\nevent: done\ndata: {"finish_reason": "stop"}\n\n'
bytes: 113
```

**113 bytes. Byte-for-byte identical to neha's live capture.** `3 passed`.

**Action:** land these as `influora-ai/tests/providers/test_stream_truncation.py` and `influora-ai/tests/routes/test_chat_blank_turn_guard.py`, inverted to assert the *fixed* behaviour (a `token` event must exist, `finish_reason` must be `empty_response`). Regression-proofing this is non-optional — the whole class of bug is "invisible in every existing test because every existing test scripts well-formed events."

---

## 3. THE FIX

### Which layer, and why

Fix at the **provider** (F1) and the **loop** (F2). Not at the assembler, and not at the frontend.

- The assembler is the wrong layer for the *root cause* — sanitizing history cannot recover a tool call that was never parsed. It **is** the right layer for the *secondary* defect (F4), as a cheap invariant.
- The frontend is the wrong layer for anything load-bearing — it is one client of the SSE contract, and the contract itself is what is broken. FE gets defense-in-depth only (F5).
- The provider is where the information exists and is being destroyed (`stop_reason`, the open `current_tool`). Fix it where the information is.

Constraints honoured throughout: **no schema combinators** (nothing below touches `input_schema`; `anyOf`/`oneOf`/`allOf` count stays at zero — see `reference_anthropic_tool_schema_no_combinators`), and **no weakening of the money guardrail** (F2's retry is provably pre-tool-forward; F7 explicitly preserves both Kabir FAIL-1 and FAIL-2 boundaries).

---

### F1 — `influora-ai/app/providers/claude.py` — never lose an open content block · **P0**

Add `stop_reason` to the event dataclass and reconcile at `message_stop`.

```python
@dataclass
class ClaudeStreamEvent:
    type: str  # "text" | "tool_use" | "message_stop" | "usage" | "truncated"
    ...
    stop_reason: str | None = None
    tool_name_partial: str | None = None
```

```python
elif event.type == "message_stop":
    final_message = await stream.get_final_message()
    stop_reason = getattr(final_message, "stop_reason", None)

    if current_tool is not None:
        # The API ended the turn without closing this tool_use block -- a
        # max_tokens cut mid input_json_delta. The partial JSON is NOT
        # salvageable and must NEVER be coerced into a real tool call: a
        # half-built create_campaign would write a truncated draft, and a
        # half-built request_payment/confirm_launch is a money-tool forward
        # built from an incomplete model intent. Drop the call, SIGNAL the
        # truncation, and let the loop recover.
        logger.warning(
            "claude stream truncated mid tool_use tool=%s stop_reason=%s json_len=%d",
            current_tool["name"], stop_reason, len(current_tool["partial_json"]),
        )
        yield ClaudeStreamEvent(
            type="truncated", stop_reason=stop_reason, tool_name_partial=current_tool["name"]
        )
        current_tool = None

    yield ClaudeStreamEvent(type="usage", usage={...}, stop_reason=stop_reason)
```

Deliberate: **do not** parse `partial_json` with `allow_partial`. The SDK will happily hand you a partial object (`_messages.py:402-412`); accepting it is how you ship a campaign draft with a half-sentence description, or worse. Dropping a truncated commit-tier tool call is the only safe behaviour.

Also index-guard while here (P2, latent): `current_tool` is a single slot and `event.index` is ignored throughout. Correct for today's sequential blocks; it silently breaks the day interleaved/thinking blocks arrive. Key it by `event.index`.

---

### F2 — `influora-ai/app/tools/loop.py` — an empty turn must never reach the client · **P0**

Replace `loop.py:166-171`:

```python
        if not pending_tool_calls:
            if assistant_text:
                messages.append({"role": "assistant", "content": assistant_text})
                yield LoopEvent(type="done", finish_reason="stop", usage=final_usage)
                return

            # ── EMPTY MODEL TURN ────────────────────────────────────────────
            # Zero text AND zero tool calls. The user would otherwise get a
            # dead bubble (P1, 2026-07-24). Two-stage recovery.
            if turn_truncated and not retried_empty:
                # A truncated tool_use produced nothing usable. Retry ONCE with
                # a wider ceiling. SAFE by construction: no tool_start was
                # emitted, no tool was forwarded to Spring, no Idempotency-Key
                # was consumed, nothing was persisted. The retry cannot double-
                # execute anything -- there is nothing to double-execute.
                retried_empty = True
                logger.warning(
                    "empty turn after truncation (stop_reason=%s) -- retrying once at %d max_tokens",
                    turn_stop_reason, ctx.max_tokens_retry,
                )
                effective_max_tokens = ctx.max_tokens_retry
                continue  # re-enters the while loop with `messages` unchanged

            yield LoopEvent(type="token", text=EMPTY_TURN_FALLBACK)
            yield LoopEvent(type="done", finish_reason="empty_response", usage=final_usage)
            return
```

with, at module scope:

```python
# Priya's rail: the user always gets a real reply or an honest, recoverable
# message -- never silence. Persona-consistent (spoken, one sentence, exactly
# ONE thing to do). This text is READ ALOUD by TTS, so no punctuation tricks.
EMPTY_TURN_FALLBACK = "Sorry, I lost my train of thought there. Say that again?"
```

and in `ToolLoopContext`:

```python
    max_tokens: int = 1024
    # Wider ceiling for the ONE server-side retry after a truncated tool turn.
    # Tool JSON is not spoken, so the spoken-length rationale behind max_tokens
    # does not apply to a retry that exists purely to finish a tool call.
    max_tokens_retry: int = 2048
```

`turn_truncated` / `turn_stop_reason` are set per-iteration from F1's `truncated` / `usage` events, reset at the top of each `while` pass. `retried_empty` is per-turn (initialised beside `iterations`) so the retry can fire at most once — bounded cost: one extra call on ~28% of turns today, ~0% once F3 lands.

Cancellation ordering nit while in here (P2-4): `loop.py:146-148` checks `is_cancelled()` *after* consuming an event, so a disconnect can drop a token that was already generated. Move the check above the `if event.type` chain.

---

### F3 — `influora-ai/app/config.py` — stop cutting tool JSON with a spoken-length ruler · **P0**

```python
    meera_chat_max_tokens: int = field(
        default_factory=lambda: _get_int("MEERA_CHAT_MAX_TOKENS", 1536)
    )
```

384 is the right budget for *narration*. It is the wrong budget for *tool planning*, because tool JSON is never spoken — and `create_campaign` alone is ~245–365 tokens (§1.3). Raising the ceiling does **not** make replies longer: reply length is shaped by the persona's `HARD LENGTH LIMIT` rails (`persona.py:31-47`), which are explicit, repeated, and were doing the real work all along; 384 was only ever the backstop.

Set `MEERA_CHAT_MAX_TOKENS=1536` explicitly in `influora-ai/env.example` and in the deploy env so this is never again silently inherited from a code default.

Rejected alternative: shrinking `create_campaign`'s `description` to 1–2 sentences to fit under 384. That trades away the draft-completeness work that just shipped (`config.py:69-98`) to work around a ceiling that costs ~$0.003/turn to raise. Wrong trade.

Guard the regression with an eval, not a hope: assert p95 assistant reply length stays ≤ ~45 words across the golden-brand set (`influora-ai/tests/eval/test_golden_brands.py`).

---

### F4 — `influora-ai/app/prompt/assembler.py` — history invariants · **P1**

Two changes in `build_block_c_messages`:

**(a) Never send an empty-content message** (fixes §1.6 at the last line of defense):

```python
        elif role == "assistant":
            tool_calls = turn.get("tool_calls")
            if not tool_calls and not (content or "").strip():
                # Anthropic rejects an empty-content message anywhere but the
                # final assistant slot. A blank Meera turn used to put exactly
                # that into replayed history and poison every subsequent turn
                # in the thread (P1, 2026-07-24). An empty assistant turn
                # carries no information -- drop it.
                continue
```
Also apply to `role == "user"` (an empty user turn is equally invalid) and to the unknown-role fallback at `:394-396`.

**(b) Drop unpaired tool blocks before returning** — Priya's hypothesis is not the cause *today*, but it becomes reachable the moment anyone persists tool turns (and Phase 3 / CREATOR will want to). Make it structurally impossible now, while it is cheap:

```python
def _drop_unpaired_tool_blocks(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Anthropic requires every assistant `tool_use` to be answered by a
    `tool_result` with the same id in the NEXT user message, and rejects a
    `tool_result` with no matching `tool_use`. Replaying either half alone is a
    hard 400 that kills every subsequent turn in the thread. Persistence does
    not produce tool turns today (chat.py persists final text only), so this is
    a structural invariant, not a live bug fix -- keep it that way."""
```
Cost: ~25 lines, one pass, zero schema surface, zero combinators. Add a unit test asserting an orphaned `tool_use` and an orphaned `tool_result` are both stripped.

---

### F5 — `src/components/feature/meera/MeeraChatPanel.tsx` — stop manufacturing empty bubbles · **P1**

**(a)** Never replay an empty bubble (`:443-449`):
```ts
const history = [
  ...messages
    .filter((m) => m.text.trim() !== '')
    .map((m) => ({ role: m.role === 'brand' ? 'user' : 'assistant', content: m.text })),
  { role: 'user', content: text },
]
```

**(b)** Never leave a dead bubble on screen (`onDone`, `:542-552`) — defense-in-depth for any client talking to an un-upgraded AI container:
```ts
onDone: () => {
  setAwaitingFirstToken(false); setLiveThinkingSteps([]); setPhase('awaiting-input')
  if (assistantText === '') {
    const hadCard = messages.find((m) => m.id === assistantMessageId)?.toolResults?.length
    if (!hadCard) {
      const fallback = "Sorry, I lost my train of thought there. Say that again?"
      setMessages((prev) => prev.map((m) => m.id === assistantMessageId ? { ...m, text: fallback } : m))
      speak(fallback, lang); return
    }
  }
  speak(assistantText, lang)
},
```

**(c)** *(quality, P2)* A tool-only turn currently replays as `content: ''` — the model cannot see that it ever called the tool. Replay a short synthetic marker instead (`"(called show_creators)"`), or better, teach Spring to persist a compact tool summary on the assistant row's `metadataJson`.

---

### F6 — Observability · **P1**

The single most damning fact in this report: **a 28% turn-failure rate was invisible in the logs.** The only trace was an anomalous `release_turn_credit` rate that nobody was watching.

1. `claude.py` — the truncation `logger.warning` in F1 (tool name + stop_reason + partial length).
2. `chat.py` — add `stop_reason` and `output_tokens` to the `ai_spend` log line (`:398-407`). Today `cost_usd` is logged but the token split is not, so §1.4's discriminator cannot be answered from logs at all. This is why the live A/B in Repro A is necessary rather than a query.
3. `chat.py` — a dedicated `log_event(..., "meera_empty_turn", fields={"stop_reason":…, "truncated_tool":…})` WARN on the F2 fallback path, and alert on rate > 1%.
4. Propagate the real `stop_reason` into the `done` event's `finish_reason` instead of hardcoding `"stop"` (`loop.py:170`). The wire protocol has had a `finish_reason` field this whole time and it has been lying.

---

### F7 — Money-path interaction · **MUST SHIP WITH F2 · P0**

F2 makes `final_text` non-empty on a failed turn. That silently flips `chat.py`'s refund decision (`:449-489`) and starts **charging brands 1 credit for a non-answer**. That is a regression the guardrail would introduce, so it ships in the same commit:

```python
# Server-generated finish reasons meaning "the user got an honest apology, not
# an answer". The fallback text IS streamed (Priya's no-silence rail) so
# final_text is non-empty -- but there is nothing to persist and the brand must
# not be charged for a turn Meera could not complete.
FALLBACK_FINISH_REASONS = {"empty_response"}
...
if final_text and finish_reason not in FALLBACK_FINISH_REASONS:
    ... persist_assistant_message ... ; return

if finish_reason in FALLBACK_FINISH_REASONS:
    log_event(logger, logging.WARNING, "meera_empty_turn", workspace_id=workspace_id,
              request_id=request_id, fields={"finish_reason": finish_reason})
    await release_charge("empty_reply_fallback")
    return
```

Guardrails explicitly preserved — I checked each:

- **Kabir FAIL 1 (disconnect farm):** untouched. `disconnected` returns at `:425-435` before any of this and still never refunds.
- **Kabir FAIL 2 (pinned `turn_id`):** untouched. `turn_id` is still the verified `messageId` claim (`:199`).
- **Kabir residual LOW (tool_result-only turn):** untouched — `tool_result_delivered` still keeps the charge, and it is checked before this branch.
- **New branch keys on a server-generated `finish_reason`**, never on anything a client can influence. Not spoofable.
- **F2's retry** forwards nothing to Spring and consumes no `Idempotency-Key` — provably pre-tool-forward, since the retry only fires when `pending_tool_calls` is empty and therefore no `tool_start` was ever emitted (`loop.py:191`) and no `call_tool_endpoint` was ever reached (`:330`).

Add `test_chat_money_path.py::test_empty_response_fallback_streams_text_and_still_refunds`.

---

## 4. THE GUARDRAIL

> **Priya's requirement:** the user always gets either a real reply or an honest recoverable message — never silence.

**Where it goes: `influora-ai/app/tools/loop.py`, in the `not pending_tool_calls` branch (F2).** That is the single funnel every turn shape passes through — text turns, tool turns, local tools, Spring tools, cap, pending-confirm. Anything downstream (`chat.py`, the SPA, `VoiceMode`, a future mobile client) inherits it for free; anything upstream can't see the whole turn. Putting it in `chat.py` would miss nothing today but would need re-deriving `assistant_text` state the loop already owns; putting it in the FE would fix one client and leave the contract broken.

**Recovery ladder (all three, in order):**

| # | Layer | Behaviour |
|---|---|---|
| 1 | `loop.py` (F2) | **One** server-side retry at `max_tokens_retry=2048`, only when the turn was truncated and only once per turn. Invisible to the user (adds ~1–2s). Cost-bounded. |
| 2 | `loop.py` (F2) | If the retry also comes back empty → stream `EMPTY_TURN_FALLBACK` as a real `token` event + `done{"finish_reason":"empty_response"}`. Renders through the existing bubble, reads aloud through existing TTS, **zero frontend changes required**. |
| 3 | `MeeraChatPanel.tsx` (F5b) | If a `done` still arrives with no text and no card, paint the same copy client-side. Defense-in-depth for version skew. |

**Exact UX.** Bubble reads:

> **Sorry, I lost my train of thought there. Say that again?**

Chosen against the persona rails (`persona.py:24-77`): sentence case, contractions, no emoji/symbols, one short sentence, exactly **ONE** thing for the user to do, honest (does not claim a system error, does not invent a reason, does not say "I'm having trouble" and then stop). It is TTS-safe. Composer stays enabled (`phase → 'awaiting-input'`), so the user simply re-sends. **No credit is charged** (F7).

Explicitly rejected:
- *Auto-resend the user's last message client-side* — re-enters `sendTurn`, mints a new `messageId`, charges a second credit. That is the double-spend the `useMeeraStream` contract already forbids (`useMeeraStream.ts:25-28`). Never do this.
- *Silent client-side retry of the SSE stream* — the stream token is single-use and would 401 (same doc).
- *A generic "Something went wrong"* — it is not an error the user caused or can act on, and it reads as broken software. The whole point is that Meera stays in character while being honest.

---

## 5. PRIORITY RANKING

| ID | Item | File(s) | Sev | Ship |
|---|---|---|---|---|
| **F1** | Reconcile an unclosed `tool_use` at `message_stop`; emit `truncated`; carry `stop_reason`; never salvage partial JSON | `influora-ai/app/providers/claude.py:143-194` | **P0** | now |
| **F2** | Empty-turn guard: one bounded retry, then honest fallback text + `finish_reason="empty_response"` | `influora-ai/app/tools/loop.py:88-96, 122-171` | **P0** | now |
| **F3** | `MEERA_CHAT_MAX_TOKENS` 384 → 1536; pin it in `env.example` + deploy env | `influora-ai/app/config.py:271-273`, `env.example`, deploy | **P0** | now |
| **F7** | Refund on `empty_response` so the guardrail can't start charging for non-answers | `influora-ai/app/routes/chat.py:449-489` | **P0** | **same commit as F2** |
| **F4a** | Drop empty-content messages from replayed history | `influora-ai/app/prompt/assembler.py:362-397` | **P1** | now |
| **F5a/b** | Stop replaying empty bubbles; never leave a dead bubble on screen | `src/components/feature/meera/MeeraChatPanel.tsx:443-449, 542-552` | **P1** | now |
| **F6** | `stop_reason` + `output_tokens` in `ai_spend`; `meera_empty_turn` WARN + alert; real `finish_reason` on the wire | `claude.py`, `chat.py:398-407`, `loop.py:170` | **P1** | now |
| **RB** | Land Repro B as regression tests (inverted to assert the fix) | `tests/providers/test_stream_truncation.py`, `tests/routes/test_chat_blank_turn_guard.py` | **P1** | now |
| **F4b** | Strip unpaired `tool_use`/`tool_result` blocks (structural invariant) | `assembler.py` | **P2** | next |
| **F5c** | Replay a marker for tool-only turns so the model can see its own tool calls | `MeeraChatPanel.tsx`, optionally `AiMessage.metadataJson` | **P2** | next |
| **P2-1** | Key `current_tool` by `event.index` instead of a single slot | `claude.py:143-176` | **P2** | next |
| **P2-3** | Catch non-`SpringCallError` exceptions in the tool forward; report `tool_error`, not `provider_timeout` | `loop.py:329-354` | **P2** | next |
| **P2-4** | Check `is_cancelled()` before consuming a stream event, not after | `loop.py:146-148` | **P2** | next |

Note: `assembler.py:314-397`'s tool-block translation is dead code in the live path (§1.5). Keep it — F4b turns it into an enforced invariant rather than an unexercised branch — but it must be labelled as unreachable-today so the next reader doesn't re-diagnose from it, as very nearly happened here.

---

## Data & Training Roadmap

- **Now:** log `stop_reason` per turn. A `max_tokens` rate per tool name is the single most useful AI-health metric this service does not have. Expect `create_campaign` to dominate.
- **Next:** log realized output-token distribution per tool. That is what sizes `max_tokens` empirically instead of by intuition — which is how 384 got picked, and how this happened.
- **Later:** once the distribution is known, consider a two-tier ceiling (narration budget vs. tool-planning budget) selected by whether the previous iteration ended in a tool call. Not worth building until the data says the flat 1536 is costing real money.

---

## Verdict: **BLOCK — P1 confirmed, root cause located, fix specified, guardrail specified**

Do not close this on the ceiling raise (F3) alone. F3 makes the current symptom rare; **F1 + F2 are what make it impossible**, and F2 is Priya's stated requirement in its own right. F7 must ship in the same commit as F2 or the guardrail introduces a billing regression.

Ship **F1 + F2 + F3 + F7** together, then run Repro A step 4 (fresh HYPE thread, 10 turns) and neha's original 25-turn script. Acceptance: **zero blank bubbles, zero `meera_empty_turn` WARNs, and a non-empty `finish_reason` distribution in the logs.**

One process note for Priya, said plainly: this exact failure mode was written down in `wiki/reports/ash-brand-ai-review-2026-07-23.md` P2 item 7 the day before it was found in production. It was ranked P2 because at the time it read as an observability nit. It was not — the missing observability *was* the defect, because a truncated tool-planning turn is indistinguishable from a successful one everywhere in this codebase. Recommend treating "an AI failure mode that is invisible in logs" as P1 by default, regardless of how benign the symptom looks on paper.
