package com.influora.repository;

import com.influora.domain.entity.Contract;
import com.influora.domain.enums.ContractStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractRepository extends JpaRepository<Contract, String> {

    Optional<Contract> findByIdAndWorkspaceId(String id, String workspaceId);

    /**
     * [BE-1, contract-flow-architecture-2026-07-23 §6.4] Immutability/duplicate guard for {@code
     * ContractService#generate} — a collaboration may have at most one non-CANCELLED contract at a
     * time. {@code status <> :status} rather than an explicit IN-list of the other four statuses so
     * this stays correct if {@link ContractStatus} ever grows a new terminal/non-terminal value.
     */
    boolean existsByCollaborationIdAndStatusNot(String collaborationId, ContractStatus status);

    /**
     * [BE-1, contract-flow-architecture-2026-07-23 §6.4] Deterministic "current contract for a
     * collaboration" lookup — {@code findByCollaborationIdOrderByVersionDesc} orders ONLY by
     * {@code version}, which is unstable (every row defaults to {@code version=1}, see {@code
     * Contract.Builder#build}) whenever more than one row exists for the same collaboration.
     * {@code createdAt DESC} as a secondary sort key makes "most recent wins" deterministic
     * regardless of how many rows exist or what order the DB happens to return ties in. Used by
     * {@code DealService#toDealResponse} to pick the contract surfaced on {@code DealResponse}.
     */
    List<Contract> findByCollaborationIdOrderByVersionDescCreatedAtDesc(String collaborationId);

    /**
     * Creator-scoped contract lookup — ownership is one hop away via {@code collaboration_id}
     * (mirrors {@code CollaborationRepository#findByIdAndCreatorId}).
     */
    @Query(
            "SELECT c FROM Contract c WHERE c.id = :id AND c.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId)")
    Optional<Contract> findByIdAndCreatorId(
            @Param("id") String id, @Param("creatorUserId") String creatorUserId);

    List<Contract> findByCollaborationIdOrderByVersionDesc(String collaborationId);

    List<Contract> findByWorkspaceId(String workspaceId);

    /** All contracts for collaborations owned by this creator user. */
    @Query(
            "SELECT c FROM Contract c WHERE c.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId) "
                    + "ORDER BY c.createdAt DESC")
    List<Contract> findByCreatorId(@Param("creatorUserId") String creatorUserId);

    /** Creator-scoped list filtered to a single deal (collaboration). */
    @Query(
            "SELECT c FROM Contract c WHERE c.collaborationId = :collaborationId AND c.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId) "
                    + "ORDER BY c.version DESC")
    List<Contract> findByCollaborationIdAndCreatorId(
            @Param("collaborationId") String collaborationId,
            @Param("creatorUserId") String creatorUserId);

    /** Contracts awaiting the creator's signature. */
    @Query(
            "SELECT c FROM Contract c WHERE c.creatorSignedAt IS NULL AND c.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId) "
                    + "ORDER BY c.createdAt DESC")
    List<Contract> findUnsignedByCreatorId(@Param("creatorUserId") String creatorUserId);
}
