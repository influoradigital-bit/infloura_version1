package com.influora.repository;

import com.influora.domain.entity.CreatorVoiceSpeak;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-V2 (SPEC.md §3, Kabir C5) — per-turn Sarvam speak-call counter. */
public interface CreatorVoiceSpeakRepository extends JpaRepository<CreatorVoiceSpeak, CreatorVoiceSpeak.Key> {

    Optional<CreatorVoiceSpeak> findByIdCreatorUserIdAndIdTurnId(String creatorUserId, String turnId);
}
