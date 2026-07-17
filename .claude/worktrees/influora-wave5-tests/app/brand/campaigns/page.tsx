'use client';

import { BrandLayout } from '@/components/brand/brand-layout';
import { CampaignsList } from '@/components/brand/campaigns/campaigns-list';

export default function CampaignsPage() {
  return (
    <BrandLayout>
      <CampaignsList />
    </BrandLayout>
  );
}
