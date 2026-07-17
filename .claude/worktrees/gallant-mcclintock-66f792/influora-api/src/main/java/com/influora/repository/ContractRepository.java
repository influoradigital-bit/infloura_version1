package com.influora.repository;

import com.influora.domain.entity.Contract;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractRepository extends JpaRepository<Contract, String> {

    Optional<Contract> findByIdAndWorkspaceId(String id, String workspaceId);

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
