package com.influora.repository;

import com.influora.domain.entity.CreatorCreditPack;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-V2 (SPEC.md §5.4) — the credit pack catalogue. */
public interface CreatorCreditPackRepository extends JpaRepository<CreatorCreditPack, String> {

    Optional<CreatorCreditPack> findByCodeAndActiveTrue(String code);
}
