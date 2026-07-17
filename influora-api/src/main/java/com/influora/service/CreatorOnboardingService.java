package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.User;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.payout.CreatorBankAccountService;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorIdResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorKycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorPayoutRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorPayoutResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorProfileRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorSocialRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorSocialResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OkResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N1 (Wave 6) — creator equivalent of {@link OnboardingService} (which only ever covered
 * {@code /onboarding/brand/*}). Backs {@code CreatorOnboardingController}; the frontend
 * (creator-onboarding.tsx) was already fully wired and calling {@code api.onboarding.*Creator*}
 * with nothing on the other end.
 */
@Service
public class CreatorOnboardingService {

    private final CreatorContextService creatorContext;
    private final CreatorProfileRepository creatorProfileRepository;
    private final PlatformStatRepository platformStatRepository;
    private final UserRepository userRepository;
    private final CreatorBankAccountService creatorBankAccountService;

    public CreatorOnboardingService(
            CreatorContextService creatorContext,
            CreatorProfileRepository creatorProfileRepository,
            PlatformStatRepository platformStatRepository,
            UserRepository userRepository,
            CreatorBankAccountService creatorBankAccountService) {
        this.creatorContext = creatorContext;
        this.creatorProfileRepository = creatorProfileRepository;
        this.platformStatRepository = platformStatRepository;
        this.userRepository = userRepository;
        this.creatorBankAccountService = creatorBankAccountService;
    }

    /**
     * See {@link CreatorSocialResponse} javadoc — no real OAuth token exchange exists yet for any
     * platform, so this persists the connection (one row per creator+platform, upserted) without
     * fabricating handle/follower data. {@code oauthCode} is accepted and validated non-blank (it's
     * the hook point for a future real exchange) but not decoded today.
     */
    @Transactional
    public CreatorSocialResponse connectSocial(AuthPrincipal principal, CreatorSocialRequest req) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        String platform = req.platform().trim().toUpperCase();

        PlatformStat stat =
                platformStatRepository
                        .findByCreatorProfileIdAndPlatform(profile.getId(), platform)
                        .orElseGet(
                                () ->
                                        PlatformStat.builder()
                                                .id(Ulids.newUlid())
                                                .creatorProfileId(profile.getId())
                                                .platform(platform)
                                                .followers(0)
                                                .verified(false)
                                                .build());
        platformStatRepository.save(stat);

        return new CreatorSocialResponse(platform, stat.getHandle() != null ? stat.getHandle() : "", stat.getFollowers());
    }

    @Transactional
    public CreatorIdResponse saveProfile(AuthPrincipal principal, CreatorProfileRequest req) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        if (req.rateMin() != null
                && req.rateMax() != null
                && req.rateMin().compareTo(req.rateMax()) > 0) {
            throw new ApiException(
                    "INVALID_RATE_RANGE", "rateMin cannot exceed rateMax", HttpStatus.BAD_REQUEST);
        }

        profile.applySelfEdit(
                req.displayName(),
                req.bio(),
                null,
                null,
                req.city(),
                JsonLists.toJson(req.verticals()),
                JsonLists.toJson(req.languages()),
                null,
                req.rateMin(),
                req.rateMax(),
                null);

        creatorProfileRepository.save(profile);
        return new CreatorIdResponse(profile.getId());
    }

    @Transactional
    public OkResponse complete(AuthPrincipal principal) {
        creatorContext.requireCreator(principal);
        User user =
                userRepository
                        .findById(principal.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
        user.setOnboardingCompleted(true);
        userRepository.save(user);
        return new OkResponse(true);
    }

    /**
     * Deferred to first withdrawal per the onboarding UI. Reuses the D14 tax-identity {@code pan}
     * column (same real-world PAN) plus the new N1 identity-KYC columns
     * (V20260715190000__creator_identity_kyc.sql).
     */
    @Transactional
    public KycResponse submitKyc(AuthPrincipal principal, CreatorKycRequest req) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        profile.applyIdentityKyc(req.pan().toUpperCase(), req.aadhaarLast4(), req.selfieUrl());
        creatorProfileRepository.save(profile);
        return new KycResponse(profile.getIdentityKycStatus().name());
    }

    /**
     * Deferred to first withdrawal per the onboarding UI. Routes through the SAME encrypted
     * {@code CreatorBankAccountService} N3 wires up to WalletController — one PII-encrypted
     * persistence path for creator payout instruments, not two. {@code accountName} (bank-transfer
     * beneficiary name) has no column on {@code CreatorBankAccount} today; it is accepted for
     * client-contract compatibility but not persisted — flagged for Priya, not silently dropped
     * without a record of the gap.
     */
    @Transactional
    public CreatorPayoutResponse savePayout(AuthPrincipal principal, CreatorPayoutRequest req) {
        String method = req.method() == null ? "" : req.method().trim().toLowerCase();
        CreatorBankAccount account;
        if ("upi".equals(method)) {
            if (req.upiId() == null || req.upiId().isBlank()) {
                throw new ApiException(
                        "INVALID_PAYOUT_DETAILS", "upiId is required for method=upi", HttpStatus.BAD_REQUEST);
            }
            account = creatorBankAccountService.addInstrument(principal, "UPI", req.upiId(), null, null);
        } else if ("bank".equals(method)) {
            if (req.bankAccount() == null
                    || req.bankAccount().isBlank()
                    || req.ifsc() == null
                    || req.ifsc().isBlank()) {
                throw new ApiException(
                        "INVALID_PAYOUT_DETAILS",
                        "bankAccount and ifsc are required for method=bank",
                        HttpStatus.BAD_REQUEST);
            }
            account =
                    creatorBankAccountService.addInstrument(
                            principal, "BANK", req.bankAccount(), req.ifsc(), null);
        } else {
            throw new ApiException(
                    "INVALID_PAYOUT_METHOD", "method must be 'upi' or 'bank'", HttpStatus.BAD_REQUEST);
        }
        // [Priya flag] req.accountName() (bank-transfer beneficiary name) is accepted for client
        // contract compatibility but has no column on CreatorBankAccount yet — not persisted.
        return new CreatorPayoutResponse(account.getId());
    }
}
