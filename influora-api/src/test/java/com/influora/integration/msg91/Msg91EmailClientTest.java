package com.influora.integration.msg91;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.InfluoraEnvironment;
import com.influora.service.notification.UnsubscribeTokenService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * T-ADMINMAIL-0903 round 3, B2 (REVIEW-R2.md ship-blocker): {@link Msg91EmailClient#renderPreview}
 * used to call {@code buildUnsubscribeUrl(sampleUserId, templateKey)}, which mints a REAL,
 * working {@code UnsubscribeTokenService} HMAC token — redeemable at the unauthenticated {@code
 * GET /notifications/unsubscribe-link} — for whatever user a caller happened to be previewing,
 * with no rate limit and no audit trail on that path. A preview never sends anything, so this
 * proves the fix at the one place a real token could still leak back in: {@code renderPreview}
 * itself never asks {@link UnsubscribeTokenService} for anything.
 *
 * <p>Falsification: reverting {@code renderPreview} to accept a {@code sampleUserId} and call
 * {@code buildUnsubscribeUrl} again turns this red — {@code generateToken} gets invoked, and the
 * rendered HTML carries a real {@code /notifications/unsubscribe-link?token=...} href instead of
 * the inert placeholder. Verified directly.
 */
@ExtendWith(MockitoExtension.class)
class Msg91EmailClientTest {

    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock private InfluoraEnvironment environment;
    @Mock private UnsubscribeTokenService unsubscribeTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Msg91EmailClient client() {
        return new Msg91EmailClient(
                mailSenderProvider,
                "noreply@mail.influora.in",
                "Influora",
                "https://api.influora.in",
                "",
                environment,
                objectMapper,
                unsubscribeTokenService);
    }

    @Test
    @DisplayName(
            "renderPreview never mints a real unsubscribe HMAC token — the footer link is an"
                    + " inert placeholder, not a working /notifications/unsubscribe-link")
    void renderPreviewNeverMintsRealUnsubscribeToken() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", "Hi there");
        data.put("bodyText", "Body text.");
        data.put("ctaLabel", null);
        data.put("ctaUrl", null);

        EmailPreviewResult result = client().renderPreview("admin.custom", data);

        verify(unsubscribeTokenService, never()).generateToken(anyString(), anyString());
        assertTrue(
                result.html().contains("#unsubscribe-preview"),
                "preview footer must use the inert placeholder href: " + result.html());
        assertFalse(
                result.html().contains("/notifications/unsubscribe-link"),
                "preview must never link to the real, token-bearing unsubscribe endpoint: "
                        + result.html());
    }
}
