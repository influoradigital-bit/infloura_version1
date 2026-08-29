# FIX-WAVE-0828 — Launch-blocker remediation

**Author:** Priya Sharma (CTO) · **Orchestrator:** Arjun Kapoor
**Date:** 2026-08-28
**Branch:** `fix/f0390-money-flags-build-pipeline`
**Trigger:** Deep production-readiness audit, 33 features traced declaration → wiring → invocation → surfaced result.
**Audit baseline:** 54.5% (12 aligned · 12 partial · 6 broken · 3 missing)

---

## CTO RULING — read this before touching anything

The question put to me was "is this production ready if we added the keys". It is not, and the
reason matters for how this wave is scoped: **the architecture is sound and the last mile is not
wired.** The ledger, escrow, webhook verification, RazorpayX payout rail, AI service auth and the
secrets matrix are real and defensively built. Nearly every failure is configuration that never
reached the process that reads it.

That means most of this wave is genuinely mechanical. **One item is not, and I am refusing it as a
code task:**

### TDS withholding — NOT in this wave (deliberate)

`PayoutService` pays creators the full net amount with zero withholding. `Payout.tdsAmount` exists
but is typed in by an admin on the manual rail; `MoneyDtos.java:149-150` states outright that TDS
and GST are unimplemented platform-wide.

This is not a bug to patch. It is an unbuilt statutory subsystem: §194-O rate selection, PAN-linked
rate variation (and the higher no-PAN rate), threshold aggregation across a financial year, Form
16A generation, and quarterly return data. Implementing that autonomously would produce
plausible-looking tax code that silently mis-withholds real money against real PANs — the failure
mode is a compliance liability that surfaces at assessment, not a stack trace.

**Ruling:** TDS is spec'd as `wiki/tech/TASK-TDS-194O-SPEC.md` and requires Swapnil's sign-off plus
a CA review before an engineer starts. Until it lands, the platform may operate **money-in live,
payouts on the manual admin rail only** — which is already the shipped default
(`VITE_PAYOUTS_ENABLED=false`). That is a coherent launch posture, not a workaround.

### Also deliberately excluded

- **Meta media insights** (`MetricsPollingJob.java:215-221` TODO, dead `InstagramMetricsFetcher`) —
  a product decision, not a defect. Either wire it or drop `instagram_manage_insights` from the
  review request. Needs a product call, so it is not in a bug wave.
- **`R2StorageService.presignPut`** dead code — leave it. Removing it is churn; wiring it is a
  separate upload-architecture change.

---

## ASSIGNMENTS (Arjun → specialists)

File domains are **strictly non-overlapping**. This repo has a documented history of concurrent
sessions clobbering in-progress edits, so no two owners share a file. Every owner re-reads their
region after editing to confirm the change survived.

### Track A — Meera (DevOps) · `deploy/**`, `generate-env.sh`

| ID | Defect | Severity |
|----|--------|----------|
| A1 | `VOICE_AI_BASE_URL` required with no default (`application-prod.yml:86`), set in zero deploy files → **API container does not boot** | BLOCKER |
| A2 | `SMTP_*` read at `application.yml:24-27`, passed in no prod compose → **all email dead** | BLOCKER |
| A3 | `UNSUBSCRIBE_SIGNING_SECRET` never generated → committed placeholder signs live tokens | HIGH |
| A4 | `generate-env.sh:64` emits `REPLACE_ME`; validator only knows `REPLACE_WITH_YOUR_WEBHOOK_SECRET` | HIGH |
| A5 | MSG91 email identity defaults to `.com`, deploy is `.in` | MED |
| A6 | `SPRING_JWKS_URL` uses public URL → cold-boot 401 window before certs exist | MED |
| A7 | `TREND_TAG_INGEST_SECRET` unpassed → n8n recovery tagger 503s | LOW |
| A8 | No `depends_on` api→clamav (90s start_period) → first-minutes upload failures | LOW |

### Track B — Ananya (Frontend) · `docker/nginx.conf.template`, `public/_headers`, `publish-images.yml`, `creator-profile.tsx`

| ID | Defect | Severity |
|----|--------|----------|
| B1 | CSP `script-src 'self'` blocks `checkout.razorpay.com` → **Checkout modal can never open; entire money-in path dead** | BLOCKER |
| B2 | Published image bakes `http://200.141.1.6/...` → mixed-content-blocked from https; `/meera` matches no proxy route | BLOCKER |
| B3 | `creator-profile.tsx:198` always selects FACEBOOK_LOGIN → creators without a Page dead-end | MED |

### Track C — Vikram (Backend, validators + Meta) · `SecretsStartupValidator`, `MetaTokenRefreshService`, `MetaTokenStorage`, `MetaOAuthController`, `MeeraController`

| ID | Defect | Severity |
|----|--------|----------|
| C1 | Refresh drops `authPath` (`MetaTokenRefreshService.java:177-179` → hardcoded FACEBOOK_LOGIN) → **IG path silently dies ~55 days after connect** | HIGH |
| C2 | `isConfigured()` has zero call sites → blank keys yield a dialog URL with empty `client_id` | MED |
| C3 | Razorpay key-id/key-secret never validated (non-blank placeholders pass `isConfigured()`) → boots, then 500s + stray PENDING row. Plus unsubscribe secret unchecked | HIGH |
| C4 | `lang` dropped by Spring's `SpeakRequest` DTO → Hindi TTS always spoken as en-IN | LOW |

### Track D — Vikram (Backend, money paths) · `EscrowService`, `CampaignServiceInvoiceService`, `WalletService`, `UploadService`

| ID | Defect | Severity |
|----|--------|----------|
| D1 | GST Doc#2 failure at release is `log.error`-only (`EscrowService.java:1213-1226`) — no marker, retry, metric or backfill tool. Money moves, statutory doc silently absent | HIGH |
| D2 | No KYC/PAN precondition on `requestCreatorWithdrawal` | HIGH |
| D3 | `UploadService.java:106` persists permanent public URLs for KYC docs (GSTIN/PAN/selfie) | HIGH |

### Track E — Kabir (Security) · new Meta platform-callback controller + SecurityConfig entry

| ID | Defect | Severity |
|----|--------|----------|
| E1 | No Deauthorize Callback anywhere (zero hits for `signed_request`/`X-Hub-Signature`) → app removal never revokes the token; jobs keep calling Graph | APP-REVIEW BLOCKER |
| E2 | No Data Deletion Callback; only the published manual-process doc satisfies the instructions URL | APP-REVIEW BLOCKER |

---

## DONE_WHEN

Per Swapnil's instruction, this wave closes when the partial/broken/missing items above are
resolved. Explicitly **excluded from DONE_WHEN** by CTO ruling: TDS (§194-O), Meta media insights,
`presignPut`. Those carry their own tickets.

Standing gate — **not optional, and not blocked on this wave:**

> **Rotate the Anthropic, Gemini and Sarvam keys before launch.** `influora-ai/env.example` is
> git-tracked and holds real-shaped credentials (`:12`, `:19`); `.gitignore:87` shows
> `.env.production` was previously committed. Rotation is required regardless of every other fix
> here, and `git rm --cached` on any tracked secret file.

## VERIFICATION STANDARD

A fix is DONE when it compiles/builds **and** the specific failure it targets is demonstrably gone.
"Build exits 0" is not evidence — this repo has a documented history of gates passing vacuously and
of producer-written checks greening their own wrong fix. Owners report file:line for every edit so
the change is re-checkable by someone who did not write it.
