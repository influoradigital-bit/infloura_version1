import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { Settings, Bell, Lock, Users, CreditCard, LogOut, Save, ToggleRight, ToggleLeft, Crown, ArrowRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';
import { api, isApiLive, ApiError } from '@/lib/api';
import { toast } from '@/hooks/use-toast';

export default function BrandSettingsPage() {
  const navigate = useNavigate();
  const liveApi = isApiLive();
  const [activeTab, setActiveTab] = React.useState('general');
  const [settings, setSettings] = React.useState({
    workspaceName: 'Tech Brands Co.',
    email: 'admin@techbrands.in',
    phone: '+91 98765 43210',
    website: 'www.techbrands.in',
    autoRecharge: true,
    autoRechargeAmount: 100000,
    emailNotifications: true,
    pushNotifications: true,
    weeklyDigest: false,
    campaignAlerts: true,
    bidNotifications: true,
    twoFactorAuth: false,
  });
  const [emailPrefLoading, setEmailPrefLoading] = React.useState(false);
  const [emailPrefSaving, setEmailPrefSaving] = React.useState(false);
  const [emailPrefError, setEmailPrefError] = React.useState<string | null>(null);

  // autoRecharge* and twoFactorAuth still have no backend route — those stay UI-only local
  // state with the Save control disabled and an honest caption below.
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
          email: ws.email ?? prev.email,
          phone: ws.phone ?? prev.phone,
          website: ws.websiteUrl ?? prev.website,
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
        email: updated.email ?? prev.email,
        phone: updated.phone ?? prev.phone,
        website: updated.websiteUrl ?? prev.website,
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
  // backed by the existing email_preferences table. It covers exactly the global email opt-out
  // (eventType "*"), which is what "Email Notifications" below is wired to. pushNotifications has
  // no backend channel at all (no push infra); campaignAlerts/bidNotifications/weeklyDigest have
  // no matching backend event-category or digest job to bind to — they stay UI-only local state
  // rather than pretend to persist (same precedent as creator-settings.tsx).
  React.useEffect(() => {
    let cancelled = false;
    setEmailPrefLoading(true);
    api.notifications
      .getPreferences('brand')
      .then((prefs) => {
        if (cancelled) return;
        const globalPref = prefs.find((p) => p.eventType === '*');
        setSettings((prev) => ({ ...prev, emailNotifications: !globalPref?.unsubscribed }));
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
          <TabsList className="grid w-full grid-cols-5 lg:w-auto">
            <TabsTrigger value="general" className="gap-2">
              <Users className="h-4 w-4" />
              <span className="hidden sm:inline">General</span>
            </TabsTrigger>
            <TabsTrigger value="notifications" className="gap-2">
              <Bell className="h-4 w-4" />
              <span className="hidden sm:inline">Notifications</span>
            </TabsTrigger>
            <TabsTrigger value="payments" className="gap-2">
              <CreditCard className="h-4 w-4" />
              <span className="hidden sm:inline">Payments</span>
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
                {[
                  { name: 'Amit Singh', role: 'Workspace Owner', email: 'amit@techbrands.in' },
                  { name: 'Priya Kumar', role: 'Manager', email: 'priya@techbrands.in' },
                  { name: 'Rahul Verma', role: 'Editor', email: 'rahul@techbrands.in' },
                ].map((member) => (
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
              <Button variant="outline" className="w-full mt-4">
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
                    <p className="text-xs text-muted-foreground">Alerts on new bids and offers</p>
                  </div>
                  <Switch
                    checked={settings.campaignAlerts}
                    onCheckedChange={(e) => setSettings({ ...settings, campaignAlerts: e })}
                    disabled
                    title="Category preferences aren't available yet"
                  />
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Bid Notifications</p>
                    <p className="text-xs text-muted-foreground">When creators submit bids</p>
                  </div>
                  <Switch
                    checked={settings.bidNotifications}
                    onCheckedChange={(e) => setSettings({ ...settings, bidNotifications: e })}
                    disabled
                    title="Category preferences aren't available yet"
                  />
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Weekly Digest</p>
                    <p className="text-xs text-muted-foreground">Summary of activities</p>
                  </div>
                  <Switch
                    checked={settings.weeklyDigest}
                    onCheckedChange={(e) => setSettings({ ...settings, weeklyDigest: e })}
                    disabled
                    title="Category preferences aren't available yet"
                  />
                </div>

                <Button disabled title={SETTINGS_PERSISTENCE_UNAVAILABLE} className="w-full gap-2">
                  <Save className="h-4 w-4" />
                  Save Preferences
                </Button>
                <p className="text-xs text-muted-foreground text-center">{SETTINGS_PERSISTENCE_UNAVAILABLE}</p>
              </div>
            </Card>
          </TabsContent>

          {/* Payment Settings */}
          <TabsContent value="payments" className="space-y-6">
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Payment Methods</h3>
              <div className="space-y-3">
                {[
                  { type: 'Credit Card', last4: '4242', expiry: '12/26', default: true },
                  { type: 'Bank Transfer', last4: '2847', expiry: 'N/A', default: false },
                ].map((method, idx) => (
                  <div key={idx} className="flex items-center justify-between p-3 border rounded-lg">
                    <div>
                      <p className="font-medium text-sm">{method.type}</p>
                      <p className="text-xs text-muted-foreground">
                        {method.type === 'Credit Card' ? `****${method.last4}` : `Ends: ${method.last4}`}
                      </p>
                    </div>
                    {method.default && <Badge variant="outline">Default</Badge>}
                  </div>
                ))}
              </div>
              <Button variant="outline" className="w-full mt-4">
                Add Payment Method
              </Button>
            </Card>

            <Card className="p-6">
              <h3 className="font-semibold mb-6">Auto-Recharge</h3>
              <div className="space-y-4">
                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Enable Auto-Recharge</p>
                    <p className="text-xs text-muted-foreground">Automatically add funds when balance is low</p>
                  </div>
                  <Switch
                    checked={settings.autoRecharge}
                    onCheckedChange={(e) => setSettings({ ...settings, autoRecharge: e })}
                  />
                </div>

                {settings.autoRecharge && (
                  <div>
                    <Label htmlFor="recharge-amount">Recharge Amount</Label>
                    <div className="flex items-center gap-2 mt-2">
                      <span>₹</span>
                      <Input
                        id="recharge-amount"
                        type="number"
                        value={settings.autoRechargeAmount}
                        onChange={(e) => setSettings({ ...settings, autoRechargeAmount: parseInt(e.target.value) })}
                      />
                    </div>
                  </div>
                )}

                <Button disabled title={SETTINGS_PERSISTENCE_UNAVAILABLE} className="w-full gap-2">
                  <Save className="h-4 w-4" />
                  Save Settings
                </Button>
                <p className="text-xs text-muted-foreground text-center">{SETTINGS_PERSISTENCE_UNAVAILABLE}</p>
              </div>
            </Card>
          </TabsContent>

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
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Security</h3>
              <div className="space-y-4">
                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Two-Factor Authentication</p>
                    <p className="text-xs text-muted-foreground">Protect your account with 2FA</p>
                  </div>
                  <Switch
                    checked={settings.twoFactorAuth}
                    onCheckedChange={(e) => setSettings({ ...settings, twoFactorAuth: e })}
                  />
                </div>

                <Button variant="outline" className="w-full">
                  Change Password
                </Button>

                <Button variant="outline" className="w-full">
                  View Active Sessions
                </Button>
              </div>
            </Card>

            <Card className="p-6">
              <h3 className="font-semibold mb-6">Danger Zone</h3>
              <div className="space-y-3">
                <Button variant="destructive" className="w-full gap-2">
                  <LogOut className="h-4 w-4" />
                  Logout from All Devices
                </Button>
                <Button variant="outline" className="w-full text-destructive-foreground hover:text-destructive-foreground">
                  Delete Workspace
                </Button>
              </div>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
}
