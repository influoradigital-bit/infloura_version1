package com.influora.service;

import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import java.time.Instant;

/**
 * One pending {@code application_history_events} row, captured at the call site and written after
 * the caller's transaction commits (see {@link com.influora.common.AfterCommit}).
 *
 * <p>A plain immutable value, not the entity: it is created inside the business transaction but
 * persisted from a later, independent one, so it must carry no JPA identity, no persistence
 * context and no lazy association. {@code historyId} is assigned at write time, so a request that
 * is discarded burns no id.
 *
 * @param viewIfAbsent {@code true} for the {@code APPLICATION_VIEWED} first-write-wins variant
 *     ({@code ApplicationHistoryService#recordViewIfAbsent}): the writer runs the existence check
 *     before inserting. {@code false} for every append-always event type.
 * @param capturedAt when the business action recorded the event. Used only in log lines, so a row
 *     rebuilt by hand from a WITHHELD or LOST line can carry the time the action happened.
 */
public record ApplicationHistoryWriteRequest(
        String campaignId,
        String applicationId,
        String dealRoomId,
        ApplicationHistoryEventType eventType,
        CollaborationStatus eventStatus,
        ApplicationHistoryActorType actorType,
        String actorId,
        String description,
        String metadata,
        String targetRoute,
        String targetId,
        boolean viewIfAbsent,
        Instant capturedAt) {

    private static final int MAX_FREE_TEXT_IN_LOG = 500;

    /**
     * The reconstruction payload for {@link com.influora.common.AfterCommit}'s DISCARDED, WITHHELD
     * and LOST lines. It carries every column the row would have had except the generated id and
     * the insert timestamp, so the row can be inserted by hand from the log alone.
     *
     * <p>{@code description} and {@code metadata} are included because the row cannot be rebuilt
     * without them. At every call site they are system-authored text, except the reject/withdraw
     * reason in {@code DealService#doReject}, which is a sanitised note a party wrote for the other
     * party to read. Both are single-lined and capped at {@value #MAX_FREE_TEXT_IN_LOG} characters,
     * so one log line cannot be split or forged by the text inside it.
     */
    public String toLogContext() {
        return "eventType="
                + eventType
                + " applicationId="
                + applicationId
                + " campaignId="
                + campaignId
                + " dealRoomId="
                + dealRoomId
                + " eventStatus="
                + eventStatus
                + " actorType="
                + actorType
                + " actorId="
                + actorId
                + " targetRoute="
                + targetRoute
                + " targetId="
                + targetId
                + " viewIfAbsent="
                + viewIfAbsent
                + " capturedAt="
                + capturedAt
                + " description="
                + quoteForLog(description)
                + " metadata="
                + quoteForLog(metadata);
    }

    private static String quoteForLog(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').replace("\"", "\\\"");
        if (oneLine.length() > MAX_FREE_TEXT_IN_LOG) {
            oneLine = oneLine.substring(0, MAX_FREE_TEXT_IN_LOG) + "...(truncated)";
        }
        return "\"" + oneLine + "\"";
    }
}
