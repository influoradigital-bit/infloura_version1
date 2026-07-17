package com.influora.domain.enums;

/** Email outbox status for the transactional outbox pattern (Domain B). */
public enum EmailOutboxStatus {
    PENDING,
    SENT,
    FAILED
}
