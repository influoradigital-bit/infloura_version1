package com.influora.service.creatorcopilot;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.domain.entity.ContentTopic;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.ContentTopicRepository;
import com.influora.repository.CreatorProfileRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CONTENT-TOPICS -- {@code get_todays_topics}: matches the hand-typed {@code content_topics}
 * catalogue (V75) against one creator's categories and returns up to {@link #MAX_TOPICS} of them
 * for today.
 *
 * <p><b>Screening happens HERE, at read time, not at insert time.</b> {@code content_topics} rows
 * are typed in by hand over a SQL client (see the V75 migration comment) -- there is no ingest job
 * and no admin write UI in front of this table, so nothing has ever checked a row's words before
 * it lands in the database. Every candidate this service is about to serve is therefore run
 * through {@link TrendHeadlineScreener#isSafeForCreatorCopy} for its title AND every one of its
 * angles before it is returned; a topic that fails on either is dropped. This mirrors {@code
 * TrendPullJob}'s "fail closed" rule from wiki/decisions/2026-09-18-trend-headline-screening.md,
 * moved from ingest to read because that is where this table's only gate can live.
 *
 * <p><b>F-0786: never log the text.</b> A drop is logged at WARN with the topic's {@code id} and
 * {@link TrendHeadlineScreener#rejectionCategory} only -- never the title or angle text that
 * tripped it. See {@code TrendPullJob}'s own rejection logging for the same discipline.
 */
@Service
public class ContentTopicService {

    private static final Logger log = LoggerFactory.getLogger(ContentTopicService.class);

    /** The tool never returns more than this many topics, even when more would match and pass. */
    public static final int MAX_TOPICS = 5;

    private final ContentTopicRepository contentTopicRepository;
    private final CreatorProfileRepository creatorProfileRepository;

    public ContentTopicService(
            ContentTopicRepository contentTopicRepository,
            CreatorProfileRepository creatorProfileRepository) {
        this.contentTopicRepository = contentTopicRepository;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /**
     * One matched, safety-screened topic, ready to render on the wire. Deliberately not the JPA
     * entity: callers (the tool executor, the admin preview) each render this into their own DTO
     * shape, and neither should be handed a persistence type.
     */
    public record ServableTopic(
            Long id,
            String category,
            String title,
            List<String> angles,
            LocalDate liveUntil,
            String sensitivity) {}

    /** A matched-but-unsafe row, reported by id and rejection category only -- never by text. */
    public record DroppedTopic(Long id, String rejectionCategory) {}

    /** The full result of matching+screening a category list against today's candidates. */
    public record ScreeningResult(List<ServableTopic> servable, List<DroppedTopic> dropped) {}

    /** Reason: the part maps to no calendar category (a typo or an off-table word). */
    public static final String REASON_UNKNOWN_CATEGORY = "not a known category";

    /** Reason: the part is a calendar category no onboarding option maps to ("Gardening"). */
    public static final String REASON_NO_CREATOR_GROUP = "no creator group reaches it";

    /** Reason: the row also has an {@code ALL} part, so this part changes nothing. */
    public static final String REASON_ALL_WITH_OTHERS =
            "ALL with other categories: the row already reaches everyone";

    /**
     * One part of a servable-today row's category that will not do what it looks like, for the
     * admin preview: the row's id, its raw category, the part, and one of {@link
     * #REASON_UNKNOWN_CATEGORY}, {@link #REASON_NO_CREATOR_GROUP}, {@link #REASON_ALL_WITH_OTHERS}.
     * Never the row's title or angles.
     */
    public record UnmatchedCategoryTopic(Long id, String category, String part, String reason) {}

    /**
     * The creator-facing read: up to {@link #MAX_TOPICS} servable topics for {@code creatorUserId}
     * on {@code today}.
     *
     * <p>{@code today} is decided entirely by the CALLER -- this method never calls {@code
     * LocalDate.now()} itself. {@code GetTodaysTopicsExecutor} is the one production caller and it
     * passes {@code LocalDate.now(ZoneId.of("Asia/Kolkata"))}; neither the model's nor the client's
     * idea of the date is ever consulted, because neither is trustworthy or even present in the
     * request.
     */
    @Transactional(readOnly = true)
    public List<ServableTopic> topicsFor(String creatorUserId, LocalDate today) {
        return screen(resolveCreatorCategories(creatorUserId), today).servable();
    }

    /**
     * The creator's own categories ({@code creator_profiles.categories_json}), exactly as stored --
     * normalising them and mapping them onto the calendar's categories ({@link
     * CreatorCategoryMap}) is done by {@link #screen}, not here. A creator with no stored categories gets an empty list back, which {@link #screen}
     * then matches against nothing but the {@code ALL} rows (see that method's javadoc).
     *
     * <p>Exposed (not private) so the admin preview endpoint can resolve and display the same
     * categories a real tool call would use, without duplicating this lookup.
     */
    public List<String> resolveCreatorCategories(String creatorUserId) {
        CreatorProfile profile =
                creatorProfileRepository
                        .findByUserId(creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "Creator profile not found",
                                                HttpStatus.NOT_FOUND));
        return JsonLists.stringListFromJson(profile.getCategoriesJson());
    }

    /**
     * Matches every servable-today row ({@link ContentTopicRepository#findServable}) against
     * {@code creatorCategories} and screens every match for safety.
     *
     * <p><b>Matching rule.</b> A topic's {@code category} may hold several categories separated by
     * commas ("Fashion, Culture"); {@link CreatorCategoryMap#splitTopicCategories} splits it,
     * trims each part and drops blank parts. If any part is the literal {@code ALL}
     * (case-insensitively) the row matches every creator. Otherwise both sides go through the same
     * table, each by its own rule: the creator's categories become {@link
     * CreatorCategoryMap#matchKeys} (each raw category plus EVERY calendar category it maps to),
     * the topic's parts become {@link CreatorCategoryMap#topicMatchKeys} (each raw part plus its
     * calendar category only when the table gives exactly one), and the row matches iff the two
     * sets share a name. So "Food &amp; Cooking" matches a "Food" topic, "Tech &amp; Gaming"
     * matches a "Tech" topic (both reach Technology), and "Music &amp; Dance" matches a "Fashion,
     * Culture" topic via Culture -- but a "Parenting &amp; Family" topic reaches only creators who
     * chose "Parenting &amp; Family", not every Food creator. A part that maps to no calendar
     * category ("Snacks") matches only a creator who typed that same word -- the admin preview
     * lists such parts ({@link #unmatchedCategories}). A creator with an empty {@code
     * creatorCategories} list therefore matches only {@code ALL} rows -- not a special case coded
     * here, just what the rule above reduces to when there is nothing else to match against.
     *
     * <p><b>Cap.</b> {@link #MAX_TOPICS} applies to the SAFE, matched result only -- a matched row
     * that gets dropped for safety does not consume a slot. {@code dropped} is not capped: every
     * matched-but-unsafe row found while scanning is reported, which is what makes the admin
     * preview endpoint a genuine check of "what does this row do", not just of the first five rows
     * in the table.
     *
     * <p>Exposed (not private) so the admin preview endpoint can run the exact same matching and
     * screening logic the real tool uses, against an arbitrary category list rather than one
     * resolved from a stored creator.
     */
    @Transactional(readOnly = true)
    public ScreeningResult screen(List<String> creatorCategories, LocalDate today) {
        List<String> categories = creatorCategories == null ? List.of() : creatorCategories;
        Set<String> creatorKeys = CreatorCategoryMap.matchKeys(categories);
        List<ContentTopic> candidates = contentTopicRepository.findServable(today);

        List<ServableTopic> servable = new ArrayList<>();
        List<DroppedTopic> dropped = new ArrayList<>();

        for (ContentTopic topic : candidates) {
            if (!matchesCategory(topic.getCategory(), creatorKeys)) {
                continue;
            }

            String rejectionCategory = firstRejectionCategory(topic);
            if (rejectionCategory != null) {
                log.warn(
                        "ContentTopicService: dropped content_topic id={} reason=word_filter category={}",
                        topic.getId(),
                        rejectionCategory);
                dropped.add(new DroppedTopic(topic.getId(), rejectionCategory));
                continue;
            }

            if (servable.size() < MAX_TOPICS) {
                servable.add(
                        new ServableTopic(
                                topic.getId(),
                                topic.getCategory(),
                                topic.getTitle(),
                                splitAngles(topic.getAngles()),
                                topic.getLiveUntil(),
                                topic.getSensitivity()));
            }
        }

        return new ScreeningResult(List.copyOf(servable), List.copyOf(dropped));
    }

    /**
     * Every PART of today's servable rows (APPROVED, in window -- {@link
     * ContentTopicRepository#findServable}) that will not do what it looks like, checked part by
     * part so a typo inside a comma list ("Food, Snakcs") is caught too:
     *
     * <ul>
     *   <li>{@link #REASON_UNKNOWN_CATEGORY}: the part maps to no calendar category and is not
     *       {@code ALL}, so it reaches only a creator who typed that exact word. A row with no
     *       parts at all (a blank category) is reported once with an empty part.
     *   <li>{@link #REASON_NO_CREATOR_GROUP}: the part resolves to a calendar category that no
     *       onboarding option maps to ({@link CreatorCategoryMap#calendarCategoriesNoVerticalReaches},
     *       today only "Gardening").
     *   <li>{@link #REASON_ALL_WITH_OTHERS}: the row also has an {@code ALL} part, so this part
     *       changes nothing; each non-{@code ALL} part of such a row is reported with this reason.
     * </ul>
     *
     * A multi-target onboarding option ("Parenting &amp; Family") is not reported: it reaches the
     * creators who chose it. Safety screening is not applied here: this is a question about the
     * category column only, and the result carries id, raw category, part and reason, never title
     * or angles.
     */
    @Transactional(readOnly = true)
    public List<UnmatchedCategoryTopic> unmatchedCategories(LocalDate today) {
        List<String> unreached = CreatorCategoryMap.calendarCategoriesNoVerticalReaches();
        List<UnmatchedCategoryTopic> unmatched = new ArrayList<>();
        for (ContentTopic topic : contentTopicRepository.findServable(today)) {
            Long id = topic.getId();
            String category = topic.getCategory();
            List<String> parts = CreatorCategoryMap.splitTopicCategories(category);
            if (parts.isEmpty()) {
                unmatched.add(new UnmatchedCategoryTopic(id, category, "", REASON_UNKNOWN_CATEGORY));
                continue;
            }
            boolean hasAll = isAll(parts);
            for (String part : parts) {
                if (part.equalsIgnoreCase(ContentTopic.CATEGORY_ALL)) {
                    continue;
                }
                if (hasAll) {
                    unmatched.add(new UnmatchedCategoryTopic(id, category, part, REASON_ALL_WITH_OTHERS));
                    continue;
                }
                List<String> calendar = CreatorCategoryMap.calendarCategoriesFor(part);
                if (calendar.isEmpty()) {
                    unmatched.add(new UnmatchedCategoryTopic(id, category, part, REASON_UNKNOWN_CATEGORY));
                } else if (calendar.size() == 1 && unreached.contains(calendar.get(0))) {
                    unmatched.add(new UnmatchedCategoryTopic(id, category, part, REASON_NO_CREATOR_GROUP));
                }
            }
        }
        return List.copyOf(unmatched);
    }

    private static boolean matchesCategory(String topicCategory, Set<String> creatorKeys) {
        List<String> parts = CreatorCategoryMap.splitTopicCategories(topicCategory);
        if (isAll(parts)) {
            return true;
        }
        return !Collections.disjoint(CreatorCategoryMap.topicMatchKeys(parts), creatorKeys);
    }

    private static boolean isAll(List<String> topicCategoryParts) {
        return topicCategoryParts.stream()
                .anyMatch(part -> part.equalsIgnoreCase(ContentTopic.CATEGORY_ALL));
    }

    /**
     * @return the rejection category for the first unsafe line (title checked before angles, then
     *     angles in file order), or {@code null} if the title and every angle are safe.
     */
    private static String firstRejectionCategory(ContentTopic topic) {
        if (!TrendHeadlineScreener.isSafeForCreatorCopy(topic.getTitle())) {
            return TrendHeadlineScreener.rejectionCategory(topic.getTitle());
        }
        for (String angle : splitAngles(topic.getAngles())) {
            if (!TrendHeadlineScreener.isSafeForCreatorCopy(angle)) {
                return TrendHeadlineScreener.rejectionCategory(angle);
            }
        }
        return null;
    }

    /** Splits {@code angles} on newlines, trims each line, and drops blank lines. */
    static List<String> splitAngles(String angles) {
        if (angles == null || angles.isBlank()) {
            return List.of();
        }
        return Arrays.stream(angles.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
