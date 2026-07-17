# Trend-Spark T12 — AI Correctness + Safety Review (Ash)

**Reviewer:** Ash (AI/ML)  **Date:** 2026-07-13  **Gate:** Final AI gate before Swapnil business sign-off (T13)
**Scope:** `influora-ai/app/routes/trendspark.py`, `app/prompt/trendspark.py`, `app/providers/claude.py` (`complete_text`), `app/config.py`, `tests/eval/test_trendspark_nudge.py`, Java `TrendSparkAiClient.java`; contracts: schema-lock §4/§5, `meera-tone-guide.md`, spec §6.

## VERDICT: ✅ PASS (no P0 blockers)

The AI layer holds up under adversarial reading. The hallucination kill-switch is enforced in **two independent layers** (Python `parse_and_validate` + Java `TrendSparkAiClient`), the model is genuinely Haiku-class, the spend gate is real and short-circuits before any provider call, the prompt-injection defense is structural (not pattern-based), and every failure path degrades to a deterministic templated fallback at HTTP 200. Eval **RE-RUN: 25/25 passed** (provider mocked; no real API called).

4 follow-ups (1×P1, 3×P2) below. None block launch — the P1 fails closed at the system level via Java's non-200→fallback, so the user never sees an error even today.

---

## Eval re-run (my own execution)

```
$ .venv/Scripts/python.exe -m pytest tests/eval/test_trendspark_nudge.py -q
25 passed in 1.57s
```

- Provider is mocked at the same `_get_claude` seam the route uses (`_mock_claude` → `AsyncMock`); **no Anthropic call is made.** Spend-gate test asserts `complete_text.assert_not_called()`.
- Auth is exercised for real: RS256 token minted against a fake JWKS source (401 no-token, 403 wrong-scope, 403 workspace-mismatch, 400 missing workspace).
- BAD cases genuinely exercise guardrails (not trivially passing):
  - pet-name → `_has_forbidden_petname` trip
  - urgency spam → 4 statements > 2-statement cap (real trigger, not just the "BUY" word)
  - invented `₹500 crore … ₹1,000 each` → `_PRICE_RE` trip
  - OWN_CONTENT pushing "Snapsby videos" → `_OWN_CONTENT_FORBIDDEN_RE` trip
  - non-JSON prose → defensive-parse `None`
  - hallucinated `vid_HALLUCINATED` → dropped, AI message kept
  - code-fenced JSON → parsed
- **Assessment:** golden set is meaningful and maps 1:1 to tone-guide §4/§5. Good coverage.

---

## Findings

### P1-1 — `await request.json()` can throw an unhandled 500 (Kabir's L2 confirmed)
**File:** `influora-ai/app/routes/trendspark.py:181`  **Owner:** Ash
`body = await request.json()` runs before auth (line 191) with no try/except. A malformed or empty request body raises `json.JSONDecodeError` (a `ValueError`), which is **not** an `HTTPException`, so FastAPI returns **HTTP 500** — directly contradicting the module's own contract ("NEVER a 500 for a phrasing miss") and the schema-lock §4 fail-closed rule.

**Why it is P1, not P0:** the only caller is Java's `TrendSparkAiClient`, which serializes via Jackson `ObjectMapper` (always well-formed JSON) and treats **any** non-200 as `null → templated fallback` (`TrendSparkAiClient.java:138-146`). So end-to-end the system still fails closed and the brand never sees an error. But the Python endpoint itself emitting a 5xx is a latent contract violation and a trivially-fixable unhandled-exception path in the one AI route.

**Failure scenario:** any client POSTs `{` (or an empty body) → 500 raised before auth even runs.
**Fix:** wrap the body read in `try/except (json.JSONDecodeError, ...)` and raise `HTTPException(400, {"code":"invalid_json"})`. A 4xx (not 5xx) before auth is correct — with an unparseable body there is no verified `workspace_id`, so a genuine authed fallback cannot be produced; a clean 400 keeps Java on its fallback path.

### P2-1 — Price kill-switch is keyword-only; bare-number invented facts slip through
**File:** `influora-ai/app/routes/trendspark.py:70`  **Owner:** Ash
`_PRICE_RE` matches `₹ | rs | inr | rupee(s)`. An invented number **without** a currency token (e.g. "made 500 crore on day one", "over 10 lakh views") is not rejected. Impact is low: Java never renders an AI-supplied price (price always comes from the persisted catalog row, per schema-lock §4 and `TrendSparkAiClient` javadoc), and the 300-char / 2-statement caps limit rambling. **Mitigation, not a block.** Consider a numeric-magnitude heuristic (`\b\d[\d,]*\s*(crore|lakh|k|cr)\b`) in a later hardening pass.

