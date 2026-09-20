# Nisha — consent review for creator web search (T-CREATOR-CREDITS-SEARCH step 0.4)

**From:** Nisha (Content Lead)
**Date:** 2026-09-20
**Ref:** `.proof-os/tasks/T-CREATOR-CREDITS-SEARCH/PLAN.md` §3 step 5, step 0.4
**Read:** `src/components/meera/ConsentScreen.tsx` (v2 consent text), `NISHA-CONSENT-0917.md`, `KABIR-CONSENT-0917.md`, `src/content/legal/privacy-policy.md`, `PLAN.md`
**Tree:** `C:\Users\Sage world\Downloads\New Influora Ai\influora-credits` (read-only)

---

## 1. Does v2 consent already cover sending search queries to outside companies?

**NO.**

The v2 consent notice (shipped, live) covers three things:

1. **Paragraph 1:** Meera is your AI manager, helps with deals/payments/metrics.
2. **Paragraph 2:** "Meera will access your profile data, deal history, payment status, and metrics."
3. **Paragraph 3:** "When you **paste** a brand's brief or message, Meera's AI reads all of it..."

### What v2 says about external processing

**English:**
> "Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it."

**Hindi:**
> "Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है।"

"Meera's AI" is mentioned, but there is **no disclosure** that:
- Your **typed questions** (not just pasted briefs) go anywhere
- These questions are sent to **Google** or **Anthropic** (outside companies)
- Search is involved at all

Kabir flagged this gap in his review (KABIR-CONSENT-0917.md Q2, L100-107): the notice doesn't say chat or paste goes to an outside provider. He marked it "Recommended, not required" and suggested adding "Meera uses an outside AI service" to paragraph 2. But that was for chat/paste, not search.

**Search is a new data flow:** your typed question → Google/Anthropic → results back → shown to you.

v2 does not cover this.

---

## 2. Wording to add

This needs a **new fourth paragraph** after the existing three. It must cover:
- The question goes to Google or Anthropic
- Results are shown with sources
- We don't keep the results (Google's terms)
- Optional feature (you can refuse and still use Meera for everything else)

### English (en-IN)

> When you ask Meera to search the web, your question is sent to Google or another search provider. The results are shown to you with their sources. Your question is saved with your conversations, but the search results are not. You can skip web search and still use Meera for deals, payments, and briefs.

**Facts covered:**
- "sent to Google or another search provider" = external processing, Google named
- "results are shown to you with their sources" = Google's requirement, Claude's requirement
- "Your question is saved with your conversations, but the search results are not" = honest about what persists (the question in chat history) vs what doesn't (the Google/Claude result)
- "You can skip web search and still use Meera for..." = optional, other features unaffected

**House style:**
- Plain words, no jargon
- Active voice ("your question is sent", "results are shown")
- No "escrow" (banned word)
- No claim of regulated custody or licensed provider handling the data (none exists for search)

### Hindi (hi-IN)

> जब आप Meera से web search करने के लिए कहते हैं, तो आपका सवाल Google या किसी और search provider को भेजा जाता है। आपको results उनके sources के साथ दिखाए जाते हैं। आपका सवाल आपकी conversations के साथ save होता है, लेकिन search results save नहीं होते। आप web search को skip कर सकते हैं और फिर भी Meera का इस्तेमाल deals, payments, और briefs के लिए कर सकते हैं।

