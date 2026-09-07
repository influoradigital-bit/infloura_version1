import * as React from 'react';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Progress } from '@/components/ui/progress';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { MetaConnectPathDialog } from '@/components/creator/meta-connect-path-dialog';
import {
  Instagram,
  Youtube,
  CheckCircle2,
  TrendingUp,
  MapPin,
  Edit2,
  Camera,
  IndianRupee,
  ExternalLink,
  Link as LinkIcon,
  Loader2,
  RefreshCw,
  Users,
  X,
} from 'lucide-react';
import { cn, publicProfileLabel } from '@/lib/utils';
import {
  api,
  ApiError,
  type CreatorProfileSelfResponse,
  type CreatorProfilePatchPayload,
  type MetaAuthPath,
} from '@/lib/api';
import { toast } from '@/hooks/use-toast';
import { Switch } from '@/components/ui/switch';

/**
 * CR-92 — same list `creator-onboarding.tsx` uses for its language step. No shared
 * taxonomy module exists to import instead (duplicated here rather than extracting
 * one, which would be a larger refactor than this ticket's scope).
 */
const LANGUAGES = [
  'Hindi', 'English', 'Tamil', 'Telugu', 'Kannada',
  'Malayalam', 'Bengali', 'Marathi', 'Gujarati', 'Punjabi',
];

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

/** Best-effort icon for a platform string — falls back to a generic link icon for anything
 * that isn't Instagram/YouTube (the only two the design has bespoke art for). */
function getPlatformIcon(platform: string) {
  const key = platform.toLowerCase();
  if (key.includes('instagram')) return Instagram;
  if (key.includes('youtube')) return Youtube;
  return LinkIcon;
}

function getPlatformIconWrapClass(platform: string): string {
  const key = platform.toLowerCase();
  if (key.includes('instagram')) return 'bg-gradient-to-br from-purple-500 via-pink-500 to-orange-400';
  if (key.includes('youtube')) return 'bg-red-500';
  return 'bg-muted-foreground/60';
}

/**
 * F-0681 — the set of `CreatorProfilePatchRequest` field names this dialog actually renders an
 * inline error for. It exists to keep the toast HONEST: F-0668 wired six fields and then titled
 * every validation failure "Please fix the highlighted fields", including the seven constrained
 * fields it never wired (categories, languages, contentStyles, avatarUrl, coverImageUrl, phone).
 * `categories` is one Enter keypress away — the add handler applies no cap while the DTO declares
 * `@Size(max = 3)` — so a creator could be told to fix a highlight that did not exist anywhere on
 * screen. That is strictly worse than the single generic toast F-0668 replaced.
 *
 * Anything NOT in this set is named explicitly in the toast body instead of being silently
 * swallowed, so a DTO constraint added later degrades to a readable message rather than a lie.
 */
const HIGHLIGHTED_FIELD_KEYS = new Set([
  'displayName',
  'username',
  'bio',
  'city',
  'rateMin',
  'rateMax',
  'categories',
  'languages',
]);

/** Human labels for fields this dialog cannot highlight, so the toast can still name them. */
const FIELD_LABELS: Record<string, string> = {
  categories: 'Categories',
  languages: 'Languages',
  contentStyles: 'Content styles',
  avatarUrl: 'Avatar image',
  coverImageUrl: 'Cover image',
  phone: 'Phone',
};

/**
 * F-0681 — Spring names a violation on a LIST ELEMENT `categories[0]`, not `categories`
 * (`@Size(max = 80) String` inside `List<String>`). Matching the raw wire name against a UI key
 * would therefore miss every per-item violation and swallow it exactly the way F-0668 swallowed
 * the unwired fields. Collapsing the index maps it back onto the control the creator can see.
 */
function baseFieldName(field: string): string {
  return field.replace(/\[\d+\]/g, '');
}

