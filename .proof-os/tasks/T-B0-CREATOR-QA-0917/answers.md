# Meera for Creators, Phase B: a creator's questions, answered from the code

**From:** Priya (CTO) | **Date:** 2026-09-17 | **Task:** T-B0-CREATOR-QA-0917

## How to read the citations

Every citation is `path:line`, relative to one base directory:

`C:/Users/Sage world/Downloads/New Influora Ai/`

- `influora-b0/...` is the worktree for branch `feat/meera-creator-phase-b0` (HEAD `df20091`).
- `New Influora/...` is the main checkout (branch `feat/meera-creator-phase-e`).

**Labels used below**

- **BUILT-b0 (committed):** the code is in a commit on `feat/meera-creator-phase-b0`.
- **BUILT-b0 (uncommitted):** the file is changed or untracked in the b0 worktree and is in no commit. `git -C influora-b0 status` shows 39 modified and 18 untracked paths. These include the only paste screen (`src/components/creator/copilot/PasteBriefCard.tsx`, untracked) and the `get_brief` tool (`GetBriefExecutor.java`, untracked). A lost worktree loses this work. Line numbers are for the files as they sit on disk today.
- **BUILT-main:** the code is in the main checkout.
- **SPEC-ONLY:** no code exists. A creator cannot use it.
- **UNKNOWN:** I could not check it.

**Checked, and true for every answer below:**

- The b0 code is not in phase-e. `git merge-base --is-ancestor df20091 feat/meera-creator-phase-e` says it is not an ancestor.
- The main checkout has neither `influora-api/src/main/java/com/influora/web/CreatorBriefController.java` nor `influora-api/src/main/java/com/influora/service/risk/`.
- `git branch -r --list '*b0*'` returns nothing, so the branch has never been pushed.

**The specs are older than the code.** The main checkout's `SPEC.md` is 1823 lines. The b0 worktree's copy is 2013 lines, because commit `1571dce` rewrote it. Spec citations below point to the main-checkout copy, as the task asked.

---

## Is it useful?

### 1. I get a brand brief on WhatsApp or in an Instagram DM. What do I do in Influora, what do I get back, and how many steps is it?

**BUILT-b0.** The server side is committed. The only screen is uncommitted.

**The steps:**

1. Open **Co-pilot**. The route is `/creator/copilot` (`influora-b0/src/App.tsx:570`), linked from the creator menu (`influora-b0/src/components/creator/creator-layout.tsx:134`).
2. Paste the brief into the "Paste a brand brief" box. The page mounts it at `influora-b0/src/pages/creator-copilot.tsx:275`, and the box itself is `influora-b0/src/components/creator/copilot/PasteBriefCard.tsx:160-180` (uncommitted). The box holds 8,000 characters. If you paste more, it says so instead of cutting silently (`PasteBriefCard.tsx:107-116`, `:183-185`).
3. Press **Analyse with Meera** (`PasteBriefCard.tsx:194-203`). This sends one request, `POST /creator/briefs` (`influora-b0/influora-api/src/main/java/com/influora/web/CreatorBriefController.java:122-129`).

The first time, you must also accept the consent notice. The server refuses without it (`CreatorBriefController.java:97-102`).

**What you get back, all in one response** (`influora-b0/influora-api/src/main/java/com/influora/web/dto/brief/BriefDtos.java:93-105`):

