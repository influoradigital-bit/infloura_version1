import * as React from 'react';
import { Link } from 'react-router-dom';
import {
  Eye,
  EyeOff,
  Upload,
  X,
  Plus,
  CheckCircle2,
  Building2,
  Users,
  Mail,
  ArrowRight,
  ArrowLeft,
  Loader2,
  FileText,
  ShieldCheck,
  CloudUpload,
  ImageIcon,
  MailCheck,
  RefreshCw,
  SkipForward,
  Info,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import type { WorkspaceType, MemberRole } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Checkbox } from '@/components/ui/checkbox';
import { Progress } from '@/components/ui/progress';
import {
  uploadToR2,
  createLocalPreview,
  validateFile,
  formatFileSize,
  type UploadResult,
} from '@/lib/upload';
import { api, ApiError } from '@/lib/api';

// ===========================
// Shared types & data
// ===========================

export interface OnboardingData {
  // Step 1: Account
  email: string;
  password: string;
  confirmPassword: string;
  firstName: string;
  lastName: string;
  phone: string;
  // Step 1b: Email OTP
  emailOtpSent: boolean;
  emailOtpCode: string;
  emailOtpVerified: boolean;
  // Step 2: Company
  companyName: string;
  companySlug: string;
  workspaceType: WorkspaceType;
  industry: string;
  companySize: string;
  websiteUrl: string;
  description: string;
  logoFile: File | null;
  logoPreview: string | null;
  logoUpload: UploadResult | null;
  // Step 3: Verification
  gstin: string;
  pan: string;
  gstinDoc: File | null;
  gstinDocPreview: string | null;
  gstinDocUpload: UploadResult | null;
  panDoc: File | null;
  panDocPreview: string | null;
  panDocUpload: UploadResult | null;
  // Step 4: Team
  teamMembers: Array<{ email: string; role: MemberRole }>;
  // Step 5: Trust (no data - informational)
  // Step 6: Wallet
  walletAmount: string;
  // Agreements
  acceptTerms: boolean;
  acceptPrivacy: boolean;
}

export const initialData: OnboardingData = {
  email: '',
  password: '',
  confirmPassword: '',
  firstName: '',
  lastName: '',
  phone: '',
  emailOtpSent: false,
  emailOtpCode: '',
  emailOtpVerified: false,
  companyName: '',
  companySlug: '',
  workspaceType: 'BRAND',
  industry: '',
  companySize: '',
  websiteUrl: '',
  description: '',
  logoFile: null,
  logoPreview: null,
  logoUpload: null,
  gstin: '',
  pan: '',
  gstinDoc: null,
  gstinDocPreview: null,
  gstinDocUpload: null,
  panDoc: null,
  panDocPreview: null,
  panDocUpload: null,
  teamMembers: [],
  walletAmount: '',
  acceptTerms: false,
  acceptPrivacy: false,
};

const industries = [
  'Fashion & Apparel',
  'Beauty & Cosmetics',
  'Food & Beverage',
  'Technology',
  'Health & Wellness',
  'Travel & Hospitality',
  'Entertainment',
  'Sports & Fitness',
  'Home & Living',
  'Financial Services',
  'Education',
  'Other',
];

const companySizes = [
  '1-10 employees',
  '11-50 employees',
  '51-200 employees',
  '201-500 employees',
  '501-1000 employees',
  '1000+ employees',
];

const teamRoles: { value: MemberRole; label: string; description: string }[] = [
  { value: 'ADMIN', label: 'Admin', description: 'Full access to all features' },
  { value: 'MANAGER', label: 'Manager', description: 'Manage campaigns and creators' },
  { value: 'MEMBER', label: 'Member', description: 'View and collaborate' },
  { value: 'VIEWER', label: 'Viewer', description: 'View-only access' },
];

// ===========================
// Shared sub-components
// ===========================

function StepHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div className="mb-8">
      <h1 className="text-2xl font-semibold tracking-tight text-foreground text-balance">{title}</h1>
      <p className="text-muted-foreground mt-1.5 text-sm leading-relaxed">{subtitle}</p>
    </div>
  );
}

function StepFooter({
  onBack,
  onNext,
  nextLabel = 'Continue',
  backLabel = 'Back',
  isSubmitting = false,
  disabled = false,
  showSkip = false,
  onSkip,
  skipLabel = 'Skip for now',
}: {
  onBack?: () => void;
  onNext?: () => void;
  nextLabel?: string;
  backLabel?: string;
  isSubmitting?: boolean;
  disabled?: boolean;
  showSkip?: boolean;
  onSkip?: () => void;
  skipLabel?: string;
}) {
  return (
    <div className="flex items-center gap-3 mt-8">
      {onBack && (
        <Button type="button" variant="outline" onClick={onBack} className="gap-1.5">
          <ArrowLeft className="h-4 w-4" />
          {backLabel}
        </Button>
      )}
      <div className="flex-1" />
      {showSkip && onSkip && (
        <Button type="button" variant="ghost" onClick={onSkip} className="text-muted-foreground">
          {skipLabel}
        </Button>
      )}
      <Button
        type={onNext ? 'button' : 'submit'}
        onClick={onNext}
        disabled={isSubmitting || disabled}
        className="gap-1.5"
      >
        {isSubmitting ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" />
            Please wait...
          </>
        ) : (
          <>
            {nextLabel}
            <ArrowRight className="h-4 w-4" />
          </>
        )}
      </Button>
    </div>
  );
}

