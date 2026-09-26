package com.influora.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §2) — a single injectable {@link Clock} bean, so every IST
 * computation in the creator-credits code goes through {@code clock.withZone(ZoneId.of(...))}
 * rather than a bare {@code Instant.now()}/{@code LocalDate.now()} — the seam a test needs to pin
 * "now" at an exact IST boundary (A4-A6: 23:59:59 vs 00:00:00 IST, the month roll at
 * 2026-09-30T18:30:00Z).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
