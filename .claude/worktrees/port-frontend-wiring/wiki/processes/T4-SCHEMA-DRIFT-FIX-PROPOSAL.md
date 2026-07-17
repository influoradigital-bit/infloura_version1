# T4 Schema Drift Fix Proposal

**Author:** Meera (DevOps) | **Date:** 2026-07-11 | **For:** Vikram (Backend)  
**Status:** PROPOSAL — awaiting Vikram implementation

---

## PROBLEM

CI schema-check (`.github/workflows/schema-check.yml`) **FAILS** because Python's two campaign-goal fields use different enums:

| Field | Location | Enum Values |
|-------|----------|-------------|
| `calculate_budget.goal` | `schemas.py:82-84` | `["awareness", "conversion", "launch", "review"]` (4 lowercase) |
| `create_campaign.campaign_type` | `schemas.py:100-102` | `["DIRECT", "HYPE", "REVIEW"]` (3 uppercase) |

**Java canonical (source of truth):**
- `CampaignIntentType.java`: `HYPE|DIRECT|REVIEW|STANDARD` (4 uppercase values)
- `V13__campaign_intents.sql:5`: Database column uses same enum

**Architectural violation:** Per `00-AI-FEATURES-ARCHITECTURE.md §5 rule 6`: *"Java is canonical; Python must mirror it."*

---

## SOLUTION

### Step 1: Align Python schemas to Java canonical enum

**File:** `influora-ai/app/tools/schemas.py`

**Change 1 — calculate_budget.goal (line 82-84):**
```python
# BEFORE:
"goal": {
    "type": "string",
    "enum": ["awareness", "launch", "conversion", "review"],
},

# AFTER:
"goal": {
    "type": "string",
    "enum": ["HYPE", "DIRECT", "REVIEW", "STANDARD"],
    "description": "Campaign type: HYPE (awareness/launch), DIRECT (conversion), REVIEW (post-purchase), STANDARD (general)"
},
```

**Change 2 — create_campaign.campaign_type (line 100-102):**
```python
# BEFORE:
"campaign_type": {
    "type": "string",
    "enum": ["HYPE", "DIRECT", "REVIEW"],
},

# AFTER:
"campaign_type": {
    "type": "string",
    "enum": ["HYPE", "DIRECT", "REVIEW", "STANDARD"],
    "description": "Campaign type (matches CampaignIntentType.java)"
},
```

**Rationale:**
1. Both fields now use **identical** enums (required by CI check)
2. Enum matches Java's `CampaignIntentType` exactly (uppercase, 4 values)
3. Adds missing `STANDARD` value (present in Java, missing from Python)
4. Descriptions guide LLM on when to use each type

---

### Step 2: Update prompt guidance (if needed)

**File:** `influora-ai/app/prompt/persona.py` (or wherever campaign type guidance lives)

**Old guidance (probably):**
```
- "awareness" for brand awareness campaigns
- "launch" for product launches
- "conversion" for direct sales
- "review" for post-purchase review campaigns
```

**New guidance:**
```
- "HYPE" for awareness/buzz/launch campaigns
- "DIRECT" for conversion/sales-focused campaigns
- "REVIEW" for post-purchase review campaigns
- "STANDARD" for general-purpose campaigns
```

**Note:** If prompt already uses the 4-type Java vocabulary, this step is a no-op.

---

### Step 3: Verify executor compatibility

**File:** `influora-api/.../service/meera/tool/CreateCampaignExecutor.java` (lines 195-204)

**Current code:**
```java
private static CampaignIntentType parseCampaignType(String raw) {
    if (raw == null) {
        return CampaignIntentType.STANDARD;
    }
    try {
        return CampaignIntentType.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException e) {
        return CampaignIntentType.STANDARD;
    }
}
```

**Analysis:** ✅ SAFE — already has `.toUpperCase()` + fallback to `STANDARD`. Will accept both old lowercase values and new uppercase values during transition. No backend code change needed.

---

### Step 4: Test migration path

**Scenario:** Existing conversations in `campaign_intents` table have old lowercase values.

**Question for Vikram:** Does `parseCampaignType` handle this?

**Test:**
```java
parseCampaignType("awareness")  // Should return HYPE (or STANDARD as fallback?)
parseCampaignType("HYPE")       // Should return HYPE
parseCampaignType(null)         // Should return STANDARD
```

**Current behavior:** `"awareness".toUpperCase()` = `"AWARENESS"`, which throws `IllegalArgumentException` (not a valid enum), falls back to `STANDARD`.

**Potential issue:** Old conversations with `"awareness"` goal will map to `STANDARD` instead of `HYPE`.

**Solutions:**
1. **Option A (clean break):** Accept the mapping change. Old "awareness" → STANDARD is acceptable if no money depends on it.
2. **Option B (compatibility layer):** Add explicit mapping:
   ```java
   // Map old lowercase values to new uppercase
   String normalized = switch (raw.toLowerCase()) {
       case "awareness", "launch" -> "HYPE";
       case "conversion" -> "DIRECT";
       case "review" -> "REVIEW";
       default -> raw.toUpperCase();
   };
   return CampaignIntentType.valueOf(normalized);
   ```

**Recommendation:** Option A (clean break) — `campaign_intents` are ephemeral draft state, not committed campaigns. If you prefer Option B for backward compatibility, add the mapping.

---

## VERIFICATION

After Vikram implements the fix:

1. **Run schema-check locally:**
   ```bash
   cd influora-ai
   python wiki/processes/test_schema_drift.py
   ```
   Expected output: `✅ No drift detected`

2. **CI will verify:**
   - `.github/workflows/schema-check.yml` passes
   - Tool names match: ✅
   - `goal` == `campaign_type`: ✅
   - (Future: compare against Java `CampaignIntentType`)

3. **Meera will report:**
   Update `SHARED_CONTEXT.md` with:
   ```
   FROM Meera → TO Arjun | T4 schema drift FIX VERIFIED | schemas.py | CI schema-check PASSING | NEXT: all 4 CI jobs green
   ```

---

## FILES TO CHANGE

| File | Lines | Change |
|------|-------|--------|
| `influora-ai/app/tools/schemas.py` | 82-84 | `calculate_budget.goal` enum → `["HYPE", "DIRECT", "REVIEW", "STANDARD"]` |
| `influora-ai/app/tools/schemas.py` | 100-102 | `create_campaign.campaign_type` add `"STANDARD"` to enum |
| `influora-ai/app/prompt/persona.py` | TBD | Update campaign type guidance (if needed) |
| `influora-api/.../CreateCampaignExecutor.java` | 195-204 | Optional: add compatibility mapping (Option B above) |

---

## SIGN-OFF

- [ ] Vikram: Implement schema changes
- [ ] Vikram: Test with `pytest` that tools validate correctly
- [ ] Meera: Run `test_schema_drift.py` — verify `✅ No drift`
- [ ] Meera: Verify CI `schema-check.yml` passes
- [ ] Arjun: Sign off on fix

---

**NEXT ACTION:** Vikram implements, runs `pytest tests/tools/` to verify schema validation, reports completion to Meera for CI verification.
