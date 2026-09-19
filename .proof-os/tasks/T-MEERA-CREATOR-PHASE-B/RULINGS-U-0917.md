# Wave U rulings: R-U1, R-U2, and the last-call bar for U-1, U-2 and U-3

**From:** Priya (CTO)
**To:** Arjun. Builders: vikram, ananya. Copy: nisha. Security: kabir
**Date:** 2026-09-17
**Tree:** `influora-b0`, branch `feat/meera-creator-phase-b0`, Wave U uncommitted on `df20091`
**How this was checked:** read only. No Maven, no stash, nothing in `New Influora` touched. `CreatorBriefService.java` was being edited while I read it (mtime 13:19). Line numbers for its new F1 code are as of 13:19. Its older methods are cited by name.

---

## 0. Vikram's F1 fix: I agree, with three additions

Arjun's fix: a NEW brief is never returned as a successful read. A young NEW brief gets 409 `BRIEF_STILL_READING`. A NEW brief older than the analysis budget gets analysed again. `get_brief` is not retried, and it gets its own longer read timeout. That design is right, and it matches SPEC §3.6 L611 ("analyses it if not yet ANALYZED").

The cause is confirmed in code. influora-ai waits 5s for Spring (`app/config.py` L226, `spring_read: float = 5.0`) and retries up to twice (L235). The retry happens on any `httpx.HTTPError`, including a read timeout (`app/clients/spring.py` L162-171). The first read of a deal can take the AI client's 5s connect plus 15s request (`application.yml` L294-295). By then the raw row is already committed on its own transaction, so the retry finds a NEW row. Before the fix, `ensurePlatformBrief` returned that row untouched and `get` built a response from its empty snapshot. Vikram's `readOrReanalyse` (L312-324 at 13:19) now sits in `get`, so the fix covers both the `deal_id` and the `brief_id` paths. That is correct.

**Addition A (required): an analysis that is missing or unreadable is never a clean success, whatever the status.** Status NEW is not the only way to reach an empty snapshot:
- A brief can be dismissed while still NEW. `dismiss()` works from any status (`CreatorBriefService.dismiss`, `BriefStatus.java` L3-8), so a DISMISSED row can have `extracted_json` still NULL (migration `V20260910100100` L54-57).
- `toResponse` turns a snapshot it cannot parse into nulls on purpose. Its javadoc says so.
- `writeJson` stores NULL when serialisation fails. The row is still marked ANALYZED.

In all three cases `flags` comes back as `null`. The model reads that as "no flags", which is the same failure as F1. `GetBriefExecutor` must refuse any read where `extraction == null` or `flags == null`, with a non-2xx code of Vikram's choosing. An empty list `[]` is a real "no flags" and still passes.

**Addition B (required): the numbers and the model's instruction.**
- The `get_brief` read timeout in influora-ai is a named setting next to `spring_read`, at least Spring's `analysisBudget()` plus 10s. That is **40s** with default settings. Its comment must name `CREATOR_COPILOT_AI_CONNECT_TIMEOUT_SECONDS` and `CREATOR_COPILOT_AI_REQUEST_TIMEOUT_SECONDS`, because Python cannot read Spring's environment. Every other creator read keeps 5s.
- The safety property rests on **no retry**, not on how the two timeouts compare. Without a retry, a timeout only reaches the model as `network_error`, and asking again later gets either a 409 or the finished brief. If an operator later raises Spring's timeouts past 40s, reads get slower to fail. They do not become wrong.
- A 40s tool call does not kill the stream. `chat.py` L700-711 keeps sending heartbeats while a tool runs, and the browser gives up only after 30s of silence (`useMeeraStream.ts` L60).
- Add to the `get_brief` description in `creator_schemas.py`: if the brief is still being read, tell the creator so and do not call the tool again this turn. That rides the same `PROMPT_VERSION` bump as R-U1's description fix.

**Addition C (required): a test that fails if `CreatorBriefService.get` becomes `@Transactional` again.** `get` now reaches the blocking AI call, which is the exact trap `GetBriefExecutor`'s javadoc (L23-34) describes. The executor already has this guard. Its new callee needs one too.

**Residuals I accept for B0.** Each must be written in the code javadoc, not only in a message:
1. Two stale reads at the same moment can both run the analysis. The cost is bounded by the per-creator monthly brief cap (SPEC §7.5 L1081).
2. Two concurrent **first** reads of a deal can create two PLATFORM rows. Nothing in the schema forbids it: the table has only a PK and one index (migration L61-63), and the finder is `findFirst` without an order (`CreatorBriefRepository` L57-61). The second row heals once analysed. This is not a wrong-success path.
3. A brief whose analysis throws every time pays one AI call per read until the brief cap switches it to the fallback extractor. Same bound as item 1.
4. `GET /creator/briefs/{id}` now inherits the re-analysis and the 409. Nothing in `src/` calls `creatorBriefs.get`, `list` or `dismiss` today (grep, test files excluded).

A recommended follow-up, not a condition: a compare-and-set claim on `updated_at` (`UPDATE ... WHERE status='NEW' AND updated_at < cutoff`) would remove residuals 1 and 3. The column already exists and is mapped (migration L60, `CreatorBrief.java` L108), so **it needs no migration**. Put it in Wave C-2 or a ticket.

---

## R-U1: the model cannot learn a pasted brief's id

### Ruling: (c), in prefill form. Not (a), not (b).

SPEC already designed this. §8.5 L1234 gives `PasteBriefCard` an "Open in Meera" action that opens the chat with the message "Look at brief {brief_id}". The card left it out only because "the chat has no first-message entry point yet" (`PasteBriefCard.tsx` L17-19). R-U1 means building that entry point. I am changing one detail of §8.5: the chat **fills the message box** with the text. It does **not send** it. The creator taps Send.

Why prefill and not auto-send: an automatic send only happens after an async connect (`MeeraCopilotChat.tsx` L180-205). To be safe it needs a send-once guard that survives React StrictMode running effects twice, a reconnect, and a consent screen in between. Prefill has none of those failure modes, and the creator sees exactly what goes to Meera.

### Why not (a)
U-1's own row says it exists "so Meera can read a pasted brief in chat" (`ASSIGN-PENDING-0917.md` L19). Wave U's stated gap is that Paste and Read "can neither be pasted into by a person nor read by Meera" (L15). Option (a) would ship that row false. It would also leave `brief_id` unreachable for `check_deal_risks` (SPEC L626) and for Wave D's `draft_reply` (SPEC L629).

What does work today: `get_my_deals` returns a `brief_id` for platform briefs (`GetMyDealsExecutor.java` L172-177, `CreatorToolDtos.DealSummary` L43). A pasted brief has no collaboration in B0, so that route never covers pastes.

### Why not (b)
1. **Security, which alone decides it.** `brand_name_guess` is pulled out of brand-written text (`CreatorBriefService.analyse` passes `extraction.brandName()` to `saveAnalysis`). The extraction step wraps that text as untrusted (SPEC L1095). A recent-briefs list would copy brand-controlled strings into Block B, a **system** block. Block B is built from an allow-list only (`assembler.py` L646-651), and the persona tells the model that text in its context "is guidance for how YOU act" (`creator_persona.py` L135-136). `_safe` only neutralises angle brackets (`assembler.py` L718-720). That reopens the injection path the persona's trust boundary closes (L130-134), and it would need Kabir.
2. **A correction to the brief.** `schema-check.yml` does **not** guard the creator context. Its blocking context diff cuts the file at `CreatorContextResponse` (L221-228) and compares only the brand `ContextResponse`. The gate (b) actually hits is `influora-ai/tests/prompt/test_creator_context_drift.py`, which parses the Java record (L60-64) and fails on exact-set drift (L67), an unread field (L87) and a render check (L103, fixture L119-149).
3. **Cost.** About 8 files in two languages, all in one commit because of the drift test:
   - Java: record component 33, `MeeraContextService` wiring, `MeeraContextServiceTest`
   - Python: allow-list plus render, the brand forbidden-fields entry, the drift fixture, the `PROMPT_VERSION` bump
   - Every creator turn also carries the list in its prompt tokens, whether or not she ever pastes.

### What to build (call it U-5)

| Half | Files | Owner | Review → last call |
|---|---|---|---|
| Frontend | `src/components/creator/copilot/PasteBriefCard.tsx`, `src/pages/creator-copilot.tsx`, `src/components/creator/MeeraCopilotChat.tsx`, plus a test for each: `PasteBriefCard.test.tsx`, `creator-copilot-paste-brief.test.tsx`, a `MeeraCopilotChat` test. **3 source + 3 test.** | ananya | kavya → meera → **priya** |
| influora-ai, riding with Vikram's no-retry change | `app/tools/creator_schemas.py` (`get_brief` description, below), `app/prompt/assembler.py` (two stale comments, below), `app/config.py` (`PROMPT_VERSION` bump, L69). **3 source.** | vikram | kavya → **priya** |
| Wording of the button and the prefilled text | same files | nisha | **nisha** |

**Gates crossed:**
- Not `schema-check.yml`: no brand tool or brand context field changes.
- Not the creator drift test: no context field changes.
- Yes the stale-comment gate's rule 3. `assembler.py` is under `PROMPT_SOURCES` (`ci/stale-comment-check.py` L66), so the bump is mandatory, and it is included above.
- Also: `test_tool_schema_anthropic_valid` (the description stays combinator-free), plus vitest, tsc and eslint.

**The bump is required even though the gate misses `creator_schemas.py`.** `PROMPT_SOURCES` lists `app/prompt/` and `app/tools/schemas.py` but not `app/tools/creator_schemas.py` (L66). A creator tool description can change the model's behaviour without the gate noticing. Bump anyway. Closing that gap is a separate CI ticket.

**influora-ai text changes:**
- `creator_schemas.py` L197 says `brief_id` comes "from an earlier tool result". That is true only for platform briefs. The new text must say where both kinds of id come from (a deal's `brief_id` from `get_my_deals`, or a brief id the creator gave in the chat), that passing both ids is refused (Kavya's LOW), and the still-reading instruction from Addition B.
- The persona bullet (`creator_persona.py` L162-165) stays as is. Bullets may not name other tools (L148-152).
- `assembler.py` L190-196 ("four as of Wave 3") and L752-754 ("arrives here with four names") are false now that `get_brief` is wired. Fix both in the same commit as the bump.

**Behaviour the frontend tests must prove:**
1. The "Ask Meera about this brief" button renders only when `result.brief_id` exists **and** the page supplied a handler.
2. Consent known, chat closed: clicking opens the chat with the message box holding the prompt, and the prompt contains the literal brief id. **Nothing is sent** (assert the send path is never called) until she taps Send.
3. Chat already open and she has typed something: her text stays and the prompt is appended. Never overwrite what she typed.
4. Consent known to be missing: the consent screen opens. Accept opens the chat with the prompt filled in. Decline opens nothing and drops the prompt.
5. `featureDisabled === true`: no card and no button.
6. Wording (Nisha): must contain the id; must promise no drafting or sending; must not use the banned payment-hold word. A hi-IN variant keyed off the page's `language`, the same way `ConsentScreen` does it.

No new security surface. A creator who types someone else's id gets 404, because `requireOwnedBrief` scopes the lookup to her profile. Kabir does not need to see U-5.

### Does R-U1 block my U-1 last call?
**No.** U-1 is judged on the executor, the route and F1. **But U-5 cannot move to Wave D.** Wave U does not close without it (L15, L19), and D-1's `draft_reply` needs a reachable `brief_id`.

---

## R-U2: consent does not mention pasted briefs

### Ruling: yes, consent must cover pasting before B0 ships, and every creator re-consents.

**Why.** The v1 notice (`ConsentScreen.tsx` L30 in en-IN, L24 in hi-IN) covers her own platform data: profile, deal history, payment status, metrics. A paste is a different kind of data:
- The text comes from outside Influora and was written by someone else.
- It can hold other people's names, emails and phone numbers.
- It goes to AI processing (SPEC §7.5 L1092-1099).
- It is **kept**: `raw_text` is `NOT NULL` (migration L52), and dismissing a brief only changes its status (`CreatorBriefService.dismiss`, `CreatorBrief.java` L201-202).

The notice's one promise about deletion covers **conversations**, not briefs. B0 has no route that deletes a brief (SPEC §3.8 L713-723; the service has no delete method).

### The exact text: en-IN
Add it as a **new final paragraph** after the existing body, and leave the existing two paragraphs unchanged. It goes after the conversations-deletion sentence so that sentence cannot be read as covering briefs:

> When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails or phone numbers in it, and saves it with your briefs. Before you paste, remove anything you don't want Meera to read.

It makes no promise about drafting, sending or deleting, and it does not use the banned word.

**hi-IN** is Nisha's to finalise, in the same register as L24. A draft to start from:

> जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI पूरा text पढ़ती है, उसमें लिखे नाम, email या phone number भी, और उसे आपके briefs के साथ save करती है। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।

### Re-consent: yes. Bump `v1` to `v2` in the same commit as the text.
- **How it works today.** Consent is recorded against `CreatorAgentPreferences.CURRENT_CONSENT_VERSION` (L47, `"v1"`). `isConsentAccepted()` requires the stored version to equal that constant (L401-403), and `recordConsent()` re-stamps an older version (L462-467).
- **Nothing new to build.** Bumping the constant sends every creator back through flows that already exist:
  - the page probe reads `consent_accepted` (`creator-copilot.tsx`)
  - the paste card sends `CONSENT_REQUIRED` to the consent screen (`PasteBriefCard.tsx` L109-111)
  - so does the chat's `onConsentRequired`
  - the brief controller checks consent before `paste` persists anything (`CreatorBriefController.java` L104-128)
- **Why not grandfather v1.** The version field exists so that "a text change must be able to force re-consent" (L454-460). A creator who agreed before pasting existed has never been told about it.
- **Cheapest time to bump.** Phase A is not deployed yet: S-2, "Deploy Phase A", is still owed (`ASSIGN-PENDING-0917.md` L70). Today only test and staging accounts re-consent.
- **What else changes.** Tests that read the constant follow it on their own. The `"v1"` literals in test fixtures are DTO data, not checks against the constant. Vikram's suite run is the proof, not my reading. Update the mock `consent_version: 'v1'` in `src/lib/api.ts` (L6642) to match.

### Owners

| What | Who |
|---|---|
| Copy, both languages | **nisha** (last call on the words) |
| `ConsentScreen.tsx` text, `CreatorAgentPreferences.java` L47 `v2`, `api.ts` mock, **one commit** | ananya builds; vikram runs the Java suite |
| Code review | kavya → **priya** |
| **Kabir must see the sentence before it ships** | **kabir** |

**Two questions only Kabir can answer:**
1. Must the notice name the outside AI provider that processes the text? Neither v1 nor this sentence does.
2. Is it acceptable for B0 that a pasted brief, which may hold other people's personal data, has no delete route? If Kabir says no, it goes to Swapnil as a scope-and-legal call. My technical recommendation is a small hard-delete route for one brief (service method, route, test). The notice must **not** promise deletion unless that route ships.

**Not required:** today nothing ties the notice text to the version constant, because the text is in TypeScript and the version is in Java. A test that reads both would stop a copy edit from shipping without a bump. Worth doing, not a B0 blocker.

---

## Last-call bar

For every item: the proof is the builder's own run output, quoted with file-level counts. For Maven that means surefire's "Tests run / Failures / Errors / **Skipped**" lines, not an exit code taken through a pipe. For each guard test, the builder must show it **failing** against a deliberately wrong version before showing it passing. A test that has only ever been green does not count.

### U-1: `GetBriefExecutor`, its route, the F1 fix
1. Tests on **both** paths, through `CreatorBriefService.get`:
   - young NEW → 409 `BRIEF_STILL_READING`, analysis never called
   - stale NEW → analysis called exactly once, response ANALYZED
   - ANALYZED → no AI call

   Each must go red when `readOrReanalyse`'s NEW branch is replaced with a plain `toResponse`.
2. Addition A: a missing or unreadable analysis is refused. At minimum, a test with a DISMISSED row and a NULL snapshot, and one with an ANALYZED row whose flags are NULL.
3. Addition B:
   - influora-ai forwards `get_brief` with `allow_retry=False` on the 40s named timeout.
   - The test must fail if `get_brief` is dropped from that set, or if its timeout falls back to `spring_read`.
   - `analysisBudget()` reads the properties. A test with tightened properties shows the budget shrinking with them.
4. Addition C: the no-`@Transactional` guard on `CreatorBriefService.get`.
5. Ownership, with no AI call and no row written on refusal:
   - someone else's `deal_id` → 404 `DEAL_NOT_FOUND`; must go red when `findByIdAndCreatorId` is put back to `findById`
   - someone else's `brief_id` → 404 `BRIEF_NOT_FOUND`
   - both ids → 400; neither → 400
6. Seams stay green:
   - `MeeraContextServiceTest`: the five wired names equal the five routes
   - `FloorBarrierTest`
   - `test_creator_context_drift.py` and `test_tool_schema_anthropic_valid`
7. Stale text fixed:
   - `api.ts` L7059 ("not re-analysed" is now false)
   - the `creator_schemas.py` description
   - the `assembler.py` comments, with the `PROMPT_VERSION` bump
8. Kavya's LOWs closed: the description says both ids are refused; a degraded reason is carried; the card is drawn. The card must never show a brief with no analysis as a clean brief.
9. Residuals 1-4 from section 0 written in the javadoc.
10. **Live, after S-2 (blocks "done", not the code pass).** One cold `get_brief` by `deal_id` on the live stack returns ANALYZED on the first call, with the stream still alive. This is also the proof for `createdAt` stored in Java (`CreatorBrief.java` L125/L149) surviving the JDBC time-zone round trip. The four compose files in `deploy/` set `serverTimezone=UTC`. I did not open the live server's own compose file.

### U-2: `PasteBriefCard`
1. **R-U2 is in the branch**: the text, `v2`, and Kabir has seen it. I will not pass U-2 without it, because U-2 is what makes pasting possible.
2. Consent known missing: Analyse sends nothing (assert `api.creatorBriefs.paste` is not called) and opens consent. Accepting from the paste card does not open the chat. Consent unknown: the paste is sent and a `CONSENT_REQUIRED` refusal is handled.
3. The 8,000-character cap: an 8,001-character paste keeps 8,000 and shows the "Only the first 8,000 characters were kept." line. No `maxLength` attribute.
4. Errors show inline, never as a toast. Error text on `bg-destructive` uses `text-destructive-foreground`.
5. `degraded_reason` "cap" and "ai_unavailable" render different, honest text (test).
6. Flags use scope `BRIEF:{brief_id}`.
7. `FEATURE_DISABLED` hides the card.
8. No dead controls: no "Create secure link" (B1), and "Ask Meera" only as specified in U-5.
9. Kavya's LOW closed: use the shared rupee formatter.
10. **Live, after S-2:** one real paste renders a summary, flags and a quote.

### U-3: session dismissal of risk flags
1. A flag with `dismissible: false` is never hidden and has no control, **even when its code is already in `sessionStorage`**. The test seeds storage first, and must go red when `flag.dismissible &&` is removed from the filter (`useRiskFlagDismissals.ts` L129).
2. `dismiss()` refuses a non-dismissible flag (L136).
3. When `sessionStorage` throws on both read and write, the page falls back to memory, nothing throws, and flags still render.
4. No scope means no dismiss control.
5. Every place that renders `DealRiskCard` passes the handler. By grep there are four: `creator-chat.tsx` L2624 and L2846, and `CreatorToolResultRenderer.tsx` L639 and L678. The builder's evidence is that same grep, with every hit wired.
6. Hiding a flag on `DEAL:X` in Meera's chat hides it in that deal's room, and the reverse. The keys match: `DealRiskService.TARGET_DEAL` / `TARGET_BRIEF` are `"DEAL"` / `"BRIEF"` (L95-96), and the chat card builds `target:target_id` (`CreatorToolResultRenderer.tsx` L671-675).
7. The wording says "hidden for this session" with "Show". Never "dismissed" or "removed".
8. A Java test pins the non-dismissible rules to `HideDisclosureRule`, `OffPlatformPaymentRule` and `RegulatedCategoryRule`, so "three flags stay non-dismissible" (L21) is proven, not just stated.

---

## Round 3 — K-2 / U-7 / K-3

**Written:** 14:38. **Read:** `VIKRAM-PLAN-K2-U7-K3-0917.md` (14:33) and `KABIR-CONSENT-0917.md` (13:48), plus the source lines cited below. Read only: no Maven, no vitest, no stash. `CreatorBriefService.java` is as of 13:43 and `loop.py` as of 14:17.

The U-1, U-2, U-3 and U-5 bars above stand as written. One addition: **K-2 is now a condition of my U-2 last call.**

### Summary

| # | Question | Ruling |
|---|---|---|
| 1 | K-2 backfill | **No re-evaluate path. Residual only**, with one check at deploy (below) |
| 2 | Repeat DELETE | **404 stays.** The UI treats `BRIEF_NOT_FOUND` on a delete as "already gone", not as an error |
| 3 | Where delete lives | **(c): a "saved briefs" table inside the existing Meera settings section**, not a new screen. Its own item before going live |
| 4 | Bucket and gate | Gate approved, and **extended to the list route**. Bucket approved at **20 per window, not 5** |
| 5 | K-3 placement | **Neutralise only the model's copy.** Cover **every string** in every creator tool result, not five named fields. Kabir picks the mechanism |
| 6 | Order | **Changed:** Vikram does K-2, then U-7 backend, then K-3. Ananya builds U-7 UI from this contract without waiting for Vikram |

---

### 1. K-2: fix approved; no re-evaluation of old rows

**The fix is right.**
- `evaluateExtraction` passes `null` as the rule text (`DealRiskService.java` L287).
- `RiskText.matches` returns false for null (`RiskText.java` L66-71).
- `evaluateBrief` already passes `brief.getRawText()` (L248), and its javadoc says why (L208-213).
- `lastBrandMessage` stays `null`. `RiskContext` makes it non-null only on the deal path, so `PARTNERSHIP_ADS_REQUEST` never fires on a brief (`RiskContext.java` L76-81).
- The single call site `CreatorBriefService.java` L464 is shared by all three callers: paste (L208), `ensurePlatformBrief`, and stale re-analysis (L349).

**No re-evaluate path.** Frozen snapshots remain the rule. The evidence that no real row can exist is in git, not in a belief:
- `V20260910100100__creator_briefs.sql` was added only by `e54c071`.
- `git branch -r --contains e54c071` returns nothing in this clone, and pushing this branch is still owed (S-1).
- So no image built from a remote branch can have created the table.

Remote-tracking refs are only as fresh as the last fetch, and a server could still have been deployed from a local build. That is what the deploy check closes:

> **Deploy check (owner: meera, at the first deploy that carries `creator_briefs`):** the deployed commit contains the K-2 fix. Any `creator_briefs` row on that database created before that deploy is test data: **delete it** (U-7 route or SQL). Do not re-evaluate it.

If a future rule change ever has to reach stored flags, that is a designed migration with its own ruling, not a quiet recompute on read.

**K-2 pass bar.** Vikram's test list (plan L57-67) is adopted in full, plus one addition. The existing mocked tests in `CreatorBriefServiceTest` must check that the fifth argument equals the stored raw text (`eq(...)` or a captor). The end-to-end tests with a real `DealRiskService` catch the bug. The captor makes a later change to the argument fail in the fast suite too.

---

### 2. A repeated DELETE returns 404

Keep Vikram's behaviour (plan L117-119). A 204 for an id that is not hers or no longer exists would report that something was erased when nothing was. That is the same false-success class as F1. It would also make any future erasure record, or any future delete tool the model can call, unreliable.

**The double-click is a UI job:**
- The delete control is disabled while its request is in flight. The existing conversations table already does this (`MeeraSettingsSection.tsx` L691, `disabled={deletingId === ...}`).
- A `404 BRIEF_NOT_FOUND` answer to a DELETE removes the row with no error shown. The UI only lists her own briefs, so a 404 there can only mean it is already gone (another tab, or a double click).
- Test: a second delete of the same id, answered 404, leaves no row and no error toast.

---

### 3. Where the delete control goes: (c), a table in the existing Meera settings section

**The smallest honest place already exists.** `MeeraSettingsSection.tsx` renders a "My Meera Conversations" DPDP table: loading and empty states, a delete per row, and an `AlertDialog` confirm (L636-704, dialog from L707, delete handler L298-315). The section sits inside the creator settings page, not on its own route (L40-45).

A second table in the same section, cloned from that one, gives every brief a delete control:
- no new route, tab or screen
- sits next to the only other erasure control Meera has

It also lets Nisha's Case B sentence point somewhere real, the way the conversations sentence already points to Settings.

**Why not (a).** A new list screen with a route and a nav entry is more scope than erasure needs. It adds a surface Nisha, Kabir and Neha must each review, and a second place to answer "where are my briefs".

**Why not (b).** A delete button on the just-pasted card cannot reach yesterday's brief. So Case B copy could never ship, and going live would depend on Swapnil accepting a manual erasure process (Kabir L125). That is not needed when the fix is this small.

**What to build (U-7 UI):**
1. A "saved briefs" table beneath the conversations table.
   - **Rows:** where the brief came from ("Pasted" or "From a deal", from `source`), the brand name guess, or a plain fallback when it is null, and the date.
   - **Row actions:** delete behind the same confirm dialog.
   - No other actions per row in this item.
