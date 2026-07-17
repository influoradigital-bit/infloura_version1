# DPF-3 mark-posted — Kabir Red-Team Audit

**Target:** `POST /creator/deliverables/{deliverableId}/mark-posted`
**Files:** `CreatorDeliverableController.java` (L104-111), `CreatorDeliverableService.java` (`markPosted` L320-349, `validatePlatformPostUrl` L411-434), `Deliverable.java` (`applyMarkPosted` L266-272), `CreatorDeliverableDtos.java` (`MarkPostedRequest` L94).
**Auditor:** Kabir (offensive / red-team)

## VERDICT: PASS — route to Kavya. No Critical/High. 1 Medium + 1 Low follow-up.

The core defensive claims hold. IDOR, state-machine, and SSRF are all closed. Two hardening gaps to track, neither ship-blocking.

---

## What I tried to break, and why it held

### 1. SSRF via `livePostUrl` — CLOSED
- **No server-side fetch sink.** `grep` confirms `postUrl` is never passed to `RestTemplate`/`WebClient`/`HttpClient`/`URL.openConnection`. `markPosted` only validates + stores the string; the only later readers are `getPostUrl()` in the metrics DTO and the response DTO — both echo it, neither fetches it. So there is no request-forgery primitive today. The whitelist is defense-in-depth only.
- **Whitelist anchoring is correct.** Java `String.matches()` implicitly anchors the whole string, and the patterns additionally carry explicit `^…$`. All the classic bypasses fail:
  - `http://…`, `file:///etc/passwd`, `javascript:alert(1)`, `data:` → rejected by `!url.startsWith("https://")` (L412).
  - `https://instagram.com.attacker.com/` → after `instagram\.com` the pattern demands `/`, sees `.` → no match.
  - `https://instagram.com@attacker.com/` → demands `/`, sees `@` → no match.
  - `https://attacker.com/instagram.com/p/xxx` → must begin `https://(www\.)?instagram\.com/` → no match.
  - `https://instagram.com/redirect?url=http://internal` → path must be `/(p|reel)/[A-Za-z0-9_-]+/?$` → `/redirect?…` → no match.
  - `169.254.169.254`, `localhost` → host is not the literal whitelisted domain → no match.
  - CRLF/`\n` injection into the URL → `.` in the YouTube pattern does not match line terminators (no DOTALL) → rejected.

### 2. IDOR — CLOSED
`requireOwnedDeliverable` (L385-394) resolves via `deliverableRepository.findByIdAndCreatorUserId(deliverableId, principal.getUserId())`. Creator A cannot mark creator B's deliverable — a foreign id yields `DELIVERABLE_NOT_FOUND` (404). Creator id is taken from the authenticated principal, never from a path/body param.

### 3. State bypass — CLOSED
`markPosted` L326 requires `status == APPROVED`. Consequences:
- Cannot mark POSTED while SUBMITTED/DRAFT/PENDING → brand approval cannot be skipped.
- Cannot mark POSTED twice — after the first call status is `POSTED`, so the second call fails the `== APPROVED` check (`INVALID_STATE`, 409).
- Cannot re-enter from any terminal/other state (VERIFIED, METRICS_REPORTED, REJECTED) for the same reason.

---

## Findings to track (non-blocking)

### M-DPF3-1 (MEDIUM) — YouTube regex `(&.*)?$` admits arbitrary chars → latent stored-injection in `post_url`
`validatePlatformPostUrl` L425:
```java
url.matches("^https://(www\\.)?youtube\\.com/watch\\?v=[A-Za-z0-9_-]+(&.*)?$")
```
The trailing `(&.*)?` accepts **any** non-newline characters after a `&`. This passes validation and is stored verbatim in `Deliverable.post_url`:
```
https://www.youtube.com/watch?v=abc&x="><script>alert(1)</script>
https://www.youtube.com/watch?v=abc&x=<img src=x onerror=...>
```
- **Not exploitable today:** `grep` for `postUrl`/`post_url` across `src/` (frontend) returns nothing — the value is not yet rendered in any brand UI, and React would auto-escape a text node. So this is a defense-in-depth gap, not a live stored-XSS. That is the only reason it is Medium and not High.
- **Risk if surfaced:** the moment the brand deal-room renders `postUrl` inside an `href`/attribute or via `dangerouslySetInnerHTML`, the `"` breakout becomes a real stored XSS. The Instagram pattern (`[A-Za-z0-9_-]+/?$`) is tight and does NOT have this problem — only the YouTube branch does.
- **Fix:** constrain the query tail, e.g. `[?&][A-Za-z0-9_=&%-]*$`, or parse with `java.net.URI`, assert host ∈ {youtube.com, www.youtube.com, youtu.be}, and extract only the `v` param. Do not accept `.*`.

### L-DPF3-2 (LOW) — no length guard before the 500-char DB column
`post_url` is `length = 500` (Deliverable L80). `validatePlatformPostUrl` imposes no max length; combined with M-DPF3-1's `&.*`, a >500-char URL passes validation then throws a `DataIntegrityViolationException` at persist → uncontrolled 500 instead of a clean 400. Add an explicit length check (e.g. reject > 500) in the validator.

