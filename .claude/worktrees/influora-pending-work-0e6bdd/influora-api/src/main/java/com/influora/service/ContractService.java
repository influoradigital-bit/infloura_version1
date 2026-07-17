package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.MilestoneDto;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract + milestone generation, signature recording, and state transitions.
 *
 * <p>{@code generate} computes {@code total_amount} as the sum of the supplied milestone
 * amounts server-side (never trusts a client-supplied contract total) and stamps a SHA-256
 * tamper-hash reference alongside the persisted terms so downstream signature verification has
 * something immutable to check against.
 */
@Service
public class ContractService {

    private final ContractRepository contractRepository;
    private final PaymentMilestoneRepository milestoneRepository;
    private final CollaborationRepository collaborationRepository;
    private final BrandContextService brandContext;

    public ContractService(
            ContractRepository contractRepository,
            PaymentMilestoneRepository milestoneRepository,
            CollaborationRepository collaborationRepository,
            BrandContextService brandContext) {
        this.contractRepository = contractRepository;
        this.milestoneRepository = milestoneRepository;
        this.collaborationRepository = collaborationRepository;
        this.brandContext = brandContext;
    }

    @Transactional
    public ContractResponse generate(AuthPrincipal principal, String workspaceId, ContractGenerateRequest req) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);

        Collaboration collaboration =
                collaborationRepository
                        .findById(req.collaborationId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));

        List<MilestoneWriteRequest> milestoneReqs = req.milestones();
        if (milestoneReqs == null || milestoneReqs.isEmpty()) {
            throw new ApiException(
                    "MILESTONES_REQUIRED", "At least one milestone is required", HttpStatus.BAD_REQUEST);
        }

        BigDecimal totalAmount =
                milestoneReqs.stream()
                        .map(MilestoneWriteRequest::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAmount.signum() <= 0) {
            throw new ApiException(
                    "INVALID_CONTRACT_TOTAL", "Contract total must be positive", HttpStatus.BAD_REQUEST);
        }

        Contract contract =
                Contract.builder()
                        .id(Ulids.newUlid())
                        .collaborationId(collaboration.getId())
                        .workspaceId(workspaceId)
                        .totalAmount(totalAmount)
                        .termsJson(sha256TamperHash(req))
                        .build();
        contractRepository.save(contract);

        List<PaymentMilestone> milestones =
                milestoneReqs.stream()
                        .map(
                                m ->
                                        PaymentMilestone.builder()
                                                .id(Ulids.newUlid())
                                                .contractId(contract.getId())
                                                .collaborationId(collaboration.getId())
                                                .sequenceNo(m.sequenceNo())
                                                .description(m.description())
                                                .amount(m.amount())
                                                .dueDate(m.dueDate())
                                                .build())
                        .toList();
        milestoneRepository.saveAll(milestones);

        return toResponse(contract, milestones);
    }

    @Transactional
    public ContractResponse recordSignature(
            AuthPrincipal principal, String workspaceId, String contractId, String role) {
        brandContext.requireMember(principal, workspaceId);
        Contract contract = requireContract(contractId, workspaceId);

        if ("BRAND".equalsIgnoreCase(role)) {
            contract.recordBrandSignature();
        } else if ("CREATOR".equalsIgnoreCase(role)) {
            contract.recordCreatorSignature();
        } else {
            throw new ApiException(
                    "INVALID_SIGNER_ROLE", "role must be BRAND or CREATOR", HttpStatus.BAD_REQUEST);
        }
        contractRepository.save(contract);

        List<PaymentMilestone> milestones =
                milestoneRepository.findByContractIdOrderBySequenceNoAsc(contract.getId());
        return toResponse(contract, milestones);
    }

    @Transactional(readOnly = true)
    public ContractResponse get(AuthPrincipal principal, String workspaceId, String contractId) {
        brandContext.requireMember(principal, workspaceId);
        Contract contract = requireContract(contractId, workspaceId);
        List<PaymentMilestone> milestones =
                milestoneRepository.findByContractIdOrderBySequenceNoAsc(contract.getId());
        return toResponse(contract, milestones);
    }

    private Contract requireContract(String contractId, String workspaceId) {
        return contractRepository
                .findByIdAndWorkspaceId(contractId, workspaceId)
                .orElseThrow(
                        () -> new ApiException("CONTRACT_NOT_FOUND", "Contract not found", HttpStatus.NOT_FOUND));
    }

    private static String sha256TamperHash(ContractGenerateRequest req) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(req.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "{\"tamperHashSha256\":\"" + hex + "\"}";
        } catch (Exception e) {
            throw new ApiException(
                    "CONTRACT_HASH_FAILED", "Failed to compute contract tamper hash", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static ContractResponse toResponse(Contract contract, List<PaymentMilestone> milestones) {
        List<MilestoneDto> milestoneDtos =
                milestones.stream()
                        .map(
                                m ->
                                        new MilestoneDto(
                                                m.getId(),
                                                m.getContractId(),
                                                m.getCollaborationId(),
                                                m.getSequenceNo(),
                                                m.getDescription(),
                                                m.getAmount(),
                                                m.getCurrency(),
                                                m.getDueDate(),
                                                m.getStatus(),
                                                m.getEscrowHoldId()))
                        .toList();
        return new ContractResponse(
                contract.getId(),
                contract.getCollaborationId(),
                contract.getWorkspaceId(),
                contract.getVersion(),
                contract.getStatus(),
                contract.getTotalAmount(),
                contract.getCurrency(),
                contract.getPdfR2Key(),
                contract.getBrandSignedAt(),
                contract.getCreatorSignedAt(),
                contract.getEffectiveDate(),
                contract.getExpirationDate(),
                milestoneDtos,
                contract.getCreatedAt(),
                contract.getUpdatedAt());
    }
}
