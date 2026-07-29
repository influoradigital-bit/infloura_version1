# CR-11 — Client crash-report contract

> **Owner:** Priya (CTO). **Status:** LOCKED for the CR-11 work. Frontend and backend are built
> in parallel against this document; neither side may change the shape unilaterally.

## Why this exists

CR-11 ("white screen on tab sequence") has been `BLOCKED` for four passes waiting for a human to
catch `[ErrorBoundary] Uncaught render error: …` in a console at the exact moment of blanking.
Neha has already swept every filter chip, every nav item and every deal-room panel without
reproducing it. The blocker was filed as *evidence*; it is really *instrumentation* — the app
cannot report its own crashes, so the only capture mechanism is a human happening to have
devtools open. That is the defect to fix.

CR-10 already stops one throw being permanent. This names the throw site.

## Endpoint

```
POST /api/v1/client-errors
```

**Auth: optional.** Send `Authorization: Bearer <token>` when a role token exists, but the
endpoint MUST accept unauthenticated reports — a render crash can happen on the public portfolio
page or before login, and those are exactly the crashes nobody can otherwise see.

**Response: `202 Accepted`, empty body.** Never 4xx a malformed report. This endpoint exists to
catch failures; making it capable of causing one defeats the point. Validation failures are
dropped server-side, not reported back.

## Payload

| Field | Type | Client cap | Notes |
|---|---|---|---|
| `message` | string | 500 | `error.message` |
| `stack` | string \| null | 4000 | `error.stack` |
| `componentStack` | string \| null | 4000 | `errorInfo.componentStack` — this is the field that names the throw site |
| `pathname` | string | 200 | `location.pathname` **only** |
| `buildId` | string | 64 | `__APP_BUILD_ID__`, see below |
| `userAgent` | string | 300 | `navigator.userAgent` |

### `pathname` only — never the full URL

No `search`, no `hash`, no `href`. Query strings in this app carry `?deal=<id>` and OAuth
callback params, and a crash report is not a place to start collecting those. This is a hard
requirement, not a preference.

### The server re-truncates everything

Client caps are a courtesy, not a control — the endpoint is unauthenticated and anyone can post
to it. The server applies its own limits to every field regardless of what arrived, rejects
bodies over 16 KB before parsing, and rate-limits per IP.

## `__APP_BUILD_ID__`

A new Vite `define`. Without it a stack trace against a minified bundle cannot be tied to a
build, which is most of the value. Source the short git SHA at build time with a timestamp
fallback so a build outside a git checkout still produces something.

It is a build identifier, not a secret, and is safe in the client bundle.

## Frontend rules (non-negotiable)

1. **`componentDidCatch` must never throw.** A throw inside the error boundary's own error
   handler is unrecoverable. Wrap the whole report in try/catch and use fire-and-forget.
2. **Never block the fallback render.** The report is a side effect; the user sees the fallback
   immediately either way.
3. **Deduplicate per session** on `message + pathname`. A render loop must not become a
   self-inflicted request flood.
4. **Keep the existing `console.error`.** CR-11's original unblock condition was that exact
   line; it stays for local debugging.
5. **Failure is silent.** If the POST fails, swallow it. A crash reporter that surfaces its own
   errors to the user is worse than no crash reporter.

## Security constraints (mandatory)

- Rate-limit per IP.
- Reject > 16 KB bodies.
- Server-side truncation of every field.
- Never echo the submitted content back in the response.
- Log at `WARN` with a stable, greppable marker so these are findable.
- No PII beyond what a stack trace inherently carries; `pathname` rule above is part of this.

## Out of scope

No third-party vendor (Sentry etc.). That would be a cost and dependency decision requiring
Rohan and Swapnil; a server log endpoint answers CR-11 without one.
