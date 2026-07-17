'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
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
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { useAuthStore } from '@/lib/store';
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Checkbox } from '@/components/ui/checkbox';

interface OnboardingData {
  // Step 1: Account
  email: string;
  password: string;
  confirmPassword: string;
  firstName: string;
  lastName: string;
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
  // Step 3: Team
  teamMembers: Array<{
    email: string;
    role: MemberRole;
  }>;
  // Step 4: Verification
  acceptTerms: boolean;
  acceptPrivacy: boolean;
  subscribeNewsletter: boolean;
}

const initialData: OnboardingData = {
  email: '',
  password: '',
  confirmPassword: '',
  firstName: '',
  lastName: '',
  companyName: '',
  companySlug: '',
  workspaceType: 'BRAND',
  industry: '',
  companySize: '',
  websiteUrl: '',
  description: '',
  logoFile: null,
  logoPreview: null,
  teamMembers: [],
  acceptTerms: false,
  acceptPrivacy: false,
  subscribeNewsletter: false,
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

// Step 1: Account Setup
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
  const [showConfirmPassword, setShowConfirmPassword] = React.useState(false);
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  const validate = () => {
    const newErrors: Record<string, string> = {};

    if (!data.email) newErrors.email = 'Email is required';
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(data.email)) {
      newErrors.email = 'Invalid email address';
    }

    if (!data.password) newErrors.password = 'Password is required';
    else if (data.password.length < 8) {
      newErrors.password = 'Password must be at least 8 characters';
    }

    if (!data.confirmPassword) {
      newErrors.confirmPassword = 'Please confirm your password';
    } else if (data.password !== data.confirmPassword) {
      newErrors.confirmPassword = 'Passwords do not match';
    }

    if (!data.firstName) newErrors.firstName = 'First name is required';
    if (!data.lastName) newErrors.lastName = 'Last name is required';

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (validate()) {
      onNext();
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Create your account</h1>
        <p className="text-muted-foreground">
          Start managing your creator collaborations in minutes
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="firstName">First name</Label>
          <Input
            id="firstName"
            placeholder="John"
            value={data.firstName}
            onChange={(e) => onUpdate({ firstName: e.target.value })}
            className={cn(errors.firstName && 'border-destructive')}
          />
          {errors.firstName && (
            <p className="text-xs text-destructive">{errors.firstName}</p>
          )}
        </div>
        <div className="space-y-2">
          <Label htmlFor="lastName">Last name</Label>
          <Input
            id="lastName"
            placeholder="Doe"
            value={data.lastName}
            onChange={(e) => onUpdate({ lastName: e.target.value })}
            className={cn(errors.lastName && 'border-destructive')}
          />
          {errors.lastName && (
            <p className="text-xs text-destructive">{errors.lastName}</p>
          )}
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="email">Work email</Label>
        <Input
          id="email"
          type="email"
          placeholder="john@company.com"
          value={data.email}
          onChange={(e) => onUpdate({ email: e.target.value })}
          className={cn(errors.email && 'border-destructive')}
        />
        {errors.email && (
          <p className="text-xs text-destructive">{errors.email}</p>
        )}
      </div>

      <div className="space-y-2">
        <Label htmlFor="password">Password</Label>
        <div className="relative">
          <Input
            id="password"
            type={showPassword ? 'text' : 'password'}
            placeholder="Create a strong password"
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
            {showPassword ? (
              <EyeOff className="h-4 w-4 text-muted-foreground" />
            ) : (
              <Eye className="h-4 w-4 text-muted-foreground" />
            )}
          </Button>
        </div>
        {errors.password && (
          <p className="text-xs text-destructive">{errors.password}</p>
        )}
      </div>

      <div className="space-y-2">
        <Label htmlFor="confirmPassword">Confirm password</Label>
        <div className="relative">
          <Input
            id="confirmPassword"
            type={showConfirmPassword ? 'text' : 'password'}
            placeholder="Confirm your password"
            value={data.confirmPassword}
            onChange={(e) => onUpdate({ confirmPassword: e.target.value })}
            className={cn('pr-10', errors.confirmPassword && 'border-destructive')}
          />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="absolute right-0 top-0 h-full px-3 hover:bg-transparent"
            onClick={() => setShowConfirmPassword(!showConfirmPassword)}
          >
            {showConfirmPassword ? (
              <EyeOff className="h-4 w-4 text-muted-foreground" />
            ) : (
              <Eye className="h-4 w-4 text-muted-foreground" />
            )}
          </Button>
        </div>
        {errors.confirmPassword && (
          <p className="text-xs text-destructive">{errors.confirmPassword}</p>
        )}
      </div>

      <Button type="submit" className="w-full" size="lg">
        Continue
        <ArrowRight className="ml-2 h-4 w-4" />
      </Button>

      <p className="text-center text-sm text-muted-foreground">
        Already have an account?{' '}
        <a href="/login" className="text-primary hover:underline">
          Sign in
        </a>
      </p>
    </form>
  );
}

