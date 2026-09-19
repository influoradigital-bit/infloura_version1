# Fifteen questions on Meera for Creators, answered from the code

**For**: Swapnil (CEO)
**From**: Priya (CTO)
**Date**: 2026-09-12
**Branch**: `feat/meera-creator-phase-b0`, HEAD `c22b00e` (worktree `influora-b0`; read-only, nothing changed)
**Sources**: every claim below was checked by opening the file. `SPEC.md`, `TASKS-B0.md`, `DECISIONS-0904.md`, `CREDITS-SPEC.md` and `finance/meera-credit-sheet.md` in this folder; the Java, Python and TypeScript named inline; the four Phase-B migrations against their entities; `git log`/`git branch` on this worktree; and one live fetch of `https://influora.in/meera-for-creators`.

**A note on the standard.** Where I could not prove something I have written **NOT VERIFIED** and said what would prove it. Six of your fifteen questions are decisions you have been avoiding rather than facts; I have named each one as a decision and given you my recommendation. Four of your questions contain a premise the code contradicts, and I have corrected the premise before answering.

---

## 1. The one honest sentence, and whether anyone has already been told more

**Say this:** *"Right now Meera can show you your deals, your verified numbers, what a package like this is worth, and what's risky in a deal you've already got — she can't read a pasted brief or write your reply yet."*

That is the whole of it. Four tools answer today: your deals, your metrics, an estimate, and deal risks (`influora-api/.../web/CreatorMeeraToolController.java` L97-126, and the four names the model is actually offered at `.../service/meera/CreatorToolScopes.java` L93-94). Two more names exist in the enum but have no route and are deliberately not offered — reading a brief and drafting a reply (`.../domain/enums/CreatorToolName.java` L24-30; the cards for both render nothing, `src/components/creator/meera/CreatorToolResultRenderer.tsx` L533-536). Inside the product, the copy is already modest: the card says "ask about deals, earnings, and metrics" (`src/pages/creator-copilot.tsx` L150).

**Yes, people have been told more, and it is live.** `https://influora.in/meera-for-creators` is up right now and says "Paste a brand brief. Meera reads it, tells you what to charge, and drafts the reply" (`src/pages/meera-for-creators.tsx` L109-111), and under "Three promises": "Every reply is labelled: drafted with Meera, approved by you" (L238-239). Neither of those exists in code. One of the three narrated films shows the same brand-facing label on screen (`src/remotion/script.en.ts` L113).

In fairness to whoever wrote that page, it is hedged harder than most launch pages: the badge says "In the works · coming soon" (L103), the CTA is "Join the waitlist", and it states outright that "Every line in the demo is scripted" (L127-128). So the exposure is not dishonesty — it is that a creator who read that page in July and gets access in September will find two of the five things missing. That is a gap to manage, not a fire.

---

## 2. The first thing a creator sees go wrong against real data

**The first break will be a wrong number, not a crash — and I do think the mechanical gap is small, for reasons I can show you.**

I ran the check that has caught us before: a column-by-column diff of each new table against the entity that maps it. It is clean — 13 columns to 13 on pasted briefs, 16 to 16 on drafts, 9 to 9 on offer history, and all 28 preference columns accounted for across three migrations (`influora-api/src/main/resources/db/migration/V20260910100000__meera_phase_b_prefs.sql` L34-40 plus `V73__creator_agent_preferences.sql` and `V20260903170000__...`). Schema validation is on at boot (`application.yml` L47), so a mismatch would refuse to start — and there isn't one. The one place that reads uncontrolled real JSON, the deliverables on a brand's proposal card, is written to return an empty list rather than throw on anything malformed (`.../service/rates/RateQuoteService.java` L528-548). Every AI failure path returns a deterministic answer rather than an error.