**Register:**
- "search provider" kept in English (follows the pattern in existing v2: "AI", "brief", "message", "Settings", "conversations" stay in English)
- "web search" in English (product feature name)
- "सवाल" (sawal) = question (plain Hindi)
- "भेजा जाता है" (bheja jata hai) = is sent (passive construction matching the English structure)
- "sources" in English (technical term established in product)
- "save" in English (matches existing paragraph 3's pattern: "copy save करता है")

**Same four facts as English:**
1. Question sent to Google or another provider
2. Results shown with sources
3. Question saved, results not saved
4. Optional (can skip and use other features)

---

## 3. Does this need a consent version bump (v2 → v3)?

**YES. This requires v2 → v3 and a re-consent.**

### Why

Under DPDP Act §6, consent must be:
- **Informed** — the Data Principal knows what data is being processed and for what purpose
- **Specific** — given for a stated purpose

v2 consent says:
- "Meera will access your profile data, deal history, payment status, and metrics."
- Pasted briefs are read and saved.

v2 does **not** say:
- Your typed questions are sent to Google or Anthropic.
- Web search exists as a feature.

This is a **new category of data** (search queries) and a **new external processor** (Google, Anthropic for search, distinct from the Anthropic chat usage Kabir flagged).

A creator who consented under v2 was not told their questions would be sent to a search provider. Adding this feature without a version bump would process their data in a way they didn't consent to.

Kabir's G-1 rule (KABIR-CONSENT-0917.md L352-356) applies: the new text and the backend version bump must ship **in the same deploy**. No build can have v3 text with backend still on v2, or backend on v3 with v2 text.

### One paragraph

Why this needs a bump and not just an addendum: the original consent was specific to profile/deals/payments/metrics (paragraph 2) and pasted briefs (paragraph 3). A typed search question is neither. It's a new processing activity.

DPDP doesn't allow a "we can do new things and tell you in an update" blanket. Each new purpose that isn't clearly covered by the original consent requires fresh consent.

Here, the creator gave consent to Meera reading their deals and pasted briefs. They didn't consent to their typed questions being sent to Google. Adding this without re-consent would be processing beyond the original scope.

So: **version bump required, re-consent required, G-1 deploy rule applies.**

---

## 4. UI labels for the search card

From PLAN.md:
- "2 free searches left this week" line
- "2.5 credits" line (for paid searches after the free quota)

### English

**Free searches counter:**
> 2 free searches left this week

**Paid search cost:**
> 2.5 credits

### Hindi

**Free searches counter:**
> इस हफ़्ते 2 free searches बची हैं

**Paid search cost:**
> 2.5 credits

**Register notes:**
- "free searches" kept in English (product term, follows the pattern in consent and UI: "Settings", "conversations", "briefs" stay English)
- "credits" in English (product currency unit, same as in the credit balance badge)
- "इस हफ़्ते" (is hafte) = this week
- "बची हैं" (bachi hain) = are left/remaining

Both labels are plain, no jargon, no over-promising.

---

## 5. What's dishonest or over-promising in the plan

### A. The creator's question IS stored, even though the result is not

PLAN.md says (step 5, Rules from providers' terms, L176):
> "results shown **only to the creator who asked**, with Google's search suggestions displayed, and **never stored or analysed**."

And later (L183):
> "a Gemini result is shown as its own card, **never fed into Meera's chat or saved**."

This is true for the search **result**. But the creator's **question** is a regular chat message. From the existing chat flow:
- The question is sent to `POST /creator/meera/send-turn`
- It's persisted in `ai_messages.content`
- It survives until the creator deletes that conversation

So "never stored" is only half-true: the **result** is never stored, but the **question** is.

**My wording in §2 fixes this:** "Your question is saved with your conversations, but the search results are not."

If the plan's UI or copy says "we don't store your searches" without this distinction, it's misleading. The question is stored; the result is not.

### B. The privacy policy claims training, but the code doesn't train

`src/content/legal/privacy-policy.md` line 44 says:
> "Improve our product, including training and evaluating AI features (Meera)"

But:
- No code in `influora-ai/` or `influora-api/` trains on user data.
- The PLAN explicitly says Gemini (paid tier) "Google does not use the data" (step 0.1).
- Anthropic's API terms (not checked in this review, but standard) typically say user data isn't used for training unless explicitly opted in.

So the privacy policy **over-claims** what we do with data. Adding search doesn't make this worse (search data isn't trained on either), but it highlights the mismatch.

**Recommendation (out of scope for consent, but flagged):** change line 44 to:
> "Improve our product and evaluate AI features (Meera)"

Remove "training" unless counsel confirms we have a legitimate basis to train on user data and are actually doing it.

### C. "only as long as needed" vs indefinite brief retention

Privacy policy line 80:
> "We keep your data only as long as needed for the purpose it was collected"

But from Kabir's K-1 finding (KABIR-CONSENT-0917.md L109-126):
- Pasted briefs are never deleted by any code path (dismiss, conversation delete, consent withdrawal, account deletion).
- Retention is indefinite.

This existed before search. Search doesn't make it worse (search results are explicitly NOT stored). But it's a standing mismatch between what the privacy policy promises and what the code does.

**Not a search blocker.** Already flagged by Kabir as K-1 HIGH, blocking B0 going live for real creators.

### D. The plan correctly follows provider terms

- Gemini: shown only to the requester ✓ (card shown to that creator only)
- Gemini: search suggestions displayed ✓ (plan says "returns the answer, the sources, and Google's search-suggestions snippet")
- Gemini: never stored ✓ (plan: "Nothing is stored; logs record counts only")
- Claude: sources must be shown ✓ (plan: "citations returned")

No over-promising here. The plan follows Google's and Anthropic's requirements.

### E. What if a creator asks a follow-up about a search result?

The PLAN says a Gemini result is "never fed into Meera's chat". So if a creator sees a search result and then asks Meera "tell me more about that", Meera won't have the search result in her context.

This could be confusing ("I just showed you the result, why can't you see it?"), but it's **not dishonest** — it's following Google's terms (don't store or analyse the result).

