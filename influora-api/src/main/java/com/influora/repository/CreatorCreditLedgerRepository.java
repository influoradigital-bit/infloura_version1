package com.influora.repository;

import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.enums.CreditLedgerReason;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.6.
 *
 * <p>Deliberately does NOT include {@code
 * existsByCreatorUserIdAndReasonAndCreatedAtGreaterThanEqual} (PLAN.md D1). The amended spec is
 * explicit: "Do NOT build this until Swapnil rules D2 = no" -- PLAN.md §2 D2's recorded default is
 * "Yes, everyone gets 2 free a week" (i.e. a pack purchase does not remove the free weekly
 * searches), which makes the D1 "who counts as a free-credit creator" predicate dead code under
 * the current default. If D2 is later ruled "no", add that finder back alongside the gating logic
 * it would support.
 */
public interface CreatorCreditLedgerRepository extends JpaRepository<CreatorCreditLedgerEntry, String> {

    List<CreatorCreditLedgerEntry> findByCreatorUserIdOrderByCreatedAtDesc(String creatorUserId, Pageable page);

    /** The §3.3 release guard-1 fix: refund only against a real debit row. */
    List<CreatorCreditLedgerEntry> findByCreatorUserIdAndReasonAndReferenceId(
            String creatorUserId, CreditLedgerReason reason, String referenceId);
}
