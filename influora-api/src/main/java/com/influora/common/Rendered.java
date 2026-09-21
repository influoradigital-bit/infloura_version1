package com.influora.common;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.6) — the one place a number or a date becomes a string for
 * a prompt or a creator-facing payload.
 *
 * <p><b>Why this class exists at all (SPEC.md &sect;0.4).</b> Every number that reaches the model is
 * rendered by Java, in the creator's locale, before it leaves the backend; Python never formats,
 * sums, or converts. Before this class each caller reached for its own {@link NumberFormat}, which
 * is how {@code MeeraContextService} ended up with three subtly different private formatters
 * (integer, 2-decimal USD, percent) — fine there, but not something a dozen Phase-B executors should
 * each re-derive.
 *
 * <p>Numeric values bound for the FRONTEND still travel as numbers in separate fields. This class is
 * for the string half of that pair, not a replacement for it.
 */
public final class Rendered {

    /** SPEC.md &sect;3.6 — the fallback when a creator has no usable language tag. */
    public static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("en-IN");

    /**
     * {@code "5 Oct 2026"} — single-digit day NOT zero-padded, which is what {@code d} gives and
     * {@code dd} (the pattern the existing PDF services use) would not.
     */
    private static final String DATE_PATTERN = "d MMM yyyy";

    private Rendered() {}

    /**
     * Grouped, no fraction digits: {@code 8000} renders as {@code "8,000"} in {@code en-IN}.
     *
     * <p>Deliberately drops the decimal part rather than rounding it into view. Every amount this
     * renders is a whole-rupee negotiating figure, and "8,000" is what a creator would write in a
     * DM; "8,000.00" reads like an invoice. Callers that genuinely need cents preserved — a USD
     * spend cap, say — must not use this (see {@code MeeraContextService.formatCapUsd}, which keeps
     * two fraction digits precisely because a 0.75 cap rounds to "1" here).
     *
     * <p>Note this FORMATS, it does not convert: the caller is responsible for the value already
     * being in the currency it intends to display.
     *
     * @return null when {@code value} is null, so a caller can hand the result straight to a
     *     {@code @JsonInclude(NON_NULL)} record component without a null check of its own
     */
    public static String money(BigDecimal value, Locale locale) {
        if (value == null) {
            return null;
        }
        NumberFormat format = NumberFormat.getIntegerInstance(localeOrDefault(locale));
        format.setGroupingUsed(true);
        return format.format(value);
    }

    /**
     * {@code "5 Oct 2026"}. The month name follows {@code locale}, so a creator reading in {@code
     * hi-IN} gets the Hindi month — the shape is fixed, the words are not.
     *
     * @return null when {@code date} is null
     */
    public static String date(LocalDate date, Locale locale) {
        if (date == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern(DATE_PATTERN, localeOrDefault(locale)).format(date);
    }

    /**
     * Same form, for an {@link Instant}. <b>Rendered in UTC</b>, matching the existing
     * {@code InvoicePdfService} / {@code ContractPdfService} formatters — an instant has no calendar
     * day until a zone is chosen, and picking the creator's zone here would make the same instant
     * render as two different dates to two readers of the same deal.
     *
     * @return null when {@code instant} is null
     */
    public static String date(Instant instant, Locale locale) {
        if (instant == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern(DATE_PATTERN, localeOrDefault(locale))
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    private static Locale localeOrDefault(Locale locale) {
        return locale != null ? locale : DEFAULT_LOCALE;
    }
}
