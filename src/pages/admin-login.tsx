/**
 * INFLUORA ADMIN PANEL — Admin Login Page
 * Owner: Ananya (Frontend) · driven by Priya (CTO), P0-SEC follow-up
 * Reference: docs/ADMIN-PANEL-SPEC.md
 *
 * Unguarded route (`/admin/login`) that authenticates an admin via
 * `authApi.login()` (POST /api/v1/admin/auth/login), stores the returned
 * access token as `admin_token` in localStorage, and hands off to the
 * guarded `/admin` console. `AdminProtectedRoute` (src/App.tsx) redirects
 * unauthenticated visitors here.
 *
 * MFA: SUPER_ADMIN/ADMIN roles must have MFA enabled server-side, so an
 * authenticator-code field is always available. The backend returns an
 * `MFA_REQUIRED` error when a code is needed but missing; because the admin
 * error envelope is known to render messages inconsistently (see
 * AdminAuthController javadoc), we simply keep the optional code field visible
 * rather than parsing the error to reveal it.
 */

import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Eye, EyeOff, ArrowRight, Loader2, Mail, Lock, ShieldCheck } from 'lucide-react';
import { AuthLoginShell } from '@/components/shared/auth-login-shell';
import { authApi } from '@/admin/services/api-contracts';

const inputClass =
  'h-11 pl-10 bg-background focus-visible:ring-2 focus-visible:ring-primary/30 focus-visible:border-primary/40 transition-[color,box-shadow,border-color] duration-150 ease-out';

export default function AdminLoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      if (!email || !password) {
        setError('Please enter your email and password');
        return;
      }

      const trimmedCode = mfaCode.trim();
      const res = await authApi.login({
        email,
        password,
        ...(trimmedCode ? { mfaCode: trimmedCode } : {}),
      });

      if (res.success && res.data?.token) {
        localStorage.setItem('admin_token', res.data.token);
        navigate('/admin');
        return;
      }

      // `apiRequest` surfaces the server error string when available; fall back
      // to a generic message (the admin error envelope may not expose one).
      setError(res.error || 'Login failed. Check your credentials and authenticator code.');
    } catch {
      setError('Login failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLoginShell
      accent="brand"
      heroTitle="Influora operations console"
      heroSubtitle="Moderation, finance, support, and platform health — one secure console."
      heroBullets={[
        'MFA-protected admin access',
        'Full audit trail on every action',
        'Role-scoped operational control',
      ]}
    >
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-foreground sm:text-3xl">Admin sign in</h1>
        <p className="mt-1.5 text-sm text-muted-foreground">
          Authorized operators only. All actions are logged.
        </p>
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
              placeholder="you@influora.com"
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

        <div>
          <Label htmlFor="mfaCode" className="mb-1.5 text-sm">
            Authenticator code
            <span className="ml-1 text-muted-foreground">(if MFA is enabled)</span>
          </Label>
          <div className="relative">
            <ShieldCheck className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              id="mfaCode"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={mfaCode}
              onChange={(e) => setMfaCode(e.target.value)}
              placeholder="123456"
              className={inputClass}
            />
          </div>
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

      <p className="mt-6 text-center text-xs text-muted-foreground">
        Access is provisioned by platform administrators. There is no public sign-up.
      </p>
    </AuthLoginShell>
  );
}
