package com.influora.service.risk.rules;

import com.influora.common.Rendered;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskContext.ActiveDeal;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code CALENDAR_OVERLOAD} (SPEC.md &sect;5.2) — this deadline lands in a week the creator has
 * already filled.
 *
 * <p><b>Deliverables, bucketed by collaboration.</b> The spec says both "the deadline week already
 * holds &ge; {@code weekly_sponsored_limit} DELIVERABLES" and, parenthetically, "count IN_PROGRESS
 * and CONTRACTED COLLABORATIONS with {@code endDate} in that ISO week". Reconciled by counting
 * deliverables but selecting them through their collaboration: each qualifying collaboration
 * contributes its deliverable count, and a collaboration with no deliverable rows yet still
 * contributes one, because a contracted deal occupies the week whether or not its slots have been
 * materialised. Counting only rows would let a freshly contracted week read as empty.
 *
 * <p><b>ISO weeks, both halves.</b> Two dates are in the same week only when their ISO week-based
 * YEAR and week number both match. Comparing the week number alone puts 30 December 2025 and 29
 * December 2026 in the same bucket.
 */
public final class CalendarOverloadRule implements RiskRule {

    public static final String CODE = "CALENDAR_OVERLOAD";
    public static final String TITLE = "That week is already full";

    /** SPEC.md &sect;5.2 — only these two statuses occupy the calendar. */
    private static final Set<CollaborationStatus> OCCUPYING_STATUSES =
            EnumSet.of(CollaborationStatus.CONTRACTED, CollaborationStatus.IN_PROGRESS);

    private static final WeekFields ISO = WeekFields.ISO;

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        PreferencesResponse prefs = ctx.prefs();
        Integer limit = prefs == null ? null : prefs.weeklySponsoredLimit();
        if (limit == null || limit <= 0) {
            // The creator has set no weekly limit. There is no number to be over.
            return Optional.empty();
        }
        LocalDate deadline = parseDeadline(ctx);
        if (deadline == null) {
            return Optional.empty();
        }

        int booked = 0;
        for (ActiveDeal deal : ctx.activeDeals()) {
            if (!OCCUPYING_STATUSES.contains(deal.status())) {
                continue;
            }
            if (!sameIsoWeek(deal.campaignEndDate(), deadline)) {
                continue;
            }
            booked += Math.max(deal.deliverableCount(), 1);
        }
        if (booked < limit) {
            return Optional.empty();
        }

        String weekOf = Rendered.date(deadline.with(ISO.dayOfWeek(), 1), ctx.locale());
        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.INFO.name(),
                        TITLE,
                        "You already have "
                                + booked
                                + " deliverables due in the week of "
                                + weekOf
                                + ", and your limit is "
                                + limit
                                + ".",
                        null,
                        "Propose a delivery date in a later week.",
                        RiskText.data(
                                "deadline", Rendered.date(deadline, ctx.locale()),
                                "week_of", weekOf,
                                "already_booked", String.valueOf(booked),
                                "weekly_limit", String.valueOf(limit)),
                        true));
    }

    /** {@code BriefExtraction.deadline} is an ISO date STRING; an unparseable one is simply absent. */
    private static LocalDate parseDeadline(RiskContext ctx) {
        String raw = ctx.extraction() == null ? null : ctx.extraction().deadline();
        if (RiskText.blank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static boolean sameIsoWeek(LocalDate a, LocalDate b) {
        if (a == null || b == null) {
            return false;
        }
        return a.get(ISO.weekBasedYear()) == b.get(ISO.weekBasedYear())
                && a.get(ISO.weekOfWeekBasedYear()) == b.get(ISO.weekOfWeekBasedYear());
    }
}
