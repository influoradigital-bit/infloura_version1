package com.influora.repository;

import com.influora.domain.entity.CreatorCreditPack;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.6. */
public interface CreatorCreditPackRepository extends JpaRepository<CreatorCreditPack, String> {

    List<CreatorCreditPack> findByActiveTrueOrderBySortOrderAsc();

    Optional<CreatorCreditPack> findByCodeAndActiveTrue(String code);
}