So what actually goes wrong is this: **every single quote will be in benchmark mode on day one, for every creator, and that is not a bug.** A price can come from her own closed deals (needs one priced deal in 180 days), or from her tier's market band (needs five deals across three different brands in 90 days), or from the constants. At launch nobody clears either of the first two bars, so everyone gets the constants (`RateQuoteService.java` L430-457). The calibration tool says so in its own words: with no completed priced deals, every realised median comes back null and every tier reads "below the floor" (`.../service/admin/CreatorAgentRateCalibrationService.java` L47-51).

**NOT VERIFIED:** that the application boots at all against a real MySQL 8. The test that would prove it exists and is Docker-gated (`MeeraPhaseB0BootValidationTest`), and Docker is not running on this machine — `docker version` returns "failed to connect to the docker API... the daemon is not running". The client is installed; the engine is not up. Proof is one run of that test on a machine with the engine started, or on the VPS.

---

## 3. The nano creator, ₹5,200, and the next ten minutes

**First, the number is real and I can show you where it comes from.** The nano band is ₹1,000-₹5,000 per post (`.../service/rates/RateTierProperties.java` L49-55, mirroring `.../service/scoring/RateEstimationService.java` L35-42). Three multipliers sit on top: engagement up to 1.3, category up to 1.3, quality up to 1.2 (`RateEstimationService.java` L110-115, L45-55, L136-139). A nano creator with 3-5% engagement in beauty and a quality score above 80 gets 1.15 × 1.25 × 1.2 = 1.725, so her band becomes ₹1,725-₹8,625, the quote is the midpoint — ₹5,175 — and the opening ask is that plus 10%, ₹5,693 (`RateQuoteService.java` L149, L799-809). That is your ₹5,200, arrived at by arithmetic over constants the file itself describes as "written for the brand-side estimate card and never checked against a real close" (`RateTierProperties.java` L13-19).

**Her next ten minutes.** She sees a card headed "Suggested package". Directly under the heading, before any figure, a line reads: *"benchmark, not market data — this is our estimate, not what creators like you have actually closed at."* (`CreatorToolResultRenderer.tsx` L327-332). The opening ask is labelled "Opening ask (benchmark)" with a second line under it saying the same thing again (L403, L407-411). And Meera is under instruction to say it in her first sentence, before she says any number at all (`influora-ai/app/prompt/creator_persona.py` L108-110). So the warning appears four times.

**What stops her concluding Meera cost her the deal: nothing.** There is no way for her to tell us the number was wrong — I searched for any feedback, rating or outcome path on a quote and found none. We keep a record of what we told her and why (`RateQuoteService.java` L900-923 writes her id, the provenance, the total, the anchor and where she asked), so we can always prove we labelled it honestly. That is a defence, not a comfort. The four warnings mean she was told; they do not mean she was protected. On the worked example above, if the true nano beauty rate is ₹2,500, we told her to open at ₹5,693 and we will only find that out from her, in a message no system reads.

**Decision for you:** I recommend we cap the opening ask in benchmark mode at the creator's own floor plus a fixed percentage, or suppress the opening ask entirely until a tier has real closes behind it, rather than lifting an invented midpoint by 10%. That is a two-line change in the anchor step. It costs us the "open high" coaching for the first cohort and it removes the specific failure you describe. Say which you want.

---

## 4. Whether a creator in a hurry registers "benchmark, not market data"

**Honest answer: we will not know until real creators use it, and I would not bet on the subtitle alone.** What I can tell you is exactly what the honesty treatment is and where it is thin.

It is stronger than "a card subtitle". It appears four times, as I listed above, and the version on the card is a full sentence in plain English, not a term of art. Measured things are labelled too, in the other direction: when there is no connected account the metrics card says "No connected account, so these are not measured yet", and a missing figure renders "Not available yet" rather than a zero or a dash (`CreatorToolResultRenderer.tsx` L258-264, L49).

Two things are genuinely weak. **All four warnings are grey text at the same visual weight as everything else on the card** — small, muted, no colour, no icon, no badge (`CreatorToolResultRenderer.tsx` L328). The card's below-floor warning gets a red chip; "we made this number up" gets grey. That asymmetry is the whole risk in one line. Second, **nothing changes behaviour in benchmark mode.** The "Use in counter" button — one tap to carry the number into a live counter-offer — has no extra friction when the number is invented (L431-440). It happens not to render today because the chat does not pass it a handler (`src/components/creator/MeeraCopilotChat.tsx` L525-531), so she has to retype the figure by hand. That is an accident of build order protecting us, not a design.