- **A 3 to 5 line summary** of what the brief says (`influora-b0/influora-ai/app/prompt/brief_extract.py:40`).
- **Chips for the terms Meera found:** deliverables, budget, deadline, usage, exclusivity and so on (`influora-b0/src/components/creator/meera/CreatorToolResultRenderer.tsx:617-633`).
- **A "What to watch" risk list** (`CreatorToolResultRenderer.tsx:639-645`).
- **A "Suggested package" price card** (`CreatorToolResultRenderer.tsx:350-354`). It shows line prices, a "Below your floor" badge where a line is too low (`:377-383`), a bundle discount, add-ons, the total, an opening ask (`:413-427`), and the payment schedule "50% on securing funds, 50% on delivery" (`influora-b0/influora-api/src/main/java/com/influora/service/rates/RateQuoteService.java:113`).
- **An "Ask Meera about this brief" button** (`PasteBriefCard.tsx:230-250`). It only puts "Look at brief {id}." into the chat box and never sends it (`influora-b0/src/pages/creator-copilot.tsx:176`, `influora-b0/src/components/creator/MeeraCopilotChat.tsx:253`).

**What you do not get: a reply draft.**

- The spec promises one: "paste any brief, get summary, rate, flags, draft" (`New Influora/.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/SPEC.md:34`). §14.5.a also puts `draft_reply` and a `DraftCard` in B0 (`SPEC.md:1621`, `:1625`).
- In the code, `draft_reply` is a name only. The server's list of working tools leaves it out (`influora-b0/influora-api/src/main/java/com/influora/service/meera/CreatorToolScopes.java:167-173`), and the tool controller has no route for it (`influora-b0/influora-api/src/main/java/com/influora/web/CreatorMeeraToolController.java:102-138`).
- The screen says so itself: "Meera doesn't draft or send anything — you decide." (`PasteBriefCard.tsx:74`).

**Code and spec disagree:**

- The spec's "Use in counter" prefill (`SPEC.md:36`, `:1625`) has a button in `CreatorToolResultRenderer.tsx:445-454`. Nothing passes it the handler it needs. The chat says so on purpose (`MeeraCopilotChat.tsx:538-541`), and `PasteBriefCard` does not pass one either (`PasteBriefCard.tsx:216-224`). So the button never shows. You copy the number into the deal's counter form yourself.
- The public marketing page in the main checkout still promises the draft: "Meera drafts the reply; you send it." (`New Influora/src/pages/meera-for-creators.tsx:44`, also `:77` and `:109`). The b0 worktree has an uncommitted fix that removes that claim (`influora-b0/src/pages/meera-for-creators.tsx:91`, `:123`).

### 2. If Influora's AI is down when I paste, do I lose the text?

**BUILT-b0 (committed).** No, the text is not lost.

**The order of work on the server:**

1. The raw text is saved and committed in its own transaction **before** the AI is called (`influora-b0/influora-api/src/main/java/com/influora/service/CreatorBriefService.java:203-206`).
2. If the AI call fails (network error, non-200 response, bad body, refusal), the client does not throw. It returns "unavailable" (`influora-b0/influora-api/src/main/java/com/influora/integration/ai/MeeraBriefAiClient.java:181-241`).
3. The service then runs a rule-based extractor instead (`CreatorBriefService.java:441-456`).

**What you still get:**

- You still receive the summary, risk flags and price.
- The first summary line says "Read without AI, so check these against the brief" (`influora-b0/influora-api/src/main/java/com/influora/service/brief/BriefFallbackExtractor.java:48`, `:191-193`).
- The card adds "Meera couldn't be reached just now, so this summary is rule-based. Check it against the brief." (`CreatorToolResultRenderer.tsx:479-480`).

**What the rule-based reader leaves out:**

- brand name, product, category, claims and regulated category (`BriefFallbackExtractor.java:159-179`).
- It recognises only English keywords (`BriefFallbackExtractor.java:68-74`, `:126-134`).

**Two gaps:**

- **Nowhere to reopen a saved brief.** If the whole request fails after the text was saved, the text stays in the box, because the error path never clears it (`PasteBriefCard.tsx:136-152`). But no screen lists your saved briefs. `GET /creator/briefs` exists (`CreatorBriefController.java:132-138`) and has a client function (`influora-b0/src/lib/api.ts:7050-7053`), but nothing in `src/` calls it. Only `paste` is called (`PasteBriefCard.tsx:133`).
- **A brief stuck mid-analysis.** Reading it back later returns 409 "Still reading this brief" for about 30 seconds (`CreatorBriefService.java:342-347`, `:358-363`). After that the server analyses it again (`CreatorBriefService.java:348-349`).

