#!/usr/bin/env bash
# F-0877 [arjun · 2026-09-17] — a live creator with no audience data must render the explicit
# "Not available" state on the brand profile, never mock-mode numbers. ec159af broke this silently
# when getCreatorDemographics was wired in and older fixtures did not mock it.
# Exit 0 proved · 1 broken · 2 unavailable.
set -u
cd "$(git rev-parse --show-toplevel)" || exit 2
command -v npx >/dev/null || exit 2
npx vitest run \
  src/pages/__tests__/brand-creator-profile.absent-audience.test.tsx \
  src/pages/__tests__/brand-creator-profile.absent-metrics.test.tsx \
  src/pages/brand-creator-profile.demographics-bands.test.tsx >/dev/null 2>&1 || exit 1
exit 0