/** Reusable file drop zone */
function FileDropZone({
  label,
  accept,
  maxSizeMB = 5,
  file,
  preview,
  uploadResult,
  isUploading,
  uploadProgress,
  onSelect,
  onRemove,
  isImage = false,
}: {
  label: string;
  accept: string;
  maxSizeMB?: number;
  file: File | null;
  preview: string | null;
  uploadResult: UploadResult | null;
  isUploading: boolean;
  uploadProgress: number;
  onSelect: (file: File) => void;
  onRemove: () => void;
  isImage?: boolean;
}) {
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = React.useState(false);
  const [error, setError] = React.useState('');

  const handleFile = (f: File) => {
    setError('');
    const validation = validateFile(f, {
      maxSizeMB,
      allowedTypes: accept.split(',').map((t) => t.trim()),
    });
    if (!validation.valid) {
      setError(validation.error || 'Invalid file');
      return;
    }
    onSelect(f);
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer.files[0];
    if (f) handleFile(f);
  };

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) handleFile(f);
  };

  if (file) {
    return (
      <div className="rounded-lg border border-border bg-muted/30 p-4">
        <div className="flex items-center gap-3">
          {isImage && preview ? (
            <Avatar className="h-12 w-12 rounded-lg">
              <AvatarImage src={preview} className="object-cover" />
              <AvatarFallback className="rounded-lg"><ImageIcon className="h-5 w-5" /></AvatarFallback>
            </Avatar>
          ) : (
            <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10">
              <FileText className="h-5 w-5 text-primary" />
            </div>
          )}
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium truncate text-foreground">{file.name}</p>
            <p className="text-xs text-muted-foreground">{formatFileSize(file.size)}</p>
            {isUploading && (
              <Progress value={uploadProgress} className="h-1.5 mt-1.5" />
            )}
            {uploadResult && (
              <div className="flex items-center gap-1 mt-1">
                <CheckCircle2 className="h-3 w-3 text-success" />
                <span className="text-xs text-success">Uploaded</span>
              </div>
            )}
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8 shrink-0"
            onClick={onRemove}
            disabled={isUploading}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        className={cn(
          'flex flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 py-8 text-center cursor-pointer transition-colors',
          dragOver
            ? 'border-primary bg-primary/5'
            : 'border-border hover:border-primary/50 hover:bg-muted/30',
        )}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => e.key === 'Enter' && inputRef.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
      >
        <CloudUpload className="h-8 w-8 text-muted-foreground/60 mb-2" />
        <p className="text-sm font-medium text-foreground">{label}</p>
        <p className="text-xs text-muted-foreground mt-1">
          Drag & drop or click to browse. Max {maxSizeMB}MB.
        </p>
      </div>
      {error && <p className="text-xs text-destructive mt-1.5">{error}</p>}
      <input ref={inputRef} type="file" accept={accept} className="hidden" onChange={onChange} />
    </div>
  );
}

// ===========================
// OTP Input Component (6-box)
// ===========================

function OtpInput({
  value,
  onChange,
  length = 6,
  error,
}: {
  value: string;
  onChange: (val: string) => void;
  length?: number;
  error?: string;
}) {
  const inputRefs = React.useRef<(HTMLInputElement | null)[]>([]);

  const handleChange = (idx: number, char: string) => {
    // Only allow digits
    if (char && !/^\d$/.test(char)) return;
    const arr = value.split('');
    arr[idx] = char;
    // Pad the array to fill all positions
    while (arr.length < length) arr.push('');
    const next = arr.join('').slice(0, length);
    onChange(next);
    // Auto-focus next box
    if (char && idx < length - 1) {
      inputRefs.current[idx + 1]?.focus();
    }
  };

  const handleKeyDown = (idx: number, e: React.KeyboardEvent) => {
    if (e.key === 'Backspace' && !value[idx] && idx > 0) {
      inputRefs.current[idx - 1]?.focus();
      const arr = value.split('');
      arr[idx - 1] = '';
      onChange(arr.join(''));
    }
    if (e.key === 'ArrowLeft' && idx > 0) inputRefs.current[idx - 1]?.focus();
    if (e.key === 'ArrowRight' && idx < length - 1) inputRefs.current[idx + 1]?.focus();
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, length);
    onChange(pasted);
    // Focus the box after last pasted digit
    const focusIdx = Math.min(pasted.length, length - 1);
    inputRefs.current[focusIdx]?.focus();
  };

  return (
    <div>
      <div className="flex items-center justify-center gap-2.5" onPaste={handlePaste}>
        {Array.from({ length }).map((_, idx) => (
          <input
            key={idx}
            ref={(el) => { inputRefs.current[idx] = el; }}
            type="text"
            inputMode="numeric"
            maxLength={1}
            value={value[idx] || ''}
            onChange={(e) => handleChange(idx, e.target.value)}
            onKeyDown={(e) => handleKeyDown(idx, e)}
            onFocus={(e) => e.target.select()}
            className={cn(
              'h-12 w-11 rounded-lg border-2 bg-background text-center text-lg font-semibold text-foreground outline-none transition-all',
              'focus:border-primary focus:ring-2 focus:ring-primary/20',
              error ? 'border-destructive' : value[idx] ? 'border-primary/50' : 'border-border',
            )}
            autoFocus={idx === 0}
          />
        ))}
      </div>
      {error && (
        <p className="text-xs text-destructive text-center mt-2">{error}</p>
      )}
    </div>
  );
}

// ===========================
// STEP 1: Account Setup (with Email OTP)
// ===========================