### P2-2 — `TRENDSPARK_MODEL` env override has no Haiku-class guard
**File:** `influora-ai/app/config.py:55`  **Owner:** Ash / Rohan
Model is correctly pinned to `claude-haiku-4-5-20251001`, but it is `os.getenv`-overridable with only a **comment** ("never Opus/Sonnet in prod") preventing a misconfig from pointing at an expensive model. The spend gate caps daily $ but does not cap per-call model choice. Recommend a boot-time allowlist assertion (Haiku-class ids only) so a bad env var refuses boot rather than quietly paying Opus prices.

### P2-3 — 2-sentence cap counts only `./!` runs
**File:** `influora-ai/app/routes/trendspark.py:77`  **Owner:** Ash
`_STATEMENT_RE = [.!]+` miscounts on abbreviations ("Mr.", "3.5") — inflating the count — and undercounts run-ons joined by `;`, `,`, or newlines. Both directions are **safe**: over-count → fallback (still valid), under-count → still bounded by the 300-char cap. Acceptable for v1; note as a known fuzziness in the tone cap.

### P2-4 — Fallback interpolates raw enum / untrusted strings
**File:** `influora-ai/app/prompt/trendspark.py:121-143`  **Owner:** Ash / Nisha
The templated fallback renders `campaign_type` raw ("there's a **HYPE** trend around …") — grammatically off-brand vs. the AI path — and interpolates `brand_name` / `trend_text` verbatim. Risk is minimal (self-scoped to the brand's own workspace; strings are length-capped; `trend_text` is server-derived from the `trends` table), but the fallback bypasses the tone validators by design. Consider lower-casing/prettifying `campaign_type` in the template for the brand-facing copy.

---

## What I verified holds (positives)

- **Hallucination kill-switch (double-enforced):** Python `parse_and_validate` keeps only `video_ids ∈ sent_ids` (empty/None/non-list model field → `[]`, not bypassable); OWN_CONTENT → always `[]`. Java `TrendSparkAiClient:165-178` independently re-drops any id not in the sent set. Defense in depth as schema-lock §4 mandates.
- **Model/cost:** `claude-haiku-4-5-20251001` (Haiku-class), never Opus/Sonnet by default. Spend gate (`check_spend_gate`) checks kill-switch then global daily ceiling **before** the provider call; on trip → fallback 200, zero tokens spent (test-confirmed).
- **Fail-safe on model failure:** malformed JSON, non-dict, missing/empty message, over-length, >2 statements, pet-name, echoed price, mode violation, and provider exception/`ok=False` all route to `_fallback_response()` at HTTP 200. `complete_text` never raises (returns `ok=False`).
- **Fallback both modes:** SNAPSBY mentions videos (allowed); OWN_CONTENT never surfaces the marketplace (no snapsby/video/buy) — enforced on the AI path by `_OWN_CONTENT_FORBIDDEN_RE` and structurally absent from the OWN_CONTENT template.
- **Prompt-injection defense is structural, not pattern-based:** `brand_name`/`trend_text` go through `wrap_untrusted` = `neutralize_angle_brackets` (every `<`/`>` → entity) + delimiters. Not bypassable via case variation or split-rejoin (the exact class of bypass called out in the brand-safety red-team fix).
- **PII-free logging:** `log_event` + `shape_of` log model + `message_source` + shapes only; never the raw message or brand strings (schema-lock §5.4 / §1d).
- **Auth before spend:** service-token verified (scope=service, aud match, body workspace match) before any provider path; failures → 401/403 with no token spend.

---

## Follow-ups routed to owners
- **P1-1** → Ash (this sprint): guard `request.json()`, return 400 not 500.
- **P2-1** → Ash (backlog): numeric-magnitude price heuristic.
- **P2-2** → Ash/Rohan (backlog): boot-time Haiku-class allowlist for `TRENDSPARK_MODEL`.
- **P2-3** → Ash (backlog): tighten statement counting if tone drift shows in week-1 logs.
- **P2-4** → Ash/Nisha (backlog): prettify `campaign_type` in fallback copy.

**Sign-off:** ✅ PASS — no P0. AI layer is correct and safe to ship. Proceed to T13 (Swapnil business sign-off). — Ash · 2026-07-13
