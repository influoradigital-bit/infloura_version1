/**
 * INFLUORA ADMIN PANEL — Billing Page
 * Owner: Ananya (Frontend)
 * Reference: wiki/processes/subscription-billing-task-breakdown.md, Task 25.
 * Mounted at /admin/billing by `src/pages/admin-console.tsx`.
 *
 * Thin page shell (header + description) around `BillingConsole.tsx`
 * (src/admin/components/billing/BillingConsole.tsx), same split as
 * `DisputesPage.tsx` around `DisputeList`. BillingConsole owns all the data
 * (currently mock — see its file header) and the Comp Pro / Override modals;
 * this page owns no state itself.
 */

import { CreditCard } from 'lucide-react';
import BillingConsole from '../components/billing/BillingConsole';

export default function BillingPage() {
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <CreditCard className="size-6" aria-hidden="true" />
          Billing
        </h2>
        <p className="text-sm text-muted-foreground">
          MRR/ARR/churn overview, workspace subscriptions, and comp/override actions for
          subscription billing. Backend (`AdminBillingController`) is pending — data below is
          demo/mock until live wiring lands.
        </p>
      </div>

      <BillingConsole />
    </div>
  );
}
