/**
 * INFLUORA ADMIN PANEL — Moderation Page (Content Flags + Approvals)
 * Owner: Ananya (Frontend)
 * Reference: A7 (Priya-routed admin-panel audit fix). Mounted at
 * /admin/moderation by src/pages/admin-console.tsx, replacing the direct
 * `<FlagQueue />` mount.
 *
 * Thin Tabs shell, same split as DisputesPage.tsx/UsersPage.tsx around their
 * list components — chosen over editing FlagQueue.tsx directly (634 lines,
 * live-wired) to keep the existing content-moderation flow at zero
 * regression risk. `FlagQueue` is unchanged; `ApprovalQueue` is new.
 */

import { ShieldAlert } from 'lucide-react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import FlagQueue from '../components/moderation/FlagQueue';
import ApprovalQueue from '../components/moderation/ApprovalQueue';

export default function ModerationPage() {
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <ShieldAlert className="size-6" aria-hidden="true" />
          Moderation
        </h2>
        <p className="text-sm text-muted-foreground">
          Review flagged content and process pending approval workflows — creator applications,
          brand KYC, escrow release, and more.
        </p>
      </div>

      <Tabs defaultValue="flags">
        <TabsList>
          <TabsTrigger value="flags">Content Flags</TabsTrigger>
          <TabsTrigger value="approvals">Approvals</TabsTrigger>
        </TabsList>
        <TabsContent value="flags">
          <FlagQueue />
        </TabsContent>
        <TabsContent value="approvals">
          <ApprovalQueue />
        </TabsContent>
      </Tabs>
    </div>
  );
}