2. Fetch with `limit=100`, the service's `MAX_LIST_LIMIT`. When exactly 100 come back, show a line saying these are her latest 100 and older ones appear as she deletes. With that line, every brief can be reached.
3. Loading, empty and error states copy the conversations table. The feature-flag hide copies L317-320.
4. Brand text in rows renders as React text. No HTML rendering.
5. **Copy (Nisha):** the section heading, the confirm text, the 100-limit line, and the switch of the notice from Case A to Case B (`KABIR-CONSENT-0917.md` L38-44).

**Consent version sequence.** Each version is true for the commit it lives in:
- **Wave U** commits **Case A + `v2`**.
- **U-7 UI** switches to **Case B and bumps to `v3` in the same commit** (Kabir C3, L58).

Nothing is deployed, so the extra bump costs no real creator anything, and it keeps the rule simple: the text changed, so the version changed.

**Owners and placement:**

| Part | Builder | Review → last call |
|---|---|---|
| U-7 backend: delete route, list gate change, bucket | vikram | kavya → **priya** |
| U-7 UI: settings table, Case B copy, `v3` | ananya | kavya → neha (live) → **kabir** (erasure control, his condition 3) and **priya** (code) |
| Copy | nisha | **nisha** |

**Its own item, blocking go-live.** It does not block Wave U from closing, and it is not Wave D.

**Forward condition for D-1, recorded now.** `meera_drafts.brief_id` has no FK on purpose (migration `V20260910100300` L5-12, column L54), so a brief delete leaves its drafts behind. B0 has no code that writes drafts, so there is nothing to clean up today. When `DraftReplyExecutor` lands, deleting a brief must also delete that brief's drafts that were **not** sent: PENDING and DISCARDED. Their text is derived from the brief and can repeat brand staff details. SENT drafts are already deal messages and stay. This is a D-1 last-call item.

---

### 4. Bucket and gate

**Gate: flag + identity, no consent. Approved** (Kabir's condition 2). Two extensions:
- **`GET /creator/briefs` (the list) moves to the same identity-only gate.** Today it uses `requireConsentedCreator` (`CreatorBriefController.java` L136). A creator who withdrew consent could then reach the delete route but never see the table that calls it. Listing her own stored rows makes no AI call, so consent does not apply.
- **`GET /creator/briefs/{id}` stays consent-gated** (L153). Since F1 it can start an AI re-analysis. Paste (L126) and dismiss (L161) are unchanged.
- **The feature flag stays in the gate** (Kabir L121). Runbook line: if `MEERA_CREATOR_ENABLED` is off for longer than an incident, erasure requests go through the manual path in Kabir L125 until it is back on.
- Tests: list and delete both succeed after consent is withdrawn. Each goes red when its gate is put back to `requireConsentedCreator`.

**Bucket: yes, user-keyed and method-scoped, but 20 per window, not 5.**
- The shared window is 60 seconds (`AuthRateLimitFilter.java` L406-407).
- A creator clearing her saved briefs is a real burst: the table holds up to 100, and 5 per minute throws a 429 in the middle of an erasure.
- A delete makes no AI call, is scoped to her own rows, and a flood can only harm her own data.
- Use the same limit as `creator-brief-get` (20, L356), under its own property so it can be tuned.
- The DELETE match must check the method, so a delete never spends the GET bucket that shares the `^/creator/briefs/[^/]+$` pattern (L160), and a GET never spends the delete bucket.
- The UI shows a 429 inline in the table.
- Vikram's bucket test (plan L133) stands, plus one test that DELETE and GET on the same path land in different buckets.

---

### 5. K-3: neutralise only the model's copy, on every string

**Placement: the model's copy only. Your preference is correct, and the code confirms it.**
- `loop.py` L660 takes `data = response.data`. L666-671 serialises it into the model's `tool_result` block. L673 hands **the same object** to the event.
- `chat.py` L724-739 streams `event.tool_result_data` to the browser as `data`.
- Only assistant text is persisted (`chat.py` L896-899, `content=real_answer_text`). The model copy exists only in the in-turn `messages` list (`loop.py` L675) and is never replayed from storage, so neutralising at L670 is complete.
- The cards render these strings as React text. A grep for `dangerouslySetInnerHTML` or other HTML rendering in `CreatorToolResultRenderer.tsx`, `PasteBriefCard.tsx` and `deal-risk-card.tsx` finds nothing. A raw `<` in the card is inert, while `&lt;` would show on screen as `&lt;`.

Vikram's version (plan L156-158) would put a visible defect on the creator's own card to guard a surface that is not exposed.

**Scope: every string in the result, for every creator tool. Not the five fields.** The plan says flag copy is never brand text and `exclusivity_brands` is a closed vocabulary (plan L152). Both are wrong:
- `BlockedBrandRule` puts the brand name into the flag's `detail` and `data` (`BlockedBrandRule.java` L41-50).
- `CompetitorConflictRule` puts an earlier brand's name into `detail` (`CompetitorConflictRule.java` L79-86).
- `exclusivity_brands` is free text up to 120 characters (`brief_extract.py` L338-339).
- `get_my_deals` carries `brand_name` and `campaign_title` (`CreatorToolDtos.java` L30-31), which brands write.

A field list will miss the next field someone adds. Walking every string costs nothing for ids, enums and pre-formatted numbers, because they contain no angle brackets.

**Shape:**
- One pure function, e.g. `model_copy_of_tool_result(tool_name, data)`, that returns a **new** structure.
- It must never change `data` in place: L661-662 read `data`, and L673 streams it.
- Call it only inside the `_safe_json(...)` at L670. Apply it when `is_creator_tool(tool_name)`, on successful results.
- Brand tools and Spring-authored error payloads are out of this item. Whether brand tools need the same treatment is Kabir's to raise, not mine to widen here.
- **Mechanism is Kabir's.** Today it is `neutralize_angle_brackets` (`untrusted.py` L14-44). If Kabir picks a different neutraliser, only this one function's body changes.

**Tests (replacing plan L174-178):**
1. The model block's content is neutralised for a `get_brief` result with an injection in `summary_lines`, **and** in a flag's `detail`, **and** in `exclusivity_brands`.
2. The event's `tool_result_data` **equals the unmodified Spring payload**, and the original `response.data` object is not mutated. This replaces plan test 2, which asserted the opposite.
3. A `get_my_deals` result with brackets in `brand_name` is neutralised in the model copy only.
4. A brand tool result is unchanged.
5. **Falsify:** pass `data` straight to `_safe_json` at L670 and tests 1 and 3 go red. Neutralise in place instead and test 2 goes red.

**Persona bullet: placement approved.**
- It goes in the static "Trust boundaries" section (`creator_persona.py` L130-139), worded generically.
- It must name no tool; `test_creator_prompt.py` L179-183 already enforces that.
- The persona text is not pinned word for word: the tests compare it to itself or check the prefix (L79, L89, L103). So the addition breaks no golden-text test.
- Bump `PROMPT_VERSION` in the same commit.
- Kabir approves the wording together with the mechanism.

---

### 6. Order: changed

**Vikram: K-2 → U-7 backend → K-3.**
1. **K-2 first.** It blocks my U-2 last call, and so blocks Wave U from closing.
2. **U-7 backend next.** It gates go-live, Case B copy, and Kabir's last call on erasure. It is small: one service method, two gate changes, one bucket. It touches the same two files K-2 just touched (`CreatorBriefService.java`, `CreatorBriefController.java`), so Kavya re-reads them once, not in two separate rounds.
3. **K-3 last.**
   - It only gates D-1, and D-1 cannot start before Wave U closes anyway.
   - It changes `loop.py` and `test_loop_creator_dispatch.py`, which Kavya is re-reviewing for U-1 right now (`loop.py` changed at 14:17). Landing it after her recheck delays nothing and leaves her review untouched.
   - **Hard gate: K-3 passes Kabir and my last call before D-1 starts.**

**Ananya:** U-7 UI after her remaining Wave U items (U-5, the consent text, the U-2 LOW). She builds from the contract fixed in sections 2-4, with the `api.ts` client mocked. She does not wait for Vikram's backend. Live verification happens once both halves are in the branch.

### Pass bars for the new items
- **K-2:** section 1. Plan L57-67 plus the argument check. Every guard test is shown red against `null` first.
- **U-7 backend:**
  - plan L129-134, with test 5 at 20 per window and the method-split test
  - the list-after-withdrawal test from section 4
  - the delete-after-withdrawal test, red when put back to `requireConsentedCreator`
- **U-7 UI:**
  - every brief up to 100 reachable, with the over-100 line
  - confirm before delete; control disabled in flight; 404 treated as gone with no error; 429 shown inline
  - flag off hides the table
  - Case B text and `v3` in the same commit
  - **live:** one paste, then delete from Settings, then the row is gone from the database. Neha on the live stack after S-2. Kabir signs.
- **K-3:** section 5, tests 1-5, the `PROMPT_VERSION` bump in the same commit, Kabir on mechanism and wording.

### 7. Erasure when the feature flag is off: (a). Written 14:45; replaces the feature-flag bullet in section 4

**Ruling: (a).** List (`GET /creator/briefs`) and delete (`DELETE /creator/briefs/{id}`) skip `requireFeatureEnabled()`. They rely on `.requestMatchers("/creator/**").hasRole("CREATOR")` (`SecurityConfig.java` L280-281, all methods) plus the identity check.

**Why (a):**
- **The flag's stated purpose is already covered.** The controller gates on the flag first so a disabled feature is hidden from an unauthenticated caller (`CreatorBriefController.java` L43-46). A caller who is not a creator never reaches these routes anyway.
- **The kill-switch has nothing to stop here.** It exists to stop AI calls and new data, and neither route makes an AI call or creates data.
- **(b) is a human step that fails silently.** It needs an on-call owner during an incident, and it makes a data right depend on an operator toggle. Deleting is the one action a kill-switch should never block.
- **Every other brief route stays behind the flag.** Paste, opening one brief (`get`) and dismiss keep `requireConsentedCreator` (L126, L153, L161). When the flag is off, those routes answer 404 `FEATURE_DISABLED` (L81-86).

**Required with it:**
- **UI.** The settings section currently hides itself entirely on `FEATURE_DISABLED` (`MeeraSettingsSection.tsx` L317-320). U-7's saved-briefs table must instead render from its own list call whenever that call succeeds and returns rows, even when the rest of the section is hidden. Otherwise (a) is a backend-only right that nobody can reach from the UI.
- **Guard test.** With the flag **off**, list and delete succeed; paste, get and dismiss return 404. With consent **withdrawn**, list and delete succeed; paste, get and dismiss return 403 (Kabir L286). The test must go red if `requireFeatureEnabled()` is put back on either erasure route, or if the flag-free helper is wired onto any other route.

**No runbook owner is needed.** The runbook line in section 4 is withdrawn.

**For the record, the rest of the coordinator's note, no objections:**
- **K-3:** I accept Kabir's `wrap_untrusted("brand_written", …)` on the model's copy only (`KABIR-CONSENT-0917.md` L232-239). It covers the whole `extraction` object and whole `flags` array for `get_brief` and `check_deal_risks`, and `brand_name`/`campaign_title` for `get_my_deals`. It replaces the "every string, neutralised" mechanism in section 5. My placement ruling is unchanged: the card receives the original `data`.
- **Resurrection fix** (Kabir L293-298): required. **Add one condition:** re-reading the managed row inside `saveAnalysis` must not undo a dismissal. `applyAnalysis` sets status to ANALYZED unconditionally (`CreatorBrief.java` L183). Keep a DISMISSED row DISMISSED while still writing its analysis, and add that case to the same H2 test.
- **Bucket:** 20 per window, as in section 4.
- **Case B text:** Kabir's version with "Your briefs and your conversations are deleted separately." (L276). Nisha finalises both languages.


---

## Round 4 — K-2b: the two non-dismissible text patterns (RX-1)

**From:** Priya (CTO). **Written:** 2026-09-17, 17:00.
**Inputs:**
- Kabir, `KABIR-CONSENT-0917.md` "Last call — K-2" (L566 onward): APPROVE WITH CHANGES, measured on real Java 21 regex.
- My own probe on the JDK the build uses (`jdk-21.0.9.10-hotspot`), a single-file program in my scratchpad. Its results are quoted below where they add to Kabir's.

**The K-2 code is right and stays as built.** This round is only about the two patterns K-2 now runs every paste through, `OffPlatformPaymentRule.OFF_PLATFORM_TEXT` and `HideDisclosureRule.HIDE_TEXT`, plus `RiskText.norm`. Call it **K-2b**.

### Summary

| # | Question | Ruling |
|---|---|---|
| 1 | Adopt Kabir's items 1-4 before U-2's last call, or before go-live only? | **Before U-2's last call.** For U-2, "K-2 done" now means K-2 and K-2b |
| 2 | Is option (b), a text-only hit as a dismissible WARN, still wanted on top? | **No.** A decision rule fixed now for gate metric 2 (item 5) replaces it |
| 3 | Owners | **vikram** builds. **nisha** writes and signs the Hinglish and Devanagari corpus rows. **kavya** QA. **kabir + priya** last call |
| 4 | SPEC §5.2 amendment? | **Yes, in the K-2b commit.** The labelled corpus test becomes the pattern contract |
| 5 | Order | **Vikram: K-2b → U-7 backend → K-3.** K-2b touches the rule classes and the K-2 tests Kavya has just read, and nothing U-7 touches |

### 1. Why before U-2's last call and not only before go-live
- **Flags freeze.** `analyse` writes the flags into the brief's snapshot, and `toResponse` never recomputes them (by that method's own javadoc). A brief analysed on the current patterns keeps a wrong **non-dismissible** flag for its whole life. U-2's own bar item 10 (one real paste, live) would create exactly such a row.
- **U-2 is the card that shows them.** I will not pass a paste card whose two can't-clear flags are known to fire on "Payment after delivery … through Influora Secure Payments" and on "TECNO #ad". The second tells a creator a compliant brand "Breaks ASCI guidelines".
- **It is small.** One normaliser, two pattern constants, one parametrised test and a SPEC row. No schema, no API, no frontend.
- **It blocks only U-2.** U-1, U-3, U-5 and U-6 are unaffected. The Round 3 §1 deploy check (delete every pre-deploy `creator_briefs` row) now also covers rows analysed before K-2b.

