package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.User;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CreatorProfileRepository;
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
    private final UserRepository userRepository;
    private final CreatorBankAccountService creatorBankAccountService;

    /** [F-0390 D4] KYC-doc read-path key resolution — see {@link #resolveKycDocUrl}. */
    private final R2StorageService r2StorageService;

    private final R2Properties r2Properties;

    public CreatorOnboardingService(
            CreatorContextService creatorContext,
            CreatorProfileRepository creatorProfileRepository,
            UserRepository userRepository,
            CreatorBankAccountService creatorBankAccountService,
            R2StorageService r2StorageService,
            R2Properties r2Properties) {
        this.creatorContext = creatorContext;
        this.creatorProfileRepository = creatorProfileRepository;
        this.userRepository = userRepository;
        this.creatorBankAccountService = creatorBankAccountService;
        this.r2StorageService = r2StorageService;
        this.r2Properties = r2Properties;
    }

    /**
     * CR-108: no real OAuth token exchange exists for ANY social platform through this endpoint —
     * Instagram now has its own real, dedicated flow ({@link com.influora.web.MetaOAuthController}
     * / {@link com.influora.service.MetaConnectionService}, CR-120) and the frontend never calls
     * this endpoint any more (creator-onboarding.tsx shows an honest "coming soon" toast for
     * YouTube/TikTok/Twitter locally instead). This method used to paper over that by silently
     * upserting a {@code PlatformStat} row with {@code followers(0)}/{@code verified(false)} for
     * whatever platform was requested. That row fed straight into the same discovery-ranking /
     * brand-facing substrate ({@link com.influora.job.PlatformStatsAggregationJob}, {@code
     * CreatorMapper.toPlatformResponse}) that real, Meta-verified rows use, and brand-creator-profile.tsx
     * renders the number unconditionally — so brands saw a literal, confident-looking "0" next to a
     * platform the creator had supposedly "connected" live, not an honest not-yet-available state.
     *
     * <p>Per TECH-STACK.md rule 7 (no fabricated backend contracts), this now refuses the call
     * outright instead of inventing a row: nothing is persisted, so nothing fabricated ever reaches
     * discovery ranking or the brand-facing profile. Building real YouTube/TikTok/Twitter OAuth is
     * CR-119's scope, not this fix's — {@code oauthCode} stays accepted/validated non-blank on the
     * request DTO as the hook point for that future work.
     */
    @Transactional(readOnly = true)
    public CreatorSocialResponse connectSocial(AuthPrincipal principal, CreatorSocialRequest req) {
        creatorContext.requireCreatorProfile(principal);
        String platform = req.platform().trim().toUpperCase();
        throw new ApiException(
                "SOCIAL_OAUTH_NOT_IMPLEMENTED",
                "Live OAuth for " + platform + " isn't available yet. Connect it from Settings once it launches.",
                HttpStatus.NOT_IMPLEMENTED);
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

    /**
     * [F-0390 D4] Resolves a stored {@code CreatorProfile.selfieUrl} value — bare R2 key (new,
     * post-D4 uploads via {@code uploadForPurpose(..., "creator_kyc_selfie")}) OR a legacy
     * permanent public URL (pre-D4 rows) — to a short-lived presigned GET. Mirrors {@code
     * PortfolioService#resolveCoverUrl}/{@code #toCoverObjectKey} exactly, same as {@code
     * OnboardingService#resolveKycDocUrl}'s brand-side counterpart — see that method's javadoc for
     * why nothing in this codebase calls this yet (no existing response DTO exposes {@code
     * selfieUrl} back to a client today).
     */
    String resolveKycDocUrl(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String key = toKycDocObjectKey(stored);
        if (key == null || !r2StorageService.isAvailable()) {
            return stored;
        }
        try {
            return r2StorageService.presignGet(key).uploadUrl();
        } catch (RuntimeException e) {
            return stored;
        }
    }

    private String toKycDocObjectKey(String stored) {
        if (!stored.startsWith("http://") && !stored.startsWith("https://")) {
            return stored;
        }
        String base = r2Properties.getPublicUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (stored.startsWith(base + "/")) {
            return stored.substring(base.length() + 1);
        }
        return null;
    }
}
