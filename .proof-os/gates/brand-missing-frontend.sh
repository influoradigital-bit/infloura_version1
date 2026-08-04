#!/usr/bin/env bash
# proof-os project gate — brand missing-feature build
# Origin findings (RETENTION rule 3):
#   F-0076  review-flag built end-to-end
#   F-0077  campaign-template create/delete built end-to-end
set -euo pipefail
cd "$(dirname "$0")/../.."
npx tsc --noEmit
npm run build
