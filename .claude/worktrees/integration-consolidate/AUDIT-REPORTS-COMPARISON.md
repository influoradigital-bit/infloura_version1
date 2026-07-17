# Comparison of the Two Influora Audits

**Report A** — `INFLUORA-CODE-AUDIT-2026-07-14.md` (earlier)
**Report B** — `INFLUORA-PRODUCTION-READINESS-AUDIT-VERIFIED.md` (mine, today)

Both are source-only audits of the same codebase, both trace controller→service→repo and UI→API-client→endpoint, both cite `file:line`. Where they say the same thing, that's strong cross-validation (two independent passes reaching the same conclusion). Where they differ, I re-checked the source and recorded who is right — that's the useful part.

---

## 1. Headline numbers

| | Report A (earlier) | Report B (today) |
|---|---|---|
| Overall health | **60 / 100** | **54 / 100** |
| Critical | 6 | 15 |
| High | 15 | 27 |
| Medium | 22 | ~30 |
| Low | 14 | ~20 |
| Backend completion | 80% | 72% |
| AI integration | 55% | 35% |
| Security | 70% | 68% |
| Production readiness | 42% | 38% |

The two are close on everything except **AI** and the **Critical count**. Both differences are explained below and are substantive, not cosmetic — B escalates more items to Critical (splitting AI into its separate break points, adding the cosmetic-admin-enforcement bugs) and rates AI lower because B found the chat path is broken end-to-end where A assumed it worked.

---

## 2. Where they agree (high-confidence shared findings)

These appear in **both** reports with matching evidence — treat them as confirmed:

- Creator auth is a hardcoded `mock_creator_token`; real endpoints never called. *(A-C1 / B-C5)*
- Creator onboarding POSTs to `/onboarding/creator/*` which doesn't exist (brand-only controller). *(A-C2 / B-C6)*
- Brand campaigns list renders `mockCampaigns`, never calls the live API. *(A-C3 / B-H9)*
- AI brand-safety + TrendSpark are dead: routers unregistered in `main.py`, config regression (`TRENDSPARK_MODEL` etc. missing) causing ImportError, and `claude.complete_text` doesn't exist. *(A-C5 / B-C8+C9)*
- No AI spend gate on the live chat/voice/analyze routes; kill-switch inert. *(A-C6 / B-H12+H13)*
- Escrow funding requires wallet balance **and** a fresh Razorpay charge, then debits the wallet — double-charge / contradictory flow. *(A-H12 / B-C1)*
- No access-token refresh; sessions hard-break on expiry. *(A-H8 / B-H16)*
- Notifications broken: divergent hook, wrong base URL, wrong paths, missing envelope. *(A-H15+M4+M5 / B-C7+H18)*
- Payout `confirmExecuted` is a no-op; `payouts` table/entity/repo orphaned; reversals never reconciled. *(A-M1+M10 / B-H7)*
- `@Scheduled` money jobs have no ShedLock → double-run on multi-instance. *(A-M3 / B-H23)*
- Malware scanning is a prod no-op. *(A-M9 / B-Medium)*
- Committed dev-default signing/DB secrets in `application.yml`. *(A-H1+H2 / B-H27)*
- Prompt-injection: assembler uses the bypassable wrapper, not the hardened one. *(A-H13 / B-H14)*
- Sarvam TTS returns raw body instead of base64 JSON. *(A-M11 / B-Low)*
- Disputes derived from `/deals`, real dispute endpoints unused. *(A-M21 / B-Medium)*
- WorkspaceMember + CampaignTemplate controllers have no frontend caller. *(A-M22 / B-Medium)*
- Admin CEO revenue + deltas hardcoded to 0. *(A-M2 / B-Medium)*

That is a large, independently-reproduced common core — the shared conclusions are reliable.

---

## 3. Where they differ — and who is right (re-verified in source today)

### 3a. Findings **Report A caught that Report B missed** (A is right)

- **Webhooks + JWKS blocked by Spring Security (A-H3).** ✅ **Confirmed — B missed this.** Only `POST /webhooks/razorpay` is `permitAll`. `SecurityConfig.java:74-88` shows `/webhooks/redemption`, `/webhooks/conversion`, `GET /track/click/{id}`, the Shopify/WooCommerce webhooks, and `GET /.well-known/jwks.json` all fall to `.anyRequest().authenticated()` → they 401 with no bearer token. This breaks coupon-redemption, conversion attribution, affiliate click tracking, store webhooks, **and** the JWKS endpoint the AI service needs. Genuine High that B did not report. *(Note: B did catch the sibling issue that `/admin/auth/**` is likewise not permit-listed — A missed that one. Both halves of the SecurityConfig gap exist.)*
- **Creator deal room is entirely mock (A-C4) and brand deal room too (A-H7).** ✅ **Confirmed — B under-reported this.** `creator-chat.tsx:171,245` render `mockDealRooms`/`mockTimelineEvents`, and accept/decline/counter/submit handlers are `console.log('[v0]…')` stubs (`:505-572`); `brand-chat.tsx` is the same (`:107,179,638`). B flagged the campaign mocks and the orphaned creator dashboard but not the deal-room chat surfaces — a real gap, since this is the core negotiation flow.
- **Deeper Python-service and secrets findings** present only in A: env-gate bypass so prod can boot on dev secrets (A-H2), unvalidated `X-Forwarded-For` in the rate limiter (A-M8), SSRF size-cap applied after full buffering (A-M12), blocking `guarded_fetch` inside the async handler (A-M13), chat persistence lost on disconnect (A-M14), AI boots "ready" then 500s on misconfig (A-M15), voice upload has no size cap (A-H14), `/escrow/payout` needs only membership not OWNER (A-L2). These are legitimate and B did not surface them.

