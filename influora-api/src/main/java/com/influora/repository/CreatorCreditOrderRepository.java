package com.influora.repository;

import com.influora.domain.entity.CreatorCreditOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.6. */
public interface CreatorCreditOrderRepository extends JpaRepository<CreatorCreditOrder, String> {

    Optional<CreatorCreditOrder> findByCreatorUserIdAndIdempotencyKey(String creatorUserId, String idempotencyKey);

    Optional<CreatorCreditOrder> findByIdAndCreatorUserId(String id, String creatorUserId);
}
