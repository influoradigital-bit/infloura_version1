import * as React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '@/lib/store';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Eye, EyeOff, ArrowRight, Loader2 } from 'lucide-react';
import { createMockCreatorUser } from '@/lib/mock-user';
import { AuthLoginShell } from '@/components/shared/auth-login-shell';
import { DemoAccessPanel } from '@/components/shared/demo-access-panel';
import { assertMockAuthAllowed } from '@/lib/api';

export default function CreatorLoginPage() {
  const navigate = useNavigate();
  const { login } = useAuthStore();
  const [isLoading, setIsLoading] = React.useState(false);
  const [showPassword, setShowPassword] = React.useState(false);
  const [email, setEmail] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [error, setError] = React.useState('');

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!email || !password) {
      setError('Please fill in all fields');
      return;
    }

    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 1000));

    // Fail-closed mock guard (Kabir A3): a production build must never
    // silently mint a hardcoded mock token with no credential check.
    assertMockAuthAllowed();

    login(createMockCreatorUser());
    localStorage.setItem('creator_token', 'mock_creator_token');

    const onboardingCompleted = localStorage.getItem('creator_onboarding_completed');
    navigate(onboardingCompleted ? '/creator/inbox' : '/creator/onboarding');
    setIsLoading(false);
  };

  return (
    <AuthLoginShell
      heroTitle="Turn collaborations into income"
      heroSubtitle="Manage deals, submit deliverables, and get paid — with a workspace built for Indian creators."
      heroBullets={[
        'Verified brand partnerships',
        'Transparent escrow payouts',
        'Deal room in one place',
      ]}
    >
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-foreground mb-2">Welcome Back</h1>
        <p className="text-muted-foreground">Sign in to your creator account</p>
      </div>

      {error && (
        <div className="mb-6 p-4 bg-destructive/10 border border-destructive/30 rounded-lg">
          <p className="text-destructive text-sm">{error}</p>
        </div>
      )}

      <form onSubmit={handleLogin} className="space-y-4">
        <div>
          <Label htmlFor="email" className="mb-2">
            Email Address
          </Label>
          <Input
            id="email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            className="h-auto py-3 bg-background/60"
          />
        </div>

        <div>
          <Label htmlFor="password" className="mb-2">
            Password
          </Label>
          <div className="relative">
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Enter your password"
              className="h-auto py-3 pr-12 bg-background/60"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            >
              {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
            </button>
          </div>
        </div>

        <div className="flex items-center justify-between pt-2">
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              className="w-4 h-4 rounded border-input accent-primary cursor-pointer"
            />
            <span className="text-sm text-muted-foreground">Remember me</span>
          </label>
          <button
            type="button"
            className="text-sm text-primary hover:underline transition-colors"
          >
            Forgot password?
          </button>
        </div>

        <Button
          type="submit"
          disabled={isLoading}
          className="w-full font-semibold py-3 h-auto mt-6 gap-2"
        >
          {isLoading ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              Signing in...
            </>
          ) : (
            <>
              Sign In
              <ArrowRight className="w-4 h-4" />
            </>
          )}
        </Button>
      </form>

      <DemoAccessPanel />

      <div className="mt-6 text-center text-sm text-muted-foreground">
        New to Influora?{' '}
        <Link to="/creator/register" className="text-primary hover:underline font-medium">
          Create an account
        </Link>
      </div>

      <div className="text-center mt-4">
        <Link to="/brand/login" className="text-sm text-muted-foreground hover:text-primary">
          Are you a brand? Sign in here
        </Link>
      </div>
    </AuthLoginShell>
  );
}
