package com.influora.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runtime environment gate keyed on the active Spring profile, not {@code influora.env} ({@code
 * APP_ENV}).
 *
 * <p><b>W0-3 fix (2026-07-15):</b> previously this read {@code influora.env}, which defaults to
 * {@code "dev"} when unset. That default is fail-OPEN: a prod deploy that forgot to set {@code
 * influora.env=prod} (while correctly setting {@code SPRING_PROFILES_ACTIVE=prod}, since that's
 * what actually selects {@code application-prod.yml}) would silently report {@code isDev()==true}
 * and every dev-only mock/bypass path (MSG91 mock-success, Razorpay stub ids, etc.) would stay
 * active in prod. Deriving from the Spring profile instead means the only way to get {@code
 * isDev()==true} is to explicitly activate the {@code dev} profile — matching {@code
 * .env.example}'s {@code SPRING_PROFILES_ACTIVE=dev} — so an unset/misconfigured profile now
 * fails closed (not dev) instead of failing open.
 *
 * <p>{@link SecretsStartupValidator} intentionally keeps its own independent {@code
 * influora.env}-gated check (see that class's javadoc) as a belt-and-suspenders layer for the
 * inverse mistake — {@code SPRING_PROFILES_ACTIVE} left unset entirely.
 */
@Component
public class InfluoraEnvironment {

    private final Environment environment;

    public InfluoraEnvironment(Environment environment) {
        this.environment = environment;
    }

    public boolean isDev() {
        return environment.matchesProfiles("dev");
    }
}
