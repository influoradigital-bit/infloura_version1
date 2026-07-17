# 🧭 CONTEXT HANDOFF — read this first in a new chat

> **Purpose:** Catch a fresh chat up on the Meera / brand-AI project WITHOUT re-reading everything.
> **How to use (token-efficient):** Read THIS file only. It's an index — open the linked files **only when a task needs them**. Don't bulk-read the folder.
> **Updated:** 2026-07-05

---

## 30-SECOND CONTEXT

- **Influora** = escrow-backed campaign platform for Indian brands + creators. Frontend: React + Vite. Backend: Spring Boot + MySQL (`influora-api/`). Payments: Razorpay escrow.
- **Meera** = the AI cofounder we're building for the brand side. A 50/50 workspace: chat left, live "canvas" right (5 stages: Snapshot→Recommend→Match→Fund→Live). Brand-themed persona, Hinglish voice.
- **Where we are:** Frontend vertical slice is BUILT (mock). Backend AI + money layer is fully SPEC'd (docs below), **not yet coded**. Next real step = Vikram builds from the manifest.

---

## DECISIONS ALREADY MADE (don't re-litigate)

| Decision | Ruling | Detail in |
|---|---|---|
| Backend split | Spring owns money/state/auth; ONE Python FastAPI does AI/voice. Python proposes, Spring disposes. LLM never moves money. | `backend/00`, `BACKEND-ARCHITECTURE-DECISION.md` |
| Voice stack | Claude (brain) + Sarvam (Hinglish STT/TTS), cascaded. Text fallback always. NOT Gemini/OpenAI speech-native — cheaper + consistent. | Rohan analysis (this chat) |
| Keys | ULID `VARCHAR(26)` everywhere, NOT BIGINT. | `backend/01` |
| Meera permissions | Tiers R/D/C/Forbidden. AI can't finalize money/contracts — human confirms via UI click. Chat "yes" ≠ authorization. | `backend/06` |
| Credit model | 100 credits/free brand, pause when out, unlimited after go-live funds escrow. | `PRD-MEERA-AI-COFOUNDER.md §7` |
| Frontend v2 | Beat Alippo: artifact chat cards, brand-themed talking orb, chat↔canvas hover-link, in-place live edits. Additive only. | `PRIYA-HANDOFF-MEERA-V2-LIVING-WORKSPACE.md` |
| File-count principle | One file = one responsibility. Collapse boilerplate (events/DTOs/repos); NEVER merge money/security logic to game the count. | this chat |

---

## FILE MAP — open only what the task needs

### Backend blueprint — `docs/AI connect/backend/`
| File | Read when you need… |
|---|---|
| `00-BACKEND-BLUEPRINT-INDEX.md` | the map + reading order (start here for backend) |
| `01-DATA-MODEL.md` | DB schema, Flyway V8–V16, entities |
| `02-API-CONTRACT-BRAND.md` | endpoints (public + `/internal/meera/*`) |
| `03-SECURITY-SPEC.md` | the 6 guardrails, must-fixes, 25-row checklist |
| `04-AI-SERVICE-SPEC.md` | Python service: prompts, caching, streaming, providers |
| `05-VIKRAM-WORK-TASKS.md` | build order / phases / critical path |
| `06-MEERA-PERMISSIONS-MATRIX.md` | what Meera may/may not do (tool-call contract) |
| `07-NOTIFICATION-SYSTEM-SPEC.md` | 26 brand↔creator email + in-app events |
| `08-CODEBASE-INVENTORY.md` | what ALREADY exists in `influora-api` (built vs absent) |
| `09-ADVANCED-SECURITY-MEASURES.md` | defense-in-depth, 24 mandatory security files |
| `10-VIKRAM-FILE-MANIFEST.md` | every file to build (~140), counted, with paths |
| `11-AI-FLOW-DETAILED.md` | end-to-end request lifecycle + security checkpoints |

### Frontend + product — `docs/AI connect/` and `docs/`
| File | Read when you need… |
|---|---|
| `FRONTEND-BUILD-SPEC-MEERA.md` | the frontend build sheet (components, motion, voice §5A) |
| `PRIYA-HANDOFF-MEERA-V2-LIVING-WORKSPACE.md` | the Alippo-beating v2 additions |
| `ANANYA-BUILD-NOTES.md` | what's already built in the frontend slice |
| `BACKEND-ARCHITECTURE-DECISION.md` | the original Spring-vs-Python ruling + Kabir guardrails |
| `../PRD-MEERA-AI-COFOUNDER.md` | full product requirements (persona, UI, cost, credits) |
| `../BUSINESS-BLUEPRINT.md` | core business context |

---

## OPEN ITEMS / NEXT STEPS

1. **Phase 0 blocker:** `application.yml` + `SecurityConfig.java` are truncated on disk (`SecurityConfig` won't compile). Restore from git before any build. (`backend/10` Phase 0)
2. `git init` — repo is uncommitted files on disk.
3. Optional: add consolidation note to `backend/10` (collapse boilerplate → ~90 real files).
4. Not started: Vikram coding, Kavya review pass on the blueprint, folding backend arch into `TECH-STACK.md`.

---

## HOW TO RESUME IN A NEW CHAT

Say: **"Read `docs/AI connect/CONTEXT-HANDOFF.md` and continue."**
Then name the specific task — the chat opens only the files that task needs, keeping tokens low.
