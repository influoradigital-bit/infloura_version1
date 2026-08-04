# FLOW — the engineering model's order, and what happens when it breaks

FLOW (fixed, no skips):
  intake → confirm(done_when) → gates(proved) → produce
  → gates again → judgment(believed) → verdict → journal → cleanup

Laws:
- done_when missing → work CANNOT start. The confirm screen is the only question.
- Gates before judgment, always. Free deterministic checks run before paid model review.
- A service's may_claim (registry) is a ceiling. oracle:model never renders proved.
- Every stage transition is journaled (who/what/when/task/stage).

## Break table — detection and automatic fix
| Break                          | Detected by            | Fix rule |
|--------------------------------|------------------------|----------|
| Stage skipped                  | journal sequence gap   | verdict refused; reset to last completed stage |
| Gate fails                     | exit code 1            | retry ≤ caps.retries → escalate to human; downstream = SUSPECT |
| Gate tool unavailable          | exit code 2            | render believed (never green); add to NEEDS YOU |
| Claim outside jurisdiction     | registry lookup        | claim rejected; reroute to owner |
| Self-scored verdict            | validate.py            | rejected outright |
| done_when absent               | confirm screen         | hard stop |
| Same failure class ×3          | promote.py --recurrence| block until class has a gate |
| Unknown break                  | anything unmatched     | ledger record (missed_by: flow) → becomes a new row HERE |

The last row is the loop applied to the flow itself: every new way the flow
breaks must end as a new detection rule in this table, or a signed
UNAUTOMATABLE entry in the ledger.
