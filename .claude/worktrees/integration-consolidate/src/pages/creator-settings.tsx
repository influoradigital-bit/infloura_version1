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
  Lock,
  CreditCard,
  HelpCircle,
  FileText,
  ChevronRight,
  Mail,
  Smartphone,
  LogOut,
  Trash2,
  Building,
  Loader2,
  CheckCircle2,
  AlertTriangle,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/lib/store';
import { TaxIdentityForm } from '@/components/creator/TaxIdentityForm';
import { api, isApiLive } from '@/lib/api';
import { toast } from '@/hooks/use-toast';

export default function CreatorSettingsPage() {
  const navigate = useNavigate();
  const { logout } = useAuthStore();
  const liveApi = isApiLive();

  // NOTE: no notification-preferences endpoint exists in src/lib/api.ts (only
  // notifications.list / notifications.markAllRead, which read/ack items —
  // they don't persist per-user channel toggles). These switches stay UI-only
  // local state until a PATCH /me/notification-prefs (or similar) route ships.
  const [notifications, setNotifications] = React.useState({
    proposals: true,
    deadlines: true,
    payments: true,
    marketing: false,
    sms: true,
    email: true,
  });
  
  const [showTaxIdentityDialog, setShowTaxIdentityDialog] = React.useState(false);
  const [showPasswordDialog, setShowPasswordDialog] = React.useState(false);
  const [showDeleteDialog, setShowDeleteDialog] = React.useState(false);
  const [showLogoutDialog, setShowLogoutDialog] = React.useState(false);
  const [isLoading, setIsLoading] = React.useState(false);
  const [isLoggingOut, setIsLoggingOut] = React.useState(false);

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
    localStorage.removeItem('creator_token');
    navigate('/creator/login');
  };

  // NOTE: no account-deletion endpoint exists in src/lib/api.ts. This stays a
  // UI-only simulated flow (matches prior behavior) until a DELETE /me (or
  // similar) route ships — do not wire it to a guessed endpoint.
  const handleDeleteAccount = async () => {
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    setIsLoading(false);
    setShowDeleteDialog(false);
    handleLogout();
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
          icon: HelpCircle,
          label: 'Help Center',
          description: 'FAQs and guides',
          onClick: () => {},
        },
        {
          icon: Mail,
          label: 'Contact Support',
          description: 'Get help from our team',
          onClick: () => {},
        },
        {
          icon: FileText,
          label: 'Terms & Privacy',
          description: 'Legal documents',
          onClick: () => {},
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
                <Switch
                  checked={notifications.sms}
                  onCheckedChange={(checked) => setNotifications({ ...notifications, sms: checked })}
                />
              </div>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Mail className="h-4 w-4 text-muted-foreground" />
                  <span className="text-sm">Email Notifications</span>
                </div>
                <Switch
                  checked={notifications.email}
                  onCheckedChange={(checked) => setNotifications({ ...notifications, email: checked })}
                />
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

      {/* Change Password Dialog */}
      <Dialog open={showPasswordDialog} onOpenChange={setShowPasswordDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Change Password</DialogTitle>
            <DialogDescription>
              Enter your current password and a new password
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="currentPassword">Current Password</Label>
              <Input id="currentPassword" type="password" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="newPassword">New Password</Label>
              <Input id="newPassword" type="password" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="confirmPassword">Confirm New Password</Label>
              <Input id="confirmPassword" type="password" />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowPasswordDialog(false)}>
              Cancel
            </Button>
            <Button>Update Password</Button>
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
