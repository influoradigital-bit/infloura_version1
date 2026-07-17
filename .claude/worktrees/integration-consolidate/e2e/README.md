# Influora Playwright E2E (Kv3b)

Vite + React Router smoke suite. Default mode is **mock API** + **dev demo auth**.

## Run

```powershell
npx playwright install chromium   # once
npm run test:e2e
```

## Demo path

Protected creator routes accept `?demo=true` only when Vite is in DEV (`npm run dev`).
Example: `/creator/dashboard?demo=true`

## Fail-closed (live API)

Do **not** set `VITE_API_MODE=live` for this suite unless you have staging OTP/JWT.
Specs skip (or the harness refuses) when live mode is detected — no accidental prod hits.
