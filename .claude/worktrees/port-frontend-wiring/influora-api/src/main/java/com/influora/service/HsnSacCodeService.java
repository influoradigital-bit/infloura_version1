package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.HsnSacCode;
import com.influora.domain.enums.HsnSacAppliesTo;
import com.influora.repository.HsnSacCodeRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Configurable HSN/SAC lookup (Rohan build-flag #4) — codes are seeded/admin-editable data
 * ({@code hsn_sac_codes}), never a hardcoded Java constant. Cached since these change rarely (a
 * CA-confirmed code revision, not a per-render lookup) and are read on every invoice PDF render.
 */
@Service
public class HsnSacCodeService {

    private final HsnSacCodeRepository repository;

    public HsnSacCodeService(HsnSacCodeRepository repository) {
        this.repository = repository;
    }

    @Cacheable("hsnSacCodes")
    public String resolveCode(HsnSacAppliesTo appliesTo) {
        return repository
                .findByAppliesTo(appliesTo)
                .map(HsnSacCode::getCode)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "HSN_SAC_CODE_NOT_CONFIGURED",
                                        "No HSN/SAC code configured for " + appliesTo,
                                        HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
