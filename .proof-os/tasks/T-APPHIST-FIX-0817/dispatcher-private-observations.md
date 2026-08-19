# Sealed until Priya reports — do NOT send to the checker

Written before Priya's fresh-context review was dispatched. The point is to test whether an
independent review finds these without being told (law 8, catch rate). If Priya finds them,
the isolation is doing real work. If she does not, these get added afterwards and her ceiling
should be read accordingly.

Both concern `src/components/creator/ApplicationHistoryTimeline.tsx`.

## O-1 · `targetRoute` is trusted verbatim as a router destination

`resolveCta` (line 115) returns `event.targetRoute` straight from the API response and it is
rendered as `<Link to={cta.href}>` (line 272). Nothing checks that the value is an internal
path. A `targetRoute` that is an absolute or external URL — whether from a backend bug, a
misconfigured seed, or anything that lets a counterparty influence the field — becomes a
navigation target the creator sees as a first-party in-app button.

Expected shape of a fix: reject anything not matching a leading `/` (and not `//`), and fall
through to the `dealRoomId` branch when it fails. The component's own docstring already says
it "never fabricates a destination", so validating the one destination it does not control is
consistent with its stated intent.

## O-2 · `scrollIntoView` can move the whole page, not just the timeline

Line 186: `bottomRef.current?.scrollIntoView({ block: 'end' })`, fired on load inside a
per-card collapsible on `/creator/applications`. `scrollIntoView` walks every scrollable
ancestor, not only the `ScrollArea`. Expanding "Show journey" on a card partway down the list
can therefore yank the viewport, and the effect re-runs on `events.length` change.

The requirement it implements ("newest events stay visible") is real and the oldest-first
ordering decision above it is correct — the mechanism is the problem, not the goal. Scrolling
the ScrollArea viewport directly (assign `scrollTop`, the pattern `creator-chat.tsx` uses per
its CR-04 note) would satisfy the requirement without touching the page.

## Uncertain, deliberately not asserted

`ScrollArea` is given `max-h-80` on its root (line 231). Radix's ScrollArea generally needs the
height constraint on the viewport for the scroll container to engage. Whether `max-h` on the
root actually clips here needs a browser, and this audit ran source-only — recorded as an open
question, not a finding.