### 3. How does Meera decide what I should charge? What if I am new?

**BUILT-b0 (committed).** It works down a fixed order and tells you which rule it used (`RateQuoteService.java:425-459`).

**The order:**

1. **Your own deals.** Deals at "terms agreed" or later, in the last 180 days, each converted to a price per reel (`RateQuoteService.java:121`, `:171-179`, `:471-492`).
   - 3 or more deals: the median. Label: "your last N priced deals" (`RateQuoteService.java:433-436`).
   - 1 or 2 deals: your median blended with the benchmark. Label: "…blended with benchmark" (`RateQuoteService.java:437-455`).
2. **Your tier on Influora.** Closed deals in the last 90 days, in your niche and follower tier. This needs at least 5 deals from at least 3 different brands (`RateQuoteService.java:138-141`, `:559-603`). If more than half of those deals were countered with a Meera draft, the label adds "mostly Meera-quoted" (`RateQuoteService.java:594-599`).
3. **A benchmark table.** The midpoint of a fixed range per follower tier, for example MICRO ₹5,000 to ₹25,000 (`influora-b0/influora-api/src/main/java/com/influora/service/scoring/RateEstimationService.java:35-42`), adjusted for your metrics, category and quality (`RateQuoteService.java:638-708`). Label: "benchmark, not market data" (`influora-b0/influora-api/src/main/java/com/influora/service/rates/RateAddOns.java:47`).

**If you are new, with no deals, no tier data and no metrics:**

- The price is your own reel floor, labelled "your floor" (`RateQuoteService.java:664-672`).
- If you never set a floor, it defaults to your last completed deal's rate. With no deals it uses the benchmark low end, and with nothing at all ₹500 (`influora-b0/influora-api/src/main/java/com/influora/service/CreatorAgentPreferencesService.java:47`, `:187-210`).
- With metrics but no deals, you are in benchmark mode. The card subtitle says "benchmark, not market data — this is our estimate, not what creators like you have actually closed at." (`CreatorToolResultRenderer.tsx:342-345`).

**The opening ask:**

- In benchmark mode it is the total × 1.10, capped at the top of the range. In the other modes it is the total × 1.15 (`RateQuoteService.java:808-819`).
- **One in five creators is never shown an opening ask.** They are placed in a test group for 90 days (`CreatorAgentPreferencesService.java:58`, `:61`, `:175-176`, `RateQuoteService.java:809-811`), and the card does not say why.

**If the brief states a budget,** the quote also gives a recommended move: ACCEPT, SCOPE_DOWN, COUNTER_AT_FLOOR or DECLINE (`RateQuoteService.java:828-842`). A budget Meera only guessed is never used for this (`RateQuoteService.java:392-395`).

**Code and spec disagree:** the spec's benchmark is the 65th percentile with a clamped ×1.15 anchor (`SPEC.md:798`, `:803`). The code follows the §14.1 correction: the midpoint, and ×1.10 clamped only in benchmark mode (`RateQuoteService.java:62-78`).

### 4. What problems does it warn me about? Give three real rules.

**BUILT-b0 (committed).** There are 14 rules (`influora-b0/influora-api/src/main/java/com/influora/service/risk/DealRiskService.java:106-120`). They run on a pasted brief and on a live deal, through `GET /deals/{id}/risks` (`influora-b0/influora-api/src/main/java/com/influora/web/DealController.java:86-89`). The deal page shows them too (`influora-b0/src/pages/creator-chat.tsx:790`, `:2624`).

**Rule 1: `BELOW_FLOOR` (CRITICAL).**