### 2. What to build (K-2b), with the constraints I verified on JDK 21.0.9

**Item 1: `RiskText.norm`, in this order:**
1. Normalise to **NFC** (`java.text.Normalizer`).
2. Map U+00A0, U+202F, U+2007, and any other `\p{Zs}`, to an ASCII space.
3. Strip U+200B-U+200D, U+2060, U+FEFF and U+00AD.
4. Then the existing apostrophe folds, trim and lower-case.

My probe results:
- `don't\s+#ad` against `don't` + U+00A0 + `#ad` → **false**. Confirms Kabir.
- The nukta form matters. Precomposed `फ़` (U+095E) against the decomposed `फ` + `़` → **false**. NFC on both the text and the pattern literal → **true**. Store every Devanagari pattern literal in NFC.
- `norm` is also used for category equality: `CompetitorConflictRule` L116 and `ExclusivityLongRule` L93/L96. The change is safe there because both sides pass through `norm`, but those rules' tests must stay green.

**Item 2: `HIDE_DISCLOSURE`:**
- A **leading `\b`** (fixes TECNO, OPPO Reno and Casino).
- **Also a trailing `\b` after each label token.** My probe: `no #adventure hashtags please` fires on the built pattern today (**true**). Kabir's list does not include that case.
- Remove bare `disclos`.
- Add Kabir's explicit label and hide phrasings, including `don't disclose (this|it) as (a )?(paid partnership|ad)`. That form must keep `TRIGGER_TEXT` (`CreatorBriefServiceRealRiskRulesTest.java` L65-67) firing, or the three K-2 path tests go red for the wrong reason.
- Add Hinglish and Devanagari short forms. Nisha checks them against how brand managers actually write.

**Item 3: `OFF_PLATFORM_PAYMENT`:**
- Remove `pay(ment)? after`.
- Add Kabir's route phrasings, in Latin script and Devanagari.
- Keep the method names.
- No bare `cash`.

**The Devanagari constraint, which is the trap in item 3 as written:**
- On JDK 21.0.9, **`\b` next to a Devanagari word never matches under the default flags.** Probe results: leading `\bविज्ञापन` → false; trailing `विज्ञापन\b` → false.
- `OFF_PLATFORM_TEXT` wraps its whole alternation in `\b( … )\b`. A Devanagari alternative such as `प्लेटफ़ॉर्म के बाहर` placed inside that group would **compile, look right in review and never fire**.
- **The fix:**
  - Put Devanagari alternatives in a separate alternation branch or a second pattern.
  - Bound them with `(?<![\p{L}\p{M}])` and `(?![\p{L}\p{M}])`. Probe: → true.
  - Do **not** switch the whole pattern to `UNICODE_CHARACTER_CLASS`. It would fix `\b`, but it also changes `\s`, `\w` and case folding for the English half, which the corpus has not measured.
- Latin-script Hinglish is ASCII and `\b` works there (probe: `\bad\s+mat\b` against `ad mat likhna` → true).

**Item 4: a labelled corpus test per rule.** Parametrised Java, one table per rule, with "should flag" and "should not flag" rows.
- **Rows:**
  - the coordinator's 15 phrases
  - Kabir's 16 probes
  - `TRIGGER_TEXT`
  - mine: `no #adventure hashtags please` (should not flag), one Devanagari row with a precomposed nukta and one decomposed (both should flag), one U+00A0 row and one U+200B row (both should flag)
  - **at least 10 held-out rows from nisha**, written without seeing the patterns
- **Why the held-out rows.** Kabir's candidate scores 1 false flag and 2 misses on a corpus he wrote it against. That is a direction, not a precision figure. The held-out rows are the only honest check on overfitting.
- **Each row has a `source` column.** The test reports hits, false flags and misses per rule and per source, so Kavya and Kabir read numbers, not just a green bar.
- **Must be shown red (kavya), one mutation at a time:**
  1. bare `disclos` put back
  2. `pay(ment)? after` put back
  3. the leading `\b` removed
  4. the trailing label `\b` removed
  5. a `\b` put around a Devanagari alternative
  6. NFC removed from `norm`
  7. the U+00A0 mapping removed
  8. zero-width stripping removed

**Item 5: measure gate metric 2 split by `basis` and `extraction_source`, with the decision fixed now.**
- **Regex-derived** means `basis=BRIEF_TEXT`, or `basis=STATED` on a brief whose `extraction_source=FALLBACK`. `BriefFallbackExtractor` L103-114 sets the hints from its own patterns, so those are regex hits wearing the `STATED` label.
- **The rule.** On the hand-checked 50-brief sample (DECISIONS-0904 L59, SPEC L1829), if regex-derived precision for either flag is **under 90%**, that rule's regex half is **removed**. The flag then fires on the model's hint only.
- **Who.** The gate review owner is unchanged. This adds the split and the pre-committed outcome, so the result cannot be argued about after the numbers arrive.
- **Later, LOW (vikram):** merge the fallback extractor's vocabulary and `RiskText`'s into one list so they cannot drift.

### 3. Why not option (b)
- **It weakens the control in the wrong case.**
  - Text cannot suppress either flag (Kabir Q2). The regex half exists to catch a brand that talks the extractor out of setting the hint.
  - Under (b), exactly that catch becomes dismissible, while a hint-only flag, which is what a benign misread by the model produces, stays non-dismissible. That is backwards.
- **It does not remove the harm.** U-3's "dismiss" is a session hide. The damage from a false ASCI accusation is that it is shown at all. The fix is precision (items 1-4) and, if precision fails, removal (item 5), not a hide button.
- **It reopens closed work** for a problem items 1-4 fix directly:
  - U-3's `nonDismissibleFlagsAreTheSpecifiedThree` (`DealRiskServiceTest.java` L729-736)
  - the frontend's per-flag `dismissible` contract
  - SPEC §5.2's dismissible line (L905)

### 4. SPEC §5.2 amendment (vikram edits in the K-2b commit; priya approves at last call)
- **The two "Fires when" cells** (SPEC L895 and L899). Replace the verbatim regexes with the hint, **or** the rule's text pattern, whose contract is the labelled corpus test class (named by path). State the intent in words:
  - `OFF_PLATFORM_PAYMENT`: payment by a route outside Influora. Timing words ("payment after delivery") are **not** a signal.
  - `HIDE_DISCLOSURE`: a request to omit or hide the ad label. Confidentiality or embargo words ("don't disclose the fee") are **not** a signal.
- **One line under the table:** text is matched after `RiskText.norm`, which does NFC, space mapping, zero-width stripping, apostrophe folding and lower-casing. Devanagari alternatives never use `\b`.
- **An `AMEND-0917` marker,** in the SPEC's existing amendment convention.
- **Javadocs.** Both rule javadocs drop "SPEC.md §5.2, verbatim" (`OffPlatformPaymentRule.java` L34, `HideDisclosureRule.java` L29). They would be false the moment the pattern changes, and stale "verbatim" comments are the class of defect this repo's stale-comment gate exists for.

### 5. K-2b pass bar
1. Items 1-4 built as in section 2. Each item-4 mutation shown red first (kavya), with the red line quoted.
2. The three K-2 path tests stay green: `CreatorBriefServiceRealRiskRulesTest`, with `TRIGGER_TEXT` still firing both flags.
3. `DealRiskServiceTest` stays green, including `everyFlagHonoursTheCopyContract` and `nonDismissibleFlagsAreTheSpecifiedThree`. The `CompetitorConflictRule` and `ExclusivityLongRule` tests stay green (shared `norm`).
4. SPEC §5.2 amended and both javadocs corrected in the same commit.
5. Kabir re-runs his `RegexProbe` bypasses (U+00A0, zero-width, nukta forms) against the **built** patterns, and signs the corpus report.
6. Full Maven counts quoted from surefire's "Tests run / Failures / Errors / Skipped" line, not an exit code through a pipe.

**U-2's last-call bar, item 1, is extended:** R-U2 in the branch, **and K-2 and K-2b closed.**


---

## Round 5 — K-2b: bare payment-method names, and what blind recall means (RX-2)

**From:** Priya (CTO). **Written:** 2026-09-17, 18:20. **These are product rulings;** Kavya is doing code QA in parallel.

**Read:**
- `RiskFlagCorpusTest.java` (L30-60 and L235-260, and the rows `OPP-N-02` L104, `OPP-N-09` L111, `KAB-OP-N-03` L121)
- `OffPlatformPaymentRule.OFF_PLATFORM_TEXT` (L79-87)
- `NISHA-RISK-CORPUS-0917.md`, in full
- SPEC §5.2 as amended (L906)
- `src/pages/creator-wallet.tsx` L1068 and L1131

**Checked by hand against the built pattern:** all four blind true positives for OFF_PLATFORM_PAYMENT (`OPP-F-01`, `-04`, `-07`, `-11`) and both blind false positives (`OPP-N-02`, `-09`) fire on **one token, `upi`**. On the blind rows the route phrases contributed nothing.

### Summary

| # | Question | Ruling |
|---|---|---|
| 1 | Bare `upi` / wallet names | **(a), with two constraints. Nisha's labels stand.** A payment method's name alone is not a signal. It fires only alongside a request to **move money to the creator** (send / pay / transfer / "bhej" / "भेज" and similar). **No exclusion list** keyed on "Influora", "payout", "wallet" or "withdraw". Not (b), not (c) now |
| 2a | Does 14% / 29% blind recall justify keeping the regex half? | **Yes.** Its job is a precise tripwire that text cannot suppress, ORed onto the model's hint. Recall is the hint's job |
| 2b | Recall floor or precision floor in the test? | **Both, but recall as a ratchet, not a percentage.** Zero false flags on every labelled NO_FLAG row, and an exact set of currently caught rows that must stay caught |
| 2c | More pattern work before U-2? | **No,** beyond Ruling 1. Recall is measured where it actually lives: an offline run of the real extractor over Nisha's 56 rows, **before go-live** (not before U-2) |

### Ruling 1: bare payment-method names

**Why (a).** On Influora, a payment method's name is Influora's own payout vocabulary:
- The creator wallet says "Add a UPI ID or bank account to withdraw funds" (`creator-wallet.tsx` L1068), with a "UPI ID" field (L1131).
- So "add your UPI ID in your Influora payout settings" is a correct, on-platform instruction.
- A bare `upi` match is therefore a **structural** false-positive source in this product, not corpus noise. It will fire on every brand message about payouts, on a flag the creator cannot clear.

Nisha's NO_FLAG labels on `OPP-N-02` and `OPP-N-09` are right. So is Kabir's own row `KAB-OP-N-03`.

**The signal that does hold on Influora.** A brand never pays a creator directly; the platform does. A brand asking to **send or pay money to the creator's own instrument** is therefore the off-platform signal. All four blind true positives say exactly that:
- "pay you directly via UPI"
- "UPI ID bhej do, hum side mein hi payment kar denge"
- "UPI नंबर भेज दीजिए, हम सीधे पेमेंट कर देंगे"
- "upi pe direct bhej denge"

