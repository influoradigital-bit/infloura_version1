package com.influora.domain.enums;

/**
 * {@code creator_connection_requests.status} (T-CREATORCONNECT-0902) — one workspace's own ask
 * to be introduced to one {@code external_creators} row.
 */
public enum ConnectionRequestStatus {
    PENDING,
    CONTACTED,
    JOINED,
    DECLINED
}
