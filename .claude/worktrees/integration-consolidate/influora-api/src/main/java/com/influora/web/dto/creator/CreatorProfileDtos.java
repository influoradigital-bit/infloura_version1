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
            int profileCompleteness) {}

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
            Boolean discoverable) {}
}
