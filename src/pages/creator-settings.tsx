import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Separator } from '@/components/ui/separator';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {
  Bell,
  Shield,
  BadgeCheck,
  Lock,
  CreditCard,
  HelpCircle,
  FileText,
  ChevronRight,
  Mail,
  Smartphone,
  LogOut,
  Trash2,
  Loader2,
  CheckCircle2,
  AlertTriangle,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/lib/store';
import { clearCreatorSession } from '@/lib/auth-session';
import { TaxIdentityForm } from '@/components/creator/TaxIdentityForm';
import { KycIdentityForm } from '@/components/creator/KycIdentityForm';
import { api, isApiLive } from '@/lib/api';
import { toast } from '@/hooks/use-toast';
import { COMPANY } from '@/lib/company';

export default function CreatorSettingsPage() {
  const navigate = useNavigate();
  const { logout } = useAuthStore();
  const liveApi = isApiLive();

  // UPDATE (2026-07-18): notifications.getPreferences/setPreference now hit a REAL route —
  // GET/POST /notifications/preferences (NotificationController.java), auth-scoped server-side
  // via AuthPrincipal (no IDOR). It's backed by the existing email_preferences table and covers
  // exactly ONE thing meaningfully: the global email opt-out, eventType "*", which is the same
  // flag NotificationService#isUnsubscribed already honors for every outbound email. That's
  // wired to the "Email Notifications" switch below.
  //
  // The four category switches above it (New Proposals / Deadline Reminders / Payment Updates /
  // Marketing & Tips) do NOT have a 1:1 backend concept to bind to — the 31 real NotificationEvent
  // types (proposal.*, escrow.*, payout.*, kyc.*, ...) aren't grouped into these four buckets
  // anywhere server-side, and there is no "deadline reminder" or "marketing" event at all today.
  // Wiring them to fake eventType strings would silently do nothing (no email path checks a
  // "marketing" or "deadlines" key), so they stay UI-only local state rather than pretend to
  // persist. SMS is UI-only too — docs/features/notifications.md confirms there's no SMS channel.
  const [notifications, setNotifications] = React.useState({
    proposals: true,
    deadlines: true,
    payments: true,
    marketing: false,
    sms: true,
    email: true,
  });
  const [emailPrefLoading, setEmailPrefLoading] = React.useState(false);
  const [emailPrefSaving, setEmailPrefSaving] = React.useState(false);
  const [emailPrefError, setEmailPrefError] = React.useState<string | null>(null);

  const [showTaxIdentityDialog, setShowTaxIdentityDialog] = React.useState(false);
  const [showKycDialog, setShowKycDialog] = React.useState(false);
  const [showPasswordDialog, setShowPasswordDialog] = React.useState(false);
  // CR-87 — the dialog's inputs were uncontrolled and "Update Password" had no
  // handler at all, despite api.auth.changePassword (POST /me/password) already
  // being a real, working, rate-limited endpoint.
  const [currentPassword, setCurrentPassword] = React.useState('');
  const [newPassword, setNewPassword] = React.useState('');
  const [confirmNewPassword, setConfirmNewPassword] = React.useState('');
  const [passwordChangeError, setPasswordChangeError] = React.useState<string | null>(null);
  const [isChangingPassword, setIsChangingPassword] = React.useState(false);
  const [showDeleteDialog, setShowDeleteDialog] = React.useState(false);
  const [showLogoutDialog, setShowLogoutDialog] = React.useState(false);
  const [isLoading, setIsLoading] = React.useState(false);
  const [isLoggingOut, setIsLoggingOut] = React.useState(false);

  // Load the real global email preference (eventType "*") on mount. Mock mode keeps the
  // switch's initial local default (true) via mockOr([]) resolving to an empty list below.
  React.useEffect(() => {
    let cancelled = false;
    setEmailPrefLoading(true);
    api.notifications
      .getPreferences('creator')
      .then((prefs) => {
        if (cancelled) return;
        const globalPref = prefs.find((p) => p.eventType === '*');
        // Absent row = implicitly subscribed (mirrors the backend's isUnsubscribed default).
        setNotifications((prev) => ({ ...prev, email: !globalPref?.unsubscribed }));
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
    const previous = notifications.email;
    setNotifications({ ...notifications, email: checked });
    setEmailPrefError(null);

    if (!liveApi) return; // mock mode: local state is the whole story

    setEmailPrefSaving(true);
    try {
      await api.notifications.setPreference('creator', '*', checked);
    } catch (err) {
      console.error('Failed to save notification preference', err);
      setNotifications((prev) => ({ ...prev, email: previous })); // revert on failure
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

  // POST /auth/logout — real endpoint (api.auth.logout). Mock branch keeps the
  // prior local-only behavior; live branch also invalidates the server session.
  const handleLogout = async () => {
    setIsLoggingOut(true);
    try {
      if (liveApi) {
        await api.auth.logout('creator');
      }
    } catch (err) {
      console.error('Logout request failed', err);
      // Still clear the local session below — a failed server call
      // shouldn't strand the user in a logged-in-looking UI.
      toast({
        title: 'Logged out locally',
        description: 'Could not reach the server to end your session, but you have been signed out on this device.',
        variant: 'destructive',
      });
    } finally {
      setIsLoggingOut(false);
    }
    logout();
    // CR-32 — this is the SECOND creator logout path, and CR-06's session clear only ever
    // reached the first (`creator-layout.tsx`'s sidebar menu). Removing `creator_token`
    // alone left `creator_user_id` / `creator_email` / `creator_display_name` behind, and
    // `persistCreatorSession` writes the display name only when the login response carries
    // one — so the next creator to sign in on this browser without a display name set
    // inherited the previous creator's name in the shell, permanently if
    // `/me/creator-profile` then failed. That is the identity-leak pattern the CR-06 CTO
    // note said to remove at the root, reintroduced through a door CR-06 did not check.
    // `clearCreatorSession()` covers the token too — do not re-add a bare removeItem here.
    clearCreatorSession();
    navigate('/creator/login');
  };

  // POST /me/password — CR-87. The dialog's inputs were uncontrolled and this button had
  // no handler at all, even though api.auth.changePassword is a real, working,
  // rate-limited endpoint (AccountController/AuthService#changePassword).
  const handleChangePassword = async () => {
    setPasswordChangeError(null);
    if (!currentPassword || !newPassword || !confirmNewPassword) {
      setPasswordChangeError('All fields are required.');
      return;
    }
    if (newPassword.length < 8) {
      setPasswordChangeError('New password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmNewPassword) {
      setPasswordChangeError('New password and confirmation do not match.');
      return;
    }
    setIsChangingPassword(true);
    try {
      if (liveApi) {
        await api.auth.changePassword({ currentPassword, newPassword });
      } else {
        await new Promise((resolve) => setTimeout(resolve, 800));
      }
      toast({
        title: 'Password updated',
        description: 'Your password has been changed successfully.',
      });
      setShowPasswordDialog(false);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmNewPassword('');
    } catch (err) {
      console.error('Failed to change password', err);
      // Unlike account deletion, the underlying error (e.g. "current password is
      // incorrect") is actionable — show it rather than a generic message.
      setPasswordChangeError(
        err instanceof Error ? err.message : 'Could not change your password. Please try again.',
      );
    } finally {
      setIsChangingPassword(false);
    }
  };

  const closePasswordDialog = (open: boolean) => {
    setShowPasswordDialog(open);
    if (!open) {
      setCurrentPassword('');
      setNewPassword('');
      setConfirmNewPassword('');
      setPasswordChangeError(null);
    }
  };

  // DELETE /me/account — real, verified soft-delete endpoint (api.me.deleteAccount,
  // AccountController.java). Server anonymizes PII, revokes refresh tokens, and
  // scopes deletedAt-checks into every creator-gated route so it takes effect on
  // the very next request. Mock mode keeps the prior simulated delay.
  const handleDeleteAccount = async () => {
    setIsLoading(true);
    try {
      if (liveApi) {
        await api.me.deleteAccount('creator');
      } else {
        await new Promise((resolve) => setTimeout(resolve, 2000));
      }
      setShowDeleteDialog(false);
      await handleLogout();
    } catch (err) {
      console.error('Failed to delete account', err);
      toast({
        title: 'Could not delete account',
        description: 'Please try again, or contact support if the problem continues.',
        variant: 'destructive',
      });
    } finally {
      setIsLoading(false);
    }
  };

  const settingsGroups: {
    title: string;
    items: {
      icon: typeof Shield;
      label: string;
      description: string;
      status?: string;
      onClick: () => void;
    }[];
  }[] = [
    {
      title: 'Account',
      items: [
        {
          icon: Shield,
          label: 'Tax Identity (GSTIN/PAN)',
          description: 'Add your GSTIN and PAN for correct invoicing',
          onClick: () => setShowTaxIdentityDialog(true),
        },
        {
          icon: BadgeCheck,
          label: 'Identity Verification (KYC)',
          description: 'PAN, Aadhaar & selfie — required before your first withdrawal',
          onClick: () => setShowKycDialog(true),
        },
        {
          icon: CreditCard,
          label: 'Payout Settings',
          description: 'UPI, Bank Account',
          onClick: () => navigate('/creator/wallet'),
        },
        {
          icon: Lock,
          label: 'Change Password',
          description: 'Update your password',
          onClick: () => setShowPasswordDialog(true),
        },
      ],
    },
    {
      title: 'Support',
      items: [
        {
          // CR-88 — was onClick: () => {} (a fully dead row).
          // CORRECTED after Priya's red-team pass: the first version of this fix pointed both
          // rows at 'https://help.influora.com', a host that does not resolve to anything real
          // — the brand side already migrated OFF this exact URL (see
          // wiki/build/ananya-nav-alignment-2026-07-23.md) after discovering the same thing.
          // Help Center now routes to the real in-app /support page instead of an unproven
          // external host or a byte-identical duplicate of the row below.
          icon: HelpCircle,
          label: 'Help Center',
          description: 'FAQs and guides',
          onClick: () => navigate('/support'),
        },
        {
          // Contact Support is a real, CEO-confirmed channel (src/lib/company.ts,
          // "Confirmed by CEO from official docs... 2026-07-13") — not a fabricated address,
          // and genuinely distinct from the Help Center row above (a mailto vs. an in-app page).
          icon: Mail,
          label: 'Contact Support',
          description: 'Get help from our team',
          onClick: () => window.open(`mailto:${COMPANY.email}`, '_blank'),
        },
        {
          // Terms & Privacy — was already routing to a real page, but /terms rendered a
          // "coming soon" placeholder while the actual v0 legal drafts sat unwired
          // (src/content/legal/*.md via LegalPage, imported nowhere in App.tsx). Fixed at the
          // route level (App.tsx: /terms and /privacy now render LegalPage), not here — this
          // row's destination is unchanged and now correct as a side effect.
          icon: FileText,
          label: 'Terms & Privacy',
          description: 'Legal documents',
          onClick: () => navigate('/terms'),
        },
      ],
    },
  ];

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-2xl">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Settings</h1>
          <p className="text-muted-foreground">Manage your account preferences</p>
        </div>

        {/* Notifications */}
        <Card className="mb-6">
          <CardHeader>
            <div className="flex items-center gap-2">
              <Bell className="h-5 w-5 text-muted-foreground" />
              <CardTitle className="text-base">Notifications</CardTitle>
            </div>
            <CardDescription>Choose what updates you receive</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <p className="font-medium text-sm">New Proposals</p>
                  <p className="text-xs text-muted-foreground">Get notified about new collaboration offers</p>
                </div>
                <Switch
                  checked={notifications.proposals}
                  onCheckedChange={(checked) => setNotifications({ ...notifications, proposals: checked })}
                  disabled
                  title="Category preferences aren't available yet"
                />
              </div>
              <Separator />
              <div className="flex items-center justify-between">
                <div>
                  <p className="font-medium text-sm">Deadline Reminders</p>
                  <p className="text-xs text-muted-foreground">Reminders before deliverable deadlines</p>
                </div>
                <Switch
                  checked={notifications.deadlines}
                  onCheckedChange={(checked) => setNotifications({ ...notifications, deadlines: checked })}
                  disabled
                  title="Category preferences aren't available yet"
                />
              </div>
              <Separator />
              <div className="flex items-center justify-between">
                <div>
                  <p className="font-medium text-sm">Payment Updates</p>
                  <p className="text-xs text-muted-foreground">Notifications about payouts and earnings</p>
                </div>
                <Switch
                  checked={notifications.payments}
                  onCheckedChange={(checked) => setNotifications({ ...notifications, payments: checked })}
                  disabled
                  title="Category preferences aren't available yet"
                />
              </div>
              <Separator />
              <div className="flex items-center justify-between">
                <div>
                  <p className="font-medium text-sm">Marketing & Tips</p>
                  <p className="text-xs text-muted-foreground">Platform updates and creator tips</p>
                </div>
                <Switch
                  checked={notifications.marketing}
                  onCheckedChange={(checked) => setNotifications({ ...notifications, marketing: checked })}
                  disabled
                  title="Category preferences aren't available yet"
                />
              </div>
            </div>

            <Separator />

            <div className="space-y-3">
              <p className="text-sm font-medium">Notification Channels</p>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Smartphone className="h-4 w-4 text-muted-foreground" />
                  <span className="text-sm">SMS Notifications</span>
                </div>
                {/* No SMS channel exists server-side (docs/features/notifications.md) — disabled
                    rather than a switch that silently does nothing. */}
                <Switch checked={false} disabled title="SMS notifications are not available yet" />
              </div>
              <div>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Mail className="h-4 w-4 text-muted-foreground" />
                    <span className="text-sm">Email Notifications</span>
                  </div>
                  <Switch
                    checked={notifications.email}
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
            </div>
          </CardContent>
        </Card>

        {/* Settings Groups */}
        {settingsGroups.map((group) => (
          <Card key={group.title} className="mb-6">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">{group.title}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-1">
              {group.items.map((item, idx) => {
                const Icon = item.icon;
                return (
                  <button
                    key={idx}
                    onClick={item.onClick}
                    className="w-full flex items-center justify-between p-3 rounded-lg hover:bg-muted transition-colors"
                  >
                    <div className="flex items-center gap-3">
                      <div className="h-10 w-10 rounded-full bg-muted flex items-center justify-center">
                        <Icon className="h-5 w-5 text-muted-foreground" />
                      </div>
                      <div className="text-left">
                        <p className="font-medium text-sm">{item.label}</p>
                        <p className="text-xs text-muted-foreground">{item.description}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {item.status === 'verified' && (
                        <CheckCircle2 className="h-5 w-5 text-green-500" />
                      )}
                      <ChevronRight className="h-5 w-5 text-muted-foreground" />
                    </div>
                  </button>
                );
              })}
            </CardContent>
          </Card>
        ))}

        {/* Danger Zone */}
        <Card className="border-red-200">
          <CardHeader>
            <div className="flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-red-500" />
              <CardTitle className="text-base text-stage-disputed-fg">Danger Zone</CardTitle>
            </div>
          </CardHeader>
          <CardContent className="space-y-3">
            <Button
              variant="outline"
              className="w-full justify-start text-stage-disputed-fg hover:text-red-700 hover:bg-red-50"
              onClick={() => setShowLogoutDialog(true)}
            >
              <LogOut className="h-4 w-4 mr-2" />
              Logout
            </Button>
            <Button
              variant="outline"
              className="w-full justify-start text-stage-disputed-fg hover:text-red-700 hover:bg-red-50"
              onClick={() => setShowDeleteDialog(true)}
            >
              <Trash2 className="h-4 w-4 mr-2" />
              Delete Account
            </Button>
          </CardContent>
        </Card>

        {/* App Info */}
        <div className="mt-6 text-center text-sm text-muted-foreground">
          <p>Influora v1.0.0</p>
          <p className="mt-1">Made with care in India</p>
        </div>
      </div>

      {/* Tax Identity Dialog */}
      <Dialog open={showTaxIdentityDialog} onOpenChange={setShowTaxIdentityDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Tax Identity</DialogTitle>
            <DialogDescription>
              Your GSTIN and PAN are used on invoices brands receive for your work.
            </DialogDescription>
          </DialogHeader>
          <TaxIdentityForm onSubmitted={() => {}} />
        </DialogContent>
      </Dialog>

      {/* KYC Identity Dialog */}
      <Dialog open={showKycDialog} onOpenChange={setShowKycDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Identity Verification (KYC)</DialogTitle>
            <DialogDescription>
              Verify your identity with your PAN, Aadhaar and a selfie so payouts can be released.
            </DialogDescription>
          </DialogHeader>
          <KycIdentityForm onSubmitted={() => {}} />
        </DialogContent>
      </Dialog>

      {/* Change Password Dialog */}
      <Dialog open={showPasswordDialog} onOpenChange={closePasswordDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Change Password</DialogTitle>
            <DialogDescription>
              Enter your current password and a new password
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            {passwordChangeError && (
              <p role="alert" className="text-sm text-destructive-foreground">
                {passwordChangeError}
              </p>
            )}
            <div className="space-y-2">
              <Label htmlFor="currentPassword">Current Password</Label>
              <Input
                id="currentPassword"
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                disabled={isChangingPassword}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="newPassword">New Password</Label>
              <Input
                id="newPassword"
                type="password"
                autoComplete="new-password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                disabled={isChangingPassword}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="confirmPassword">Confirm New Password</Label>
              <Input
                id="confirmPassword"
                type="password"
                autoComplete="new-password"
                value={confirmNewPassword}
                onChange={(e) => setConfirmNewPassword(e.target.value)}
                disabled={isChangingPassword}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => closePasswordDialog(false)} disabled={isChangingPassword}>
              Cancel
            </Button>
            <Button onClick={handleChangePassword} disabled={isChangingPassword}>
              {isChangingPassword ? 'Updating…' : 'Update Password'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Logout Dialog */}
      <AlertDialog open={showLogoutDialog} onOpenChange={setShowLogoutDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Confirm Logout</AlertDialogTitle>
            <AlertDialogDescription>
              Are you sure you want to logout? You will need to sign in again to access your account.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isLoggingOut}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleLogout}
              className="bg-red-600 hover:bg-red-700"
              disabled={isLoggingOut}
            >
              {isLoggingOut ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Logging out...
                </>
              ) : (
                'Logout'
              )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Delete Account Dialog */}
      <AlertDialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete Account</AlertDialogTitle>
            <AlertDialogDescription>
              This action cannot be undone. This will permanently delete your account and remove all your data from our servers.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDeleteAccount}
              className="bg-red-600 hover:bg-red-700"
              disabled={isLoading}
            >
              {isLoading ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Deleting...
                </>
              ) : (
                'Delete Account'
              )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </CreatorLayout>
  );
}
