package com.influora.integration.msg91;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MSG91 Email API client (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md). Sends templated emails
 * via MSG91's Email API v5.
 *
 * <p><b>Placeholder implementation:</b> In dev/test environments without MSG91 credentials, this
 * logs the email details and returns success. Production requires valid credentials.
 *
 * <p>No PII beyond name in email bodies per Kabir's security constraints (§7).
 */
@Component
public class Msg91EmailClient {

    private static final Logger log = LoggerFactory.getLogger(Msg91EmailClient.class);
    private static final String MSG91_API_URL = "https://control.msg91.com/api/v5/email/send";

    private final String authKey;
    private final String fromEmail;
    private final String fromName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public Msg91EmailClient(
            @Value("${msg91.auth-key:}") String authKey,
            @Value("${msg91.from-email:noreply@influora.com}") String fromEmail,
            @Value("${msg91.from-name:Influora}") String fromName,
            ObjectMapper objectMapper) {
        this.authKey = authKey;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Returns true if MSG91 credentials are configured (non-empty auth key).
     */
    public boolean isConfigured() {
        return authKey != null && !authKey.isBlank();
    }

    /**
     * Sends a templated email via MSG91. Returns true on success, false on failure.
     *
     * <p>In dev/test without credentials, logs and returns true (mock success).
     *
     * @param toEmail recipient email address
     * @param templateKey MSG91 template slug (e.g., "creator.proposal_received")
     * @param templateDataJson JSON string of merge fields for the template
     */
    public boolean sendTemplateEmail(String toEmail, String templateKey, String templateDataJson) {
        if (!isConfigured()) {
            // Dev/test mode - log and mock success
            log.info(
                    "[MOCK] Email would be sent: to={}, templateKey={}, data={}",
                    toEmail,
                    templateKey,
                    templateDataJson);
            return true;
        }

        try {
            Map<String, Object> payload = buildPayload(toEmail, templateKey, templateDataJson);
            String body = objectMapper.writeValueAsString(payload);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(MSG91_API_URL))
                            .header("authkey", authKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.debug("MSG91 email sent successfully: to={}, templateKey={}", toEmail, templateKey);
                return true;
            } else {
                log.error(
                        "MSG91 email failed: to={}, templateKey={}, status={}, body={}",
                        toEmail,
                        templateKey,
                        response.statusCode(),
                        response.body());
                return false;
            }
        } catch (Exception e) {
            log.error(
                    "MSG91 email error: to={}, templateKey={}, error={}", toEmail, templateKey, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildPayload(
            String toEmail, String templateKey, String templateDataJson) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> templateData =
                objectMapper.readValue(templateDataJson, LinkedHashMap.class);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", toEmail);
        payload.put("from", Map.of("email", fromEmail, "name", fromName));
        payload.put("template_id", templateKey);
        payload.put("variables", templateData);

        return payload;
    }
}
