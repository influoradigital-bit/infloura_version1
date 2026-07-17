package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.ContractService;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.ContractSignRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/{contractId}")
    public ApiResponse<ContractResponse> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String contractId) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(contractService.get(principal, workspace.getId(), contractId));
    }

    @PostMapping("/{contractId}/sign")
    public ApiResponse<ContractResponse> sign(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String contractId,
            @Valid @RequestBody ContractSignRequest body) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(
                contractService.recordSignature(principal, workspace.getId(), contractId, body.role()));
    }
}
