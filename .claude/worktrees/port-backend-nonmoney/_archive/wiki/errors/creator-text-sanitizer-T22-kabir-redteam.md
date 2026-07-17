# TextSanitizer Hardening — Task #22 (Kabir Red-Team Re-Review)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** `TextSanitizer.java`, all Task #22 ingress wiring, adversarial bypass probes, service-level XSS regressions, frontend egress cross-check (`creator-chat.tsx`, `brand-chat.tsx`, `message-card.tsx`, `deliverable-card.tsx`)  
**Closes:** Task #7 **M-2** (`Collaboration.notes`), Task #9 **M-9-1** (`DealMessage.content`), Task #20/T21 **M-2 extended** (deliverable submit text + brand `feedback`)  
**Reference:** `wiki/errors/creator-campaign-apply-T7-kabir-redteam.md`, `wiki/errors/creator-deal-controller-T9-kabir-redteam.md`, `wiki/errors/creator-deliverable-submit-T20-kabir-redteam.md`, `wiki/errors/creator-deliverable-review-T21-kabir-redteam.md`, `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.2

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

**M-2 + M-9-1: CLOSED** for sprint gate. **Production deploy of deal-room message paths and brand-review text paths is UNBLOCKED.**

Vikram's `TextSanitizer` is correctly wired on every ingress point named in Task #22. Adversarial probes (case variants, nested tags, named entity decode-then-strip, event handlers, split-rejoin, style blocks, unclosed tags, iframe/object) do not yield stored executable HTML in the scoped write paths. Scoped tests **15/15 PASS** (11 `TextSanitizerTest` + 4 service XSS regressions). Meera-reported scoped gate **59/59** not re-run here; targeted subset verified locally.

**Finding count:** 0 Critical · 0 High · 0 Medium · **4 Low** (non-blocking)

No Critical or High findings. Residual LOW items are defense-in-depth gaps outside Task #22 acceptance criteria — they do **not** reopen M-2 or M-9-1.

---

## 1. Sanitizer Bypass Probes

### 1a. Implementation

```23:31:influora-api/src/main/java/com/influora/common/TextSanitizer.java
    public static String sanitizePlainText(String input) {
        if (input == null) {
            return null;
        }
        String cleaned = decodeBasicEntities(input);
        cleaned = SCRIPT_OR_STYLE_BLOCK.matcher(cleaned).replaceAll("");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll("");
        return cleaned.strip();
    }
```

Decode → strip `script`/`style` blocks (case-insensitive `(?is)`) → strip all `<[^>]*>` → trim.

### 1b. Adversarial probe matrix (Kabir live probes)

| Probe | Payload class | Output | Executable XSS? |
|---|---|---|---|
| Case variant | `<ScRiPt>alert(1)</ScRiPt>safe` | `safe` | **No** |
| Named entities | `&lt;script&gt;alert(1)&lt;/script&gt;safe` | `safe` | **No** |
| Double encode | `&amp;lt;script&amp;gt;…` | `&lt;script&gt;…safe` (literal entities) | **No** |
| Numeric entity | `&#60;script&#62;…` | stored verbatim, no literal `<` | **No** (React text) |
| Hex entity | `&#x3c;script&#x3e;…` | stored verbatim, no literal `<` | **No** (React text) |
| Event handler | `<img src=x onerror=alert(1)>visible` | `visible` | **No** |
| SVG onload | `<svg onload=alert(1)></svg>ok` | `ok` | **No** |
| Nested script | `<div><script>…</script>text</div>` | `text` | **No** |
| Style block | `<style>body{background:url(javascript:…)}</style>content` | `content` | **No** |
| Split-rejoin | `<scr<script>ipt>alert(1)</script>safe` | `<scrsafe` | **No** (residual `<`, see L-22-2) |
| Split-close | `<scr</script>ipt>alert(1)</script>safe` | `ipt>alert(1)safe` | **No** |
| Unclosed script | `<script>alert(1)safe` | `alert(1)safe` | **No** |
| Script slash | `<script/x>alert(1)</script>safe` | `safe` | **No** |
| iframe/object | `<iframe src=javascript:alert(1)>safe` | `safe` | **No** |
| autofocus | `<input onfocus=alert(1) autofocus>safe` | `safe` | **No** |
| Newline in tag | `<script\n>alert(1)</script>safe` | `safe` | **No** |
| Null byte | `<scr\u0000ipt>alert(1)</script>safe` | `alert(1)safe` | **No** |

**Bypass verdict:** No probe produced a stored value that executes in current SPA egress (`{event.content}`, `{caption}`, `{m.content}` — React text interpolation, no `dangerouslySetInnerHTML` on these fields). Regex-based strip is not a formal HTML parser; two probes leave a literal `<` without a closable tag (L-22-2). Acceptable for M-2/M-9-1 closure given React egress posture.

### 1c. `sanitizeHashtags`

Per-tag `sanitizePlainText` + drop blanks. Verified: `<script>x</script>tag` → `tag`. **PASS.**

---

## 2. Ingress Path Verification

All paths below call `TextSanitizer` **before** persistence (grep + code review + service tests).

