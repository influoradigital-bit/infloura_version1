package com.influora.service.meera.tool;

import com.influora.common.JsonLists;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorProfileSpecifications;
import com.influora.web.dto.meera.MeeraToolDtos.CreatorSummary;
import com.influora.web.dto.meera.MeeraToolDtos.ShowCreatorsResult;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R-tier executor (06-MEERA-PERMISSIONS-MATRIX.md row 5): "Rank + explain matched creators from
 * verified pool" — reads verified stats only, tenant-scoped, no raw creator PII returned to the
 * prompt ([SEC: G3]).
 *
 * <p>Only fields safe to hand back to an LLM prompt are ever mapped into {@link CreatorSummary}:
 * discoverable/verified creator-profile aggregate stats (followers, engagement, categories,
 * city). No email, phone, bank, KYC, or any {@code User}-table field is read or returned — this
 * executor never touches {@code UserRepository}.
 *
 * <p>Tenant scope: {@code workspaceId} on the envelope is validated upstream by
 * {@code OnBehalfAuthResolver} against the on-behalf JWT before this executor runs; the executor
 * itself does not filter creators by workspace (creators are a shared discoverable pool, not
 * workspace-owned data) — the tenant boundary that matters here is "which brand is asking",
 * already enforced before this class is reached.
 */
@Service
public class ShowCreatorsExecutor {

    private static final int MAX_RESULTS = 10;

    private final CreatorProfileRepository creatorProfileRepository;
    private final AuditLogService auditLogService;

    public ShowCreatorsExecutor(CreatorProfileRepository creatorProfileRepository, AuditLogService auditLogService) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public ShowCreatorsResult execute(String workspaceId, Map<String, Object> input) {
        // Field names match app/tools/schemas.py SHOW_CREATORS input_schema exactly: niche
        // (required), count (required, 1..100), city (optional).
        String niche = stringArg(input, "niche");
        String city = stringArg(input, "city");
        Integer requestedCount = intArg(input, "count");
        int limit = requestedCount != null ? Math.min(Math.max(requestedCount, 1), MAX_RESULTS) : MAX_RESULTS;

        Specification<CreatorProfile> spec =
                CreatorProfileSpecifications.combine(
                        CreatorProfileSpecifications.nameSearch(niche), CreatorProfileSpecifications.singleCity(city));

        List<CreatorProfile> profiles =
                creatorProfileRepository
                        .findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "totalFollowers")))
                        .getContent();

        List<CreatorSummary> summaries =
                profiles.stream()
                        .map(
                                p ->
                                        new CreatorSummary(
                                                p.getId(),
                                                p.getDisplayName(),
                                                p.getCity(),
                                                JsonLists.stringListFromJson(p.getCategoriesJson()),
                                                p.getTotalFollowers(),
                                                p.getEngagementRate(),
                                                p.isVerified()))
                        .toList();

        auditLogService.recordToolCall(
                workspaceId,
                "show_creators",
                "R",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("resultCount", summaries.size()));

        return new ShowCreatorsResult(summaries);
    }

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
