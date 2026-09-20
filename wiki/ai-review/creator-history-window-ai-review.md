# AI review — CREATOR history window (T-CREATOR-CREDITS-SEARCH step 2)

**Reviewer:** Ash (AI/ML reviewer)
**Date:** 2026-09-20
**Change under review:** `db3b837` — `perf(creator): replay only the newest 20 creator turns to the model`
**Worktree:** `influora-credits`, branch `feat/creator-credits-search`
**Files:** `influora-ai/app/prompt/assembler.py`, `influora-ai/app/config.py`, `influora-ai/tests/prompt/test_creator_history_window.py`
**What I ran:** `pytest` in `influora-ai` (1460 passed, 99s); 4 falsifying mutations in an isolated copy outside the worktree (`scratchpad/iso`, restored byte-identical, sha verified); two measurement scripts against the real prompt builders and `app/costs/pricing.py`. No file in the worktree was modified — `git status influora-ai wiki` is clean apart from this report.

---

## How It Works (traced flow)

1. **The client sends the whole thread, plus the live message.** `src/components/creator/MeeraCopilotChat.tsx:471-473` posts `conversation: [...messages.map(...), { role: 'user', content: text }]`. The live user message is therefore **always the last element** of `conversation`. The panel's `messages` state is rehydrated from Spring (`:204`) or, on an empty/failed history read, seeded with a local Meera greeting (`:209`, `:213`).
2. **Spring bounds it at 100.** `MeeraSessionService.DEFAULT_HISTORY_LIMIT = 100` (`influora-api/.../meera/MeeraSessionService.java:125`), applied as a DB `LIMIT` in the no-cursor branch. Only two roles are ever persisted: `USER` (`:404`) and `ASSISTANT` (`:261` the day-one onboarding greeting, `:671` the reply write-back). `MessageRole` also declares `SYSTEM` and `TOOL` (`domain/enums/MessageRole.java:3-8`) but **nothing in `src/main` ever writes either**.
3. **The route takes the list verbatim.** `influora-ai/app/routes/chat.py:613` — `"conversation": body.get("conversation") or []` for the CREATOR branch. There is **no validation** of the list length or of any message's size anywhere on this path.
4. **`assemble_prompt`** (`assembler.py:959`) reads `audience` at `:969`, builds creator Block A/B and the creator tool set at `:977-1001`, then at `:1003-1006`:
   ```python
   conversation = brand_context.get("conversation") or []
   if audience == "CREATOR":
       conversation = _creator_history_window(conversation)
   messages = build_block_c_messages(conversation)
   ```
5. **`_creator_history_window`** (`:799-817`) reads `get_settings().creator_history_turns` (`:814`), returns the input unchanged when the setting is `<= 0` or the thread is already short enough (`:815-816`), else `conversation[-window:]` (`:817`). It slices the **raw** turns, before conversion.
6. **`build_block_c_messages`** (`:820`) converts turn → message, one message per turn *at most*: a blank, tool-call-free `user`/`assistant` turn is **dropped** (`:871-883`, the F4a blank-turn fix), so `len(prompt.messages)` can be less than the window. `role == "tool"` becomes a `user` message wrapped as `unverified_replayed_tool_result` (`:893-901`); replayed assistant tool activity becomes a one-line text summary (`_replayed_tool_calls_summary`, `:910`). **No native `tool_use`/`tool_result` block is ever emitted from replay** — that is the F-08/F-15 rule the comment at `:787-796` records.
7. **`prompt.messages` goes to the provider unchanged.** `chat.py:738` → `run_tool_loop(initial_messages=prompt.messages, ...)` → `providers/claude.py:165-168` → `client.messages.stream(model=CLAUDE_MODEL, messages=...)`. No normalisation, no role repair, no length check between the assembler and Anthropic.
8. **Cache geometry.** Block A (`:295`, `:493`, `:784`) and Block B (`:541`) each carry `cache_control: ephemeral`; the tool schemas are in front of them in render order, so the cached prefix is *tools + system A + system B*. **Block C carries no breakpoint at all** — every replayed message is billed at the full input rate on every turn, and again on each extra loop iteration.

---

## Integration Map

