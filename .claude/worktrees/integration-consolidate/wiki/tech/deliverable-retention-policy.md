# Deliverable Media Retention Policy (R2 Storage Lifecycle)

> **Owner:** Priya Sharma (CTO) · **Architecture authority**
> **Date:** 2026-07-13
> **Status:** 🟡 SPEC — approved-content retention *number* pending Swapnil + legal/CA sign-off
> **Related:** `wiki/tech/deliverable-payment-flow-spec.md` (DPF epic), `dispute-resolution-policy`, `escrow-and-refund-policy`

---

## 0. Context

Question raised (CEO): once the brand approves the final video, should we delete the stored copy in Cloudflare R2 after 7 days to save storage?

**CTO verdict: NO — do not delete approved deliverables at 7-days-post-approval.** Approval triggers *payment release*, so "delete 7 days after approval" = "delete the primary evidence 7 days after money changed hands." That is the wrong trigger and the wrong window. Instead, adopt a **tiered retention policy** driven by a lifecycle job, and clean up only the genuinely disposable content (superseded revisions, abandoned drafts).

---

## 1. Why NOT delete approved deliverables early

| Risk | Detail |
|------|--------|
| **Disputes & chargebacks** 🔴 | The deliverable video is the PRIMARY evidence of what was delivered. Disputes/refund claims/payment chargebacks routinely surface weeks–months later. Our own `dispute-resolution-policy` + escrow flow depend on proving delivery. Deleting at 7 days destroys evidence inside the window disputes actually occur. |
| **Brand owns the paid asset** | Brand may re-download / reuse / license the approved video. Deleting the thing they just bought in 7 days is a support-ticket generator and a trust hit. |
| **Compliance / legal retention (India)** | Payments platform with TDS, invoices, ASCI advertising-disclosure obligations. Financial-transaction records (a deliverable is tied to one) typically need multi-year retention for tax/audit. 7 days is nowhere near. |

**Cost reality:** R2 is **$0.015/GB-month with free egress**. ~10,000 deliverables/yr × ~100 MB ≈ 1 TB ≈ **~$15/month** to keep a full year of everything. The savings from early deletion are negligible against the dispute/legal/reuse exposure. This optimization saves pennies and risks real money.

---

## 2. Retention tiers (LOCKED design; one NUMBER pending legal)

| Content class | Definition | Retention | Deletable? |
|---------------|-----------|-----------|------------|
| **Approved deliverable** | The final, brand-approved version tied to a released/paid milestone | **Compliance window** — dispute + chargeback + tax retention. **Recommended ≥ 1 year; exact number pending legal/CA.** | ❌ NOT until compliance window elapses |
| **Superseded revision** | Old versions (v1, v2…) replaced by a later approved version | **~30 days after approval** | ✅ YES — this is the real waste to trim |
| **Abandoned draft** | Uploaded but never approved; deal died / collaboration cancelled | **~90 days after last activity** | ✅ YES |
| **Proof screenshots** | `proof/{creatorId}/{deliverableId}/…` metrics proof | Same as the deliverable they belong to | Follows parent |

**Principle:** keep the approved final for the full compliance window; delete only superseded/abandoned content. This trims ~all the storage waste without touching evidence.

---

## 3. Lifecycle job design (technical)

- **Driver:** a scheduled job (reuse the `ScoreCalculationJob` scheduling pattern), NOT the approval event. Approval must never directly trigger a delete.
- **Selection:** query deliverables by class + age:
  - superseded revisions where `approved_at + 30d < now`
  - abandoned drafts where `updated_at + 90d < now` AND status ∉ {APPROVED, POSTED, VERIFIED} AND no funded/released escrow on the collaboration
- **Guard (CRITICAL):** before deleting ANY object, re-check there is **no active or historical dispute** and **no unreleased/held escrow** on the collaboration. A deliverable tied to a dispute is frozen from deletion regardless of age. (Mirror `EscrowService.assertEscrowNotBlockedByDispute` semantics.)
- **Delete = R2 object delete + null the `filesJson` entry** (keep the DB row + metadata as an audit record; only the heavy media object is removed).
- **Audit:** log every deletion (object key, class, reason, timestamp) to the audit log — never a silent purge.
- **Idempotent + dry-run first:** ship with a dry-run flag that logs what *would* be deleted for one cycle before enabling real deletes.

---

## 4. What is EXPLICITLY out of scope (until legal signs)

- ❌ Deleting **approved** deliverables at any fixed short window (7 days, 30 days, etc.). Blocked until legal/CA confirms the compliance retention number.
- ❌ Any deletion tied directly to the approval event.
- ❌ Deleting anything on a collaboration with an active dispute or unreleased escrow.

---

## 5. OPEN DECISION — escalated to Swapnil + counsel

**Question:** What is the mandatory retention period for an **approved, paid** deliverable video?

Inputs needed from legal/CA:
1. Financial-record retention (TDS / invoice / audit) — how many years?
2. Chargeback window for our payment aggregator (RazorpayX / card networks) — how long can a chargeback land?
3. Dispute-resolution-policy retention — does our own policy state a number?
4. DPDP data-retention constraints (we can't keep personal data *longer* than needed either).

**Until this number lands:** default to **keep indefinitely** (safe default — never delete approved content). Superseded/abandoned cleanup (DPF-8) proceeds now since it touches no approved/paid evidence.

---

## 6. Work item

- **DPF-8** (added to DPF epic) — lifecycle job for **superseded revisions + abandoned drafts only**. Approved-content deletion is NOT part of DPF-8. Build: Vikram → Kabir (deletion-guard audit: can it ever delete evidence?) → Kavya → Meera → Priya. See `wiki/tech/deliverable-payment-flow-spec.md` §5.

---

_Authored 2026-07-13 by Priya (CTO). Retention *design* locked; retention *number* for approved content escalated to Swapnil + counsel before any approved-content deletion is built._
