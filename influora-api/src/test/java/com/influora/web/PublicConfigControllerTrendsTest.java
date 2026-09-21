package com.influora.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.common.ApiResponse;
import com.influora.config.RazorpayProperties;
import com.influora.config.TrendFeatureGate;
import com.influora.config.TrendIngestProperties;
import com.influora.web.PublicConfigController.PublicConfigResponse;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-TSOFF-0920 — {@code GET /config/public} publishes the trend switch, and publishes the SAME
 * predicate the endpoints refuse on.
 *
 * <p>WHY THIS FILE EXISTS. This one field is the whole contract between the server's feature state
 * and what the SPA renders. If it ever drifted from {@link TrendFeatureGate#isEnabled()} — a
 * second boolean, a cached value, a hand-maintained constant — the UI would advertise surfaces
 * whose endpoints then 404, which is the dead-control state the task removed. So the assertion
 * here is not "the field is false in the default config" (that would also pass against a
 * hardcoded {@code false}); it is that the published value TRACKS the gate across a config flip.
 *
 * <p>It also pins that the field is on {@code /public} and not {@code /razorpay}: {@code /public}
 * is the only {@code permitAll} route in the controller, and the pre-login marketing surfaces
 * that must stay consistent with the flag are reachable without a token.
 */
class PublicConfigControllerTrendsTest {

    private static final String REQUIRE_OTP_FIELD = "requireEmailOtpBeforeRegister";

    private static PublicConfigController controller(TrendIngestProperties trendIngest) {
        RazorpayProperties razorpay = new RazorpayProperties();
        razorpay.setKeyId("rzp_test_placeholder_not_a_credential");
        PublicConfigController c =
                new PublicConfigController(razorpay, new TrendFeatureGate(trendIngest));
        setOtpFlag(c, false);
        return c;
    }

    /** {@code @Value}-injected field; set reflectively because there is no setter to use. */
    private static void setOtpFlag(PublicConfigController c, boolean value) {
        try {
            Field f = PublicConfigController.class.getDeclaredField(REQUIRE_OTP_FIELD);
            f.setAccessible(true);
            f.setBoolean(c, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "PublicConfigController." + REQUIRE_OTP_FIELD + " was renamed", e);
        }
    }

    private static TrendIngestProperties fullyOn() {
        TrendIngestProperties props = new TrendIngestProperties();
        props.setEnabled(true);
        props.setNewsapiApiKey("test-key-not-a-real-credential");
        props.setClassifierWorkspaceId("ws_test");
        return props;
    }

    @Test
    @DisplayName("shipped beta default: /config/public reports trendsEnabled=false")
    void defaultIsOff() {
        ApiResponse<PublicConfigResponse> response =
                controller(new TrendIngestProperties()).getPublicConfig();

        assertThat(response.data().trendsEnabled()).isFalse();
    }

    @Test
    @DisplayName("fully configured: /config/public reports trendsEnabled=true")
    void configuredIsOn() {
        ApiResponse<PublicConfigResponse> response = controller(fullyOn()).getPublicConfig();

        assertThat(response.data().trendsEnabled()).isTrue();
    }

    @Test
    @DisplayName("the published value tracks the gate, it is not a second source of truth")
    void tracksTheGate() {
        TrendIngestProperties props = fullyOn();
        TrendFeatureGate gate = new TrendFeatureGate(props);
        RazorpayProperties razorpay = new RazorpayProperties();
        razorpay.setKeyId("rzp_test_placeholder_not_a_credential");
        PublicConfigController controller = new PublicConfigController(razorpay, gate);
        setOtpFlag(controller, false);

        assertThat(controller.getPublicConfig().data().trendsEnabled()).isEqualTo(gate.isEnabled());

        // Flip each of the three conditions in turn; the published value must follow every time.
        props.setEnabled(false);
        assertThat(controller.getPublicConfig().data().trendsEnabled())
                .isEqualTo(gate.isEnabled())
                .isFalse();

        props.setEnabled(true);
        props.setClassifierWorkspaceId("");
        assertThat(controller.getPublicConfig().data().trendsEnabled())
                .isEqualTo(gate.isEnabled())
                .isFalse();

        props.setClassifierWorkspaceId("ws_test");
        assertThat(controller.getPublicConfig().data().trendsEnabled())
                .isEqualTo(gate.isEnabled())
                .isTrue();
    }

    @Test
    @DisplayName("the flag is on /public, not smuggled into the authenticated /razorpay response")
    void livesOnThePublicRoute() {
        PublicConfigController controller = controller(fullyOn());

        // /razorpay stays exactly what it was: the publishable key id and nothing else.
        assertThat(controller.getRazorpayConfig().data().keyId())
                .isEqualTo("rzp_test_placeholder_not_a_credential");
        assertThat(PublicConfigController.RazorpayConfigResponse.class.getRecordComponents())
                .hasSize(1);

        // /public carries exactly the two flags the SPA needs before a token exists.
        assertThat(PublicConfigResponse.class.getRecordComponents()).hasSize(2);
    }

    @Test
    @DisplayName("requireEmailOtp still round-trips (the new field did not displace it)")
    void otpFlagStillWorks() {
        PublicConfigController controller = controller(new TrendIngestProperties());
        setOtpFlag(controller, true);

        assertThat(controller.getPublicConfig().data().requireEmailOtp()).isTrue();
    }
}
