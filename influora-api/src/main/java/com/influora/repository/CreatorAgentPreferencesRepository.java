package com.influora.repository;

import com.influora.domain.entity.CreatorAgentPreferences;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 1.2, A3/A7). {@code creatorId} here is always a {@code
 * creator_profiles.id} — see {@link CreatorAgentPreferences} class javadoc.
 *
 * <p><b>Info barrier (A7a):</b> no class under a brand-facing package/name (service/meera/brand*,
 * any {@code *Brand*}-named class under {@code web}/{@code service.meera}) may import this
 * interface — enforced by {@code InfoBarrierTest}. The floors this repository reads are exactly
 * the numbers SPEC.md &sect;3.2 says must never reach a brand.
 */
public interface CreatorAgentPreferencesRepository extends JpaRepository<CreatorAgentPreferences, String> {

    Optional<CreatorAgentPreferences> findByCreatorId(String creatorId);
}
