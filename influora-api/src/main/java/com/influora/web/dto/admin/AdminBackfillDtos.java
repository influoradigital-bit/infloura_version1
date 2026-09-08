package com.influora.web.dto.admin;

import java.util.List;

/** F-0740 — admin one-time backfill surface. */
public final class AdminBackfillDtos {

    private AdminBackfillDtos() {}

    /**
     * What a backfill run did, or (when {@code dryRun}) exactly what it would have done.
     *
     * <p>Every bucket is reported rather than collapsed into a single "processed" count, because
     * the buckets mean different things to whoever runs this: {@code alreadyPresent} is the healthy
     * steady state and should grow to equal {@code scanned} once the backfill has run, {@code
     * noHandle} is data an admin could still fix by enriching those imports, and {@code failed} is
     * the only one that warrants looking at a log. A single number would hide all three.
     *
     * <p>{@code samples} carries at most a handful of creator profile ids from the {@code written}
     * bucket so the operator can spot-check real rows in Discover afterwards instead of trusting
     * this response — deliberately a sample, not the full list, so a large run does not return an
     * unbounded payload.
     */
    public record PlatformStatBackfillResult(
            boolean dryRun,
            int scanned,
            int written,
            int alreadyPresent,
            int noHandle,
            int failed,
            List<String> samples) {}
}
