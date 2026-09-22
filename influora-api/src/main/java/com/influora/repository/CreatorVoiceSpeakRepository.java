package com.influora.repository;

import com.influora.domain.entity.CreatorVoiceSpeak;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-V2 (SPEC.md §3, Kabir C5) — per-turn Sarvam speak-call counter. */
public interface CreatorVoiceSpeakRepository extends JpaRepository<CreatorVoiceSpeak, CreatorVoiceSpeak.Key> {

    Optional<CreatorVoiceSpeak> findByIdCreatorUserIdAndIdTurnId(String creatorUserId, String turnId);

    // Locking variants (T-CREATOR-CREDITS-V2, found by CreatorCreditConcurrencyIntegrationTest on
    // real MySQL): under REPEATABLE READ a PLAIN read inside a transaction that already read
    // anything (MeeraSessionService#doSendTurn reads the conversation first) sees that earlier
    // snapshot even after the account row lock is taken, so 20 parallel charges all saw the same
    // balance and overspent. A locking read always sees the latest committed row. Use these, not
    // the plain finders, for every read that decides a write after lockAccount.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT v FROM CreatorVoiceSpeak v WHERE v.id.creatorUserId = :creatorUserId "
                    + "AND v.id.turnId = :turnId")
    Optional<CreatorVoiceSpeak> lockByCreatorUserIdAndTurnId(
            @Param("creatorUserId") String creatorUserId, @Param("turnId") String turnId);
}
