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

    /** Brand-scoped list filtered to a single deal (collaboration) — C-1 fix. */
    List<Contract> findByWorkspaceIdAndCollaborationId(String workspaceId, String collaborationId);

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

    /**
     * [F-0623] Contracts genuinely awaiting the creator's signature. The original query filtered
     * only {@code creatorSignedAt IS NULL} — that also matches a {@code DRAFT} contract (not yet
     * sent to either party) and a {@code CANCELLED} one, so the creator dashboard's "brand signed,
     * your turn" list could show a contract the brand never signed, or one that no longer exists
     * in any actionable sense. {@code status = PENDING_SIGNATURES} is the one state that means
     * "sent for signing, not yet complete"; {@link ContractStatus#ACTIVE} already implies
     * {@code creatorSignedAt IS NOT NULL} so excluding it here is redundant with the existing
     * filter, not a behaviour change, but naming it explicitly is what makes the query say what
     * the list actually claims.
     *
     * <p>[F-0623 round 2] There is no contract-cancel endpoint anywhere in this codebase — a
     * Contract row itself can never BE cancelled — so filtering the contract's own status is not
     * enough on its own. The COLLABORATION it belongs to can still be cancelled ({@code
     * DealService#reject}), and nothing ever moves the orphaned contract out of {@code
     * PENDING_SIGNATURES} when that happens: it would stay in this list forever, and signing it
     * 409s (the collaboration-status guard in {@code ContractService#recordSignatureForCreator}).
     * The collaboration join now also excludes {@code CANCELLED} explicitly, rather than assume
     * the contract-status filter alone covers it.
     */
    @Query(
            "SELECT c FROM Contract c WHERE c.status = com.influora.domain.enums.ContractStatus.PENDING_SIGNATURES "
                    + "AND c.creatorSignedAt IS NULL AND c.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId "
                    + "AND co.status <> com.influora.domain.enums.CollaborationStatus.CANCELLED) "
                    + "ORDER BY c.createdAt DESC")
    List<Contract> findUnsignedByCreatorId(@Param("creatorUserId") String creatorUserId);
}
