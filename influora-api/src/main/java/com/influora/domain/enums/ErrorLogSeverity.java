package com.influora.domain.enums;

/**
 * Severity for {@code error_log} rows. Mirrors {@code ErrorSeverity} in
 * {@code src/admin/types/admin.types.ts} (ERROR/WARN/CRITICAL).
 *
 * <p><b>Honesty note:</b> the only current producer — {@code GlobalExceptionHandler}'s catch-all
 * 500 handler — always writes {@link #ERROR}. There is no server-side taxonomy today that
 * distinguishes a "critical" fault from an ordinary one, so {@link #WARN}/{@link #CRITICAL} have no
 * producer yet (see {@code AdminErrorLogService.getStats}'s {@code critical24h} javadoc). The enum
 * carries all three values so the FE contract and a future classifier have somewhere to land.
 */
public enum ErrorLogSeverity {
    ERROR,
    WARN,
    CRITICAL
}
