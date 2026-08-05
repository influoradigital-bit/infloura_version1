/**
 * INFLUORA ADMIN PANEL — Finance / Escrow / Revenue Console Page
 * Owner: Ananya (Frontend)
 * Reference: task brief "Admin Finance / Escrow / Revenue console UI".
 * Mounted at /admin/revenue by `src/pages/admin-console.tsx`.
 *
 * Thin page shell (header + description) around `FinanceConsole.tsx`
 * (src/admin/components/finance/FinanceConsole.tsx), same split as
 * `BillingPage.tsx` around `BillingConsole`. FinanceConsole owns all the
 * data (live, read-only) and internal tab state; this page owns no state
 * itself.
 *
 * Named "Revenue" (not "Finance") because `/admin/finance` already routes to
 * `FeeControlPanel` (the platform fee schedule editor) — this is an
 * additional, separate read console over GMV/escrow/at-risk/HYPE/suspension
 * data, not a replacement for the fee-config screen.
 */

import { LineChart } from 'lucide-react';
import FinanceConsole from '../components/finance/FinanceConsole';

export default function RevenuePage() {
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <LineChart className="size-6" aria-hidden="true" />
          Finance &amp; Revenue
        </h2>
        <p className="text-sm text-muted-foreground">
          GMV, platform revenue, escrow health, flagged transactions, at-risk campaigns, HYPE
          ops, and account suspensions. Live, read-only — backed by AdminRevenueService,
          AdminFinanceService, AdminEscrowController, AdminCampaignService, AdminModerationService,
          and AdminMarketingService.
        </p>
      </div>

      <FinanceConsole />
    </div>
  );
}
