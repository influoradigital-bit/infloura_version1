/**
 * INFLUORA ADMIN PANEL — Admin Console Router Container
 * Owner: Ananya (Frontend)
 * Reference: PRIYA-CTO-ADMIN-MISSING-FEATURES-2026-07-13.md §6 Phase 1
 *
 * Nested router for the admin panel. Wraps all admin screens in `AdminLayout`
 * and routes /admin/* paths to their respective components:
 * - /admin (default) → PulseDashboard
 * - /admin/users/* → UsersPage (Brands/Creators list + BrandProfile/CreatorProfile detail, Task #8)
 * - /admin/campaigns → CampaignTable
 * - /admin/finance → FeeControlPanel
 * - /admin/support → TicketList
 * - /admin/moderation → ModerationPage (Content Flags + Approvals tabs, A7)
 * - /admin/disputes → DisputesPage (Task #9)
 * - /admin/billing → BillingPage (Subscription billing console, Task #25 — UI shell,
 *   mock data until AdminBillingController ships)
 * - /admin/audit → AuditLogPage (Audit log viewer, A6)
 * - /admin/errors → ErrorLogPage (Error log console — recent errors + stats + resolve)
 * - /admin/emails → EmailQueuePage (Email queue console — queue + stats + templates + retry)
 *
 * Route-level auth gating happens in `AdminProtectedRoute` (src/App.tsx) — this
 * component assumes it is only ever rendered for an authenticated session.
 */

import { Routes, Route, Navigate } from 'react-router-dom';
import AdminLayout from '@/admin/components/AdminLayout';
import PulseDashboard from '@/admin/components/dashboard/PulseDashboard';
import UsersPage from '@/admin/pages/UsersPage';
import CampaignTable from '@/admin/components/campaigns/CampaignTable';
import FeeControlPanel from '@/admin/components/finance/FeeControlPanel';
import TicketList from '@/admin/components/support/TicketList';
import ModerationPage from '@/admin/pages/ModerationPage';
import DisputesPage from '@/admin/pages/DisputesPage';
import BillingPage from '@/admin/pages/BillingPage';
import AuditLogPage from '@/admin/pages/AuditLogPage';
import ErrorLogPage from '@/admin/pages/ErrorLogPage';
import EmailQueuePage from '@/admin/pages/EmailQueuePage';
import { useAdminAuth } from '@/admin/hooks/useAdminAuth';

export default function AdminConsolePage() {
  const { user } = useAdminAuth();

  // AdminUser (src/admin/types/admin.types.ts) only carries `email`, no
  // display name — fall back to the local part of the email, then to the
  // AdminLayout default ('Admin') while the session is still resolving.
  const adminName = user?.email ? user.email.split('@')[0] : undefined;

  return (
    <AdminLayout adminName={adminName} pageTitle="Admin Console">
      <Routes>
        <Route index element={<PulseDashboard />} />
        <Route path="users/*" element={<UsersPage />} />
        <Route path="campaigns" element={<CampaignTable />} />
        <Route path="finance" element={<FeeControlPanel />} />
        <Route path="support" element={<TicketList />} />
        <Route path="moderation" element={<ModerationPage />} />
        <Route path="disputes" element={<DisputesPage />} />
        <Route path="billing" element={<BillingPage />} />
        <Route path="audit" element={<AuditLogPage />} />
        <Route path="errors" element={<ErrorLogPage />} />
        <Route path="emails" element={<EmailQueuePage />} />
        {/* Catch unmatched /admin/* paths — redirect to dashboard */}
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Routes>
    </AdminLayout>
  );
}
