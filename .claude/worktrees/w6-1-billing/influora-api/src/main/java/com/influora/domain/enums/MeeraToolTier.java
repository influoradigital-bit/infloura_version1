package com.influora.domain.enums;

/**
 * Permission tier per 06-MEERA-PERMISSIONS-MATRIX.md — every tool-call executor maps to exactly
 * one of these; {@code FORBIDDEN} has no corresponding executor and no route (structural
 * guarantee: the capability is absent, not soft-blocked).
 */
public enum MeeraToolTier {
    /** Read — Meera reads data and advises. No state change. */
    R,
    /** Draft — Meera creates a reversible, non-binding draft. No money, no external commitment. */
    D,
    /** Commit — touches money or binds the brand to a third party. Meera stages only; human confirms. */
    C,
    /** Forbidden — Meera has no capability, ever. No endpoint exists for these. */
    FORBIDDEN
}
