# QA Review: DPF-4 — PaymentMilestone.releaseCondition Schema Migration

**Date:** 2026-07-13  
**Reviewer:** Kavya (QA Lead)  
**Developer:** Vikram  
**Status:** ✅ **PASS**

---

## Scope

DPF-4 adds `release_condition ENUM` to `payment_milestones` table and corresponding entity field. Enables data-driven release gates for DPF-5.

---

## Files Reviewed

1. `influora-api/src/main/resources/db/migration/V52__payment_milestone_release_condition.sql`
2. `influora-api/src/main/java/com/influora/domain/entity/PaymentMilestone.java`

---

## QA Checklist Results

### ✅ Migration Correctness

**Version numbering:**
- V52 is the correct next version (V51 = trendspark migration exists)
- No collision with existing migrations

**SQL syntax:**
```sql
ALTER TABLE payment_milestones
ADD COLUMN release_condition ENUM('ON_APPROVAL','ON_POSTED','ON_VERIFIED_METRICS')
NOT NULL
DEFAULT 'ON_POSTED';
```
- ENUM definition is valid MySQL syntax
- NOT NULL constraint present
- DEFAULT 'ON_POSTED' ensures existing rows get safe default on ALTER
- No data migration needed (default handles backfill)

**Migration header:**
- Includes DPF-4 reference and spec pointer (good practice)

---

### ✅ Entity Mapping

**Storage strategy:**
```java
@Enumerated(EnumType.STRING)
@Column(name = "release_condition", nullable = false)
private ReleaseCondition releaseCondition = ReleaseCondition.ON_POSTED;
```
- Uses `EnumType.STRING` (matches SQL ENUM storage, not ordinal — correct)
- Column name matches SQL (`release_condition`)
- `nullable = false` matches SQL `NOT NULL`
- Field-level default `= ReleaseCondition.ON_POSTED` matches SQL default

**Enum values:**
```java
public enum ReleaseCondition {
    ON_APPROVAL,
    ON_POSTED,
    ON_VERIFIED_METRICS
}
```
- Java enum names EXACTLY match SQL ENUM strings (case-sensitive)
- No ordinal mismatch risk
- All three values present

---

### ✅ Builder Integration

**Builder method added:**
```java
public Builder releaseCondition(ReleaseCondition releaseCondition) {
    m.releaseCondition = releaseCondition;
    return this;
}
```
- Follows existing builder pattern
- Returns `this` for chaining (correct)

**Build method null safety:**
```java
if (m.releaseCondition == null) {
    m.releaseCondition = ReleaseCondition.ON_POSTED;
}
```
- Applies default if not set by caller
- Matches SQL default and field-level default (triple-defense)
- Prevents null constraint violations

---

### ✅ No Regressions

**Existing builder calls:**
- Grep found 7 files using `PaymentMilestone.builder()`
- All existing calls omit `.releaseCondition(...)` (expected — new field)
- Builder's `build()` method applies default → no breakage

**Test files checked:**
- `ContractServiceTest.java`: builds milestone without setting `releaseCondition` → defaults to `ON_POSTED` (safe)
- `ContractService.java`: same pattern (production code)
- No forced-parameter requirement → backward compatible

**Only constructor is protected no-args (JPA):**
- No public constructor requiring new parameter
- All construction via builder (which handles default) → safe

---

## Security Check

- No hardcoded credentials
- No SQL injection risk (ENUM values are compile-time literals)
- No client-trusted input (this is a builder field, set server-side)

---

## Performance Check

- Adding nullable-false column with default is safe on existing rows (MySQL handles atomically)
- ENUM storage is compact (1-2 bytes vs VARCHAR)
- No index added (none needed for this use case)

---

## Accessibility/Frontend Impact

- Backend-only change (no UI impact)
- No API contract change yet (DPF-5 will expose this in responses)

---

## Verdict

**✅ PASS — route to Meera for local verification**

### Why PASS

1. Migration version is correct (V52)
2. SQL syntax is valid, safe, and includes default for existing rows
3. Entity mapping is correct (`@Enumerated(STRING)`, exact name match)
4. Builder integration is complete and null-safe
5. No regressions in existing code (all builder calls remain compatible)
6. No security/performance/accessibility issues

### Notes for Meera

- Run `mvn clean compile` to verify entity builds
- Run `mvn test` to verify no test regressions
- Check that Flyway accepts V52 migration (no version conflict)
- Spot-check that existing `PaymentMilestone.builder().build()` calls compile (they should default to `ON_POSTED`)

### Next Steps

1. Meera: local build + test verification
2. If green: DPF-4 → DONE, ready for DPF-5 (release logic implementation)
3. Update SHARED_CONTEXT.md with PASS verdict

---

**Kavya (QA Lead)**  
2026-07-13