**My recommendation, and it is cheap:** give the benchmark line the same visual weight as the below-floor chip, and when it fires, make the button say "Use ₹X in counter — our estimate" rather than "Use in counter". Then measure it: gate metric 4 already reports quoted-versus-stated-budget per tier, which is the only real read we will get on whether the label landed.

---

## 5. Who watches for Meera confirming her own prices

**The instrument exists, nobody is watching it, and the standing rule is the thing you are being asked for.**

The watching is built and it is good. A calibration report shows three columns side by side per tier: what the formula says, what creators actually closed at, and what Meera has been quoting (`CreatorAgentRateCalibrationService.java` L42-45). It carries the exact number you are worried about — the share of the deals in a tier's market band that Meera herself anchored, counted over distinct negotiations so a two-counter deal cannot inflate it (L157-168). And the band that feeds a live quote already refuses to fire unless it has five deals from at least three different brands, so one brand's five deals cannot become "the market" (`RateQuoteService.java` L98, L101). There is an admin page for the report (`src/admin/hooks/useCreatorAgentRateCalibration.ts`).

**But it is a page somebody has to open.** No job runs it, no threshold trips, no alert exists. So the answer to "who watches" today is: whoever remembers to look. That should be one named person on a fixed cadence, and I recommend it be Rohan monthly alongside the cost report, with me on the ruling.

**Your decision, and it is the one you have been avoiding.** Right now the spec has no rule for pulling prices down. It has a rule for *recalibrating up or down once real data exists* — for any tier with 20 or more real closes, replace the constants with 0.6× to 1.4× the realised median — and gate metric 4 explicitly has **"No threshold"** and merely "must be shown" (`SPEC.md` L1831). That is the brief-by-brief trap you want out of.

**My recommendation as a standing rule, decide once:** if in any tier the median quote exceeds the median stated brand budget by more than 25%, that tier's band is cut by the observed gap at the next deploy, automatically, without a ruling from you — and the cut is logged. Below 25% we leave it alone and keep gathering. Two further safety rails I would set at the same time: no tier ever prices off its market band while the Meera-anchored share is above 50% (the label threshold already exists at `RateQuoteService.java` L104 — I would make it a hard block, not a label), and no tier's band is ever raised on fewer than 20 real closes. That gives you one number to approve instead of a judgement per brief.

---

## 6. The 90-day holdout — and the premise is wrong in a way that matters

**Correct the premise first: the holdout does not turn the headline feature off.** A creator in the holdout still gets the full package quote — every line, the total, the floor comparison, and the recommended move ("accept", "scope down", "counter at your floor", "decline"). She loses exactly two things: the suggested opening ask, and — once it is built — the ability to have a counter drafted for her (`.../domain/entity/CreatorAgentPreferences.java` L164-168; the anchor returns nothing under holdout at `RateQuoteService.java` L799-803). The tool itself is deliberately never withdrawn, precisely so the two arms do not behave visibly differently (`CreatorToolScopes.java` L133-138). So the real question is narrower than you put it: *is the opening-ask suggestion worth withholding from one creator in five?*

**What we learn.** Only one thing, and it is the thing we cannot get any other way: whether Meera's coaching moves the money, or whether creators who choose to use Meera were simply going to close better anyway. Without a control arm, every number in the B1 business case is a correlation. With it, we can say "creators who got an opening ask closed X% higher". That is the claim the whole creator product rests on.

**What it costs, precisely.** One creator in five, chosen deterministically from her profile id, for 90 days from the day her preferences row is created (`.../service/CreatorAgentPreferencesService.java` L175-176, constants at L57-60). At the gate's own target of 40 creators, that is **8 in the control arm and 32 in the treatment arm** — and the sample is at its smallest exactly when you most need it to be big.

