# Ruling: credits on a plan upgrade

> **Decision by:** Swapnil Maruti (CEO) — 2026-09-18, relayed by Arjun
> **Applies to:** F-0881 (Kabir HIGH) and F-0883 (Kabir MEDIUM), both on
> `AICreditService#applyPlanAllotment`
> **Status:** RULED. Implement in the S3 repair round.

## The decision

On an allotment INCREASE, set `creditsRemaining` to the **full new `monthlyAllotment`** — not a
top-up by the difference. A brand that upgrades at 0 credits has the whole Pro allowance available
immediately.

**Granted at most once per billing period.** A second increase inside the same billing period must
not grant again. The billing period is the subscription's own
`currentPeriodStart`/`currentPeriodEnd` (`Subscription.java:46-49`), not the calendar month.

## Why both halves are one ruling

Setting the full allowance is what a paying brand expects, and what
`assignments-0917-subscription.md` S3 item 1 asked for. On its own it makes F-0883 worse: today
every `PAST_DUE → ACTIVE` pair re-grants the increase (Kabir probe: `granted=300` three times in a
row), and with this ruling each flap would hand over a full 400 instead. The per-period guard is
what makes the full grant safe, so neither half ships without the other.

## What this does not change

- **Decreases never claw back.** A mid-cycle downgrade leaves `creditsRemaining` alone; the lower
  allowance takes effect at the next reset. Existing documented intent, unchanged.
- **The loyalty bonus still stacks** (SM-0.2): Pro with a funded campaign is 450, without 400.
- **Nothing here changes what a brand is charged.**

## Open for the implementer, not decided here

`BrandAiCredit` has no field that records which billing period was last granted (`cycleStart` and
`lastReset` both belong to the monthly reset). A new column plus a Flyway migration is the likely
shape, with a `wiki/processes/schema-changes.md` entry. Any cycle-free design that survives webhook
retries and redeliveries is acceptable — flag it to Priya if a different shape is cheaper.
