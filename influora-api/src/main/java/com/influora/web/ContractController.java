package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.ContractService;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractPdfDownloadResponse;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.ContractSignRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/contracts")
public class ContractController {

    private final ContractService contractService;
    private final BrandContextService brandContext;

    public ContractController(ContractService contractService, BrandContextService brandContext) {
        this.contractService = contractService;
        this.brandContext = brandContext;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractResponse> generate(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody ContractGenerateRequest body) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(contractService.generate(principal, workspace.getId(), body));
    }

    @GetMapping
    public ApiResponse<List<ContractResponse>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String dealId) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.listForCreator(principal, dealId));
        }
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(contractService.listForBrand(principal, workspace.getId(), dealId));
    }

    /** Contracts awaiting the authenticated creator's signature (exec plan Week 3). */
    @GetMapping("/unsigned")
    public ApiResponse<List<ContractResponse>> listUnsigned(
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }
        return ApiResponse.ok(contractService.listUnsignedForCreator(principal));
    }

    @GetMapping("/{contractId}")
    public ApiResponse<ContractResponse> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String contractId) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.getForCreator(principal, contractId));
        }
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(contractService.get(principal, workspace.getId(), contractId));
    }

    @PostMapping("/{contractId}/sign")
    public ApiResponse<ContractResponse> sign(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String contractId,
            @Valid @RequestBody(required = false) ContractSignRequest body) {
        // [F-0292] The typed full name the e-sign UI calls the legally binding act
        // (contracts-and-deliverables.tsx) -- carried on the request body as `name`, previously
        // dropped because ContractSignRequest had no field for it.
        String signerName = body != null ? body.name() : null;
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(
                    contractService.recordSignatureForCreator(principal, contractId, signerName));
        }
        var workspace = brandContext.requireBrandWorkspace(principal);
        // [Fix: brand-feature-audit.md #2] Server-derive the signer role from the authenticated
        // principal's own userType instead of REQUIRING `body.role()`. Every principal that
        // reaches this branch is BRAND-authenticated (`requireBrandWorkspace` above), and the
        // FE's only real call path (`api.ts:1466`, `signContract`) sends just
        // `{name, agreedAt}` -- no `role` -- to self-attest the brand's own signature, which
        // previously 400'd with INVALID_SIGNER_ROLE on every brand sign. Mirrors how
        // `recordSignatureForCreator` (creator branch above) ignores the body entirely and
        // derives the role from the authenticated identity.
        //
        // An explicit `role` is still honored when a caller sends one -- this preserves the
        // elevated-member CREATOR-relay path documented on
        // `ContractService#recordSignature` (Kabir E2 LOW-4: a brand OWNER/ADMIN/MANAGER
        // recording the creator's out-of-band signature). That path is not exercised by any FE
        // call site today, but nothing here removes it; `recordSignature` still validates
        // `role` is BRAND or CREATOR and still gates CREATOR-relay behind elevated membership.
        String role = (body != null && body.role() != null && !body.role().isBlank())
                ? body.role()
                : "BRAND";
        return ApiResponse.ok(
                contractService.recordSignature(
                        principal, workspace.getId(), contractId, role, signerName));
    }

    /**
     * Mints a fresh secure presigned download link for the contract PDF (P0 #2). 404s with
     * {@code CONTRACT_PDF_NOT_READY} until the contract is fully signed by both parties and PDF
     * generation has completed.
     */
    @GetMapping("/{contractId}/pdf-download-url")
    public ApiResponse<ContractPdfDownloadResponse> pdfDownloadUrl(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String contractId) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.getPdfDownloadUrlForCreator(principal, contractId));
        }
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(
                contractService.getPdfDownloadUrl(principal, workspace.getId(), contractId));
    }
}
