import * as React from 'react';
import { Settings, Bell, Lock, Users, CreditCard, LogOut, Save, ToggleRight, ToggleLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';

export default function BrandSettingsPage() {
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

  const handleSave = () => {
    alert('Settings saved successfully!');
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
            <TabsTrigger value="payments" className="gap-2">
              <CreditCard className="h-4 w-4" />
              <span className="hidden sm:inline">Payments</span>
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
              <div className="space-y-4">
                <div>
                  <Label htmlFor="workspace-name">Workspace Name</Label>
                  <Input
                    id="workspace-name"
                    value={settings.workspaceName}
                    onChange={(e) => setSettings({ ...settings, workspaceName: e.target.value })}
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
                    className="mt-2"
                  />
                </div>
                <div>
                  <Label htmlFor="phone">Phone</Label>
                  <Input
                    id="phone"
                    value={settings.phone}
                    onChange={(e) => setSettings({ ...settings, phone: e.target.value })}
                    className="mt-2"
                  />
                </div>
                <div>
                  <Label htmlFor="website">Website</Label>
                  <Input
                    id="website"
                    value={settings.website}
                    onChange={(e) => setSettings({ ...settings, website: e.target.value })}
                    className="mt-2"
                  />
                </div>
                <Button onClick={handleSave} className="gap-2">
                  <Save className="h-4 w-4" />
                  Save Changes
                </Button>
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
                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Email Notifications</p>
                    <p className="text-xs text-muted-foreground">Receive updates via email</p>
                  </div>
                  <Switch
                    checked={settings.emailNotifications}
                    onCheckedChange={(e) => setSettings({ ...settings, emailNotifications: e })}
                  />
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Push Notifications</p>
                    <p className="text-xs text-muted-foreground">Browser notifications</p>
                  </div>
                  <Switch
                    checked={settings.pushNotifications}
                    onCheckedChange={(e) => setSettings({ ...settings, pushNotifications: e })}
                  />
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Campaign Alerts</p>
                    <p className="text-xs text-muted-foreground">Alerts on new bids and offers</p>
                  </div>
                  <Switch
                    checked={settings.campaignAlerts}
                    onCheckedChange={(e) => setSettings({ ...settings, campaignAlerts: e })}
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
                  />
                </div>

                <Button onClick={handleSave} className="w-full gap-2">
                  <Save className="h-4 w-4" />
                  Save Preferences
                </Button>
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

                <Button onClick={handleSave} className="w-full gap-2">
                  <Save className="h-4 w-4" />
                  Save Settings
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
                <Button variant="outline" className="w-full text-destructive hover:text-destructive">
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
