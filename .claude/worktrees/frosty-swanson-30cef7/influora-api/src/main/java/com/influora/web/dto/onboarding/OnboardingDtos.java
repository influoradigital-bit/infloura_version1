package com.influora.web.dto.onboarding;

import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class OnboardingDtos {

    private OnboardingDtos() {}

    public record BrandCompanyRequest(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank
                    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Invalid slug format")
                    @Size(max = 100)
                    String companySlug,
            @NotNull WorkspaceType workspaceType,
            @NotBlank @Size(max = 100) String industry,
            @NotBlank @Size(max = 50) String companySize,
            @Size(max = 500) String websiteUrl,
            @Size(max = 5000) String description,
            @Size(max = 500) String logoUrl) {}

    public record WorkspaceIdResponse(String workspaceId) {}

    public record OkResponse(boolean ok) {}

    public record KycRequest(
            @NotBlank
                    @Pattern(
                            regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
                            message = "Invalid GSTIN format")
                    String gstin,
            @NotBlank @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN format")
                    String pan,
            @NotBlank @Size(max = 500) String gstinDocUrl,
            @NotBlank @Size(max = 500) String panDocUrl) {}

    public record KycResponse(String kycStatus) {}

    public record SlugCheckResponse(String slug, boolean available, java.util.List<String> suggestions) {}
}
