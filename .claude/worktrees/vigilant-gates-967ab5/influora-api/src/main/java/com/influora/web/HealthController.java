package com.influora.web;

import com.influora.integration.storage.R2StorageService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final R2StorageService r2StorageService;

    public HealthController(R2StorageService r2StorageService) {
        this.r2StorageService = r2StorageService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "storage", Map.of("r2", r2StorageService.isAvailable() ? "configured" : "not_configured"));
    }
}
