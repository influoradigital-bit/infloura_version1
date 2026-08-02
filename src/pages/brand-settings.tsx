import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { Settings, Bell, Lock, Users, LogOut, Save, Crown, ArrowRight, UserPlus, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { api, isApiLive, ApiError, type NotificationPreference } from '@/lib/api';
import { useAuthStore } from '@/lib/store';
import { toast } from '@/hooks/use-toast';

/**
 * Verified against influora-api: NotificationService#isUnsubscribed (service/notification/
 * NotificationService.java) keys the per-event lookup on `event.eventType()` — the literal string
 * each NotificationEvent record returns from its own `eventType()` method — NOT the "creator.x" /
 * "brand.x" strings NotificationListener also passes as `templateKey` for email-template selection.
 * Those templateKey strings (e.g. "creator.campaign_match", "brand.new_application",
 * "creator.bid_accepted", "brand.counter_bid", "brand.proposal_accepted",
 * "creator.proposal_received") are never read by isUnsubscribed, so binding a toggle to one would
 * be a dead preference row. Further restricted to events where the BRAND is the actual recipient
 * (NotificationListener's "Creator -> Brand events" block, `emailOf(event.userId())` resolving to
 * the brand's own user) — "campaign.created" (CampaignCreatedEvent) and "bid.accepted"
 * (BidAcceptedEvent) are real, emitted eventTypes, but both only ever notify the *creator* side, so
 * a brand's preference row for them would never be matched by isUnsubscribed either. Grepped and
 * cross-checked against every *Event.java under service/notification/event/ on 2026-07-30.
 *
 * UPDATE (2026-07-30, Vikram recipient-trace): expanded both groups to the complete brand-recipient
 * eventType sets — traced by which emailOf(...) resolution each event actually hits, not by
 * comment/file grouping. `message.first` is a real brand-recipient eventType too but is
 * DELIBERATELY EXCLUDED from Campaign Alerts (Priya ruling) — a first-message email isn't a
 * "campaign alert", and folding it in would mean disabling Campaign Alerts silently kills message
 * notifications, which is surprising. It stays controlled only by the global "*" switch until a
 * dedicated "Messages" toggle exists. Contract/billing eventTypes (contract.signed,
 * contract.ready_for_escrow, billing.invoice_ready, billing.payment_failed,
 * billing.subscription_halted) are also real brand-recipient events but are intentionally NOT
 * bound to any toggle — see the transactional-notice copy in the Notifications tab below.
 */
const CAMPAIGN_ALERT_EVENT_TYPES = ['application.created', 'deliverable.submitted', 'shipment.received'] as const;
const BID_NOTIFICATION_EVENT_TYPES = ['bid.countered', 'proposal.accepted'] as const;
const CATEGORY_EVENT_TYPES = {
  campaignAlerts: CAMPAIGN_ALERT_EVENT_TYPES,
  bidNotifications: BID_NOTIFICATION_EVENT_TYPES,
} as const;
type CategoryPrefKey = keyof typeof CATEGORY_EVENT_TYPES;

// OFF only when every eventType in the group is unsubscribed; a missing row defaults to
// subscribed (mirrors NotificationService#isUnsubscribed's own default), so any missing/false row
// keeps the group ON. Deterministic and idempotent regardless of preference-row ordering.
function isCategoryGroupOff(prefs: NotificationPreference[], eventTypes: readonly string[]): boolean {
  return eventTypes.every((eventType) => prefs.find((p) => p.eventType === eventType)?.unsubscribed === true);
}

export default function BrandSettingsPage() {
  const navigate = useNavigate();
  const { logout: clearAuthStore } = useAuthStore();
  const liveApi = isApiLive();
  const [activeTab, setActiveTab] = React.useState('general');
  const [settings, setSettings] = React.useState({
    workspaceName: 'Tech Brands Co.',
    email: 'admin@techbrands.in',
    phone: '+91 98765 43210',
    website: 'www.techbrands.in',
    emailNotifications: true,
    pushNotifications: true,
    weeklyDigest: false,
    campaignAlerts: true,
    bidNotifications: true,
  });
  const [emailPrefLoading, setEmailPrefLoading] = React.useState(false);
  const [emailPrefSaving, setEmailPrefSaving] = React.useState(false);
  const [emailPrefError, setEmailPrefError] = React.useState<string | null>(null);

  // Granular category toggles (campaignAlerts/bidNotifications/weeklyDigest) still have no
  // backend emitter reading them — they stay UI-only local state, disabled, with an honest
  // caption. Per Priya's UI Honesty rule this is the "Disabled + captioned" bucket, not "Wired".
  const SETTINGS_PERSISTENCE_UNAVAILABLE =
    "Settings sync isn't available yet — changes apply to this session only.";

  // UPDATE (2026-07-18): GET/PATCH /workspaces/me now exist (WorkspaceController, L-9) and
  // cover name/email(-> billing_email)/phone/websiteUrl for this "Workspace Information" card.
  // `email` is the workspace's billing/contact email server-side, not a login email. `phone`
  // shipped 2026-07-18 (migration + GET/PATCH, Vikram) — full-replace, blank clears it
  // server-side; server validates `+ ( ) - space` + 7-15 digits, so we don't duplicate that
  // validation client-side beyond a light hint.
  const [workspaceInfoLoading, setWorkspaceInfoLoading] = React.useState(false);
  const [workspaceInfoLoadError, setWorkspaceInfoLoadError] = React.useState<string | null>(null);
  const [workspaceInfoSaving, setWorkspaceInfoSaving] = React.useState(false);
  const [workspaceInfoSaveError, setWorkspaceInfoSaveError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!liveApi) return; // mock mode: local defaults are the whole story
    let cancelled = false;
    setWorkspaceInfoLoading(true);
    setWorkspaceInfoLoadError(null);
    api.workspaces
      .getMe()
      .then((ws) => {
        if (cancelled) return;
        setSettings((prev) => ({
          ...prev,
          workspaceName: ws.name,
          // Live-mode hydration: a null field means the workspace genuinely has no
          // value on file — fall back to '' (honest empty), NOT prev.* which is the
          // mock seed (admin@techbrands.in / +91 98765 43210 / www.techbrands.in).
          // Keeping the seed leaked those placeholders into the live form and into the
          // "You" member row (which renders `settings.email || 'No email on file'`).
          email: ws.email ?? '',
          phone: ws.phone ?? '',
          website: ws.websiteUrl ?? '',
        }));
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load workspace information', err);
        setWorkspaceInfoLoadError('Could not load workspace information.');
      })
      .finally(() => {
        if (!cancelled) setWorkspaceInfoLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [liveApi]);

  const handleSaveWorkspaceInfo = async () => {
    if (!settings.workspaceName.trim()) {
      setWorkspaceInfoSaveError('Workspace name is required.');
      return;
    }
    setWorkspaceInfoSaving(true);
    setWorkspaceInfoSaveError(null);
    try {
      const updated = await api.workspaces.updateMe({
        name: settings.workspaceName,
        email: settings.email,
        phone: settings.phone,
        websiteUrl: settings.website,
      });
      setSettings((prev) => ({
        ...prev,
        workspaceName: updated.name,
        // Same honest-empty rule as the load path — a cleared field comes back null
        // and must not fall back to the mock seed.
        email: updated.email ?? '',
        phone: updated.phone ?? '',
        website: updated.websiteUrl ?? '',
      }));
      toast({ title: 'Workspace updated', description: 'Your workspace information has been saved.' });
    } catch (err) {
      console.error('Failed to save workspace information', err);
      const message =
        err instanceof ApiError && err.status === 403
          ? 'Only workspace owners/admins can change these settings.'
          : 'Could not save workspace information. Please try again.';
      setWorkspaceInfoSaveError(message);
      toast({ title: 'Save failed', description: message, variant: 'destructive' });
    } finally {
      setWorkspaceInfoSaving(false);
    }
  };

  // UPDATE (2026-07-18): notifications.getPreferences/setPreference (src/lib/api.ts) now hit a
  // real, auth-scoped route (GET/POST /notifications/preferences, NotificationController.java),
  // backed by the existing email_preferences table. "Email Notifications" below is wired to the
  // global opt-out (eventType "*"), which NotificationService#isUnsubscribed checks FIRST and
  // short-circuits on — if the master switch is off, per-category rows are moot regardless of
  // their own value, so that stays the master switch, unchanged.
  // UPDATE (2026-07-30): campaignAlerts/bidNotifications ARE wired for real now — isUnsubscribed
  // falls through to a genuine per-eventType lookup (findByUserIdAndEventType) once the global
  // check passes, and that lookup is honored by the real event emitters. See
  // CATEGORY_EVENT_TYPES above for the exact eventType groups and why each string was kept/dropped.
  // weeklyDigest and pushNotifications remain unbacked (no weekly-digest job — only
  // "cron.monthly_statement" exists — and no push infra at all) and stay UI-only/disabled.
  React.useEffect(() => {
    let cancelled = false;
    setEmailPrefLoading(true);
    api.notifications
      .getPreferences('brand')
      .then((prefs) => {
        if (cancelled) return;
        const globalPref = prefs.find((p) => p.eventType === '*');
        setSettings((prev) => ({
          ...prev,
          emailNotifications: !globalPref?.unsubscribed,
          campaignAlerts: !isCategoryGroupOff(prefs, CAMPAIGN_ALERT_EVENT_TYPES),
          bidNotifications: !isCategoryGroupOff(prefs, BID_NOTIFICATION_EVENT_TYPES),
        }));
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load notification preferences', err);
        setEmailPrefError('Could not load your email notification preference.');
      })
      .finally(() => {
        if (!cancelled) setEmailPrefLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleEmailPrefChange = async (checked: boolean) => {
    const previous = settings.emailNotifications;
    setSettings({ ...settings, emailNotifications: checked });
    setEmailPrefError(null);

    if (!liveApi) return; // mock mode: local state is the whole story

    setEmailPrefSaving(true);
    try {
      await api.notifications.setPreference('brand', '*', checked);
    } catch (err) {
      console.error('Failed to save notification preference', err);
      setSettings((prev) => ({ ...prev, emailNotifications: previous })); // revert on failure
      setEmailPrefError('Could not save. Please try again.');
      toast({
        title: 'Save failed',
        description: 'Could not update your email notification preference.',
        variant: 'destructive',
      });
    } finally {
      setEmailPrefSaving(false);
    }
  };

  // Category toggles (campaignAlerts/bidNotifications) — one setPreference call per eventType in
  // the group (no bulk endpoint; same one-call-per-item precedent as markAllRead in
  // useNotifications.ts). A partial failure would leave the group's eventTypes in an inconsistent
  // subscribed state, so this reverts the whole optimistic toggle on ANY failure rather than trying
  // to reconcile per-eventType, same as handleEmailPrefChange above.
  const [categoryPrefSaving, setCategoryPrefSaving] = React.useState<Record<CategoryPrefKey, boolean>>({
    campaignAlerts: false,
    bidNotifications: false,
  });
  const [categoryPrefError, setCategoryPrefError] = React.useState<Record<CategoryPrefKey, string | null>>({
    campaignAlerts: null,
    bidNotifications: null,
  });

  const handleCategoryPrefChange = async (key: CategoryPrefKey, checked: boolean) => {
    const previous = settings[key];
    setSettings((prev) => ({ ...prev, [key]: checked }));
    setCategoryPrefError((prev) => ({ ...prev, [key]: null }));

    if (!liveApi) return; // mock mode: local state is the whole story

    setCategoryPrefSaving((prev) => ({ ...prev, [key]: true }));
    try {
      await Promise.all(
        CATEGORY_EVENT_TYPES[key].map((eventType) => api.notifications.setPreference('brand', eventType, checked)),
      );
    } catch (err) {
      console.error(`Failed to save ${key} notification preference`, err);
      setSettings((prev) => ({ ...prev, [key]: previous })); // revert on failure
      setCategoryPrefError((prev) => ({ ...prev, [key]: 'Could not save. Please try again.' }));
      toast({
        title: 'Save failed',
        description: 'Could not update your notification preference.',
        variant: 'destructive',
      });
    } finally {
      setCategoryPrefSaving((prev) => ({ ...prev, [key]: false }));
    }
  };

  // BR-05 — GET /workspace/members is real (WorkspaceMemberController.java:74,
  // api.workspaceMembers.list() at src/lib/api.ts). Live mode renders the actual roster; the
  // response has no name/email per row (MemberResponse = id/workspaceId/userId/role/active),
  // so non-self rows are labelled by a truncated userId rather than inventing a display name.
  const mockWorkspaceMembers = [
    { name: 'Amit Singh', role: 'Workspace Owner', email: 'amit@techbrands.in' },
    { name: 'Priya Kumar', role: 'Manager', email: 'priya@techbrands.in' },
    { name: 'Rahul Verma', role: 'Editor', email: 'rahul@techbrands.in' },
  ];

  const [memberRows, setMemberRows] = React.useState<
    { id: string; userId: string; role: string; active: boolean; isYou: boolean }[]
  >([]);
  const [membersLoading, setMembersLoading] = React.useState(false);
  const [membersError, setMembersError] = React.useState<string | null>(null);

  const loadMembers = React.useCallback(() => {
    if (!liveApi) return;
    setMembersLoading(true);
    setMembersError(null);
    const myUserId = localStorage.getItem('brand_user_id');
    api.workspaceMembers
      .list()
      .then((rows) => {
        setMemberRows(
          rows.map((r) => ({
            id: r.id,
            userId: r.userId,
            role: r.role,
            active: r.active,
            isYou: r.userId === myUserId,
          })),
        );
      })
      .catch((err) => {
        console.error('Failed to load workspace members', err);
        setMembersError('Could not load workspace members.');
      })
      .finally(() => setMembersLoading(false));
  }, [liveApi]);

  React.useEffect(() => {
    loadMembers();
  }, [loadMembers]);

  // Invite Member dialog — POST /workspace/members/invite (Vikram, WorkspaceMemberController.java:51).
  const [isInviteOpen, setIsInviteOpen] = React.useState(false);
  const [inviteEmail, setInviteEmail] = React.useState('');
  const [inviteRole, setInviteRole] = React.useState<'ADMIN' | 'MANAGER' | 'MEMBER' | 'VIEWER'>('MEMBER');
  const [inviteSubmitting, setInviteSubmitting] = React.useState(false);
  const [inviteError, setInviteError] = React.useState<string | null>(null);

  const handleInviteMember = async () => {
    const email = inviteEmail.trim();
    if (!email) {
      setInviteError('Email is required.');
      return;
    }
    setInviteSubmitting(true);
    setInviteError(null);
    try {
      await api.workspaceMembers.invite(email, inviteRole);
      toast({ title: 'Invite sent', description: `${email} has been invited as ${inviteRole.toLowerCase()}.` });
      setIsInviteOpen(false);
      setInviteEmail('');
      setInviteRole('MEMBER');
    } catch (err) {
      console.error('Failed to invite workspace member', err);
      setInviteError(err instanceof ApiError ? err.message : 'Could not send the invite. Try again.');
    } finally {
      setInviteSubmitting(false);
    }
  };

  // Change Password dialog — POST /me/password (Vikram, landing in parallel with this change).
  const [isPasswordOpen, setIsPasswordOpen] = React.useState(false);
  const [currentPassword, setCurrentPassword] = React.useState('');
  const [newPassword, setNewPassword] = React.useState('');
  const [confirmPassword, setConfirmPassword] = React.useState('');
  const [passwordSubmitting, setPasswordSubmitting] = React.useState(false);
  const [passwordError, setPasswordError] = React.useState<string | null>(null);

  const handleChangePassword = async () => {
    if (!currentPassword || !newPassword) {
      setPasswordError('Current and new password are both required.');
      return;
    }
    if (newPassword.length < 8) {
      setPasswordError('New password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('New passwords do not match.');
      return;
    }
    setPasswordSubmitting(true);
    setPasswordError(null);
    try {
      await api.auth.changePassword({ currentPassword, newPassword });
      toast({ title: 'Password changed', description: 'Your password has been updated.' });
      setIsPasswordOpen(false);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      console.error('Failed to change password', err);
      setPasswordError(err instanceof ApiError ? err.message : 'Could not change your password. Try again.');
    } finally {
      setPasswordSubmitting(false);
    }
  };

  // Logout from All Devices — POST /auth/logout (revokes every refresh token server-side).
  const [loggingOutAllDevices, setLoggingOutAllDevices] = React.useState(false);
  const handleLogoutAllDevices = async () => {
    setLoggingOutAllDevices(true);
    try {
      await api.auth.logout('brand');
    } catch (err) {
      // `api.auth.logout` already clears the local token before the request fires, so a
      // failed network call still leaves this session logged out client-side.
      console.error('Logout-all-devices request failed', err);
    } finally {
      clearAuthStore();
      setLoggingOutAllDevices(false);
      navigate('/brand/login');
    }
  };

  return (
    <div className="flex-1 overflow-auto">
      <div className="p-8 max-w-5xl">
        {/* Header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold tracking-tight flex items-center gap-2">
            <Settings className="h-8 w-8" />
            Settings
          </h1>
          <p className="text-muted-foreground mt-2">Manage your workspace preferences and account</p>
        </div>

        <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-6">
          <TabsList className="grid w-full grid-cols-4 lg:w-auto">
            <TabsTrigger value="general" className="gap-2">
              <Users className="h-4 w-4" />
              <span className="hidden sm:inline">General</span>
            </TabsTrigger>
            <TabsTrigger value="notifications" className="gap-2">
              <Bell className="h-4 w-4" />
              <span className="hidden sm:inline">Notifications</span>
            </TabsTrigger>
            <TabsTrigger value="billing" className="gap-2">
              <Crown className="h-4 w-4" />
              <span className="hidden sm:inline">Billing</span>
            </TabsTrigger>
            <TabsTrigger value="security" className="gap-2">
              <Lock className="h-4 w-4" />
              <span className="hidden sm:inline">Security</span>
            </TabsTrigger>
          </TabsList>

          {/* General Settings */}
          <TabsContent value="general" className="space-y-6">
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Workspace Information</h3>
              {workspaceInfoLoading && (
                <p className="text-xs text-muted-foreground mb-4">Loading workspace information…</p>
              )}
              {workspaceInfoLoadError && (
                <p className="text-xs text-destructive-foreground mb-4">{workspaceInfoLoadError}</p>
              )}
              <div className="space-y-4">
                <div>
                  <Label htmlFor="workspace-name">Workspace Name</Label>
                  <Input
                    id="workspace-name"
                    value={settings.workspaceName}
                    onChange={(e) => setSettings({ ...settings, workspaceName: e.target.value })}
                    disabled={workspaceInfoLoading}
                    className="mt-2"
                  />
                </div>
                <div>
                  <Label htmlFor="email">Email</Label>
                  <Input
                    id="email"
                    type="email"
                    value={settings.email}
                    onChange={(e) => setSettings({ ...settings, email: e.target.value })}
                    disabled={workspaceInfoLoading}
                    placeholder="No email on file"
                    className="mt-2"
                  />
                  <p className="text-xs text-muted-foreground mt-1">Used for billing & workspace contact</p>
                </div>
                <div>
                  <Label htmlFor="phone">Phone</Label>
                  <Input
                    id="phone"
                    type="tel"
                    value={settings.phone}
                    onChange={(e) => setSettings({ ...settings, phone: e.target.value })}
                    disabled={workspaceInfoLoading}
                    placeholder="+91 98765 43210"
                    className="mt-2"
                  />
                  <p className="text-xs text-muted-foreground mt-1">
                    Digits, spaces, and + ( ) - only. Leave blank to clear.
                  </p>
                </div>
                <div>
                  <Label htmlFor="website">Website</Label>
                  <Input
                    id="website"
                    value={settings.website}
                    onChange={(e) => setSettings({ ...settings, website: e.target.value })}
                    disabled={workspaceInfoLoading}
                    className="mt-2"
                  />
                </div>
                <Button
                  onClick={handleSaveWorkspaceInfo}
                  disabled={workspaceInfoLoading || workspaceInfoSaving}
                  className="gap-2"
                >
                  <Save className="h-4 w-4" />
                  {workspaceInfoSaving ? 'Saving…' : 'Save Changes'}
                </Button>
                {workspaceInfoSaveError && (
                  <p className="text-xs text-destructive-foreground">{workspaceInfoSaveError}</p>
                )}
              </div>
            </Card>

            <Card className="p-6">
              <h3 className="font-semibold mb-6">Workspace Members</h3>
              <div className="space-y-3">
                {liveApi && membersLoading && (
                  <p className="text-xs text-muted-foreground">Loading members…</p>
                )}
                {liveApi && !membersLoading && membersError && (
                  <p className="text-xs text-destructive-foreground">{membersError}</p>
                )}
                {liveApi && !membersLoading && !membersError &&
                  memberRows.map((member) => (
                    <div key={member.id} className="flex items-center justify-between p-3 border rounded-lg">
                      <div className="flex items-center gap-3">
                        <Avatar className="h-8 w-8">
                          <AvatarFallback>
                            {(member.isYou ? settings.email || 'Y' : member.userId).charAt(0).toUpperCase()}
                          </AvatarFallback>
                        </Avatar>
                        <div>
                          <p className="font-medium text-sm">
                            {member.isYou ? 'You' : `Member ${member.userId.slice(0, 8)}`}
                          </p>
                          <p className="text-xs text-muted-foreground">
                            {member.isYou
                              ? settings.email || 'No email on file'
                              : member.active
                                ? 'Active'
                                : 'Deactivated'}
                          </p>
                        </div>
                      </div>
                      <Badge>{member.role}</Badge>
                    </div>
                  ))}
                {!liveApi &&
                  mockWorkspaceMembers.map((member) => (
                    <div key={member.email} className="flex items-center justify-between p-3 border rounded-lg">
                      <div className="flex items-center gap-3">
                        <Avatar className="h-8 w-8">
                          <AvatarFallback>{member.name.charAt(0)}</AvatarFallback>
                        </Avatar>
                        <div>
                          <p className="font-medium text-sm">{member.name}</p>
                          <p className="text-xs text-muted-foreground">{member.email}</p>
                        </div>
                      </div>
                      <Badge>{member.role}</Badge>
                    </div>
                  ))}
              </div>
              <Button variant="outline" className="w-full mt-4" onClick={() => setIsInviteOpen(true)}>
                <UserPlus className="h-4 w-4" />
                Invite Member
              </Button>
            </Card>
          </TabsContent>

          {/* Notification Settings */}
          <TabsContent value="notifications" className="space-y-6">
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Notification Preferences</h3>
              <div className="space-y-5">
                <div className="p-4 border rounded-lg">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="font-medium text-sm">Email Notifications</p>
                      <p className="text-xs text-muted-foreground">Receive updates via email</p>
                    </div>
                    <Switch
                      checked={settings.emailNotifications}
                      disabled={emailPrefLoading || emailPrefSaving}
                      onCheckedChange={handleEmailPrefChange}
                    />
                  </div>
                  {(emailPrefLoading || emailPrefSaving || emailPrefError) && (
                    <p
                      className={cn(
                        'text-xs mt-1',
                        emailPrefError ? 'text-destructive-foreground' : 'text-muted-foreground',
                      )}
                    >
                      {emailPrefError ?? (emailPrefLoading ? 'Loading preference…' : 'Saving…')}
                    </p>
                  )}
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Push Notifications</p>
                    <p className="text-xs text-muted-foreground">Browser notifications</p>
                  </div>
                  {/* No push channel exists server-side — disabled rather than a switch that
                      silently does nothing. */}
                  <Switch checked={false} disabled title="Push notifications are not available yet" />
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Campaign Alerts</p>
                    <p className="text-xs text-muted-foreground">
                      New applications, submitted deliverables, and shipment updates on your campaigns.
                    </p>
                  </div>
                  <Switch
                    checked={settings.campaignAlerts}
                    disabled={categoryPrefSaving.campaignAlerts}
                    onCheckedChange={(e) => handleCategoryPrefChange('campaignAlerts', e)}
                  />
                </div>
                {categoryPrefError.campaignAlerts && (
                  <p className="text-xs text-destructive-foreground -mt-3">{categoryPrefError.campaignAlerts}</p>
                )}

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Bid Notifications</p>
                    <p className="text-xs text-muted-foreground">
                      When a creator counters your offer or accepts your proposal.
                    </p>
                  </div>
                  <Switch
                    checked={settings.bidNotifications}
                    disabled={categoryPrefSaving.bidNotifications}
                    onCheckedChange={(e) => handleCategoryPrefChange('bidNotifications', e)}
                  />
                </div>
                {categoryPrefError.bidNotifications && (
                  <p className="text-xs text-destructive-foreground -mt-3">{categoryPrefError.bidNotifications}</p>
                )}

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Weekly Digest</p>
                    <p className="text-xs text-muted-foreground">Summary of activities</p>
                  </div>
                  <Switch
                    checked={settings.weeklyDigest}
                    onCheckedChange={(e) => setSettings({ ...settings, weeklyDigest: e })}
                    disabled
                    title="Weekly digest isn't available yet"
                  />
                </div>

                {/* Priya ruling: contract/escrow/billing emails (contract.signed,
                    contract.ready_for_escrow, billing.invoice_ready, billing.payment_failed,
                    billing.subscription_halted) are transactional and always-on — a brand must
                    not be able to switch off a payment-failed or contract-signed email, so this
                    is disclosure copy, not a control. */}
                <p className="text-xs text-muted-foreground">
                  Essential contract, escrow, and billing emails are always sent and can&apos;t be
                  turned off individually — only the Email Notifications switch above affects them.
                </p>

                <Button disabled title={SETTINGS_PERSISTENCE_UNAVAILABLE} className="w-full gap-2">
                  <Save className="h-4 w-4" />
                  Save Preferences
                </Button>
                <p className="text-xs text-muted-foreground text-center">{SETTINGS_PERSISTENCE_UNAVAILABLE}</p>
              </div>
            </Card>
          </TabsContent>

          {/* Payment Settings — REMOVED (BR-05, Priya 2026-07-30): both cards here were either
              hardcoded fake data (Credit Card ****4242) or need a Razorpay mandate that doesn't
              exist yet (auto-recharge). brand-billing-settings.tsx is the single honest owner of
              payment UI; this tab duplicated it with fabricated state. Per UI Honesty, "Absent"
              beats a control that lies. */}

          {/* Billing & Subscription — links out to the dedicated page (live plan/usage/invoices) */}
          <TabsContent value="billing" className="space-y-6">
            <Card className="p-6">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10">
                    <Crown className="h-5 w-5 text-primary" />
                  </div>
                  <div>
                    <h3 className="font-semibold">Plan & Subscription</h3>
                    <p className="text-sm text-muted-foreground">
                      View your current plan, usage meters, and invoices
                    </p>
                  </div>
                </div>
                <Button onClick={() => navigate('/brand/settings/billing')} className="gap-2">
                  Manage Billing
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </Card>
          </TabsContent>

          {/* Security Settings */}
          <TabsContent value="security" className="space-y-6">
            {/* Two-Factor Authentication — REMOVED (P1 security fix, Priya 2026-07-30). The
                Switch here was interactive and persisted nothing: no user-facing 2FA backend
                exists (only the separate /admin/auth realm). A brand owner flipping this on
                would believe their account was protected when it was not — a security
                misrepresentation, not a stub. Real 2FA goes to backlog with its own spec. */}
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Security</h3>
              <div className="space-y-4">
                <Button variant="outline" className="w-full" onClick={() => setIsPasswordOpen(true)}>
                  Change Password
                </Button>

                {/* "View Active Sessions" removed — needs a session registry we don't have.
                    Never ship a button that 404s. */}
              </div>
            </Card>

            <Card className="p-6">
              <h3 className="font-semibold mb-6">Danger Zone</h3>
              <div className="space-y-3">
                <Button
                  variant="destructive"
                  className="w-full gap-2"
                  onClick={handleLogoutAllDevices}
                  disabled={loggingOutAllDevices}
                >
                  {loggingOutAllDevices ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <LogOut className="h-4 w-4" />
                  )}
                  {loggingOutAllDevices ? 'Logging out…' : 'Logout from All Devices'}
                </Button>
                {/* "Delete Workspace" removed — deliberately not built. Account deletion does not
                    cascade into workspaces, and workspace deletion touches escrow holds, signed
                    contracts, and GST invoices. That's a data-lifecycle project with legal
                    surface, not a settings toggle. */}
              </div>
            </Card>
          </TabsContent>
        </Tabs>

        {/* Invite Member dialog */}
        <Dialog
          open={isInviteOpen}
          onOpenChange={(open) => {
            setIsInviteOpen(open);
            if (!open) {
              setInviteError(null);
              setInviteEmail('');
              setInviteRole('MEMBER');
            } else if (liveApi) {
              loadMembers();
            }
          }}
        >
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Invite a team member</DialogTitle>
              <DialogDescription>
                They&apos;ll receive an email invite to join this workspace.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-2">
              <div>
                <Label htmlFor="invite-email">Email</Label>
                <Input
                  id="invite-email"
                  type="email"
                  value={inviteEmail}
                  onChange={(e) => setInviteEmail(e.target.value)}
                  placeholder="teammate@company.com"
                  className="mt-2"
                  disabled={inviteSubmitting}
                />
              </div>
              <div>
                <Label htmlFor="invite-role">Role</Label>
                <Select
                  value={inviteRole}
                  onValueChange={(v) => setInviteRole(v as typeof inviteRole)}
                  disabled={inviteSubmitting}
                >
                  <SelectTrigger id="invite-role" className="mt-2">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ADMIN">Admin</SelectItem>
                    <SelectItem value="MANAGER">Manager</SelectItem>
                    <SelectItem value="MEMBER">Member</SelectItem>
                    <SelectItem value="VIEWER">Viewer</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              {inviteError && <p className="text-xs text-destructive-foreground">{inviteError}</p>}
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setIsInviteOpen(false)} disabled={inviteSubmitting}>
                Cancel
              </Button>
              <Button onClick={handleInviteMember} disabled={inviteSubmitting}>
                {inviteSubmitting ? 'Sending…' : 'Send Invite'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Change Password dialog */}
        <Dialog
          open={isPasswordOpen}
          onOpenChange={(open) => {
            setIsPasswordOpen(open);
            if (!open) {
              setPasswordError(null);
              setCurrentPassword('');
              setNewPassword('');
              setConfirmPassword('');
            }
          }}
        >
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Change password</DialogTitle>
              <DialogDescription>Enter your current password and choose a new one.</DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-2">
              <div>
                <Label htmlFor="current-password">Current password</Label>
                <Input
                  id="current-password"
                  type="password"
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  className="mt-2"
                  disabled={passwordSubmitting}
                />
              </div>
              <div>
                <Label htmlFor="new-password">New password</Label>
                <Input
                  id="new-password"
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  className="mt-2"
                  disabled={passwordSubmitting}
                />
              </div>
              <div>
                <Label htmlFor="confirm-password">Confirm new password</Label>
                <Input
                  id="confirm-password"
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className="mt-2"
                  disabled={passwordSubmitting}
                />
              </div>
              {passwordError && <p className="text-xs text-destructive-foreground">{passwordError}</p>}
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setIsPasswordOpen(false)} disabled={passwordSubmitting}>
                Cancel
              </Button>
              <Button onClick={handleChangePassword} disabled={passwordSubmitting}>
                {passwordSubmitting ? 'Saving…' : 'Change Password'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </div>
  );
}