The UI should make this clear: the search card is separate from chat, and Meera can't reference it.

**Recommendation (out of scope for consent):** if the creator asks a follow-up in chat that seems to reference a recent search, Meera says "I can't see the search results card, but I can search again if you'd like."

---

## Summary for Kabir

1. **Does v2 cover sending search queries to outside companies?** NO. v2 talks about profile/deals/pasted briefs, never about typed questions being sent to Google/Anthropic for search.

2. **New wording needed:** A fourth paragraph (§2 above) in both languages, covering: question sent to Google/another provider, results shown with sources, question saved but results not saved, optional feature.

3. **Version bump required?** YES. v2 → v3, with re-consent. This is a new data processing activity (search queries to external providers) not covered by v2. Kabir's G-1 rule applies: text and backend version must ship in the same deploy.

4. **UI labels:** English and Hindi for "2 free searches left this week" and "2.5 credits" in §4 above.

5. **What's dishonest:** (A) "never stored" is only true for the result, not the question — my wording fixes this. (B) Privacy policy claims training but code doesn't train (pre-existing issue, not search-specific). (C) Privacy policy says "only as long as needed" but briefs are never deleted (K-1, pre-existing). (D) Provider terms are followed correctly. (E) Meera can't reference search results in follow-ups (correct per Google's terms, but UX should clarify).

Kabir reviews this for final approval. The fourth paragraph is written as final copy, ready to ship once approved.

---

## Final copy (ready for Kabir)

### English — new fourth paragraph

> When you ask Meera to search the web, your question is sent to Google or another search provider. The results are shown to you with their sources. Your question is saved with your conversations, but the search results are not. You can skip web search and still use Meera for deals, payments, and briefs.

### Hindi — new fourth paragraph

> जब आप Meera से web search करने के लिए कहते हैं, तो आपका सवाल Google या किसी और search provider को भेजा जाता है। आपको results उनके sources के साथ दिखाए जाते हैं। आपका सवाल आपकी conversations के साथ save होता है, लेकिन search results save नहीं होते। आप web search को skip कर सकते हैं और फिर भी Meera का इस्तेमाल deals, payments, और briefs के लिए कर सकते हैं।

### UI labels

**English:**
- Free counter: "2 free searches left this week"
- Paid cost: "2.5 credits"

**Hindi:**
- Free counter: "इस हफ़्ते 2 free searches बची हैं"
- Paid cost: "2.5 credits"

---

**Version bump:** v2 → v3 required.
**Deploy rule:** G-1 applies (text + backend version in same deploy).
**Reviewed by:** Nisha (content). Next: Kabir (security/privacy approval).
