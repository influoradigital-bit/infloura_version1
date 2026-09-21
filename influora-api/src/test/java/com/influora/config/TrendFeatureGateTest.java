package com.influora.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influora.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * T-TSOFF-0920 — {@link TrendFeatureGate} and the predicate it is built on.
 *
 * <p>WHY THIS FILE EXISTS. "TrendSpark is off for the beta" was true only by side effect: with
 * ingest off nothing writes to {@code trends}, so the read endpoints happened to return their
 * ordinary "nothing to say" answers. Nothing asserted it, nothing distinguished it from a normal
 * quiet day, and the frontend consequently told creators their first idea would land "by tomorrow
 * morning". These tests pin the predicate itself, because every user-facing decision — what the
 * SPA renders, what {@code /config/public} publishes, what the endpoints answer — is now derived
 * from this one method.
 *
 * <p>The three sub-conditions are asserted INDIVIDUALLY rather than only in combination: each one
 * alone is sufficient to guarantee an empty {@code trends} table (see {@link
 * TrendIngestProperties#canProduceTrends()}'s javadoc for which line of {@code TrendPullJob}
 * enforces each), so a gate that only checked {@code enabled} would call the feature "on" in two
 * configurations where it can still never produce a single row.
 */
class TrendFeatureGateTest {

    /** Fully switched on: ingest enabled, one source key, a classifier workspace to bill. */
    private static TrendIngestProperties fullyOn() {
        TrendIngestProperties props = new TrendIngestProperties();
        props.setEnabled(true);
        props.setNewsapiApiKey("test-key-not-a-real-credential");
        props.setClassifierWorkspaceId("ws_test");
        return props;
    }

    @Nested
    @DisplayName("TrendIngestProperties.canProduceTrends()")
    class CanProduceTrends {

        @Test
        @DisplayName("a default-constructed config (the shipped beta default) cannot produce trends")
        void defaultsAreOff() {
            assertThat(new TrendIngestProperties().canProduceTrends()).isFalse();
        }

        @Test
        @DisplayName("fully configured -> true")
        void fullyConfiguredIsOn() {
            assertThat(fullyOn().canProduceTrends()).isTrue();
        }

        @Test
        @DisplayName("enabled=false alone is enough to be off (TrendPullJob returns at its first line)")
        void disabledFlagAlone() {
            TrendIngestProperties props = fullyOn();
            props.setEnabled(false);
            assertThat(props.canProduceTrends()).isFalse();
        }

        @Test
        @DisplayName("no source key alone is enough to be off (every source is skipped, nothing written)")
        void noSourceKeyAlone() {
            TrendIngestProperties props = fullyOn();
            props.setNewsapiApiKey("");
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.hasClassifierWorkspaceId()).isTrue();
            assertThat(props.canProduceTrends()).isFalse();
        }

        @Test
        @DisplayName("no classifier workspace alone is enough to be off (EV-013 fail-closed leg)")
        void noClassifierWorkspaceAlone() {
            TrendIngestProperties props = fullyOn();
            props.setClassifierWorkspaceId("");
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.isConfigured()).isTrue();
            // This is the live beta's actual shape if someone flips TREND_INGEST_ENABLED=true
            // without the (still unruled) classifier workspace id: the job runs, fetches, and
            // rejects every row. Reporting "on" here would put a permanently empty trend surface
            // back in front of users.
            assertThat(props.canProduceTrends()).isFalse();
        }

        @Test
        @DisplayName("a blank-but-not-empty classifier id is still off")
        void blankClassifierWorkspace() {
            TrendIngestProperties props = fullyOn();
            props.setClassifierWorkspaceId("   ");
            assertThat(props.canProduceTrends()).isFalse();
        }

        @Test
        @DisplayName("any single source key is enough (matches TrendPullJob's per-source skipping)")
        void anySingleSourceKeySuffices() {
            TrendIngestProperties tmdbOnly = fullyOn();
            tmdbOnly.setNewsapiApiKey("");
            tmdbOnly.setTmdbApiKey("test-key-not-a-real-credential");
            assertThat(tmdbOnly.canProduceTrends()).isTrue();

            TrendIngestProperties youtubeOnly = fullyOn();
            youtubeOnly.setNewsapiApiKey("");
            youtubeOnly.setYoutubeApiKey("test-key-not-a-real-credential");
            assertThat(youtubeOnly.canProduceTrends()).isTrue();
        }
    }

    @Nested
    @DisplayName("TrendFeatureGate")
    class Gate {

        @Test
        @DisplayName("off -> requireEnabled throws 404 TRENDS_DISABLED with a non-promising message")
        void offThrows() {
            TrendFeatureGate gate = new TrendFeatureGate(new TrendIngestProperties());

            assertThat(gate.isEnabled()).isFalse();
            assertThatThrownBy(gate::requireEnabled)
                    .isInstanceOf(ApiException.class)
                    .satisfies(
                            thrown -> {
                                ApiException ex = (ApiException) thrown;
                                assertThat(ex.getCode()).isEqualTo("TRENDS_DISABLED");
                                assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                                // The message is user-visible. It must not promise a return date,
                                // which is the exact dishonesty this task removed from the UI.
                                assertThat(ex.getMessage().toLowerCase())
                                        .doesNotContain("tomorrow")
                                        .doesNotContain("soon")
                                        .doesNotContain("check back")
                                        .doesNotContain("coming");
                            });
        }

        @Test
        @DisplayName("on -> requireEnabled is a no-op (the gate is not a permanent kill switch)")
        void onPasses() {
            TrendFeatureGate gate = new TrendFeatureGate(fullyOn());

            assertThat(gate.isEnabled()).isTrue();
            assertThatCode(gate::requireEnabled).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the gate reads the live properties object, so a config change is picked up")
        void readsLiveProperties() {
            TrendIngestProperties props = fullyOn();
            TrendFeatureGate gate = new TrendFeatureGate(props);
            assertThat(gate.isEnabled()).isTrue();

            props.setEnabled(false);

            // No cached boolean: the gate must never answer "on" from a value it captured at
            // construction, or a Spring Cloud Config / restart-free flip would leave the
            // endpoints and /config/public disagreeing with the ingest job.
            assertThat(gate.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("404 is used, matching the codebase's existing FEATURE_DISABLED convention")
        void usesTheExistingConvention() {
            // CreatorAgentController / CreatorMeeraController / PublicCreatorController all answer
            // 404 for a switched-off feature. One convention, not two.
            assertThat(TrendFeatureGate.DISABLED_CODE).isEqualTo("TRENDS_DISABLED");
            assertThat(TrendFeatureGate.DISABLED_MESSAGE).isNotBlank();
        }
    }
}