| Layer | File:line | Role in this change |
|---|---|---|
| Browser | `src/components/creator/MeeraCopilotChat.tsx:471-473` | Builds `conversation`; live user message is last. No length trim client-side. |
| Browser | `MeeraCopilotChat.tsx:204-213` | Rehydrates history; seeds a local **assistant** greeting when history is empty. |
| Spring | `MeeraSessionService.java:125, :763-780` | Serves the newest 100 messages, oldest-first. |
| Spring | `MeeraSessionService.java:261, :404, :671` | Only writers of message rows: an ASSISTANT onboarding greeting first, then USER/ASSISTANT pairs. No TOOL rows. |
| AI route | `app/routes/chat.py:613, :645, :738` | Passes the client list through, assembles, hands `prompt.messages` to the loop. |
| Assembler | `app/prompt/assembler.py:799-817` | **The change.** Slices the newest N raw turns for CREATOR only. |
| Assembler | `app/prompt/assembler.py:820-908` | Converts turns to messages; drops blanks; never emits native tool blocks. |
| Config | `app/config.py:554-556` | `CREATOR_HISTORY_TURNS`, default 20, via `_get_int` (`:36-43`, silently falls back on garbage). Read through `get_settings`, which is `@lru_cache(maxsize=1)` (`:682`). |
| Provider | `app/providers/claude.py:165-168` | `CLAUDE_MODEL` = `claude-sonnet-4-5-20250929` (`config.py:68`) — **not** the Haiku `CREATOR_COPILOT_MODEL`, which only `routes/creator_suggestion.py` uses. |
| Pricing | `app/costs/pricing.py:105` | Sonnet 4.5 at $3 / $15 per MTok; cache read 0.1x, cache write 1.25x (`:59-60`). |
| Tools | `app/tools/creator_schemas.py:179, :205, :316, :334` | The six creator tools; `get_creator_tool_schemas` (`:411`) returns `[]` for an absent list. |

---

## The five questions

### 1. Does the window break anything mechanically?

**No orphaned pairs, and the live turn can never be dropped — the docstring's reasoning holds. One semantic regression, and one pre-existing exposure the window sits on top of.**

