# VERDICT — the one vocabulary (Sage OS v0.1)

Every service, gate, and report in this OS speaks ONE shape. No exceptions.

## The four columns
    subject · owner · status · proved_by

## Status enum (exactly four)
    aligned   full chain present & consistent
    partial   declared and partly wired; a link is missing
    broken    a link is wrong (bad ref, dead path, contradiction)
    missing   referenced but never implemented

## Evidence shape (required on every verdict)
```json
{
  "subject":  "src/app/api/products/route.ts",
  "owner":    "vikram",
  "status":   "aligned|partial|broken|missing",
  "evidence": {
    "oracle":  "tsc|eslint|gitleaks|lighthouse|playwright|script|model",
    "command": "npx tsc --noEmit",
    "result":  "0 errors",
    "where":   "file:line"
  }
}
```

## Trust law
- `oracle: model` may NEVER render as proved. Ceiling: **believed** (amber).
- A service's ceiling is set by `may_claim` in registry.json — trust is
  ASSIGNED by the registry, never asserted by the service.
- Scores are COMPUTED by scripts/validate.py. A report that supplies its
  own overall score is rejected (this kills the overall_pct bug class).

## Scoring
    aligned=1.0  partial=0.5  broken=0.0  missing=0.0
    alignment % = sum/count. Reproducible from rows or it is invalid.

## Voice
- Silent when green. Verbs encode certainty: proved/believed/broken/missing.
- Never "seems", "probably", "looks good". Uncertainty is a STATUS.

## Ledger law
    No failure closes without promoted_to → an existing gate file,
    or an explicit unautomatable sign-off naming who accepted it.
