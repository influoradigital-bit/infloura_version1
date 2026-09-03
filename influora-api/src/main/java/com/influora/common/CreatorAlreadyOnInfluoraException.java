package com.influora.common;

import org.springframework.http.HttpStatus;

/**
 * T-CREATORCONNECT-0902 — thrown by {@code ExternalCreatorService#connect} when the target
 * external creator has already {@code JOINED} (a real, linked {@code CreatorProfile} exists).
 * Carries {@code linkedCreatorProfileId} so the 409 response body can hand the frontend the
 * create-campaign link directly — same additive-subclass pattern as {@link
 * InsufficientFundsException}.
 */
public class CreatorAlreadyOnInfluoraException extends ApiException {

    private final String linkedCreatorProfileId;

    public CreatorAlreadyOnInfluoraException(String message, String linkedCreatorProfileId) {
        super("CREATOR_ALREADY_ON_INFLUORA", message, HttpStatus.CONFLICT);
        this.linkedCreatorProfileId = linkedCreatorProfileId;
    }

    public String getLinkedCreatorProfileId() {
        return linkedCreatorProfileId;
    }
}
