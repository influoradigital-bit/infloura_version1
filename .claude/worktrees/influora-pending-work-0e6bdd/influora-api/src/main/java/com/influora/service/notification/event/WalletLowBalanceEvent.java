package com.influora.service.notification.event;

/** #21: Wallet low balance warning (< Rs.500) (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). */
public record WalletLowBalanceEvent(
        String userId,
        String workspaceId,
        String entityId,
        String currentBalance
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "wallet.low_balance";
    }
}
