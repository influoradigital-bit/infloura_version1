package com.influora.repository;

import com.influora.domain.entity.CreatorCreditWelcomeClaim;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-CREATOR-CREDITS-V2 (SPEC.md §6, Kabir K-12) — permanent welcome-grant claim rows. */
public interface CreatorCreditWelcomeClaimRepository extends JpaRepository<CreatorCreditWelcomeClaim, String> {

    boolean existsByCreatorUserId(String creatorUserId);
}
