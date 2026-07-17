import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Eye, EyeOff, ArrowRight, Loader2, Mail, Lock } from 'lucide-react';
import { AuthLoginShell } from '@/components/shared/auth-login-shell';
import { DemoAccessPanel } from '@/components/shared/demo-access-panel';
import { api, ApiError } from '@/lib/api';
import { getBrandOnboardingComplete } from '@/lib/auth-session';

const inputClass =
  'h-11 pl-10 bg-background focus-visible:ring-2 focus-visible:ring-primary/30 focus-visible:border-primary/40 transition-[color,box-shadow,border-color] duration-150 ease-out';

export default function BrandLoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      if (!email || !password) {
        setError('Please fill in all fields');
        return;
      }

      const session = await api.auth.brandLogin({ email, password });
      const done = session.onboardingComplete || getBrandOnboardingComplete();
      navigate(done ? '/brand/dashboard' : '/brand/onboarding');
    } catch (err) {
      const message =
        err instanceof ApiError ? err.message : 'Login failed. Please try again.';
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLoginShell
      accent="brand"
      heroTitle="Collaborate with creators at scale"
      heroSubtitle="Fund campaigns, manage deal rooms, and release payments — all in one workspace."
      heroBullets={[
        'Escrow-protected brand budgets',
        'End-to-end deal room & contracts',
        'Creator discovery built for India',
      ]}
    >
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-foreground sm:text-3xl">Welcome back</h1>
        <p className="mt-1.5 text-sm text-muted-foreground">Sign in to your brand account</p>
      </div>

      {error && (
        <div
          className="mb-5 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3"
          role="alert"
        >
          <p className="text-sm text-destructive">{error}</p>
        </div>
      )}

      <form onSubmit={handleLogin} className="space-y-4">
        <div>
          <Label htmlFor="email" className="mb-1.5 text-sm">
            Email address
          </Label>
          <div className="relative">
            <Mail className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@company.com"
              className={inputClass}
              aria-invalid={!!error}
              autoComplete="email"
            />
          </div>
        </div>

        <div>
          <Label htmlFor="password" className="mb-1.5 text-sm">
            Password
          </Label>
          <div className="relative">
            <Lock className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              className={`${inputClass} pr-10`}
              aria-invalid={!!error}
              autoComplete="current-password"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground transition-[color] duration-150 ease-out hover:text-foreground active:scale-[0.94]"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
            >
              {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </div>

        <div className="flex justify-end pt-1">
          <Link
            to="/brand/forgot-password"
            className="text-sm text-primary transition-[color] duration-150 ease-out hover:underline"
          >
            Forgot password?
          </Link>
        </div>

        <Button type="submit" className="h-11 w-full font-medium" disabled={loading}>
          {loading ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Signing in...
            </>
          ) : (
            <>
              Sign in
              <ArrowRight className="h-4 w-4" />
            </>
          )}
        </Button>
      </form>

      <DemoAccessPanel />

      <p className="mt-6 text-center text-sm text-muted-foreground">
        Don&apos;t have an account?{' '}
        <Link
          to="/brand/register"
          className="font-medium text-primary transition-[color] duration-150 ease-out hover:underline"
        >
          Create one
        </Link>
      </p>
    </AuthLoginShell>
  );
}