**Constraint A: positive context only, never an exclusion list.** Firing must not depend on the **absence** of words like "Influora", "payout", "wallet" or "withdraw".
- An exclusion lets brand text switch the regex half off ("send it to my UPI instead of the Influora payout").
- "Text cannot suppress a flag" is the one property Round 4 kept (Kabir Q2), and it was my reason for rejecting option (b).

**Constraint B: the route phrases stay as they are.** "pay you directly", "outside/off the platform", "direct payment/transfer", "platform ke bahar" and the Devanagari form do not need a method name.

**Why not (b).** A non-dismissible "Payment offered outside the platform" on a brand message that says "through Influora" is the alert fatigue metric 2 exists to prevent, on a flag she cannot clear. It also writes an `OFF_PLATFORM_HINT` audit row against a brand that did nothing wrong (SPEC L906). And since the cause is structural, it would fail the <10% false-positive bar on live data too.

**Why not (c) now.** Round 4 fixed the removal decision to the **live** 50-brief sample (item 5), so the result could not be argued about after the numbers arrive.
- Dropping the regex half on a synthetic corpus, with **6** flagged blind rows, would move my own goalpost on the weakest possible evidence.
- The false-positive mechanism has a principled fix.
- Removing the half would also throw away the literal UPI catch, which is the most common real off-platform route in India.
- **(c) stays live.** Item 5 applies unchanged.

**Acceptance for Ruling 1 (vikram builds, kavya checks each point red then green):**
1. `OPP-N-02`, `OPP-N-09` and `KAB-OP-N-03` do not flag. `offPlatformPaymentFalsePositivesAreExactlyTheDisputedSet` becomes **zero false positives across all OFF_PLATFORM_PAYMENT NO_FLAG rows**, the same shape as `hideDisclosureHasNoFalsePositives`.
2. **No caught row is lost.** Every OFF_PLATFORM_PAYMENT row the built pattern catches today is still caught; at minimum blind `OPP-F-01`, `-04`, `-07`, `-11`.
3. **The suppression test.** Take each caught row, append " — or through Influora payouts if you prefer" and prepend "Influora wallet note: ". It must still flag. Must go red if an exclusion list is added.
4. **Each wallet name alone does not fire.** A row with the bare word (`upi`, `gpay`, `phonepe`, `paytm`, `google pay`, `neft`, `imps`, `rtgs`, `bank transfer`) and no sending request stays silent. Must go red if a bare name is restored.
5. **SPEC §5.2, the OFF_PLATFORM_PAYMENT "Fires when" cell (L906), is amended in the same commit.** Today it says the text "names a payment method". It must say: a payment method's name counts only together with a request to send or pay money to the creator; route phrases count on their own; timing words and payout-configuration wording are not signals. Correct the rule's javadoc to match.

**Honesty note, recorded so nobody quotes it later.** After this change, **Nisha's OFF_PLATFORM_PAYMENT rows are no longer blind.** This ruling was made by reading them, and the fix is shaped by what they contain. Blind-row numbers for this rule after Round 5 are regression checks, not precision evidence. The first real precision figure is the live 50-brief sample (item 5). HIDE_DISCLOSURE's blind figures are unaffected, because Ruling 1 does not touch that pattern.

### Ruling 2: recall

**2a. Keep the regex half of both rules.**
- **What the flag's recall is.** Both rules fire on `hint OR text`. The creator sees the union. The corpus measures the text half alone, with no model in the loop, so its 14% and 29% are not the flag's recall and were never meant to be.
- **What the text half is for.** A precise, literal tripwire that brand text cannot talk out of firing. It catches the case where the extraction was steered or the brand wrote the ask plainly. It also protects the FALLBACK path's creators to a degree, although `BriefFallbackExtractor` carries its own patterns.
- **Why that is worth keeping.** HIDE_DISCLOSURE's text half is at precision 1.0 with zero false flags on blind rows, so it costs the creator nothing. OFF_PLATFORM_PAYMENT's will be at zero false flags after Ruling 1.
- **Why the low recall is expected.** Nisha wrote paraphrase and euphemism on purpose ("keep this transaction between us", "off the record as far as the sponsorship goes"). No lexicon should catch those, and chasing them with patterns is how precision gets lost again.

**2b. The test pins precision as a hard floor and recall as a ratchet.**
- **Precision:** zero false flags on every labelled NO_FLAG row, blind and non-blind, for both rules. This is already true for HIDE_DISCLOSURE and becomes true for OFF_PLATFORM_PAYMENT under Ruling 1.
- **Recall:** a named set per rule, e.g. `hideDisclosureStillCatches` and `offPlatformPaymentStillCatches`, listing the exact row ids caught today. The test fails if **any** of them stops flagging.
  - **Not a percentage.** A 14% floor would pass after losing a row whenever another was gained, and it invites edits that trade a real catch for a new one.
  - **Adding a catch** means adding its id to the set in the same change.
  - **Removing an id** from the set needs a written reason in the commit and Kabir's sign-off, because it gives up a literal catch.
- **Keep `printFullReport`** as the reported numbers alongside the two hard gates.

**2c. No more pattern work before U-2, beyond Ruling 1.** What is required instead, **before go-live to real creators (not before U-2's last call)**:
- **Why the live sample can't do this job.** Gate metric 2's sample is "the first 50 briefs **with ≥ 1 flag**" (SPEC L1842), so a miss can never appear in it. As designed, nothing in B0 would ever measure whether a Hinglish "#ad mat daalna" gets flagged at all.
- **The offline recall run (owner vikram; kavya checks reproducibility; kabir reads it as the evidence for "the hint is the real control"):**
  - Run Nisha's 56 rows through the **real** brief-extraction route (`influora-ai` brief extract, the production prompt and model), outside any creator account. Rows must not be persisted.
  - Record each row's two hints, then apply the built rules to get the flag each row would get.
  - Report precision and recall **per rule, per language (en / Hinglish / Hindi), for the hint alone and for hint-or-text.**
  - Cost is 56 short extraction calls. It is billed to the cost log, not to a creator's allowance.
- **The bar, fixed now:** for each rule, hint-or-text **recall ≥ 80%** on the SHOULD-FLAG rows, and **precision ≥ 90%** on the NO_FLAG rows.
  - **If a rule misses,** the fix goes to the extraction prompt or the hint definitions, not to regex. It is re-run on a **fresh** blind set from Nisha (≥ 10 rows per rule), because these 56 will have been seen.
  - **If a rule still misses,** the creator-facing copy must not claim Meera catches hidden-ad or off-platform asks in Hindi or Hinglish. That goes to Swapnil as a go-live decision.
- **Consistent with Round 4.** Kabir's own Q1 says the model's hint is the real control, so this is the first measurement of that control.

### Pass bar for this round
1. Ruling 1 acceptance points 1-5, each shown red against its wrong version first (kavya), then green, with red lines quoted.
2. The two ratchet sets from 2b, each shown red by deleting one caught row's matching phrase from the pattern.
3. `hideDisclosureHasNoFalsePositives` and the new OFF_PLATFORM_PAYMENT zero-false-positive test stay green. The three K-2 path tests and `TRIGGER_TEXT` still fire both flags. `DealRiskServiceTest` stays green.
4. SPEC §5.2's OFF_PLATFORM_PAYMENT row and the rule's javadoc amended in the same commit.
5. Full Maven counts quoted from surefire's "Tests run / Failures / Errors / Skipped" line.
6. **U-2's last-call bar, item 1,** now reads: R-U2 in the branch, and K-2, K-2b and **Round 5 Ruling 1 plus 2b** closed.
7. The offline recall run from 2c is a **go-live** condition, tracked with G-1 and the U-7 erasure control, not a U-2 condition.


---

## Round 6 — F-0769: pairing across a sentence end, and the FALLBACK path (K-2c)

**From:** Priya (CTO). **Written:** 2026-09-18. **Product rulings.** Read-only, no Maven, nothing in the tree changed.

**Read:**
- `KABIR-KB5-CHECK-0918.md`, in full, and ledger F-0769.
- In full:
  - `OffPlatformPaymentRule.java` (`4f5e9905…08ff`)
  - `HideDisclosureRule.java` (`72c27e65…a53c`)
  - `RiskText.java` (`d6c0e01f…9e31`)
  - `RiskFlagCorpusTest.java` (`d89910b8…0001`)
  - `nisha-blind-0917.tsv` (`fbe0a922…da14`)
- `BriefFallbackExtractor.java` (`afc6dbb9…`) L100-116, L145, L160-182 and L222-235.
- `CreatorBriefService.analyse` L433-475.
- `DealRiskService.evaluate` L432-445 and `recordOffPlatformHintIfPresent` L485-500.
- SPEC §5.2 L886-944.

**Measured, not assumed.** Kabir left the boundary fix unmeasured, so I measured it.
- **The probe.** `scratchpad/f0769/F0769Probe.java` is a single-file program run on the build JDK (21.0.9) with no Maven and no class files. It holds verbatim copies of `RiskText.norm` and `OffPlatformPaymentRule.matches`.
- **The copy is faithful.** The `WALLET_NAME` and `SEND_REQUEST` literals diff clean against the source. The copy reproduces the built corpus result: 0 false flags, all 9 ratchet rows caught, all 6 of Kabir's short briefs flagged.
- **What the candidates change.** Every candidate only narrows pairing to one sentence. So it can only remove flags the built rule raises, never add one. The probe checks this on every row, and every variant printed an empty list.
- **72 rows:**
  - Nisha's 28 OFF_PLATFORM_PAYMENT rows
  - the 11 non-blind corpus rows
  - Kabir's 6 short briefs (XS-1..XS-6, in his table's order) and his 3 recall texts
  - my own rows: 13 real asks with a dot inside them (`Rs.`, `a/c no.`, a decimal, a URL, an e-mail address), 3 real asks split across two sentences, 6 line-break shapes and 2 residuals
- **My rows are not precision evidence.** They were written knowing the rule, so they are regression checks only. The live 50-brief sample (round 4 item 5) is still the first real precision figure.

### Summary

| # | Question | Ruling |
|---|---|---|
| 1 | Boundary rule | **Pair only inside one sentence, still within 6 tokens.** A sentence ends at a run of `.` `!` `?` `…` `।` `॥` followed by whitespace or end of text, at a blank line, or at a line break that starts a list item. A single `.` is **not** an end before a digit or currency symbol, or after a word on a closed abbreviation list. A line break on its own is not an end. Route phrases are unchanged |
| 2 | U-2's last call or go-live? | **U-2's last call**, for the round 4 §1 reasons |
| 3a | Pin the window exactly? | **Yes**, 6 in and 7 out, in a dedicated test with neutral filler, not as labelled corpus rows |
| 3b | Kabir's two long-winded asks | Corpus rows as known misses (report only), plus a non-blind supplement to the offline recall run. **Not** in Nisha's fresh blind set |
| 3c | Untested alternatives | One natural row per alternative, written by nisha. Prune any alternative she cannot write naturally. Prune the Hinglish-branch `#ad` now: it is redundant (measured) |
| 3d | Report every missing row in one run | **Yes**, for every test in the class that asserts inside a loop |
| 4 | Owner and order | **vikram**, after F-0770/F-0771, in one commit (**K-2c**) that also carries F-0772. **nisha** writes her rows now, in parallel. **kavya** QA. **kabir + priya** last call |
| new | FALLBACK path | **F-0772, to be ledgered.** FALLBACK briefs set both hints from the extractor's own old patterns, which bypass rounds 4 and 5 and this fix. It goes in the same commit and blocks U-2 the same way |

### Ruling 1: the boundary

| Variant | Kabir's 6 short briefs flagged (must be 0) | Real asks with a dot inside, kept (of 13) | Asks split across 2 sentences, kept (of 3) | NO_FLAG line-break shapes flagged (of 4) | Hard-wrapped real asks kept (of 2) |
|---|---|---|---|---|---|
| Built | 6 | 13 | 3 | 4 | 2 |
| Cut at terminators and at every line break, no protection | 0 | **6** | 0 | 0 | **0** |
| Cut at terminators, no protection | 0 | **6** | 0 | 3 | 2 |
| Cut at terminators, protected | 0 | 13 | 0 | 3 | 2 |
| Protected, plus blank lines | 0 | 13 | 0 | 2 | 2 |
| **Protected, plus blank lines and list items (ruled)** | **0** | **13** | 0 | **0** | **2** |
| Protected, plus every line break | 0 | 13 | 0 | 0 | **0** |

**The same in every variant:**
- Nisha's blind rows do not change: 4 caught, 0 false.
- No ratchet row is lost.
- No wallet name fires on its own.
- 0 of 36 suppression wraps un-flag a caught row. That is 4 wraps × 9 ratchet rows:
  - the existing prefix and suffix
  - a wrap packed with terminators, "Rs. 5,000", "No. 12" and "approx. 3 days"
  - blank-line paragraphs
  - a Devanagari sentence on each side

