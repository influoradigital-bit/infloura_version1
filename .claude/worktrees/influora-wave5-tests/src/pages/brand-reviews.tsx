import { BrandLayout } from '@/components/brand/brand-layout';
import { CollaborationReviewsPanel } from '@/components/shared/collaboration-reviews-panel';

/**
 * Brand reviews — Task #33 A4 (CEO §1.2).
 * Rate creators post-COMPLETED; view reviews left about the brand.
 * POST /brand/reviews wired in live mode; received-list read endpoint is an honest gap.
 */
export default function BrandReviewsPage() {
  return (
    <BrandLayout>
      <CollaborationReviewsPanel
        role="brand"
        counterpartyLabel="creators"
        pageTitle="Reviews"
        pageDescription="Rate creators after completed collaborations and see feedback left about your brand."
      />
    </BrandLayout>
  );
}