- **Fires when:** the brand names an amount and it is lower than your floor total for the deliverables asked (`influora-b0/influora-api/src/main/java/com/influora/service/risk/rules/BelowFloorRule.java:30-47`).
- **Stays silent when:** no amount is stated (`:39-43`).
- **You see:** "Offer is {value} against your floor of {floor}."
- **Advice:** NANO and MICRO creators are told to counter with a smaller package. Larger creators are told to counter at the floor (`:52-58`, `:65`).

**Rule 2: `USAGE_PERPETUAL` (CRITICAL).**

- **Fires when:** the brief says usage is perpetual, the usage covers all five channels, or the text contains "perpetu", "in perpetuity" or "all media" (`influora-b0/influora-api/src/main/java/com/influora/service/risk/rules/UsagePerpetualRule.java:34-46`).
- **You see:** "Brand can use this content forever." and "Price the perpetuity add-on before you agree to it." (`:54-56`).

**Rule 3: `OFF_PLATFORM_PAYMENT` (WARN, cannot be dismissed).**

- **Fires when:** the brief pushes payment outside the platform, or the text contains UPI, GPay, PhonePe, Paytm, bank transfer, NEFT, IMPS or "pay after" (`influora-b0/influora-api/src/main/java/com/influora/service/risk/rules/OffPlatformPaymentRule.java:41-53`).
- **You see:** "Paying outside Secure Payments loses dispute cover." (`:60`).
- **It never blocks anything** (`:65-66`), and you cannot hide it (`:67`).

**One more:** `HIDE_DISCLOSURE` fires on "no #ad", "without disclosure" and similar. It says "Breaks ASCI guidelines." and cannot be dismissed (`influora-b0/influora-api/src/main/java/com/influora/service/risk/rules/HideDisclosureRule.java:30-53`).

**Code and spec disagree:** the spec's scope table says "ten deterministic rules" (`SPEC.md:37`). Its rule table and the code both have 14 (`SPEC.md:836-849`).

### 5. Can it help me find campaigns on Influora and apply?

**Split answer.**

- **BUILT-main:** you can browse and apply by hand. The routes are `GET /creator/campaigns` and `POST /creator/campaigns/{id}/apply` (`New Influora/influora-api/src/main/java/com/influora/web/CreatorCampaignController.java:31`, `:40`, `:60`), and the page is `/creator/campaigns` (`New Influora/src/App.tsx:645`).
- **SPEC-ONLY:** Meera ranking campaigns or drafting the application (`SPEC.md:40`, job B7, moved to B1 at `SPEC.md:1621`).
  - `rank_open_campaigns` and `draft_application` appear only as scope names (`influora-b0/influora-api/src/main/java/com/influora/service/meera/CreatorToolScopes.java:77-79`).
  - They are not in the working-tool list (`CreatorToolScopes.java:167-173`).
  - `CampaignFitService` exists in neither checkout. A search of both `src/main` trees found nothing.
- **You cannot use Meera for this.**

---

## Is it like a PR manager or a pull request?

### 6. Is Meera my PR manager or agent, or like a pull request that I review and approve?

**Today it is neither.** In code, Meera **reads and advises**. It summarises, prices and warns. Nothing it produces goes to a brand.

**BUILT-b0:**

- No draft is ever created. Nothing in `src/main` calls `meeraDraftRepository.save`. The only use is a lookup that checks whether a counter came from a Meera draft (`influora-b0/influora-api/src/main/java/com/influora/service/DealService.java:1615-1640`).
- No send route exists (`CreatorMeeraToolController.java:102-138`).

**SPEC-ONLY:** the "pull request" model. Meera writes a draft, you edit it, then approve or discard it through `/creator/meera/drafts/{id}/approve` (`SPEC.md:603-632`). That is the right mental model for what is planned, but it is not built.

**Copy and code disagree:** the main-checkout marketing page calls Meera "Your own PR manager, on your side" and "drafts the reply" (`New Influora/src/pages/meera-for-creators.tsx:77`). The code does not draft.