**The rule, for vikram:**
1. Normalise with `RiskText.norm`, as today. `ROUTE_PHRASES` still runs on the whole text, unchanged (Constraint B).
2. Cut the normalised text into sentences at any of these:
   - a run of `.` `!` `?` `…` (U+2026) `।` (U+0964) `॥` (U+0965) followed by whitespace or end of text
   - a blank line (two line breaks with only spaces or tabs between them), or U+2029
   - a line break followed by optional spaces, then a list marker, then a space. The markers are `-` `*` `•` `·` `▪` `➤`, or 1-2 digits followed by `.` or `)`
3. **Except for a single `.`.** A run that is exactly one `.` does **not** cut when either of these holds:
   - the next character on the same line (after spaces) is a digit (`\p{Nd}`, which includes Devanagari digits) or a currency symbol (`\p{Sc}`, which includes ₹). This covers "Rs. 5,000", "No. 12", "approx. 3 days" and "रु. 5000".
   - the word before the dot, with opening brackets and quotes stripped, is one of: `rs re inr amt approx appx no nos a/c acc acct e.g i.e vs mr mrs ms dr pvt रु`
4. Pair a wallet name with a request word only inside one sentence, within 6 tokens, exactly as `matches` does today, run once per sentence.

**How the abbreviation list may grow.** A word belongs on the list only if it almost never ends a sentence and commonly sits inside a payment ask.
- `etc`, `ltd` and `co` stay off because they often end a sentence. With `etc` off, "Send the draft, captions etc. UPI payout via Influora as usual." is correctly cut (measured).
- Adding a word needs a row showing a real ask lost without it.