export default function CreatorProfilePage() {
  const [profile, setProfile] = React.useState<CreatorProfileSelfResponse | null>(null);
  const [isLoading, setIsLoading] = React.useState(true);
  const [loadError, setLoadError] = React.useState<string | null>(null);

  const [showEditDialog, setShowEditDialog] = React.useState(false);
  const [editData, setEditData] = React.useState({
    displayName: '',
    bio: '',
    city: '',
    rateMin: '',
    rateMax: '',
    // CR-92 — the 4 fields the dialog previously had no control for at all.
    username: '',
    categories: [] as string[],
    languages: [] as string[],
    discoverable: true,
  });
  const [categoryDraft, setCategoryDraft] = React.useState('');
  /**
   * F-0668 — `PATCH /me/creator-profile` (`CreatorProfilePatchRequest`, CreatorProfileDtos.java)
   * carries real `@Size`/`@DecimalMin` bean validation (displayName ≤100, bio ≤2000, city ≤100,
   * username ≤500, rateMin/rateMax ≥0), which `GlobalExceptionHandler.handleValidation` turns
   * into a `VALIDATION_ERROR` envelope with a `fields: [{field, message}]` array keyed by these
   * exact record component names. `ApiError` has carried `field`/`fields` through since F-0466,
   * but nothing in the app ever read them — every server-named field error still surfaced as one
   * generic toast, telling a creator something was wrong without saying what. Wired end to end
   * here: this dialog is the one real form on a page this agent can edit whose PATCH endpoint
   * actually emits per-field validation errors (confirmed server-side above), so it becomes the
   * first real consumer instead of new UI invented to justify the wiring.
   */
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string | undefined>>({});
  const openEditDialog = () => {
    setFieldErrors({});
    setShowEditDialog(true);
  };
  const clearFieldError = (field: string) =>
    setFieldErrors((prev) => (prev[field] === undefined ? prev : { ...prev, [field]: undefined }));
  const [isSaving, setIsSaving] = React.useState(false);
  const [syncingPlatform, setSyncingPlatform] = React.useState<string | null>(null);
  const [lastSynced, setLastSynced] = React.useState<Record<string, Date>>({});

  const loadProfile = React.useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const data = await api.creatorProfile.getMe();
      setProfile(data);
      setEditData({
        displayName: data.displayName ?? '',
        bio: data.bio ?? '',
        city: data.city ?? '',
        rateMin: data.rateMin != null ? String(data.rateMin) : '',
        rateMax: data.rateMax != null ? String(data.rateMax) : '',
        username: data.username ?? '',
        categories: data.categories ?? [],
        languages: data.languages ?? [],
        discoverable: data.discoverable,
      });
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : 'Could not load your profile.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    loadProfile();
  }, [loadProfile]);

  /**
   * CR-84 — was a bare `setTimeout` with no API call at all, showing a fabricated "synced"
   * confirmation. Now calls the same real `POST /portfolio/sync` the portfolio editor's own
   * sync button uses — PortfolioService.syncPlatforms() does a genuine Meta Graph API fetch and
   * platform_stats upsert (no longer the documented no-op it used to be), so a success here
   * really did refresh this creator's platform stats, and a failure (e.g. no connected account,
   * an expired token) surfaces as a real error below instead of a silent client-only timer.
   */
  const handleSyncStats = async (platform: string) => {
    setSyncingPlatform(platform);
    try {
      await api.portfolio.syncPlatforms();
      setLastSynced((prev) => ({ ...prev, [platform]: new Date() }));
    } catch (err) {
      toast({
        variant: 'destructive',
        title: `Could not sync ${platform}`,
        description: err instanceof ApiError ? err.message : 'Please try again in a moment.',
      });
    } finally {
      setSyncingPlatform(null);
    }
  };

  /**
   * CR-86 — "Connect More Accounts" had no onClick at all. Mirrors
   * `ConnectedAccounts.handleConnect` (connected-accounts.tsx) exactly rather than
   * mounting that whole settings card here (that's CR-101, a separate High-severity
   * ticket about the card never being mounted anywhere) — this just makes the one
   * button on this page do the real thing instead of nothing.
   */
  /**
   * CR-85 — the camera button had no onClick and no upload endpoint wired to it, despite
   * `avatarUrl` being a plain patchable URL field and `POST /uploads` (generic multipart,
   * see UploadController.java) plus `api.uploads.upload()` already existing and being used
   * elsewhere (creator deliverables). Reuses both rather than building anything new.
   */
  const avatarFileInputRef = React.useRef<HTMLInputElement>(null);
  const [isUploadingAvatar, setIsUploadingAvatar] = React.useState(false);
  const handleAvatarFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-selecting the same file next time
    if (!file) return;
    setIsUploadingAvatar(true);
    try {
      const { url } = await api.uploads.upload(file, 'creator');
      const updated = await api.creatorProfile.patchMe({ avatarUrl: url });
      setProfile(updated);
      toast({ title: 'Profile photo updated' });
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Could not update your photo',
        description: err instanceof ApiError ? err.message : 'Please try again.',
      });
    } finally {
      setIsUploadingAvatar(false);
    }
  };

  const [isConnectingMeta, setIsConnectingMeta] = React.useState(false);
  // B3/F-0390 — mirrors the path-choice pattern in connected-accounts.tsx (T-IGLOGIN-0820):
  // Meta offers two OAuth configurations and only one requires a Facebook Page, so a creator
  // with no Page dead-ended when this always called authorize() with no argument (which
  // defaults to FACEBOOK_LOGIN). Ask before redirecting instead.
  const [showMetaPathChoice, setShowMetaPathChoice] = React.useState(false);
  const handleConnectMoreAccounts = (authPath: MetaAuthPath) => {
    setShowMetaPathChoice(false);
    setIsConnectingMeta(true);
    (async () => {
      try {
        // F-0168 — plain initiator, no return-path of its own; clear any leftover marker from an
        // abandoned Deal Room/Co-pilot connect first, so it can't misroute this one.
        api.metaOAuth.clearConnectReturnTo();
        const { authorizationUrl } = await api.metaOAuth.authorize(authPath);
        window.location.href = authorizationUrl;
      } catch (err) {
        setIsConnectingMeta(false);
        toast({
          variant: 'destructive',
          title: 'Could not start Instagram/Facebook connect',
          description: err instanceof ApiError ? err.message : 'Please try again in a moment.',
        });
      }
    })();
  };

  const formatSyncTime = (date?: Date) => {
    if (!date) return null;
    const diff = Date.now() - date.getTime();
    const mins = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    if (mins < 1) return 'Just now';
    if (mins < 60) return `${mins}m ago`;
    return `${hours}h ago`;
  };

  const handleSave = async () => {
    setIsSaving(true);
    setFieldErrors({});
    try {
      const payload: CreatorProfilePatchPayload = {
        displayName: editData.displayName,
        bio: editData.bio,
        city: editData.city,
        rateMin: editData.rateMin ? Number(editData.rateMin) : undefined,
        rateMax: editData.rateMax ? Number(editData.rateMax) : undefined,
        username: editData.username || undefined,
        categories: editData.categories,
        languages: editData.languages,
        discoverable: editData.discoverable,
      };
      const updated = await api.creatorProfile.patchMe(payload);
      setProfile(updated);
      setShowEditDialog(false);
      toast({ title: 'Profile updated' });
    } catch (err) {
      // F-0668 — `fields` (plural) is what the server actually sends for this endpoint's
      // `@Valid` failures (see the state's doc comment above); map it onto the matching input
      // instead of leaving it to a single generic toast that names no field at all.
      if (err instanceof ApiError && err.fields?.length) {
        // F-0681 — collapse `categories[0]` onto `categories` first, then split the errors into
        // the ones this dialog can highlight and the ones it cannot. The title only promises a
        // highlight when at least one really exists; everything else is named in the body.
        const normalised = err.fields.map((f) => ({
          field: baseFieldName(f.field),
          message: f.message,
        }));
        setFieldErrors(Object.fromEntries(normalised.map((f) => [f.field, f.message])));
        const highlighted = normalised.filter((f) => HIGHLIGHTED_FIELD_KEYS.has(f.field));
        const unhighlighted = normalised.filter((f) => !HIGHLIGHTED_FIELD_KEYS.has(f.field));
        const unhighlightedText = unhighlighted
          .map((f) => `${FIELD_LABELS[f.field] ?? f.field}: ${f.message}`)
          .join(' · ');
        toast({
          title: highlighted.length
            ? 'Please fix the highlighted fields'
            : 'Please fix the following',
          description: unhighlightedText || err.message,
          variant: 'destructive',
        });
      } else {
        toast({
          title: 'Could not save changes',
          description: err instanceof ApiError ? err.message : 'Please try again.',
          variant: 'destructive',
        });
      }
    } finally {
      setIsSaving(false);
    }
  };

  if (isLoading && !profile) {
    return (
      <CreatorLayout>
        <div className="flex items-center justify-center py-24">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      </CreatorLayout>
    );
  }

  if (!profile) {
    return (
      <CreatorLayout>
        <div className="container mx-auto px-4 py-16 max-w-2xl text-center">
          <p className="text-muted-foreground mb-4">{loadError || 'Could not load your profile.'}</p>
          <Button onClick={loadProfile}>Retry</Button>
        </div>
      </CreatorLayout>
    );
  }

  // 2026-07-23 P-1 fix: `profile.username` is now always backfilled server-side (see
  // CreatorProfileService#ensureUsername, called from GET /me/creator-profile) — a Instagram/
  // YouTube handle is NOT the same namespace as an Influora public-page username and must never
  // be substituted for it, and a literal 'creator' placeholder produced a dead /@creator link
  // pointing at nobody's profile. If `username` is somehow still absent (e.g. a stale cached
  // response from before this fix rolled out), don't fabricate a link — hide the banner instead.
  const publicUsername = profile.username || null;

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-2xl">
        {/* Public page promo banner */}
        {publicUsername && (
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
                {publicProfileLabel(publicUsername)} — share it in your Instagram bio
              </p>
            </div>
            <span className="text-xs font-medium text-primary group-hover:underline shrink-0">View →</span>
          </a>
        )}

        {/* Profile Header */}
        <Card className="mb-6">
          <CardContent className="p-6">
            <div className="flex flex-col sm:flex-row items-center sm:items-start gap-6">
              {/* Avatar */}
              <div className="relative">
                <Avatar className="h-24 w-24">
                  <AvatarImage src={profile.avatarUrl ?? undefined} />
                  <AvatarFallback className="bg-gradient-to-br from-primary to-accent text-white text-2xl font-bold">
                    {(profile.displayName || profile.username || 'C').charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <input
                  ref={avatarFileInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={handleAvatarFileSelected}
                />
                <button
                  className="absolute bottom-0 right-0 h-8 w-8 rounded-full bg-primary text-primary-foreground flex items-center justify-center shadow-lg disabled:opacity-60"
                  onClick={() => avatarFileInputRef.current?.click()}
                  disabled={isUploadingAvatar}
                  aria-label="Change profile photo"
                >
                  {isUploadingAvatar ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Camera className="h-4 w-4" />
                  )}
                </button>
                {profile.verified && (
                  <div className="absolute -top-1 -right-1 h-7 w-7 rounded-full bg-blue-500 text-white flex items-center justify-center">
                    <CheckCircle2 className="h-4 w-4" />
                  </div>
                )}
              </div>

              {/* Info */}
              <div className="flex-1 text-center sm:text-left">
                <div className="flex items-center justify-center sm:justify-start gap-2">
                  <h1 className="text-2xl font-bold">{profile.displayName || 'Unnamed creator'}</h1>
                  <Button variant="ghost" size="icon" className="h-8 w-8" onClick={openEditDialog}>
                    <Edit2 className="h-4 w-4" />
                  </Button>
                </div>
                {profile.username && <p className="text-muted-foreground">@{profile.username}</p>}
                {profile.city && (
                  <div className="flex items-center justify-center sm:justify-start gap-2 mt-2 text-sm text-muted-foreground">
                    <MapPin className="h-4 w-4" />
                    <span>{profile.city}</span>
                  </div>
                )}
                <p className="mt-3 text-sm text-balance">
                  {profile.bio || 'No bio yet — tell brands about yourself.'}
                </p>

                {/* Categories */}
                <div className="flex flex-wrap gap-2 mt-4 justify-center sm:justify-start">
                  {profile.categories.length > 0 ? (
                    profile.categories.map((category) => (
                      <Badge key={category} variant="secondary">
                        {category}
                      </Badge>
                    ))
                  ) : (
                    <span className="text-sm text-muted-foreground">No categories added yet.</span>
                  )}
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
            {profile.platforms.length === 0 && (
              <p className="text-sm text-muted-foreground">No social accounts connected yet.</p>
            )}
            {profile.platforms.map((social) => {
              const Icon = getPlatformIcon(social.platform);
              const syncedAt = formatSyncTime(lastSynced[social.platform]);
              return (
                <div key={social.platform} className="p-3 bg-muted/50 rounded-lg space-y-2">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div
                        className={cn(
                          'h-10 w-10 rounded-full flex items-center justify-center',
                          getPlatformIconWrapClass(social.platform),
                        )}
                      >
                        <Icon className="h-5 w-5 text-white" />
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <p className="font-medium">{social.handle}</p>
                          {social.isVerified && <CheckCircle2 className="h-4 w-4 text-blue-500" />}
                        </div>
                        <p className="text-sm text-muted-foreground">
                          {formatNumber(social.followers)} followers
                          {' • '}
                          {/* F-0664 — sibling of the F-0662 fix above: this PER-PLATFORM
                              engagementRate is null until this platform's own stats sync
                              (CreatorDtos.PlatformStatResponse.engagementRate is a nullable
                              BigDecimal). Interpolating it raw rendered a fabricated "null%"/
                              "undefined% engagement" for an unsynced platform instead of the
                              same honest "not available yet" idiom used just below. */}
                          {social.engagementRate != null
                            ? `${social.engagementRate}% engagement`
                            : 'Engagement not available yet'}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        title={`Sync stats from ${social.platform}`}
                        onClick={() => handleSyncStats(social.platform)}
                        disabled={!!syncingPlatform}
                      >
                        <RefreshCw
                          className={`h-4 w-4 ${syncingPlatform === social.platform ? 'animate-spin text-primary' : ''}`}
                        />
                      </Button>
                      {social.profileUrl && (
                        <Button variant="ghost" size="icon" asChild>
                          <a href={social.profileUrl} target="_blank" rel="noreferrer">
                            <ExternalLink className="h-4 w-4" />
                          </a>
                        </Button>
                      )}
                    </div>
                  </div>
                  {syncedAt && <p className="text-xs text-muted-foreground pl-[52px]">Last synced: {syncedAt}</p>}
                </div>
              );
            })}

            <Button
              variant="outline"
              className="w-full"
              onClick={() => setShowMetaPathChoice(true)}
              disabled={isConnectingMeta}
            >
              {isConnectingMeta ? (
                <Loader2 className="h-4 w-4 mr-2 animate-spin" />
              ) : (
                <LinkIcon className="h-4 w-4 mr-2" />
              )}
              Connect More Accounts
            </Button>
          </CardContent>
        </Card>

        {/* B3/F-0390 — asked before the redirect since the two Meta configurations differ in
            whether a Facebook Page is required. T-IGTRUST-0907: this was the FOURTH hand-copy of
            that dialog and had already drifted (its "Yes" option had lost the Facebook icon the
            other three carry). Now the one shared component. */}
        <MetaConnectPathDialog
          open={showMetaPathChoice}
          onOpenChange={setShowMetaPathChoice}
          onChoose={handleConnectMoreAccounts}
          busy={isConnectingMeta}
          changeLaterLabel="from this page"
        />

        {/* Stats — the real fields the backend actually returns; no fabricated
            "total collabs" / "rating" / "on-time %" (not part of CreatorProfileSelfResponse). */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Profile Stats</CardTitle>
            <CardDescription>How brands see you</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="text-center p-4 bg-muted/50 rounded-lg">
                <div className="flex items-center justify-center gap-1">
                  <Users className="h-5 w-5 text-stage-contracted-fg" />
                  <p className="text-3xl font-bold">{formatNumber(profile.totalFollowers)}</p>
                </div>
                <p className="text-sm text-muted-foreground">Total Followers</p>
              </div>
              <div className="text-center p-4 bg-muted/50 rounded-lg">
                <div className="flex items-center justify-center gap-1">
                  <TrendingUp className="h-5 w-5 text-stage-approved-fg" />
                  {/* F-0662 — engagementRate is null until a platform is actually synced
                      (F-0464). A bare "%" with no number would present absent data as a
                      measurement; render the same honest "Not available yet" idiom used
                      elsewhere in this codebase (see creator-verified-metrics.tsx). */}
                  {profile.engagementRate != null ? (
                    <p className="text-3xl font-bold">{profile.engagementRate}%</p>
                  ) : (
                    <p className="text-sm font-normal text-muted-foreground">Not available yet</p>
                  )}
                </div>
                <p className="text-sm text-muted-foreground">Engagement Rate</p>
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between text-sm mb-1.5">
                <span className="text-muted-foreground">Profile Completeness</span>
                <span className="font-medium">{profile.profileCompleteness}%</span>
              </div>
              <Progress value={profile.profileCompleteness} className="h-1.5" />
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
            {profile.rateMin != null && profile.rateMax != null ? (
              <div className="flex items-center justify-between p-4 bg-stage-approved rounded-lg">
                <div className="flex items-center gap-2">
                  <IndianRupee className="h-5 w-5 text-stage-approved-fg" />
                  <span className="font-medium text-green-800">Rate Range</span>
                </div>
                <span className="text-stage-approved-fg font-semibold">
                  {formatINR(profile.rateMin)} - {formatINR(profile.rateMax)}
                </span>
              </div>
            ) : (
              <div className="flex items-center justify-between gap-3 p-4 bg-muted/50 rounded-lg">
                <p className="text-sm text-muted-foreground">
                  Set your rate range so brands know your budget.
                </p>
                <Button size="sm" variant="outline" onClick={openEditDialog}>
                  Add rates
                </Button>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Languages */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Languages</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {profile.languages.length > 0 ? (
                profile.languages.map((language) => (
                  <Badge key={language} variant="outline">
                    {language}
                  </Badge>
                ))
              ) : (
                <span className="text-sm text-muted-foreground">No languages added yet.</span>
              )}
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
                onChange={(e) => {
                  setEditData({ ...editData, displayName: e.target.value });
                  clearFieldError('displayName');
                }}
                aria-invalid={!!fieldErrors.displayName}
              />
              {fieldErrors.displayName && (
                <p className="text-xs text-destructive-foreground">{fieldErrors.displayName}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="bio">Bio</Label>
              <Textarea
                id="bio"
                value={editData.bio}
                onChange={(e) => {
                  setEditData({ ...editData, bio: e.target.value });
                  clearFieldError('bio');
                }}
                rows={4}
                aria-invalid={!!fieldErrors.bio}
              />
              {fieldErrors.bio && <p className="text-xs text-destructive-foreground">{fieldErrors.bio}</p>}
            </div>

            <div className="space-y-2">
              <Label htmlFor="city">City</Label>
              <Input
                id="city"
                value={editData.city}
                onChange={(e) => {
                  setEditData({ ...editData, city: e.target.value });
                  clearFieldError('city');
                }}
                aria-invalid={!!fieldErrors.city}
              />
              {fieldErrors.city && <p className="text-xs text-destructive-foreground">{fieldErrors.city}</p>}
            </div>

            {/* CR-92 — username, categories, languages, discoverable were all patchable
                (CreatorProfilePatchPayload) but had no control anywhere in this dialog. */}
            <div className="space-y-2">
              <Label htmlFor="username">Public page username</Label>
              <Input
                id="username"
                value={editData.username}
                onChange={(e) => {
                  setEditData({ ...editData, username: e.target.value.trim().toLowerCase() });
                  clearFieldError('username');
                }}
                placeholder="e.g. priya_creates"
                aria-invalid={!!fieldErrors.username}
              />
              {fieldErrors.username ? (
                <p className="text-xs text-destructive-foreground">{fieldErrors.username}</p>
              ) : (
                <p className="text-xs text-muted-foreground">
                  Your public page is influora.com/@{editData.username || 'username'}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="categories">Categories</Label>
              <div className="flex flex-wrap gap-2">
                {editData.categories.map((category) => (
                  <Badge key={category} variant="secondary" className="gap-1">
                    {category}
                    <button
                      type="button"
                      onClick={() =>
                        setEditData({
                          ...editData,
                          categories: editData.categories.filter((c) => c !== category),
                        })
                      }
                      aria-label={`Remove ${category}`}
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </Badge>
                ))}
              </div>
              <Input
                id="categories"
                value={categoryDraft}
                onChange={(e) => setCategoryDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key !== 'Enter' && e.key !== ',') return;
                  e.preventDefault();
                  const value = categoryDraft.trim();
                  if (value && !editData.categories.includes(value)) {
                    setEditData({ ...editData, categories: [...editData.categories, value] });
                  }
                  setCategoryDraft('');
                }}
                placeholder="Type a category and press Enter"
                aria-invalid={!!fieldErrors.categories}
              />
              {fieldErrors.categories && (
                <p className="text-xs text-destructive-foreground">{fieldErrors.categories}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label>Languages</Label>
              <div className="flex flex-wrap gap-2">
                {LANGUAGES.map((language) => {
                  const selected = editData.languages.includes(language);
                  return (
                    <Badge
                      key={language}
                      variant={selected ? 'default' : 'outline'}
                      className="cursor-pointer select-none"
                      onClick={() =>
                        setEditData({
                          ...editData,
                          languages: selected
                            ? editData.languages.filter((l) => l !== language)
                            : [...editData.languages, language],
                        })
                      }
                    >
                      {language}
                    </Badge>
                  );
                })}
              </div>
              {fieldErrors.languages && (
                <p className="text-xs text-destructive-foreground">{fieldErrors.languages}</p>
              )}
            </div>

            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <Label htmlFor="discoverable">Discoverable by brands</Label>
                <p className="text-xs text-muted-foreground">
                  Off hides your profile from brand search and discovery.
                </p>
              </div>
              <Switch
                id="discoverable"
                checked={editData.discoverable}
                onCheckedChange={(checked) => setEditData({ ...editData, discoverable: checked })}
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
                    onChange={(e) => {
                      setEditData({ ...editData, rateMin: e.target.value });
                      clearFieldError('rateMin');
                    }}
                    className="pl-9"
                    aria-invalid={!!fieldErrors.rateMin}
                  />
                </div>
                {fieldErrors.rateMin && (
                  <p className="text-xs text-destructive-foreground">{fieldErrors.rateMin}</p>
                )}
              </div>
              <div className="space-y-2">
                <Label htmlFor="rateMax">Max Rate</Label>
                <div className="relative">
                  <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                  <Input
                    id="rateMax"
                    type="number"
                    value={editData.rateMax}
                    onChange={(e) => {
                      setEditData({ ...editData, rateMax: e.target.value });
                      clearFieldError('rateMax');
                    }}
                    className="pl-9"
                    aria-invalid={!!fieldErrors.rateMax}
                  />
                </div>
                {fieldErrors.rateMax && (
                  <p className="text-xs text-destructive-foreground">{fieldErrors.rateMax}</p>
                )}
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