### 7. Can Meera message a brand without me? What are the levels? Can I cancel a sent message?

**Today: no.** Meera cannot send anything at any level.

**BUILT-main (the setting):** Settings has an "Automation Level" choice (`New Influora/src/components/creator/MeeraSettingsSection.tsx:430-453`):

- Level 0: "Draft only — I approve every message".
- Level 1: "Routine replies — Meera can send simple messages (media kit, availability)".
- Level 2: "Auto-decline — Meera can decline excluded categories or blocked brands".

Under the options it states: "Meera does not send or decline anything on your behalf yet, regardless of this setting." (`MeeraSettingsSection.tsx:455`).

**BUILT-b0 (the permissions behind it):**

- Level 1 permissions include `send_routine_reply` (`CreatorToolScopes.java:109`). Level 2 adds nothing (`:131`).
- A creator represented by an agency gets read-only tools at any level (`:139-141`, `:197-200`).
- An unexpected level value falls back to the narrowest set (`:201-205`).
- **Why none of this sends anything:**
  1. No `send_routine_reply` route exists (`CreatorMeeraToolController.java:102-138`).
  2. Any future send route must be behind `creator-send-enabled`, which defaults to false (`influora-b0/influora-api/src/main/java/com/influora/config/MeeraCreatorFeatureProperties.java:33`, `influora-b0/influora-api/src/main/resources/application.yml:243`).
  3. An architecture test fails if someone adds such a route without that switch (`CreatorToolScopes.java:58-60`).

**SPEC-ONLY:**

- Level 1 would allow only six kinds of message: send media kit, send rate card, acknowledge the brief, ask the deliverable count, ask the timeline, tentative availability (`SPEC.md:684`).
- A message would be refused if it contains a floor number, "₹" followed by digits, a date, or accept or decline wording (`SPEC.md:696`).
- Sends would be queued 60 seconds, with `POST /creator/meera/sends/{id}/cancel` (`SPEC.md:700`, `:714`).
- None of this exists. **You cannot cancel a send, because there are no sends.**

### 8. If Meera gets a number or a date wrong, what stops it reaching the brand?

**BUILT-b0:** a wrong number cannot reach a brand today, because nothing Meera produces is sent. Three things also guard what **you** see:

- **Invented numbers are removed.** A summary line with a number that is not in the brief is dropped (`influora-b0/influora-ai/app/routes/brief_extract.py:259-272`, `:356-366`). If fewer than 3 lines survive, the whole AI reading is thrown away and the labelled rule-based reader is used (`brief_extract.py:367-368`).
- **A guessed budget is thrown away.** It is cleared unless the brief actually names a fee (`brief_extract.py:302-311`), and only a stated budget can drive the recommended move (`RateQuoteService.java:392-395`).
- **Off-list values are dropped.** An unknown category, usage channel or exclusivity scope is removed, not guessed (`brief_extract.py:179-187`).

**Not guarded: dates.** The deadline is passed through as text up to 40 characters, without checking it against the brief (`brief_extract.py:324`). The date check that would stop a date going out is SPEC-ONLY (`SPEC.md:696`).

---

## Limitations

### 9. What will Meera never do for me?

**The spec rules these out:** reading Gmail or DMs, outreach outside Influora, auto-accepting at any level, moving money beyond the existing contract and funding flow, TDS or GST figures, and WhatsApp (`SPEC.md:42`).

**BUILT-b0, the code matches:**

- It sees only text you paste. The request body is one `text` field (`BriefDtos.java:67-70`).
- The creator tool list has six names: get deals, get brief, estimate rate, get metrics, check risks, draft reply. None of them accepts a deal, pays, withdraws or files anything (`influora-b0/influora-api/src/main/java/com/influora/domain/enums/CreatorToolName.java:24-30`).
- The AI reading the brief is told to give "No advice and no opinion" (`brief_extract.py:41`).

