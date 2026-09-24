package com.influora.service.meera.tool.creator;

import com.influora.service.creatorcopilot.ContentTopicService;
import com.influora.service.creatorcopilot.ContentTopicService.ServableTopic;
import com.influora.web.dto.meera.CreatorToolDtos.GetTodaysTopicsResult;
import com.influora.web.dto.meera.CreatorToolDtos.TopicResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * T-CONTENT-TOPICS -- {@code get_todays_topics}: today's date/weekday plus up to five matched,
 * safety-screened content topics for the calling creator.
 *
 * <p><b>{@code today} is computed HERE, never accepted from {@code input} or inferred by the
 * model.</b> Meera has no other source of the current date -- nothing in her prompt states it --
 * and the client is not a trustworthy clock either. {@link #IST} is IST specifically (not the
 * server's default zone, not UTC): "today" for an Indian creator's content calendar is an IST
 * date, and {@code content_topics.live_from}/{@code live_until} are plain {@code DATE} columns
 * with no timezone of their own to disagree with.
 */
@Service
public class GetTodaysTopicsExecutor {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ContentTopicService contentTopicService;

    public GetTodaysTopicsExecutor(ContentTopicService contentTopicService) {
        this.contentTopicService = contentTopicService;
    }

    /**
     * @param input unused -- {@code get_todays_topics} takes no arguments; the parameter is kept so
     *     every creator read executor shares one dispatch signature at the controller
     */
    public GetTodaysTopicsResult execute(String creatorUserId, Map<String, Object> input) {
        LocalDate today = LocalDate.now(IST);
        List<ServableTopic> topics = contentTopicService.topicsFor(creatorUserId, today);

        return new GetTodaysTopicsResult(
                today.toString(),
                today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                topics.stream().map(GetTodaysTopicsExecutor::toTopicResult).toList());
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
}
