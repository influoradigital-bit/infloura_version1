# Meera Chat Architecture — Browser-Direct SSE Flow

**Owner:** Vikram (Backend)  
**Status:** ✅ DOCUMENTED (2026-07-12)  
**Authority:** Priya (CTO) — locked design

---

## Architecture Decision: Browser-Direct SSE (NOT Spring→Python)

Meera's chat flow uses a **3-tier browser-direct SSE architecture** where the browser opens a DIRECT connection to the Python AI service, NOT through Spring as a proxy.

---

## Flow Diagram

```
┌─────────┐                  ┌──────────────┐                  ┌──────────────┐
│ Browser │                  │ Spring Boot  │                  │ Python /chat │
│  (FE)   │                  │   (Java)     │                  │   (SSE)      │
└────┬────┘                  └──────┬───────┘                  └──────┬───────┘
     │                              │                                 │
     │  1. POST /meera/sessions/{id}/messages                         │
     │────────────────────────────>│                                  │
     │     { content: "user text" }│                                  │
     │                              │                                 │
     │                              │ 2. Persist user message         │
     │                              │    Credit-gate BEFORE AI call   │
     │                              │    Mint scoped stream token     │
     │                              │                                 │
     │  3. Return streamUrl + token │                                 │
     │<────────────────────────────│                                  │
     │  { streamUrl, streamToken }  │                                 │
     │                              │                                 │
     │  4. DIRECT SSE connection to Python (browser→Python, NO Spring proxy)
     │──────────────────────────────────────────────────────────────>│
     │                              │                                 │
     │  5. Stream AI tokens         │                                 │
     │<───────────────────────────────────────────────────────────────│
     │  event: token                │                                 │
     │  data: {"text": "..."}       │                                 │
     │                              │                                 │
     │  6. Stream complete          │                                 │
     │<───────────────────────────────────────────────────────────────│
     │  event: done                 │                                 │
     │                              │                                 │
     │                              │  7. Callback: persist final AI message
     │                              │<────────────────────────────────│
     │                              │  POST /internal/meera/messages  │
     │                              │  { conversationId, content }    │
     │                              │                                 │
     │                              │  8. Return 200 OK               │
     │                              │─────────────────────────────────>│
```

---

## Why Browser-Direct (NOT Spring Proxy)?

**Priya's design decision:**

1. **Real-time streaming** — SSE tokens must reach the browser with minimal latency. A Spring proxy would add buffering delay.
2. **Credit-gating BEFORE AI call** — Spring gates credits BEFORE minting the stream token, so the AI call never happens if credits are exhausted (Guardrail 5).
3. **Scoped security** — The stream token is single-use, bound to one `conversationId`, and expires quickly (see `StreamTokenService`).
4. **Separation of concerns** — Spring owns persistence & credit ledger; Python owns AI reasoning & streaming.

---

## Code Locations

| Component | File | Role |
|-----------|------|------|
| **Spring Controller** | `influora-api/src/main/java/com/influora/web/MeeraController.java` | Receives user message, returns `streamUrl` + `streamToken` |
| **Spring Service** | `influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java` | Persists user message, credit-gates, mints token, persists placeholder |
| **Python SSE** | `influora-ai/app/routes/chat.py` | Calls Claude API, streams tokens to browser, callbacks to Spring |
| **Frontend** | `src/components/feature/meera/MeeraChatPanel.tsx` | Opens SSE connection, renders tokens, handles errors |

---

## Placeholder ASSISTANT Message (NOT a Bug)

`MeeraSessionService.sendTurn()` persists a **temporary placeholder ASSISTANT message** with content `"[Awaiting AI response via SSE stream]"`.

**Why?**
- Ensures the audit trail (`ai_messages` table) has an ASSISTANT record even if Python's callback fails.
- **NEVER shown to the user** — the real AI response comes from the SSE stream.
- Gets **replaced** by Python's callback (`persistAssistantWriteback`) once the stream completes.

**This is NOT a stub.** The live flow is already wired. The placeholder is a safety net for the audit trail.

---

## E2E Verification Checklist

To verify the full flow works:

1. **Backend green** — `mvn test` passes (P0-1 done)
2. **Running stack:**
   - Spring Boot API running on port 8080
   - Python AI service running on port 8000
   - Vite dev server running on port 5173
3. **Send a message in the UI** — type a message in Meera chat
4. **Verify:**
   - User message appears in UI immediately
   - SSE connection opens to `http://localhost:8000/chat` (check browser Network tab)
   - AI tokens stream into the chat bubble in real-time
   - Final assistant message is persisted in `ai_messages` table (check DB)
   - Credits are decremented (check `ai_credits` table)

**QA:** Kavya  
**E2E Verify:** Meera (runs the full stack + curl checks)

---

## Security Notes

- **Stream token is single-use** — bound to one `conversationId`, expires quickly
- **Credit-gating BEFORE AI call** — no LLM reachability if credits exhausted
- **Tenant-scoped** — every method validates `workspaceId` (Guardrail 4)
- **Browser cannot forge tokens** — minted server-side only

---

**Last Updated:** 2026-07-12 by Vikram (Backend Developer)  
**Authority:** Priya (CTO) — this design is locked