**Two real defects you should know before you rule.** The line Meera is given reads "Negotiation coaching: withheld for this deal until [date]" (`influora-ai/app/prompt/assembler.py` L744-748) — but the holdout is not per-deal, it is her whole account for 90 days. The copy is wrong and will read as a glitch. Worse, **the card says nothing at all**: the quote is never marked as withheld anywhere in the code (the flag is hard-coded false, `RateQuoteService.java` L349), so the "this is being withheld" panel that exists in the UI can never fire, and the opening-ask row simply vanishes with no explanation (`CreatorToolResultRenderer.tsx` L319, L341-345). A holdout creator sees a quote that is quietly missing a row the other 80% get.

**My recommendation:** keep the holdout, fix the two copy defects first, and **drop it from one in five to one in ten for the first cohort only**, reverting to one in five once we are past 200 creators. At 40 creators a 20% holdout buys a control arm too small to conclude anything from while costing us a fifth of our earliest advocates. One in ten at this size is honest about what the measurement can actually support. That is a decision only you can take, because it trades measurement rigour against goodwill.

---

## 7. Fourteen rules, and when a creator stops reading

**Corrections to the premise first, and both change the answer.** It is **three** flags that cannot be dismissed, not two: hiding the ad label, off-platform payment, and regulated category (`.../service/risk/rules/HideDisclosureRule.java` L15-18, `OffPlatformPaymentRule.java` L21-23, `RegulatedCategoryRule.java` L18-19). And **today no flag can be dismissed at all**, dismissible or not — the dismiss control only renders when a screen supplies a handler to save the dismissal, and neither the chat nor the deal page supplies one (`src/components/shared/deal-risk-card.tsx` L168; `src/pages/creator-chat.tsx` L2608, L2825).

**How many flags an ordinary WhatsApp brief throws: we will not know until real briefs arrive.** What I can give you is the bound and the shape. The ceiling is 14. Thirteen of the fourteen rules need the brief to have been read and structured by the extractor, which is Wave 4 and does not exist; on a live deal with no brief, only the four rules that also scan raw text can fire, plus the below-floor check (`.../service/risk/DealRiskService.java` L485-490, L208-212). So today the realistic number is one to five, not fourteen. Once paste ships, my expectation — and it is an expectation, not a finding — is that a typical WhatsApp brief throws three to six, because vague deliverables, long usage rights and a below-floor budget will fire on most of them.

**The count is not where you lose the flag that mattered. The sort order is.** Flags are sorted worst-first, in both the engine and the card (`DealRiskService.java` L409-412; `deal-risk-card.tsx` L124-127). Four rules are Critical by default: below floor, a brand you blocked, an excluded category, perpetual usage rights. **All three of the non-dismissible flags — the ones that carry your creator's legal exposure — are only Warnings.** They are promoted to Critical only when the deal is over ₹25,000 (`RiskSeverity.java` L32-34, `DealValue.java` L26-30). So on the ₹5,000 nano deal in your question 3, "the brand asked you to hide the ad label" sorts *below* "this is under your floor". The one flag that can get her an ASCI problem is rendered after the one that costs her ₹500.

And the gate compounds it: metric 2 measures precision on **Critical** flags and false positives on those two by name (`SPEC.md` L1829) — so on ordinary small deals the two flags you care most about sit outside the precision bar entirely.

**My recommendation, and it needs no ruling from you:** make the three non-dismissible flags Critical at every deal size, and pin them to the top of the card above the value-based Criticals. Then the tolerance question answers itself — a creator can stop reading after two flags and still have read the two that could hurt her.

---

## 8. Off-platform payment in shadow mode — who tells her

