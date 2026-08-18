/**
 * .proof-os/gates/vitest.gates.config.ts — the config proof-os gates run their own fixture
 * specs under. origin: F-0334.
 *
 * THE PROBLEM. Gates that prove a behaviour rather than grep for a token need real specs, and
 * those specs live in .proof-os/gates/ because gate work owns .proof-os/** and nothing else.
 * But the project's vitest.config.ts excludes only node_modules, dist, e2e and .claude, while
 * vitest's default include is `**\/*.{test,spec}.?(c|m)[jt]s?(x)` — so every gate fixture was
 * being collected by the PRODUCT suite, and by gates/build.sh's `npm test` leg. Two harms: a
 * gate fixture failing reads as a product regression, and build.sh ends up partly grading the
 * trust layer instead of the product. Worse, several gates write TEMPORARY *.test.tsx into this
 * directory and reap them in an EXIT trap — a hard kill between write and reap leaves a fixture
 * behind for the next full run to collect.
 *
 * THE FIX, and why it needs TWO configs. vitest.config.ts now excludes '**\/.proof-os/**',
 * which closes all of the above including the temp-file leak. But vitest applies `exclude` even
 * to a file path passed explicitly on the command line, so with that one line the gates' own
 * specs became uncollectable and all three gates went to exit 2 (unavailable) — measured, not
 * assumed. This config is the escape hatch: the project config with that single exclusion
 * removed, and nothing else changed. Gates invoke
 *
 *     node_modules/.bin/vitest run --config .proof-os/gates/vitest.gates.config.ts <spec>
 *
 * so gate fixtures run under exactly the project's environment, setup, aliases and env
 * overrides — they just are not swept up by a product-wide run.
 *
 * DELIBERATELY DERIVED, NOT COPIED. Everything here comes from ../../vitest.config.ts at load
 * time. A copy would drift: the day someone adds a setupFile or changes VITE_API_MODE there,
 * gate fixtures would silently run under a different environment from the product suite and
 * their verdicts would stop meaning what the gate says they mean.
 */
import path from 'path';

import base from '../../vitest.config';

const baseConfig = base as Record<string, any>;
const baseTest: Record<string, any> = baseConfig.test ?? {};
const baseExclude: string[] = baseTest.exclude ?? [];

const gatesExclude = baseExclude.filter((p) => !p.includes('.proof-os'));
if (gatesExclude.length === baseExclude.length) {
  // Not fatal — this config is still correct — but say it out loud rather than let someone
  // believe this file is doing work it is not. If the main config stops excluding .proof-os,
  // gate fixtures are back in the product suite and F-0334 is open again.
  console.warn(
    '[vitest.gates.config] vitest.config.ts no longer excludes .proof-os — gate fixtures are ' +
      'being collected by the product suite again (F-0334).',
  );
}

export default {
  ...baseConfig,
  // The config file does not sit at the project root, and vitest would otherwise resolve
  // `root` to this directory — which would break setupFiles' relative path and change what
  // the default include sweeps.
  root: path.resolve(__dirname, '..', '..'),
  test: {
    ...baseTest,
    exclude: gatesExclude,
  },
};
