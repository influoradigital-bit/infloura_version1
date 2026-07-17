'use client';

import { BrandLayout } from '@/components/brand/brand-layout';
import { CampaignForm } from '@/components/brand/campaigns/campaign-form';

export default function NewCampaignPage() {
  return (
    <BrandLayout>
      <CampaignForm />
    </BrandLayout>
  );
}
