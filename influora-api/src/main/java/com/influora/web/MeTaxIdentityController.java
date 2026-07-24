package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorTaxIdentityService;
import com.influora.web.dto.creator.CreatorTaxIdentityDtos.TaxIdentitySubmitRequest;
import com.influora.web.dto.creator.CreatorTaxIdentityDtos.TaxIdentitySubmitResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D14-B creator tax-identity (GSTIN/PAN) capture. Sibling of {@link MeCreatorProfileController}
 * (same {@code /me/*} self-service, {@code @AuthenticationPrincipal}-scoped, no path-param creator
 * id) — kept as its own controller/route rather than folded into {@code
 * CreatorProfilePatchRequest} since tax identity has its own validation rules (GSTIN/PAN format,
 * "at least one required") distinct from a generic profile-field patch.
 *
 * <p>Matches {@code src/lib/api.ts}'s {@code creatorTaxIdentity.submit()} client, which previously
 * always rejected with a typed {@code NOT_IMPLEMENTED} because this route did not exist.
 */
@RestController
@RequestMapping("/me/tax-identity")
public class MeTaxIdentityController {

    private final CreatorTaxIdentityService creatorTaxIdentityService;

    public MeTaxIdentityController(CreatorTaxIdentityService creatorTaxIdentityService) {
        this.creatorTaxIdentityService = creatorTaxIdentityService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaxIdentitySubmitResponse>> submit(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody TaxIdentitySubmitRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(creatorTaxIdentityService.submit(principal, body)));
    }
}
