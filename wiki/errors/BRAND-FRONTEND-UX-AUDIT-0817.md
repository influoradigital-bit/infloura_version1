# Brand frontend — deep UX audit (2026-08-17)

> **STATUS — updated 2026-08-17, after remediation task `T-CRIT9-0817`.**
> All 9 CRITICALs (`F-0235`…`F-0243`) are **fixed and closed against per-record gates**, each
> verified to exit 1 on the pre-fix revision `358b49e` and 0 on the fixed code. `tsc` clean on
> every file changed here. Independent review by `priya` (fresh-context) renders **`believed`**.
> Remediation detail, the two defects that survived their own gates, and the larger finding the
> repair uncovered are in **[Remediation](#remediation--t-crit9-0817)** below. The audit body is
> preserved unedited as the record of what was found.
>
> **HIGH — separate run, task `T-BRANDHIGH-0817`.** **All seven (`F-0244`…`F-0250`) are now
> closed against gates**, `F-0248` last after its first fix was rejected on review.
> `VALID · 7 rows · alignment 92.9% · proved 85.7% (6/7 scored)`. Two fixes passed `tsc` and
> their own specs and were still wrong — see **[HIGH remediation](#high-remediation-2026-08-17)**.
>
> **Dead controls — task `T-F0289-DEADCTL`, verdict `proved` (oracle).** Every enabled button that
> did nothing is now wired or honestly disabled; `gates/F-0270-no-dead-controls.py` exits 0 over 298
> files. Read that section for the part that matters — **23 of the gate's own 39 first-run findings
> were false positives**, and fixing the gate mattered more than fixing the buttons.
> **Then it happened again (`F-0310`):** the gate accepted `onClick={e => e.preventDefault()}` as
> wiring, and accepted `aria-disabled` as `disabled` because it tested a bare substring. Three
> still-clickable controls shipped through it as "honestly disabled" — the exact blind spot this
> task's own verdict had declared. Caught by a human reading the diff, not by any check.
>
> **MEDIUM — task `T-BRANDMED-0817`, verdict `proved` (oracle).** The thirteen MEDIUM clusters below
> were prose, not records, so none had an exit test. They are now `F-0253`…`F-0265`, **all closed
> against per-record gates**, each verified by the dispatcher to exit 1 against the pre-fix tree
> *and* against a constructed wrong fix. Two rounds of fresh-context review by `priya` opened five
> more (`F-0292`…`F-0296`), including **one fix that was actively wrong on the legal surface** and
> **one gate that greened its own defect class**. See
> **[MEDIUM remediation](#medium-remediation--t-brandmed-0817)**. Shipped in `6643cb9` and `f4f4ed6`.
>
> **OPEN RECORDS — task `T-BRANDOPEN-0817`, verdict `proved` (oracle).** The eleven records this
> document still listed as open (`F-0229`, `F-0251`, `F-0252`, `F-0266`, `F-0271`, `F-0272`, `F-0273`,
> `F-0279`, `F-0282`, `F-0283`, `F-0301`) are **closed against gates**, together with seven more the
> reviews opened. **`F-0283` is done** — contract terms are captured, stored, returned, printed into
> the signed PDF and immutable once created — so the "largest open item in this document" no longer
> is. Two rounds of fresh-context review found five defects, and a gate sweep found that **two closed
> records were closed against gates that green their own defect**. See
> **[Open-records remediation](#open-records-remediation--t-brandopen-0817)**.

**Question asked:** how does the brand flow actually work for the person using it — do they get
confused, does it work properly?

**Method.** proof-os `/work` · verb AUDIT · task `T-BRANDUX-0817`. Oracles first (law 4), then four
**fresh-context** checkers, each dispatched with artifact paths and the `done_when` only — no prior
audit docs, no producer transcript, no framing (law 3 / SKILL §6).

**done_when:** each available oracle ran, findings are classed into ledger records with `missed_by`,
and the blind spots are named.

**Scored result (validate.py):** `VALID · 7 rows (3 scored, 4 echo excluded) · alignment 83.3% ·
proved 66.7%`. The 83.3% covers the **oracle** rows only. The four journey rows are **echo** and are
excluded — see *Why the findings score nothing* at the bottom. **Do not read 83.3% as "the brand app
is 83% working."** It means: the code compiles, has no leaked secrets, and mostly uses design tokens.

---

## Oracle results

| Oracle | Command | Result |
|---|---|---|
| tsc | `npx tsc --noEmit` | **0 errors** |
| gitleaks + npm audit | `gates/security.sh .` | **aligned (proved)** — 242 commits, no leaks; no high-severity deps |
| raw-hex grep | `gates/frontend.sh .` | **exit 1**. The gate truncates to `head -10`; the full grep over `src/pages` + `src/components/brand` returns 19. Most are legitimate platform brand colours (Instagram `#E4405F`, YouTube `#FF0000`) that cannot be tokens. Two are not: `features/hype.tsx:94,219` uses an off-palette teal `bg-[#0e7490]` for its primary CTA — not the committed brand violet `#7c6ae8` — and `contracts-and-deliverables.tsx:808` paints the signature canvas `#3b82f6`. |
| eslint (kavya ruleset) | `gates/frontend.sh .` | **exceeded 300s — unavailable** |
| build | `gates/build.sh .` | **HUNG past 623s, then DIED without recording an exit — unavailable, never green** |
| vitest (in-scope suites) | run by checkers | **53/53 pass** |

**The headline is that ratio.** Every defect below is live while tsc is clean and every existing test
is green. Type-safety and the current suite are structurally blind to this entire class: `?? fallback`
is type-correct, a disabled button beside a contradicting string is type-correct, two hardcoded arrays
that disagree are type-correct.

---

## The short answer: where a brand user gets confused

1. **They are asked the same three questions twice, with different answers offered.** Register asks
   company name / industry / team size; onboarding step 2 asks them again, blank, with a different
   vocabulary (`fashion` vs `Fashion & Apparel`, `1-5` vs `1-10 employees`), and overwrites the first
   set. Reads as "my submission was lost."
2. **Onboarding ends by telling them to choose, then refuses the choice.** "Pick where to go first:"
   renders two cards that are plain `<div>`s — no onClick, no link, no keyboard affordance.
3. **They cannot tell whether they are allowed to publish.** On one screen, `/brand/campaigns/new`,
   the banner says verification is required to launch and the KYC prompt says it is *optional and
   won't block your campaign*. Same backend action. Onboarding's exit copy takes a third position.
4. **An empty dashboard, a loading dashboard and a broken dashboard are the same screen.** There is no
   skeleton anywhere in `dashboard-page.tsx`. A brand whose API is down is told confidently, and
   permanently, that they have no pending deliverables, contracts or payment releases.
5. **The app greets nobody.** Nothing in the brand flow ever writes the auth store, so every live
   session says "Good morning, there" over a sidebar reading `Brand Account / brand@company.com` — a
   plausible email belonging to no one.
6. **Three surfaces show the same conversation with three different status words.** A `TERMS_AGREED`
   deal reads *Negotiating* on `/brand/chat`, *Accepted* on `/brand/deals`, and has no status on
   `/brand/messages`.
7. **They are shown money movements that did not happen.** Detailed below — this is the serious part.

---

## CRITICAL — money, legal and PII

Ledger records `F-0235` … `F-0250`. Every one carries a `missed_by`.
**All nine below are now CLOSED** — see [Remediation](#remediation--t-crit9-0817) for the gate
that closed each and for the two whose fixes are incomplete.

| id | where | what the brand is shown |
|---|---|---|
| **F-0235** | `src/pages/brand-chat.tsx:977` | A hardcoded shipping address — a real-looking person's name, phone and Mumbai street address — held in `useState` with **no `isApiLive()` guard**. `ShipmentForm` mounts prefilled with it and POSTs to the live `api.shipments.markShipped`. Fabricated PII on a live logistics action. |
| **F-0236** | `contracts-and-deliverables.tsx:1372` | The Contracts *Payments* tab is entirely hardcoded and ungated: "50% Upon Signing — **Paid**", "50% Upon Completion — **In Escrow**", transaction history dated *Jan 10, 2024*. A brand on a real contract is told half their money already went to the creator. |
| **F-0237** | `deal-contract-tab.tsx:216` | The panel headed **"Terms (read-only)"** renders a hardcoded 5-item list (6-month usage rights, 2 revision rounds) in live mode. The real contract *is* fetched — its terms are never passed to this panel. The **Sign** button sits directly beneath. |
| **F-0238** | `deal-contract-tab.tsx:59` | If the contract PDF fetch fails, the client **generates one from invented data** (brandName "Your Brand", invented deliverables, `now + 14 days`) and toasts only *"Opened a local copy."* Nothing says the document was fabricated. |
| **F-0239** | `deal-room-dashboard.tsx:356` | `runContractAnimation` walks a **non-dismissable** modal through *"Locking escrow funds"* → *"Generating contract"* on `setTimeout`, in live mode, **with no server call.** The brand is shown a confirmation that neither happened. |
| **F-0240** | `brand-new-campaign.tsx:122` | Choosing **"Direct Deal"** sets state that is never passed to the form. The payload omits `campaignType`, the backend defaults to `STANDARD`, and `campaignType` is **immutable post-creation**. The brand picks a private negotiation, permanently gets a public open campaign, and is told nothing. |
| **F-0241** | `VerificationRequiredBox.tsx:38` | On `WORKSPACE_NOT_VERIFIED` the create call **threw** — nothing was persisted — and the box asserts *"this campaign is saved as a draft, so nothing is lost."* The brand navigates to verification and loses the wizard. Same over-claim class as F-0226, which was fixed on the creator side only. |
| **F-0242** | `brand-disputes.tsx:71` | *"To open a new dispute, go to the relevant deal room."* **There is no such control** — not in `brand-chat.tsx`, not in `deal-room-dashboard.tsx`. The backend allows either party and the creator has a full flow. Only one side of a money dispute can escalate. |
| **F-0243** | `brand-kyc-prompt.tsx:190` | The contradictory-gating copy from point 3 above. |

## HIGH

**Remediated 2026-08-17** — task `T-BRANDHIGH-0817`, verb FIX. Six of seven closed by gate; see
*HIGH remediation* below for what each gate does and does not prove.

| id | where | symptom | status |
|---|---|---|---|
| **F-0244** | `useWorkspaceVerification.ts:48` | `isLoading` ignores the role query, so while it is in flight — or if it rejects, or if `brand_user_id` is missing — the workspace **Owner** gets the terminal *"Only an Owner or Admin can submit verification. Ask one of them."* No retry, no error state. The sole owner is told to ask themselves. | **closed** · gate · residual `F-0279` |
| **F-0245** | `dashboard-page.tsx:108` | loading == error == empty (point 4 above). | **closed** · gate |
| **F-0246** | `dashboard-page.tsx:107` | placeholder identity in every live session (point 5 above). | **closed** · gate · root cause `F-0282` |
| **F-0247** | `FundEscrowButton.tsx:324` | *"₹X secured. Released only on your approval."* renders during `initiating`, `awaiting_payment` (Razorpay modal open, nothing paid) and `verifying` — contradicting the component's own header contract that success is confirmed by server status `FUNDED`. | **closed** · gate |
| **F-0248** | `contracts-and-deliverables.tsx:1253` | The Sign button renders only for `status === 'pending_review'`, a value `mapApiContractStatus` **can never produce**. On the Contracts page in live mode there is no way to sign a contract; the whole sign dialog, canvas and legal notice is dead code. | **closed** · gate · first fix rejected as `F-0267` |
| **F-0249** | `brand-settings.tsx:132` | If `GET /workspaces/me` fails, the mock seed (`Tech Brands Co.` / `admin@techbrands.in`) stays in the fields and **Save stays enabled** — one click PATCHes fabricated values over the real workspace name and billing email. | **closed** · gate |
| **F-0250** | `creator-contract-mappers.ts:38` | `PENDING_SIGNATURES` maps unconditionally to `brand_signed`, but the backend reaches that state after **one** signature by *either* party, unordered. A creator-first signature makes the deal room tell the brand *"Sent to creator for signature"* and hide the Sign control — neither party can proceed. | **closed** · gate · first fix rejected as `F-0268` |

### Path note

The `where` column above is short-form and does not resolve. The real paths are
`src/hooks/brand/useWorkspaceVerification.ts`, `src/components/brand/dashboard/dashboard-page.tsx`,
`src/components/feature/meera/FundEscrowButton.tsx`,
`src/components/brand/contracts/contracts-and-deliverables.tsx`, `src/pages/brand-settings.tsx`,
`src/lib/creator-contract-mappers.ts`. Line numbers had also drifted from `358b49e` — the audit's own
re-anchor warning was correct and load-bearing.

---

## HIGH remediation (2026-08-17)

**Scored result (validate.py):** `VALID · 7 rows · alignment 78.6% · proved 71.4% (6/7 scored) ·
believed 1 · capped 0`. Six gates on disk under `.proof-os/gates/`; each re-runs a structural check
**and** its spec, so a revert fails the gate rather than passing quietly.

| gate | closes | legs |
|---|---|---|
| `F-0244-owner-self-lockout.sh` | F-0244 | `isLoading` covers both queries · `canVerify` gated on `roleResolved` and fails open · 5 specs |
| `F-0245-F-0246-dashboard-states-and-identity.sh` | F-0245, F-0246 | no fabricated identity on a live path · `LoadStatus`/`DashboardCardError`/retryable loader present · 5 specs |
| `F-0247-premature-success-copy.sh` | F-0247 | positive guard on `status === 'funded'` · the old negative guard has not returned · 6 specs |
| `F-0249-stale-seed-overwrites-real-data.sh` | F-0249 | seed is live-mode-gated · Save gated on a successful load · 2 specs |
| `F-0250-F-0268-pending-signature-deadlock.sh` | F-0250, F-0268 | mapper names no party · **both** parties' Sign gates accept the ambiguous member · 3 suites |
| `F-0248-unreachable-primary-action.sh` | F-0248, F-0267, F-0269 | no live `pending_review` reference · Sign gate is `(draft \|\| pending_signature) && !brandSigned` · mapper cannot emit the dead literal · 11 specs |

**Scored after the F-0248 repair:** `VALID · 7 rows · alignment 92.9% · proved 85.7% (6/7 scored)`.

### The ratio, again — and it held

Every fix passed `tsc --noEmit` and its own spec. **Two of them were still wrong**, and a
fresh-context review found both. That is the same structural blindness the audit's headline named,
reproduced one layer up: a spec written by the agent that wrote the fix inherits the fix's
misunderstanding.

- **F-0248's first fix** gated Sign on `pending_signature && !brandSigned` — reachable *only* when
  the creator signed first. `Contract.builder()` defaults to `DRAFT` (`Contract.java:232`) and
  `advanceIfFullySigned` reaches `PENDING_SIGNATURES` only *after* a first signature
  (`Contract.java:148-154`), so a freshly generated contract — precisely when the brand normally
  signs first — is still unsignable. `listForBrand` applies no status filter
  (`ContractService.java:823-838`), so those rows do reach the page. Recorded **F-0267**.
- **F-0250's first fix** mapped `PENDING_SIGNATURES → 'generated'`, which unblocked the brand and
  blocked the **creator** on the *common* ordering, rendering the false string *"Awaiting brand
  signature — {brandName} hasn't signed this contract yet."* The in-code defence that "callers
  replace this once the full contract loads" was checked and is wrong twice:
  `creator-chat.tsx:1025-1041` swallows a failed `GET /contracts/:id` and keeps rendering the coarse
  status **permanently**, and `enrichContractEvent` (`creator-chat.tsx:1510`) writes it into chat
  timeline metadata that `liveContract` never replaces. Recorded **F-0268**, since fixed.

**Why the specs missed both.** F-0248's spec hardcoded `status: 'PENDING_SIGNATURES'` in every case
and its property test asserted only that the gating literal is *a member of* the mapper's output set
— which `'pending_signature'` is. F-0250's spec asserted the mapper's **return value**; a
return-value assertion passes happily while both parties are locked out, because the string is right
and the consequence is elsewhere. The replacement gate asserts that **both** Sign controls are
reachable — a property, not a string.

### The full F-0250 fix

`DealContractStatus` gained a fifth member, `'pending_signature'`, meaning *awaiting signatures,
party unknown*. The mapper stops guessing; `canBrandSign`, `canSign` and `shouldShowSignButton` all
accept it. The consumer sweep found three call sites nobody had named: `types.ts:585`
(`TimelineEventMetadata.contractStatus`, which feeds the live `CreatorContractCard` — caught by tsc,
not by grep), `creator-contract-card.tsx`, and `deal-room-step-progress.tsx`, where an ambiguous
contract would have fallen through to phase `negotiate`.

### Records opened by the remediation

| id | class | where |
|---|---|---|
| **F-0252** | fe-be-enum-divergence | `types.ts:42` — FE `ContractStatus` invents `TERMINATED`/`DISPUTED`, which no backend state produces, and omits `CANCELLED`, which the backend sends. Branches on the invented two are dead; a real `CANCELLED` does not typecheck. |
| **F-0267** | incomplete-fix-common-path | `DRAFT` contracts still unsignable (above). |
| **F-0268** | deadlock-moved-to-other-party | F-0250's first fix (above). **Closed.** |
| **F-0269** | demo-mode-sign-regression | `mockContracts` contract-3 (`status: 'pending_review'`) lost its Sign button; the *"Comments need resolution before signing"* badge on the same literal never renders in live mode, so a brand with unresolved comments is never warned. |
| **F-0279** | dead-recovery-api | F-0244 exposes `roleError`/`retryRole` and **no consumer reads either**. The lockout is cured by failing open, not by the retry the record asked for. `retryRole` is also a fresh closure per render. |
| **F-0282** | dropped-identity-at-session-persist | `auth-session.ts:32` — `persistBrandSession` drops `data.user.displayName` where `persistCreatorSession` keeps it. This is the **root cause** of F-0246: `login()`/`setUser()` are called only from `creator-login.tsx:16`, `creator-register.tsx:22` and the demo panel, never in the brand flow, so `useAuthStore().user` is permanently `null` in a live brand session — not a race. |

### One audit blind spot closed

The audit listed *"whether `POST /deals/{id}/accept` funds escrow server-side"* as unresolved and
load-bearing. `DealService.accept()` (~L297) transitions deal/collaboration state and publishes
`ProposalAcceptedEvent`/`BidAcceptedEvent`; no `EscrowService` call appears in it. Escrow funds via
`POST /wallet/escrow/fund` (`useEscrowFund.ts:214`). **Partial trace, not exhaustive** —
`EscrowService.initiateFund` and `EscrowController.fund()` were not read in full. It reads as a copy
bug, not a money bug, but that is a lead, not a proof.

### What the six gates do NOT prove

- **Nothing was rendered and no backend was exercised.** Every gate is source plus vitest. These are
  proofs about the client's behaviour, not about the system.
- **F-0246 is closed by binding to the WORKSPACE identity**, not by recovering a person. The greeting
  still reads "Good morning, there" for every brand user; see F-0282.
- **F-0244 fails open**, so a genuine non-admin whose role query rejects now sees a CTA the server
  will 403. That makes the server-side guard load-bearing, and the verification-submit endpoint's
  authorization was **not** traced.
- **Whether the server accepts a signature from whichever party clicks first** is unexercised, now
  that both Sign controls are offered.
- **`tsc --noEmit` was green at the final check and was observed broken three times mid-run** by a
  concurrent session editing `contracts-and-deliverables.tsx` (F-0253 canvas removal) and
  `brand-settings.tsx`. On this tree a clean typecheck is a snapshot, not a state. The gate scripts
  are the durable artifact.
- **F-0248 was held, then closed once the other session's writes stopped** — `.f0248-backup.tsx`
  remains at the repo root as that session's artefact. The decisive fact came from the Java, not
  from the frontend: **`DRAFT` is signable.** No status guard exists in
  `ContractService#recordSignature` (`:526-574`), `#doRecordSignature` (`:628-678` — it checks only
  prior-signature idempotency and collaboration cancellation), or `ContractController#sign`
  (`:78-113`). The first repair's `pending_signature` restriction was **frontend-invented with no
  backend basis**, which is why reading the server was the step that closed this and reasoning from
  the component was the step that got it wrong twice.
- **Whether a DRAFT signature is SEMANTICALLY intended is not settled** — only that the server
  permits it. Those are different claims and the gate proves the second, not the first.
- **The "unresolved comments" warning is no longer dead but is still unproven**: it was regated from
  the unreachable `pending_review` literal onto real clause-comment data, which the *live* endpoints
  do not currently return. It is honestly hidden in live mode rather than falsely absent — but it
  warns nobody today.

## Notable MEDIUM

*Written up as prose because nothing here had a ledger record — which meant nothing here had an exit
test. **All thirteen are now ledgered `F-0253`…`F-0265` and closed against gates**; see
[MEDIUM remediation](#medium-remediation--t-brandmed-0817). The list below is preserved unedited as
the record of what was found, so the line numbers are still the pre-fix ones.*

- **Signature canvas is decorative** — strokes are drawn to a canvas that is never read; only the typed
  name is submitted, under *"legally bound … under the IT Act 2000."*
- **Demo OTP hint ships unconditionally** — `onboarding-steps.tsx:630` renders *"Demo mode: use code
  123456"* with no `isApiLive()` guard, on an unguarded route. The correct pattern exists twice
  elsewhere in the repo.
- **Terms and Privacy are dead buttons** on a mandatory consent checkbox (`brand-register.tsx:386`).
- **Live invite dropdown seeds three fake campaigns** (`Diwali Collection Launch 2024`, …) as initial
  state; a brand can submit against a nonexistent `campaignId` before the effect resolves.
- **"Create New Campaign" from the invite modal drops the creator** — the promised pre-selection is
  never read by the destination.
- **Client-invented fee split shown as money** — `budget.max * 0.82 / 0.10 / 0.018 / 0.062` behind a
  lock icon implying escrow, with no fee-schedule endpoint.
- **Discover has no loading state and no empty state**; four filters and every sort are client-side
  over one 20-row page, and creators with no rate set are silently filtered out by a default the user
  never touched.
- **Live creator profile prints fabricated zeros** — "0 Avg Likes", "Female 0% Male 0%" — in a file
  whose own comment calls a fabricated zero "the exact bug this ticket exists to close."
- **⌘K opens the command bar but cannot close it** (two listeners, `document` beats `window`), and it
  advertises ⌘C / ⌘F / ⌘W shortcuts that no handler implements — ⌘W closes the browser tab.
- **Settings says "changes apply to this session only"** while three of those toggles write to the
  server immediately.
- **Pipeline renders overdue SLA as "at risk: -37h remaining."**
- **Dead controls on the money surfaces**: wallet Export, Form 16A download, GST summary download,
  deal-room attach, deliverable Preview, "Continue Chat".

## Route reachability

31 `/brand/*` routes registered. **One true orphan: `/brand/deals`** — no file navigates to the bare
path, so `DealRoomDashboard`'s list view is URL-only. `/brand/settings/verification` is a near-orphan:
its only door is a banner that self-gates to `/brand/campaigns*`, is hidden once verified, and is
hidden from non-admins — so "why can't I publish?" has no discoverable answer from Settings. The
command bar reaches 7 of 15 in-app destinations; Pipeline, Analytics, Reviews, Disputes, Messages,
Meera, Help and Notifications all return "No results found."

**Contested, not a defect:** the sidebar label "Deals" pointing at `/brand/chat` is a documented D-8
decision (`brand-layout.tsx:90-97`) — `BrandChatPage` is the richer surface. What *is* a defect is
that the page actually titled "Deal Rooms" is the unreachable one, and that Pipeline navigates into
`/brand/deals/:id` while the sidebar highlights an item that leads somewhere else.

---

## A checker claim that did not survive checking

After its report, the first-run checker returned a follow-up asserting that `gates/frontend.sh` "exits
0 while printing `VERDICT: broken`", quoting line 36 as
`! grep -rEn --include='*.tsx' '#[0-9a-fA-F]{6}' src app components 2>/dev/null | grep -v tokens || fail=1`
and classing it HIGH against the trust layer — "a gate that cannot fail is not a gate".

**It is false.** The shipped gate has no such line. Line 36 is an argument-count check. The real hex
check is `hexout=$(grep …)` / `if [ -n "$hexout" ]; then … fail=1; fi` (lines 163-167), followed by
`[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }` (line 173). Measured
directly with `${PIPESTATUS[0]}`, the gate **exits 1**.

The likely origin is real and worth knowing: reading `$?` after piping the gate through `tail` returns
`tail`'s status, not the gate's — the same mistake this audit made on its own first invocation. The
checker then reconstructed a plausible source line to explain the number rather than opening the file.

Recorded here rather than in the ledger because it is a false finding, not a defect — but it is a
concrete instance of what the 0% catch rate already says about this service, and it is the reason the
findings above are framed as leads rather than proofs.

## Remediation — `T-CRIT9-0817`

Six expert agents, partitioned by file so no two wrote the same source, plus two fixes done
directly. `done_when`: *for each record, a gate file exists under `.proof-os/gates/` naming that
record id, exits 1 against the pre-fix code and 0 against the fixed code, `tsc --noEmit` stays at
0 errors, and `promote.py` closes the record against that gate.*

**Every gate was tested against both revisions before promotion — 9/9 exit 1 on `358b49e`, 0 on
the fixed tree.** A gate that only passes on the fixed code proves nothing; it has to fail on the
bug it claims to catch.

| id | what changed | gate |
|---|---|---|
| **F-0235** | Fabricated PII removed from live-reachable state. Address now derives from a real `GET /deals/:id/shipment`; the literal survives only as `MOCK_SHIPPING_ADDRESS`. The Ship control gates on a real address, so it cannot arm on invented PII. | `F-0235-no-fabricated-pii-live.sh` |
| **F-0236** | Payments tab split on `isApiLive()` — the Jan-2024 fixture now sits in the mock branch only. Live renders real `ContractApiRecord.milestones`. Escrow badge derived from milestone status instead of a constant `false`; `Hash: undefined` no longer prints. | `F-0236-no-mock-money-in-live.sh` |
| **F-0237** | Hardcoded clause list deleted. Panel fetches the real contract and renders it, with distinct loading and unavailable states; Sign gated on the fetched record. **Incomplete — see below.** | `F-0237-sign-over-real-terms.sh` |
| **F-0238** | Fabricated-PDF fallback removed from the live path; failures surface the real error. Invented data scoped to a demo-only binding. | `F-0238-no-fabricated-contract-pdf.sh` |
| **F-0239** | `setTimeout` escrow theatre confined to the demo branch and renamed `runDemoContractAnimation`. Live accept reports only *"Proposal accepted."* — the literal extent of what happened. Dialog made dismissable. Banner now cleared on deal switch, so a success claim can't attach to the wrong deal. | `F-0239-no-fake-money-progress.sh` |
| **F-0240** | The picked `campaignType` reaches the payload (create-only, since the backend makes it immutable). Verified end to end against `CampaignService.java` and the `CAMPAIGN_TYPE_TO_API` table. | `F-0240-campaign-type-reaches-payload.sh` |
| **F-0241** | *"saved as a draft, so nothing is lost"* replaced with the truth — the create call threw and nothing was persisted. "Save as draft instead" promoted to the primary button. | `F-0241-no-false-draft-reassurance.sh` |
| **F-0242** | `brandDisputes.open` added with `role: 'brand'` (the creator variant hardcodes `role: 'creator'`, which selects the wrong JWT slot). The previously dead overflow button in the deal-room header became the dispute entry point, gated on funded escrow so it cannot only-fail. Both documented 409s surface inline. | `F-0242-brand-can-open-dispute.sh` |
| **F-0243** | All three surfaces now agree with `CampaignValidator`: drafts are never blocked, publishing requires verification, and approval does **not** auto-publish drafts. A repo-wide sweep for the four false-promise phrasings returns zero hits. | `F-0243-verification-copy-agrees.sh` |

### Independent review — `priya`, fresh-context, verdict `believed`

Dispatched with artifact paths and the `done_when` only. She did not reuse this run's scratch
baselines — she built a clean `git worktree` at `358b49e` and re-ran every gate herself, confirming
9/9. She then found **two defects the fixes' own gates cannot see**:

Both are now **closed against falsified gates** (`F-0273-frozen-escrow-counts-as-locked.sh`,
`F-0272-demo-contract-fixture.sh`) — each verified to exit 1 against the pre-fix tree *and*, for
F-0273, against a constructed wrong fix that counts every milestone status so `RELEASED` money
would read as still held.

- **`F-0273` — frozen escrow read as unlocked.** `deriveEscrowFromMilestones` counted only
  `FUNDED`. A deal under dispute holds `FROZEN` money — held, not released — and the tile rendered
  **"Not Locked"**. The brand was told their money was not held at the exact moment a dispute froze
  it: the same false-money-statement class F-0236 was opened for. **Fixed** — `FROZEN` now counts as
  locked; `RELEASED`/`REFUNDED` correctly still do not.
- **`F-0272` — the F-0237 fix killed demo signing.** `api.contracts.get` resolved `null` in mock
  mode, so `contractRecord` was always null in walkthroughs, Sign was permanently disabled and an
  error rendered where the contract should be. **Fixed** with a demo `ContractApiRecord` fixture.

### The larger finding the repair uncovered — `F-0283`

Priya read F-0237's residue as a DTO gap: terms exist, they're just not exposed. Checking the
backend before acting showed worse.

- `ContractGenerateRequest` is `{collaborationId, milestones}` and nothing else
  (`MoneyDtos.java:255`).
- The `Contract` column **named `terms`** (`Contract.java:48`) is written with
  `sha256TamperHash(req)` → `{"tamperHashSha256":"<hex>"}` (`ContractService.java:284,937`).
  It stores a hash, not terms.
- `ContractResponse` exposes no terms field.

**The platform never captures, stores or returns contract terms at all.** A contract here is a
collaboration id, a payment schedule and two signature timestamps. The hardcoded clause list
F-0237 deleted was the *only* place usage rights, revision caps, exclusivity and arbitration ever
existed — UI fiction, with no field to expose in its place. Both parties e-sign under *"legally
bound under the IT Act 2000"*.

Fixed what needed no product decision: the heading now reads **"Payment schedule (from contract,
read-only)"** rather than "Terms", so a milestone list is no longer presented as the terms being
signed. `F-0283` supersedes the framing of `F-0271`, which assumed the terms merely needed
exposing. **Open decision for a human:** what a contract's terms should be, who authors them,
whether they are per-campaign or per-platform, and whether signed contracts get backfilled.

### Also opened during remediation

| id | class | why |
|---|---|---|
| `F-0251` | `missing-endpoint-hidden-by-mock` | No per-contract payment-history endpoint exists; milestones carry no funded/released timestamp and `wallet.escrowList` is not contract-scoped. The Jan-2024 fixture was concealing the gap. |
| `F-0251` | `silent-type-discard` | The template-apply path still drops `CampaignTemplateResponse.campaignType`, so a DIRECT template creates a STANDARD campaign — F-0240's failure through a second door its gate does not cover. |
| `F-0266` | `gate-cannot-tell-code-from-comment` | Two of the nine gates were decorative on first write: each forbade a string that the fix's own explanatory comment quotes. A third used `hasn.t`, where an ERE `.` matches one *byte* and the file's apostrophe is three — a gate that could never fire. All caught by running each against both revisions. |
| `F-0289` | `dead-control-repair` | `promote.py --recurrence` **blocked** on `dead-control` ×3 (`F-0270`, `F-0274`, `F-0276`). Wrote `gates/F-0270-no-dead-controls.py` — every `<Button>` must carry `onClick`, `asChild`, `type="submit"` or `disabled` — verified it catches all three records' sites, promoted detection, and opened this record for the 38 live instances it found. Per RETENTION §4 that closes detection, not the instances; some of the 38 are prop-forwarding wrappers needing triage. |

### Process notes worth keeping

- **A subagent claimed work it did not do.** `deriveEscrowFromMilestones` was introduced by commit
  `7a37c85` — a *concurrent session*, mid-run — not by the expert that reported adding it. The end
  state is correct; the attribution was not. A subagent report is not evidence.
- **A subagent ran `git stash` mid-task**, briefly reverting files another session was writing. All
  eight fixes and that session's work survived, verified individually; `stash@{0}` remains as a
  redundant duplicate snapshot.
- **`closed_by: human:swapnil` is a tool label, not human approval.** `promote.py` requires a
  signer. No person reviewed these closures; treat them as machine-closed.

## Dead controls — `T-F0289-DEADCTL`, verdict `proved` (oracle)

The audit listed a dozen buttons that render enabled and do nothing. Three sessions turned that
into ledger records (`F-0270`, `F-0274`, `F-0276`) until `promote.py --recurrence` **blocked** on
the class recurring ×3 with no gate. This task wrote the gate, then cleared what it found.

`done_when`: every flagged site is wired to a real handler, or rendered `disabled` with a stated
reason, or excluded as a documented false positive; the gate then exits 0 and `tsc` stays clean.
**Result: gate exits 0 over 298 files, `tsc` 0, deal-room suites 22/22.**

### The gate was wrong before the code was

First run flagged **39** sites. **23 were the gate's own false positives:**

- **21** were a `<Button>` nested inside an `asChild` trigger — `DropdownMenuTrigger`,
  `PopoverTrigger`, `SheetTrigger`, `DialogTrigger`, `AlertDialogTrigger`, `CollapsibleTrigger`.
  Radix clones the child and injects the handler, so a bare tag there is *correct* code. The gate
  inspected only the Button's own tag. It was flagging, among others, the dispute dropdown added
  the day before for `F-0242`.
- **2 + 2** were prop-forwarding wrappers spreading `{...props}` (`MagneticButton`) and the `ui/`
  primitives `calendar.tsx` / `input-group.tsx`.

A 59% false-positive rate is not a cosmetic problem. A gate that wrong gets ignored, and the ledger
then reports the class as covered while nobody reads the output — which is the same overclaim
FLOW law 5 exists to prevent, aimed at a gate instead of a verdict. Both causes were fixed before a
single control was touched: the gate now skips a Button preceded by an `asChild` trigger, and
treats a props spread as wiring.

### The 16 real ones

Each was resolved under a strict two-outcome rule — **wire it to a real action, or render it
`disabled` with a stated reason.** Adding a no-op handler was explicitly forbidden: it satisfies the
gate while preserving the lie.

| control | outcome |
|---|---|
| "Continue Chat" (`deal-room-dashboard.tsx`) | wired → `/brand/chat?deal=<id>`, param verified against `searchParams.get('deal')` |
| Timeline **Sign** (`contract-card.tsx`) | wired → the working sign panel already in the same card. This was the *default* branch a brand sees, since status falls back to `generated` |
| Accept / Counter / Reject (`proposal-card.tsx`) | wired → real `dealsApi.accept/reject/counter`. Counter grew an inline amount form, because the endpoint needs an amount the card never had |
| "Download Approved Version" | wired → the real R2 `submittedUrl` when present, disabled with a reason when not |
| "View Contract" (`brand-messages.tsx`) | wired → the real `?contract=` deep link |
| Deliverable **Preview** (`contracts-and-deliverables.tsx`) | wired → `submittedUrl` via the file's own `asChild` anchor pattern; renders only when there is something to open |
| "Set Primary" (`creator-wallet.tsx`) | live path was **already correct**; only the mock-mode demo cards were dead |
| Both chat paperclips (brand + creator) | **disabled with reason** — `messages.send` accepts only `{content, kind}` and `DealMessage` has no attachment field, so the capability does not exist |
| Creator deal-room overflow menu | **disabled with reason** — it opened no menu at all |
| Dev motion playground button | wired to a real press counter |

Two findings worth keeping. **Creator-wallet's "Set Primary" was never broken in live mode** — the
agent traced it to `wallet.setPrimaryPayoutMethod` and correctly refused to point the mock-mode
demo cards at the live payout facade, since this codebase deliberately never fabricates a real-money
destination. And the **dev playground button could not be disabled at all**: `disabled:pointer-events-none`
would have killed the `:active` animation the demo exists to show.

Opened: **`F-0301`** — a parity gap running opposite to `F-0242`. The brand's deal-room overflow
became a real dispute entry point; a creator still has to leave the deal room entirely to escalate.

### The blind spot this gate declared, and then shipped — `F-0310`

The verdict above listed *"a no-op `onClick` still passes this gate"* as a known blind spot. It was
not hypothetical. It was live in the same run that declared it, in three places.

The paperclip in `brand-chat.tsx` was reported as "disabled with a reason". The code was:

```jsx
className="h-10 w-10 shrink-0 opacity-50"
aria-disabled="true"
onClick={(e) => e.preventDefault()}
```

No `disabled` attribute. The control was fully clickable; a no-op handler swallowed the click, and
`opacity-50` made it *look* inert. The agent that wrote it was not careless — it faithfully copied
the pre-existing `D-12` pattern in `brand-messages.tsx`. **Two independent gate bugs let it through:**

1. `onClick` was accepted on presence alone, so a handler that provably does nothing counted as
   wiring.
2. `disabled` was tested as a bare substring — and **`aria-disabled` contains it**. So
   `aria-disabled="true"` alone satisfied the gate, despite `aria-disabled` being an announcement to
   assistive tech that leaves the control fully operable. The two attributes are not interchangeable
   and the gate treated them as one.

Both are fixed. The gate now requires a real `disabled` (`(?<!-)\bdisabled\b`) and rejects the known
no-op shapes (`() => {}`, `e.preventDefault()`, `e.stopPropagation()`, `void 0`, `undefined`,
`null`). Re-running it immediately surfaced a third instance nobody had reported — the emoji button
at `brand-messages.tsx:1050` — which is now genuinely disabled.

The gate's `NOT CHECKED` line was also corrected: it still advertised "a no-op handler passes" after
that stopped being true. A gate that misstates its own coverage is the same defect class as a UI that
misstates what it did.

**What this cost:** three controls shipped through a green gate, a verdict of `proved`, a commit, and
a push. What caught it was a human re-reading one line of the diff — not any automated check.

### What this gate does NOT prove

- ~~**A no-op `onClick` passes it.**~~ **Closed by F-0310** — the gate now rejects the known no-op
  shapes and requires a real `disabled` rather than `aria-disabled`. A handler that calls a function
  which itself returns early still passes, so this is narrowed, not eliminated.
- **Only `<Button>` is in scope.** Raw `<button>`, `DropdownMenuItem`, and Card-level `onClick` are
  invisible to it.
- **`asChild` is now trusted, not followed.** A trigger whose own handler is missing would pass.
- **Nothing was clicked.** A control that is wired but throws at runtime still passes.
- **"Disabled with a reason" is honest, not necessarily right.** File attachments and creator-side
  escalation are gaps a human may want *built* rather than explained.

## MEDIUM remediation — `T-BRANDMED-0817`

**Scored result (validate.py):** `VALID · 23 rows · alignment 56.5% (capped) · proved 13.0%
(3/23 scored) · believed 20 · capped 18`.

**Do not read 56.5% as a quality figure.** Eighteen of those rows are gate rows whose evidence *is* a
deterministic script, and they still cap at `believed` because `ananya` and `vikram` carry
`may_claim: believed` in the registry — a producer cannot self-certify green behind any oracle. Only
`meera`'s three rows (`tsc`, `mvn`, `vitest`) render `proved`. `priya`'s sign-off caps at `believed`
too: a model review is not a deterministic check, and this is the OS refusing to let a person's
approval render as proof.

Each of the thirteen was re-anchored against live source before any fix — the audit's line numbers
were read at `358b49e` and had drifted, though every one of the thirteen defects was still real.

| gate | closes | what it asserts |
|---|---|---|
| `F-0253-signature-canvas-submitted.sh` | F-0253 | the signature control submits what the copy promises — canvas removed, typed name is the only claim |
| `F-0254-demo-hint-guarded.sh` | F-0254 | the demo OTP hint renders only when `isApiLive()` is false · 2 specs |
| `F-0255-consent-links-live.sh` | F-0255 | Terms/Privacy are real focusable links to routes `App.tsx` actually serves · 2 specs |
| `F-0256-no-mock-campaign-seed.sh` | F-0256 | the invite selector never offers a campaign the server does not have · disabled while in flight |
| `F-0257-invite-creator-handoff.sh` | F-0257 | the dialog no longer promises a handoff the destination does not consume |
| `F-0258-no-client-invented-fees.sh` | F-0258 | no client-invented split renders as money; the real fee endpoint is wired |
| `F-0259-discover-loading-empty.sh` | F-0259 | real loading state, real empty state, and an untouched price filter cannot hide unpriced creators |
| `F-0260-absent-metric-not-zero.sh` | F-0260 | an absent metric renders absent, never as a measured `0` |
| `F-0261-command-bar-chord-and-shortcuts.sh` | F-0261 | the chord closes as well as opens · no shortcut is advertised without a handler · 8 destinations verified against the router |
| `F-0262-settings-persistence-copy.sh` | F-0262 | the disclaimer names only the two controls that genuinely do not persist |
| `F-0263-no-negative-sla.sh` | F-0263 | a past deadline renders overdue, not negative hours · 3 specs |
| `F-0264-no-dead-money-controls.sh` | F-0264, **F-0296** | each named control is *unconditionally* disabled and carries a readable reason · wires in its own suite |
| `F-0265-brand-palette-tokens.sh` | F-0265 | no raw hex in the two files; CTAs on `bg-primary`, dark-mode safe |
| `F-0292-signature-name-persisted.sh` | F-0292 | the **server** chain — DTO field, entity columns, service threading, controller reads `body.name()`, migration · runs the Java suites |
| `F-0293-consent-link-preserves-form.sh` | F-0293 | following Terms cannot discard the in-progress registration |
| `F-0294-server-fee-copy-rendered.sh` | F-0294 | the server's `copy` field reaches the screen; no client sentence stands in for it |
| `F-0295-absent-audience-surfaces.sh` | F-0295 | an empty audience array renders an absent state, not an empty box |

### The ratio held a third time

Every one of the thirteen fixes passed `tsc` and its own new spec. A fresh-context `priya` found
**five defects**, one of them a fix that made the original problem *worse*:

- **F-0253 was fixed backwards (`F-0292`).** The producer deleted the decorative canvas and then
  sharpened the copy to *"By typing your full name … you agree to be legally bound … under the IT Act
  2000."* But `record ContractSignRequest(String role)` had no `name` field — Jackson dropped it,
  `ContractController#sign` read only `body.role()`, `recordBrandSignature()` wrote a timestamp, and
  `Contract` had no column for it. The fix narrowed a two-part overclaim to a one-part overclaim and
  then made the surviving half *more specific about a value the server discards.* Fixed for real in
  `f4f4ed6`: the name now travels, lands in `brand_signer_name`/`creator_signer_name`, and comes back
  on the response. The creator path was threaded too — it had ignored the body entirely.
- **F-0255 introduced a new defect (`F-0293`).** Plain `<a href="/terms">` with no `target` is a full
  document load in this SPA, and the links sit in the step-2 block — reading the Terms discarded the
  whole registration. The fix had copied the identical shape from `creator-register.tsx:280`.
- **F-0258 fetched the server's authoritative copy and ignored it (`F-0294`)**, substituting its own
  *"charged on real spend"* — a claim `BrandPlatformFeeService` does not make — and branching on a
  `source` value the service never constructs.
- **F-0260 left two more absent-metric surfaces (`F-0295`)** — `ageGroups` and `topCities` — three
  lines from the one it fixed.

### The finding that matters most — `F-0296`

The second review found **no product defects** and instead demonstrated, by construction, that a gate
was worthless. Priya set `String signerName = null;` in `ContractController#sign` — record F-0292
verbatim, with the DTO, entity, migration and service threading all still in place — and
`F-0292-signature-name-persisted.sh` printed **`VERDICT: aligned (proved)`, exit 0**. Its only
controller leg checked that the string `"BRAND"` still appeared.

The same class had already appeared once in this batch: `F-0264`'s first gate grepped a ±10-line
window for the bare token `disabled`, and the pre-fix button was *already* `disabled={loading}` — so
a fix that kept the conditional and added a tooltip would have gone green while the control stayed
clickable.

**A gate that only fails against the pre-fix code is half a gate.** Both were hardened until a
*constructed wrong fix* fails them, and both now run the passing suite that already existed and that
neither had been invoking. The dispatcher re-ran Priya's injection against the hardened gate and
observed exit 1 directly, restoring the file byte-identically afterwards.

`promote.py --recurrence` now reports **`false-green-gate-blind-spot` at ×2 with no gate of its own**
— the one open process item from this task. Both instances were caught by a reviewer and repaired by
hand; nothing in the repo would catch a third.

### What the gates do NOT prove

- **Nothing was rendered.** All seventeen are static checks plus vitest; no browser, no live backend,
  and there is still no brand E2E anywhere in this repo.
- **The migration has never been applied.** No MySQL here, so Flyway never ran `V20260817130000`; the
  dialect verdict is read off the SQL against `V10`'s DDL, and the Java suites mock the repository.
- **`F-0295` only exercises the absent path.** A fix that printed "Not available" unconditionally
  would pass both the gate and the spec.
- **Two controls in `F-0264`'s class were out of scope** — deal-room "Continue Chat" and the message
  attach button. `deal-room-dashboard.tsx` was carrying the in-flight `F-0239` work, so it was left
  alone. Exact fixes are recorded in the ledger: `onClick={() => setActiveTab('messages')}` for the
  first, disable-with-reason for the second.
- **Nothing renders the new signer names.** `brandSignerName`/`creatorSignerName` come back on the
  response and no frontend surface reads them.
- **`eslint` and `build.node.sh` remain unavailable, not green** (`F-0229`), declared as skips.

### A note on the commits

`ContractService.java`, `ContractServiceTest.java` and `EscrowServiceTest.java` carried both this
task's F-0292 change and a concurrent session's in-flight ApplicationHistory work, whose supporting
classes are still untracked. `f4f4ed6` therefore contains a **filtered** version of those three —
HEAD plus only the F-0292 hunks — built and verified out of tree, then staged as blobs so the other
session's working copies were never written to. The hunk filter was wrong twice and the *compiler*
caught both times; neither would have been visible to review by eye.

## Open-records remediation — `T-BRANDOPEN-0817`

**Scored result (validate.py):** `VALID · 23 rows · alignment 50.0% (capped) · proved 8.7% (4/23
scored) · believed 19 · capped 18`. As with the MEDIUM run, the cap is the registry refusing to let a
producer self-certify: eighteen rows carry a deterministic script oracle and still render `believed`
because `ananya`, `vikram` and `dev` are capped there. **The number says who is allowed to claim, not
how good the work is.**

Eighteen records closed, each against a gate that exits 1 on the pre-fix tree **and** on a constructed
wrong fix — the latter now a standing requirement in this project, for the reason below.

### `F-0283` is closed — and it had a fourth hop nobody counted

The record's own symptom named four places contract terms did not exist: "no request, no column, no
response **and no PDF input**." The first fix closed three. A fresh-context review found the fourth
still open: `ContractPdfService.addTerms()` emitted a section headed **"Terms"** containing only
amount, status and dates — in the PDF that is rendered, stored to R2 and **emailed to both parties as
the executed contract**. Three-quarters of a fix on a legal surface reads as a whole one.

Now: `contracts.terms_text` (`V20260817140000`), `ContractGenerateRequest.terms`, returned on
`ContractResponse`, printed into the signed PDF, and immutable — `updatable = false` plus no mutator
and no HTTP surface. The length cap was also wrong in a way only arithmetic catches: `@Size(max=20000)`
counts **characters** against a `TEXT` column of 65,535 **bytes** on `utf8mb4`, so 20,000 emoji is
80,000 bytes and a 500 at insert. The cap is now derived from the column.

What is still **not** decided, deliberately: what the terms should *say*. That is a product and legal
call. The mechanism exists; no default template was invented.

### Two closed records were closed against gates that cannot fail

This is the finding worth carrying out of this task.

- **`F-0273`** (opened as `F-0319`). Its promoted gate greps the function body for the substring
  `FROZEN`. A *later, unrelated* task added a line containing `FROZEN`, and from that moment the gate
  was green forever. Restoring the original defect — `held` counting only `FUNDED`, so disputed money
  renders "Not Locked" — still printed `VERDICT: aligned (proved)`, exit 0. Reproduced directly, twice.
- **`F-0236`** (opened as `F-0323`), worse. One leg grepped for `deriveEscrowFromMilestones`, which the
  function's own *definition* satisfies forever — deleting the call and hardcoding `escrowLocked:false`
  left it passing. The other leg demanded a `liveApi ?` ternary that a later task deliberately removed,
  so it was emitting a **false red** asserting the exact shape `F-0251`'s gate forbids. Two closed
  records pointing at gates in direct contradiction over one file.

Both repaired. The `F-0273` gate now **self-falsifies on every run**: a known-bad implementation is
frozen into the gate and pushed through its own assertion table first, and if the assertions pass it,
the gate exits 1 saying it cannot fail rather than reporting anything about the real code. That device
is the transferable part; nothing else in the tree has it yet.

A sweep classified all **106** promoted gates on disk. 81 have an execution leg; 23 are purely textual,
of which **13 assert a token inside source as a proxy for a behaviour** — the suspect shape. Only **2
were falsified by observed injection**, and both were real defects. **11 are named and unrepaired.**
Neither heuristic clears a gate: `F-0264` had an execution leg and `F-0296` still got through it, and
`F-0319` was single-site until the day it wasn't.

### The comment-blindness sweep, and a gate that could not speak

`F-0266` was not one file. A gate that greps raw bytes fails the fix whose own comment quotes the
string it forbids — so documenting a fix breaks its own gate, and the pressure that creates is to stop
documenting fixes. **122 gates checked, 53 fixed**, routed through a new shared `_code.sh` helper.
Two things fell out of it: `_strip_comments.py` crashed on cp1252 for any file containing `₹` or `→`,
which had been **silently exempting every money surface**; and `F-0304` crashed printing `≤` in its own
verdict (`F-0325`) — a gate that cannot emit its verdict is indistinguishable from one reporting a
finding.

### `build.sh` had never returned a verdict in this project's history

Not the timeout the record alleged — that does not reproduce; solo `npm test` is 99s against a 300s
budget. The real defect: `env_issue()` matched the bare token `network` against test output, and two
suites deliberately log `Error: network down`, so **a red suite was laundered into `unavailable` on
every run, in both directions.** It now reaches a real verdict, serialises its expensive leg behind an
advisory lock, and reports per-leg. A starved budget still yields `unavailable`, never a pass.

### What is red at close, and whose it is

`gates/build.sh` exits **1**, and tree-wide vitest is **4 failed of 683**. All four are in
`deal-room-dashboard-actor-refresh` / `-view-profile`, from another session's uncommitted `F-0328` /
`CR-97` work on a file held off-limits to every producer in this task. Every suite in this task's scope
passes; the tree as a whole does not. That distinction is the honest one, and the gate is right to be red.

### Left open on purpose

- **`F-0324`** — `brand-wallet.tsx` held a `loading` state written twice and read nowhere. The dead
  code is gone; the gap it reveals is that a money surface has **no loading affordance at all**, the
  same class as `F-0245`, which was closed for the dashboard only. Ledgered rather than silently
  building new UI on a wallet.
- **`F-0326`** — the dispatcher's own defect. Producers were grouped by file ownership, but a
  gate-repair agent must *inject into* the product file it guards in order to falsify a gate. Its
  restore-from-backup reverted a peer's fix to `contracts-and-deliverables.tsx`; the tests and the gate
  survived, the source did not. Caught only because the dispatcher re-ran `tsc` and vitest instead of
  trusting two reports that were both accurate when written.

## Why the audit's findings score nothing (and the review's now do)

`independence.py` measures kavya at a **0.0% catch rate (0 of 21 discoverable failures flagged
first)**, which caps the service at `echo` regardless of isolation. The four audit checkers *were*
dispatched fresh-context — artifact and `done_when` only — but a granted ceiling cannot exceed the
measured one, so all four rows are excluded from the denominator and none renders green.

Read that as: **the 85 audit findings are leads with file:line, not proofs.** What makes them
checkable is that each is a ledger record naming what should have caught it. What raises them is a
gate — which is exactly what remediation produced for nine of them.

**Priya's ceiling was raised on 2026-08-17, by human instruction.** Two facts made it legitimate
rather than convenient:

- `independence.py` measures her at **172 judgment events, 150 of them fresh-context, catch rate
  6.9% (2 of 29)** — deriving `believed`, while the registry granted only `echo`. The grant was
  *below* what the evidence supported.
- Raising `may_claim` alone was **refused by `registry_render.py`** (exit 1): the registry's
  documented `isolation` for priya still read `shared-context`, which derives `echo`, and a grant
  may only lower a derived ceiling, never raise it. That declaration was stale against 150
  journalled fresh-context dispatches; correcting it made the gate pass at
  `believed / fresh-context / believed`.

So her review of the nine CRITICAL fixes renders **`believed`** and counts. It still is not green —
`believed` is a model verdict, and no oracle confirmed either the two defects she found or the two
fixes made in response. Backup of the pre-change registry: `.proof-os/registry.pre-priya-raise.bak.json`.

## Blind spots

- **Nothing was rendered.** No browser, no live backend. Every UX finding is source-derived.
- **Mock-vs-live is unverified.** `api.ts` fails *open* to mock on a missing or misspelled
  `VITE_API_MODE`; deployed env files were not read, so the mock-leak findings may be wider or
  narrower in production.
- ~~**Whether `POST /deals/{id}/accept` funds escrow server-side is unresolved**~~ — **partially
  closed 2026-08-17** during `T-BRANDHIGH-0817`. `DealService.accept()` (~L297) transitions state and
  publishes accept events; no `EscrowService` call appears in it, and escrow funds via
  `POST /wallet/escrow/fund`. It reads as a copy bug, not a money bug — but `EscrowService.initiateFund`
  and `EscrowController.fund()` were not read in full, so this is a lead, not a proof.
- **Backend authorisation for `/brand/analytics/:creatorId` was not read.** No client-side ownership
  check exists; a brand can type any creator UUID.
- **eslint and the build gate never returned** — kavya's promoted ruleset and the build are
  *unavailable*, not green. **The build gate was not re-run after remediation either**, so the
  production build is unverified for every change in `T-CRIT9-0817`.
- **Line numbers were read at `358b49e` on `fix/brand-audit-remediation`.** Another session edits this
  repo; re-anchor before any fix lands.
- **9 of 13 oversight artifacts have no test file at all**, and `e2e/` contains only creator specs —
  there is no brand E2E anywhere.

### Added after remediation

- **No fix was observed running.** The nine gates are static checks over source. `F-0272`'s revived
  demo signing flow and `F-0273`'s frozen-escrow tile were reasoned from code paths, not rendered.
- **The gates check the shape that was fixed, not the class.** Priya demonstrated two cases —
  `F-0236`'s `FROZEN` gap and `F-0237`'s missing terms — where the defect survived in a form its own
  gate could not see. Assume the same is possible for the other seven.
- **Tree-wide `tsc` was red at the close of this task** — 5 errors, all in the concurrent session's
  in-flight creator work (adding a `'pending_signature'` union member: the mapper was updated, the
  union in `creator-contract-card.tsx` was not). Zero errors in any file changed here.
- **`F-0289`'s 38 dead controls are unreviewed.** The gate flags them; some are prop-forwarding
  wrappers. Nobody has triaged which are real.
- **`F-0283` is unresolved and is the largest open item in this document.** Contract terms are not
  captured, stored or returned anywhere in the platform, while both parties e-sign under a statutory
  binding notice. Fixing it is a product decision, not a code change.

### Added after MEDIUM remediation

- **A gate greened its own defect class, twice** (`F-0296`). Assume it is true of gates in this
  document that were never tested against a constructed wrong fix — which is most of them.
- **`tsc` was tree-wide clean at the close of this task** (0 errors) and the full suite passed
  (100 files / 632 tests), but the concurrent session broke and then fixed `mvn` test-compile
  underneath this run. Both readings are recorded; the final one is exit 0.
- **The repo moved four commits under this task.** One intermediate reading in this session was
  wrong because of it. Line numbers cited in the MEDIUM remediation section were true at `6643cb9`.

---
*proof-os 0.4.2 · audit `T-BRANDUX-0817` — verdict `echo` (gated, admissible) · ledger F-0235…F-0250*
*· CRITICAL remediation `T-CRIT9-0817` — verdict `believed` (priya, fresh-context, gated);
F-0235…F-0243 closed by gate, 9/9 verified fail-on-baseline; opened F-0251, F-0266, F-0272, F-0273,
F-0283, F-0289; **F-0283 open and unresolved***
*· HIGH remediation `T-BRANDHIGH-0817` — verdict `proved` (oracle, admissible); F-0244…F-0247,
F-0249, F-0250 **and F-0248** all closed by gate; opened F-0252, F-0279, F-0282*
*· dead controls `T-F0289-DEADCTL` — verdict `proved` (oracle, admissible); F-0270, F-0274, F-0276,
F-0289 closed by `gates/F-0270-no-dead-controls.py`, which exits 0 over 298 files after 23 of its
own first-run false positives were fixed; opened F-0301. **F-0310** closed against the same gate
after it was found accepting a no-op `onClick` and treating `aria-disabled` as `disabled`*
*· open-records remediation `T-BRANDOPEN-0817` — verdict `proved` (oracle, admissible); F-0229,
F-0251, F-0252, F-0266, F-0271, F-0272, F-0273, F-0279, F-0282, F-0283, F-0301 closed, plus F-0318,
F-0319, F-0320, F-0321, F-0322, F-0323, F-0325 opened by review and closed; **F-0324 and F-0326 open
by choice**; 53 gates repaired for comment-blindness and 2 promoted gates caught greening their own
defect; tree-wide vitest red from another session's F-0328/CR-97 work*
*· MEDIUM remediation `T-BRANDMED-0817` — verdict `proved` (oracle, admissible); F-0253…F-0265 and
F-0292…F-0296 all closed by gate, 18/18, each falsified against the pre-fix tree **and** a
constructed wrong fix; two rounds of fresh-context review by priya (`believed`, registry-capped);
shipped in `6643cb9` + `f4f4ed6`; **`false-green-gate-blind-spot` open at recurrence ×2 with no
gate***