**UNKNOWN:** whether Phase E, on the phase-e branch, adds any of these. I did not audit Phase E code for this question.

### 10. Does it work if my brief is in Hindi, Hinglish, Marathi or Tamil?

**Partly, and it is not tested.**

- **Chat: BUILT-b0.** Meera is told to reply in your language, for example "hi-IN means Hindi or natural Hinglish", and to follow the language you write in (`influora-b0/influora-ai/app/prompt/creator_persona.py:77-79`).
- **Brief reading: BUILT-b0, English-only in practice.**
  - The reading prompt says nothing about language (`brief_extract.py:31-45`).
  - Your language setting is sent (`MeeraBriefAiClient.java:163`), but the AI service only logs it and never passes it to the model (`influora-b0/influora-ai/app/routes/brief_extract.py:446`).
  - So the summary comes back in whatever language the model picks.
- **If the AI is down, a non-English brief yields almost nothing.** The fallback reader and the risk-rule text matches look for English words: reel, story, budget, exclusivity, perpetual, "no #ad" (`BriefFallbackExtractor.java:68-134`, `HideDisclosureRule.java:30-33`, `UsagePerpetualRule.java:34-35`).
- **UNKNOWN:** how well the model reads Hindi, Marathi or Tamil. A search of `test_brief_extract.py`, `BriefFallbackExtractorTest.java` and `CreatorBriefServiceTest.java` found no Devanagari or Tamil script and no Hinglish fixture.

**Code and spec disagree:** the spec says money is formatted in your locale (`SPEC.md:16`). The code does that for the price card (`RateQuoteService.java:328-351`), but not for the language of the summary.

### 11. Will a brand know Meera wrote a message? Can a brand ever see my minimum rate or my agency's name?

**Meera label: SPEC-ONLY.**

- The spec's default is a "Drafted with Meera" label that brands can see (`SPEC.md:1295`, `:1549`, `:1554`).
- No such label exists, and the b0 marketing copy removed that promise for this reason (`influora-b0/src/pages/meera-for-creators.tsx:250-252`, uncommitted).
- It does not matter yet, because no Meera text reaches a brand (question 6).

**Your minimum rate (floor): BUILT-b0 (committed).**

- The brief response carries your floor, and it is served only on creator routes (`CreatorBriefController.java:48-52`, `BriefDtos.java:86-91`).
- The deal-risk route refuses brands with `CREATOR_ONLY` (`DealController.java:86-89`, `influora-b0/influora-api/src/main/java/com/influora/service/DealService.java:198-201`).
- `FloorBarrierTest` walks every controller's response types. A floor field that reaches any controller not on a short exemption list fails the build (`influora-b0/influora-api/src/test/java/com/influora/architecture/FloorBarrierTest.java:44-50`, `:84`).
- The AI service removes floors from any brand-side context (`influora-b0/influora-ai/app/prompt/assembler.py:75`, `:95-98`, `:265`).

**Your agency name: BUILT-main, with a test gap.**

- It is stored (`New Influora/influora-api/src/main/java/com/influora/domain/entity/CreatorAgentPreferences.java:113`).
- It is read only into your own preferences (`New Influora/influora-api/src/main/java/com/influora/service/CreatorAgentPreferencesService.java:304`) and your own Meera context (`New Influora/influora-api/src/main/java/com/influora/service/meera/MeeraContextService.java:289`).
- The AI service removes it from brand context (`influora-b0/influora-ai/app/prompt/assembler.py:102-104`).
- **UNKNOWN:** whether any Java test would fail if the agency name leaked to a brand. `FloorBarrierTest` does not mention it; a search for "agency" in that file found nothing.

### 12. Can I use any of this today? Is it merged, deployed, or behind a flag?

**No, not as a creator on the live product.**

