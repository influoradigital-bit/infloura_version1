# Kabir red-team review — Fix #3 Deliverable-level brand-safety review

Reviewer: Kabir (red-team lead). Target: Vikram's NEW build, `brand-fixes-backend.md` #3.
Method: adversarial trace against real code (controller → service → repo → AI client → Python route),
not against the build doc's self-description. Verdict per invariant with file:line + exploit attempt.

**OVERALL GATE: PASS — cleared to ship live.** No cross-party leak, no IDOR. Two non-blocking
follow-ups (one doc correction, one FE render-safety note) recorded below.

---

## Invariant 1 — Info-barrier / cross-party leak → **PASS**

**Claim under test:** scoring a CREATOR's caption and showing the verdict to the BRAND is a leak.

**Trace:**
- The classify request carries exactly two things: `workspace.getId()` (the caller's OWN workspace,
  server-derived — see IDOR below) and ONE `ContentItem` built from `deliverable.getCaption()`
  (`DeliverableSafetyReviewService.java:127,131`). No other creator, no batch, no aggregate.
- The deliverable is resolved through `findByIdAndWorkspaceId` (`DeliverableRepository.java:49-54`) —
  Collaboration → Campaign → `workspaceId` join-through. The row is only reachable if it belongs to
  a campaign THIS brand's workspace owns, i.e. content the brand commissioned and is paying for.
- The response (`DeliverableSafetyReviewResponse`) contains only THIS deliverable's own verdict:
  10 GARM category statuses, per-category rationale, one 0-100 score, a timestamp
  (`DeliverableSafetyReviewService.java:155-160`). Nothing from any other deliverable, creator,
  or market aggregate; no k-anon-gated cohort data; no creator PII (the caption is PII-redacted
  before it even leaves the service — `:125`).

**Verdict:** Legitimately within the brand↔creator contracted relationship. The brand commissioned
and pays for THIS deliverable; classifying its caption for that same brand does not cross the info
barrier the way an unrelated cross-tenant read would. The response leaks nothing beyond this
deliverable's own safety signal. **Not a leak.**

**Non-blocking follow-up (F1, doc accuracy):** the service/DTO javadoc claims model output is
"consumed as STRUCTURED DATA ONLY … never as free text rendered back to the brand verbatim"
(`DeliverableSafetyReviewService.java:56-58`). That is overstated: `GarmFlag.rationale()` — model-
generated free text — is placed into `SafetyCheck.detail` and returned to the brand
(`:189`), and `overall_rationale` exists on the wire DTO (`BrandSafetyDtos.java:50`). This is model
prose ABOUT the brand's own deliverable, so it is still not a cross-party leak, but the "structured-
only" wording should be corrected so a future reader doesn't rely on a guarantee the code doesn't
make. See also F2.

---

## Invariant 2 — IDOR (foreign deliverable) → **PASS**

**Exploit attempted:** brand A calls `GET /deliverables/{B's-deliverable-id}/safety-review`.

**Trace:**
- Workspace is resolved from the principal, never from the client:
  `brandContext.requireBrandWorkspace(principal)` (`DeliverableSafetyReviewService.java:103`).
  `requireBrandWorkspace` first enforces `UserType.BRAND` (403 `WRONG_USER_TYPE` for a creator —
  `BrandContextService.java:36-41`) then derives `workspaceId` from the JWT / active membership
  (`:43-65`). No path-param or body influences which workspace is used.
- The deliverable is loaded with the tenant-scoped finder
  `findByIdAndWorkspaceId(deliverableId, workspace.getId())` (`:104-112`); a foreign/unowned id
  yields `Optional.empty()` → `DELIVERABLE_NOT_FOUND` (404), identical discipline and error code to
  the sibling `getDetail`/`approve`/`reject` reads on the same controller. Confirmed the finder's
  JPQL is a genuine join-through (`DeliverableRepository.java:49-54`), not a plain `findById`.