- **Tool pairs: safe, and for two independent reasons.** `build_block_c_messages` emits a native `tool_use`/`tool_result` block on *no* path (`assembler.py:820-908`; `_replayed_tool_calls_summary:910-931` exists precisely so it doesn't). So the F-08/F-15 rule at `:787-796` still holds after the slice: there is no native pair to halve. Independently, **no `tool` or `tool_calls` turn ever reaches this code for a creator**: Spring writes only USER/ASSISTANT rows (`MeeraSessionService.java:261/404/671`) and the panel maps everything to `user`/`assistant` (`MeeraCopilotChat.tsx:472`). The `role_key == "tool"` branch (`:893`) is unreachable from the creator path today. If a future client does send tool turns, the window can leave a `unverified_replayed_tool_result` user message whose asking assistant turn was dropped — an untrusted-labelled text orphan, not an API error. Worth pinning before that client exists (P2-3).
- **The current user message is never dropped.** The slice is `conversation[-window:]` with `window >= 1` guaranteed by the `<= 0` guard (`:815`), and the live message is `conversation[-1]` (`MeeraCopilotChat.tsx:473`). Verified by repro across 0–99 prior turns: `messages[-1]` is `user` in every case.
- **The regression the window does introduce: the replayed history now routinely *starts* on an assistant turn that answers a question the model can no longer see.** For a real creator thread the persisted history is `[ASSISTANT greeting, USER, ASSISTANT, …, ASSISTANT]` plus the live USER, so its length is even; with an even window of 20, `conversation[-20:]` starts at an even index — an assistant turn — every time the thread exceeds 20 messages. The model's first input is half of a conversation, presented under the `replayed_assistant_message` label with nothing it was replying to.
- **Pre-existing exposure the window does *not* cause and does *not* fix (see P0-1).** Because Spring's first row is the ASSISTANT onboarding greeting (`MeeraSessionService.java:261`), `prompt.messages[0]["role"] == "assistant"` on a creator turn **with or without** the window. My repro shows assistant-first at 6, 19 and 20 messages (no window applied) as well as at 21, 22, 41 and 100 (windowed). Claude Code's own `claude-api` reference states the Messages API rule as "First message must be `user`"; the installed SDK's `message_create_params.py` docstring documents consecutive same-role merging and assistant-prefill but is silent on the first-message rule. Nothing in this repo guards or tests it, and nothing between `assembler.py:1006` and `claude.py:168` repairs it. This needs one real call to settle, not more reading.

### 2. What does Meera lose at 20 turns?

Recoverable vs. gone, checked against the six tools in `creator_schemas.py`:

| Dropped from text at turn 5, asked about at turn 30 | Recoverable? | Evidence |
|---|---|---|
| A **platform deal's** brief id ("that brief") | **Yes** | `get_my_deals` returns deals; `get_brief`'s description states "a deal's brief_id comes from an earlier get_my_deals result" (`creator_schemas.py:205-225`). |
| A **pasted** brief's id (`BR-123`, typed by the creator) | **No** | `get_brief` requires `brief_id` or `deal_id` (`:228-236`) and the only other source the description names is "a brief id the creator herself gave in this chat". There is **no list-pasted-briefs tool** — the catalogue is `get_my_deals, get_brief, estimate_my_rate, get_my_metrics, check_deal_risks, draft_reply`. Once that turn falls out, the id is unreachable and Meera must ask the creator to paste it again. |
| A **rate** quoted earlier | **Yes, and windowing helps** | `creator_persona.py:84-87` already forbids recalling a number from earlier in the chat — every number must come from context or a fresh tool result. Re-calling `estimate_my_rate` is the mandated behaviour, so dropping the text costs nothing and removes the temptation. |
| A **brand name** | **Yes** | `get_my_deals` carries it (`:179-200`); `check_deal_risks` takes `deal_id`/`brief_id` (`:316-331`). |
| A **preference stated mid-chat** ("don't pitch me alcohol", "call me Ri") | **No** | Block B carries only the *saved* settings from the Meera settings page (`_creator_rules_lines`, `assembler.py:573`; the drift test pins the field list). Anything said conversationally lives only in Block C text. |
| The creator's **"this one's strategic, I'll go below my floor"** | **No, and it is load-bearing** | `draft_reply` may set `strategic_override` only when "the creator has said **in this conversation**…" (`creator_schemas.py:344-350`). That consent is readable only from replayed text. Dropping it fails safe (Meera re-prices at the floor) but makes the creator repeat a decision she already gave. |

**One more structural loss that is independent of N:** tool activity is never persisted (only USER/ASSISTANT rows exist), so replayed creator history contains **no record that Meera called `get_brief` or what it returned** — only her 1-3 sentence narration (`creator_persona.py:75-76`). Tool-derived facts were already thin in history; the window removes the creator's own words, which is the part nothing else can reconstruct.

**Is 20 too small? Yes.** The persona mandates a tool chain before a draft: `check_deal_risks` first, `estimate_my_rate` first when naming a number (`creator_schemas.py:337-341`), and `get_brief` before summarising terms (`:209-212`). With a creator turn between each, one brief → risks → rate → draft → one revision is roughly 10-14 messages. At 20 (= 10 exchanges, and the onboarding greeting eats one) the opening of a **single working arc** — which brief, what the brand asked, what she decided — falls out of the window before that arc finishes. **I would set 40** (`CREATOR_HISTORY_TURNS=40`): two full arcs, still a hard bound against the 100 Spring serves, and only about ₹0.63/turn more in the worst case (₹0.32 typical) before the caching fix in P1-1 — after which the difference is about ₹0.04/turn. 40 is a judgement call from the tool chain, not from usage data; there is no session-length telemetry in this repo, and `chat_turn_started` already logs `conversation_len` (`chat.py:528`), so one week of live logs should replace my number with a measured p95.

### 3. Is the default in the right place?

- **A turn count is the wrong unit.** The thing being bounded is tokens, and per-message size is unbounded: `chat.py:613` accepts the list verbatim with no per-message or total length validation, and the creator UI lets a brief be pasted into the chat. Twenty "haan theek hai" turns are ~300 tokens; twenty pasted-brief turns are tens of thousands. So the cost ceiling the window advertises is not actually a ceiling. **A token (or char) budget with a turn floor** is the right shape: walk backwards accumulating `len(content)` until a char budget is spent, keep at least K turns, always keep the last. That is ~8 lines in the same function and makes the cost claim true instead of typical-case true.
- **The value's plumbing is sound but under-guarded.** `_get_int` (`config.py:36-43`) falls back to 20 on garbage instead of raising, so `CREATOR_HISTORY_TURNS=2O` silently reads as 20. There is no floor: `1` is accepted and leaves Meera with literally no history, with no warning at boot.
- **Cache impact — this is the important part, and it cuts against the design.** Block C sits after the last breakpoint (`assembler.py:541` is the final `cache_control`), so a sliding window **cannot invalidate the cached A/B prefix**; that part is safe. But Block C is also **never cached today**, and a *sliding* window is precisely what makes caching it impossible in future: on turn N the messages are `N-20..N`, on turn N+1 they are `N-19..N+1`, so the message prefix changes every turn and any breakpoint inside `messages` would miss 100% of the time. A **growing** history, by contrast, has a stable prefix and can be cached at 0.1x. Measured with `pricing.py`'s own rates (₹88/$1, ~60 tok per replayed message, a 2-call tool turn):
  - 100 messages replayed uncached: **₹3.17**
  - 20-message window, uncached: **₹0.63**
  - 100 messages cached (read) + a write of the newest turn: **₹0.36**

  So the lossless option is cheaper than the lossy one — on a cache hit. Break-even is ~83% hit rate against the 5-minute TTL (a cold write of a 100-message history is ₹1.98), which an active back-and-forth session clears comfortably and a slow-typing creator does not. Hence the recommendation is the **hybrid**, not "drop the window": add a breakpoint at the end of the replayed history and keep a *generous* window as a context-length ceiling rather than as the cost lever.

### 4. Tests

Three tests, all passing (`pytest tests/prompt/test_creator_history_window.py` → 3 passed in 0.11s), and the full suite is green (1460 passed). Each was falsified in an isolated copy:

| Mutation (in `scratchpad/iso`, never the worktree) | Result | Reads on |
|---|---|---|
| **F1** delete `if audience == "CREATOR": conversation = _creator_history_window(...)` from `assemble_prompt` | **RED** (1 failed) | The red-first claim in the commit message is true. |
| **F3** `conversation[-window:]` → `conversation[:window]` (keep the oldest 20) | **RED** | Direction is pinned. |
| **F4** remove the audience guard so BRAND is windowed too | **RED** (`test_brand_history_is_not_windowed`) | Test 3 earns its place. |
| **F2a** silently drop the newest turn whenever the thread ends in a `user` role — i.e. **throw away the creator's live question on every real request** | **GREEN — 3/3 pass** | The fixtures are blind to the production shape. |
| **F2b** only window past 25 messages (so 21-25 replay everything) | **GREEN — 3/3 pass** | The boundary is unpinned. |

F2a is the finding. `_conversation(n)` (`test_creator_history_window.py:19-22`) alternates from `user`, so `_conversation(30)` **ends on `turn-29`, an assistant turn** — a shape that never occurs in production, where `MeeraCopilotChat.tsx:473` always appends the live user message last. Every assertion in the file (`:41-46`) is satisfied by a window that mangles the live turn. `assert "turn-29" in rendered` (`:43`) does pin "the newest turn survives" *for the fixture*, and the fixture is the wrong shape.

**Cases that should be pinned and are not:**

1. **The newest turn is never dropped, in the production shape.** Assert on `prompt.messages[-1]`, from a conversation that ends with the live user message. Kills F2a.
2. **A conversation of exactly 20, and of exactly 21.** `test_a_short_creator_history_is_untouched` (`:49-51`) tests 5; nothing tests the boundary. Kills F2b.
3. **BRAND stays unwindowed** — this one *is* pinned (`:54-63`) and dies under F4. Keep it, but note it now pins an unbounded client-controlled Block C open for BRAND (P2-4).
4. **`0` disables the window**, and **a non-default window** (e.g. 5) behaves. Neither is testable as written: `get_settings` is `@lru_cache(maxsize=1)` (`config.py:682`) and the test has no `get_settings.cache_clear()` / monkeypatch fixture. That same cache makes `assert window == 20` (`:37`) environment-coupled — the suite fails for the wrong reason in any shell or container that sets `CREATOR_HISTORY_TURNS`.
5. **`prompt.messages[0]["role"] == "user"`.** One assertion would have surfaced the exposure in P0-1 and would document the invariant the provider call depends on.
6. **The docstring's "one input turn is one replayed message".** Not exactly true — blank turns are dropped *after* the window (`assembler.py:871-883`), so a thread with persisted blank rows replays fewer than N. Either pin it or soften the docstring.

Suggested replacement fixture (three lines, kills F2a and F2b):

```python
def _live_thread(prior: int) -> list[dict]:
    """Production shape: an ASSISTANT onboarding greeting, alternating turns, live user message last."""
    turns = [{"role": "assistant" if i % 2 == 0 else "user", "content": f"turn-{i}"} for i in range(prior)]
    return turns + [{"role": "user", "content": "LIVE"}]

# then, for prior in (19, 20, 21, 40):
#   assert prompt.messages[-1]["content"].endswith(...)  # "LIVE" always replayed
#   assert len(prompt.messages) == min(prior + 1, window)
```

### 5. Cost

**The claim holds, within ~7%, under an assumption set PLAN.md does not state — and it is a small slice of the real worst case.**

Measured inputs (script against the real builders; token bands at 3.5-4.0 chars/token):

| Piece | Measured | PLAN §5 assumption |
|---|---|---|
| Creator persona + directives (Block A) | 6,186 chars ≈ **1,546-1,767 tok** | ~1,770 ✔ (top of band) |
| Six creator tool schemas (JSON) | 8,892 chars ≈ **2,223-2,541 tok** | ~2,615 ✔ (≈3% over) |
| Creator profile (Block B, fully populated) | 572 chars ≈ **143-163 tok** | 200-500 ✖ (over by ~2x, immaterial) |
| Untrusted wrapper overhead per replayed message | **51 chars, flat** | not modelled |

Rebuilt with `pricing.py`'s own rates (Sonnet 4.5 $3/$15, cache read 0.1x, write 1.25x — `pricing.py:105, :59-60`), ₹88/$1, ~60 tok per replayed message, 250 output tokens:

| Scenario | Model | PLAN §5 |
|---|---|---|
| 100 msgs, 2 calls, cold prefix (pre-step-2 worst) | **₹5.19** | ₹5.39 (−4%) |
| 20 msgs, 2 calls, cold prefix (post-step-2 worst) | **₹2.65** | ₹2.86 (−7%) |
| 20 msgs, 1 call, warm prefix (typical) | **₹0.77** | ₹1.31 (PLAN conservative by 1.7x) |

So "worst case ₹5.39 → ₹2.86" is **reproducible and honest**, and the table's typical column (₹1.31, unchanged by step 2) is conservative, not wrong. Three caveats that matter for the decision:

- **After step 2 the dominant term is the cache write, not Block C.** Of the ₹2.65, ₹1.56 is the cold-prefix write and only ₹0.63 is the history. The next rupee is in cache hit rate, not in history length.
- **PLAN §5's worst case understates the ceiling.** `meera_chat_max_tokens = 1536` and `tool_loop_max_iterations = 6` (`config.py:416, :393`), so output alone can reach 6 × 1536 × $15/MTok ≈ **₹12.2** on one chat message — five times the ₹2.53 the window saves. The window is a real saving on the smaller half of the bill.
- **Two smaller inconsistencies.** PLAN uses ₹88/$1 while `pricing.py:145` uses ₹83 for the Sarvam conversion — one FX constant, two values. And PLAN §5 assumes "Haiku 4.5 for briefs and paid search" while **creator chat runs on Sonnet 4.5** (`claude.py:165` → `config.py:68`); `CREATOR_COPILOT_MODEL` (Haiku) is only read by `routes/creator_suggestion.py:296`. If Haiku was ever intended for creator chat, that is a 3x lever nobody has pulled, and it dwarfs this change.

---

## Findings

### P0-1 — Creator Block C starts with an `assistant` message on every turn; nothing guards or tests it
- **Where:** `influora-api/.../MeeraSessionService.java:261` (first persisted row is the ASSISTANT onboarding greeting) → `MeeraCopilotChat.tsx:204` → `chat.py:613` → `assembler.py:1006` → `providers/claude.py:165-168`. Also reachable via the locally seeded greeting at `MeeraCopilotChat.tsx:209/213`.
- **Issue:** `prompt.messages[0]["role"] == "assistant"` for every creator turn, before and after this change. Claude Code's `claude-api` reference states the Messages API requires the first message to use the `user` role; the installed SDK performs no client-side check and `claude.py` does no normalisation, so if the rule is enforced the turn returns a 400 that surfaces as a generic stream failure. Every route test uses a single `{"role": "user"}` turn (`tests/routes/test_chat_creator_audience.py:102`) with a mocked provider, so the suite cannot see it, and `T-MEERA-CREATOR-PHASE-A/TASKS.md:307` leaves "Deployed to production" unchecked — this path may never have run against the real API. **Not caused by this commit**, and the window does not change it (assistant-first either way) — but it is on the traced path and it decides whether step 2's premise (100-message threads being billed) is even real.
- **Fix:** one real Sonnet call with `messages=[{"role":"assistant",...},{"role":"user",...}]` settles it (minutes, cents). If it 400s: drop leading non-`user` messages in `build_block_c_messages`, or prepend the onboarding greeting as a `user`-labelled context line, and pin `messages[0]["role"] == "user"`.
- **Gain:** either removes a total-breakage risk from the creator chat, or converts an unknown into a pinned invariant for ~10 lines.

### P1-1 — The window is a lossy lever where a lossless, cheaper one exists, and a *sliding* window forecloses it
- **Where:** `assembler.py:541` (last cache breakpoint) and `:817` (the slice).
- **Issue:** Block C has no `cache_control`, so today every replayed message costs full input rate. Measured: 100 messages uncached over a 2-call turn = ₹3.17; the 20-turn window = ₹0.63; the *same 100 messages* served from cache = ₹0.36 + ₹0.04 write. Caching is cheaper **and** loses no memory. A sliding window makes the messages prefix change every turn, so a message-level breakpoint would never hit — the change permanently closes the better door. Break-even is ~83% of turns landing inside the 5-minute TTL (a cold write of a 100-message history is ₹1.98), which an active session clears and an idle one does not.
- **Fix:** put a `cache_control: ephemeral` breakpoint on the last replayed Block C message (4 breakpoints max, 2 used) and treat the window as a context ceiling at 40-60 rather than the cost lever. Verify with `usage.cache_read_input_tokens` on a live turn, which `providers/claude.py` already collects.
- **Gain:** ~₹0.27/turn better than the window at 100 messages, with none of the memory loss — and it is the lever PLAN §5 already names as the platform's cost strategy.

### P1-2 — 20 turns cuts inside a single working arc; the tools cannot recover a pasted brief id or a mid-chat decision
- **Where:** `config.py:554-556`; `creator_schemas.py:205-236` (`get_brief` needs an id), `:337-350` (`draft_reply`'s `strategic_override` reads "said in this conversation").
- **Issue:** the mandated chain (`get_brief` → `check_deal_risks` → `estimate_my_rate` → `draft_reply`, each with a creator turn between) plus one revision is ~10-14 messages; the onboarding greeting spends one slot. At 20 the creator's opening context leaves the window mid-task. A pasted brief's id is unrecoverable (no list-briefs tool), as is any preference or "this one's strategic" said conversationally.
- **Fix:** `CREATOR_HISTORY_TURNS=40`, and replace my number with the p95 of `conversation_len`, which `chat.py:528` already logs, after one live week.
- **Gain:** removes the most likely "Meera forgot what we were doing" complaint for ~₹0.04/turn once P1-1 lands.

### P1-3 — The tests are blind to the production conversation shape; a window that deletes the live user message passes all three
- **Where:** `tests/prompt/test_creator_history_window.py:19-22` (fixture alternates from `user`, so it ends on an assistant turn), `:39`, `:41-46`.
- **Issue:** proven by mutation, not by reading — **F2a** (drop the newest turn whenever the thread ends in `user`, i.e. every real request) and **F2b** (only window past 25) both leave 3/3 green. The 20/21 boundary is unpinned, `0`-disables is untested, and a non-default window is untestable because `get_settings` is `@lru_cache(maxsize=1)` (`config.py:682`) with no `cache_clear()` in the test; `assert window == 20` (`:37`) also couples the suite to the shell environment.
- **Fix:** the `_live_thread` fixture above; assert on `prompt.messages[-1]` and `prompt.messages[0]["role"]`; add `prior in (19, 20, 21, 40)`; add a `monkeypatch` + `get_settings.cache_clear()` fixture and test `0` and `5`.
- **Gain:** the difference between a test that pins the contract and one that pins a fixture — this is the same shape as the ledger entries about gates that greened their own blind spot.

### P1-4 — A turn count cannot bound tokens, because nothing bounds a turn
- **Where:** `chat.py:613` (no validation of `conversation`), `assembler.py:817` (counts turns).
- **Issue:** the window's whole justification is a token bill, and per-message size is unbounded — one pasted brief in history can exceed the entire 20-turn budget, so the ceiling is typical-case only. The 51-char untrusted wrapper (measured) is also unmodelled per message.
- **Fix:** window on a char/token budget with a turn floor and a mandatory last turn (~8 lines in `_creator_history_window`); optionally reject absurd `conversation` payloads at the route.
- **Gain:** makes the cost bound real, and closes the amplification path where a client controls both the count and the size of Block C.

### P2-1 — `if audience == "CREATOR"` is written twice in `assemble_prompt`
- **Where:** `assembler.py:977` and `:1004`. **Issue:** two branches on the same condition; a future audience (agency) added to one and not the other silently loses the window with no test to notice. **Fix:** fold the windowing into the existing CREATOR branch. **Gain:** removes a desync class for a one-line move.

### P2-2 — No floor, and a silent fallback, on `CREATOR_HISTORY_TURNS`
- **Where:** `config.py:554-556`, `_get_int:36-43`. **Issue:** `1` is accepted (Meera left with no history at all, no warning); a typo (`2O`) silently becomes the default. **Fix:** clamp to a floor (e.g. 4) or log a startup warning below some threshold. **Gain:** a config typo can't quietly lobotomise the assistant.

### P2-3 — The no-orphan claim is true but unpinned, and the "one turn, one message" claim is slightly false
- **Where:** `assembler.py:806-809` (docstring) vs `:871-883` (blank turns dropped after the window) and `:893-901` (the `tool` branch, currently unreachable). **Fix:** one test asserting no replayed message ever carries a native `tool_use`/`tool_result`, plus a note that a windowed tool turn can orphan a text-labelled result if a client ever sends one; soften "one input turn is one replayed message" to "at most one". **Gain:** the F-08/F-15 rule stops depending on a comment.

### P2-4 — BRAND Block C is still unbounded, and the new test now pins it that way
- **Where:** `test_creator_history_window.py:54-63`, `assembler.py:1004`. **Issue:** the BRAND path takes an arbitrary client-supplied list at full input price with no count or size limit; the change is correctly scoped to CREATOR, but the new test makes "BRAND replays everything" a pinned expectation rather than a known gap. **Fix:** leave the test, add a comment pointing at the BRAND ticket. **Gain:** the gap stays visible instead of looking deliberate.

### P2-5 — PLAN §5 hands the cost model to the reader without its assumptions, and mixes FX
- **Where:** `.proof-os/tasks/T-CREATOR-CREDITS-SEARCH/PLAN.md` §5; `pricing.py:145` (₹83) vs PLAN (₹88); `config.py:416/393` (output ceiling). **Fix:** state tokens/message, calls/turn and cache state beside the three rupee figures; add the output-bound worst case (₹12.2); pick one FX constant. **Gain:** the next reviewer can reproduce the numbers instead of re-deriving them, as I had to.

### P2-6 — Creator chat runs on Sonnet 4.5 while a Haiku creator model sits unused on this path
- **Where:** `claude.py:165` → `config.py:68`; `CREATOR_COPILOT_MODEL` (`config.py:194`) read only by `routes/creator_suggestion.py:296`. **Issue:** a 3x per-token difference on the highest-volume creator route, unexamined by a plan whose §5 explicitly assumes Haiku elsewhere. **Fix:** an eval on real creator turns before any switch — this is a quality decision, not a config one. **Gain:** potentially larger than everything else in this change combined; must not be done on cost alone.

---

## Verdict

**SHIP WITH P1 FIXES.**

The slice itself is correct: it cannot orphan a tool pair (replay never emits native blocks, and no tool turn is ever persisted for a creator), it cannot drop the live user message, and it leaves the cached A/B prefix untouched. The BRAND path is genuinely unchanged and a test dies if that guard is removed. The cost claim reproduces within 7%.

What should not ship as-is: the tests cannot see the shape every real request takes (a mutation that deletes the creator's live question passes 3/3), the unit cannot bound the thing it claims to bound, 20 truncates inside one working arc with no tool able to recover a pasted brief id, and the lever chosen is dominated by a message-level cache breakpoint that a *sliding* window permanently forecloses. P1-1 through P1-4 are all small, local edits to the same function and the same test file.

Separately and urgently: **P0-1 is one API call away from being settled**, and it decides whether creator chat works at all past the greeting. It is not this commit's fault, and it should not be this commit's blocker — but it should not stay unknown either.
