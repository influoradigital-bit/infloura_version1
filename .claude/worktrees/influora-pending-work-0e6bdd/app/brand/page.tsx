'use client';

import { BrandLayout } from '@/components/brand/brand-layout';
import { DashboardPage } from '@/components/brand/dashboard/dashboard-page';

export default function BrandDashboardPage() {
  return (
    <BrandLayout>
      <DashboardPage />
    </BrandLayout>
  );
}
