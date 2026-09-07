import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  ArrowLeft,
  Instagram,
  Youtube,
  CheckCircle2,
  User,
  Loader2,
  Link as LinkIcon,
  Sparkles,
  Briefcase,
  Wallet as WalletIcon,
  IndianRupee,
  Search,
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Textarea } from '@/components/ui/textarea';
import { MetaConnectPathDialog } from '@/components/creator/meta-connect-path-dialog';
import { cn } from '@/lib/utils';
import { api, ApiError, type MetaAuthPath } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { normalizePhone, isValidPhone, filterPhoneInput } from '@/lib/phone';

/**
 * Creator onboarding — reduced from 5 steps to 3.
 *   1. Connect socials   → api.onboarding.connectCreatorSocial
 *   2. Build profile     → api.onboarding.saveCreatorProfile
 *   3. You're in (tour)  → api.onboarding.completeCreator
 *
 * Deferred (collected just-in-time):
 *   - PAN/Aadhaar KYC  → Settings › Identity Verification, and required before the
 *                        first withdrawal  (api.onboarding.submitCreatorKyc,
 *                        captured by KycIdentityForm)
 *   - Payout method    → first withdrawal, via the wallet
 *                        (GET/POST /wallet/payout-methods in creator-wallet.tsx)
 */

const STEPS = [
  { id: 1, title: 'Connect Socials', icon: LinkIcon },
  { id: 2, title: 'Build Profile', icon: User },
  { id: 3, title: "You're in", icon: Sparkles },
];

/**
 * Set immediately before we hand the browser to Meta's OAuth dialog. The shared
 * callback page (`creator-meta-callback.tsx`) reads it to route the creator back
 * *here* instead of to Settings, then clears it. See CR-120.
 */
const META_ONBOARDING_RESUME_KEY = 'creator_onboarding_meta_resume';

const CONTENT_VERTICALS = [
  'Fashion & Lifestyle',
  'Beauty & Skincare',
  'Fitness & Health',
  'Food & Cooking',
  'Tech & Gaming',
  'Travel & Adventure',
  'Education & Learning',
  'Finance & Business',
  'Entertainment & Comedy',
  'Parenting & Family',
  'Art & Photography',
  'Music & Dance',
];

const LANGUAGES = [
  'Hindi', 'English', 'Tamil', 'Telugu', 'Kannada',
  'Malayalam', 'Bengali', 'Marathi', 'Gujarati', 'Punjabi',
];

type Social = 'instagram' | 'youtube';

