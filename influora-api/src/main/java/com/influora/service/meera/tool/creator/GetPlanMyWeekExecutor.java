package com.influora.service.meera.tool.creator;

import com.influora.service.creatorcopilot.ContentTopicService;
import com.influora.service.creatorcopilot.ContentTopicService.ServableTopic;
import com.influora.service.creatorcopilot.CreatorPostingPatternService;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PatternWindow;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PostingPattern;
import com.influora.web.dto.meera.CreatorToolDtos.PatternResult;
import com.influora.web.dto.meera.CreatorToolDtos.PatternWindowResult;
import com.influora.web.dto.meera.CreatorToolDtos.PlanDay;
import com.influora.web.dto.meera.CreatorToolDtos.PlanMyWeekResult;
import com.influora.web.dto.meera.CreatorToolDtos.TopicResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/**
 * T-PLAN-MY-WEEK -- {@code plan_my_week}: today's date plus the next six days, today's matched and
 * safety-screened content topics ({@link ContentTopicService#topicsFor}, the same read {@code
 * get_todays_topics} serves), and the creator's deterministic {@link PostingPattern}.
 *
 * <p><b>{@code today} is computed HERE, never accepted from {@code input} or inferred by the
 * model</b> -- the same discipline {@link GetTodaysTopicsExecutor} already follows for {@code
 * get_todays_topics}. {@link #IST} is IST specifically, not the server's default zone and not UTC:
 * "today" for an Indian creator's content calendar is an IST date. {@code days} exists precisely
 * because this is the model's ONLY source of the current date -- nothing in its prompt states it,
 * and it must not be left to guess one from training data or from the client.
 */
@Service
public class GetPlanMyWeekExecutor {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** {@code days} is always exactly this many entries: today plus the next six. */
    private static final int PLAN_DAYS = 7;

    private final ContentTopicService contentTopicService;
    private final CreatorPostingPatternService creatorPostingPatternService;

    public GetPlanMyWeekExecutor(
            ContentTopicService contentTopicService,
            CreatorPostingPatternService creatorPostingPatternService) {
        this.contentTopicService = contentTopicService;
        this.creatorPostingPatternService = creatorPostingPatternService;
    }

    /**
     * @param input unused -- {@code plan_my_week} takes no arguments; the parameter is kept so every
     *     creator read executor shares one dispatch signature at the controller
     */
    public PlanMyWeekResult execute(String creatorUserId, Map<String, Object> input) {
        LocalDate today = LocalDate.now(IST);

        List<PlanDay> days = buildDays(today);
        // The SAME lookup topicsFor performs, so the categories the calendar filters on and the
        // ones the topics were screened against can never disagree.
        List<String> categories = contentTopicService.resolveCreatorCategories(creatorUserId);
        List<ServableTopic> topics = contentTopicService.topicsFor(creatorUserId, today);
        PostingPattern pattern = creatorPostingPatternService.analyse(creatorUserId, today);

        return new PlanMyWeekResult(
                today.toString(),
                days,
                categories,
                topics.stream().map(GetPlanMyWeekExecutor::toTopicResult).toList(),
                toPatternResult(pattern));
    }

    private static List<PlanDay> buildDays(LocalDate today) {
        return IntStream.range(0, PLAN_DAYS)
                .mapToObj(today::plusDays)
                .map(
                        date ->
                                new PlanDay(
                                        date.toString(),
                                        date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)))
                .toList();
    }

    private static TopicResult toTopicResult(ServableTopic topic) {
        return new TopicResult(
                topic.id(),
                topic.category(),
                topic.title(),
                topic.angles(),
                topic.liveUntil().toString(),
                topic.sensitivity());
    }

    private static PatternResult toPatternResult(PostingPattern pattern) {
        List<PatternWindowResult> windows =
                pattern.windows().stream()
                        .map(GetPlanMyWeekExecutor::toPatternWindowResult)
                        .toList();
        return new PatternResult(
                pattern.enoughData(), pattern.postsCounted(), pattern.bestPostType(), windows, pattern.note());
    }

    private static PatternWindowResult toPatternWindowResult(PatternWindow window) {
        return new PatternWindowResult(window.label(), window.posts(), window.engagementRate());
    }
}
