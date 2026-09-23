package com.influora.domain.enums;

/**
 * Lifecycle of a {@link com.influora.domain.entity.CreatorChallenge} (CHALLENGE-SPEC.md, Backend
 * &sect;1/&sect;4). {@code ACTIVE} is the one row a creator can have at a time -- enforced by the
 * {@code active_key} unique index, not by this enum. {@code COMPLETED} is set lazily, on a GET,
 * once {@code today} is past day 6 (V20260923100000 migration javadoc). {@code ENDED} is the
 * creator choosing to stop early ({@code POST /creator/challenge/{id}/end}).
 */
public enum CreatorChallengeStatus {
    ACTIVE,
    COMPLETED,
    ENDED
}
