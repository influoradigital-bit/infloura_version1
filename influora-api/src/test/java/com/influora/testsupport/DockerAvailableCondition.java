package com.influora.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Skips (not errors) any {@link AbstractIntegrationTest} subclass when no Docker environment is
 * reachable, instead of letting {@code @Testcontainers}' static {@code @Container} field blow up
 * with an {@code IllegalStateException} during container startup.
 *
 * <p>Why an {@code ExecutionCondition} and not {@code Assumptions.assumeTrue(...)} in a
 * {@code @BeforeAll}: with {@code @Testcontainers} + a static {@code @Container} field, the
 * container's {@code start()} call happens inside {@code TestcontainersExtension#beforeAll}
 * (a {@code BeforeAllCallback}), which the JUnit 5 engine invokes for the failing Docker-less
 * environment BEFORE any user-defined {@code @BeforeAll} method runs -- confirmed from the
 * baseline failure's stack trace (see verification notes), where the error originates at
 * {@code TestcontainersExtension$StoreAdapter.start -> GenericContainer.start ->
 * DockerClientProviderStrategy}. An {@code assumeTrue} placed in {@code @BeforeAll} would never
 * get a chance to run -- the extension's own {@code beforeAll} already throws first.
 *
 * <p>{@code ExecutionCondition} extensions are evaluated by the JUnit 5 engine as a distinct,
 * earlier phase -- before any {@code BeforeAllCallback} (including
 * {@code TestcontainersExtension}) is invoked for that class -- so disabling here prevents
 * {@code TestcontainersExtension#beforeAll} from ever calling {@code MYSQL.start()}.
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
