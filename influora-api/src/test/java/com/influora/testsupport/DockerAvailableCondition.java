package com.influora.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Skips (not errors) any {@link AbstractIntegrationTest} subclass when no Docker environment is
 * reachable, instead of letting a test class try to run against a null/never-started
 * {@code MYSQL} container.
 *
 * <p>Why an {@code ExecutionCondition} and not {@code Assumptions.assumeTrue(...)} in a
 * {@code @BeforeAll}: {@link AbstractIntegrationTest}'s {@code MYSQL} field is initialized (and,
 * when Docker is reachable, started) in a static initializer that runs at class-load time --
 * before JUnit 5 has even finished discovering the test class, let alone reached any
 * user-defined {@code @BeforeAll} method. Originally (pre T-CI-CONTAINERS) that field was managed
 * by {@code @Testcontainers} + a static {@code @Container} field instead, where the identical
 * ordering problem applied one phase later: the container's {@code start()} call happened inside
 * {@code TestcontainersExtension#beforeAll} (a {@code BeforeAllCallback}), which the JUnit 5
 * engine invokes for a Docker-less environment BEFORE any user-defined {@code @BeforeAll} method
 * runs -- confirmed from the baseline failure's stack trace (see verification notes), where the
 * error originated at {@code TestcontainersExtension$StoreAdapter.start -> GenericContainer.start
 * -> DockerClientProviderStrategy}. Either way, an {@code assumeTrue} placed in
 * {@code @BeforeAll} would never get a chance to run -- something earlier already throws first.
 *
 * <p>{@code ExecutionCondition} extensions are evaluated by the JUnit 5 engine as a distinct,
 * earlier phase -- before any {@code BeforeAllCallback} and before Spring's
 * {@code @DynamicPropertySource} handling runs for that class -- so disabling here means nothing
 * downstream ever dereferences a null {@code MYSQL}.
 *
 * <p>Testcontainers 1.19.8 (the version resolved from this project's parent BOM) has no built-in
 * {@code @EnabledIfDockerAvailable} annotation -- that was added in a later release not present in
 * this repo's offline {@code ~/.m2} cache -- hence this hand-rolled condition using the same
 * {@code DockerClientFactory} check that annotation wraps.
 */
public class DockerAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
        } catch (Throwable t) {
            // Any failure while probing Docker is treated the same as "not available".
        }
        return ConditionEvaluationResult.disabled(
                "No Docker environment available -- skipping Testcontainers-backed integration test");
    }
}
