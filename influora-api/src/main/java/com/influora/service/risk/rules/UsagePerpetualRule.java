package com.influora.service.risk.rules;

import com.influora.domain.enums.UsageChannel;
import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code USAGE_PERPETUAL} (SPEC.md &sect;5.2) — the brand can use the content forever.
 *
 * <p>Three independent triggers, any one of which is enough: the extractor's explicit
 * {@code usage_perpetual} flag, a channel list that covers ALL FIVE {@link UsageChannel} values
 * (which is perpetuity written as a checklist rather than as a word), or the words themselves in
 * the brief.
 *
 * <p>The all-five test counts against {@link UsageChannel#values()} rather than a hardcoded 5, so
 * a sixth channel added later automatically raises the bar instead of leaving this rule firing on
 * five out of six.
 */
public final class UsagePerpetualRule implements RiskRule {

    public static final String CODE = "USAGE_PERPETUAL";
    public static final String TITLE = "Brand wants your content forever";

    /** SPEC.md &sect;5.2, verbatim. Matched against {@link RiskText#norm}'s lower-cased output. */
    private static final Pattern PERPETUAL_TEXT =
            Pattern.compile("perpetu|in perpetuity|all media", Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        boolean flaggedByExtractor = extraction != null && extraction.usagePerpetual();
        boolean allChannels = coversEveryChannel(extraction == null ? null : extraction.usageChannels());
        boolean inText = RiskText.matches(ctx.text(), PERPETUAL_TEXT);

        if (!flaggedByExtractor && !allChannels && !inText) {
            return Optional.empty();
        }

        String basis = flaggedByExtractor ? "STATED" : allChannels ? "ALL_CHANNELS" : "BRIEF_TEXT";
        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.CRITICAL.name(),
                        TITLE,
                        "Brand can use this content forever.",
                        null,
                        "Price the perpetuity add-on before you agree to it.",
                        RiskText.data(
                                "basis", basis,
                                "channel_count",
                                        String.valueOf(
                                                extraction == null || extraction.usageChannels() == null
                                                        ? 0
                                                        : extraction.usageChannels().size())),
                        true));
    }

    /** True only when every declared {@link UsageChannel} name appears in the list. */
    private static boolean coversEveryChannel(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return false;
        }
        Set<String> present = new HashSet<>();
        for (String channel : channels) {
            if (channel != null) {
                present.add(channel.trim().toUpperCase(Locale.ROOT));
            }
        }
        for (UsageChannel channel : UsageChannel.values()) {
            if (!present.contains(channel.name())) {
                return false;
            }
        }
        return true;
    }
}