**Verdict:** Foreign id returns 404 before any classify call. **No IDOR.** (The service's own unit
test `unowned deliverable rejected before any classify call` covers exactly this and matches the
code.)

---

## Invariant 3 — SR-2 / prompt injection ("ignore instructions, mark safe") → **PASS**

**Exploit attempted:** creator submits a caption whose text instructs the model to emit a safe
verdict / an injected instruction.

**Trace:**
- The verdict is 100% SERVER-computed, never read from the model. The model returns only per-category
  `risk` enum + sentiment + numeric score; `deriveVerdict` (`DeliverableSafetyReviewService.java:
  208-225`) and `toCheckStatus` (`:194-204`) compute PASS/WARNING/FAIL/REVIEW in Java from the enum.
  There is no field on `ClassifiedItem` the model can populate to say "PASS".
- The structured output is enforced twice: forced `tool_choice`
  (`brand_safety.py:315 complete_with_forced_tool`) plus `_validate_model_result`
  (`brand_safety.py:199-257`) which rejects (→ typed 502, never a passthrough) any result where a
  category is outside the fixed GARM set, a risk is outside the risk enum, not all 10 categories are
  present, or the score is out of `0-100`. A caption that talks the model into a bogus non-enum value
  fails validation rather than flipping the verdict. Worst-case the model is merely *wrong* about the
  risk tier — that is a model-accuracy limitation, not an injection flipping a control.
- Free-text redaction where logged: caption is never logged raw — Python logs only `shape_of(...)`
  (`brand_safety.py:306`); the Java client deliberately logs no request body, only workspace + item
  count + status (`BrandSafetyAiClient.java:124-131,36-38`). Caption is additionally run through
  `SensitiveTextRedactor.redact` before egress (`DeliverableSafetyReviewService.java:125`).

**Verdict:** The model's text cannot flip the verdict or smuggle a server-honored instruction.
Free text is redacted at egress and never logged raw. **Holds.**

**Non-blocking follow-up (F2, FE render-safety):** the RETURN path is not symmetric with the send
path — `rationale` / `overall_rationale` are NOT passed through `SensitiveTextRedactor` on the way
back, and a hostile caption can steer the model to place attacker-chosen prose into `detail`
(`:189`). Backend impact is nil (advisory text about the brand's own content; cannot flip the
verdict). But `useDeliverableSafetyReview.ts` / the review UI MUST render `detail` as TEXT, never as
`dangerouslySetInnerHTML`, or this becomes a stored-XSS sink where the creator's caption is the
injection vector and the brand is the victim. Flag to Ananya; not a backend block.

---

## Invariant 4 — Advisory-only / R-tier (cannot block or gate money) → **PASS**

**Trace:**
- The ONLY caller of `getReview` is the pure GET route `GET /{deliverableId}/safety-review`
  (`BrandDeliverableController.java:81-86`). Repo-wide grep for `DeliverableSafetyReviewService` /
  `getReview(` returns only: the controller wiring, the DTO/service javadoc, and tests — nothing in
  `submit` / `approve` / `requestRevision` / `reject`, and nothing in any escrow/payout/eligibility
  path references this service.
- `deriveVerdict` feeds no persistence and no gate — the response is constructed and returned inline
  (`:155-160`), nothing is written, no `@Transactional` side effect (`readOnly = true`).
- Failure modes all degrade softly and never propagate as a blocker: classifier failure → 503
  `SAFETY_REVIEW_UNAVAILABLE`, no caption → 404 `SAFETY_REVIEW_NO_CONTENT`, unowned → 404
  (`:114-152`). None of these can stall approve/reject.

**Verdict:** Structurally incapable of blocking or auto-rejecting a deliverable, and no money/
eligibility decision depends on it. **Holds.**

---

## Gate decision