export function AccountSetupStep({
  data,
  onUpdate,
  onNext,
}: {
  data: OnboardingData;
  onUpdate: (updates: Partial<OnboardingData>) => void;
  onNext: () => void;
}) {
  const [showPassword, setShowPassword] = React.useState(false);
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  // OTP state
  const [otpError, setOtpError] = React.useState('');
  const [isSendingOtp, setIsSendingOtp] = React.useState(false);
  const [isVerifyingOtp, setIsVerifyingOtp] = React.useState(false);
  const [resendTimer, setResendTimer] = React.useState(0);

  // Countdown timer for resend
  React.useEffect(() => {
    if (resendTimer <= 0) return;
    const timer = setTimeout(() => setResendTimer((t) => t - 1), 1000);
    return () => clearTimeout(timer);
  }, [resendTimer]);

  const validateFields = () => {
    const e: Record<string, string> = {};
    if (!data.firstName) e.firstName = 'Required';
    if (!data.lastName) e.lastName = 'Required';
    if (!data.email) e.email = 'Required';
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(data.email)) e.email = 'Invalid email';
    if (!data.phone) e.phone = 'Required';
    else if (!/^[6-9]\d{9}$/.test(data.phone.replace(/\s+/g, ''))) e.phone = 'Enter valid 10-digit mobile';
    if (!data.password) e.password = 'Required';
    else if (data.password.length < 8) e.password = 'Min 8 characters';
    if (data.password !== data.confirmPassword) e.confirmPassword = 'Does not match';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  // Stage A: Send OTP to email
  const handleSendOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateFields()) return;

    setIsSendingOtp(true);
    setOtpError('');
    try {
      await api.auth.sendBrandEmailOtp(data.email);
      onUpdate({ emailOtpSent: true, emailOtpCode: '', emailOtpVerified: false });
      setResendTimer(30);
    } catch (err) {
      setOtpError(err instanceof ApiError ? err.message : 'Could not send verification code');
    } finally {
      setIsSendingOtp(false);
    }
  };

  // Resend OTP
  const handleResendOtp = async () => {
    if (resendTimer > 0) return;
    setIsSendingOtp(true);
    try {
      await api.auth.sendBrandEmailOtp(data.email);
      onUpdate({ emailOtpCode: '', emailOtpVerified: false });
      setResendTimer(30);
      setOtpError('');
    } catch (err) {
      setOtpError(err instanceof ApiError ? err.message : 'Could not resend code');
    } finally {
      setIsSendingOtp(false);
    }
  };

  // Stage B: Verify OTP
  const handleVerifyOtp = async () => {
    if (data.emailOtpCode.length !== 6) {
      setOtpError('Enter all 6 digits');
      return;
    }

    setIsVerifyingOtp(true);
    setOtpError('');
    try {
      const result = await api.auth.verifyBrandEmail(data.email, data.emailOtpCode);
      if (result.emailVerified) {
        onUpdate({ emailOtpVerified: true });
        setTimeout(() => onNext(), 600);
      } else {
        setOtpError('Verification failed. Please try again.');
      }
    } catch (err) {
      setOtpError(err instanceof ApiError ? err.message : 'Invalid verification code');
    } finally {
      setIsVerifyingOtp(false);
    }
  };

  // Handle OTP change -- auto-verify when 6 digits entered
  const handleOtpChange = (val: string) => {
    onUpdate({ emailOtpCode: val });
    setOtpError('');
  };

  // Go back to edit form
  const handleEditEmail = () => {
    onUpdate({ emailOtpSent: false, emailOtpCode: '', emailOtpVerified: false });
    setOtpError('');
  };

  // ---- OTP Verification Screen (Stage B) ----
  if (data.emailOtpSent && !data.emailOtpVerified) {
    return (
      <div>
        <StepHeader
          title="Verify your email"
          subtitle={`We sent a 6-digit code to ${data.email}`}
        />

        <div className="flex flex-col gap-6">
          {/* Email display with edit */}
          <div className="flex items-center gap-3 rounded-lg border border-border bg-muted/30 px-4 py-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/10">
              <Mail className="h-4 w-4 text-primary" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-foreground truncate">{data.email}</p>
              <p className="text-xs text-muted-foreground">Check your inbox and spam folder</p>
            </div>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="text-xs text-primary shrink-0"
              onClick={handleEditEmail}
            >
              Edit
            </Button>
          </div>

          {/* OTP Input */}
          <div className="flex flex-col items-center gap-4 py-4">
            <OtpInput
              value={data.emailOtpCode}
              onChange={handleOtpChange}
              error={otpError}
            />

            {/* Resend */}
            <div className="flex items-center gap-2">
              {resendTimer > 0 ? (
                <p className="text-xs text-muted-foreground">
                  {"Didn't receive it? Resend in "}
                  <span className="font-semibold text-foreground tabular-nums">{resendTimer}s</span>
                </p>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-xs text-primary gap-1.5"
                  onClick={handleResendOtp}
                  disabled={isSendingOtp}
                >
                  {isSendingOtp ? (
                    <Loader2 className="h-3 w-3 animate-spin" />
                  ) : (
                    <RefreshCw className="h-3 w-3" />
                  )}
                  Resend code
                </Button>
              )}
            </div>
          </div>

          {/* Verify button */}
          <Button
            type="button"
            onClick={handleVerifyOtp}
            disabled={isVerifyingOtp || data.emailOtpCode.length !== 6}
            className="w-full gap-1.5"
            size="lg"
          >
            {isVerifyingOtp ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
                Verifying...
              </>
            ) : (
              <>
                <MailCheck className="h-4 w-4" />
                Verify email
              </>
            )}
          </Button>

          {/* Demo hint */}
          <div className="flex items-center gap-2 rounded-lg border border-border/50 bg-muted/20 px-3 py-2">
            <Info className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
            <p className="text-xs text-muted-foreground">
              Demo mode: use code <span className="font-mono font-semibold text-foreground">123456</span> to verify
            </p>
          </div>
        </div>
      </div>
    );
  }

  // ---- Verified Success (brief flash before auto-advance) ----
  if (data.emailOtpVerified) {
    return (
      <div className="flex flex-col items-center justify-center py-16 gap-4">
        <div className="flex h-16 w-16 items-center justify-center rounded-full bg-success/10">
          <CheckCircle2 className="h-8 w-8 text-success" />
        </div>
        <div className="text-center">
          <p className="text-lg font-semibold text-foreground">Email verified</p>
          <p className="text-sm text-muted-foreground mt-1">Taking you to the next step...</p>
        </div>
      </div>
    );
  }

  // ---- Account Form (Stage A) ----
  return (
    <form onSubmit={handleSendOtp}>
      <StepHeader
        title="Create your account"
        subtitle="Start managing creator collaborations in minutes"
      />

      <div className="flex flex-col gap-5">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="firstName">First name</Label>
            <Input
              id="firstName"
              placeholder="Rahul"
              value={data.firstName}
              onChange={(e) => onUpdate({ firstName: e.target.value })}
              className={cn(errors.firstName && 'border-destructive')}
            />
            {errors.firstName && <p className="text-xs text-destructive">{errors.firstName}</p>}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="lastName">Last name</Label>
            <Input
              id="lastName"
              placeholder="Sharma"
              value={data.lastName}
              onChange={(e) => onUpdate({ lastName: e.target.value })}
              className={cn(errors.lastName && 'border-destructive')}
            />
            {errors.lastName && <p className="text-xs text-destructive">{errors.lastName}</p>}
          </div>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="email">Work email</Label>
          <Input
            id="email"
            type="email"
            placeholder="rahul@company.com"
            value={data.email}
            onChange={(e) => onUpdate({ email: e.target.value })}
            className={cn(errors.email && 'border-destructive')}
          />
          {errors.email && <p className="text-xs text-destructive">{errors.email}</p>}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="phone">Mobile number</Label>
          <div className="flex gap-2">
            <div className="flex h-9 items-center rounded-md border border-input bg-muted px-3 text-sm text-muted-foreground">
              +91
            </div>
            <Input
              id="phone"
              placeholder="98765 43210"
              value={data.phone}
              onChange={(e) => onUpdate({ phone: e.target.value.replace(/[^0-9\s]/g, '') })}
              className={cn('flex-1', errors.phone && 'border-destructive')}
              maxLength={12}
            />
          </div>
          {errors.phone && <p className="text-xs text-destructive">{errors.phone}</p>}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="password">Password</Label>
          <div className="relative">
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              placeholder="Min 8 characters"
              value={data.password}
              onChange={(e) => onUpdate({ password: e.target.value })}
              className={cn('pr-10', errors.password && 'border-destructive')}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="absolute right-0 top-0 h-full px-3 hover:bg-transparent"
              onClick={() => setShowPassword(!showPassword)}
            >
              {showPassword ? <EyeOff className="h-4 w-4 text-muted-foreground" /> : <Eye className="h-4 w-4 text-muted-foreground" />}
            </Button>
          </div>
          {errors.password && <p className="text-xs text-destructive">{errors.password}</p>}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="confirmPassword">Confirm password</Label>
          <Input
            id="confirmPassword"
            type="password"
            placeholder="Re-enter password"
            value={data.confirmPassword}
            onChange={(e) => onUpdate({ confirmPassword: e.target.value })}
            className={cn(errors.confirmPassword && 'border-destructive')}
          />
          {errors.confirmPassword && <p className="text-xs text-destructive">{errors.confirmPassword}</p>}
        </div>
      </div>

      <div className="flex items-center gap-3 mt-8">
        <div className="flex-1" />
        <Button
          type="submit"
          disabled={isSendingOtp}
          className="gap-1.5"
        >
          {isSendingOtp ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Sending OTP...
            </>
          ) : (
            <>
              Send verification code
              <ArrowRight className="h-4 w-4" />
            </>
          )}
        </Button>
      </div>

      <p className="text-center text-xs text-muted-foreground mt-6">
        Already have an account?{' '}
        <Link to="/brand/login" className="text-primary font-medium hover:underline">
          Sign in
        </Link>
      </p>
    </form>
  );
}