// Step 2: Company Details
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
  const fileInputRef = React.useRef<HTMLInputElement>(null);

  const handleLogoUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onloadend = () => {
        onUpdate({
          logoFile: file,
          logoPreview: reader.result as string,
        });
      };
      reader.readAsDataURL(file);
    }
  };

  const removeLogo = () => {
    onUpdate({ logoFile: null, logoPreview: null });
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const generateSlug = (name: string) => {
    return name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '');
  };

  const handleCompanyNameChange = (name: string) => {
    onUpdate({
      companyName: name,
      companySlug: data.companySlug || generateSlug(name),
    });
  };

  const validate = () => {
    const newErrors: Record<string, string> = {};

    if (!data.companyName) newErrors.companyName = 'Company name is required';
    if (!data.companySlug) newErrors.companySlug = 'URL slug is required';
    else if (!/^[a-z0-9-]+$/.test(data.companySlug)) {
      newErrors.companySlug = 'Slug can only contain lowercase letters, numbers, and hyphens';
    }
    if (!data.industry) newErrors.industry = 'Industry is required';
    if (!data.companySize) newErrors.companySize = 'Company size is required';

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (validate()) {
      onNext();
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Company details</h1>
        <p className="text-muted-foreground">
          Tell us about your brand to personalize your experience
        </p>
      </div>

      {/* Logo Upload */}
      <div className="space-y-2">
        <Label>Company logo</Label>
        <div className="flex items-center gap-4">
          <div className="relative">
            {data.logoPreview ? (
              <div className="relative">
                <Avatar className="h-20 w-20">
                  <AvatarImage src={data.logoPreview} />
                  <AvatarFallback>{data.companyName?.charAt(0) || 'C'}</AvatarFallback>
                </Avatar>
                <Button
                  type="button"
                  variant="destructive"
                  size="icon"
                  className="absolute -right-2 -top-2 h-6 w-6"
                  onClick={removeLogo}
                >
                  <X className="h-3 w-3" />
                </Button>
              </div>
            ) : (
              <div
                className="flex h-20 w-20 cursor-pointer items-center justify-center rounded-xl border-2 border-dashed border-muted-foreground/30 bg-muted/50 transition-colors hover:border-primary hover:bg-muted"
                onClick={() => fileInputRef.current?.click()}
              >
                <Upload className="h-6 w-6 text-muted-foreground" />
              </div>
            )}
          </div>
          <div className="space-y-1">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => fileInputRef.current?.click()}
            >
              Upload logo
            </Button>
            <p className="text-xs text-muted-foreground">
              PNG, JPG up to 2MB. Recommended: 400x400px
            </p>
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            className="hidden"
            onChange={handleLogoUpload}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="companyName">Company name</Label>
        <Input
          id="companyName"
          placeholder="Acme Inc"
          value={data.companyName}
          onChange={(e) => handleCompanyNameChange(e.target.value)}
          className={cn(errors.companyName && 'border-destructive')}
        />
        {errors.companyName && (
          <p className="text-xs text-destructive">{errors.companyName}</p>
        )}
      </div>

      <div className="space-y-2">
        <Label htmlFor="companySlug">Workspace URL</Label>
        <div className="flex items-center">
          <span className="flex h-9 items-center rounded-l-md border border-r-0 border-input bg-muted px-3 text-sm text-muted-foreground">
            collabos.app/
          </span>
          <Input
            id="companySlug"
            placeholder="acme-inc"
            value={data.companySlug}
            onChange={(e) => onUpdate({ companySlug: e.target.value.toLowerCase() })}
            className={cn('rounded-l-none', errors.companySlug && 'border-destructive')}
          />
        </div>
        {errors.companySlug && (
          <p className="text-xs text-destructive">{errors.companySlug}</p>
        )}
      </div>

      {/* Workspace Type */}
      <div className="space-y-2">
        <Label>Workspace type</Label>
        <div className="grid gap-3 sm:grid-cols-2">
          <Card
            className={cn(
              'cursor-pointer transition-colors',
              data.workspaceType === 'BRAND' && 'border-primary bg-primary/5'
            )}
            onClick={() => onUpdate({ workspaceType: 'BRAND' })}
          >
            <CardContent className="flex items-center gap-3 p-4">
              <div className={cn(
                'flex h-10 w-10 items-center justify-center rounded-lg',
                data.workspaceType === 'BRAND' ? 'bg-primary text-primary-foreground' : 'bg-muted'
              )}>
                <Building2 className="h-5 w-5" />
              </div>
              <div>
                <p className="font-medium">Brand</p>
                <p className="text-xs text-muted-foreground">Single company</p>
              </div>
            </CardContent>
          </Card>
          <Card
            className={cn(
              'cursor-pointer transition-colors',
              data.workspaceType === 'AGENCY' && 'border-primary bg-primary/5'
            )}
            onClick={() => onUpdate({ workspaceType: 'AGENCY' })}
          >
            <CardContent className="flex items-center gap-3 p-4">
              <div className={cn(
                'flex h-10 w-10 items-center justify-center rounded-lg',
                data.workspaceType === 'AGENCY' ? 'bg-primary text-primary-foreground' : 'bg-muted'
              )}>
                <Users className="h-5 w-5" />
              </div>
              <div>
                <p className="font-medium">Agency</p>
                <p className="text-xs text-muted-foreground">Multiple clients</p>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="industry">Industry</Label>
          <Select
            value={data.industry}
            onValueChange={(value) => onUpdate({ industry: value })}
          >
            <SelectTrigger className={cn(errors.industry && 'border-destructive')}>
              <SelectValue placeholder="Select industry" />
            </SelectTrigger>
            <SelectContent>
              {industries.map((industry) => (
                <SelectItem key={industry} value={industry}>
                  {industry}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {errors.industry && (
            <p className="text-xs text-destructive">{errors.industry}</p>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="companySize">Company size</Label>
          <Select
            value={data.companySize}
            onValueChange={(value) => onUpdate({ companySize: value })}
          >
            <SelectTrigger className={cn(errors.companySize && 'border-destructive')}>
              <SelectValue placeholder="Select size" />
            </SelectTrigger>
            <SelectContent>
              {companySizes.map((size) => (
                <SelectItem key={size} value={size}>
                  {size}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {errors.companySize && (
            <p className="text-xs text-destructive">{errors.companySize}</p>
          )}
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="websiteUrl">Website URL (optional)</Label>
        <Input
          id="websiteUrl"
          type="url"
          placeholder="https://www.example.com"
          value={data.websiteUrl}
          onChange={(e) => onUpdate({ websiteUrl: e.target.value })}
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="description">Company description (optional)</Label>
        <Textarea
          id="description"
          placeholder="Tell creators about your brand..."
          value={data.description}
          onChange={(e) => onUpdate({ description: e.target.value })}
          rows={3}
        />
      </div>

      <div className="flex gap-3">
        <Button type="button" variant="outline" onClick={onBack} className="flex-1">
          <ArrowLeft className="mr-2 h-4 w-4" />
          Back
        </Button>
        <Button type="submit" className="flex-1">
          Continue
          <ArrowRight className="ml-2 h-4 w-4" />
        </Button>
      </div>
    </form>
  );
}

// Step 3: Team Setup
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
  const [newMemberEmail, setNewMemberEmail] = React.useState('');
  const [newMemberRole, setNewMemberRole] = React.useState<MemberRole>('MEMBER');
  const [error, setError] = React.useState('');

  const addTeamMember = () => {
    if (!newMemberEmail) {
      setError('Email is required');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(newMemberEmail)) {
      setError('Invalid email address');
      return;
    }
    if (data.teamMembers.some((m) => m.email === newMemberEmail)) {
      setError('This email is already added');
      return;
    }

    onUpdate({
      teamMembers: [...data.teamMembers, { email: newMemberEmail, role: newMemberRole }],
    });
    setNewMemberEmail('');
    setNewMemberRole('MEMBER');
    setError('');
  };

  const removeTeamMember = (email: string) => {
    onUpdate({
      teamMembers: data.teamMembers.filter((m) => m.email !== email),
    });
  };

  const updateMemberRole = (email: string, role: MemberRole) => {
    onUpdate({
      teamMembers: data.teamMembers.map((m) =>
        m.email === email ? { ...m, role } : m
      ),
    });
  };

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Invite your team</h1>
        <p className="text-muted-foreground">
          Collaborate with your team on campaigns and creator management
        </p>
      </div>

      {/* Add Member Form */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">Add team member</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col gap-3 sm:flex-row">
            <div className="flex-1 space-y-2">
              <Input
                type="email"
                placeholder="colleague@company.com"
                value={newMemberEmail}
                onChange={(e) => {
                  setNewMemberEmail(e.target.value);
                  setError('');
                }}
                className={cn(error && 'border-destructive')}
              />
              {error && <p className="text-xs text-destructive">{error}</p>}
            </div>
            <Select
              value={newMemberRole}
              onValueChange={(value) => setNewMemberRole(value as MemberRole)}
            >
              <SelectTrigger className="w-full sm:w-40">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {teamRoles.map((role) => (
                  <SelectItem key={role.value} value={role.value}>
                    {role.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button type="button" onClick={addTeamMember}>
              <Plus className="h-4 w-4 sm:mr-2" />
              <span className="hidden sm:inline">Add</span>
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Team Members List */}
      {data.teamMembers.length > 0 && (
        <div className="space-y-3">
          <p className="text-sm font-medium">
            {data.teamMembers.length} team member{data.teamMembers.length > 1 ? 's' : ''} invited
          </p>
          {data.teamMembers.map((member) => (
            <div
              key={member.email}
              className="flex items-center justify-between gap-4 rounded-lg border border-border p-3"
            >
              <div className="flex items-center gap-3">
                <Avatar className="h-9 w-9">
                  <AvatarFallback className="bg-primary/10 text-primary text-sm">
                    {member.email.charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div>
                  <p className="text-sm font-medium">{member.email}</p>
                  <p className="text-xs text-muted-foreground">
                    {teamRoles.find((r) => r.value === member.role)?.description}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Select
                  value={member.role}
                  onValueChange={(value) => updateMemberRole(member.email, value as MemberRole)}
                >
                  <SelectTrigger className="h-8 w-28">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {teamRoles.map((role) => (
                      <SelectItem key={role.value} value={role.value}>
                        {role.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8"
                  onClick={() => removeTeamMember(member.email)}
                >
                  <X className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {data.teamMembers.length === 0 && (
        <Card className="border-dashed">
          <CardContent className="flex flex-col items-center justify-center py-8 text-center">
            <Mail className="h-10 w-10 text-muted-foreground/50" />
            <p className="mt-3 font-medium">No team members yet</p>
            <p className="text-sm text-muted-foreground">
              Add team members above or skip this step
            </p>
          </CardContent>
        </Card>
      )}

      <div className="flex gap-3">
        <Button type="button" variant="outline" onClick={onBack} className="flex-1">
          <ArrowLeft className="mr-2 h-4 w-4" />
          Back
        </Button>
        <Button type="button" onClick={onNext} className="flex-1">
          {data.teamMembers.length > 0 ? 'Continue' : 'Skip for now'}
          <ArrowRight className="ml-2 h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

// Step 4: Verification & Confirmation
export function VerificationStep({
  data,
  onUpdate,
  onBack,
  onComplete,
  isSubmitting,
}: {
  data: OnboardingData;
  onUpdate: (updates: Partial<OnboardingData>) => void;
  onBack: () => void;
  onComplete: () => void;
  isSubmitting: boolean;
}) {
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  const validate = () => {
    const newErrors: Record<string, string> = {};

    if (!data.acceptTerms) {
      newErrors.acceptTerms = 'You must accept the terms of service';
    }
    if (!data.acceptPrivacy) {
      newErrors.acceptPrivacy = 'You must accept the privacy policy';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (validate()) {
      onComplete();
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Almost there!</h1>
        <p className="text-muted-foreground">
          Review your details and accept our terms to get started
        </p>
      </div>

      {/* Summary Card */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">Account Summary</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-4">
            <Avatar className="h-16 w-16">
              {data.logoPreview ? (
                <AvatarImage src={data.logoPreview} />
              ) : null}
              <AvatarFallback className="bg-primary/10 text-primary text-lg">
                {data.companyName?.charAt(0) || 'C'}
              </AvatarFallback>
            </Avatar>
            <div>
              <p className="font-semibold">{data.companyName}</p>
              <p className="text-sm text-muted-foreground">collabos.app/{data.companySlug}</p>
              <div className="mt-1 flex items-center gap-2">
                <Badge variant="secondary">{data.workspaceType}</Badge>
                <Badge variant="outline">{data.industry}</Badge>
              </div>
            </div>
          </div>

          <div className="space-y-2 border-t border-border pt-4">
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Account owner</span>
              <span>{data.firstName} {data.lastName}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Email</span>
              <span>{data.email}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Company size</span>
              <span>{data.companySize}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Team members</span>
              <span>{data.teamMembers.length} invited</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Terms & Conditions */}
      <div className="space-y-4">
        <div className="flex items-start gap-3">
          <Checkbox
            id="acceptTerms"
            checked={data.acceptTerms}
            onCheckedChange={(checked) => onUpdate({ acceptTerms: checked === true })}
          />
          <div className="space-y-1">
            <Label
              htmlFor="acceptTerms"
              className={cn(
                'text-sm font-normal leading-tight cursor-pointer',
                errors.acceptTerms && 'text-destructive'
              )}
            >
              I agree to the{' '}
              <a href="/terms" className="text-primary hover:underline">
                Terms of Service
              </a>
            </Label>
            {errors.acceptTerms && (
              <p className="text-xs text-destructive">{errors.acceptTerms}</p>
            )}
          </div>
        </div>

        <div className="flex items-start gap-3">
          <Checkbox
            id="acceptPrivacy"
            checked={data.acceptPrivacy}
            onCheckedChange={(checked) => onUpdate({ acceptPrivacy: checked === true })}
          />
          <div className="space-y-1">
            <Label
              htmlFor="acceptPrivacy"
              className={cn(
                'text-sm font-normal leading-tight cursor-pointer',
                errors.acceptPrivacy && 'text-destructive'
              )}
            >
              I agree to the{' '}
              <a href="/privacy" className="text-primary hover:underline">
                Privacy Policy
              </a>
            </Label>
            {errors.acceptPrivacy && (
              <p className="text-xs text-destructive">{errors.acceptPrivacy}</p>
            )}
          </div>
        </div>

        <div className="flex items-start gap-3">
          <Checkbox
            id="subscribeNewsletter"
            checked={data.subscribeNewsletter}
            onCheckedChange={(checked) => onUpdate({ subscribeNewsletter: checked === true })}
          />
          <Label
            htmlFor="subscribeNewsletter"
            className="text-sm font-normal leading-tight cursor-pointer"
          >
            Send me product updates and tips (optional)
          </Label>
        </div>
      </div>

      <div className="flex gap-3">
        <Button
          type="button"
          variant="outline"
          onClick={onBack}
          className="flex-1"
          disabled={isSubmitting}
        >
          <ArrowLeft className="mr-2 h-4 w-4" />
          Back
        </Button>
        <Button type="submit" className="flex-1" disabled={isSubmitting}>
          {isSubmitting ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              Creating workspace...
            </>
          ) : (
            <>
              Complete setup
              <CheckCircle2 className="ml-2 h-4 w-4" />
            </>
          )}
        </Button>
      </div>
    </form>
  );
}

// Main Onboarding Component
export function BrandOnboarding() {
  const router = useRouter();
  const { login } = useAuthStore();
  const [currentStep, setCurrentStep] = React.useState(1);
  const [data, setData] = React.useState<OnboardingData>(initialData);
  const [isSubmitting, setIsSubmitting] = React.useState(false);

  const updateData = (updates: Partial<OnboardingData>) => {
    setData((prev) => ({ ...prev, ...updates }));
  };

  const handleComplete = async () => {
    setIsSubmitting(true);

    // Simulate API call
    await new Promise((resolve) => setTimeout(resolve, 2000));

    // Create mock user and workspace
    const user = {
      id: crypto.randomUUID(),
      email: data.email,
      userType: 'BRAND' as const,
      status: 'ACTIVE' as const,
      emailVerified: false,
      phoneVerified: false,
      displayName: `${data.firstName} ${data.lastName}`,
      firstName: data.firstName,
      lastName: data.lastName,
      createdAt: new Date(),
      updatedAt: new Date(),
    };

    const workspace = {
      id: crypto.randomUUID(),
      name: data.companyName,
      slug: data.companySlug,
      type: data.workspaceType,
      logoUrl: data.logoPreview || undefined,
      websiteUrl: data.websiteUrl || undefined,
      industry: data.industry,
      companySize: data.companySize,
      description: data.description || undefined,
      verificationStatus: 'PENDING' as const,
      createdAt: new Date(),
      updatedAt: new Date(),
    };

    login(user, workspace);
    router.push('/brand');
  };

  return (
    <>
      {currentStep === 1 && (
        <AccountSetupStep
          data={data}
          onUpdate={updateData}
          onNext={() => setCurrentStep(2)}
        />
      )}
      {currentStep === 2 && (
        <CompanyDetailsStep
          data={data}
          onUpdate={updateData}
          onNext={() => setCurrentStep(3)}
          onBack={() => setCurrentStep(1)}
        />
      )}
      {currentStep === 3 && (
        <TeamSetupStep
          data={data}
          onUpdate={updateData}
          onNext={() => setCurrentStep(4)}
          onBack={() => setCurrentStep(2)}
        />
      )}
      {currentStep === 4 && (
        <VerificationStep
          data={data}
          onUpdate={updateData}
          onBack={() => setCurrentStep(3)}
          onComplete={handleComplete}
          isSubmitting={isSubmitting}
        />
      )}
    </>
  );
}

export { initialData, type OnboardingData };
