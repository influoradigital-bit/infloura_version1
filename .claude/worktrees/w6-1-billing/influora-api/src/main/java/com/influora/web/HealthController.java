package com.influora.web;

import com.influora.integration.storage.R2StorageService;
import java.util.Map;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final R2StorageService r2StorageService;
    private final HealthEndpoint healthEndpoint;

    public HealthController(R2StorageService r2StorageService, HealthEndpoint healthEndpoint) {
        this.r2StorageService = r2StorageService;
        this.healthEndpoint = healthEndpoint;
    }

    // Meera I-1b — previously always returned hardcoded "ok" with no DB check. Delegates to the
    // actuator HealthEndpoint (spring-boot-starter-actuator, added in pom.xml), which auto-wires a
    // DataSourceHealthIndicator that actually pings the database; overall status is DOWN if the
    // DB is unreachable, instead of silently reporting healthy.
    @GetMapping("/health")
    public Map<String, Object> health() {
        HealthComponent actuatorHealth = healthEndpoint.health();
        return Map.of(
                "status", actuatorHealth.getStatus().getCode(),
                "storage", Map.of("r2", r2StorageService.isAvailable() ? "configured" : "not_configured"));
    }
}