**Correct the premise: she is told, at the time, in the card.** "Shadow mode" does not mean hidden. It means the flag blocks nothing. She sees it, headed "Payment offered outside the platform", with the detail "Paying outside Secure Payments loses dispute cover", and a specific next move: "Reply steering the brand back to Secure Payments so the funds are secured before you start." She cannot dismiss it (`OffPlatformPaymentRule.java` L15-19, L55-67). The separate record we keep is deliberately thin — the brand's workspace id and nothing else, no creator name, no matched text — because the point is spotting a brand with a pattern, not building a file on what a named creator was privately offered (`DealRiskService.java` L443-470).

**So your scenario is narrower and more uncomfortable than it sounds:** she was warned, once, in grey, at the bottom of a list sorted below any Critical flag, she took the UPI anyway, and we hold a per-brand record she never sees.

**Two real holes.** The rule's own documentation says the creator "keeps the report button" (`OffPlatformPaymentRule.java` L18). **There is no report button.** Nothing in the risk card offers a way to report a brand, and I found no report-a-brand path in the API. So the record accumulates and she has no way to add to it or to ask us to act. Second, nothing acts on the record — no threshold, no review, no alert. A brand can steer ten creators off-platform and nobody is told.

**What we say when she gets stiffed, and this is a decision you have to take, not a fact I can report.** My recommendation: we say plainly that we flagged it at the time, that a payment outside Secure Payments has no dispute cover and we cannot recover it, and then we do the two things that make that sentence survivable — we act on the brand (suspend or warn, on the record we already hold), and we tell her we are doing so. We should not offer compensation as policy; we should not pretend we did not see it. **What you need to decide is whether we ever pay out goodwill in this case, and what the brand-side consequence is.** Until that is decided the support team will improvise it, differently every time.

I would also ship the report button before B0 goes live. It is small, and "we saw it coming and gave you nowhere to go" is the version of this story that hurts.

---

## 9. When Meera refuses to draft — door or alternative

**Alternative, and the wording is already written. But there is a bigger problem: she cannot draft anything at all yet.**

Drafting is Wave 5. It is not built and the tool is deliberately not offered to the model (`CreatorToolName.java` L16-19; `CreatorToolScopes.java` L93-94; the card renders nothing, `CreatorToolResultRenderer.tsx` L533-536). So today, if a creator asks Meera to write her reply, Meera says she cannot do that and points her to where in the app she can do it herself — that rail is explicit in her instructions (`creator_persona.py` L123-126). That is the honest behaviour, and it is also the single biggest gap between the product and the public page.

**On the refusals themselves, the answer to your worry is yes.** Neither flag is a lecture. Hiding the ad label gives her: "Tell the brand the paid-partnership label stays; the post cannot run without it" (`HideDisclosureRule.java` L51). Regulated category gives her: "Ask the brand for the evidence in the deal thread before you script anything" (`RegulatedCategoryRule.java` L84). Both are a sentence she can send, and both keep the deal alive rather than killing it. Her instructions also forbid Meera from giving a legal conclusion as fact — she explains the term and points to a CA or lawyer (`creator_persona.py` L100-102). That is the right shape: refuse the undisclosed version, hand her the line that lands the disclosed one.

**One thing is broken.** The regulated-category flag **cannot fire at all today**. It reads only fields the brief extractor fills, and the live-deal path leaves those empty by design (`DealRiskService.java` L485-490; the rule reads no raw text). So until paste ships, a creator taking a finance, health, real-money-gaming, crypto, alcohol or tobacco deal through the platform gets no warning whatsoever. That is the one item in this answer I would call a launch blocker, and it is a Wave 4 dependency, not a new build.

---

## 10. How a brand reads the label, and what Meera says if asked

**The label does not exist in code.** I searched the whole product: "Drafted with Meera" appears only in the marketing films (`src/remotion/script.en.ts` L113) and as a promise on the live public page (`src/pages/meera-for-creators.tsx` L238-239). The build that would put it in a brand's thread is Wave 6 and unstarted. Meanwhile the deal message layer passes the entire metadata bundle to both parties unfiltered (`.../service/DealService.java` L2385) — which is exactly why the planned change is a filter, not a new field. Nothing writes any Meera marker today, so nothing leaks today. But the day drafting ships, that pass-through becomes the leak.

