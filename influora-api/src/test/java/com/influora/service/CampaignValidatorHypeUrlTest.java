package com.influora.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influora.common.ApiException;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.web.dto.campaign.CampaignDtos.HypeConfigDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [SEC: Kabir follow-up, meera-completion-flow review] Covers the server-side scheme guard on
 * {@code hype.sourceReelUrl}.
 *
 * <p>Meera can set {@code sourceReelUrl} on a HYPE draft (CreateCampaignExecutor's partial
 * HypeConfig), and the value is later rendered as a link. Before this guard only a non-blank check
 * ran server-side, so a {@code javascript:} / {@code data:} URL passed validation and safety rested
 * entirely on React's href sanitization plus the FE form's own submit check.
 *
 * <p>Deliberately named for this one concern (not {@code CampaignValidatorTest}) so it cannot
 * collide with other in-flight validator work.
 */
class CampaignValidatorHypeUrlTest {

    private final CampaignValidator validator = new CampaignValidator();

    /** A hype config that is valid in every respect except the URL under test. */
    private static HypeConfigDto hypeWithUrl(String sourceReelUrl) {
        return new HypeConfigDto(
                sourceReelUrl,
                null,
                "#GlowDrop",
                List.of("Remix the hook"),
                new BigDecimal("3500"),
                "INR",
                100,
                0,
                null);
    }

    @Test
    void acceptsHttpsUrl() {
        assertThatCode(
                        () ->
                                validator.validateHypeConfig(
                                        CampaignIntentType.HYPE,
                                        hypeWithUrl("https://instagram.com/reel/abc123")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsHttpUrl() {
        assertThatCode(
                        () ->
                                validator.validateHypeConfig(
                                        CampaignIntentType.HYPE, hypeWithUrl("http://example.com/reel")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsJavascriptScheme() {
        assertThatThrownBy(
                        () ->
                                validator.validateHypeConfig(
                                        CampaignIntentType.HYPE, hypeWithUrl("javascript:alert(1)")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http(s) URL");
    }

    @Test
    void rejectsDataScheme() {
        assertThatThrownBy(
                        () ->
                                validator.validateHypeConfig(
                                        CampaignIntentType.HYPE,
                                        hypeWithUrl("data:text/html;base64,PHNjcmlwdD4=")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http(s) URL");
    }

    @Test
    void rejectsSchemelessValue() {
        assertThatThrownBy(
                        () ->
                                validator.validateHypeConfig(
                                        CampaignIntentType.HYPE, hypeWithUrl("instagram.com/reel/abc")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http(s) URL");
    }

    /** The pre-existing required check must still fire first for a blank value. */
    @Test
    void stillRejectsBlankUrlAsRequired() {
        assertThatThrownBy(() -> validator.validateHypeConfig(CampaignIntentType.HYPE, hypeWithUrl("  ")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("required");
    }

    /** Non-HYPE campaigns skip hype validation entirely — a bad URL there is not this check's business. */
    @Test
    void skipsValidationForNonHypeCampaign() {
        assertThatCode(
                        () ->
                                validator.validateHypeConfig(
                                        CampaignIntentType.STANDARD, hypeWithUrl("javascript:alert(1)")))
                .doesNotThrowAnyException();
    }
}