| # | Ingress | Call site | Sanitizer | Service XSS test |
|---|---|---|---|---|
| 1 | Campaign apply message | `Collaboration.apply()` L85 | `sanitizePlainText(message)` | `CreatorCampaignServiceTest.testApplyStripsXssMessage` ✅ |
| 2 | Campaign invite message | `Collaboration.invite()` L63 | `sanitizePlainText(message)` | (entity factory — same code path) ✅ |
| 3 | Deal propose message | `Collaboration.propose()` L158 | `sanitizePlainText(message)` | wired via factory ✅ |
| 4 | Deal send message | `DealService.sendMessage()` L278 | `sanitizePlainText(body.content())` | `DealServiceTest.testSendMessageStripsXss` ✅ |
| 5 | Deal counter/proposal msg | `DealService.persistProposalMessage()` L436 | `sanitizePlainText(message)` | code wired, no dedicated test (L-22-3) |
| 6 | Deal system messages | `DealService.appendSystemMessage()` L449 | `sanitizePlainText(content)` | reject reason L198 same path ✅ |
| 7 | Creator submit caption | `CreatorDeliverableService.submitForReview()` L156 | `sanitizePlainText(body.finalCaption())` | `CreatorDeliverableServiceTest.testSubmitStripsXss` ✅ |
| 8 | Creator submit hashtags | same L157 | `sanitizeHashtags(body.hashtags())` | same test ✅ |
| 9 | Creator submit notes | same L158 | `sanitizePlainText(body.notes())` | same test ✅ |
| 10 | Brand revise feedback | `BrandDeliverableService.requestRevision()` L62 | `sanitizePlainText(feedback.trim())` | `BrandDeliverableServiceTest.testReviseStripsXssFeedback` ✅ |

**Egress note:** `seedNotesMessage` and `lastMessage` fallback return already-sanitized `Collaboration.notes` for rows created after Task #22. Pre-existing DB rows are not backfilled (L-22-4).

**TODO #2 verdict: PASS** — all Task #22 ingress paths verified.

---

## 3. Findings

### L-22-1 (LOW) — Upload ingress still raw; submit-null preserves poisoned caption

`CreatorDeliverableService.uploadContent()` persists `caption`, `hashtags`, and `creatorNotes` **without** `TextSanitizer` (L118–120). `Deliverable.applySubmit()` only overwrites fields when non-null — a creator can `POST /upload` with XSS caption then `POST /submit` with `{}` and retain unsanitized text in `SUBMITTED` state.

**Mitigation today:** SPA upload-then-submit always passes `finalCaption`/`notes` from the same form (`creator-chat.tsx` L826–828), so the happy-path re-sanitizes. React text interpolation prevents DOM execution even if poisoned.

**Out of Task #22 scope** (acceptance listed submit text only). Recommend follow-up: sanitize on upload or always overwrite caption on submit. **Does not block** M-2/M-9-1 closure or prod deploy of scoped paths.

### L-22-2 (LOW) — Residual `<` from malformed split payloads

Probes `<scr<script>ipt>…` and `<<script>script>…` leave a literal `<` in stored output (`<scrsafe`, `<safe`). Not executable in React text nodes; incomplete angle-bracket stripping. Defense-in-depth gap only.

### L-22-3 (LOW) — Missing DealService XSS regressions for counter/proposal/reject

`persistProposalMessage` and `appendSystemMessage` are sanitized in code, but only `sendMessage` has a dedicated XSS unit test. Test coverage gap, not a wiring gap.

### L-22-4 (LOW) — No backfill for pre-Task #22 rows

Legacy `collaborations.notes` / `deal_messages.content` / deliverable text written before sanitizer land may still contain HTML. Write-path protection only. Acceptable for sprint gate if no production data yet; consider one-time cleanup before prod if early adopters exist.

---

## 4. Frontend Egress Cross-Check

| Surface | Render | XSS execution risk |
|---|---|---|
| `creator-chat.tsx` message bodies | `{event.content}` | **None** (text node) |
| `brand-chat.tsx` messages | `{m.content}` / `{event.content}` | **None** |
| `message-card.tsx` | `{event.content}` | **None** |
| `deliverable-card.tsx` caption | `{caption}` | **None** |
| `chart.tsx` | `dangerouslySetInnerHTML` | **Unrelated** (chart CSS only) |

Server-side sanitization + React text interpolation = defense in depth. **PASS.**

---

## 5. Test Evidence

| Suite | Result |
|---|---|
| `TextSanitizerTest` | **11/11 PASS** |
| `DealServiceTest#testSendMessageStripsXss` | **PASS** |
| `CreatorCampaignServiceTest#testApplyStripsXssMessage` | **PASS** |
| `CreatorDeliverableServiceTest#testSubmitStripsXss` | **PASS** |
| `BrandDeliverableServiceTest#testReviseStripsXssFeedback` | **PASS** |
| Meera scoped gate (per Vikram ship note) | **59/59 PASS** (not re-run; targeted subset verified 2026-07-09) |

---

## 6. Gate Verdict

| Gate item | Status |
|---|---|
| M-2 `Collaboration.notes` sanitization | **✅ CLOSED** |
| M-9-1 `DealMessage.content` sanitization | **✅ CLOSED** |
| M-2 extended deliverable submit text | **✅ CLOSED** |
| M-2 extended brand `feedback` | **✅ CLOSED** |
| Sprint gate (no Critical/High) | **✅ GO** |
| Deal room prod deploy | **✅ UNBLOCKED** |
| Brand review text paths prod deploy | **✅ UNBLOCKED** |

**Overall: PASS WITH FINDINGS** — 4 LOW carry-forward (L-22-1 through L-22-4). Route to Priya sign-off. Vikram optional follow-up: sanitize upload ingress + add counter/proposal XSS tests.

---

*Kabir Singh · Offensive Security / Red-Team Lead · Sage Digital*
