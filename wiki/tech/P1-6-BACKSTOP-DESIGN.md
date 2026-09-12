# P1-6 residual — a safe server-side credit backstop

**Arjun Kapoor** · 2026-09-12 · **NOT BUILT. Needs Kabir, then Priya, before anyone writes code.**
Context: `wiki/processes/P1-RUN-REPORT-0912.md` · Python fix shipped as `7595290`

---

## What is already closed

`release_early()` (shipped) refunds the send-time charge on all five terminal paths that end the
request before the streaming generator runs. That is P1-6 as reported, and it is done.

## The one case left

**If influora-ai is unreachable from the browser, no Python code runs at all** — DNS failure,
connection refused, a 502 at the edge, the process restarting mid-request. Java charged at SEND, and
there is nobody to call the refund. During an outage every retry costs the brand another credit.

This needs a server-side backstop. One was written this week and **rejected** — understanding why is
the whole point of this document.

## Why the rejected design was wrong

It refunded any turn whose newest row was a USER message older than 15 minutes, charged, with **no
`meera.persist_writeback` row**. It treated the **absence** of a writeback row as proof that nothing
was delivered. It is not proof. Two paths reach that exact state with the reply already in the
brand's hands:

1. **The deliberate disconnect path.** `chat.py` returns on `disconnected` *before*
   `persist_assistant_message` and intentionally never releases — that omission **is** Kabir's
   FAIL 1 fix. Read every SSE token, abort, wait out the grace window, send again, and the sweep
   returns the credit for a reply you already have. The free-turn farm, reopened.
2. **Tool-result-only turns.** `chat.py`'s `if tool_result_delivered:` branch keeps the charge and
   persists nothing. So **every ordinary `show_creators` or `calculate_budget` turn answered without
   narration** would be silently refunded on the brand's next send. Not an exploit — just wrong,
   continuously, for normal use.

**The lesson to carry:** a writeback row is written on exactly one success path, so its absence means
many different things. Any backstop keyed on absence must key on the absence of something written on
**every** path.

## Proposed design — stream-token redemption

Rather than marking every terminus (four call sites, each a chance to miss one), mark the **single
entry point**.

Java already mints a single-use stream token per turn, and Python must verify it before it can emit
anything. So:

1. When Python successfully verifies a stream token, it tells Java once: **this turn started.**
   Record it in the existing `IdempotencyService` ledger under a new scope, e.g.
   `meera.turn_started`, keyed on the same server-minted `messageId` the charge and writeback
   already use. No new table.
2. The backstop refunds a charged turn only when, after a grace window, there is **no
   `turn_started` row at all** — meaning Python never reached the point where it could produce
   output, so nothing was delivered and nothing else will ever refund it.

**Why this is safe where the rejected design was not:** delivery is impossible without verification.
So "never started" cannot be true for a turn whose reply reached the brand — by construction, not by
a guard someone has to remember.

| Scenario | `turn_started`? | Backstop | Correct? |
|---|---|---|---|
| Python unreachable from browser | absent | **refunds** | ✅ the case we are fixing |
| Brand closes the tab before connecting | absent | **refunds** | ✅ nothing was delivered |
| Reads all tokens, then disconnects | present | keeps charge | ✅ Kabir FAIL 1 preserved |
| Tool-result-only, no narration | present | keeps charge | ✅ the second bug, closed by construction |
| Clean success | present | keeps charge | ✅ |
| Spend gate / context 403 | present | `release_early` already refunded | ✅ and `release` is idempotent |

## Known limitation — state it, do not hide it

If Python verifies the token and **then** crashes before streaming anything, `turn_started` exists,
so the backstop will not refund and the brand loses a credit for our failure. The non-crash version
of that window is already covered by `failed_before_stream`. Narrowing the crash case further would
mean charging on first token instead of at send — a larger money-path change with its own review, and
explicitly **not** part of this ticket.

## Build notes for whoever picks this up

- **Do not** reuse `PERSIST_WRITEBACK_SCOPE`. A distinct scope, or the two signals collapse again.
- The backstop must run **outside** `doSendTurn`'s transaction. `IdempotencyService#executeOnce`
  commits its reservation in its own transaction while the guarded effect runs in the caller's, so
  sweeping from inside `doSendTurn` lets a later failure roll the refund back while the release
  ledger row stays committed — the turn is then permanently marked released with the credit never
  returned.
- Grace window: comfortably longer than `AI_RESERVATION_CHAT_TTL_SECONDS` (300s). Shortening it is
  a **security change**, not a tuning change. Say so in the constant's javadoc.
- Tenant check first: never read another workspace's messages, even to count them.
- **Tests must be falsified.** The rejected attempt shipped three tests that never executed an
  assertion (`UnfinishedStubbing` from a nested `lenient().when()`), and one of its tests *asserted
  the vulnerable behaviour*. Required: a test proving a **delivered-then-disconnected** turn stays
  charged, and one proving a **tool-result-only** turn stays charged. Those two are the ones that
  would have caught the rejected design, so write them first.

## Recommendation

Worth building, not urgent. It only pays off during an influora-ai outage, and the shipped Python
fix already covers every in-process failure. I would rather it wait for Kabir than ship fast — this
is the third attempt at P1-6's server side, and the two failures both looked correct in review.
