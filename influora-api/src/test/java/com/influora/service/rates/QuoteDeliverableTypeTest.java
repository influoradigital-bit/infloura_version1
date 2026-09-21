package com.influora.service.rates;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.domain.enums.DeliverableType;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;4.1, B0-30). */
class QuoteDeliverableTypeTest {

    @ParameterizedTest(name = "parse(\"{0}\") -> {1}")
    @DisplayName("4.1 - the pricing names round-trip, case-insensitively")
    @CsvSource({
        "REEL,REEL",
        "reel,REEL",
        "  ReEl  ,REEL",
        "STATIC_POST,STATIC_POST",
        "STORY_SET,STORY_SET",
        "story_set,STORY_SET",
        "SHORT,SHORT",
        "YT_INTEGRATION,YT_INTEGRATION",
        "YT_DEDICATED,YT_DEDICATED",
        "UGC_ONLY,UGC_ONLY",
        "OTHER,OTHER"
    })
    void pricingNamesParse(String raw, QuoteDeliverableType expected) {
        assertThat(QuoteDeliverableType.parse(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "legacy parse(\"{0}\") -> {1}")
    @DisplayName("4.1 - the legacy short forms older proposal rows carry")
    @CsvSource({"reel,REEL", "story,STORY_SET", "post,STATIC_POST", "STORY,STORY_SET", "POST,STATIC_POST"})
    void legacyNamesParse(String raw, QuoteDeliverableType expected) {
        assertThat(QuoteDeliverableType.parse(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "platform parse(\"{0}\") -> {1}")
    @DisplayName(
            "4.1 - the PERSISTED platform taxonomy maps, because live proposal metadata carries"
                    + " those names and not the pricing ones")
    @CsvSource({
        "INSTAGRAM_REEL,REEL",
        "instagram_reel,REEL",
        "INSTAGRAM_STORY,STORY_SET",
        "INSTAGRAM_POST,STATIC_POST",
        "INSTAGRAM_CAROUSEL,STATIC_POST",
        "YOUTUBE_SHORT,SHORT",
        "YOUTUBE_VIDEO,YT_INTEGRATION"
    })
    void platformNamesParse(String raw, QuoteDeliverableType expected) {
        assertThat(QuoteDeliverableType.parse(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("4.1 - unknown, null and blank all price neutrally as OTHER, never throw")
    void unknownIsOther() {
        assertThat(QuoteDeliverableType.parse(null)).isEqualTo(QuoteDeliverableType.OTHER);
        assertThat(QuoteDeliverableType.parse("")).isEqualTo(QuoteDeliverableType.OTHER);
        assertThat(QuoteDeliverableType.parse("   ")).isEqualTo(QuoteDeliverableType.OTHER);
        assertThat(QuoteDeliverableType.parse("!!!")).isEqualTo(QuoteDeliverableType.OTHER);
        assertThat(QuoteDeliverableType.parse("PODCAST_MENTION")).isEqualTo(QuoteDeliverableType.OTHER);
    }

    @Test
    @DisplayName("4.1 - punctuation and spacing are folded, so \"story set\" is not an unknown type")
    void punctuationIsFolded() {
        assertThat(QuoteDeliverableType.parse("story set")).isEqualTo(QuoteDeliverableType.STORY_SET);
        assertThat(QuoteDeliverableType.parse("instagram-reel")).isEqualTo(QuoteDeliverableType.REEL);
        assertThat(QuoteDeliverableType.parse("YouTube.Video"))
                .isEqualTo(QuoteDeliverableType.YT_INTEGRATION);
    }

    @Test
    @DisplayName("4.1 - the weights are the spec's, and OTHER is neutral rather than free")
    void weightsMatchTheSpec() {
        assertThat(QuoteDeliverableType.REEL.unitWeight).isEqualTo(1.00);
        assertThat(QuoteDeliverableType.STATIC_POST.unitWeight).isEqualTo(0.50);
        assertThat(QuoteDeliverableType.STORY_SET.unitWeight).isEqualTo(0.50);
        assertThat(QuoteDeliverableType.SHORT.unitWeight).isEqualTo(0.70);
        assertThat(QuoteDeliverableType.YT_INTEGRATION.unitWeight).isEqualTo(1.50);
        assertThat(QuoteDeliverableType.YT_DEDICATED.unitWeight).isEqualTo(3.00);
        assertThat(QuoteDeliverableType.UGC_ONLY.unitWeight).isEqualTo(0.60);
        assertThat(QuoteDeliverableType.OTHER.unitWeight).isEqualTo(1.00);
    }

    /**
     * The reason this enum exists at all. If a future edit "helpfully" adds the pricing values to
     * {@link DeliverableType}, {@code ContractService}'s {@code valueOf} parse behaviour changes and
     * a persisted column gains values it never had. This pins the two vocabularies apart.
     */
    @Test
    @DisplayName("4.1 - the persisted DeliverableType is untouched and shares no value with this enum")
    void theTwoTaxonomiesStayDisjoint() {
        assertThat(DeliverableType.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "INSTAGRAM_POST",
                        "INSTAGRAM_REEL",
                        "INSTAGRAM_STORY",
                        "INSTAGRAM_CAROUSEL",
                        "YOUTUBE_VIDEO",
                        "YOUTUBE_SHORT",
                        "FACEBOOK_POST",
                        "FACEBOOK_REEL",
                        "TIKTOK_VIDEO");

        assertThat(Arrays.stream(QuoteDeliverableType.values()).map(Enum::name))
                .noneMatch(
                        pricingName ->
                                Arrays.stream(DeliverableType.values())
                                        .map(Enum::name)
                                        .anyMatch(pricingName::equals));
    }

    /**
     * Every persisted {@link DeliverableType} constant must reach a priced type. Three of them
     * ({@code FACEBOOK_POST}, {@code FACEBOOK_REEL}, {@code TIKTOK_VIDEO}) are outside SPEC.md
     * &sect;4.1's enumerated mapping list — see {@link QuoteDeliverableType}'s class javadoc for
     * why they are mapped anyway. This test is the record of that decision: without the
     * {@code FACEBOOK_POST} mapping a static post prices at weight 1.00 instead of 0.50, i.e.
     * double.
     */
    @Test
    @DisplayName("4.1 (deviation, flagged) - every persisted platform constant prices sensibly")
    void everyPersistedPlatformNameIsMapped() {
        assertThat(QuoteDeliverableType.parse(DeliverableType.FACEBOOK_POST.name()))
                .isEqualTo(QuoteDeliverableType.STATIC_POST);
        assertThat(QuoteDeliverableType.parse(DeliverableType.FACEBOOK_REEL.name()))
                .isEqualTo(QuoteDeliverableType.REEL);
        assertThat(QuoteDeliverableType.parse(DeliverableType.TIKTOK_VIDEO.name()))
                .isEqualTo(QuoteDeliverableType.REEL);

        for (DeliverableType persisted : DeliverableType.values()) {
            assertThat(QuoteDeliverableType.parse(persisted.name()))
                    .as("persisted type %s must map to a priced type", persisted)
                    .isNotNull();
        }
    }
}
