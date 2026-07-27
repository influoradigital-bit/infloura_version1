import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Eye, EyeOff, ArrowRight, ArrowLeft, CheckCircle2 } from 'lucide-react';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { EmailOtpGate } from '@/components/shared/email-otp-gate';
import { api, ApiError } from '@/lib/api';

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return <p className="text-red-400 text-xs mt-1">{message}</p>;
}

export default function BrandRegisterPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState(1);

  // Step 1 fields
  const [companyName, setCompanyName] = useState('');
  const [industry, setIndustry] = useState('');
  const [teamSize, setTeamSize] = useState('');

  // Step 2 fields
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [agreeToTerms, setAgreeToTerms] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  const [loading, setLoading] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // Email-OTP gate. This page is the signup CTA linked from the landing page, every /features
  // page, both how-it-works pages and the blog — so it, not the onboarding wizard, is where
  // verification has to happen. `requireEmailOtp` mirrors the server flag enforced by
  // AuthService.brandRegister; it defaults false so a slow or failed config read never blocks
  // signup (the server would reject with a readable EMAIL_NOT_VERIFIED instead).
  const [requireEmailOtp, setRequireEmailOtp] = useState(false);
  const [showOtp, setShowOtp] = useState(false);

  React.useEffect(() => {
    let cancelled = false;
    api.config.public().then((cfg) => {
      if (!cancelled) setRequireEmailOtp(cfg.requireEmailOtp);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const validateStep1 = () => {
    const errs: Record<string, string> = {};
    if (!companyName.trim()) errs.companyName = 'Company name is required';
    if (!industry) errs.industry = 'Please select your industry';
    if (!teamSize) errs.teamSize = 'Please select your team size';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const validateStep2 = () => {
    const errs: Record<string, string> = {};
    if (!email.trim()) {
      errs.email = 'Email address is required';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      errs.email = 'Please enter a valid email address';
    }
    if (!password) {
      errs.password = 'Password is required';
    } else if (password.length < 8) {
      errs.password = 'Password must be at least 8 characters';
    } else if (!/(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/.test(password)) {
      errs.password = 'Password must contain uppercase, lowercase and a number';
    }
    if (!confirmPassword) {
      errs.confirmPassword = 'Please confirm your password';
    } else if (password !== confirmPassword) {
      errs.confirmPassword = 'Passwords do not match';
    }
    if (!agreeToTerms) {
      errs.agreeToTerms = 'You must agree to the Terms of Service to continue';
    }
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleNextStep = (e: React.FormEvent) => {
    e.preventDefault();
    if (validateStep1()) setStep(2);
  };

  /**
   * The actual account creation. Split out of `handleRegister` so it can fire either straight
   * from step 2 (OTP off) or from the OTP gate's `onVerified` (OTP on).
   */
  const submitRegistration = async () => {
    setLoading(true);
    setErrors({});

    try {
      const [firstName, ...rest] = email.includes('@')
        ? email.split('@')[0].split(/[._-]/).filter(Boolean)
        : ['Brand', 'User'];
      const lastName = rest.length > 0 ? rest.join(' ') : 'User';

      await api.auth.brandRegister({
        email,
        password,
        firstName: firstName.charAt(0).toUpperCase() + firstName.slice(1),
        lastName: lastName.charAt(0).toUpperCase() + lastName.slice(1),
        companyName,
        industry: industry || 'other',
        companySize: teamSize || '1-5',
        acceptedTerms: agreeToTerms,
      });

      navigate('/brand/onboarding');
    } catch (err) {
      const message =
        err instanceof ApiError ? err.message : 'Registration failed. Please try again.';
      setErrors({ form: message });
      // Back to the form so the error sits next to the fields it refers to.
      setShowOtp(false);
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateStep2()) return;

    if (!requireEmailOtp) {
      await submitRegistration();
      return;
    }

    // Send the first code from here so a delivery failure (MSG91 unconfigured → 503
    // EMAIL_DELIVERY_FAILED) surfaces on the form rather than stranding the user on an empty
    // OTP panel with no code coming.
    setLoading(true);
    setErrors({});
    try {
      await api.auth.sendBrandEmailOtp(email);
      setShowOtp(true);
    } catch (err) {
      const message =
        err instanceof ApiError ? err.message : 'Could not send the verification code. Try again.';
      setErrors({ form: message });
    } finally {
      setLoading(false);
    }
  };

  const inputClassName =
    'w-full px-4 py-3 bg-background border border-input rounded-lg text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent transition h-auto';

  const inputErrorClass = (field: string) =>
    errors[field] ? 'border-red-500 focus:ring-red-500' : '';

  return (
    <div className="min-h-screen auth-gradient flex flex-col">
      <div className="px-4 py-6 sm:px-6 sm:py-8">
        <InfluoraLogo size="lg" showName={true} />
      </div>

      <div className="flex-1 flex items-center justify-center px-4 py-12">
        <div className="w-full max-w-md">
          <div className="bg-card border border-border shadow-sm rounded-2xl p-8 backdrop-blur-sm">
            {showOtp ? (
              <EmailOtpGate
                email={email}
                role="brand"
                onVerified={submitRegistration}
                onEditEmail={() => setShowOtp(false)}
              />
            ) : (
            <>
            <div className="mb-8">
              <h1 className="text-3xl font-bold text-foreground mb-2">Create Account</h1>
              <p className="text-muted-foreground">
                {step === 1 ? 'Tell us about your brand' : 'Set up your account credentials'}
              </p>
            </div>

            {/* Step Progress */}
            <div className="mb-8 flex gap-2">
              <div className={`flex-1 h-1 rounded-full ${step >= 1 ? 'bg-blue-500' : 'bg-muted'}`} />
              <div className={`flex-1 h-1 rounded-full ${step >= 2 ? 'bg-blue-500' : 'bg-muted'}`} />
            </div>

            {/* ── STEP 1 ── */}
            {step === 1 && (
              <form onSubmit={handleNextStep} className="space-y-5" noValidate>
                <div>
                  <Label htmlFor="company-name" className="block text-sm font-medium text-foreground mb-1.5">
                    Company Name <span className="text-red-400">*</span>
                  </Label>
                  <Input
                    id="company-name"
                    type="text"
                    value={companyName}
                    onChange={(e) => {
                      setCompanyName(e.target.value);
                      if (errors.companyName) setErrors((prev) => ({ ...prev, companyName: '' }));
                    }}
                    placeholder="Your brand or company name"
                    className={`${inputClassName} ${inputErrorClass('companyName')}`}
                  />
                  <FieldError message={errors.companyName} />
                </div>

                <div>
                  <Label htmlFor="industry" className="block text-sm font-medium text-foreground mb-1.5">
                    Industry <span className="text-red-400">*</span>
                  </Label>
                  <Select
                    value={industry}
                    onValueChange={(val) => {
                      setIndustry(val);
                      if (errors.industry) setErrors((prev) => ({ ...prev, industry: '' }));
                    }}
                  >
                    <SelectTrigger
                      id="industry"
                      className={`${inputClassName} w-full ${inputErrorClass('industry')}`}
                    >
                      <SelectValue placeholder="Select your industry" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="fashion">Fashion & Beauty</SelectItem>
                      <SelectItem value="food">Food & Beverage</SelectItem>
                      <SelectItem value="tech">Technology</SelectItem>
                      <SelectItem value="health">Health & Wellness</SelectItem>
                      <SelectItem value="finance">Finance</SelectItem>
                      <SelectItem value="other">Other</SelectItem>
                    </SelectContent>
                  </Select>
                  <FieldError message={errors.industry} />
                </div>

                <div>
                  <Label htmlFor="team-size" className="block text-sm font-medium text-foreground mb-1.5">
                    Team Size <span className="text-red-400">*</span>
                  </Label>
                  <Select
                    value={teamSize}
                    onValueChange={(val) => {
                      setTeamSize(val);
                      if (errors.teamSize) setErrors((prev) => ({ ...prev, teamSize: '' }));
                    }}
                  >
                    <SelectTrigger
                      id="team-size"
                      className={`${inputClassName} w-full ${inputErrorClass('teamSize')}`}
                    >
                      <SelectValue placeholder="Select team size" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="1-5">1–5 people</SelectItem>
                      <SelectItem value="6-20">6–20 people</SelectItem>
                      <SelectItem value="21-50">21–50 people</SelectItem>
                      <SelectItem value="50+">50+ people</SelectItem>
                    </SelectContent>
                  </Select>
                  <FieldError message={errors.teamSize} />
                </div>

                <Button
                  type="submit"
                  className="w-full bg-primary hover:bg-primary/90 text-white font-semibold py-3 rounded-lg transition mt-4 gap-2"
                >
                  Next
                  <ArrowRight className="w-4 h-4" />
                </Button>
              </form>
            )}

            {/* ── STEP 2 ── */}
            {step === 2 && (
              <form onSubmit={handleRegister} className="space-y-5" noValidate>
                {errors.form && (
                  <p className="text-sm text-red-400 bg-red-500/10 border border-red-500/30 rounded-lg px-3 py-2">
                    {errors.form}
                  </p>
                )}
                <div>
                  <Label htmlFor="email" className="block text-sm font-medium text-foreground mb-1.5">
                    Email Address <span className="text-red-400">*</span>
                  </Label>
                  <Input
                    id="email"
                    type="email"
                    value={email}
                    onChange={(e) => {
                      setEmail(e.target.value);
                      if (errors.email) setErrors((prev) => ({ ...prev, email: '' }));
                    }}
                    placeholder="you@company.com"
                    className={`${inputClassName} ${inputErrorClass('email')}`}
                  />
                  <FieldError message={errors.email} />
                </div>

                <div>
                  <Label htmlFor="password" className="block text-sm font-medium text-foreground mb-1.5">
                    Password <span className="text-red-400">*</span>
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
                      placeholder="Create a strong password"
                      className={`${inputClassName} pr-12 ${inputErrorClass('password')}`}
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
                  <p className="text-xs text-muted-foreground mt-1.5">
                    Min 8 characters with uppercase, lowercase, and a number
                  </p>
                  <FieldError message={errors.password} />
                </div>

                <div>
                  <Label htmlFor="confirm-password" className="block text-sm font-medium text-foreground mb-1.5">
                    Confirm Password <span className="text-red-400">*</span>
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
                      className={`${inputClassName} pr-12 ${inputErrorClass('confirmPassword')}`}
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

                <div>
                  <label className="flex items-start gap-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={agreeToTerms}
                      onChange={(e) => {
                        setAgreeToTerms(e.target.checked);
                        if (errors.agreeToTerms) setErrors((prev) => ({ ...prev, agreeToTerms: '' }));
                      }}
                      className="w-4 h-4 rounded bg-muted border-input cursor-pointer accent-primary mt-0.5 shrink-0"
                    />
                    <span className="text-sm text-muted-foreground">
                      I agree to the{' '}
                      <button type="button" className="text-primary hover:underline">Terms of Service</button>
                      {' '}and{' '}
                      <button type="button" className="text-primary hover:underline">Privacy Policy</button>
                    </span>
                  </label>
                  <FieldError message={errors.agreeToTerms} />
                </div>

                <div className="flex gap-3 pt-2">
                  <Button
                    type="button"
                    onClick={() => { setStep(1); setErrors({}); }}
                    variant="outline"
                    className="flex-1 gap-2"
                  >
                    <ArrowLeft className="w-4 h-4" />
                    Back
                  </Button>
                  <Button
                    type="submit"
                    disabled={loading}
                    className="flex-1 bg-primary hover:bg-primary/90 text-white font-semibold rounded-lg transition disabled:opacity-50 disabled:cursor-not-allowed gap-2"
                  >
                    {loading ? 'Creating...' : 'Create Account'}
                    {!loading && <CheckCircle2 className="w-4 h-4" />}
                  </Button>
                </div>
              </form>
            )}

            <div className="mt-6 text-center text-sm text-muted-foreground">
              Already have an account?{' '}
              <button
                onClick={() => navigate('/brand/login')}
                className="text-primary hover:text-primary/80 font-medium"
              >
                Sign in
              </button>
            </div>
            </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
