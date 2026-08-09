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
} from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { api, ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';

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

  // Step 2
  const [profileData, setProfileData] = React.useState({
    displayName: '',
    bio: '',
    verticals: [] as string[],
    languages: [] as string[],
    city: '',
    rateMin: '',
    rateMax: '',
  });

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
  const handleConnectInstagram = async () => {
    setConnectingPlatform('instagram');
    try {
      localStorage.setItem(META_ONBOARDING_RESUME_KEY, '1');
      const { authorizationUrl } = await api.metaOAuth.authorize();
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
      void handleConnectInstagram();
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
      case 2:
        return (
          profileData.displayName.trim().length > 0 &&
          profileData.verticals.length > 0 &&
          profileData.rateMin.trim().length > 0
        );
      case 3:
        return true;
      default:
        return false;
    }
  };

  const handleSaveProfileAndNext = async () => {
    setIsLoading(true);
    try {
      await api.onboarding.saveCreatorProfile({
        displayName: profileData.displayName,
        bio: profileData.bio,
        verticals: profileData.verticals,
        languages: profileData.languages,
        city: profileData.city,
        rateMin: Number(profileData.rateMin) || 0,
        rateMax: Number(profileData.rateMax) || 0,
      });
      setCurrentStep(3);
      window.scrollTo(0, 0);
    } catch (err) {
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

  const handleComplete = async () => {
    setIsLoading(true);
    try {
      await api.onboarding.completeCreator();
      localStorage.setItem('creator_onboarding_completed', 'true');
      navigate('/creator/deals');
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

  return (
    <div className="space-y-6">
      <div className="text-center">
        <h2 className="text-2xl font-bold">Connect your social accounts</h2>
        <p className="text-muted-foreground mt-2">
          We'll auto-import your profile info and verify your creator status.
        </p>
      </div>

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

      <p className="text-xs text-muted-foreground text-center">
        Connect at least one account. You can add more later from your profile.
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
    rateMin: string;
    rateMax: string;
  };
  onUpdate: React.Dispatch<React.SetStateAction<BuildProfileStepProps['data']>>;
  onToggleVertical: (v: string) => void;
  onToggleLanguage: (l: string) => void;
}

function BuildProfileStep({
  data,
  onUpdate,
  onToggleVertical,
  onToggleLanguage,
}: BuildProfileStepProps) {
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
  onComplete: () => void;
  isLoading: boolean;
}

function YoureInStep({ onComplete, isLoading }: YoureInStepProps) {
  return (
    <div className="space-y-7">
      <div className="space-y-3 text-center">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-success/10">
          <CheckCircle2 className="h-7 w-7 text-success" />
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
        <div className="flex items-start gap-3 rounded-lg border border-border bg-card p-4">
          <Briefcase className="h-5 w-5 text-primary mt-0.5 shrink-0" />
          <div className="min-w-0">
            <p className="text-sm font-medium">Check your Deals</p>
            <p className="text-xs text-muted-foreground">
              Incoming proposals from brands show up here. Accept, counter, or reject in one tap.
            </p>
          </div>
        </div>
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

      <Button onClick={onComplete} disabled={isLoading} className="w-full gap-1.5" size="lg">
        {isLoading ? 'Setting up…' : 'Go to Deals'}
        <ArrowRight className="h-4 w-4" />
      </Button>
    </div>
  );
}