**Why this keeps Constraint A:**
- **Firing still depends only on positive context.** The list can only stop a cut, and stopping a cut can only keep a pairing. So no word on it can ever make a flag disappear. The rule as a whole only removes flags the built rule raises (measured on all 72 rows).
- **Text outside an ask cannot switch it off.** A cut separates the two words only if it falls between them, and appended or prepended text never lands there (0 of 36 wraps).
- **What a brand can still do** is write its own ask as two sentences: "Share your UPI. We'll send it tonight."
  - That is rewriting the ask. It is the same class of evasion as putting a seventh word in between, which the window already concedes.
  - The regex half is a precise tripwire, not a control against a motivated brand (round 5 §2a; Kabir's Q1). Recall belongs to the hint.

**Why a line break on its own does not end a sentence.** PDF and plain-text email pastes break lines mid-sentence.
- "…we can send the fee straight to your⏎UPI, faster that way…" is a real ask. Cutting at every line break lost both hard-wrapped asks I tried.
- Cutting only at list items and blank lines fixes all 4 bullet and paragraph false flags at no cost to those asks.

**The price, recorded now so nobody is surprised later.** All 3 real asks split across two sentences stop flagging: the `?`, `.` and `।` forms. Kabir's two long-winded asks were already missed before this change, so they are not part of the price.

**Residual, left on purpose.** With no space after the full stop ("…kal tak.UPI se payout…"), the text still flags.
- Cutting at a dot followed by a letter would also split URLs, e-mail addresses and "e.g.", and it needs its own measurement.
- It goes to the live sample's `basis` split, like the two residuals by design.

### Ruling 2: it blocks U-2's last call

Every reason from round 4 §1 applies:
- **The flag freezes.** It is written into the brief snapshot at `saveAnalysis` (`CreatorBriefService` L465-475) and never recomputed.
- **It is non-dismissible, and it accuses the brand.** Every evaluation writes an `OFF_PLATFORM_HINT` audit row against the brand's workspace (`DealRiskService.evaluate` L443 → L485-500).
- **It fires on the most ordinary brief wording there is**, such as "send the draft" and "Draft bhej do". U-2's own live paste (bar item 10) would create exactly such a frozen row.
- **The fix is small:** one method, a set of rows and one SPEC line.

The round 3 §1 deploy check (delete every `creator_briefs` row from before the deploy) now also covers rows analysed before K-2c.

### Ruling 3: Kabir's four LOW notes

**(a) Pin the window exactly, in a dedicated test, not as labelled rows.**
- **Why not rows.** Corpus rows carry ground-truth labels and feed the precision and recall report. "7 tokens apart, must not flag" is a pin on the contract, not a truth label. As a row, it would put a real-looking ask into the NO_FLAG set.
- **The test, `pairingWindowIsExactlySix`.** Neutral filler words, one sentence, checked in both directions (wallet then request, request then wallet):
  - at distance 6 the text flags
  - at distance 7 it does not
  - "google pay" counts as one position
  - measured on the built rule: 6 true, 7 false, in both directions and for the two-word name
- **The distances are the literals 6 and 7.** Writing them as `PAIRING_WINDOW` and `PAIRING_WINDOW + 1` would pass whatever the constant says.
- **Falsify:** set the window to 5, then to 7. Each goes red.
- **SPEC:** the cell reads "WITHIN 6 TOKENS OF … IN THE SAME SENTENCE".

**(b) Kabir's two long-winded asks:**
- **In the corpus** as `kabir` SHOULD_FLAG rows that are known misses. `printFullReport` reports them; they are not in the ratchet, the same as `KAB-OP-F-02`.
- **In the offline recall run.** Add them, and my three split-sentence asks, to the round 5 §2c run as a non-blind supplement reported separately. They are exactly what the hint has to catch when the text half cannot.
- **Not in Nisha's fresh blind set.** A blind set is written without seeing patterns, probes or known misses. Seeding it would make it neither blind nor representative, and Nisha is not told about this weakness when she writes it.

**(c) Untested alternatives: one natural caught row per alternative, or prune it.**
- **The alternatives:** Hinglish `sponsored`, `nahi`, `na`, `laga` and `mention`; Devanagari `sponsored` and `नहीं`.
- **nisha** writes one row for each, the way a brand manager actually writes. The rows are tagged `nisha_guard` (non-blind), and vikram adds them.
- **If she cannot write a natural sentence for an alternative, it goes.** An alternative nobody writes is recall on paper and a precision risk in practice.
- **The Hinglish-branch `#ad`.** Kabir is right that it adds nothing, but it can match. Measured (`HashAdProbe.java`):
  - on its own it matches only right after a word character ("caption#ad mat daalna")
  - on every text I tried, the branch gives the same answer with or without it, because `ad` matches the same text one character later
  - so it is **redundant**. Prune it, with a one-line comment. No row can guard an alternative that never changes the result.
- **The Devanagari branch keeps its `#ad`.** Its lookaround makes it the only thing that catches "#ad मत डालना" (`KAB5-HD-F-devanagari-02`).
- **Falsify:** delete each guarded alternative on its own. Each goes red on its row.

**(d) Yes: every loop-assert test collects its misses and asserts once.**
- **The tests:** both ratchets, the suppression test, `bareWalletNameAloneStaysSilent`, and the new pin and boundary tests.
- **The shape:** the same `List<String>` plus `isEmpty()` the false-positive tests already use. No new dependency.
- **Falsify:** remove two catches at once. A single red run names both ids.

### Ruling 4: owner and order
- **vikram builds, in this order:**
  1. **Finish F-0770 and F-0771 first.** They are in flight in `influora-ai` and gate D-1 through K-3.
  2. **Then K-2c, as one commit in `influora-api`:** F-0769, F-0772 and LOW (a), (c) and (d). All of these touch `RiskFlagCorpusTest`, and one reviewed change to that file beats three.
- **Why this order.** K-3's fixes are smaller and already open, and the two pieces of work touch different modules. Both sit on D-1's path: K-3 directly, and U-2 through the close of Wave U. I review K-3 when it comes back, while K-2c is being built.
- **nisha, now, in parallel:** the (c) guard rows, plus a naturalness sign-off on the Hinglish and Devanagari rows below (the round 4 owner rule).
- **kavya:** QA. Each mutation shown red first, with the red line quoted.
- **kabir + priya:** last call. Kabir re-runs his KB5 probe against the built rule.

### New: F-0772 — the FALLBACK path ignores rounds 4 and 5 and this round

I found this while reading the path the flag takes.

**What happens on FALLBACK:**
- When the AI extraction is skipped (the monthly cap is reached, or the AI is unavailable), `CreatorBriefService.analyse` falls back to `BriefFallbackExtractor` (L446-448).
- The extractor sets `off_platform_payment_hint` from its own pattern, which fires on a bare `upi` (L103-104, L176).
- It sets `disclosure_hidden_hint` from its own pattern, which predates round 4 (L111-116).
- Both rules OR the hint into the text check, so both flags fire, labelled `STATED`.

**Measured** (`FallbackProbe.java`; both literals diff clean against the source):
- The FALLBACK payment hint is set on `OPP-N-02`, `OPP-N-09`, `KAB-OP-N-03`, both `KAB5-N` rows, XS-1, XS-3 and a bare "upi".
- The FALLBACK hide hint is set on "Caption: Loving my new TECNO #ad", which is the defect round 4 fixed in `HIDE_TEXT`, and on "no disclosure issues expected".
- The FALLBACK summary line then tells the creator the brand "Mentions paying you outside a platform" (L230-231).

**Consequences:**
- On FALLBACK, round 5 Ruling 1 and any F-0769 fix do nothing.
- `BriefFallbackExtractorTest` L108 even pins "Bank transfer within 7 days" as a positive, which contradicts Ruling 1.

**Ruling: on FALLBACK the extractor sets both hints to false and drops those two summary lines.**
- **Nothing the ruled patterns catch is lost.** The rules' text checks already run on the same raw text (`evaluateExtraction` passes it as `RiskContext.text`). So a FALLBACK brief's flags become exactly what the ruled patterns say, labelled honestly `basis=BRIEF_TEXT`.
- **Why not have the hint call the rule's own check.** That gives the same flags, but keeps a second copy of the vocabulary in the extractor and labels a regex hit `STATED`. This ruling also settles round 4's LOW "merge the two vocabularies" for these two signals, by deleting the copy.
- **Recall given up:** only vocabulary the ruled patterns leave out on purpose, such as bare wallet names, "keep it organic" and "avoid disclosure".

**Acceptance:**
- `BriefFallbackExtractorTest` L106-122 is rewritten to assert both hints are false, citing this round.
- A FALLBACK end-to-end test runs through the real rules, in the `CreatorBriefServiceRealRiskRulesTest` shape:
  - `OPP-N-02`'s text raises no `OFF_PLATFORM_PAYMENT`
  - "Caption: Loving my new TECNO #ad" raises no `HIDE_DISCLOSURE`
  - `OPP-F-01` raises `OFF_PLATFORM_PAYMENT` with `basis=BRIEF_TEXT`
  - `KAB-HD-F-01` raises `HIDE_DISCLOSURE`
  - `TRIGGER_TEXT` raises both
- **Falsify:** restore the extractor's own OFF_PLATFORM pattern. The `OPP-N-02` case goes red.
- **Ledger:** a new record at the next free id (F-0772), opened by priya. Not F-0769: it is a different path with a different fix.

### K-2c pass bar
1. **Build.** Ruling 1 built as specified. The rule's javadoc states:
   - the terminator set
   - the protections
   - how the abbreviation list may grow
   - the price (asks split across sentences)
   - the no-space residual
2. **Rows.** All tagged `round6` unless stated; the texts are in `F0769Probe.java` `main`. nisha signs the Hinglish and Devanagari ones.
   - Kabir's XS-1..XS-6 as `kabir` NO_FLAG rows. All six, not two: together they cover English, Hinglish, Devanagari and the danda.
   - The 13 asks with a dot inside them, as SHOULD_FLAG rows **added to the ratchet**. All 13 are caught today, and they are what makes a naive cut go red.
   - The 4 NO_FLAG line-break shapes, and the 2 hard-wrapped asks as SHOULD_FLAG rows in the ratchet.
   - The 3 split-sentence asks and Kabir's two long-winded asks as SHOULD_FLAG rows, report-only.
3. **Mutations,** each shown red first by kavya:
   - the sentence cut removed → the XS rows (zero-false-positive test)
   - the digit and currency protection removed → `AB-rs`, `AB-inr`, `AB-rupee-sym` (ratchet)
   - the abbreviation list emptied → `AB-ac-no`, `AB-amt`, `AB-eg`
   - a single line break made a cut → the two hard-wrapped asks
   - the list-item cut removed → `NL-bullets`
   - `।` removed from the terminator set → XS-5
   - the window set to 5, then 7 → the pin test
   - an Influora or payout exclusion added → the suppression test
   - the FALLBACK OFF_PLATFORM pattern restored → the F-0772 test
4. **Suppression wraps.** The suppression test gains the probe's three extra wraps:
   - terminators and abbreviations around the row
   - blank-line paragraphs
   - a Devanagari sentence on each side
5. **SPEC.** §5.2's OFF_PLATFORM_PAYMENT cell reads "WITHIN 6 TOKENS OF … IN THE SAME SENTENCE", with an `AMEND-0918` marker that defines a sentence in words. The FALLBACK change gets one line under the table.
6. **Regression.** These stay green:
   - the three K-2 path tests
   - `DealRiskServiceTest`, including `nonDismissibleFlagsAreTheSpecifiedThree`
   - the `CompetitorConflictRule` and `ExclusivityLongRule` tests

   Counts are quoted from surefire's "Tests run / Failures / Errors / Skipped" line.
7. **U-2's last-call bar, item 1,** now reads: R-U2 in the branch, and K-2, K-2b, round 5 Ruling 1 plus 2b, and **K-2c (F-0769, F-0772)** closed.


---

## Round 7 — K-2c follow-up: the last dead alternative, F-0777, Nisha's rows, the pipe, and compliance lines that flag (R7-A)

**From:** Priya (CTO). **Written:** 2026-09-18, 17:15. **Product rulings.** Read-only on the tree: no Maven, nothing in `influora-api/` or `src/` edited, no stash.

**Numbering correction:** round 6 called the FALLBACK record "F-0772". In the ledger, F-0772 is meera's exemption-list record, and the FALLBACK record is **F-0773**, as Kabir noted. Read every "F-0772" in round 6 as F-0773.

**Read:**
- `KABIR-K2C-CHECK-0918.md`, in full.
- Ledger F-0769, F-0773, F-0776 and F-0777.
- `NISHA-HIDE-WORD-ROWS-0918.md`.
- Source, by sha256:
  - `HideDisclosureRule.java` (`e9c38087…`)
  - `OffPlatformPaymentRule.java` (`6b7c7655…`)
  - `RiskText.java` (`d6c0e01f…`)
  - `BriefFallbackExtractor.java` L150-240 (`a7050a53…`)
  - `RiskFlagCorpusTest.java` L80-400 and L555-625 (`64d3690d…`)
- Nisha's 28 blind HIDE_DISCLOSURE rows.
- Frontend:
  - `deal-risk-card.tsx` L120-150
  - `degradedLabelFor` (`CreatorToolResultRenderer.tsx` L466-487)
- `CreatorBriefService.analyse` L433-457.
- `brief_extract_monthly_cap_usd` in `influora-ai/app/config.py` L561-563.

**Measured on the built classes, not on a copy of the regex:**
- **Probes.** `PriyaR7Probe`, `…Probe2` to `…Probe5` in `scratchpad/pk3r3/r7/`.
  - Compiled with JDK 21.0.9 against Kabir's snapshot of the built classes (`scratchpad/k2c/probe-classes`, taken at his green baseline).
  - The five source files hash equal to his baseline.
  - The built `HIDE_TEXT` string compares equal to the source literal.
- **Corpus.** The corpus and both ratchets are read by reflection from the built `RiskFlagCorpusTest`: 138 rows (69 HD, 69 OP), with ratchets of 22 (HD) and 24 (OP).
- **OFF_PLATFORM variants** run through a copy of the pairing code. The copy agrees with the built `matches()` on all 159 texts tried.
- **Every row I wrote is non-blind.** My rows are existence proofs and regression rows, not precision evidence.
- **Vikram's F-0776 work was in flight while I measured.**
  - `RiskFlagCorpusTest.java` changed at 16:51 (the VIK-GUARD2 rows).
  - `HideDisclosureRule.java` changed at 17:04, and again before 17:07. At 17:04 `ad\s*tag` was missing, presumably a falsification run. That line had no FALSIFY marker.
  - Nothing below is measured on those states.

### Summary

| # | Question | Ruling |
|---|---|---|
| 1 | B3's `ad` (only "as ad" / "as a ad") | **Widen** `(?:a\s+)?` to `(?:an?\s+)?`. Guard row: "Don't disclose this as an ad." 0 corpus false flags, 0 ratchet rows lost |
| 2 | F-0777 | **(c).** No recall regex before U-2. Five candidate words are rejected on measured false flags. Three spelling variants are accepted for a go-live **K-2d**. Before go-live, the FALLBACK notice also covers the risk check, and the offline recall run gains a FALLBACK column. **Blocks go-live, not U-2** |
| 3 | Nisha's 7 natural rows fire on nothing | **Accept the mechanical rows** as deletion guards. Nisha's 7 FLAG rows stay as report-only known misses; her 7 NO_FLAG rows stay in the zero-false-positive test. No rewrite and no widening before U-2. Nisha answers yes or no: does a brand send each terse form? A "no" prunes that alternative |
| 4 | Pipe `\|` typed as a danda | **Fix now**, in the same commit. Measured: it clears the three on-platform pipe lines and keeps both real asks. 0 corpus false flags, 0 ratchet rows lost |
| new | **R7-A: HIDE_DISCLOSURE fires on ASCI-compliance lines** | 13 of 15 lines in which the brand tells the creator to **keep or place** the label raise the non-dismissible "Breaks ASCI guidelines" flag. **Blocks U-2.** Fixed in the same commit: a measured candidate clears 12 of the 13 with 0 corpus false flags and 0 ratchet rows lost |
| new | OFF_PLATFORM fires on Influora payout statements | All 7 lines that describe Influora paying the creator's UPI flag. This is the residual of round 5's Constraint A. **Go-live, not U-2** |

### Ruling 1: B3's `ad` — widen to `an?`

| Text | Built | `(?:an?\s+)?` | `ad` pruned |
|---|---|---|---|
| "Don't disclose this as an ad." | – | **FLAG** | – |
| "Please don't disclose it as an ad on your page." | – | **FLAG** | – |
| "don't disclose this as ad" | FLAG | FLAG | – |
| "Don't disclose it as a paid partnership." | FLAG | FLAG | FLAG |

Corpus, for both variants: false flags `[]`, ratchet rows lost `[]`.

**Why widen, not prune:**
- F-0776 forces an edit here either way. A dead alternative can be made live or removed; it cannot stay.
- The words before it, "don't disclose this/it as", already say it is a hide ask. An article cannot add a false flag, and none was measured.
- Pruning would leave the natural English form uncaught on the one path where the text is the only control (FALLBACK, Ruling 2).

This is the only widening in this round.

**Residual, recorded:** "Don't disclose this as an ad-hoc payment" flags, because `\b` falls between `ad` and `-`. The sentence is contrived. It does not justify an extra lookahead that would itself need a guard row.

**Row and falsify:**
- SHOULD_FLAG "Don't disclose this as an ad.", added to the ratchet.
- Put `(?:a\s+)?` back: that row goes red.
- Delete `ad`: that row goes red.

### New, R7-A (HIGH): compliance lines raise HIDE_DISCLOSURE — blocks U-2

**What fires.** Built pattern, my lines (probe 1). Each is a brand telling the creator to **keep**, add or place the label.

| id | Text | Built | Fires through |
|---|---|---|---|
| C1 | "Please do not post without the paid partnership label." | **FLAG** | `without` |
| C2 | "No post should go live without #ad." | **FLAG** | `without` |
| C3 | "Posts without the #ad tag will not be approved." | **FLAG** | `without` |
| C4 | "Never publish without the paid partnership tag, ASCI is strict about this." | **FLAG** | `without` |
| C5 | "Content with no #ad label will be rejected." | **FLAG** | `no` |
| C6 | "Don't put #ad at the end of the caption, it has to be in the first line." | **FLAG** | `don't put` |
| C7 | "Don't add #ad only in the comments, it must be in the caption." | **FLAG** | `don't add` |
| C8 | "Don't use #ad in the comments; use the paid partnership label in the caption." | **FLAG** | `don't use` |
| C9 | "Do not use the ad tag in place of the paid partnership label." | **FLAG** | `do not use` |
| C10 | "Ad nahi likha toh post approve nahi hoga." | **FLAG** | Hinglish `nahi` |
| C11 | "#ad नहीं लगाया तो पोस्ट अप्रूव नहीं होगी।" | **FLAG** | Devanagari `नहीं` |
| C12 | "Sponsored nahi likha toh ASCI notice aa sakta hai." | **FLAG** | Hinglish `nahi` |
| — | "No #ad, no approval." | **FLAG** | `no` (residual, below) |
| — | "Please don't skip the #ad tag." / "Don't skip the paid partnership label, ASCI requires it." | – | (fire only if `skip` is added; Ruling 2) |

**Why it blocks U-2.** This is round 4 §1's standard, applied to a worse case than TECNO:
- The flag cannot be dismissed. It tells the creator that a compliant brand "Breaks ASCI guidelines" and that "the post cannot run without" a label the brand just asked her to use.
- It freezes into the brief's snapshot.
- The "do not post without the paid partnership label" line is boilerplate in agency briefs, so it hits the most compliant brands hardest.

**Why the corpus missed it.** None of the 69 HIDE_DISCLOSURE corpus rows has this shape. Nisha's 14 blind NO_FLAG rows state compliance positively ("make sure you add the #ad tag", "don't forget"), so `hideDisclosureHasNoFalsePositives` stayed green.

**The fix (vikram), measured as a whole (probes 3 and 5):**
1. **Prune `without` from B1's negators.**
   - In briefs, its natural use is the compliance form (C1-C4).
   - The hide ask "post it without #ad" differs only by a negation before "post", and a regex cannot scope that negation.
   - Under round 6 §3c, an alternative whose natural use is the opposite ask is a precision risk, not recall.
   - `VIK-GUARD2-HD-F-without` leaves the ratchet and stays as a report-only known miss.
2. **`no` → `(?<!with\s)no`.** Clears C5.
3. **After B1's label group, add `(?!\s+(?:at\s+the\s+end|in\s+the\s+comments?|only|in\s+place)\b)`.** Clears C6-C9. That is four alternatives with one row each; no more.
4. **B4: exclude the conditional on the general negators only: `(?:mat|(?:nahi|na)(?!\s+\w+\s+toh?\b))`.**
   - Clears C10 and C12.
   - "mat" is used only in commands, so "Ad mat likhna toh achha rahega, reach better aayegi." and "#ad mat lagana toh post organic lagegi." must keep flagging, and do.
   - Putting the lookahead on the whole branch instead lost both of those asks (probe 4). That is why it sits on `nahi|na`.
5. **B5, the same shape: `(?:मत|नहीं(?!\s+[\p{L}\p{M}]+\s+तो(?![\p{L}\p{M}])))`.**
   - Clears C11.
   - "#ad मत डालना तो बेहतर है।" still flags.

Each subject and verb alternative still appears exactly once. The measured candidate, for vikram to build from:

```
\b(?:do not|don'?t|(?<!with\s)no)\s+(?:(?:use|add|put)\s+)?(?:the\s+)?(?:#ad|#collab|#sponsored|ad\s*tag|ad\s*label|paid\s+partnership|sponsored\s+tag)\b(?!\s+(?:at\s+the\s+end|in\s+the\s+comments?|only|in\s+place)\b)
|\bdon'?t\s+mention\s+(?:it'?s|this\s+is)\s+sponsored\b
|\bdon'?t\s+disclose\s+(?:this|it)\s+as\s+(?:an?\s+)?(?:paid\s+partnership|ad)\b
|\b(?:ad|sponsored|paid\s+partnership)\s+(?:mat|(?:nahi|na)(?!\s+\w+\s+toh?\b))\s+(?:likh|daal|laga|dikha|mention)\w*\b
|(?<![\p{L}\p{M}])(?:#ad|विज्ञापन|sponsored)\s+(?:मत|नहीं(?!\s+[\p{L}\p{M}]+\s+तो(?![\p{L}\p{M}])))(?![\p{L}\p{M}])
```

(Line breaks are for reading. The pattern is one alternation with the built flags, `CASE_INSENSITIVE`.)

**What the candidate does:**
- **Compliance rows:** 0 of C1-C12 flag.
- **Hide asks, all still caught:**
  - Kabir's 7 F-0776 guard texts and "Don't disclose this as an ad."
  - "ad mat likhna", "#ad mat lagana", "ad nahi likhna", "ad na likhna", "Caption mein #ad nahi likhna hai."
  - "#ad मत डालना", "#ad नहीं डालना।", "विज्ञापन मत लिखना"
- **Corpus:** NO_FLAG rows flagged `[]`; HD ratchet rows lost `[]`; 22 of 22 caught SHOULD_FLAG rows still caught.

**Falsify map (probe 3).** Take each part back out on its own; these rows flag again:

| Part taken out | Rows that flag again |
|---|---|
| `without` restored | C1, C2, C3, C4 |
| `(?<!with\s)` | C5 |
| `at\s+the\s+end` | C6 |
| `only` | C7 |
| `in\s+the\s+comments?` | C8 |
| `in\s+place` | C9 |
| B4's lookahead | C10, C12 |
| B5's lookahead | C11 |

**Constraint A still holds.**
- Each lookaround reads only the words right beside that one occurrence, so text elsewhere in the brief cannot switch off a separate ask. This is the same argument as round 6's sentence cut.
- What a brand can do is write its own ask as a placement ("don't put #ad at the end or anywhere"). That is rewriting the ask, the class round 6 conceded.

**Residuals, recorded in the javadoc and SPEC:**
- "No #ad, no approval." still flags.
- The text half no longer catches "Post it without the #ad tag."
- "Go with no #ad this time" escapes.
- A hide ask phrased as a placement escapes.
- "…as an ad-hoc payment" flags.

**Nisha's blind compliance rows (required in this commit).**
- At least 12 rows: 4 English, 4 Hinglish, 4 Hindi.
- Each is a brand telling the creator to keep, add or place the label, the way agencies write it.
- Written without seeing `HIDE_TEXT`, this round or my rows.
- Tagged `nisha_blind_r7` and placed in the zero-false-positive test. **0 may flag.**
- If one flags: vikram proposes a local fix, measured on the whole corpus, and Kabir and I rule on it in writing. No relabelling.

**SPEC and javadoc.**
- §5.2's HIDE_DISCLOSURE cell gains: "Instructions to keep, add or place the label ('do not post without the paid partnership label', 'don't put #ad at the end') are NOT a signal." Add an `AMEND-0918-R7` marker.
- The rule's javadoc lists the pruned `without`, the four lookarounds and the residuals above.

