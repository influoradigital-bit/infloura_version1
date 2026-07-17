import * as React from 'react';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Separator } from '@/components/ui/separator';
import { Progress } from '@/components/ui/progress';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Instagram,
  Youtube,
  CheckCircle2,
  Star,
  TrendingUp,
  Calendar,
  MapPin,
  Edit2,
  Camera,
  Shield,
  Award,
  Clock,
  Briefcase,
  IndianRupee,
  ExternalLink,
  Link as LinkIcon,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import { cn } from '@/lib/utils';

// Mock creator profile data
const mockProfile = {
  name: 'Priya Sharma',
  displayName: 'Priya Creates',
  email: 'priya@creator.com',
  phone: '+91 98765 43210',
  bio: 'Fashion & lifestyle content creator. Passionate about sustainable fashion and mindful living. Love helping brands tell their stories through creative content.',
  city: 'Mumbai',
  avatar: '',
  verified: true,
  verifiedSince: '2025-01-15',

  // Social handles
  socials: {
    instagram: {
      handle: '@priya_creates',
      followers: 125000,
      engagement: 4.2,
      verified: true,
      lastSynced: new Date(Date.now() - 1000 * 60 * 60 * 3), // 3 hours ago
    },
    youtube: {
      handle: 'Priya Creates',
      subscribers: 50000,
      avgViews: 25000,
      verified: true,
      lastSynced: new Date(Date.now() - 1000 * 60 * 60 * 5), // 5 hours ago
    },
  },
  
  // Verticals and rates
  verticals: ['Fashion & Lifestyle', 'Beauty & Skincare', 'Travel & Adventure'],
  languages: ['Hindi', 'English', 'Marathi'],
  rateRange: { min: 25000, max: 75000 },
  
  // Stats
  stats: {
    totalCollabs: 45,
    completedOnTime: 43,
    avgRating: 4.8,
    totalEarnings: 425000,
    responseRate: 95,
    repeatBrands: 12,
  },
  
  // Badges
  badges: [
    { id: 'b1', title: 'Top Creator', description: 'Top 5% engagement rate', icon: Star },
    { id: 'b2', title: 'Fast Responder', description: '< 2 hour response time', icon: Clock },
    { id: 'b3', title: 'On-Time Delivery', description: '95% OTD rate', icon: CheckCircle2 },
    { id: 'b4', title: 'Brand Favorite', description: '12 repeat collaborations', icon: Award },
  ],
};

function formatINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatNumber(num: number): string {
  if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
  if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
  return num.toString();
}

