package com.influora.repository;

import com.influora.domain.entity.Shipment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * wiki/decisions/shipment-backend-design-2026-07-24.md §3. Callers MUST authorize the owning
 * {@code Collaboration} first (via {@code CollaborationRepository.findByIdAndCreatorId} /
 * {@code findByIdAndWorkspaceId}) and only then look up the {@link Shipment} by {@code
 * collaboration_id} — never by this entity's own {@code id} from a client-supplied param.
 */
public interface ShipmentRepository extends JpaRepository<Shipment, String> {

    Optional<Shipment> findByCollaborationId(String collaborationId);
}
