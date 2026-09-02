package com.influora.web.dto.creator;

import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class CreatorProfileDtos {

    private CreatorProfileDtos() {}

    public record CreatorProfileSelfResponse(
            String id,
            String userId,
            String displayName,
            String username,
            String bio,
            String avatarUrl,
            String coverImageUrl,
            String city,
            List<String> categories,
            List<String> languages,
            List<String> contentStyles,
            List<PlatformStatResponse> platforms,
            BigDecimal rateMin,
            BigDecimal rateMax,
            String currency,
            boolean discoverable,
            boolean verified,
            long totalFollowers,
            BigDecimal engagementRate,
            boolean onboardingComplete,
            int profileCompleteness,
            // PHONE-0829 — users.phone_number, read back so a set/clear round-trips visibly (UI
            // Honesty rule: a control may not represent state it does not persist). Raw digits
            // (normalized 10-digit Indian mobile), null when not set/cleared.
            String phone) {}

    /**
     * PHONE-0829 — {@code phone} is OPTIONAL (existing creators have none) and nullable/clearable:
     * {@code null} means "leave unchanged" (same convention every other field on this record
     * already uses, enforced by {@link com.influora.service.CreatorProfileService}'s null-guards);
     * an explicit blank string ("") means "clear the stored number". Raw client input — spaces,
     * {@code +91}, a leading {@code 0} are all normalized server-side before persisting (never
     * trust the client), see {@code CreatorProfileService#applyPhone}. Not {@code @Pattern}-checked
     * here because the raw pre-normalization value may legitimately contain the punctuation the
     * final stored value must not.
     */
    public record CreatorProfilePatchRequest(
            @Size(max = 100) String displayName,
            @Size(max = 500) String username,
            @Size(max = 2000) String bio,
            @Size(max = 500) String avatarUrl,
            @Size(max = 500) String coverImageUrl,
            @Size(max = 100) String city,
            @Size(max = 3) List<@Size(max = 80) String> categories,
            List<@Size(max = 40) String> languages,
            List<@Size(max = 40) String> contentStyles,
            @DecimalMin("0") BigDecimal rateMin,
            @DecimalMin("0") BigDecimal rateMax,
            Boolean discoverable,
            @Size(max = 20) String phone) {}
}