---

## Routing
- **PASS to Kavya** for QA. No loop-back to Vikram required for DPF-3.
- Track **M-DPF3-1** (tighten YouTube regex) and **L-DPF3-2** (length guard) as follow-ups — fold into the same fix if DPF-8 loops back anyway.

---

# DPF-3b — Hardening Re-Audit (Kabir Red-Team)

**Target of re-audit:** `validatePlatformPostUrl` (`CreatorDeliverableService.java` L421-453) + `MAX_POST_URL_LENGTH` (L410).
**Vikram's changes verified:** YouTube tail `(&.*)?$` → `(&[A-Za-z0-9_=&-]*)?$`; length guard `url.length() > 500` → 400 before persist; 13 markPosted tests.

## VERDICT: ✅ PASS — M-DPF3-1 + L-DPF3-2 CLOSED. Route to Kavya. No bypass found.

I did not trust the diff or the test summary — I extracted the exact regex + guard and executed them under Java 21 against 24 adversarial inputs (harness output archived). All passed.

### 1. Stored-XSS (M-DPF3-1) — CLOSED
- New tail charset `[A-Za-z0-9_=&-]` (hyphen is trailing → literal, not a range). It genuinely excludes every HTML-breaking char. Executed and **REJECTED**: `&x=<script>alert(1)</script>`, `&x="onmouseover="`, `&x='></a>`, `` &x=`alert` ``, `&x=</script>`, `&x=a>b`. No `<`, `>`, `"`, `'`, backtick, or `/` can reach `post_url` via the YouTube branch.
- **Java `String.matches()` `$`/trailing-newline gotcha — checked, not a hole.** `matches()` requires the whole string; `markPosted` also `.trim()`s first (L342). Result: `v=abc\n<script>`, mid-string `&t=1\n<script>`, and `\r\n<script>` all **REJECTED** (a non-final newline never satisfies `$`, and `.` is not DOTALL). A lone trailing `\n` is stripped by trim to a clean valid URL — no payload survives.
- **Instagram branch — confirmed no attacker-controlled chars.** IG pattern accepts **no** query/fragment tail: `?igsh=<script>` and `#<script>` both **REJECTED**. Shortcode charset `[A-Za-z0-9_-]+` is HTML-safe. Vikram's "IG has no query tail" claim holds.
- `youtu.be` branch: no tail, clean charset — nothing to smuggle.

### 2. Length guard (L-DPF3-2) — CLOSED, and it is load-bearing
- Ordering is correct: HTTPS check (L422) → **length check (L429)** → platform regex (L437+) → `applyMarkPosted`/save. Over-length fails as a clean 400 **before** any persist.
- Proved the guard actually matters: a **regex-VALID** 501-char YouTube URL (`matches()==true`, confirmed) is caught by the length check → `INVALID_POST_URL` 400, never reaching the `VARCHAR(500)` column. Boundary correct — 500 chars accepted exactly (500 is not `> 500`, fits the column), 501 rejected. No truncation-to-DB / DataIntegrityViolation 500.
- `post_url` has a **single writer** (`Deliverable.applyMarkPosted` ← `markPosted`, post-validation); no other code path sets it. Metrics path only *reads* the already-validated value. No unvalidated back-door.
- No ReDoS: single non-nested optional group `(&[…]*)?`, linear; input capped at 500 anyway.

### Non-blocking notes (do NOT loop back — hand to Kavya/Vikram as polish)
- **Test-quality gap (not a defect):** `testMarkPostedRejectsOverLengthUrl` uses a regex-*invalid* Instagram URL (`/p/x?…`), so it passes even if the length guard were deleted — it does not isolate the guard. The proper regression is a regex-*valid* >500-char YouTube URL (as in my harness). Add one.
- **XSS test covers only `&x=<script>`** — the `"` and `'` breakout payloads aren't asserted. Charset covers them (proven here), so it's coverage, not a hole.
- **Minor over-restriction (functional, not security):** dropping `%` and `.` from the tail rejects some legit URLs — `&ab_channel=My%20Name`, `&feature=youtu.be`. Acceptable trade for a tighter charset; note for product if creators report rejected YouTube links.

**Harness:** exact regex/guard replicated and run under `openjdk 21.0.11`; 24/24 expectations met (6 XSS reject, 5 newline/CRLF, 2 IG smuggle, 6 legit accept, 2 over-restrict, 2 length-boundary, 1 trim).

---

# DPF-3b (loop 2) — `%` + `.` Re-Added: Encoded-XSS Re-Confirm (Kabir Red-Team)

