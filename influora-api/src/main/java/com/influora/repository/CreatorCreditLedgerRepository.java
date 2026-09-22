package com.influora.repository;

import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.enums.CreditLedgerReason;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-V2 (SPEC.md §4) — the money record repository. */
public interface CreatorCreditLedgerRepository extends JpaRepository<CreatorCreditLedgerEntry, String> {

    /** Every ledger row (across every touched grant) for a set of reference ids — used to load a turn/brief's DEBIT rows before releasing them. */
    List<CreatorCreditLedgerEntry> findByCreatorUserIdAndReferenceIdIn(
            String creatorUserId, Collection<String> referenceIds);

    List<CreatorCreditLedgerEntry> findByCreatorUserIdAndReferenceIdInAndReason(
            String creatorUserId, Collection<String> referenceIds, CreditLedgerReason reason);

    // Locking variants (T-CREATOR-CREDITS-V2, found by CreatorCreditConcurrencyIntegrationTest on
    // real MySQL): under REPEATABLE READ a PLAIN read inside a transaction that already read
    // anything (MeeraSessionService#doSendTurn reads the conversation first) sees that earlier
    // snapshot even after the account row lock is taken, so 20 parallel charges all saw the same
    // balance and overspent. A locking read always sees the latest committed row. Use these, not
    // the plain finders, for every read that decides a write after lockAccount.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT e FROM CreatorCreditLedgerEntry e WHERE e.creatorUserId = :creatorUserId "
                    + "AND e.referenceId IN :referenceIds")
    List<CreatorCreditLedgerEntry> lockByCreatorUserIdAndReferenceIdIn(
            @Param("creatorUserId") String creatorUserId, @Param("referenceIds") Collection<String> referenceIds);
}
