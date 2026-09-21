# CEO → CTO: 50 Questions on the Brand-Side AI

**From:** Swapnil Maruti (CEO)
**To:** Priya (CTO)
**Date:** 2026-09-12
**Subject:** How does our AI actually work, and what does it actually do for a brand?

---

## Why I'm asking

We sell Influora to brands on the promise that Meera does the heavy lifting. I need to know, with
code behind every claim, what she really does, where she really is, and where we are selling
something that is a draft, a mock, or a flag that is off in production.

**Rules for your answers:**
1. Every answer cites `file:line`. Open the file. If you did not open it, write `UNVERIFIED`.
2. If the honest answer is "it does not work" or "it is behind a flag that is off", say that first.
3. Business first: after each technical answer, one line on what it means for a brand.
4. No answer longer than ~8 lines. I want the truth, not a thesis.

---

## SECTION A — What the AI actually is (Q1–Q8)

**Q1.** What model(s) do we call for the brand chat, exactly? Model ID, provider, where it is
configured, and who can change it without a deploy.

**Q2.** We have `app/providers/claude.py`, `gemini.py`, `sarvam.py`. Which of the three actually
serves a brand turn today? What is each one for? Is there a fallback chain, and has it ever fired?

**Q3.** Walk me through one brand message end to end — browser keystroke → Spring → Python →
Anthropic → back to the screen. Name the file at each hop.

**Q4.** Why is the AI a separate Python service (`influora-ai`) instead of living inside the Java
API? What does that split buy us, and what does it cost us when the Python service is down?

**Q5.** What happens to a brand mid-conversation if `influora-ai` is unreachable? Do they see an
error, a spinner forever, or a fallback reply?

**Q6.** `app/prompt/persona.py` is the system prompt. Who wrote it, how long is it, and what is it
costing us per turn in input tokens? Is prompt caching actually on, and can you prove it?

**Q7.** Streaming — is the brand seeing tokens as they generate, or a spinner then a full block?
Which file owns the stream, and what happens if the stream drops halfway?

**Q8.** Does Meera remember a brand between sessions, or does every conversation start blind? If
there is memory, where is it stored and what exactly is in it?

---

## SECTION B — Where the AI touches the brand (Q9–Q18)

**Q9.** List every single screen in the brand product where AI runs. Route path + component file.
I want the complete list, not the highlights.

**Q10.** `src/pages/brand-meera.tsx` — is this the real brand AI surface, or a demo page? Is it
linked from the brand dashboard nav, and can a logged-in brand reach it today?

**Q11.** Onboarding — when a brand signs up and pastes their store URL, what does the AI do with it?
Which endpoint, what comes back, and does it actually populate their profile or just display?

**Q12.** `analyze_site` — what does it really extract from a brand's website? Products? Prices?
Tone? Show me the output shape. How often does it return garbage or nothing?

**Q13.** Campaign creation by AI — walk me through it. Does the brand end up with a real row in the
campaigns table, or a draft object that dies when they close the tab?

**Q14.** When Meera creates a campaign, is it ever live without the brand clicking something? Show
me the code that guarantees DRAFT.

**Q15.** Creator discovery — when a brand asks "find me creators", what actually runs? Is it a real
match algorithm, a DB query with filters, or an LLM guessing names?

**Q16.** Budget — `CalculateBudgetExecutor`. What formula? Where do the numbers come from? Is it
real market data or a hardcoded multiplier?

**Q17.** Analytics/performance — `GetCampaignPerformanceExecutor`. What is the data source? My memory
says campaign analytics are CREATOR_REPORTED. Does Meera tell the brand that, or does she present
self-reported numbers as verified platform data?

**Q18.** Payments — `RequestPaymentExecutor` and `ConfirmLaunchExecutor`. Can the AI move money? What
is the exact human confirmation step, and can it be skipped?

---

## SECTION C — What the AI can actually DO (Q19–Q28)

**Q19.** Give me the complete tool list Meera can call on the brand side, with a one-line
"what a brand gets" for each.

**Q20.** For each tool: is it READ or WRITE? Which ones mutate our database?