**On how a brand reads it: we have not tested it, and we will not know until brands see it.** My reasoning, which is reasoning and not a finding: "Drafted with Meera, approved by [name]" reads as *represented* rather than *incapable*, because the approval half puts a human decision-maker in the sentence. That was the argument in the ruling paper and I still think it is right — a brand that knows there is a manager negotiates with the manager (`DECISIONS-0904.md` L16). The version that would read badly is a bare "Sent by Meera" with no human in it, and that wording is in the plan for the six routine sends in B1. **I would not ship the bare "Sent by Meera" form.** Make every stamp carry the creator's name.

**When a brand asks outright: NOT VERIFIED, because no words exist.** Meera is forbidden from denying she is Meera and forbidden from writing as though the creator typed it personally (`creator_persona.py` L127-128) — both are listed under what she cannot do. But that is a prohibition, not a script. There is no sentence anywhere in the prompt or the product for "are I talking to a person or software", and in any case Meera never writes into a brand thread in B0 — every word in that thread was tapped through by the creator. What would prove the gap closed is one line in her instructions and one test asserting it.

**Your decision, which the default has already taken for you:** the label ships shown unless you say otherwise (`DECISIONS-0904.md` L74). I recommend confirming it, with two amendments: the creator's name is in every stamp, and we write the exact sentence for "is this software" before B1 rather than leaving the model to improvise it.

---

## 11. Who is accountable for a wrong number, and how she corrects it

**In the brand's eyes: the creator, every time, and that is not a legal opinion — it is what the product does.** Nothing goes to a brand without the creator tapping send. Her instructions require Meera to say "I've drafted it, tap to send" and never to claim she sent it (`creator_persona.py` L120-122). So the number in the brand's inbox was quoted by the creator. That is the design and I would not change it.

**In our own eyes, I do not think that survives contact with a real dispute, and this is a decision for you.** We suggested the figure, we labelled it as a benchmark, and we can prove exactly what we said and on what basis: her id, the provenance string, the total, the opening ask, and where she asked — with no floors in the record (`RateQuoteService.java` L900-923). That record is good enough to defend us and good enough to show a creator we were straight with her. What it does not settle is whether we ever make a creator whole for a deal lost to our estimate. **My recommendation: no compensation, and say so in the terms before launch, in the same plain words the card uses** — "this is our estimate, not what creators like you have closed at". An unstated policy here is worse than a hard one, because support will invent a generous one under pressure and we will be held to it.

**On telling Meera she was wrong: there is no way to do it.** I searched for any feedback, rating, thumbs or outcome path on a quote and found none. Her free-text complaint in the chat is saved with the conversation and read by nothing. The only loop that exists is indirect and slow: when enough real deals close, the market band and her own history override the constants automatically (`RateQuoteService.java` L430-457), and the calibration report shows us the gap. That is months, not minutes.

**I recommend one small build before launch:** two buttons on the quote card — "too high" / "too low" — writing one row against the quote's audit id. It turns gate metric 4 from an inference about budgets into a direct signal, and it is the cheapest instrumentation in this whole phase.

---

## 12. What happens to the reply she was drafting when the cap hits

**Precisely this: the text she typed is cleared from the input box the instant she taps send, before we know whether the send will succeed** (`src/components/creator/MeeraCopilotChat.tsx` L245). It is not lost — it appears immediately as her own message bubble in the transcript (L246). But it is not returned to the box and there is no retry, so to try again she has to select it out of the bubble and retype it. Then, in place of Meera's answer, she gets: *"You've reached your monthly Meera usage limit. It resets on the 1st of next month. If you need more before then, message support and we'll sort it out."* (`influora-ai/app/costs/spend_tracker.py` L85-88, rendered at `MeeraCopilotChat.tsx` L398-406 and L441-445).

**On "rule-based": nothing in the product says that, and nothing degrades gracefully yet.** The phrase exists only in the spec, describing a label the paste card is supposed to show when a summary came from the regex fallback rather than the model (`SPEC.md` L1724). Paste does not exist, so neither does the label. Nor does the planned cap message that names the three things which still work — that is Wave 6 and unstarted, and today the message names nothing.

