package com.influora.repository;

import com.influora.domain.entity.CreatorBankAccount;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreatorBankAccountRepository extends JpaRepository<CreatorBankAccount, String> {

    List<CreatorBankAccount> findByCreatorUserIdOrderByCreatedAtDesc(String creatorUserId);

    Optional<CreatorBankAccount> findByIdAndCreatorUserId(String id, String creatorUserId);

    Optional<CreatorBankAccount> findByCreatorUserIdAndPrimaryTrue(String creatorUserId);

    /**
     * Locks every row for this creator for the duration of the caller's transaction. Used by
     * {@code CreatorBankAccountService.setPrimary}/{@code addInstrument} to serialize concurrent
     * primary-selection changes for the same creator — without this, two concurrent calls can both
     * read "no conflict" before either commits and both end up primary (QA-caught race). The
     * {@code uq_creator_bank_accounts_primary_marker} unique index added in V62 is the authoritative
     * backstop if this lock is ever bypassed; this lock exists so a normal double-click doesn't
     * surface as a 409 to the user in the first place.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CreatorBankAccount c WHERE c.creatorUserId = :creatorUserId")
    List<CreatorBankAccount> lockAllForCreatorUpdate(@Param("creatorUserId") String creatorUserId);
}
