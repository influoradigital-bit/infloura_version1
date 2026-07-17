# Approved Dependencies Log

Dependencies added to this repo must be signed off by the CTO (Priya) and logged here.

| Date | Package | Type | Approved by | Purpose / Notes |
|---|---|---|---|---|
| 2026-07-05 | `lighthouse` | devDependency | Priya (CTO) | Measure FRONTEND-BUILD-SPEC-MEERA.md §9 DoD "Lighthouse ≥85 mobile; no layout shift" for `/brand/meera`. Dev-only, never bundled. Gate = Performance ≥85 mobile + CLS ≈ 0; all 4 categories recorded. |
| 2026-07-05 | `puppeteer-core` | devDependency | Priya (CTO) | Drives the already-installed system Chrome (`C:\Program Files\Google\Chrome\Application\chrome.exe`) — downloads NO Chromium. Needed to seed `localStorage.brand_token` so Lighthouse measures the real `/brand/meera` page instead of redirecting to `/brand/login` (the `?demo=true` bypass is dead-stripped in prod builds, App.tsx:48). Dev-only, never bundled, no source changes. |
| 2026-07-05 | `com.razorpay:razorpay-java` (influora-api, Maven) | runtime dependency | Priya (CTO) | Approved to replace the hand-rolled `java.net.http.HttpClient` scaffolding in `RazorpayClient`/`RazorpayXClient` (integration/razorpay/) with the official typed SDK before any real Razorpay API call is made. Architecture already commits to Razorpay per `BACKEND-ARCHITECTURE-DECISION.md`; this is completing that decision, not a new vendor choice — no cost/stack-conflict escalation to Swapnil needed. Vikram: swap the hand-rolled request/response building for SDK client calls, keep the existing amount-cross-check and Jackson-based webhook parsing (those are our own guardrail logic, SDK doesn't replace them). |

Approval bus: `PRIYA(CTO) → MEERA(DevOps) | APPROVE lighthouse+puppeteer-core as devDeps | GATE=Performance≥85 mobile + CLS≈0, record all 4 categories | no source changes`
Approval bus: `PRIYA(CTO) → VIKRAM | APPROVE com.razorpay:razorpay-java as influora-api runtime dep | swap hand-rolled HTTP calls in RazorpayClient/RazorpayXClient for SDK, keep our own amount-validation + webhook-parsing guardrails intact`
