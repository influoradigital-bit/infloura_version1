# Ruling: the Agency workspace chooser is removed

**Decided by:** Swapnil, 2026-09-18. **Recorded by:** Arjun.
**Answers:** B1 in `wiki/processes/task-subscription-remaining-0915.md`. **Unblocks:** C5, D2.

## The ruling

**Remove it.** Onboarding offers BRAND only. The 4 existing AGENCY workspaces convert to BRAND.
Priya's recommendation on record is followed; the Agency product is not being built now.

## Why (state of the code when this was decided)

An AGENCY workspace is silently degraded today, which is worse than not offering the choice:

- It gets no `subscriptions` row — 4 of 4 AGENCY workspaces had none on production (2026-09-15).
  `V20260912120000__backfill_free_subscriptions.sql` is scoped to `type = 'BRAND'` on purpose.
- It is invisible in the admin billing console, which pages over `Subscription` rows, so it cannot
  be comped or extended.
- Its AI credits never reset: `AICreditResetJob` is brand-scoped (an earlier unfiltered sweep
  created spurious `BrandAiCredit` rows for AGENCY workspaces, which is why it is scoped).

## What this makes true

| ID | Work | Owner |
|---|---|---|
| D2 | Remove the Brand/Agency card from `src/components/brand/onboarding/onboarding-steps.tsx`; the signup payload stops sending a chooser value (`brand-onboarding.tsx:102` `workspaceType`) | Ananya |
| C5 | Convert the 4 AGENCY workspaces to BRAND (SQL), so 31/31 have subscription rows | Vikram, then Meera runs it |

`WorkspaceType.AGENCY` stays in the enum and in the database: existing rows must still load, and
removing an enum constant a column holds is a separate, riskier change. Nothing new writes it.
Any surface that still branches on AGENCY should be treated as dead and removed as it is found.

## Not decided here

Whether an agency product is built later, and if so whether it is a workspace type at all rather
than a multi-workspace account. This ruling only removes the chooser that promises it today.
