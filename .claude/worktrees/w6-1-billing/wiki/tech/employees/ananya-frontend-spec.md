# Ananya — Frontend Spec

> **Reports to:** Priya (CTO) · **Wave:** 1–2 · **Blocked by:** Vikram (`CreatorFitProfile` DTO merged)
> **Read first:** `wiki/tech/employees/00-AI-FEATURES-ARCHITECTURE.md` §4, §5

---

## STOP — the stack is not what `TECH-STACK.md` says

The agent-roster `TECH-STACK.md` claims Next.js 14 + Prisma + NextAuth + Vercel. **All four are
wrong.** From `package.json` and the code:

| Roster says | Reality |
|---|---|
| Next.js 14 App Router | **Vite 6 + React 19 + react-router-dom 7** |
| Prisma | JPA/Hibernate (Java side; you never touch it) |
| NextAuth | Spring Security + JWT |
| Vercel | Docker / nginx |

`next.config.mjs` exists and is vestigial. I am rewriting `TECH-STACK.md`. **Do not scaffold a
Next.js route, a server component, or `use server`.** None of it exists here.

What is real: TanStack Query 5, Zustand 5, react-hook-form + zod, shadcn/ui (new-york) + Radix,
Tailwind 4, framer-motion 12, GSAP + Lenis.

---

## The one rule that matters for this work

**Every new field ships nullable, and you render the null state first.**

`brandSafetyScore` is `null` for every creator in the database until Vikram's
`BrandSafetyScoreService` backfills. `audienceCityPct` is `null` for any creator without a Meta
snapshot. `completionRate` is `null` for a creator with zero terminal deals.

A `null` completion rate must render **"New creator — no track record yet."**
It must never render **"0%"**. That number would cost a real person real work.

This is the same failure Ash's whole AI review was about: confident output with nothing behind it.
It applies to UI exactly as it applies to prompts.

---

## F1 — Types (do this first, alone, in its own PR)

`src/types/meera.ts` — mirror `MeeraToolDtos.CreatorFitProfile` **field-for-field**. Java is
canonical (architecture doc §4). Names already match camelCase; do not "improve" them.

```ts
export interface CreatorFitProfile {
  creatorId: string;
  followers: number;
  engagementRate: number;
  audienceCityPct: number | null;
  audienceTopCity: string | null;
  audienceFemalePct: number | null;
  audienceTopAgeBand: string | null;
  completedDeals: number;
  completionRate: number | null;
  onTimeRate: number | null;
  avgResponseMinutes: number | null;
  qualityScore: number | null;
  fakeFollowerScore: number | null;
  brandSafetyScore: number | null;
  riskFlags: RiskFlag[];
}

export type RiskFlag =
  | 'missed_deadline'
  | 'slow_responder'
  | 'low_completion'
  | 'high_revision_rate'
  | 'unverified_audience';
```

`riskFlags` is an enum union, not `string[]`. Vikram emits from a fixed vocabulary so you can style
each one and never render an unknown token.

TS strict mode, no `any`. If a field can be null in Java, it is `| null` here. No `?:` — the field
is always present, its value may be null. Those are different things and the distinction is the
whole point.

---

## F2 — Hook

`src/hooks/brand/useCreatorFit.ts`

TanStack Query, following the six existing real-fetch hooks (`src/hooks/analytics/*`). Do **not**
add a seventh mock hook — 76 of 316 frontend files import mock data and only 7 do a real `fetch`.
That ratio is the reason we are 42% live end-to-end. Every new hook is real or it is not merged.

```ts
export function useCreatorFit(creatorId: string, campaignId?: string)
```

- `queryKey: ['creator-fit', creatorId, campaignId ?? null]`
- No `refetchInterval`. Reliability stats update nightly.
- `staleTime: 5 * 60_000`.

---

## F3 — `CreatorFitCard.tsx`

`src/components/feature/meera/CreatorFitCard.tsx`

Renders one creator inside a Meera `tool_result`. The AI writes the prose; **you render the
numbers.** Never let the model's narration and your figures disagree — the figures come from
`fitProfile`, the prose comes from the SSE `token` stream, and both derive from the same tool
result.

Sections:

1. **Header** — display name, followers, engagement. Always present.
2. **Audience** — top city + `audienceCityPct`, gender split, age band.
   `null` → *"Audience data syncing — check back after the next Instagram sync."*
3. **Track record** — completed deals, completion rate, on-time rate.
   `completionRate === null` → *"New creator — no completed deals yet."*
4. **Risk flags** — badge per flag, amber. Empty array → render nothing. Not "No risks!" — absence
   of a flag is not a guarantee, and we do not make guarantees about people.
5. **Safety** — `brandSafetyScore === null` → hide the row entirely. Do not show "Not scored." A
   brand reading "Not scored" reads it as "suspicious." Hide until we have the number.

Accessibility, per my locked standards: WCAG AA. Risk flags need a text label, not colour alone.
Score bars need `role="meter"` with `aria-valuenow`/`aria-valuetext`.

---

## F4 — `CreatorCompareTable.tsx`

Shortlist comparison, 2–8 creators. Columns from `CreatorFitProfile`. Sortable client-side.

Null cells render `—` with an `aria-label` of *"no data"*. **Sorting must sink nulls to the
bottom regardless of direction.** A creator with no data is not the best creator and is not the
worst; she is unknown, and unknown does not compete for the top of a sorted column.

No "overall fit score" column. Vikram doesn't compute one and you must not invent one in the
client. If Swapnil wants a single number, it gets defined in Java, in a DTO, with a documented
formula — architecture doc, rule 4.

---

## F5 — Meera stream: the null-state discipline (Wave 1, small)

`src/hooks/useMeeraStream.ts` already consumes the SSE protocol. Two gaps:

- `done { finish_reason: "iteration_cap" }` and `"pending_human_confirm"` are distinct from
  `"stop"`. Today the UI treats them the same. `iteration_cap` means Meera gave up mid-task —
  say so, offer to continue. Don't render a truncated turn as a finished thought.
- Vikram is adding `finish_reason: "length"` (Ash P1-3: `max_tokens=1024` on every turn,
  `stop_reason` never read). When it arrives, render a continue affordance.

---

## What you never do

| Never | Why |
|---|---|
| Render `0` for a `null` metric | It is a claim about a person that we cannot support |
| Compute a fit score client-side | Rule 4 — every number the UI shows traces to a DTO field |
| Add a mock-data hook | 42% live is the company's biggest problem; you don't make it worse |
| Put a secret in `import.meta.env.VITE_*` | Vite inlines it into the bundle. Same rule as `NEXT_PUBLIC_`. |
| Trust the model's prose over the DTO | Prose is generated. Numbers are fetched. |

---

## Definition of Done

- [ ] `src/types/meera.ts` mirrors `MeeraToolDtos.CreatorFitProfile` exactly; strict, no `any`
- [ ] `useCreatorFit` does a real fetch; zero mock imports
- [ ] `CreatorFitCard` renders every null state with copy Nisha has reviewed
- [ ] `CreatorCompareTable` sinks nulls in both sort directions
- [ ] `finish_reason` cases handled: `stop`, `iteration_cap`, `pending_human_confirm`, `length`
- [ ] WCAG AA: labels not colour-only, meters have `aria-valuetext`
- [ ] `useReducedMotion()` bypass on any card entrance animation

Kavya gates. Meera runs `npm run build` + `npm run test`. Then Priya signs.
