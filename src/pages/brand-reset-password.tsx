import React, { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { api, ApiError, isApiLive } from '@/lib/api';

const MIN_PASSWORD_LENGTH = 8;

/**
 * BR-03: the page the emailed reset link (`webBaseUrl + "/reset-password?token=" + raw` —
 * AuthService.forgotPassword) actually lands on. Layout/copy mirrors brand-forgot-password.tsx.
 * Role-agnostic by design: `POST /auth/forgot-password` sends the same link regardless of
 * whether the request came from the brand or creator forgot-password page (AuthController is
 * user-type agnostic here — see creator-forgot-password.tsx's comment), so this single page
 * — registered at the bare `/reset-password` path the email actually points to — serves both.
 */
export default function BrandResetPasswordPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const missingToken = token.length === 0;

  const validate = (): string | null => {
    if (missingToken) return 'This reset link is invalid or expired. Please request a new one.';
    if (newPassword.length < MIN_PASSWORD_LENGTH) {
      return `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
    }
    if (newPassword !== confirmPassword) return 'Passwords do not match.';
    return null;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const validationError = validate();
    if (validationError) {
      setError(validationError);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      if (isApiLive()) {
        await api.auth.resetPassword({ token, newPassword });
      } else {
        await new Promise((resolve) => setTimeout(resolve, 800));
      }
      setDone(true);
      setTimeout(() => navigate('/brand/login'), 2000);
    } catch (err) {
      // INVALID_TOKEN / EXPIRED_TOKEN and any other backend rejection all read the same to the
      // user — the link no longer works and they need a fresh one.
      const message =
        err instanceof ApiError
          ? 'This reset link is invalid or expired. Please request a new one.'
          : 'Something went wrong. Please try again.';
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen auth-gradient flex flex-col items-center justify-center px-4">
      <div className="w-full max-w-md space-y-6">
        <InfluoraLogo size="lg" showName={true} />
        <div className="bg-card border border-border shadow-sm rounded-2xl p-8 backdrop-blur-sm">
          <h1 className="text-2xl font-bold text-white mb-2">Set a new password</h1>
          <p className="text-muted-foreground text-sm mb-6">
            {done
              ? 'Your password has been reset. Redirecting to sign in...'
              : 'Choose a new password for your account.'}
          </p>

          {!done ? (
            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <Label htmlFor="newPassword" className="text-foreground">
                  New password
                </Label>
                <Input
                  id="newPassword"
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  placeholder="••••••••"
                  className="mt-2 bg-muted/50 border-slate-600 text-white h-auto py-3"
                  disabled={missingToken}
                  required
                />
              </div>
              <div>
                <Label htmlFor="confirmPassword" className="text-foreground">
                  Confirm password
                </Label>
                <Input
                  id="confirmPassword"
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="••••••••"
                  className="mt-2 bg-muted/50 border-slate-600 text-white h-auto py-3"
                  disabled={missingToken}
                  required
                />
              </div>
              {error && (
                <p role="alert" className="text-sm text-destructive-foreground">
                  {error}
                </p>
              )}
              <Button type="submit" disabled={loading || missingToken} className="w-full">
                {loading ? 'Resetting...' : 'Reset password'}
              </Button>
            </form>
          ) : (
            <Button className="w-full" onClick={() => navigate('/brand/login')}>
              Back to sign in
            </Button>
          )}

          <button
            type="button"
            onClick={() => navigate('/brand/login')}
            className="mt-4 w-full text-center text-sm text-primary hover:text-primary/80"
          >
            Back to login
          </button>
        </div>
      </div>
    </div>
  );
}