**Change verified:** YouTube tail charset `[A-Za-z0-9_=&-]` → `[A-Za-z0-9_=&%.-]` (re-added `%` and `.` to fix Kavya's P1 over-restriction of `&ab_channel=My%20Name` / `&feature=youtu.be`). Javadoc hard-constraint added: `postUrl` must never be URL-decoded server-side, and any FE render must be as-is (never `decodeURIComponent`). New test documents `&x=%3Cscript%3E` accepted as opaque.

## VERDICT: ✅ PASS — no encoded-XSS path exists today → route to Kavya. + one P2 defense-in-depth recommendation (NOT a gate-blocker) and one MANDATORY DPF-6 carry-forward.

### 1. Raw XSS still closed (Q1) — CONFIRMED
The two added chars are `%` and `.`. Neither is a raw HTML-breakout char. Inside the char class `[A-Za-z0-9_=&%.-]`, `.` is a **literal** (not wildcard) and `-` is trailing (literal, not range). The pattern is still `^…$`-anchored and Java `matches()` is full-string. `<`, `>`, `"`, `'`, backtick, `/` remain **impossible** to reach `post_url` via the YouTube branch. Newline/CRLF/trim behavior is unchanged from loop 1 (still rejected). Raw breakout: **closed.**

### 2. Encoded-XSS path (Q2) — the real question — NO LIVE PATH
`%3Cscript%3E` (encoded `<script>`) is now storable in `post_url`. Verified it is **safe today** on all three legs:
- **No server-side decode — grep-confirmed independently.** Every file using `URLDecoder`/`decodeURIComponent`/`unescape` (`MetaOAuthService`, `ShopifyOAuthService`, `TotpService`, `CampaignLinkService`, `AuthRateLimitFilter`) has **zero** `postUrl` references. `postUrl`'s only consumers: `markPosted` (validate→store), `reportMetrics` L300 (reads, hands to `metric.applyReport` as an opaque link field — no decode/render), and the echo response DTOs. Single writer, no decode sink. Vikram's claim holds.
- **No FE render today.** Repo-wide grep finds `postUrl` only in backend Java + tests + wiki + one stale `dist/` bundle. No `.tsx/.jsx` source consumes it. Same posture as loop 1.
- **Encoded ≠ executable at rest.** `%3Cscript%3E` in a text node renders literally (React escapes AND does not auto-decode `%`). In an `<a href>` it stays a valid encoded query param — the browser navigates, does not inject DOM. The **only** way it becomes live XSS is `decodeURIComponent(postUrl)` **then** `dangerouslySetInnerHTML` — two stacked anti-patterns.

**→ No encoded-XSS path is exploitable today. The DPF-3b close is not blocked.**

### 3. Is "accept `%3Cscript%3E` as opaque" the right call? (Q3) — my opinion: acceptable to ship, but add a targeted defensive reject
This is the one place I'll push. Two honest observations:
- **The constraint lives in the wrong repo for the party who can trip it.** The defense is documentation only — javadoc (L425-428), inline comment (L452-455), and a Java test comment (L886-887). All three are backend-side. The consumer that could violate it (DPF-6 platform-verification + any FE that renders `postUrl`) is built by frontend/Ananya in a **different repo** where a React dev will never see a Java javadoc. Documentation across a repo boundary is a fragile control precisely when the consumer is imminent — and Priya has made DPF-3b the explicit release-gate *for DPF-6*.
- **A targeted reject costs zero legit URLs and does NOT reintroduce Kavya's UX bug.** Kavya's failure was rejecting `%` *broadly* (killed `%20` in `ab_channel=My%20Name`). But no real YouTube share URL ever contains `%3C %3E %22 %27 %60`. Rejecting only those specific dangerous encodings (case-insensitive) — e.g. a post-regex guard `if (url.matches("(?i).*%(3c|3e|22|27|60).*")) reject;` — keeps `%20/%2F/%3D` flowing and removes the latent payload entirely. This is strictly better with no UX downside, and it is *consistent with why we tightened the raw charset in loop 1 at all* (that raw case wasn't exploitable either — we hardened it on defense-in-depth grounds; the encoded equivalent deserves the same).

I am **not** blocking the gate on this: severity is **P2 / defense-in-depth**, because there is no live path and the fix can land alongside DPF-6. But I recommend folding the targeted encoded-bracket reject into the DPF-6 change, and I am attaching a hard carry-forward:

### CARRY-FORWARD (MANDATORY for DPF-6 / any `postUrl`-rendering FE) — do NOT lose
Output-side is the real defense, not the validator. Whoever renders `postUrl`:
- MUST treat it as untrusted: render as an escaped text node or a plain `href` string. **Never** `decodeURIComponent(postUrl)` before render. **Never** `dangerouslySetInnerHTML`.
- The backend javadoc constraint (L425-428) MUST be mirrored as a comment at the FE render site — the control cannot live only in the backend repo.
- Kabir must re-audit the DPF-6 render path specifically (it is where this latent payload would detonate).

### Routing
- **✅ PASS DPF-3b (loop 2) → Kavya.** Encoded-XSS not exploitable; raw XSS closed; length guard intact; single writer; no decode sink.
- **P2 (Vikram, fold into DPF-6):** add targeted post-regex reject of `%3C/%3E/%22/%27/%60` (case-insensitive). Zero UX cost — does not re-break `%20`.
- **MANDATORY (DPF-6):** output-side escaping + no-decode at the render site; Kabir re-audit that path.