- **Not merged.** `feat/meera-creator-phase-b0` is 44 commits ahead of `main` (`git rev-list --count main..feat/meera-creator-phase-b0` = 44). It is not an ancestor of phase-e, and the main checkout has no brief controller or risk package (see "How to read the citations").
- **Not pushed.** No remote branch exists, so CI could not have built an image from it.
- **The paste screen is not even committed.**
  - The committed `creator-copilot.tsx` never mentions `PasteBriefCard` (`git show HEAD:src/pages/creator-copilot.tsx | grep -c PasteBriefCard` = 0).
  - The latest commit says: "no screen calls any of this … no creator can yet paste anything" (`git show df20091`).
- **Flags on the b0 branch:**
  - `creator-enabled` defaults to true (`influora-b0/influora-api/src/main/resources/application.yml:232`); the paste routes check it first (`CreatorBriefController.java:81-86`).
  - `creator-send-enabled` defaults to false (`application.yml:243`).
- **Never run against a real database.** The commit message lists three database behaviours as not proven (`git show df20091`, "NOT PROVEN").
- **UNKNOWN:** what runs on the live server. I did not inspect the VPS. From the branch facts above, it cannot have come from main, phase-e or CI.

---

## Price and credits

### 13. Do I pay for Meera? What costs a credit?

**Today no credits exist, and you pay nothing.**

- **BUILT-main:** chat messages from creators are **not** charged. The credit charge runs only for brand users (`New Influora/influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java:374-389`).
- **BUILT-b0:** pasting a brief is not charged either. `paste` saves and analyses with no charge step (`CreatorBriefService.java:199-209`). A search of both checkouts for `CreatorCreditService` or `CREATOR_CREDITS_ENABLED` found nothing.
- **SPEC-ONLY:** a chat message would cost 1 credit, voice 2, and a brief 3 (`New Influora/.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/CREDITS-SPEC.md:268-270`). Drafts have no price in the spec.

**Code and spec disagree:** CREDITS-SPEC says the brief charge waits for "Phase B1 `CreatorBriefService.paste` (not yet built)" (`CREDITS-SPEC.md:470`, `:881`). In fact paste was built in B0, with no charge hook. `SPEC.md:1823` puts that hook inside paste.

### 14. How many free credits, when do they reset, what happens when I run out, can I buy packs?

**SPEC-ONLY. None of this exists.**

- **Free credits:** 30 at signup, which never expire, plus 40 every month (`CREDITS-SPEC.md:52-53`, `:266-267`).
- **Reset:** the monthly bucket resets on the 1st, UTC, and does not roll over (`CREDITS-SPEC.md:52`, `:792`).
- **Running out:** an error, 402 `CREDITS_EXHAUSTED`, "You've used your Meera credits. Top up to keep going." (`CREDITS-SPEC.md:343`).
- **Packs:** Starter 50 credits ₹149, Standard 100 ₹249, Power 300 ₹649 (`CREDITS-SPEC.md:126-128`, `:134`).
- **Switch:** everything would ship turned off, `CREATOR_CREDITS_ENABLED=false` (`CREDITS-SPEC.md:44`, `:294`).
- **You cannot buy a pack today.**

### 15. Is there a spending cap per creator, and what do I see when I hit it?

**Yes, a cost cap exists: BUILT-main and BUILT-b0.**

**Chat:**

- The AI service caps each creator at US$0.75 a month (`New Influora/influora-ai/app/config.py:456`). The live deploy file sets the same figure (`New Influora/deploy/utho/docker-compose.utho.yml:308`).
- At the cap, the chat returns HTTP 429 (`New Influora/influora-ai/app/routes/chat.py:263`, `:608`) with: "You've reached your monthly Meera usage limit. It resets on the 1st of next month. If you need more before then, message support and we'll sort it out." (`New Influora/influora-ai/app/costs/spend_tracker.py:85-88`).
- The chat shows it (`New Influora/src/components/creator/MeeraCopilotChat.tsx:38`, `:267`).

**Brief reading (BUILT-b0):**