// ===========================
// STEP 2: Company Details
// ===========================

export function CompanyDetailsStep({
  data,
  onUpdate,
  onNext,
  onBack,
}: {
  data: OnboardingData;
  onUpdate: (updates: Partial<OnboardingData>) => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const [isUploadingLogo, setIsUploadingLogo] = React.useState(false);
  const [logoProgress, setLogoProgress] = React.useState(0);

  const generateSlug = (name: string) =>
    name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');

  const handleCompanyNameChange = (name: string) => {
    onUpdate({
      companyName: name,
      companySlug: data.companySlug || generateSlug(name),
    });
  };

  const handleLogoSelect = async (file: File) => {
    const preview = await createLocalPreview(file);
    onUpdate({ logoFile: file, logoPreview: preview, logoUpload: null });

    // Auto-upload to R2
    setIsUploadingLogo(true);
    setLogoProgress(0);
    try {
      const result = await uploadToR2(file, 'brand-logos', (p) => setLogoProgress(p.percentage));
      onUpdate({ logoUpload: result });
    } catch {
      // Upload failed -- file is still selected locally
    } finally {
      setIsUploadingLogo(false);
    }
  };

  const handleLogoRemove = () => {
    onUpdate({ logoFile: null, logoPreview: null, logoUpload: null });
    setLogoProgress(0);
  };

  const validate = () => {
    const e: Record<string, string> = {};
    if (!data.companyName) e.companyName = 'Required';
    if (!data.companySlug) e.companySlug = 'Required';
    else if (!/^[a-z0-9-]+$/.test(data.companySlug)) e.companySlug = 'Lowercase letters, numbers, hyphens only';
    if (!data.industry) e.industry = 'Required';
    if (!data.companySize) e.companySize = 'Required';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (validate()) onNext();
  };

  return (
    <form onSubmit={handleSubmit}>
      <StepHeader
        title="Tell us about your brand"
        subtitle="This helps creators understand who you are"
      />

      <div className="flex flex-col gap-5">
        {/* Logo upload */}
        <div className="flex flex-col gap-1.5">
          <Label>Company logo</Label>
          <FileDropZone
            label="Upload your brand logo"
            accept="image/png,image/jpeg,image/webp,image/svg+xml"
            maxSizeMB={2}
            file={data.logoFile}
            preview={data.logoPreview}
            uploadResult={data.logoUpload}
            isUploading={isUploadingLogo}
            uploadProgress={logoProgress}
            onSelect={handleLogoSelect}
            onRemove={handleLogoRemove}
            isImage
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="companyName">Company name</Label>
          <Input
            id="companyName"
            placeholder="Acme India Pvt Ltd"
            value={data.companyName}
            onChange={(e) => handleCompanyNameChange(e.target.value)}
            className={cn(errors.companyName && 'border-destructive')}
          />
          {errors.companyName && <p className="text-xs text-destructive">{errors.companyName}</p>}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="companySlug">Workspace URL</Label>
          <div className="flex">
            <span className="flex h-9 items-center rounded-l-md border border-r-0 border-input bg-muted px-3 text-sm text-muted-foreground">
              influora.com/
            </span>
            <Input
              id="companySlug"
              placeholder="acme-india"
              value={data.companySlug}
              onChange={(e) => onUpdate({ companySlug: e.target.value.toLowerCase() })}
              className={cn('rounded-l-none', errors.companySlug && 'border-destructive')}
            />
          </div>
          {errors.companySlug && <p className="text-xs text-destructive">{errors.companySlug}</p>}
        </div>

        {/* Workspace type */}
        <div className="flex flex-col gap-1.5">
          <Label>Workspace type</Label>
          <div className="grid gap-3 sm:grid-cols-2">
            {[
              { value: 'BRAND' as WorkspaceType, icon: Building2, title: 'Brand', desc: 'Single company' },
              { value: 'AGENCY' as WorkspaceType, icon: Users, title: 'Agency', desc: 'Multiple clients' },
            ].map(({ value, icon: Icon, title, desc }) => (
              <button
                key={value}
                type="button"
                className={cn(
                  'flex items-center gap-3 rounded-lg border-2 p-3.5 text-left transition-all',
                  data.workspaceType === value
                    ? 'border-primary bg-primary/5'
                    : 'border-border hover:border-primary/30',
                )}
                onClick={() => onUpdate({ workspaceType: value })}
              >
                <div className={cn(
                  'flex h-9 w-9 items-center justify-center rounded-lg',
                  data.workspaceType === value ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground',
                )}>
                  <Icon className="h-4 w-4" />
                </div>
                <div>
                  <p className="text-sm font-medium text-foreground">{title}</p>
                  <p className="text-xs text-muted-foreground">{desc}</p>
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label>Industry</Label>
            <Select value={data.industry} onValueChange={(v) => onUpdate({ industry: v })}>
              <SelectTrigger className={cn(errors.industry && 'border-destructive')}>
                <SelectValue placeholder="Select" />
              </SelectTrigger>
              <SelectContent>
                {industries.map((i) => (
                  <SelectItem key={i} value={i}>{i}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            {errors.industry && <p className="text-xs text-destructive">{errors.industry}</p>}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Company size</Label>
            <Select value={data.companySize} onValueChange={(v) => onUpdate({ companySize: v })}>
              <SelectTrigger className={cn(errors.companySize && 'border-destructive')}>
                <SelectValue placeholder="Select" />
              </SelectTrigger>
              <SelectContent>
                {companySizes.map((s) => (
                  <SelectItem key={s} value={s}>{s}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            {errors.companySize && <p className="text-xs text-destructive">{errors.companySize}</p>}
          </div>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="websiteUrl">Website (optional)</Label>
          <Input
            id="websiteUrl"
            type="url"
            placeholder="https://www.example.com"
            value={data.websiteUrl}
            onChange={(e) => onUpdate({ websiteUrl: e.target.value })}
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="description">Short bio (optional)</Label>
          <Textarea
            id="description"
            placeholder="A few words about your brand..."
            value={data.description}
            onChange={(e) => onUpdate({ description: e.target.value })}
            rows={2}
          />
        </div>
      </div>

      <StepFooter onBack={onBack} />
    </form>
  );
}

// ===========================
// STEP 3: Verification (GSTIN / PAN) -- OPTIONAL at signup
// Per Brand Guide: "GSTIN + PAN required only at first campaign launch, not at signup."
// ===========================

export function VerificationDocsStep({
  data,
  onUpdate,
  onNext,
  onBack,
}: {
  data: OnboardingData;
  onUpdate: (updates: Partial<OnboardingData>) => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const [isUploadingGstin, setIsUploadingGstin] = React.useState(false);
  const [gstinProgress, setGstinProgress] = React.useState(0);
  const [isUploadingPan, setIsUploadingPan] = React.useState(false);
  const [panProgress, setPanProgress] = React.useState(0);
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  const handleGstinDocSelect = async (file: File) => {
    const preview = await createLocalPreview(file);
    onUpdate({ gstinDoc: file, gstinDocPreview: preview, gstinDocUpload: null });
    setIsUploadingGstin(true);
    setGstinProgress(0);
    try {
      const result = await uploadToR2(file, 'verification/gstin', (p) => setGstinProgress(p.percentage));
      onUpdate({ gstinDocUpload: result });
    } catch { /* noop */ } finally {
      setIsUploadingGstin(false);
    }
  };

  const handlePanDocSelect = async (file: File) => {
    const preview = await createLocalPreview(file);
    onUpdate({ panDoc: file, panDocPreview: preview, panDocUpload: null });
    setIsUploadingPan(true);
    setPanProgress(0);
    try {
      const result = await uploadToR2(file, 'verification/pan', (p) => setPanProgress(p.percentage));
      onUpdate({ panDocUpload: result });
    } catch { /* noop */ } finally {
      setIsUploadingPan(false);
    }
  };

  const validate = () => {
    const e: Record<string, string> = {};
    if (data.gstin && !/^\d{2}[A-Z]{5}\d{4}[A-Z]{1}[A-Z\d]{1}[Z]{1}[A-Z\d]{1}$/.test(data.gstin)) {
      e.gstin = 'Invalid GSTIN format';
    }
    if (data.pan && !/^[A-Z]{5}\d{4}[A-Z]{1}$/.test(data.pan)) {
      e.pan = 'Invalid PAN format';
    }
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handleContinue = () => {
    if (validate()) onNext();
  };

  const hasAnyData = !!(data.gstin || data.pan || data.gstinDoc || data.panDoc);

  return (
    <div>
      <StepHeader
        title="Business verification"
        subtitle="Optional now -- you can complete this later from Settings or before your first campaign."
      />

      <div className="flex flex-col gap-6">
        {/* Optional callout */}
        <div className="flex items-start gap-3 rounded-lg border border-primary/30 bg-primary/5 p-3.5">
          <SkipForward className="h-5 w-5 text-primary mt-0.5 shrink-0" />
          <div>
            <p className="text-sm font-medium text-foreground">This step is optional</p>
            <p className="text-xs text-muted-foreground mt-0.5 leading-relaxed">
              GSTIN and PAN are only required before you launch your first campaign.
              You can skip now and add them anytime from Settings.
            </p>
          </div>
        </div>

        {/* Verification benefit badge */}
        <div className="flex items-start gap-3 rounded-lg border border-success/30 bg-success/5 p-3.5">
          <ShieldCheck className="h-5 w-5 text-success mt-0.5 shrink-0" />
          <div>
            <p className="text-sm font-medium text-foreground">Why verify?</p>
            <ul className="text-xs text-muted-foreground mt-1 leading-relaxed flex flex-col gap-0.5">
              <li>Verified brands pay <span className="font-semibold text-foreground">10% platform fee</span> (vs 12% unverified)</li>
              <li>Verified badge visible to creators -- builds trust</li>
            </ul>
          </div>
        </div>

        {/* GSTIN */}
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="gstin">GSTIN <span className="text-muted-foreground font-normal">(optional)</span></Label>
            <Input
              id="gstin"
              placeholder="22AAAAA0000A1Z5"
              value={data.gstin}
              onChange={(e) => onUpdate({ gstin: e.target.value.toUpperCase() })}
              className={cn('font-mono text-sm', errors.gstin && 'border-destructive')}
              maxLength={15}
            />
            {errors.gstin && <p className="text-xs text-destructive">{errors.gstin}</p>}
          </div>
          <FileDropZone
            label="Upload GSTIN certificate"
            accept="image/png,image/jpeg,application/pdf"
            maxSizeMB={5}
            file={data.gstinDoc}
            preview={data.gstinDocPreview}
            uploadResult={data.gstinDocUpload}
            isUploading={isUploadingGstin}
            uploadProgress={gstinProgress}
            onSelect={handleGstinDocSelect}
            onRemove={() => onUpdate({ gstinDoc: null, gstinDocPreview: null, gstinDocUpload: null })}
          />
        </div>

        {/* PAN */}
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="pan">PAN <span className="text-muted-foreground font-normal">(optional)</span></Label>
            <Input
              id="pan"
              placeholder="AAAAA1234A"
              value={data.pan}
              onChange={(e) => onUpdate({ pan: e.target.value.toUpperCase() })}
              className={cn('font-mono text-sm', errors.pan && 'border-destructive')}
              maxLength={10}
            />
            {errors.pan && <p className="text-xs text-destructive">{errors.pan}</p>}
          </div>
          <FileDropZone
            label="Upload PAN card"
            accept="image/png,image/jpeg,application/pdf"
            maxSizeMB={5}
            file={data.panDoc}
            preview={data.panDocPreview}
            uploadResult={data.panDocUpload}
            isUploading={isUploadingPan}
            uploadProgress={panProgress}
            onSelect={handlePanDocSelect}
            onRemove={() => onUpdate({ panDoc: null, panDocPreview: null, panDocUpload: null })}
          />
        </div>

        {/* Terms */}
        <div className="flex flex-col gap-3 pt-2 border-t border-border">
          <div className="flex items-start gap-2.5">
            <Checkbox
              id="acceptTerms"
              checked={data.acceptTerms}
              onCheckedChange={(c) => onUpdate({ acceptTerms: c === true })}
              className="mt-0.5"
            />
            <Label htmlFor="acceptTerms" className="text-sm font-normal leading-relaxed cursor-pointer">
              I agree to the{' '}
              <Link to="/terms" className="text-primary hover:underline">Terms of Service</Link>
              {' '}and{' '}
              <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link>
            </Label>
          </div>
        </div>
      </div>

      <div className="flex items-center gap-3 mt-8">
        <Button type="button" variant="outline" onClick={onBack} className="gap-1.5">
          <ArrowLeft className="h-4 w-4" />
          Back
        </Button>
        <div className="flex-1" />
        {!hasAnyData && (
          <Button
            type="button"
            variant="ghost"
            onClick={() => onNext()}
            className="text-muted-foreground gap-1.5"
          >
            <SkipForward className="h-4 w-4" />
            Skip for now
          </Button>
        )}
        <Button
          type="button"
          onClick={handleContinue}
          disabled={!data.acceptTerms}
          className="gap-1.5"
        >
          {hasAnyData ? 'Save & Continue' : 'Continue'}
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>

      <p className="text-center text-xs text-muted-foreground mt-4">
        You can complete verification later from Settings
      </p>
    </div>
  );
}

// ===========================
// STEP 4: Team Setup
// ===========================

export function TeamSetupStep({
  data,
  onUpdate,
  onNext,
  onBack,
}: {
  data: OnboardingData;
  onUpdate: (updates: Partial<OnboardingData>) => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const [email, setEmail] = React.useState('');
  const [role, setRole] = React.useState<MemberRole>('MEMBER');
  const [error, setError] = React.useState('');

  const addMember = () => {
    if (!email) { setError('Email required'); return; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { setError('Invalid email'); return; }
    if (data.teamMembers.some((m) => m.email === email)) { setError('Already added'); return; }
    onUpdate({ teamMembers: [...data.teamMembers, { email, role }] });
    setEmail('');
    setRole('MEMBER');
    setError('');
  };

  const removeMember = (memberEmail: string) => {
    onUpdate({ teamMembers: data.teamMembers.filter((m) => m.email !== memberEmail) });
  };

  return (
    <div>
      <StepHeader
        title="Invite your team"
        subtitle="Collaborate on campaigns together. You can always add more later."
      />

      <div className="flex flex-col gap-5">
        {/* Add form */}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <div className="flex-1 flex flex-col gap-1.5">
            <Label htmlFor="teamEmail">Email</Label>
            <Input
              id="teamEmail"
              type="email"
              placeholder="colleague@company.com"
              value={email}
              onChange={(e) => { setEmail(e.target.value); setError(''); }}
              className={cn(error && 'border-destructive')}
              onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addMember())}
            />
            {error && <p className="text-xs text-destructive">{error}</p>}
          </div>
          <Select value={role} onValueChange={(v) => setRole(v as MemberRole)}>
            <SelectTrigger className="w-full sm:w-32">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {teamRoles.map((r) => (
                <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button type="button" onClick={addMember} variant="secondary" className="shrink-0">
            <Plus className="h-4 w-4 sm:mr-1.5" />
            <span className="hidden sm:inline">Add</span>
          </Button>
        </div>

        {/* Member list */}
        {data.teamMembers.length > 0 ? (
          <div className="flex flex-col gap-2">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              {data.teamMembers.length} member{data.teamMembers.length !== 1 ? 's' : ''} invited
            </p>
            {data.teamMembers.map((m) => (
              <div
                key={m.email}
                className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2.5"
              >
                <div className="flex items-center gap-2.5 min-w-0">
                  <Avatar className="h-8 w-8">
                    <AvatarFallback className="bg-primary/10 text-primary text-xs">
                      {m.email.charAt(0).toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                  <div className="min-w-0">
                    <p className="text-sm font-medium truncate text-foreground">{m.email}</p>
                    <p className="text-xs text-muted-foreground">
                      {teamRoles.find((r) => r.value === m.role)?.label}
                    </p>
                  </div>
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-7 w-7 shrink-0"
                  onClick={() => removeMember(m.email)}
                >
                  <X className="h-3.5 w-3.5" />
                </Button>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-border py-10 text-center">
            <Mail className="h-8 w-8 text-muted-foreground/40" />
            <p className="text-sm font-medium text-muted-foreground mt-3">No members yet</p>
            <p className="text-xs text-muted-foreground/70 mt-0.5">Add members above or skip this step</p>
          </div>
        )}
      </div>

      <StepFooter
        onBack={onBack}
        onNext={onNext}
        nextLabel={data.teamMembers.length > 0 ? 'Continue' : 'Skip for now'}
        showSkip={data.teamMembers.length > 0}
        onSkip={onNext}
        skipLabel="Skip"
      />
    </div>
  );
}

// ===========================
// STEP 5: Trust Primer (Escrow Explainer)
// ===========================

export function TrustPrimerStep({
  onNext,
  onBack,
}: {
  onNext: () => void;
  onBack: () => void;
}) {
  const escrowSteps = [
    {
      num: '01',
      title: 'Fund your wallet',
      desc: 'Add funds to your Influora wallet. Money stays yours until you approve work.',
      color: 'bg-primary/10 text-primary',
    },
    {
      num: '02',
      title: 'Escrow locks on contract',
      desc: 'When you accept a proposal, the agreed amount moves to secure escrow -- visible to both sides.',
      color: 'bg-warning/10 text-warning',
    },
    {
      num: '03',
      title: 'Creator delivers content',
      desc: 'The creator produces content per your brief. Review drafts, request up to 2 revisions.',
      color: 'bg-chart-4/10 text-chart-4',
    },
    {
      num: '04',
      title: 'You approve, payment releases',
      desc: 'Only when you approve the final deliverable does payment release to the creator.',
      color: 'bg-success/10 text-success',
    },
  ];

  return (
    <div>
      <StepHeader
        title="How your money is protected"
        subtitle="Every rupee is held in escrow until you approve the work"
      />

      <div className="flex flex-col gap-3">
        {escrowSteps.map((step, idx) => (
          <div key={idx} className="flex items-start gap-4 rounded-lg border border-border p-4">
            <div className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-sm font-bold', step.color)}>
              {step.num}
            </div>
            <div>
              <p className="text-sm font-medium text-foreground">{step.title}</p>
              <p className="text-xs text-muted-foreground mt-0.5 leading-relaxed">{step.desc}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-start gap-2.5 rounded-lg border border-success/30 bg-success/5 p-3.5 mt-6">
        <ShieldCheck className="h-5 w-5 text-success mt-0.5 shrink-0" />
        <div>
          <p className="text-sm font-medium text-foreground">RBI-compliant escrow</p>
          <p className="text-xs text-muted-foreground mt-0.5">
            Disputes are mediated by our team. Your money is protected with bank-grade security.
          </p>
        </div>
      </div>

      <StepFooter onBack={onBack} onNext={onNext} nextLabel="Continue to Wallet" />
    </div>
  );
}

// ===========================
// STEP 6: Wallet Funding
// ===========================

export function WalletFundingStep({
  data,
  onUpdate,
  onComplete,
  onBack,
  isSubmitting,
}: {
  data: OnboardingData;
  onUpdate: (updates: Partial<OnboardingData>) => void;
  onComplete: () => void;
  onBack: () => void;
  isSubmitting: boolean;
}) {
  const [selectedPreset, setSelectedPreset] = React.useState<number | null>(null);

  const presets = [
    { amount: 25000, label: '25K', desc: '2-3 small collabs' },
    { amount: 50000, label: '50K', desc: '3-5 collabs', recommended: true },
    { amount: 100000, label: '1L', desc: '5-10 collabs' },
    { amount: 500000, label: '5L', desc: 'Large campaigns' },
  ];

  const handlePreset = (p: typeof presets[0]) => {
    setSelectedPreset(p.amount);
    onUpdate({ walletAmount: p.amount.toString() });
  };

  const amount = parseInt(data.walletAmount) || 0;

  return (
    <div>
      <StepHeader
        title="Fund your wallet"
        subtitle="Add funds to start collaborating. Your first 2 deals are free (0% platform fee)."
      />

      <div className="flex flex-col gap-5">
        {/* Free banner */}
        <div className="flex items-center gap-2.5 rounded-lg border border-primary/30 bg-primary/5 px-4 py-3">
          <Badge variant="secondary" className="shrink-0 bg-primary/10 text-primary border-0 text-xs font-semibold">
            FREE
          </Badge>
          <p className="text-sm text-foreground">
            Your first 2 campaigns have <span className="font-semibold">zero platform fee</span>
          </p>
        </div>

        {/* Preset amounts */}
        <div className="grid grid-cols-2 gap-3">
          {presets.map((p) => (
            <button
              key={p.amount}
              type="button"
              className={cn(
                'relative flex flex-col items-start rounded-lg border-2 p-4 text-left transition-all',
                selectedPreset === p.amount
                  ? 'border-primary bg-primary/5'
                  : 'border-border hover:border-primary/30',
              )}
              onClick={() => handlePreset(p)}
            >
              {p.recommended && (
                <Badge className="absolute -top-2.5 right-2 text-[10px] px-1.5 py-0">
                  Recommended
                </Badge>
              )}
              <span className="text-lg font-bold text-foreground">{p.label}</span>
              <span className="text-xs text-muted-foreground">{p.desc}</span>
            </button>
          ))}
        </div>

        {/* Custom amount */}
        <div className="flex flex-col gap-1.5">
          <Label>Or enter custom amount</Label>
          <div className="flex items-center gap-2">
            <span className="flex h-9 items-center rounded-md border border-input bg-muted px-3 text-sm text-muted-foreground font-medium">
              INR
            </span>
            <Input
              type="number"
              placeholder="Enter amount"
              value={data.walletAmount}
              onChange={(e) => {
                onUpdate({ walletAmount: e.target.value });
                setSelectedPreset(null);
              }}
              className="flex-1"
              min={10000}
            />
          </div>
          <p className="text-xs text-muted-foreground">Minimum: INR 10,000</p>
        </div>
      </div>

      <div className="flex items-center gap-3 mt-8">
        <Button type="button" variant="outline" onClick={onBack} className="gap-1.5">
          <ArrowLeft className="h-4 w-4" />
          Back
        </Button>
        <div className="flex-1" />
        <Button type="button" variant="ghost" onClick={onComplete} className="text-muted-foreground">
          Skip for now
        </Button>
        <Button
          type="button"
          onClick={onComplete}
          disabled={isSubmitting || amount < 10000}
          className="gap-1.5"
        >
          {isSubmitting ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Setting up...
            </>
          ) : amount >= 10000 ? (
            <>
              {'Add \u20B9'}
              {amount.toLocaleString('en-IN')}
              <ArrowRight className="h-4 w-4" />
            </>
          ) : (
            <>
              Add Funds
              <ArrowRight className="h-4 w-4" />
            </>
          )}
        </Button>
      </div>
    </div>
  );
}