export default function CreatorOnboardingPage() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [currentStep, setCurrentStep] = React.useState(1);
  const [isLoading, setIsLoading] = React.useState(false);

  // Step 1
  const [connectedSocials, setConnectedSocials] = React.useState<Social[]>([]);
  const [connectingPlatform, setConnectingPlatform] = React.useState<Social | null>(null);
  // T-IGTRUST-0907 — mirrors connected-accounts.tsx: the Facebook-Page question has to be
  // asked BEFORE the redirect, because the two Meta configurations differ in whether a Page
  // is required and the choice cannot be changed once Meta's dialog has loaded.
  const [showPathChoice, setShowPathChoice] = React.useState(false);

  // Step 2
  const [profileData, setProfileData] = React.useState({
    displayName: '',
    bio: '',
    verticals: [] as string[],
    languages: [] as string[],
    city: '',
    phone: '',
    rateMin: '',
    rateMax: '',
  });
  // PHONE-0904 — mirrors creator-settings.tsx's phone dialog: optional field, server-side
  // 409 (duplicate phone) surfaces here, field-adjacent, not as a generic toast.
  const [phoneError, setPhoneError] = React.useState<string | null>(null);

  const progress = (currentStep / STEPS.length) * 100;

  // Returning from the real Meta OAuth redirect: the callback page persisted the
  // connection state before routing us back here. Reflect it so Step 1 shows the
  // account as connected and `canProceed()` passes. (CR-120)
  React.useEffect(() => {
    if (api.metaOAuth.getLocalConnectionState().connected) {
      setConnectedSocials((prev) =>
        prev.includes('instagram') ? prev : [...prev, 'instagram'],
      );
    }
  }, []);

  // Instagram uses the *real* Meta OAuth flow — a full-page redirect to Meta that
  // returns to /creator/settings/meta/callback. We drop a resume marker first so
  // that callback routes back into onboarding. (CR-120: the old path posted a
  // literal 'mock_oauth_code' that the live backend rejected, hard-blocking every
  // creator at Step 1.)
  //
  // T-IGTRUST-0907 — `authPath` is now REQUIRED here rather than omitted. Omitting it
  // defaults the backend to FACEBOOK_LOGIN (api.ts metaOAuth.authorize javadoc), whose
  // dialog demands an Instagram professional account already linked to a Facebook Page
  // the creator can administer. Settings has asked this question before the redirect
  // since T-IGLOGIN-0820 (connected-accounts.tsx) precisely because a creator without a
  // Page dead-ends inside Meta's own UI with nothing explaining why; onboarding — the
  // one place EVERY creator passes through — never got that fix and silently sent all
  // of them down the Page-required path.
  const handleConnectInstagram = async (authPath: MetaAuthPath) => {
    setShowPathChoice(false);
    setConnectingPlatform('instagram');
    try {
      localStorage.setItem(META_ONBOARDING_RESUME_KEY, '1');
      const { authorizationUrl } = await api.metaOAuth.authorize(authPath);
      window.location.assign(authorizationUrl);
    } catch (err) {
      localStorage.removeItem(META_ONBOARDING_RESUME_KEY);
      setConnectingPlatform(null);
      toast({
        title: 'Couldn’t start Instagram connection',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    }
  };

  const handleConnectSocial = (platform: Social) => {
    if (platform === 'instagram') {
      setShowPathChoice(true);
      return;
    }
    // YouTube has no OAuth backend yet. Never send a fabricated code to the live
    // API (that was the CR-120 defect) — tell the truth and let the creator move on.
    toast({
      title: 'YouTube connection is coming soon',
      description: 'You can connect YouTube later from Settings once it’s available.',
    });
  };

  const handleVerticalToggle = (vertical: string) => {
    setProfileData((prev) => {
      if (prev.verticals.includes(vertical)) {
        return { ...prev, verticals: prev.verticals.filter((v) => v !== vertical) };
      }
      if (prev.verticals.length >= 3) return prev;
      return { ...prev, verticals: [...prev.verticals, vertical] };
    });
  };

  const handleLanguageToggle = (language: string) => {
    setProfileData((prev) =>
      prev.languages.includes(language)
        ? { ...prev, languages: prev.languages.filter((l) => l !== language) }
        : { ...prev, languages: [...prev.languages, language] },
    );
  };

  const canProceed = () => {
    switch (currentStep) {
      case 1:
        return connectedSocials.length > 0;
      case 2: {
        // Phone is Optional here (matches creator Settings' phone field, PHONE-0829 P1) —
        // but if the creator did type something, it must be a valid 10-digit Indian mobile
        // before Continue is allowed. Normalize first (PHONE-0904) so a pasted +91/leading-0
        // number that the server would accept isn't blocked client-side.
        const normalizedPhone = normalizePhone(profileData.phone);
        const phoneValid = normalizedPhone.length === 0 || isValidPhone(normalizedPhone);
        return (
          profileData.displayName.trim().length > 0 &&
          profileData.verticals.length > 0 &&
          profileData.rateMin.trim().length > 0 &&
          phoneValid
        );
      }
      case 3:
        return true;
      default:
        return false;
    }
  };

  const handleSaveProfileAndNext = async () => {
    setIsLoading(true);
    setPhoneError(null);
    try {
      const normalizedPhone = normalizePhone(profileData.phone);
      await api.onboarding.saveCreatorProfile({
        displayName: profileData.displayName,
        bio: profileData.bio,
        verticals: profileData.verticals,
        languages: profileData.languages,
        city: profileData.city,
        phone: normalizedPhone || undefined,
        rateMin: Number(profileData.rateMin) || 0,
        rateMax: Number(profileData.rateMax) || 0,
      });
      setCurrentStep(3);
      window.scrollTo(0, 0);
    } catch (err) {
      // 409 = duplicate phone (server-enforced uniqueness) — shown inline, next to the
      // field, not as a generic toast. Mirrors creator-settings.tsx's phone dialog. Must
      // NOT advance to step 3: falling through to the generic toast below would still
      // leave the user on step 2, but this gives them an actionable, field-adjacent reason.
      //
      // PHONE-0904 Q6 fix — branch on the machine-readable `code`, not the bare HTTP status.
      // saveCreatorProfile shares this 409 status with other ApiExceptions the profile-patch
      // path can throw; keying on `err.code === 'PHONE_ALREADY_EXISTS'` is what stops any of
      // those from ever being mislabeled as a phone conflict.
      if (err instanceof ApiError && err.code === 'PHONE_ALREADY_EXISTS') {
        setPhoneError('This mobile number is already registered');
        return;
      }
      // Was try/finally with NO catch — a save failure was an unhandled rejection
      // and the user stayed on step 2 with no explanation.
      toast({
        title: 'Couldn’t save your profile',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsLoading(false);
    }
  };

  const handleNext = () => {
    if (currentStep === 2) {
      void handleSaveProfileAndNext();
      return;
    }
    if (currentStep < STEPS.length) {
      setCurrentStep(currentStep + 1);
      window.scrollTo(0, 0);
    }
  };

  const handlePrev = () => {
    if (currentStep > 1) {
      setCurrentStep(currentStep - 1);
      window.scrollTo(0, 0);
    }
  };

  /** F-0341 (creator mirror) — destination is an argument so the terminal step's "Check your
   *  Deals" card can actually go there instead of being an inert div beside the one real button. */
  const handleComplete = async (destination = '/creator/dashboard') => {
    setIsLoading(true);
    try {
      await api.onboarding.completeCreator();
      localStorage.setItem('creator_onboarding_completed', 'true');
      // F-0275 — was '/creator/deals', which skips creator-dashboard.tsx's zero-state
      // entirely (its CTAs — Explore campaigns, Complete profile — only fire for
      // isEmptyCreator === true, i.e. deals.length === 0, which is exactly the state a
      // freshly-onboarded creator is in). Matches the register-page destination below, and
      // stays the default for every caller that does not name one.
      navigate(destination);
    } catch (err) {
      // Was try/finally with NO catch — the final "Go to Deals" step failed silently.
      toast({
        title: 'Couldn’t finish setting up your account',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <header className="sticky top-0 z-10 border-b bg-background/95 backdrop-blur">
        <div className="container mx-auto px-4 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="h-8 w-8 rounded-lg bg-gradient-to-br from-primary to-accent flex items-center justify-center">
                <span className="text-white font-bold text-sm">C</span>
              </div>
              <span className="font-semibold">Creator Onboarding</span>
            </div>
            <div className="text-sm text-muted-foreground">
              Step {currentStep} of {STEPS.length}
            </div>
          </div>
          <Progress value={progress} className="mt-3 h-1" />
        </div>
      </header>

      {/* Step indicators */}
      <div className="container mx-auto px-4 py-4">
        <div className="flex items-center justify-center gap-2">
          {STEPS.map((step) => {
            const Icon = step.icon;
            const isActive = currentStep === step.id;
            const isCompleted = currentStep > step.id;
            return (
              <div
                key={step.id}
                className={cn(
                  'flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium whitespace-nowrap transition-colors',
                  isActive && 'bg-primary text-primary-foreground',
                  isCompleted && 'bg-stage-approved text-stage-approved-fg',
                  !isActive && !isCompleted && 'bg-muted text-muted-foreground',
                )}
              >
                {isCompleted ? (
                  <CheckCircle2 className="h-3.5 w-3.5" />
                ) : (
                  <Icon className="h-3.5 w-3.5" />
                )}
                <span className="hidden sm:inline">{step.title}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Main */}
      <main className="container mx-auto px-4 py-6 max-w-lg">
        {currentStep === 1 && (
          <ConnectSocialsStep
            connectedSocials={connectedSocials}
            connectingPlatform={connectingPlatform}
            onConnect={handleConnectSocial}
          />
        )}

        {currentStep === 2 && (
          <BuildProfileStep
            data={profileData}
            onUpdate={setProfileData}
            onToggleVertical={handleVerticalToggle}
            onToggleLanguage={handleLanguageToggle}
            phoneError={phoneError}
            onPhoneErrorClear={() => setPhoneError(null)}
          />
        )}

        {currentStep === 3 && (
          <YoureInStep onComplete={handleComplete} isLoading={isLoading} />
        )}

        {/* Footer */}
        {currentStep < 3 && (
          <div className="mt-8 space-y-3">
            <div className="flex items-center gap-3">
              {currentStep > 1 && (
                <Button variant="outline" onClick={handlePrev} className="gap-1.5">
                  <ArrowLeft className="h-4 w-4" />
                  Back
                </Button>
              )}
              <div className="flex-1" />
              <Button
                onClick={handleNext}
                disabled={!canProceed() || isLoading}
                className="gap-1.5"
              >
                {isLoading ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Saving…
                  </>
                ) : (
                  <>
                    Continue
                    <ArrowRight className="h-4 w-4" />
                  </>
                )}
              </Button>
            </div>

            {/* CR-120: Step 1 must never be a hard wall. A creator who can't connect
                a Business Instagram (personal account, OAuth down, etc.) can still
                finish onboarding and connect later from Settings. */}
            {currentStep === 1 && connectedSocials.length === 0 && (
              <div className="text-center">
                <button
                  type="button"
                  onClick={handleNext}
                  disabled={isLoading}
                  className="text-xs font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline disabled:opacity-40"
                >
                  Skip for now — connect from Settings later
                </button>
              </div>
            )}
          </div>
        )}
      </main>

      {/* T-IGTRUST-0907 — see MetaConnectPathDialog's header for why this question exists and
          why it is one shared component rather than a copy per connect surface. */}
      <MetaConnectPathDialog
        open={showPathChoice}
        onOpenChange={setShowPathChoice}
        onChoose={(authPath) => void handleConnectInstagram(authPath)}
        busy={connectingPlatform === 'instagram'}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 1 — Connect Socials
// ---------------------------------------------------------------------------

interface ConnectSocialsStepProps {
  connectedSocials: Social[];
  connectingPlatform: Social | null;
  onConnect: (platform: Social) => void;
}

function ConnectSocialsStep({
  connectedSocials,
  connectingPlatform,
  onConnect,
}: ConnectSocialsStepProps) {
  const socials: Array<{ id: Social; label: string; icon: React.ComponentType<{ className?: string }>; handleMock: string; bg: string; comingSoon?: boolean }> = [
    { id: 'instagram', label: 'Instagram', icon: Instagram, handleMock: 'Connected', bg: 'bg-gradient-to-br from-purple-500 via-pink-500 to-orange-400' },
    { id: 'youtube', label: 'YouTube', icon: Youtube, handleMock: 'Connected', bg: 'bg-red-500', comingSoon: true },
  ];

  const instagramConnected = connectedSocials.includes('instagram');

  return (
    <div className="space-y-6">
      <div className="text-center">
        <h2 className="text-2xl font-bold">Connect your Instagram</h2>
        <p className="text-muted-foreground mt-2">
          This is what turns your account into a profile brands can actually find.
        </p>
      </div>

      {/*
        T-IGTRUST-0907 — the stake, stated concretely rather than as "verify your creator
        status". Verified, not asserted: CreatorProfileSpecifications.hasPlatforms (line 163)
        filters brand Discover with an EXISTS subquery over platform_stats, and the only two
        writers of that table (PlatformStatsAggregationJob, PortfolioService.syncPlatforms)
        both build the row from a Meta metric. A creator who never completes OAuth therefore
        has no row and cannot appear when a brand ticks the Instagram filter — which is the
        single most common way a brand searches. See ledger F-0694.
      */}
      {!instagramConnected && (
        <div className="rounded-lg border border-primary/30 bg-primary/5 p-4">
          <p className="flex items-start gap-2.5 text-sm">
            <Search className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
            <span>
              <span className="font-medium">Brands search by verified Instagram reach.</span>{' '}
              <span className="text-muted-foreground">
                Until this is connected, your profile doesn’t appear in that search — no matter
                how good the rest of it is.
              </span>
            </span>
          </p>
        </div>
      )}

      {/*
        Every line below is checked against what the code actually requests, because a trust
        promise that turns out to be false is worse than no promise:
          · MetaOAuthService.REQUIRED_SCOPES        = instagram_basic, instagram_manage_insights,
                                                      pages_show_list
          · MetaOAuthService.INSTAGRAM_LOGIN_SCOPES = instagram_business_basic,
                                                      instagram_business_manage_insights
        Neither list contains a publishing scope (instagram_content_publish,
        pages_manage_posts) or a messaging scope (instagram_manage_messages), so "never posts
        or DMs" is a statement about the grant itself, not a policy we could quietly change.
        The disconnect claim is ConnectedAccounts' revoke button (connected-accounts.tsx),
        which calls MetaOAuthController's creator-scoped revoke.
      */}
      {/*
        Meta / Instagram Graph API attribution (Priya ruling, T-IGTRUST-0907).

        WHAT THIS MAY CLAIM, AND WHY THIS EXACT WORDING
        The approval attaches to the API PERMISSIONS, never to Influora the company:
          · "Permissions approved by Meta App Review" is TRUE and specific — Meta App Review
            granted Advanced Access on the scopes MetaOAuthService requests
            (app 850102124044922, Advanced Access on 4 scopes, confirmed 2026-09-02).
          · "Approved by Meta" / "Meta Verified" / "Meta Partner" are all REFUSED. The first
            reads as Meta endorsing the company; the second is a paid Meta product Influora
            does not hold; the third is false — Influora is not a Meta Business Partner and
            pages_read_engagement was rejected at review.

        THIS LINE HAS AN EXPIRY. If Advanced Access on those scopes ever lapses or a scope is
        removed at re-review, the claim becomes false and must come down the same day. It is
        not decorative copy — it is an assertion about our App Review standing.
      */}
      <div className="flex items-center gap-3 rounded-lg border bg-muted/30 p-4">
        <div
          className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-purple-500 via-pink-500 to-orange-400"
          aria-hidden="true"
        >
          <Instagram className="h-7 w-7 text-white" />
        </div>
        <div>
          <p className="text-sm font-semibold">Official Instagram Graph API</p>
          <p className="text-xs text-muted-foreground">
            Permissions approved by Meta App Review. You sign in on Instagram’s own screen —
            never on Influora.
          </p>
        </div>
      </div>

      {/*
        The four-line permission list ("We read… / We never post… / We never see your password… /
        Disconnect whenever you want") was REMOVED at Swapnil's direction after the header went.
        The screen now leads with the App Review credential above and nothing else, on the view
        that an itemised grant list reads as a disclosure form and puts the creator on guard at
        exactly the wrong moment.

        Two things that went with it, recorded so nobody re-derives them from scratch:
          · the revocability promise ("disconnect any time, one click, your account stays") is
            usually the line that converts a hesitant creator — it now appears only in Settings ›
            Connected Accounts, i.e. AFTER they have already granted.
          · "we never post, comment or DM" was the only place the product said out loud what the
            scopes exclude. It remains true (no publishing or messaging scope is in
            REQUIRED_SCOPES or INSTAGRAM_LOGIN_SCOPES) — it is simply no longer said here.
        Both survive on the Settings card (connected-accounts.tsx), which keeps its short version.
      */}

      <div className="space-y-3">
        {socials.map((social) => {
          const Icon = social.icon;
          const isConnected = connectedSocials.includes(social.id);
          const isConnecting = connectingPlatform === social.id;
          return (
            <Card
              key={social.id}
              className={cn(
                'transition-all',
                isConnected && 'border-green-500 bg-green-50',
              )}
            >
              <CardContent className="p-4">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className={cn('h-12 w-12 rounded-full flex items-center justify-center', social.bg)}>
                      <Icon className="h-6 w-6 text-white" />
                    </div>
                    <div>
                      <p className="font-medium">{social.label}</p>
                      <p className="text-sm text-muted-foreground">
                        {isConnected ? social.handleMock : 'Connect your account'}
                      </p>
                    </div>
                  </div>
                  {isConnected ? (
                    <CheckCircle2 className="h-6 w-6 text-stage-approved-fg" />
                  ) : social.comingSoon ? (
                    <span className="text-xs font-medium text-muted-foreground">Coming soon</span>
                  ) : (
                    <Button size="sm" onClick={() => onConnect(social.id)} disabled={isConnecting}>
                      {isConnecting ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Connect'}
                    </Button>
                  )}
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>

      {/*
        Meta attribution (Priya ruling, T-IGTRUST-0907).

        WHAT THIS MAY AND MAY NOT SAY
        The ask was to show that Meta "verifies" us. It must not, on three grounds:
          · "Meta Verified" is a specific paid Meta product (the business/creator blue tick).
            Using the phrase claims a subscription Influora does not hold.
          · Implying Meta endorses or verifies Influora is a Brand Guidelines / Platform Terms
            violation. Influora is NOT a Meta Business Partner; app 850102124044922 holds
            Advanced Access on the scopes it requests and had pages_read_engagement REJECTED.
          · It would be a false claim on the one screen whose entire purpose is being
            believable, which defeats the screen.

        What IS true, and is what a creator actually wants to know: the integration is the
        official Instagram Graph API, and the password is entered on Instagram's own domain,
        never ours. Stated as fact about the integration, never as an endorsement of Influora.
        The Instagram glyph is used unmodified to refer to the service being connected.

        If a Meta wordmark or partner badge is ever wanted here, it needs a check against the
        current Meta brand permissions first — it is not a copy decision.
      */}
      {/* The Graph API / App Review attribution moved ABOVE the list, at full size — a trust
          signal set in 12px grey under the fold is not a trust signal. Kept to one instance:
          repeating it here would read as protesting too much.
          "Connect at least one account" was removed as untrue — CR-120 added the skip link, and
          a rule the product doesn't enforce is the kind of small lie that makes a creator
          distrust the bigger claims above it. */}
      <p className="text-xs text-muted-foreground text-center">
        Takes about 30 seconds. You can also do it later from Settings.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 2 — Build Profile
// ---------------------------------------------------------------------------

interface BuildProfileStepProps {
  data: {
    displayName: string;
    bio: string;
    verticals: string[];
    languages: string[];
    city: string;
    phone: string;
    rateMin: string;
    rateMax: string;
  };
  onUpdate: React.Dispatch<React.SetStateAction<BuildProfileStepProps['data']>>;
  onToggleVertical: (v: string) => void;
  onToggleLanguage: (l: string) => void;
  /** PHONE-0904 — server-side 409 (duplicate phone), surfaced field-adjacent by the parent. */
  phoneError: string | null;
  onPhoneErrorClear: () => void;
}

function BuildProfileStep({
  data,
  onUpdate,
  onToggleVertical,
  onToggleLanguage,
  phoneError,
  onPhoneErrorClear,
}: BuildProfileStepProps) {
  // Same validation as creator Settings' phone field (creator-settings.tsx handleSavePhone) —
  // optional, but if present it must look like a real Indian mobile number. Normalized first
  // (PHONE-0904) so a pasted +91/leading-0 number is judged the same way the server would.
  const normalizedPhone = normalizePhone(data.phone);
  const phoneFormatInvalid = normalizedPhone.length > 0 && !isValidPhone(normalizedPhone);

  return (
    <div className="space-y-6">
      <div className="text-center">
        <h2 className="text-2xl font-bold">Build your profile</h2>
        <p className="text-muted-foreground mt-2">
          Brands use this to decide whether to send you proposals.
        </p>
      </div>

      <div className="space-y-2">
        <Label htmlFor="displayName">Display name *</Label>
        <Input
          id="displayName"
          value={data.displayName}
          onChange={(e) => onUpdate({ ...data, displayName: e.target.value })}
          placeholder="e.g., Priya Creates"
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="bio">Bio</Label>
        <Textarea
          id="bio"
          value={data.bio}
          onChange={(e) => onUpdate({ ...data, bio: e.target.value })}
          placeholder="A 1–2 line intro brands will see first"
          rows={3}
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="city">City</Label>
        <Input
          id="city"
          value={data.city}
          onChange={(e) => onUpdate({ ...data, city: e.target.value })}
          placeholder="e.g., Mumbai"
        />
      </div>

      {/* PHONE-0904 — Optional here, same as creator Settings' Mobile Number field
          (PHONE-0829 P1). Same +91 affix / filter / maxLength / shared phone.ts helper as
          creator-settings.tsx so both creator surfaces agree. The onChange filter allows
          '+' and spaces through (does not strip them) so pasting '+91 9876543210' from
          contacts is never mangled mid-keystroke — normalizePhone() handles it at
          validate/submit time instead. */}
      <div className="space-y-2">
        <Label htmlFor="phone">Mobile number</Label>
        <div className="flex gap-2">
          <div className="flex h-9 items-center rounded-md border border-input bg-muted px-3 text-sm text-muted-foreground">
            +91
          </div>
          <Input
            id="phone"
            inputMode="tel"
            placeholder="98765 43210"
            value={data.phone}
            onChange={(e) => {
              onPhoneErrorClear();
              onUpdate({ ...data, phone: filterPhoneInput(e.target.value) });
            }}
            maxLength={17}
            className="flex-1"
          />
        </div>
        <p className="text-xs text-muted-foreground">
          Optional. Brands and Influora use this to reach you about deals.
        </p>
        {phoneFormatInvalid && (
          <p role="alert" className="text-xs text-destructive-foreground">
            Enter a valid 10-digit mobile number
          </p>
        )}
        {phoneError && (
          <p role="alert" className="text-xs text-destructive-foreground">
            {phoneError}
          </p>
        )}
      </div>

      <div className="space-y-2">
        <Label>Content verticals * <span className="text-xs text-muted-foreground">(pick up to 3)</span></Label>
        <div className="flex flex-wrap gap-2">
          {CONTENT_VERTICALS.map((v) => {
            const active = data.verticals.includes(v);
            const disabled = !active && data.verticals.length >= 3;
            return (
              <button
                key={v}
                type="button"
                disabled={disabled}
                onClick={() => onToggleVertical(v)}
                className={cn(
                  'rounded-full border px-3 py-1.5 text-xs font-medium transition-colors',
                  active
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'bg-background text-foreground border-border hover:bg-muted',
                  disabled && 'opacity-40 cursor-not-allowed',
                )}
              >
                {v}
              </button>
            );
          })}
        </div>
      </div>

      <div className="space-y-2">
        <Label>Languages</Label>
        <div className="flex flex-wrap gap-2">
          {LANGUAGES.map((l) => {
            const active = data.languages.includes(l);
            return (
              <button
                key={l}
                type="button"
                onClick={() => onToggleLanguage(l)}
                className={cn(
                  'rounded-full border px-3 py-1.5 text-xs font-medium transition-colors',
                  active
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'bg-background text-foreground border-border hover:bg-muted',
                )}
              >
                {l}
              </button>
            );
          })}
        </div>
      </div>

      <div className="space-y-2">
        <Label>Rate range per deliverable (₹) *</Label>
        <div className="flex gap-3">
          <div className="relative flex-1">
            <IndianRupee className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              type="number"
              inputMode="numeric"
              value={data.rateMin}
              onChange={(e) => onUpdate({ ...data, rateMin: e.target.value })}
              placeholder="Min"
              className="pl-9"
            />
          </div>
          <div className="relative flex-1">
            <IndianRupee className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              type="number"
              inputMode="numeric"
              value={data.rateMax}
              onChange={(e) => onUpdate({ ...data, rateMax: e.target.value })}
              placeholder="Max"
              className="pl-9"
            />
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 3 — You're in
// ---------------------------------------------------------------------------

interface YoureInStepProps {
  onComplete: (destination?: string) => void;
  isLoading: boolean;
}

function YoureInStep({ onComplete, isLoading }: YoureInStepProps) {
  return (
    <div className="space-y-7">
      <div className="space-y-3 text-center">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-success/10">
          <CheckCircle2 className="h-7 w-7 text-success-foreground" />
        </div>
        <h2 className="text-2xl font-bold">
          You're in! <Sparkles className="inline-block h-5 w-5 text-amber-500" />
        </h2>
        <p className="text-sm leading-relaxed text-muted-foreground">
          Your profile is live. Brands can now find and message you. We'll ask for KYC and
          payout details when you make your first withdrawal — no need now.
        </p>
      </div>

      <div className="space-y-3">
        {/* F-0341 — "Check your Deals" is an instruction; it was a plain div with nothing to
            click, so the only way out of this screen was the button below. */}
        <button
          type="button"
          onClick={() => onComplete('/creator/deals')}
          disabled={isLoading}
          className="flex w-full items-start gap-3 rounded-lg border border-border bg-card p-4 text-left transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-60"
        >
          <Briefcase className="h-5 w-5 text-primary mt-0.5 shrink-0" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium">Check your Deals</p>
            <p className="text-xs text-muted-foreground">
              Incoming proposals from brands show up here. Accept, counter, or reject in one tap.
            </p>
          </div>
          <ArrowRight className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
        </button>
        <div className="flex items-start gap-3 rounded-lg border border-border bg-card p-4">
          <WalletIcon className="h-5 w-5 text-muted-foreground mt-0.5 shrink-0" />
          <div className="min-w-0">
            <p className="text-sm font-medium">Wallet stays empty until you earn</p>
            <p className="text-xs text-muted-foreground">
              Payouts land in your wallet after each completed deal. Add bank/UPI on first withdrawal.
            </p>
          </div>
        </div>
      </div>

      {/* Wrapped, not passed bare: `onComplete` now takes a destination, and `onClick={onComplete}`
          would hand it the MouseEvent to navigate() to. */}
      <Button
        onClick={() => onComplete('/creator/dashboard')}
        disabled={isLoading}
        className="w-full gap-1.5"
        size="lg"
      >
        {isLoading ? 'Setting up…' : 'Go to Dashboard'}
        <ArrowRight className="h-4 w-4" />
      </Button>
    </div>
  );
}