So the answer to "help or quiet degradation" is: **today it is neither help nor degradation, it is a wall.** Three messages into a negotiation, she is told to email support and her half-typed thought is sitting in a chat bubble.

**Three fixes, in the order I would do them.** Keep her text in the box on any failure — that is one line and it is the whole difference between a stumble and an insult. Name what still works in the cap message, which was already planned. And when we do label a fallback summary, label it as *"a quick read while Meera's limit resets"*, not "rule-based" — nobody outside this building knows what that means.

---

## 13. What one active creator costs, and what we get back

**Cost: about ₹57 a month for a typical creator today, capped at about ₹189 a month if we raise the cap as planned.** From Rohan's sheet: a chat turn is ₹0.648, a brief read ₹0.294, a voice turn ₹1.415, the weekly note ₹0.98 a month (`finance/meera-credit-sheet.md` §1). A "typical" creator at 60 turns, 6 briefs and 10 voice turns comes to ₹55.77 plus the note (§2). The ceiling is the cap: the planned $2.00 for chat plus $0.25 for brief reads is ₹189 a month at 84 to the dollar. Against a ₹750 platform fee on a ₹5,000 deal (fee rate confirmed at `influora-api/src/main/resources/application.yml` L424), a typical creator's AI cost is 7.4% of one fee, and under 21% even for a heavy user closing only one deal (§3).

**Three things in that sheet you should treat as assumptions, not measurements.** The model prices and the model pins are real and I re-checked them (`influora-ai/app/costs/pricing.py` L105-106; `influora-ai/app/config.py` L68, L149) — those are measurements. **Everything about volume is an assumption**: the tokens per turn, the 70% cache-hit rate, the 40% chance of a tool round trip, and above all the three personas' usage levels. The sheet itself flags that losing either the cache-hit rate or the cheap model for brief reads pushes a typical creator over even a raised cap (§6). And the brief-read row prices a model setting that **does not exist in the code yet** — I looked; there is no such constant, only a permission entry waiting for the route (`influora-ai/app/auth/service_token.py` L75). So the ₹0.294 is an estimate of a thing not built.

**One correction to an assumption that is already stale: the cap has not been raised.** It is still $0.75 everywhere — in the code default (`config.py` L473) and in both deployment files. That work is Wave 6. At $0.75, a typical creator already sits at 88.5% of the cap and a heavy one is 2.5× over it, so heavy users will be cut off mid-month (`meera-credit-sheet.md` §2).

**What we get back: nothing is measured, and — this is the part I want you to see — the gate will not measure it either.** The five gate metrics are creators pasting, flag precision, draft acceptance, quote-versus-budget, and floor leaks (`SPEC.md` L1828-1832). **Not one of them measures a deal closed, a rupee of fee earned, or a creator retained.** So "we will know after the gate" would be the wrong answer: after the gate we will know whether the features work, not whether they pay. If you want the business answer you have to add a sixth metric now — deals closed and fees earned by creators who used Meera, against those who did not, which is exactly what the holdout in question 6 exists to make readable.

**And one live inconsistency you should fix this week.** The public page is running a pricing poll at **₹899 / ₹999 / ₹1,499 for 300 / 400 / 750 credits a month** (`src/components/site/MeeraPricingPoll.tsx` L31-33). Rohan's sheet recommends 40 free credits plus ₹249 per 100 (§8). The credits spec seeds packs at ₹149 / ₹249 / ₹649 with 30 free to start and 40 a month (`CREDITS-SPEC.md` L126-128, L817). The public page implies a brief read costs about 5 credits; both internal documents say 3. **₹999 buys 400 credits in public and 100 credits in the finance model — a four-fold gap, already visible to creators.** Worse, the poll records nothing: a vote is saved in the visitor's own browser and otherwise turned into a pre-filled email (L40, L49, L69). We are asking creators to price our product and throwing the answers away.

