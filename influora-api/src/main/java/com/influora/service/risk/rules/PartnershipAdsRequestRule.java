package com.influora.service.risk.rules;

import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.UsageChannel;
import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code PARTNERSHIP_ADS_REQUEST} (SPEC.md &sect;5.2) — a signed deal's brand is asking to run the
 * creator's post as a paid ad, and the deal's usage rights do not cover that.
 *
 * <p><b>Deal path only.</b> Enforced by requiring {@link RiskContext#collaboration()} and
 * {@link RiskContext#lastBrandMessage()}, both of which only {@code evaluateDeal} supplies. A
 * pasted brief cannot fire this: there is no signed deal to be asked about, and no message thread
 * for the brand to have asked in.
 *
 * <p><b>"status &ge; CONTRACTED" is an explicit set, not an ordinal comparison.</b>
 * {@link CollaborationStatus} declares CANCELLED and DISPUTED after CONTRACTED, so
 * {@code status.ordinal() >= CONTRACTED.ordinal()} would flag a cancelled deal — and would silently
 * change meaning the next time anyone reorders the enum.
 */
public final class PartnershipAdsRequestRule implements RiskRule {

    public static final String CODE = "PARTNERSHIP_ADS_REQUEST";
    public static final String TITLE = "Brand wants to run this as an ad";

    /** SPEC.md &sect;5.2, verbatim. */
    private static final Pattern ADS_REQUEST =
            Pattern.compile(
                    "partnership ad|boost|promote (this|the) (post|reel)|whitelist",
                    Pattern.CASE_INSENSITIVE);

    private static final Set<CollaborationStatus> SIGNED_OR_BEYOND =
            EnumSet.of(
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED);

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        if (!ctx.isDealPath() || RiskText.blank(ctx.lastBrandMessage())) {
            return Optional.empty();
        }
        if (!SIGNED_OR_BEYOND.contains(ctx.collaboration().getStatus())) {
            return Optional.empty();
        }
        if (!RiskText.matches(ctx.lastBrandMessage(), ADS_REQUEST)) {
            return Optional.empty();
        }
        List<String> channels = ctx.extraction() == null ? null : ctx.extraction().usageChannels();
        if (covers(channels, UsageChannel.PAID_ADS) || covers(channels, UsageChannel.WHITELISTING)) {
            // The rights were already bought. Asking is then just scheduling, not a scope grab.
            return Optional.empty();
        }

        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        "Do not approve the partnership-ads request until the paid-ads add-on is paid.",
                        null,
                        "Quote the paid-ads add-on and wait for the funds to be secured before you approve it.",
                        RiskText.data(
                                "deal_status", ctx.collaboration().getStatus().name(),
                                "granted_channels", channels == null || channels.isEmpty() ? "none" : String.join(", ", channels)),
                        true));
    }

    private static boolean covers(List<String> channels, UsageChannel channel) {
        if (channels == null || channels.isEmpty()) {
            return false;
        }
        return channels.stream()
                .anyMatch(c -> c != null && channel.name().equals(c.trim().toUpperCase(Locale.ROOT)));
    }
}
