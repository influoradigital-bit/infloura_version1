package com.influora.domain.enums;

/**
 * {@code external_creators.status} (T-CREATORCONNECT-0902). UNVERIFIED — not (yet) an Influora
 * member. INVITED — admin sent {@code creator.join_invitation}. JOINED — matched to a real
 * {@code CreatorProfile} by {@code ExternalCreatorLinkService}.
 */
public enum ExternalCreatorStatus {
    UNVERIFIED,
    INVITED,
    JOINED
}
