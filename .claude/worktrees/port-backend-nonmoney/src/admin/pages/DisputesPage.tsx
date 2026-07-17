/**
 * INFLUORA ADMIN PANEL — Disputes Page
 * Owner: Ananya (Frontend)
 * Reference: Task #9 (this cycle). Mounted at /admin/disputes by
 * `src/pages/admin-console.tsx`.
 *
 * Thin page shell (header + description) around `DisputeList.tsx`
 * (src/admin/components/disputes/DisputeList.tsx), same split as
 * `UsersPage.tsx` around `BrandsTable`/`CreatorsTable`. Disputes are opened,
 * reviewed, and resolved (escrow release/refund/split) entirely within
 * `DisputeList`'s row-click Dialog — this page owns no data itself.
 */

import { Scale } from 'lucide-react';
import DisputeList from '../components/disputes/DisputeList';

export default function DisputesPage() {
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <Scale className="size-6" aria-hidden="true" />
          Disputes
        </h2>
        <p className="text-sm text-muted-foreground">
          Review open brand/creator disputes and resolve them — release escrow to the
          creator, refund the brand, or split between both. Every resolution requires a
          reason and is recorded to the audit log.
        </p>
      </div>

      <DisputeList />
    </div>
  );
}
