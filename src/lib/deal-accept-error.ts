import { ApiError } from '@/lib/api';

// Shared by both brand surfaces that can accept an offer (/brand/chat and /brand/deals), so the
// two cannot drift: /brand/deals used to swallow every rejection into "Try again.", including
// 409s that a retry can never clear.

/**
 * CR-07 — turns an accept rejection into copy the brand can act on.
 *
 * A 409 here is never "try again": the deal has genuinely moved, and retrying fails identically
 * forever. Each code DealService.doAccept can raise gets its own explanation; anything else keeps
 * the server's own message and only falls back to a retry prompt for transient failures.
 */
export function describeAcceptError(err: unknown): { message: string; stale: boolean } {
  if (err instanceof ApiError && err.status === 409) {
    switch (err.code) {
      case 'DEAL_NOT_ACCEPTABLE':
        return {
          message:
            'This deal has already moved past the offer stage — it can no longer be accepted. Refresh to see where it stands now.',
          stale: true,
        };
      case 'CANNOT_ACCEPT_OWN_OFFER':
        return {
          message:
            'You made the last offer, so the creator has to accept it. Send a new proposal if you want to change the terms.',
          stale: false,
        };
      // Not stale: refreshing re-fetches the same deliverable-less offer and 409s again. What
      // clears it is a new offer that lists the deliverables (the contract builds the creator's
      // submission slots from them), and only the brand's proposal form collects those.
      case 'DELIVERABLES_REQUIRED':
        return {
          message:
            "This offer doesn't list any deliverables, so it can't be accepted yet. Send a proposal that lists the deliverables — the creator can then accept that.",
          stale: false,
        };
      case 'AGREED_RATE_REQUIRED':
        return {
          message:
            'There is no priced offer to accept yet. Send a proposal with a rate and deliverables first.',
          stale: false,
        };
      default:
        return { message: err.message, stale: true };
    }
  }
  if (err instanceof ApiError) {
    return { message: err.message, stale: false };
  }
  return {
    message: 'Could not accept this proposal. Check your connection and try again.',
    stale: false,
  };
}
