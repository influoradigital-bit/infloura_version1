import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, Building2, Clapperboard } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { assertMockAuthAllowed, isApiLive } from '@/lib/api';
import { persistBrandSession, type BackendTokenPair } from '@/lib/auth-session';
import { useAuthStore } from '@/lib/store';
import { createMockCreatorUser } from '@/lib/mock-user';

/**
 * Demo Access panel — one-click entry into a fully-seeded Brand or Creator
 * session for walkthroughs. Renders ONLY in mock/demo builds; the
 * `assertMockAuthAllowed()` guard (Kabir A3) makes a production build throw
 * rather than mint a credential-free session, so this can never ship as a
 * real login bypass.
 *
 * Both roles are offered from a single panel so you can jump into either side
 * of the marketplace from whichever login screen you land on.
 */

// Synthetic token pair for the demo brand — mirrors the `u_1` / Glow Naturals
// seed used across demo-data.ts and the mock api branches, and flows through
// the same persistBrandSession() the live path uses.
const DEMO_BRAND_TOKEN_PAIR: BackendTokenPair = {
  user: { id: 'u_1', email: 'growth@glownaturals.in', displayName: 'Glow Naturals', userType: 'BRAND' },
  workspace: { id: 'ws-1', name: 'Glow Naturals', slug: 'glow-naturals' },
  accessToken: 'mock_brand_token',
  onboardingCompleted: true,
};

export function DemoAccessPanel() {
  const navigate = useNavigate();
  const { login: creatorStoreLogin } = useAuthStore();

  // In a live build there is no demo data to show and no mock token to mint —
  // hide the panel entirely rather than offer a button that would throw.
  if (isApiLive()) return null;

  const enterAsBrand = () => {
    assertMockAuthAllowed();
    persistBrandSession(DEMO_BRAND_TOKEN_PAIR);
    navigate('/brand/dashboard');
  };

  const enterAsCreator = () => {
    assertMockAuthAllowed();
    creatorStoreLogin(createMockCreatorUser());
    localStorage.setItem('creator_token', 'mock_creator_token');
    localStorage.setItem('creator_onboarding_completed', 'true');
    navigate('/creator/dashboard');
  };

  return (
    <div className="mt-6">
      <div className="relative mb-4 flex items-center">
        <span className="h-px flex-1 bg-border" />
        <span className="px-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Demo access
        </span>
        <span className="h-px flex-1 bg-border" />
      </div>

      <div className="rounded-xl border border-primary/30 bg-primary/5 p-4">
        <div className="mb-3 flex items-start gap-2">
          <Sparkles className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
          <p className="text-sm text-muted-foreground">
            Explore the full product with seeded data — no account needed.
          </p>
        </div>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <Button
            type="button"
            variant="secondary"
            onClick={enterAsBrand}
            className="h-11 w-full gap-2 border border-primary/40 font-medium"
          >
            <Building2 className="h-4 w-4" />
            Enter as Brand
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={enterAsCreator}
            className="h-11 w-full gap-2 border border-primary/40 font-medium"
          >
            <Clapperboard className="h-4 w-4" />
            Enter as Creator
          </Button>
        </div>
      </div>
    </div>
  );
}
