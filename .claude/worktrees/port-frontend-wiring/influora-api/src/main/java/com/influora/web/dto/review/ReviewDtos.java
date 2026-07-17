package com.influora.web.dto.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ReviewDtos {

    private ReviewDtos() {}

    public record CreateReviewRequest(
            @NotBlank String collaborationId,
            @NotNull @Min(1) @Max(5) Integer stars,
            @Size(max = 1000) String text) {}

    public record FlagReviewRequest(@NotBlank @Size(max = 255) String reason) {}

    public record ReviewResponse(
            String id,
            String collaborationId,
            String reviewerType,
            String reviewerUserId,
            int stars,
            String text,
            Instant createdAt) {}

    public record FlagReviewResponse(String flagId, String status) {}
}