**Decision for you, and it is overdue:** pick one credit model and one price ladder, and make the public page match it. I recommend Rohan's Option C — free credits plus top-ups — because it keeps Meera free at the point of use, and I recommend the poll either be wired to store a vote server-side or be taken down.

---

## 14. Where 40 creators come from, and how to read their absence

**I cannot tell you where they come from, because there is no mechanism to bring them.** The "Join the waitlist" button on the live page goes straight to the full creator registration form — there is no waitlist. I checked: the word appears nowhere in the API, in no migration, and nowhere in the product except that button's own label. So every interested creator since that page went up either completed a full signup (which requires a mobile number and an email code) or vanished, and we hold no list of them.

**NOT VERIFIED: how many creator accounts exist today.** I have no database from here. One count against the production replica settles it, and it should be the first thing done — the 40 is either two weeks of nudging an existing base or a cold-start problem, and right now nobody in this company knows which.

**On how to read a failure, the spec has already made the call and I agree with it:** if 40 creators do not paste a brief, "the problem is distribution and onboarding, not features — do not build B1, fix the entry point" (`SPEC.md` L1828).

**Where I would push back on your framing.** "Nobody wanting this" is not one of the two readings available, because **we have never offered it to anyone.** Phase A has never been deployed. A creator cannot reach a single one of these four tools today. A zero here is not a verdict on demand; it is a verdict on our distribution — and, if the page's promises stay ahead of the product, on the gap between what we advertised and what a creator finds when they arrive.

**My recommendation:** before the 14-day window opens, do three things — count the existing creator base, build a real waitlist capture on that page so the traffic it is already getting stops evaporating, and pick 40 creators by name that we will personally walk through one paste each. A measurement window that depends on strangers finding us is not a measurement, it is a hope.

---

## 15. The single specific thing between one real creator and one real brief

**There is no single thing, and the honest answer is worse than "Docker is missing": none of this code is anywhere but this laptop.**

Here is the state, from `git` in this worktree. Our main branch is at the fix wave of **28 August** — local and remote are identical, both at `8f1153d`. **Phase A is not on main at all**: commit `8c7b18b` is not an ancestor of it. And the branch carrying all of Waves 0 through 3 has **no remote at all** — `feat/meera-creator-phase-b0` has no upstream configured, and `c22b00e` appears on no remote branch. The only related thing that has been pushed is `feat/meera-creator-phase-e`. The feature flag is not the problem; it is already on in both deployment files (`MEERA_CREATOR_ENABLED: "true"`).

So the chain between one creator and one brief has four links, in order, and the paste itself is the fourth:

1. **Push this branch and get Phase A onto main.** Nothing is backed up and nothing is deployable from main. This is the one that frightens me — three commits of work exist on one disk.
2. **Run the boot test on a real MySQL 8.** Docker's client is installed on this machine but its engine is not running — `docker version` reports it cannot connect to the daemon. **NOT VERIFIED whether that is a five-minute fix or a broken install**; starting Docker Desktop and re-running the test settles it. Otherwise the VPS does it.
3. **Deploy Phase A and smoke it.** This is the oldest open item on the whole feature and the spec is blunt that it blocks the measurement, not just the launch (`SPEC.md` L1816).
4. **Then build paste** — Wave 4. It does not exist, and the phase is named after it.

**What you need to buy, authorize or sign this week: nothing to buy.** The VPS is already running. What I need from you is three authorizations: **(a)** permission to push this branch and merge Phase A to main — main is two weeks stale and someone should decide that deliberately rather than by drift; **(b)** a decision on whether we smoke-test on the VPS or on a developer machine, because "either" has meant "neither" for two weeks now; and **(c)** an owner and a date for the deploy, with the authority to do it. Every one of those is a decision, not a purchase, and the reason this has sat is that it belongs to nobody.

---

*Answered from the working tree at `c22b00e` on 2026-09-12. Nothing in the worktree was modified. Every file and line cited above was opened.*
