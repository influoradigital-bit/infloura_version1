package com.influora.repository;

import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.enums.CreditLedgerReason;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-V2 (SPEC.md §4) — the money record repository. */
public interface CreatorCreditLedgerRepository extends JpaRepository<CreatorCreditLedgerEntry, String> {

    /** Every ledger row (across every touched grant) for a set of reference ids — used to load a turn/brief's DEBIT rows before releasing them. */
    List<CreatorCreditLedgerEntry> findByCreatorUserIdAndReferenceIdIn(
            String creatorUserId, Collection<String> referenceIds);

    List<CreatorCreditLedgerEntry> findByCreatorUserIdAndReferenceIdInAndReason(
            String creatorUserId, Collection<String> referenceIds, CreditLedgerReason reason);
}