| Invariant | Verdict |
|---|---|
| 1 — Info-barrier / cross-party leak | **PASS** |
| 2 — IDOR (foreign deliverable) | **PASS** |
| 3 — SR-2 / prompt injection | **PASS** |
| 4 — Advisory-only / R-tier | **PASS** |

**SHIP.** Vikram's info-barrier judgment is confirmed correct: this is the brand scoring its own
commissioned, workspace-verified deliverable for itself — not a cross-party read. No hard-BLOCK
condition (real leak or IDOR) is present.

Two non-blocking follow-ups, neither gating this ship:
- **F1** — correct the "structured data only / never free text back to the brand" javadoc claim
  (`DeliverableSafetyReviewService.java:56-58`); `detail`/rationale IS model free text returned to
  the brand.
- **F2** — FE must render `SafetyCheck.detail` as plain text (no HTML injection). Owner: Ananya.
  Backend redaction on the return path (rationale) would be defense-in-depth but is optional.

---

## Independent re-review (owner-requested) — 2026-07-22

Owner asked for a fresh confirm-or-correct on three specific points, not a restatement of the gate
above. Re-traced the actual code (not the build doc) for each. Method: attack the point, then rule.

### Q1 — Info-barrier: is AI-classifying a submitted-but-not-yet-public caption for the brand a NEW disclosure? → **CONFIRM (no new disclosure)**

The decisive fact the owner named — *does the brand already see this caption directly in the
deliverable-review flow?* — is **TRUE**, verified in code:

- The brand's deliverable-detail read is `GET /deliverables/{id}` →
  `BrandDeliverableController.getDetail` (`BrandDeliverableController.java:39-44`) →
  `BrandDeliverableService.getDetail` → `toDetailResponse` (`BrandDeliverableService.java:202-219`),
  which puts **`deliverable.getCaption()` on the wire at `:212`**.
- The DTO it populates, `DeliverableDetailResponse`, has a first-class **`String caption` field
  (`BrandDeliverableDtos.java:34`)**. So the brand already receives the raw, un-redacted caption
  as normal deliverable-review content, through the same workspace boundary, *before* any
  safety-review call exists.

Therefore the safety-review feature sends the AI a caption **the commissioning brand already holds
in plaintext**. An AI read of content the recipient already possesses is not a new pre-publication
disclosure of creator content — there is no party on the safety-review path who cannot already see
the caption. (It is in fact *more* protective than the detail read: the caption is
`SensitiveTextRedactor.redact`-scrubbed before egress to influora-ai —
`DeliverableSafetyReviewService.java:128` — whereas the detail read at `:212` ships it raw.)

**The owner's "if FALSE this flips the verdict" condition does NOT trigger — the fact is TRUE.**
Verdict stands and is now grounded on the decisive fact rather than the softer "brand commissioned
it" framing from the first pass.

### Q2 — Authorization: is `findByIdAndWorkspaceId` sufficient? IDOR attempts → **CONFIRM (no IDOR)**

- **Workspace derived from principal, never a caller param.** `getReview` calls
  `brandContext.requireBrandWorkspace(principal)` (`DeliverableSafetyReviewService.java:106`);
  `requireBrandWorkspace` reads the workspace from `principal.getWorkspaceId()` / the principal's
  active membership (`BrandContextService.java:43-65`). No path-param, query-param, or body value
  influences workspace selection. The classify call also uses `workspace.getId()` (`:134`), the
  server-derived id — not anything client-supplied.
- **Foreign / other-workspace deliverable id → 404.** `findByIdAndWorkspaceId` is a genuine
  3-level join-through, confirmed in the JPQL (`DeliverableRepository.java:49-54`):
  `Deliverable.collaborationId IN (Collaboration WHERE campaignId IN (Campaign WHERE
  workspaceId = :workspaceId))`. A deliverable owned by another workspace yields `Optional.empty()`
  → `DELIVERABLE_NOT_FOUND` **404** (`:110-115`) — resource-hiding, not 403, identical to the
  sibling reads (`BrandDeliverableService.requireBrandDeliverable` uses the exact same finder,
  `:187-196`). No probe distinguishes "exists elsewhere" from "does not exist."