export default function CreatorProfilePage() {
  const [showEditDialog, setShowEditDialog] = React.useState(false);
  const [editData, setEditData] = React.useState({
    displayName: mockProfile.displayName,
    bio: mockProfile.bio,
    city: mockProfile.city,
    rateMin: mockProfile.rateRange.min.toString(),
    rateMax: mockProfile.rateRange.max.toString(),
  });
  const [isSaving, setIsSaving] = React.useState(false);
  const [syncingPlatform, setSyncingPlatform] = React.useState<'instagram' | 'youtube' | null>(null);
  const [lastSynced, setLastSynced] = React.useState({
    instagram: mockProfile.socials.instagram.lastSynced,
    youtube: mockProfile.socials.youtube.lastSynced,
  });

  const handleSyncStats = async (platform: 'instagram' | 'youtube') => {
    setSyncingPlatform(platform);
    await new Promise((resolve) => setTimeout(resolve, 1800));
    setLastSynced((prev) => ({ ...prev, [platform]: new Date() }));
    setSyncingPlatform(null);
  };

  const formatSyncTime = (date: Date) => {
    const diff = Date.now() - date.getTime();
    const mins = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    if (mins < 1) return 'Just now';
    if (mins < 60) return `${mins}m ago`;
    return `${hours}h ago`;
  };

  const handleSave = async () => {
    setIsSaving(true);
    await new Promise((resolve) => setTimeout(resolve, 1500));
    setIsSaving(false);
    setShowEditDialog(false);
  };

  const onTimeRate = (mockProfile.stats.completedOnTime / mockProfile.stats.totalCollabs * 100).toFixed(0);

  const publicUsername = mockProfile.socials.instagram.handle.replace(/[@_]/g, '') || 'priyacreates';

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-2xl">
        {/* Public page promo banner */}
        <a
          href={`/@${publicUsername}`}
          target="_blank"
          rel="noreferrer"
          className="mb-4 flex items-center gap-3 rounded-lg border border-primary/20 bg-primary/5 px-4 py-3 hover:bg-primary/10 transition-colors group"
        >
          <div className="h-9 w-9 rounded-lg bg-primary/15 text-primary flex items-center justify-center shrink-0">
            <ExternalLink className="h-4 w-4" />
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium">Your public page is live</p>
            <p className="text-xs text-muted-foreground truncate">
              influora.com/@{publicUsername} — share it in your Instagram bio
            </p>
          </div>
          <span className="text-xs font-medium text-primary group-hover:underline shrink-0">View →</span>
        </a>

        {/* Profile Header */}
        <Card className="mb-6">
          <CardContent className="p-6">
            <div className="flex flex-col sm:flex-row items-center sm:items-start gap-6">
              {/* Avatar */}
              <div className="relative">
                <Avatar className="h-24 w-24">
                  <AvatarImage src={mockProfile.avatar} />
                  <AvatarFallback className="bg-gradient-to-br from-primary to-accent text-white text-2xl font-bold">
                    {mockProfile.name.charAt(0)}
                  </AvatarFallback>
                </Avatar>
                <button className="absolute bottom-0 right-0 h-8 w-8 rounded-full bg-primary text-primary-foreground flex items-center justify-center shadow-lg">
                  <Camera className="h-4 w-4" />
                </button>
                {mockProfile.verified && (
                  <div className="absolute -top-1 -right-1 h-7 w-7 rounded-full bg-blue-500 text-white flex items-center justify-center">
                    <CheckCircle2 className="h-4 w-4" />
                  </div>
                )}
              </div>

              {/* Info */}
              <div className="flex-1 text-center sm:text-left">
                <div className="flex items-center justify-center sm:justify-start gap-2">
                  <h1 className="text-2xl font-bold">{mockProfile.displayName}</h1>
                  <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => setShowEditDialog(true)}>
                    <Edit2 className="h-4 w-4" />
                  </Button>
                </div>
                <p className="text-muted-foreground">{mockProfile.name}</p>
                <div className="flex items-center justify-center sm:justify-start gap-2 mt-2 text-sm text-muted-foreground">
                  <MapPin className="h-4 w-4" />
                  <span>{mockProfile.city}</span>
                </div>
                <p className="mt-3 text-sm text-balance">{mockProfile.bio}</p>

                {/* Verticals */}
                <div className="flex flex-wrap gap-2 mt-4 justify-center sm:justify-start">
                  {mockProfile.verticals.map((vertical) => (
                    <Badge key={vertical} variant="secondary">
                      {vertical}
                    </Badge>
                  ))}
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Social Handles */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Connected Accounts</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {/* Instagram */}
            <div className="p-3 bg-muted/50 rounded-lg space-y-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="h-10 w-10 rounded-full bg-gradient-to-br from-purple-500 via-pink-500 to-orange-400 flex items-center justify-center">
                    <Instagram className="h-5 w-5 text-white" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <p className="font-medium">{mockProfile.socials.instagram.handle}</p>
                      {mockProfile.socials.instagram.verified && (
                        <CheckCircle2 className="h-4 w-4 text-blue-500" />
                      )}
                    </div>
                    <p className="text-sm text-muted-foreground">
                      {formatNumber(mockProfile.socials.instagram.followers)} followers
                      {' • '}
                      {mockProfile.socials.instagram.engagement}% engagement
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Sync stats from Instagram"
                    onClick={() => handleSyncStats('instagram')}
                    disabled={!!syncingPlatform}
                  >
                    <RefreshCw className={`h-4 w-4 ${syncingPlatform === 'instagram' ? 'animate-spin text-primary' : ''}`} />
                  </Button>
                  <Button variant="ghost" size="icon">
                    <ExternalLink className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <p className="text-xs text-muted-foreground pl-[52px]">
                Last synced: {formatSyncTime(lastSynced.instagram)}
              </p>
            </div>

            {/* YouTube */}
            <div className="p-3 bg-muted/50 rounded-lg space-y-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="h-10 w-10 rounded-full bg-red-500 flex items-center justify-center">
                    <Youtube className="h-5 w-5 text-white" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <p className="font-medium">{mockProfile.socials.youtube.handle}</p>
                      {mockProfile.socials.youtube.verified && (
                        <CheckCircle2 className="h-4 w-4 text-blue-500" />
                      )}
                    </div>
                    <p className="text-sm text-muted-foreground">
                      {formatNumber(mockProfile.socials.youtube.subscribers)} subscribers
                      {' • '}
                      {formatNumber(mockProfile.socials.youtube.avgViews)} avg views
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Sync stats from YouTube"
                    onClick={() => handleSyncStats('youtube')}
                    disabled={!!syncingPlatform}
                  >
                    <RefreshCw className={`h-4 w-4 ${syncingPlatform === 'youtube' ? 'animate-spin text-primary' : ''}`} />
                  </Button>
                  <Button variant="ghost" size="icon">
                    <ExternalLink className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <p className="text-xs text-muted-foreground pl-[52px]">
                Last synced: {formatSyncTime(lastSynced.youtube)}
              </p>
            </div>

            <Button variant="outline" className="w-full">
              <LinkIcon className="h-4 w-4 mr-2" />
              Connect More Accounts
            </Button>
          </CardContent>
        </Card>

        {/* Stats */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Performance Stats</CardTitle>
            <CardDescription>Your track record with brands</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-4">
              <div className="text-center p-4 bg-muted/50 rounded-lg">
                <p className="text-3xl font-bold text-stage-contracted-fg">{mockProfile.stats.totalCollabs}</p>
                <p className="text-sm text-muted-foreground">Total Collabs</p>
              </div>
              <div className="text-center p-4 bg-muted/50 rounded-lg">
                <div className="flex items-center justify-center gap-1">
                  <Star className="h-5 w-5 text-amber-500 fill-amber-500" />
                  <p className="text-3xl font-bold">{mockProfile.stats.avgRating}</p>
                </div>
                <p className="text-sm text-muted-foreground">Avg Rating</p>
              </div>
              <div className="text-center p-4 bg-muted/50 rounded-lg">
                <p className="text-3xl font-bold text-stage-approved-fg">{onTimeRate}%</p>
                <p className="text-sm text-muted-foreground">On-Time Delivery</p>
              </div>
              <div className="text-center p-4 bg-muted/50 rounded-lg">
                <p className="text-3xl font-bold text-stage-outreach-fg">{mockProfile.stats.responseRate}%</p>
                <p className="text-sm text-muted-foreground">Response Rate</p>
              </div>
            </div>

            {/* Repeat Brands */}
            <div className="mt-4 p-4 bg-stage-contracted rounded-lg">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Award className="h-5 w-5 text-stage-contracted-fg" />
                  <span className="font-medium text-violet-800">Brand Loyalty</span>
                </div>
                <span className="text-stage-contracted-fg font-semibold">
                  {mockProfile.stats.repeatBrands} repeat brands
                </span>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Rate Card */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Rate Card</CardTitle>
            <CardDescription>Your expected fee per collaboration</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-center justify-between p-4 bg-stage-approved rounded-lg">
              <div className="flex items-center gap-2">
                <IndianRupee className="h-5 w-5 text-stage-approved-fg" />
                <span className="font-medium text-green-800">Rate Range</span>
              </div>
              <span className="text-stage-approved-fg font-semibold">
                {formatINR(mockProfile.rateRange.min)} - {formatINR(mockProfile.rateRange.max)}
              </span>
            </div>
          </CardContent>
        </Card>

        {/* Badges */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Achievements</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-3">
              {mockProfile.badges.map((badge) => {
                const Icon = badge.icon;
                return (
                  <div key={badge.id} className="flex items-start gap-3 p-3 bg-muted/50 rounded-lg">
                    <div className="h-10 w-10 rounded-full bg-amber-100 flex items-center justify-center flex-shrink-0">
                      <Icon className="h-5 w-5 text-stage-negotiating-fg" />
                    </div>
                    <div>
                      <p className="font-medium text-sm">{badge.title}</p>
                      <p className="text-xs text-muted-foreground">{badge.description}</p>
                    </div>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>

        {/* Languages */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Languages</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {mockProfile.languages.map((language) => (
                <Badge key={language} variant="outline">
                  {language}
                </Badge>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Edit Profile Dialog */}
      <Dialog open={showEditDialog} onOpenChange={setShowEditDialog}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Edit Profile</DialogTitle>
            <DialogDescription>
              Update your creator profile information
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="displayName">Display Name</Label>
              <Input
                id="displayName"
                value={editData.displayName}
                onChange={(e) => setEditData({ ...editData, displayName: e.target.value })}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="bio">Bio</Label>
              <Textarea
                id="bio"
                value={editData.bio}
                onChange={(e) => setEditData({ ...editData, bio: e.target.value })}
                rows={4}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="city">City</Label>
              <Input
                id="city"
                value={editData.city}
                onChange={(e) => setEditData({ ...editData, city: e.target.value })}
              />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="rateMin">Min Rate</Label>
                <div className="relative">
                  <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                  <Input
                    id="rateMin"
                    type="number"
                    value={editData.rateMin}
                    onChange={(e) => setEditData({ ...editData, rateMin: e.target.value })}
                    className="pl-9"
                  />
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="rateMax">Max Rate</Label>
                <div className="relative">
                  <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                  <Input
                    id="rateMax"
                    type="number"
                    value={editData.rateMax}
                    onChange={(e) => setEditData({ ...editData, rateMax: e.target.value })}
                    className="pl-9"
                  />
                </div>
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setShowEditDialog(false)}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={isSaving}>
              {isSaving ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Saving...
                </>
              ) : (
                'Save Changes'
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </CreatorLayout>
  );
}
