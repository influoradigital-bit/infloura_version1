package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.WalletService;
import com.influora.web.dto.money.MoneyDtos.WalletBalanceResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class WalletController {

    private final WalletService walletService;
    private final BrandContextService brandContext;

    public WalletController(WalletService walletService, BrandContextService brandContext) {
        this.walletService = walletService;
        this.brandContext = brandContext;
    }

    @GetMapping("/balance")
    public ApiResponse<WalletBalanceResponse> getBalance(@AuthenticationPrincipal AuthPrincipal principal) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return ApiResponse.ok(walletService.getBalance(workspace.getId()));
    }
}
