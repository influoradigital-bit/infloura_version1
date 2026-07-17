package com.influora.repository;

import com.influora.domain.entity.ConversionWebhookSecret;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage-abstraction repository for {@link ConversionWebhookSecret} (V31 {@code
 * conversion_webhook_secrets}) -- see that entity's javadoc for the full trust model this backs.
 */
public interface ConversionWebhookSecretRepository extends JpaRepository<ConversionWebhookSecret, String> {

    /** Workspace-scoped lookup -- every brand-authed read/write path must confirm the secret belongs to the caller's workspace. */
    Optional<ConversionWebhookSecret> findByWorkspaceIdAndRevokedFalse(String workspaceId);
}