**Ledger (next free id, opened by priya):** `flag-fires-on-unrelated-words` in `HideDisclosureRule.java`.
- **Symptom:** compliance lines C1-C12 raise the non-dismissible flag.
- **missed_by:** every HIDE_DISCLOSURE NO_FLAG row states compliance positively; none tells the creator not to post without the label.

### Ruling 2: F-0777 — (c), and it blocks go-live, not U-2

**Why not U-2.** F-0777 is a miss on the FALLBACK path, not a false accusation, and the card claims nothing when there is no flag:
- `DealRiskCard` renders nothing for zero flags. Its own javadoc (L126-128) refuses an empty "no risks found" panel.
- The FALLBACK notice already says the reading is rule-based and to check it against the brief.

What is missing is a sentence that covers the risk check. That is copy, and it is needed before real creators, not before the code pass.

**FALLBACK is not rare.** The brief cap is a small per-creator monthly budget (`BRIEF_EXTRACT_MONTHLY_CAP_USD`, default 0.25). An active creator reaches FALLBACK within the month, and every creator does during an AI outage.

**Candidates measured (probe 1).** 0 ratchet rows are lost in every row below.

| Candidate | New catches | New false flags | Ruling |
|---|---|---|---|
| `skip` as a B1 negator | "Please skip the #ad tag on this one."; blind HD-F-03 | "Please don't skip the #ad tag."; "Don't skip the paid partnership label, ASCI requires it." | **Rejected**: it flags the compliance form |
| `#\s?ad` in B1's labels | "Don't use # ad on this one." | none (0 corpus, compliance rows unchanged) | **K-2d** (go-live), built on R7-A's B1 |
| `via` as a request word | "Can we do this via GPay instead?…" | corpus **NL-bullets-num**; "Payment via UPI through Influora within 7 days."; "Share the draft by Friday. UPI payout via Influora as usual." | **Rejected** |
| `payment` as a request word | "Kindly share your UPI ID for payment." | corpus **XS-4** | **Rejected** |
| `share` as a request word | "Kindly share your UPI ID for payment." | "Please share your UPI ID on Influora so the payout reaches you." Only an "Influora" exclusion would clear it, and Constraint A forbids one | **Rejected** |
| `g[\s-]?pay` | "…on G Pay…", "…on G-Pay…" | none | **K-2d** |
| `phone\s?pe` | "Share your Phone Pe number…" | "Draft phone pe bhej do kal tak."; "Script phone pe bhej dena, call pe baat karte hain." ("phone pe" means "on the phone") | **Rejected** |
| `u\.p\.i\.?` | "…to your U.P.I tonight." | none | **K-2d** |

**"Kindly share your UPI ID for payment." stays a text-half miss, by design.** Every word that would catch it also flags a compliant line.
- **On the AI path,** catching it is the hint's job. It goes into the offline recall run's supplement (item 2 below).
- **On FALLBACK,** the notice says the check can miss it (item 1 below).

**Required before go-live, not before U-2:**
1. **Copy.** nisha writes the words, ananya builds.
   - All three sentences in `degradedLabelFor` (cap, outage, bare FALLBACK) also say that the risk check on this brief was rule-based and can miss an ask written in other words.
   - The cap and outage sentences stay different from each other.
   - The test asserts the new sentence in all three cases.
2. **The offline recall run (round 5 §2c) gains a FALLBACK column.**
   - It reports the text half alone, per rule and per language, on the same rows.
   - Non-blind supplement: Kabir's F-0777 asks, plus Nisha's 7 natural `nisha_guard` FLAG rows.
   - The FALLBACK column has no numeric bar. If it falls below the AI path's 80%, creator-facing copy may not claim Meera catches these asks once her monthly allowance is spent. That goes to Swapnil, together with round 5's decision.
3. **K-2d (vikram).** `#\s?ad`, `g[\s-]?pay` and `u\.p\.i\.?`, each with a ratchet row, and each deletion shown red.

**Closing F-0777.** It closes when items 1 and 2 exist. The text half's recall on FALLBACK is then an accepted, documented property, not a defect for regex to chase.

### Ruling 3: Nisha's natural rows — accept the mechanical guards

**What the rows show** (probe 1, built pattern):
- All 7 of her FLAG rows come out `got=false`.
- All 7 of her NO_FLAG rows come out `got=false`.
- All 7 `VIK-GUARD-HD-F-*` rows come out `got=true`.

**What her rows prove.** The short-form branches need the subject, negator and verb next to each other, and natural sentences put words between them ("'sponsored' word bilkul mat daalna", "#ad wala hashtag mat laga"). That is a recall limit of the branch's shape, not of any one word. Pruning single words would not change it.

**The ruling:**
- **Accept the mechanical rows** as the deletion guards F-0776's done_when asks for.
- **Nisha's 7 FLAG rows** stay as SHOULD_FLAG report-only known misses. They are the honest recall figure for these branches: 0 of 7. They also join the offline run's supplement (Ruling 2).
- **Her 7 NO_FLAG rows** stay in the zero-false-positive test, where they already pass.
- **No tighter rewrite.** A sentence written to fit the pattern is vikram's mechanical row with a longer tail, and it adds no evidence.
- **No widening before U-2.** Letting words sit between subject and negator is recall work, and the conditional compliance form (C10-C12) is exactly what a looser window would catch. It goes to **K-2d**, measured against R7-A's rows and Nisha's blind compliance rows.
- **Nisha answers yes or no, in this commit.** For each terse form the ratchet holds ("sponsored mat likhna", "ad nahi likhna", "ad na likhna", "#ad mat laga", "paid partnership mat mention karna", "sponsored मत लिखना", "#ad नहीं डालना"), does a brand manager send it, perhaps with more words around it? A "no" prunes that alternative and its guard row in the same commit. This is round 6 §3c's test, applied to the form the pattern actually matches.

### Ruling 4: the pipe — fix now

Measured (probe 1), adding `|` to the terminator set:

| Text | Built | With `\|` |
|---|---|---|
| "ड्राफ्ट भेज दीजिए \| भुगतान UPI से Influora पर होगा" | FLAG | – |
| "Draft bhej do kal tak \| UPI se payout Influora pe aayega" | FLAG | – |
| "Send the draft by Monday \| UPI payouts go through Influora as usual" | FLAG | – |
| "Send your UPI \| we pay today" (real ask) | FLAG | FLAG |
| "We'll send ₹5000 to your UPI \| no app needed" (real ask) | FLAG | FLAG |

Corpus: 0 false flags, 0 ratchet rows lost.

**How to build it.** Change `SENTENCE_TERMINATOR` to `[.!?…।॥|]+(?=\s|$)`, so `||` also works as a double danda.
- It can only add cuts, so it can only remove flags (round 6's argument).
- A pipe-separated rate list also gets cut, which is the right reading.

A comma still does not cut. So "Rates: reel 5k | story 2k || send the invoice, UPI payout via Influora" still flags inside its last piece. That is the existing 6-token window, not the pipe.

**Rows:**
- the first two lines above: NO_FLAG, tagged `round7`
- "We'll send ₹5000 to your UPI | no app needed": SHOULD_FLAG, added to the ratchet

**Falsify:** remove `|` and both NO_FLAG rows go red. It sits on the same lines as Kabir's six terminator rows, so it goes in the same commit.

### New: OFF_PLATFORM fires on Influora payout statements — go-live, not U-2

Measured (probe 2, built rule). **7 of 7 flag:**
- "You'll be paid to your UPI ID through Influora within 7 days of approval."
- "Influora will transfer the fee to your UPI after the post goes live."
- "Payments are sent to your UPI through Influora Secure Payments."
- "Your fee is paid out via Influora to the UPI or bank account in your payout settings."
- "Aapka payment Influora se UPI pe bhej diya jayega."
- "भुगतान Influora के ज़रिए आपके UPI पर भेजा जाएगा।"
- "Once approved, the amount is transferred to your UPI by Influora."

**Why this does not block U-2, when R7-A does:**
- Round 5 chose Constraint A (no "Influora" exclusion) knowing that on-platform payout wording is the structural risk.
- Round 4 item 5 already fixes what happens: if the live sample shows regex-derived precision under 90%, the text half is removed.
- The flag runs in shadow mode (logged, never blocks), although it is shown and writes the brand audit row.
- The fixes available are an exclusion that Constraint A forbids, or a passive-voice rule. A passive-voice rule would also drop real passive asks ("Payment will be sent directly to your UPI, no need for the app"). That needs its own measurement and ruling, not a last-minute edit.
- R7-A has a local, measured fix with no such trade.

**Required before go-live (vikram).** Measure a passive-voice exclusion:
- a passive auxiliary before the request word (`be|is|are|was|were|been|get|gets|got`);
- Hinglish `diya jayega` / `kiya jayega`;
- Devanagari `भेजा जाएगा`.

Measure it against the corpus, these 7 lines and Nisha's fresh blind set, and bring me the numbers. Until I rule, round 4 item 5 applies unchanged, and these lines are exactly what it will count.

**Ledger:** a new record (next free id, opened by priya), class `flag-fires-on-unrelated-words`, in `OffPlatformPaymentRule.java`.

### K-2c.2 pass bar: one commit

vikram builds; kavya checks red-first; kabir and priya take the last call.
1. **F-0776.**
   - Each remaining alternative is guarded, with its deletion shown red alone: `put`, `#collab`, `ad\s*tag`, `sponsored\s+tag`, B2 `this\s+is`, B3 `it` (vikram's VIK-GUARD2 rows), and B3 `ad` (Ruling 1's row). `without` is pruned under R7-A.
   - Kabir's six definition rows, each shown red: `?`, `!`, `…`, `॥`, U+2029, and the currency-symbol protection (his clause 2 fix).
2. **R7-A.**
   - Pattern parts 1-5.
   - C1-C12 added as `round7` NO_FLAG rows.
   - The falsify map above, each part shown red on its own.
   - Nisha's 12 or more blind compliance rows, with 0 flagged.
   - Residuals written in the javadoc.
3. **Ruling 3.** Nisha's yes or no on each terse form. Any "no" is pruned together with its row.
4. **Ruling 4.** The `|` terminator and its three rows, falsified.
5. **SPEC and comments.**
   - §5.2's HIDE_DISCLOSURE cell gets the R7-A sentence, and the terminator line gains `|`. Marker: `AMEND-0918-R7`.
   - Both javadocs updated.
   - The stale header comment in `.proof-os/gates/F-0765-F-0766-risk-corpus.sh` fixed (Kabir's LOW).
6. **Regression.** Round 6 item 6's list stays green, plus both proof-os risk gates. Counts quoted from surefire's "Tests run / Failures / Errors / Skipped" line.
7. **Kabir** re-runs his K-2c probe and the R7-A rows against the **built** pattern, and signs.
8. **Every falsification carries a FALSIFY marker**, and the tree shows none when the commit is cut (arjun's pre-commit checklist).
9. **U-2's last-call bar, item 1,** now reads: R-U2 in the branch, and K-2, K-2b, round 5 Ruling 1 plus 2b, K-2c (F-0769, F-0773), and **K-2c.2 (F-0776, R7-A, the pipe)** closed.

**Parallel work, starting now:**
- **nisha:** the blind compliance rows and the yes/no on the terse forms.
- **ananya:** U-2's four missing guard tests (`PRIYA-LASTCALL-U2-0918.md`).

Neither waits for vikram.