**Q21.** Which tools are enabled in production right now, and which are behind a flag that is
currently off? Name the flag and its live value if you can determine it.

**Q22.** `ToolCallValidator` — what does it actually block? Give me one concrete example of a bad
tool call it stops.

**Q23.** My memory says there was an on-behalf scope bug — the JWT was minted read-only and 4 of 6
tools silently 403'd, including analytics. Is that fixed in code, and is the fix deployed?

**Q24.** `OnBehalfTokenService` — when Meera acts "as" the brand, what is the blast radius of that
token? Scope, TTL, and what stops it being replayed.

**Q25.** Can a brand's Meera ever read another brand's data? What is the isolation boundary, and
what test proves it?

**Q26.** `present_options` — is this a real interactive card the brand taps, or does the model just
describe options in text?

**Q27.** Campaign templates — the persona says recommend by name. Where does that template list come
from, and is it live data or a static file?

**Q28.** What can a brand ask Meera that she will confidently get wrong? Give me the three worst
failure modes you know of.

---

## SECTION D — Trust, accuracy, safety (Q29–Q36)

**Q29.** `app/prompt/untrusted.py` — what is this defending against, and does it actually work when a
brand pastes a store URL whose page contains injected instructions?

**Q30.** `app/security/ssrf_guard.py` — a brand pastes a URL and we fetch it. What stops that being
pointed at our own internal network?

**Q31.** `app/security/redaction.py` — what gets redacted, from what, and does any brand PII reach
Anthropic's servers today?

**Q32.** `brand_safety.py` route and prompt — what is this for, who calls it, and is it wired to
anything a brand sees?

**Q33.** Hallucination control — what stops Meera inventing a creator who does not exist, a price we
do not charge, or a result we never delivered?

**Q34.** Do we log every AI interaction? `MeeraInteractionLogService` — what is captured, retained
how long, and could we reconstruct a disputed conversation?

**Q35.** If a brand says "Meera told me X and it was wrong", what is our evidence trail, and how long
does it take to pull?

**Q36.** The word "escrow" is banned in user copy. Does the Meera prompt comply, and is there a gate
that keeps it compliant?

---

## SECTION E — Cost and limits (Q37–Q42)

**Q37.** What does one brand conversation cost us in API spend? Give me a real number with the math.

**Q38.** `app/costs/gate.py`, `spend_tracker.py`, `worker_guard.py` — what is the hard ceiling, and
what happens to a brand mid-sentence when it is hit?

**Q39.** `AICreditService` — do brands consume credits? Who pays, how are they priced, and is this
live or spec?

**Q40.** What is our single largest cost line in a brand turn — prompt, context assembly, tool
results, or output? Where is the fat?

**Q41.** If 100 brands used Meera hard tomorrow, what breaks first — cost gate, rate limit, Anthropic
throughput, or our own DB?

**Q42.** `BrandContextAssembler` — how many tokens of brand context do we stuff into every turn?
Is any of it dead weight?

---

## SECTION F — What is real vs what we are selling (Q43–Q50)

**Q43.** Blunt: what percentage of the brand-side AI is live, working, and reachable by a real logged-
in brand today? Show me your denominator.

**Q44.** What are we saying on the marketing site about brand AI that the code does not currently
back up? Cite both sides.

**Q45.** Meera vs a brand just using ChatGPT — what can she do that ChatGPT structurally cannot?
Name the moat in one sentence, then prove it with a file.

**Q46.** Has a real human ever completed a full brand journey with Meera on live — signup to campaign
to creator to payment? If yes, where is the evidence? If no, say no.

**Q47.** Every feature flag gating brand AI: name, file, default value, and live value if known.

**Q48.** What in the brand AI is uncommitted, on a branch, or built-but-not-deployed right now?

**Q49.** What would you fix first if I gave you one week and one engineer — and what does that fix
get us in brand outcomes?

**Q50.** What have I not asked that I should have? The thing about our brand AI that would embarrass
us if a customer found it before we did.

---

*Answers go to `wiki/decisions/CTO-AI-BRAND-50A-0912.md`.*
