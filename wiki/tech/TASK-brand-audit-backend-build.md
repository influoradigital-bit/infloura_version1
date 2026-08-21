# TASK — Backend Build: Runway + PDF Contracts + Reported Analytics

> **From:** Priya (CTO) — Swapnil approved all three, 2026-07-06
> **Owners:** Vikram (backend, all 3) + Ananya (frontend for #1 display & #3 analytics UI)
> **Source:** Vikram's verification report (Q17/Q13/Q9) — all confirmed real gaps.
> Stack reality: Spring Boot + MySQL (`influora-api/`), Vite+React frontend (`src/`). Trust the code, not persona docs.

---

## #1 — Wallet runway = REAL math (Q17) — SMALL, do FIRST
**Problem:** `WalletBalanceResponse` (`influora-api/.../web/dto/money/MoneyDtos.java:30-31`) has no `runwayDays`. Frontend always falls back to hardcoded `47` (`dashboard-page.tsx:90,163`; `brand-wallet.tsx`). Every brand sees a fabricated number — trust liability on a money product.

**Vikram (backend):**
- Add `runwayDays` (Integer, nullable) to `WalletBalanceResponse`.
- Compute in `WalletService` from `WalletLedgerService` real ledger data: `runwayDays = floor(availableBalance / avgDailyBurn)`, where `avgDailyBurn = (sum of DEBIT entries in trailing 30 days) / 30`.
- Edge cases: if `avgDailyBurn == 0` (no spend) → return `null` (frontend shows "—" / "Healthy", not Infinity). Cap absurd values sensibly. Document the window in a comment.
- Unit test the calc (zero-burn, normal, high-burn).

**Ananya (frontend):**
- `dashboard-page.tsx:163` and `brand-wallet.tsx`: when API returns real `runwayDays`, use it. When `null`, show a graceful "—"/"Healthy" state — do NOT fall back to the mock 47 anymore. **Remove `mockWallet.runwayDays` fallback** so a fabricated number can never render again.

---

## #2 — Contract PDF generation + email link (Q13) — MEDIUM
**Problem:** `Contract.pdfR2Key` is a dead field (never set). No PDF generation anywhere. MSG91 payload has no attachment support (`Msg91EmailClient.buildPayload()`).

**Approved dependency (logged in approved-deps.md):** `com.github.librepdf:openpdf` (+ optional `flying-saucer-pdf-openpdf` for HTML→PDF). No other PDF lib.

**Vikram (backend):**
- On contract signing (`ContractService.recordSignature` / after `ContractSignedEvent`): generate a PDF of the contract (parties, terms, amounts, signature timestamps). HTML-template→PDF via Flying Saucer is cleanest; pure OpenPDF is fine too.
- Store to R2 via existing `R2StorageService` (note: current `presignPut` is upload-oriented for videos — you'll need a direct server-side `putObject` path for a server-generated PDF; extend `R2StorageService` with a `putBytes(key, bytes, contentType)` method, keep it small).
- Set `Contract.pdfR2Key` to the stored object key.
- Delivery: MSG91 **cannot attach** — email a **secure, time-limited presigned GET download link** to both brand + creator via the existing notification/outbox path. Add a template variable for the link; do NOT try to attach the file.
- Secrets: R2 + MSG91 creds stay as placeholders (`REPLACE_WITH_YOUR_*`) — do not commit real keys.

**Ananya (frontend):** add a "Download contract PDF" affordance in the Deal Room contract view once `pdfR2Key` is present (link to the download URL the API exposes). Small.

---

## #3 — Campaign analytics = REPORTED-first (Q9) — MEDIUM (Swapnil chose reported now, platform-API later)
**Problem:** `brand-campaign-detail.tsx:94-135` renders a fully hardcoded `mockCompletedCampaign.analytics`. No analytics data model, no endpoint. `PlatformStat` holds creator follower/engagement snapshots but NO per-campaign/per-deliverable performance data.

**Decision:** ship **creator-reported** metrics now; verified platform-API integration is a SEPARATE post-launch effort (do not build it now).

**Vikram (backend):**
- New entity `DeliverableMetric` (or extend the deliverable model): per deliverable — `reach`, `impressions`, `engagements`, `link`, optional `proofScreenshotR2Key`, `reportedByCreatorId`, `reportedAt`. All creator-entered.
- Endpoint for creator to submit/update metrics on an approved deliverable; endpoint for brand to read aggregated campaign analytics (sum reach/impressions/engagements, simple derived rates).
- **Honesty rule (non-negotiable):** every analytics response must carry a `source: "CREATOR_REPORTED"` flag so the UI can label it. We do NOT present self-declared numbers as verified.

**Ananya (frontend):**
- Replace the hardcoded analytics object in `brand-campaign-detail.tsx` with a real fetch.
- **Label clearly**: "Creator-reported" badge/tooltip on the analytics block. Do not imply these are platform-verified.
- Empty state when no metrics reported yet ("Waiting for creator to report performance") — not fake zeros or mock numbers.

---

## GLOBAL RULES
- Sequence: #1 (runway) first — smallest, highest trust-impact. Then #2 and #3 in parallel if capacity allows.
- No secrets — placeholders only (R2, MSG91). Standing rule.
- No dependencies beyond the OpenPDF/Flying Saucer already logged. Anything else → ask me first.
- Backend → Kavya QA → Meera build-verify. Frontend same.
- `runwayDays` and analytics must **never** silently fall back to a mock number. Missing data = honest empty state.

## REPORT BACK TO PRIYA
- #1: DTO + calc + test result; frontend confirms mock fallback removed.
- #2: PDF generated + stored + `pdfR2Key` set + email link path (screenshot of a generated PDF).
- #3: entity + endpoints + `source` flag; frontend shows "creator-reported" label + empty state.
