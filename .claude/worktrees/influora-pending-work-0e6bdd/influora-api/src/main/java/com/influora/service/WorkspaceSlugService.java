package com.influora.service;

import com.influora.common.SlugUtils;
import com.influora.common.Ulids;
import com.influora.repository.WorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceSlugService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceSlugService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public SlugAvailability checkAvailability(String rawSlug, String excludeWorkspaceId) {
        String slug = SlugUtils.slugify(rawSlug);
        boolean available =
                excludeWorkspaceId != null && !excludeWorkspaceId.isBlank()
                        ? !workspaceRepository.existsBySlugAndIdNot(slug, excludeWorkspaceId)
                        : !workspaceRepository.existsBySlug(slug);

        List<String> suggestions = new ArrayList<>();
        if (!available) {
            suggestions = suggestAlternatives(slug, excludeWorkspaceId);
        }

        return new SlugAvailability(slug, available, suggestions);
    }

    public void ensureSlugAvailable(String slug, String excludeWorkspaceId) {
        SlugAvailability check = checkAvailability(slug, excludeWorkspaceId);
        if (!check.available()) {
            throw new com.influora.common.ApiException(
                    "SLUG_NOT_AVAILABLE",
                    "Workspace slug is already taken",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
    }

    private List<String> suggestAlternatives(String base, String excludeWorkspaceId) {
        List<String> out = new ArrayList<>();
        String[] suffixes = {"-pvt", "-in", "-india", "-2026", "-hq"};
        for (String suffix : suffixes) {
            String candidate = base + suffix;
            if (out.size() >= 3) break;
            SlugAvailability c = checkAvailability(candidate, excludeWorkspaceId);
            if (c.available()) {
                out.add(candidate);
            }
        }
        int guard = 0;
        while (out.size() < 3 && guard++ < 10) {
            String candidate = base + "-" + ThreadLocalRandom.current().nextInt(100, 999);
            SlugAvailability c = checkAvailability(candidate, excludeWorkspaceId);
            if (c.available()) {
                out.add(candidate);
            }
        }
        if (out.isEmpty()) {
            out.add(base + "-" + Ulids.newUlid().substring(0, 6).toLowerCase());
        }
        return out;
    }

    public record SlugAvailability(String slug, boolean available, List<String> suggestions) {}
}
