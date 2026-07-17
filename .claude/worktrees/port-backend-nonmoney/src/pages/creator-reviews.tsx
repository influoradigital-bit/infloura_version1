import { CreatorLayout } from '@/components/creator/creator-layout';
import { CollaborationReviewsPanel } from '@/components/shared/collaboration-reviews-panel';

/**
 * Creator reviews — Task #33 A4 + A-GA-5 (CEO §1.2).
 * Rate brands post-COMPLETED; view reviews left about the creator via
 * GET /creator/reviews/received (V-GA-8). Write path untouched.
 */
export default function CreatorReviewsPage() {
  return (
    <CreatorLayout>
      <CollaborationReviewsPanel
        role="creator"
        counterpartyLabel="brands"
        pageTitle="Reviews"
        pageDescription="Rate brands after completed collaborations and see feedback left about you."
      />
    </CreatorLayout>
  );
}
