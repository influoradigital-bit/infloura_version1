package com.influora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime environment gate keyed on {@code influora.env} ({@code APP_ENV}), not {@code
 * spring.profiles.active}. Matches {@link SecretsStartupValidator} — profile activation is too
 * easy to leave unset by accident in staging.
 */
@Component
public class InfluoraEnvironment {

    @Value("${influora.env:dev}")
    private String env;

    public boolean isDev() {
        return "dev".equalsIgnoreCase(env);
    }
}
