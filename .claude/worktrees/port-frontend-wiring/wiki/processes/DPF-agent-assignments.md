# DPF Agent Assignments — Quick Reference

> **Date:** 2026-07-13
> **Orchestrator:** Arjun (Engineering Lead)
> **Status:** 🔴 BLOCKED on Swapnil CEO sign-off

---

## Your Assignment (by agent)

### VIKRAM (Backend Developer)
You own **6 out of 7 tasks** in this epic. All backend changes. Read the spec first: `wiki/tech/deliverable-payment-flow-spec.md`

| Task | What You Build | Review Chain | Can Start? |
|------|----------------|--------------|------------|
| **DPF-1** | Brand view endpoint `GET /deliverables/{id}` — reuse `CreatorDeliverableService.toFileResponse` + `resolveDownloadUrl` for presigned URLs; workspace→collaboration trust boundary (reuse `BrandDeliverableService.requireBrandDeliverable` pattern) | Kabir (IDOR) → Kavya → Meera | ✅ YES (after CEO sign-off) |
| **DPF-3** | Mark-posted endpoint `POST /deliverables/{id}/mark-posted` — creator submits `livePostUrl` → validate platform (Instagram/YouTube regex), write `Deliverable.postUrl`, set status `POSTED` | Kabir (SSRF/URL validation) → Kavya → Meera | ✅ YES (after CEO sign-off) |
| **DPF-4** | Flyway migration V52 `ALTER TABLE payment_milestones ADD COLUMN release_condition ENUM(...) NOT NULL DEFAULT 'ON_POSTED'`; update `PaymentMilestone.java` entity + builder | Kavya → Meera | ✅ YES (after CEO sign-off) |
| **DPF-5** | Release gate in `EscrowService.release()` — check `Deliverable.status` per `milestone.releaseCondition`; auto-release scheduled job (reuse `ScoreCalculationJob` pattern); fire `PaymentReleasedEvent` | **Kabir CRITICAL** → Kavya → Meera → **PRIYA SIGN-OFF** | 🔴 NO (needs DPF-3 ✅ + DPF-4 ✅ first) |
| **DPF-6** | Platform verification — pull metrics from `postUrl` via `MetaGraphApiClient` / `YouTubeDataClient`; status `VERIFIED`, source `PLATFORM_VERIFIED`; self-report becomes fallback | **ASH MANDATORY** → Kabir (token/PII) → Kavya → Meera | 🔴 NO (needs DPF-3 ✅ first) |
| **DPF-7** | Merge divergence — `DeliverableMetricService.submit()` reads real `DeliverableStatus` (not milestone-`FUNDED` proxy); update gate check line 96 | **ASH MANDATORY** → Kavya → Meera | 🔴 NO (needs DPF-5 ✅ + DPF-6 ✅ first) |

**Your workflow per task:**
1. Read Priya's spec section for your task
2. Code in `influora-api/src/main/java/com/influora/...`
3. Write unit tests (JUnit 5, Mockito)
4. Self-test: `mvn -o test` (your new tests must pass, zero new failures in baseline)
5. Route to next agent in chain (Ash if AI, else Kabir if money/PII, else Kavya)
6. If RED: read the finding in `wiki/errors/DPF-{task}-*.md`, fix, loop back to step 2
7. Once Meera ✅: Arjun routes to Priya for code review

**Hard rules:**
- **DPF-5 code CANNOT start** until Swapnil signs the spec — it changes when creators get paid
- **DPF-6 & DPF-7 MUST route to Ash first** — no direct-to-Kavya allowed
- **All your tasks route to Kabir** except DPF-4 (pure schema) and DPF-7 (Ash handles security there)

---

### ANANYA (Frontend Developer)
You own **1 task** — the brand viewer UI. Read the spec: `wiki/tech/deliverable-payment-flow-spec.md`

| Task | What You Build | Review Chain | Can Start? |
|------|----------------|--------------|------------|
| **DPF-2** | Deal-room brand viewer UI — video/image player (reuse existing `<video>`/`<img>` from creator flow), caption display, hashtags, approve/revise action buttons (wire to existing `deliverables.approve`/`requestRevision` from `api.ts`) | Kavya → Meera | 🔴 NO (needs DPF-1 ✅ — backend endpoint must exist first) |