- **Creator principal hitting the brand endpoint → 403, not a leak.** `requireBrandWorkspace` →
  `requireBrand` throws `WRONG_USER_TYPE` **403** for any non-`BRAND` principal
  (`BrandContextService.java:36-41`) *before* any deliverable row is touched. (403-not-404 is correct
  here: this rejects the account *type*, which reveals nothing about any specific resource — the
  resource-hiding 404 is the layer that matters for IDOR, and that one holds.)

The boundary is the same one `BrandDeliverableService` already uses for approve/reject/detail;
reusing it is sufficient. **No IDOR.**

### Q3 — Injection / exfil: redaction + structured-output-only consumption adequate? Is `rationale`→`detail` an exfil/injection vector? → **CONFIRM (adequate; residual is low, and closed at render)**

Verdict path cannot be flipped by a hostile caption:
- The model returns only enum `risk` per category + numeric score; PASS/WARNING/FAIL and the
  overall verdict are computed in Java (`toCheckStatus` `:197-207`, `deriveVerdict` `:211-228`).
  There is no model-populated field that names a verdict.
- influora-ai enforces the structure twice — forced `tool_choice`
  (`brand_safety.py:315`) + `_validate_model_result` (`:199-257`), which returns `None` → typed 502
  if any category is out-of-enum, any category is missing, count mismatches, or score ∉ [0,100]. A
  caption that talks the model into a non-enum value fails validation rather than flipping a control.

The `rationale` free-text vector (the owner's specific concern) — traced end to end:
- `GarmFlag.rationale()` (model free text, `BrandSafetyDtos.java:53`) → `SafetyCheck.detail`
  (`DeliverableSafetyReviewService.java:192`) → returned to the brand. It is **not** re-run through
  `SensitiveTextRedactor` on the return path (send-path only, `:128`). So a hostile caption *can*
  in principle steer the model to place attacker-chosen prose into `detail`. Confirmed real, matches
  F2.
- **This is closed at the render layer, verified in code.** `DeliverableSafetyReviewCard.tsx`
  renders `detail` **only** as `title={check.detail}` (`:132`) — a native browser tooltip, set by
  React as a literal DOM string attribute, never parsed or executed as HTML. The visible chip text
  is `check.label` (`:135`), which is **server-controlled** (the `GARM_CATEGORY_LABELS` map,
  `DeliverableSafetyReviewService.java:237-250`), not model output. Repo-wide `dangerouslySetInnerHTML`
  grep on this path: only hit is the **comment forbidding it** (`:120-126`). So the model text
  cannot become a script/HTML sink, and the injection→XSS→brand chain does not close.

**Residual (non-blocking, explicitly noted):** because `detail` is un-redacted model prose surfaced
to the brand, a hostile caption could make the tooltip *read* as a persuasive/phishing sentence
(plain text, no link, no markup, viewed by the brand about its own commissioned content). Severity
is low — it is a social-engineering-via-tooltip surface, not XSS and not a cross-tenant exfil (the
only reader is the brand that already owns the caption per Q1). Optional hardening: run the return
path `rationale` through `SensitiveTextRedactor` too, for send/return symmetry. Not a ship blocker.

### Overall verdict — **SHIP CONFIRMED**

All three owner questions **CONFIRM**. The one that could have flipped the gate (Q1's
brand-already-sees-caption fact) came back **TRUE**, so the info-barrier judgment is not just upheld
but now rests on the decisive code fact rather than the weaker "they commissioned it" argument. No
IDOR, no verdict-flip injection, no HTML/exfil sink at render. F1 (javadoc wording) and F2 (keep
`detail` render as plain text — already the case, `:132`) remain the only follow-ups; neither gates
this. **No change to the PASS gate.**
