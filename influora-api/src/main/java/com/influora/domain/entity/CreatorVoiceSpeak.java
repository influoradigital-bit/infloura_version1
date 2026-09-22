package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §3, Kabir C5) — caps real Sarvam TTS calls per paid voice turn.
 * One {@code tts:<turnId>} debit buys at most {@code voice-speaks-per-turn} (default 3) calls.
 */
@Entity
@Table(name = "creator_voice_speaks")
public class CreatorVoiceSpeak {

    @EmbeddedId
    private Key id;

    @Column(name = "speak_count", nullable = false)
    private int speakCount;

    /**
     * K-15/round-2 review finding #3 — true once ANY {@code speak} attempt for this turn has
     * returned real, paid-for audio to the creator. Written under the account row lock by {@code
     * CreatorCreditService#markVoiceDelivered}, called from {@code CreatorMeeraController#speak}'s
     * success path. Replaces the old process-local {@code deliveredVoiceTurns} map — persisted,
     * survives a restart, and shared across instances.
     */
    @Column(name = "delivered", nullable = false)
    private boolean delivered;

    /**
     * K-15/round-2 review finding #3 — true once this turn's {@code tts:} surcharge has been
     * refunded (at most once, ever). Set under the account lock, in the SAME transaction as the
     * refund itself ({@code CreatorCreditService#releaseVoiceIfUndelivered}), so a later claim for
     * this turn can tell "already refunded" apart from "never refunded" without re-deriving it from
     * the ledger.
     */
    @Column(name = "refunded", nullable = false)
    private boolean refunded;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorVoiceSpeak() {}

    public static CreatorVoiceSpeak newRow(String creatorUserId, String turnId) {
        CreatorVoiceSpeak s = new CreatorVoiceSpeak();
        s.id = new Key(creatorUserId, turnId);
        s.speakCount = 0;
        s.delivered = false;
        s.refunded = false;
        s.updatedAt = Instant.now();
        return s;
    }

    public String getCreatorUserId() {
        return id.creatorUserId;
    }

    public String getTurnId() {
        return id.turnId;
    }

    public int getSpeakCount() {
        return speakCount;
    }

    public boolean isDelivered() {
        return delivered;
    }

    public boolean isRefunded() {
        return refunded;
    }

    public void markDelivered() {
        delivered = true;
        updatedAt = Instant.now();
    }

    public void markRefunded() {
        refunded = true;
        updatedAt = Instant.now();
    }

    public boolean tryIncrement(int maxSpeaks) {
        if (speakCount >= maxSpeaks) {
            return false;
        }
        speakCount++;
        updatedAt = Instant.now();
        return true;
    }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "creator_user_id", length = 26)
        private String creatorUserId;

        @Column(name = "turn_id", length = 26)
        private String turnId;

        protected Key() {}

        Key(String creatorUserId, String turnId) {
            this.creatorUserId = creatorUserId;
            this.turnId = turnId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(creatorUserId, key.creatorUserId) && Objects.equals(turnId, key.turnId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(creatorUserId, turnId);
        }
    }
}