**Your workflow:**
1. Wait for DPF-1 ✅ (Vikram's backend endpoint)
2. Build the UI in `src/components/brand/deliverables/DeliverableViewer.tsx`
3. Wire to `GET /deliverables/${id}` (new endpoint from DPF-1)
4. Test locally: files presign correctly, video plays, approve/revise actions work
5. Route to Kavya
6. If RED: read `wiki/errors/DPF-2-kavya-qa.md`, fix, loop
7. Once Meera ✅: done

**Tech stack (from TECH-STACK.md):**
- React (functional components + hooks)
- TailwindCSS for styling
- Framer Motion for transitions (if needed)
- No new dependencies without Priya approval

---

### ASH (AI/ML Expert & AI Code Reviewer)
You own **AI-review for 2 tasks** — DPF-6 and DPF-7. These are MANDATORY gates; Kavya cannot see the code until your review is filed.

| Task | What You Review | Your Deliverable | Can Start? |
|------|-----------------|------------------|------------|
| **DPF-6** | Platform verification logic — Meta/YouTube API pull, reconciliation (self-report vs API), anti-gaming (can creator fake a URL?), labeling (source=PLATFORM_VERIFIED vs CREATOR_REPORTED), cost per call | `wiki/ai-review/DPF-6-platform-verification.md` — model/data choice, integration correctness, anti-gaming checks, cost estimate | 🔴 NO (wait for Vikram to finish DPF-6 build) |
| **DPF-7** | Analytics gate logic — does the divergence fix actually read `DeliverableStatus.APPROVED`? Is the new gate condition sound? Does it handle edge cases (e.g. deliverable deleted, status reverted)? | `wiki/ai-review/DPF-7-analytics-gate.md` — logic correctness, edge cases, regression risk | 🔴 NO (wait for Vikram to finish DPF-7 build) |

**Your workflow per task:**
1. Vikram routes task to you (via Arjun's loop)
2. Read the code + tests
3. Write AI-review note in `wiki/ai-review/DPF-{task}-*.md` covering:
   - **Data/model choice:** Is the API client correct? Are we calling the right Meta/YouTube endpoints?
   - **Integration:** Does it fit the current codebase? Any conflicts with existing analytics?
   - **Anti-gaming:** Can a creator exploit this? (e.g. fake URL, manipulate metrics)
   - **Cost:** What's the ₹ per verification call? Scalability?
   - **Concrete improvement plan:** What would you change? (optional if already solid)
4. PASS/CONDITIONAL PASS/FAIL in your note
5. If PASS or CONDITIONAL PASS → route to next agent (Kabir for DPF-6, Kavya for DPF-7)
6. If FAIL → finding goes to Vikram, loop restarts

**Hard rule:** Kavya QA CANNOT start until your AI-review note is filed. You are the gate.

---

### KAVYA (QA Lead)
You review **all 7 tasks** after their prior gates (Ash if AI, Kabir if money/PII, else you're first).

**Your checklist per task:**
1. Read the code changes
2. Check TECH-STACK.md compliance (TypeScript strict for FE, Spring Boot patterns for BE)
3. Verify tests exist and are meaningful (not just passing)
4. Security spot-check (no hardcoded secrets, no SQL injection, no XSS)
5. Write finding in `wiki/errors/DPF-{task}-kavya-qa.md`:
   - **PASS** (clean, route to next)
   - **CONDITIONAL PASS** (minor notes, non-blocking, route to next)
   - **FAIL** (bug/violation found, loop back to builder)

**Review chain order per task:**
- DPF-1: after Kabir → you → Meera
- DPF-2: you first → Meera
- DPF-3: after Kabir → you → Meera
- DPF-4: you first → Meera
- DPF-5: after Kabir → you → Meera
- DPF-6: after Ash + Kabir → you → Meera
- DPF-7: after Ash → you → Meera

---

### KABIR (Red-Team / Security)
You review **4 tasks** — DPF-1, DPF-3, DPF-5, DPF-6. All involve money, PII, or external URLs (SSRF risk).

| Task | Security Focus | Your Deliverable | Can Start? |
|------|----------------|------------------|------------|
| **DPF-1** | IDOR — can a brand view another workspace's deliverable? Cross-tenant leak? | `wiki/errors/DPF-1-kabir-redteam.md` | 🔴 NO (wait for Vikram build) |
| **DPF-3** | SSRF — URL validation bypasses? Can creator submit `file://`, `http://localhost`, or a redirect chain? | `wiki/errors/DPF-3-kabir-redteam.md` | 🔴 NO (wait for Vikram build) |
| **DPF-5** | Financial logic — can brand bypass the gate? Can creator game POSTED/VERIFIED check? Auto-release timer exploits? | `wiki/errors/DPF-5-kabir-redteam.md` — **CRITICAL PATH** | 🔴 NO (wait for Vikram build + DPF-3/4 ✅) |
| **DPF-6** | Token/PII handling — Meta/YouTube tokens stored securely? API responses logged/cached (leak risk)? | `wiki/errors/DPF-6-kabir-redteam.md` | 🔴 NO (wait for Vikram build + Ash review) |

**Your workflow per task:**
1. Vikram routes to you (or Ash routes if AI task)
2. Read code adversarially — assume attacker mindset
3. Test attack vectors (IDOR probes, SSRF payloads, timing attacks)
4. Write finding:
   - **PASS** (no exploits found, route to Kavya)
   - **Critical/High** (block the loop, send back to Vikram immediately)
   - **Medium/Low** (note it, route to Kavya, track as follow-up)
5. If Critical/High: Vikram fixes, loop restarts from you

**DPF-5 is your critical path** — this is the money gate, so it gets your deepest audit. Budget 6–8 hours.

---

### MEERA (DevOps / Local Verifier)
You verify **all 7 tasks** as the final gate before Priya code review.

**Your checklist per task:**
1. Pull the code (`git fetch`, checkout the branch)
2. Backend changes: `cd influora-api && mvn -o test` — must match baseline (900/0F/0E, or 893/11F/9E if Docker test excluded)
3. Frontend changes: `npm run build` — must exit 0
4. Contract test (if API change): `npx vitest run src/lib/__tests__/api-contract.test.ts`
5. Write verify report in `wiki/errors/DPF-{task}-meera-verify.md`:
   - **✅ PASS** (build green, tests green, route to Priya)
   - **❌ FAIL** (build/test failure, capture log, loop back to builder)

**Hard rule:** Your PASS is the gate to Priya's code review. If you report ❌, the loop restarts from the builder.

---

### PRIYA (CTO)
You review **all 7 tasks** after Meera ✅. This is your final code-quality gate before the task closes.

**Your checklist per task:**
1. Read the code changes (not just the test results)
2. Architectural soundness — does it fit the spec? Any shortcuts taken?
3. Maintainability — will this cause problems in 6 months?
4. TECH-STACK.md compliance — any violations?
5. Sign-off:
   - **✅ APPROVE** (task closed, mark DONE in SHARED_CONTEXT.md)
   - **🔴 REJECT** (architectural issue, loop back to builder with your note)

**DPF-5 requires your explicit APPROVE** before it can close — it's the money gate, so you're the final authority.

---

## Status Tracker (live)

| Task | Builder | Status | Next Agent | Blocker |
|------|---------|--------|------------|---------|
| DPF-1 | Vikram | 🔴 NOT STARTED | — | Swapnil sign-off |
| DPF-2 | Ananya | 🔴 NOT STARTED | — | DPF-1 ✅ |
| DPF-3 | Vikram | 🔴 NOT STARTED | — | Swapnil sign-off |
| DPF-4 | Vikram | 🔴 NOT STARTED | — | Swapnil sign-off |
| DPF-5 | Vikram | 🔴 NOT STARTED | — | Swapnil sign-off + DPF-3 ✅ + DPF-4 ✅ |
| DPF-6 | Vikram | 🔴 NOT STARTED | — | DPF-3 ✅ |
| DPF-7 | Vikram | 🔴 NOT STARTED | — | DPF-5 ✅ + DPF-6 ✅ |

Arjun updates this table as tasks move through the pipeline. When a task reaches ✅ DONE, it moves to the "Complete" section below.

---

## Complete Tasks (archive when epic closes)

_None yet — awaiting Swapnil sign-off to start._

---

**All agents: bookmark this file. Arjun will ping you when your task is ready. Read the spec (`wiki/tech/deliverable-payment-flow-spec.md`) before you start.**

_Agent assignments written 2026-07-13 by Arjun (Engineering Lead)._
