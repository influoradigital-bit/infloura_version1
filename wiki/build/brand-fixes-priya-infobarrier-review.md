# Priya (CTO) — architecture / info-barrier review, Fix #3 deliverable-safety review

Scope: the ARCHITECTURE / info-barrier lock only (mine to own). Kabir owns the offensive
security surface in parallel (`wiki/build/brand-fixes-kabir-review.md`). Traced against the real
code, not the build doc's self-description. Verdict per question with file:line evidence.

Target: `DeliverableSafetyReviewService.getReview` → `BrandSafetyAiClient.classify` →
influora-ai `POST /internal/brand-safety`.

---

## Q1 — Info-barrier: does classifying the submitted-but-not-public caption cross the barrier?

**CONFIRM (feature owner's judgment is correct) — no barrier crossing, discloses nothing new.**

Decisive architectural fact, traced in the existing (pre-Fix-#3) system: the brand ALREADY holds
this caption through the normal deliverable-review flow it uses every day.

- `BrandDeliverableController.getDetail` → `BrandDeliverableService.getDetail`
  (`BrandDeliverableService.java:74-78`) → `toDetailResponse`
  (`BrandDeliverableService.java:202-219`) returns `deliverable.getCaption()`
  (**`BrandDeliverableService.java:212`**) directly to the brand, alongside the files
  (`:211`, presigned R2 GET URLs), hashtags (`:213`), creatorNotes (`:214`) and reviewNotes.
  The brand fetches this the moment a deliverable is SUBMITTED — that is the entire review UI.
- The deliverable in `getDetail` is resolved through the SAME finder Fix #3 uses —
  `requireBrandDeliverable` → `findByIdAndWorkspaceId` (`BrandDeliverableService.java:187-196`).
  So the exact caption Fix #3 forwards to the classifier is byte-for-byte the caption the brand can
  already read on screen via `getDetail`.

Therefore Fix #3 derives a *safety read* from data the brand already possesses. It is not a new
pre-publication disclosure of creator content — the disclosure already happened (legitimately, by
design) the instant the creator submitted the deliverable into the brand's own review queue. A
classifier verdict computed from data the brand can already see leaks nothing the brand didn't
already hold.

**Within-relationship, not cross-tenant — CONFIRM.** The classify request carries exactly two
things: `workspace.getId()` (the caller's OWN workspace, server-derived — see Q2) and ONE
`ContentItem` built from this one deliverable's caption
(`DeliverableSafetyReviewService.java:130,134`). No batch, no aggregate, no other creator's or
workspace's data enters the path. The `findByIdAndWorkspaceId` join-through
(`DeliverableRepository.java:49-54`: `Deliverable → Collaboration → Campaign → workspaceId`) means
the row is reachable only if it belongs to a campaign THIS brand's workspace commissioned and pays
for. The info barrier I own is about OTHER counterparties' data (cross-tenant / market aggregate /
k-anon cohort reads); this is the brand↔its own commissioned creator, entirely inside the
contracted relationship. The barrier is not implicated.

**Architecturally the safest possible shape:** it reuses the caption already inside the brand's
trust boundary rather than reaching for any new field. Even the pre-publication-timing concern
(caption scored before the post is public) is moot — the brand already reviews that same
pre-public caption to approve/reject it. The safety read is strictly narrower than the review
access the brand already has.

---

## Q2 — Authorization boundary: is reusing `findByIdAndWorkspaceId` the correct authz reuse?

**CONFIRM — correct reuse, no new gate needed, deny-by-default holds, no new column auto-flows.**

- Same boundary as every sibling read. `DeliverableSafetyReviewService.getReview`
  (`:106-115`) resolves the workspace from the principal via
  `brandContext.requireBrandWorkspace(principal)` then loads the row with
  `findByIdAndWorkspaceId(deliverableId, workspace.getId())` — identical to
  `BrandDeliverableService.requireBrandDeliverable` (`BrandDeliverableService.java:187-196`) used
  by `getDetail`/`approve`/`revise`/`reject`. This is the correct architectural move: authz is not
  re-implemented, it is the SAME join-through boundary already enforced and tested for the
  deliverable surface. A new bespoke gate would be the wrong call — it would be a second authz
  code path to keep in sync with the first, i.e. drift risk.
- Deny-by-default preserved. `requireBrandWorkspace` (`BrandContextService.java:43-65`) first
  enforces `UserType.BRAND` (creator → 403) then derives `workspaceId` from the JWT
  (`principal.getWorkspaceId()`, `:45`) or the active membership (`:47-57`) — never from a
  path/query/body param. A foreign/unowned deliverable id yields `Optional.empty()` →
  `DELIVERABLE_NOT_FOUND` 404 (`:110-115`) BEFORE any classify call. No IDOR surface, and the
  server chooses the tenant, not the client.
- No new column auto-flows. The read consumes exactly one existing field — `deliverable.getCaption()`
  (`:117`) — which the brand-facing DTO already exposed. The response DTO
  (`DeliverableSafetyReviewResponse`, built at `:158-163`) is a derived verdict + 10 GARM statuses +
  a numeric score + timestamp; it emits no additional deliverable/creator field. Additive and
  narrow.

---

## Q3 — Consistency with SR-1 / SR-2 locks

**CONFIRM both — verdict is server-derived (SR-1), untrusted caption handled before the AI call
(SR-2). No new self-reported-as-verified path.**

- **SR-1 (no trust of model output for a gating decision):** the verdict is 100% Java-computed.
  The model returns only per-category `risk` enum + sentiment + a 0-100 score; `toCheckStatus`
  (`DeliverableSafetyReviewService.java:197-207`) maps the enum to PASS/WARNING/FAIL and
  `deriveVerdict` (`:211-228`) folds those to PASS/REVIEW/FAIL in Java. There is no field on
  `ClassifiedItem` the model can populate to assert a verdict — worst case a hostile caption makes
  the model *wrong about a risk tier* (a model-accuracy limit), never *flips a control*. This
  mirrors the SR-1 posture: control decisions are server-derived, model output is an input signal,
  not the decision. And architecturally it can't gate anything anyway — it is a pure
  `@Transactional(readOnly = true)` GET (`:104`), advisory-only, and nothing in
  submit/approve/revise/reject calls it (Kabir Invariant 4, confirmed).
- **SR-2 (untrusted input handled before the AI call):** the caption is untrusted creator free
  text. It was `TextSanitizer`-stripped at submit time (`Deliverable.applySubmit`), then
  `SensitiveTextRedactor.redact` runs on it (`:128`) BEFORE it leaves this service, and the
  influora-ai side enforces forced `tool_choice` + `_validate_model_result` server-side (Kabir
  Invariant 3, traced to `brand_safety.py`). Layered untrusted-input handling is present at both
  the Java egress and the Python ingest.
- **No new self-reported-as-verified path:** the verdict is NOT self-reported by the creator or by
  the model — it is server-computed from enum-validated categories. This does not reintroduce the
  self-report anti-pattern the SR locks exist to prevent.

---

## Architecture verdict

**CONFIRM the feature owner's (Vikram's) judgment — SHIP-cleared on the architecture/info-barrier
axis.** This is the brand deriving a safety signal from a caption it already legitimately holds
inside its own workspace boundary, for its own commissioned deliverable, using the reused
tenant-scoped authz finder, with a server-derived verdict. It does not cross the info-barrier lock
I own. No barrier redesign or separate justification is required — the disclosure the owner worried
about does not exist, because the brand already has the data.

**Agreement:**
- I **AGREE** with the feature owner's judgment (`brand-fixes-backend.md` #3, the flagged
  "does NOT cross the info barrier" call). Confirmed, and I strengthened the reasoning: the
  decisive fact is not merely "the brand commissioned it" but "the brand already receives this exact
  caption via `getDetail` (`BrandDeliverableService.java:212`)".
- I **AGREE** with Kabir's PASS on Invariant 1 (info-barrier) and his overall SHIP. Our two
  independent traces converge on the same conclusion from different angles (his: response leaks
  nothing beyond this deliverable's own verdict; mine: input was already brand-held). Kabir's F1
  (correct the "structured-data-only" javadoc — `rationale` IS model free text returned to the
  brand) and F2 (FE must render `detail` as plain text, not innerHTML) are both correct and
  non-blocking; I concur they don't gate the architecture. Note the current javadoc
  (`DeliverableSafetyReviewService.java:56-61`) already acknowledges F1/F2 inline.

**Disagreement flag for Swapnil: NONE.** Kabir and I agree. No escalation required.

**Non-blocking (mine, for the record, not a ship gate):** point 6 in the build doc — every GET is
a fresh uncached Claude call. That is a cost/latency concern, not an architecture or barrier
concern. Fine for first pass; revisit caching-per-deliverable-version if usage justifies it. Owner:
Vikram/Rohan to watch cost.

— Priya, CTO, 2026-07-22
