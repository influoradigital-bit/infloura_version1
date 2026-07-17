import * as React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '@/lib/store';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { Eye, EyeOff, ArrowRight, Loader2 } from 'lucide-react';
import { createMockCreatorUser } from '@/lib/mock-user';
import { AuthLoginShell } from '@/components/shared/auth-login-shell';
import { api, ApiError, isApiLive } from '@/lib/api';

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return <p className="mt-1 text-xs text-destructive">{message}</p>;
}

export default function CreatorRegisterPage() {
  const navigate = useNavigate();
  const { login } = useAuthStore();
  const [isLoading, setIsLoading] = React.useState(false);
  const [showPassword, setShowPassword] = React.useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = React.useState(false);
  const [name, setName] = React.useState('');
  const [email, setEmail] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [confirmPassword, setConfirmPassword] = React.useState('');
  const [acceptTerms, setAcceptTerms] = React.useState(false);
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  const validate = () => {
    const errs: Record<string, string> = {};
    if (!name.trim()) errs.name = 'Your name is required';
    if (!email.trim()) {
      errs.email = 'Email address is required';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      errs.email = 'Please enter a valid email address';
    }
    if (!password) {
      errs.password = 'Password is required';
    } else if (password.length < 8) {
      errs.password = 'Password must be at least 8 characters';
    }
    if (!confirmPassword) {
      errs.confirmPassword = 'Please confirm your password';
    } else if (password !== confirmPassword) {
      errs.confirmPassword = 'Passwords do not match';
    }
    if (!acceptTerms) errs.acceptTerms = 'You must agree to the Terms of Service to continue';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsLoading(true);
    setErrors({});
    try {
      // api.auth.creatorRegister (src/lib/api.ts) calls POST /auth/creator/register in live
      // mode — verified against AuthController.creatorRegister / CreatorRegisterRequest — and
      // only falls back to a mock token (behind assertMockAuthAllowed's fail-closed guard,
      // Kabir A3) when VITE_API_MODE !== 'live', mirroring how creator-login.tsx now calls
      // api.auth.creatorLogin instead of minting a hardcoded token.
      const result = await api.auth.creatorRegister({
        email,
        password,
        displayName: name.trim(),
        acceptedTerms: acceptTerms,
      });
      api.auth.setToken('creator', result.token);

      if (!isApiLive()) {
        // Mock mode only — populate a demo profile for the UI to render.
        login(createMockCreatorUser({ id: 'creator-new', displayName: name.trim() || 'New Creator', email }));
      }

      navigate(result.onboardingComplete ? '/creator/dashboard' : '/creator/onboarding');
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Registration failed. Please try again.';
      setErrors({ form: message });
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <AuthLoginShell
      accent="creator"
      heroTitle="Turn collaborations into income"
      heroSubtitle="Manage deals, submit deliverables, and get paid — with a workspace built for Indian creators."
      heroBullets={[
        'Verified brand partnerships',
        'Transparent escrow payouts',
        'Deal room in one place',
      ]}
    >
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-foreground mb-2">Create your account</h1>
        <p className="text-muted-foreground">Start earning from brand collaborations</p>
      </div>

      {errors.form && (
        <div className="mb-6 rounded-lg border border-destructive/30 bg-destructive/10 p-4">
          <p className="text-sm text-destructive">{errors.form}</p>
        </div>
      )}

      <form onSubmit={handleRegister} className="space-y-4" noValidate>
        <div>
          <Label htmlFor="name" className="mb-2">
            Your Name
          </Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              if (errors.name) setErrors((prev) => ({ ...prev, name: '' }));
            }}
            placeholder="Enter your full name"
            className="h-auto py-3 bg-background/60"
          />
          <FieldError message={errors.name} />
        </div>

        <div>
          <Label htmlFor="email" className="mb-2">
            Email Address
          </Label>
          <Input
            id="email"
            type="email"
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
              if (errors.email) setErrors((prev) => ({ ...prev, email: '' }));
            }}
            placeholder="you@example.com"
            className="h-auto py-3 bg-background/60"
          />
          <FieldError message={errors.email} />
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
              onChange={(e) => {
                setPassword(e.target.value);
                if (errors.password) setErrors((prev) => ({ ...prev, password: '' }));
              }}
              placeholder="Create a password (min 8 characters)"
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
          <FieldError message={errors.password} />
        </div>

        <div>
          <Label htmlFor="confirm-password" className="mb-2">
            Confirm Password
          </Label>
          <div className="relative">
            <Input
              id="confirm-password"
              type={showConfirmPassword ? 'text' : 'password'}
              value={confirmPassword}
              onChange={(e) => {
                setConfirmPassword(e.target.value);
                if (errors.confirmPassword) setErrors((prev) => ({ ...prev, confirmPassword: '' }));
              }}
              placeholder="Confirm your password"
              className="h-auto py-3 pr-12 bg-background/60"
            />
            <button
              type="button"
              onClick={() => setShowConfirmPassword(!showConfirmPassword)}
              aria-label={showConfirmPassword ? 'Hide confirm password' : 'Show confirm password'}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            >
              {showConfirmPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
            </button>
          </div>
          <FieldError message={errors.confirmPassword} />
        </div>

        <div className="flex items-start gap-2 pt-1">
          <Checkbox
            id="terms"
            checked={acceptTerms}
            onCheckedChange={(checked) => {
              setAcceptTerms(!!checked);
              if (errors.acceptTerms) setErrors((prev) => ({ ...prev, acceptTerms: '' }));
            }}
          />
          <label htmlFor="terms" className="cursor-pointer text-xs leading-tight text-muted-foreground">
            I agree to the <a href="/terms" className="text-primary hover:underline">Terms of Service</a> and{' '}
            <a href="/privacy" className="text-primary hover:underline">Privacy Policy</a>
          </label>
        </div>
        <FieldError message={errors.acceptTerms} />

        <Button type="submit" disabled={isLoading} className="w-full font-semibold py-3 h-auto mt-6 gap-2">
          {isLoading ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              Creating account...
            </>
          ) : (
            <>
              Create Account
              <ArrowRight className="w-4 h-4" />
            </>
          )}
        </Button>
      </form>

      <div className="mt-6 text-center text-sm text-muted-foreground">
        Already have an account?{' '}
        <Link to="/creator/login" className="text-primary hover:underline font-medium">
          Sign in
        </Link>
      </div>

      <div className="text-center mt-4">
        <Link to="/brand/register" className="text-sm text-muted-foreground hover:text-primary">
          Are you a brand? Register here
        </Link>
      </div>
    </AuthLoginShell>
  );
}
