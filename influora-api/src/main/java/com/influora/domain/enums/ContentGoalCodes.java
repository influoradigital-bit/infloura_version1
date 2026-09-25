package com.influora.domain.enums;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Goal memory (Meera intelligence v1, spec &sect;2.2/&sect;6) -- the fixed codes a creator can tap
 * on the "My goals" chips. They are app-layer enums stored as VARCHAR/TEXT JSON (not DB ENUMs),
 * like {@code creator_challenges.status}. Only these exact names are accepted: a code is parsed
 * case-sensitively and never trimmed, so an unknown or mistyped code is a 400, not a guess.
 *
 * <p>No free text is ever stored from these chips -- that is what keeps them outside the model's
 * write surface (Meera never saves a goal; the creator taps one).
 */
public final class ContentGoalCodes {

    private ContentGoalCodes() {}

    /** {@code content_goal} -- single select. */
    public enum ContentGoal {
        GROW_FOLLOWERS,
        BRAND_DEALS,
        SELL_PRODUCT
    }

    /** {@code weekly_time_band} -- single select. */
    public enum WeeklyTimeBand {
        UNDER_2H,
        H2_TO_5,
        OVER_5H
    }

    /** {@code equipment} -- multi select. */
    public enum Equipment {
        PHONE_ONLY,
        TRIPOD,
        EXTERNAL_MIC,
        RING_LIGHT,
        GIMBAL
    }

    /** {@code content_dislikes} -- multi select, "rather not". */
    public enum ContentDislike {
        NO_FACE,
        NO_VOICE,
        NO_DANCING,
        NO_TRENDING_AUDIO,
        NO_OUTDOOR
    }

    /** Exact {@link Enum#name()} match, or empty for null or anything else. */
    public static <E extends Enum<E>> Optional<E> parse(Class<E> type, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Arrays.stream(type.getEnumConstants()).filter(c -> c.name().equals(raw)).findFirst();
    }

    /**
     * The distinct codes of {@code raw} in the enum's declared order, or empty when {@code raw} is
     * null or empty. Empty {@link Optional} (not an empty list) when ANY element is unknown, so the
     * caller rejects the whole request rather than silently dropping a code.
     */
    public static <E extends Enum<E>> Optional<List<String>> parseAll(Class<E> type, List<String> raw) {
        Set<E> codes = EnumSet.noneOf(type);
        if (raw != null) {
            for (String value : raw) {
                Optional<E> code = parse(type, value);
                if (code.isEmpty()) {
                    return Optional.empty();
                }
                codes.add(code.get());
            }
        }
        List<String> names = new ArrayList<>();
        codes.forEach(c -> names.add(c.name()));
        return Optional.of(List.copyOf(names));
    }
}
