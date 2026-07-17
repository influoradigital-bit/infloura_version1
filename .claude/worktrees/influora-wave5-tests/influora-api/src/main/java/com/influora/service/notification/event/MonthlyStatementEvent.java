package com.influora.service.notification.event;

/** #22: Monthly statement (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). Email only. */
public record MonthlyStatementEvent(
        String userId,
        String workspaceId,
        String entityId,
        String statementPeriod,
        String statementUrl
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "cron.monthly_statement";
    }
}