- It has its own US$0.25 monthly cap (`influora-b0/influora-ai/app/config.py:545-547`), roughly 70 readings by the spec's own estimate (`SPEC.md:1592`), on a separate spend key (`brief_extract.py:422-438`). Heavy chat use cannot block paste.
- At that cap, paste still works. You get the rule-based reading, labelled "Meera's monthly limit is reached, so this summary is rule-based. Check it against the brief." (`CreatorToolResultRenderer.tsx:476-477`, `MeeraBriefAiClient.java:222-227`).
- Paste is also limited to 10 per 60-second window per creator (`influora-b0/influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:337`, `:406-407`, `:702`, `:716`).

**Code and spec disagree:**

- The spec sets a US$2.00 creator cap by default (`SPEC.md:1297`, `:1600`). Code and deploy still say 0.75 (`New Influora/influora-ai/app/config.py:456`, `New Influora/deploy/utho/docker-compose.utho.yml:308`).
- The spec's cap message would give a reset date and three links (`SPEC.md:1598`). The built message is prose only (`spend_tracker.py:85-88`).
- **SPEC-ONLY:** a usage meter (`SPEC.md:1603`).

---

## What a creator should know before relying on this

- **You cannot use it yet.** Paste-and-read exists only on an unmerged, unpushed branch, and its screen is not even committed (question 12).
- **When it ships, it reads and advises. It does not write or send.** No reply draft, no "Use in counter" button, no message to a brand at any level. The Settings level choice changes nothing (questions 1, 6, 7).
- **Check where the price came from.** "benchmark, not market data" and "your floor" are estimates, not what creators like you closed at. One in five creators is never shown an opening ask (question 3).
- **Treat non-English briefs and AI outages with care.** The rule-based reader and the text matches are English-only, and no test covers a Hindi, Marathi or Tamil brief (questions 2 and 10).
- **Your floor is guarded from brands by a failing test. Your agency name is guarded by a filter, but no Java test checks it** (question 11).
- **Credits and packs are a plan, not a product.** The only limit today is a US$0.75 monthly chat cap, plus a separate cap for brief reading that falls back to the rule-based reader (questions 13 to 15).

## Summary table

| # | Question | Status |
|---|---|---|
| 1 | Paste a brief: what you get, and how many steps | BUILT-b0 (API committed; screen uncommitted). The draft is SPEC-ONLY |
| 2 | Is the text lost if the AI is down? | BUILT-b0 |
| 3 | How the price is set, and what a new creator sees | BUILT-b0 |
| 4 | Risk rules | BUILT-b0 |
| 5 | Find and apply to campaigns | BUILT-main (by hand). Meera help is SPEC-ONLY |
| 6 | PR manager or pull request? | SPEC-ONLY (draft and approve). BUILT-b0 is read-only advice |
| 7 | Sending, levels, cancelling | SPEC-ONLY (sends and cancel). BUILT-main (level setting) and BUILT-b0 (permissions, send switch) |
| 8 | What stops a wrong number or date | BUILT-b0 (numbers). SPEC-ONLY (dates) |
| 9 | What Meera will never do | BUILT-b0 (matches SPEC §1). UNKNOWN for Phase E |
| 10 | Hindi, Hinglish, Marathi, Tamil | BUILT-b0 (chat language; brief reading English-only). UNKNOWN (model accuracy) |
| 11 | Meera label, floor and agency name seen by brands | SPEC-ONLY (label). BUILT-b0 (floor barrier). BUILT-main (agency name, not tested) |
| 12 | Can I use it today? | Not merged, not pushed, screen uncommitted. UNKNOWN (live server) |
| 13 | What costs a credit | SPEC-ONLY. BUILT-main: no charge exists |
| 14 | Free credits, reset, packs | SPEC-ONLY |
| 15 | Spending cap | BUILT-main (US$0.75 chat cap). BUILT-b0 (brief cap and fallback) |