### 3b. Findings **Report B caught that Report A missed** (B is right)

- **Deliverable rows are never created (B-C2)** and **brand approval triggers no escrow/payment/notification (B-C3).** No production code instantiates a `Deliverable`; `approve()` only flips status. A treated the deliverable/contract UI as "mock data" but didn't identify that the **backend provisioning + approval→payment link simply doesn't exist**. Deeper root cause.
- **Admin suspend/ban is cosmetic (B-C13/C14).** Suspend persists a flag that nothing enforces — suspended brands/creators still log in and stay discoverable. **A did not report this at all**; its admin section is lighter.
- **`release_condition` gate is dead (B-H5)**, **contract generate/sign DTOs mismatch the UI (B-C4)**, **25 of 26 notification events are never published (B-H8)**, **`@Async` is a silent no-op with no `@EnableAsync` (B-H26)**, **IDOR on escrow milestone funding (B-H3)**, and the **GST/invoicing correctness issues** (invoice creation rolls back payouts; NULL-code invoice numbers aren't unique — B-H10/H11). A has none of these; they come from B's deeper backend state-machine and money-correctness tracing.
- **Meera chat is broken end-to-end (B-C10/C11/C12)** — see 3c.

### 3c. The one real contradiction: does Meera chat work?

- **Report A:** rates AI 55%, calls chat "connected," and its M14 assumes the Python stream is reached and generating billed output.
- **Report B:** rates AI 35% and says chat is broken end-to-end.
- **Verified today — B is correct.** `MeeraStreamProperties.java:25` sets `publicChatUrl = http://localhost:8000/chat` (the Python route, which is `@router.post("/chat")`). The browser opens `new EventSource(url + "?token=")` (`useMeeraStream.ts:124-126`) — EventSource is **GET only** and cannot send a body or Authorization header, so it hits a POST route (405) and can transmit neither the token nor `workspace_id`. On top of that, Spring's `MeeraSessionService` returns a hardcoded `"Meera (placeholder)…"` (no LLM call), and the stream token is minted without the `scope`/`iss` claims the Python verifier requires (401). A missed the placeholder, the transport mismatch, and the token gap. B's lower AI score is the accurate one.

---

## 4. Net assessment

Neither report is "wrong." They corroborate each other on ~20 core defects, which is the strongest signal in either document. Their differences are mostly **complementary coverage**:

- **A is stronger on:** the Spring-Security reachability gap (webhooks/JWKS), the deal-room mock surfaces, and several Python-service hardening details (XFF, SSRF buffering, async blocking, voice cap) plus a sharper secrets/env-gate analysis.
- **B is stronger on:** backend state-machine correctness (deliverable provisioning, approval→payment, `release_condition`), admin-enforcement bugs (cosmetic suspend/ban), the notification event fabric, money/GST correctness, and the true end-to-end status of the AI chat path.

**The most accurate picture is the union of the two.** Combined, that's roughly **17 Critical-class** problems once A's webhook/JWKS block and deal-room mocks are added to B's list, and the honest overall grade sits at the lower end — **~52–55/100** — because A's higher backend/AI scores rest on two things B disproved (chat isn't connected; several "mock UI" surfaces are actually missing backends, not just unwired frontends).

### Action list if you merge both

1. **Config/security, do first (cheap, high blast radius):** permitAll the non-Razorpay webhooks + JWKS + admin-login (A-H3 + B-H1); fix the env-gate so prod fails closed and rotate committed keys (A-H1/H2).
2. **Money correctness:** escrow double-charge (both), payout reconciliation (both), invoice-rollback + NULL invoice-number uniqueness (B-H10/H11).
3. **Make the creator side real:** creator auth + onboarding + deal room + wallet (A-C1/C2/C4/H4, B-C5/C6).
4. **Make the marketplace loop exist:** create deliverable rows, wire approval→release, fix contract DTOs (B-C2/C3/C4/H5).
5. **Fix AI properly:** register routers + restore config (both), fix chat transport/token/placeholder (B-C10/C11/C12), spend gate on live routes (both), hardened injection wrap (both).
6. **Notifications + admin enforcement:** publish the missing events + `@EnableAsync` (B-H8/H26), enforce suspend/ban (B-C13/C14), fix the notification client (both).
7. **Ops hardening:** ShedLock (both), XFF trust, SSRF streaming cap, malware scanning, async fetch (A-M-series).

---

*Both reports were prepared from source only. This comparison re-verified every point of disagreement directly in the code before ruling on it.*
