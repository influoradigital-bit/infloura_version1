# QA Review: CR-51 RE-FIX (Vikram, Option B)
Date: 2026-07-30
Reviewer: Kavya
Status: PASS

## Scope
Vikram's CR-51 re-fix per Priya's Option B ruling (gate re-key from milestone-scoped to collaboration-scoped lookup). Static/diff review only; integration test execution separately assigned to Meera.

## Critical Checks: ALL CLEAR

### ✅ 1. Gate re-key correct
EscrowService.java:~1121-1189 — assertReleaseConditionSatisfied now queries `findByCollaborationIdOrderBySlotIndexAsc(milestone.getCollaborationId())` (line ~187-189 in diff). Zero grep matches for `findByMilestoneId` in EscrowService.java gate path. Old milestone-scoped lookup GONE from the gate.

### ✅ 2. Gate logic ELSE unchanged per Priya's spec
- isPostCutover early return: ADDED (line 1121, unchanged logic), cutover boundary unchanged
- satisfyingStatusesFor: ZERO modification (lines 1173-1188 identical to baseline, confirmed via diff grep)
- Empty-set fail-open: PRESERVED (lines ~190-203 in diff, now logs warn for post-cutover zero-deliverable case)
- allSatisfied + RELEASE_CONDITION_NOT_MET throw: UNCHANGED (lines ~211-216 in diff, mechanical rename `linked` → `deliverables`)
- Warn text + javadoc: updated to collaboration-scoped language (correct)

### ✅ 3. ContractService.java has NO change
git diff ContractService.java shows ONLY javadoc/comment updates (`deliverableSlots` → `deliverables` terminology alignment). Zero `setMilestoneId` additions (grep confirmed no new +setMilestoneId lines). Deliverable.milestoneId stays NULL by design.

### ✅ 4. Integration test is REAL (no mocks)
EscrowReleaseGateIntegrationTest.java:
- Line 78: `@Autowired private DeliverableRepository deliverableRepository;` — REAL bean, not @MockBean
- grep for `@MockBean|@Mock|Mockito|when(` on deliverableRepository returns ONLY javadoc comment (line 42) explaining "no Mockito mock of that repository anywhere in this class"
- Lines 96-97: `deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(fx.collaborationId())` — queries REAL repo
- Lines 338-354 (seedDeliverables): persists via `deliverableRepository.saveAndFlush(d)` — REAL persistence, collaborationId set, milestoneId NULL
- Test assertions genuinely prove:
  - **A** (lines 92-106): findByCollaborationId returns real rows, milestoneId==null
  - **B** (lines 116-132): DRAFT deliverables BLOCK release (409 RELEASE_CONDITION_NOT_MET, hold stays FUNDED)
  - **C** (lines 142-159): POSTED deliverables UNBLOCK release (hold → RELEASED)
  - **D** (lines 170-182): zero-deliverable collaboration fails open (release succeeds, hold → RELEASED)
  - **E** (lines 192-207): pre-cutover milestone fails open even with unsatisfied deliverables
- extends AbstractIntegrationTest (Testcontainers MySQL), not @DataJpaTest mock
- Zero Mockito involvement for deliverableRepository anywhere in the file

### ✅ 5. Mock-based test updates mechanical
EscrowServiceTest.java + EscrowServiceReleaseTest.java:
- All stub changes: `findByMilestoneId(MILESTONE_ID)` → `findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID)` (lines 294, 303, 312, 321 in ReleaseTest; lines 492, 510 in Test)
- Coverage intent PRESERVED: still testing release gate behavior, just against the correct lookup key
- New cutover boundary tests in EscrowServiceTest (lines 390-524): cover cutover-unset, pre-cutover, post-cutover empty-set, post-cutover unsatisfied, post-cutover satisfied, invalid cutover value — all invoke the private gate method via reflection (correct isolation)
- NO weakened coverage (assertions still check exception codes, hold status transitions, fail-open paths)

## Advisories (non-blocking)

1. **Docker dependency** — EscrowReleaseGateIntegrationTest skips if Docker unavailable (via DockerAvailableCondition, per repo precedent). Meera's run will confirm whether the test EXECUTES or SKIPs; gap noted in Vikram's notes as expected.
2. **ContractService.java line-ending warning** — git reports "LF will be replaced by CRLF" on ContractService.java (cosmetic, not a code issue).

## Verdict
**PASS** — all 5 critical checks satisfied. Gate re-key is correct, everything ELSE unchanged per Priya's spec, ContractService untouched, integration test is genuinely real (zero mocks of deliverableRepository), assertions prove all required behaviors (block, unblock, fail-open, cutover boundary).

Code quality meets CR-51 Option B spec. Integration test execution outcome is Meera's separate deliverable.

## Next Steps
Route to Meera for integration test execution (Testcontainers + real DB assertions). Re-review only if Meera finds runtime failures that contradict these static checks.
