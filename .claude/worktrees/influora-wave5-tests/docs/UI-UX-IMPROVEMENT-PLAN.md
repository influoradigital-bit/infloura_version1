# Influora — UI/UX Improvement Plan
**Audit Date:** 2026-05-17  
**Auditor:** SaaS Expert Review  
**Product:** Influora — B2B Influencer Marketing Platform  
**Stack:** React 19 + Vite 6 + TypeScript + Tailwind CSS 4 + shadcn/ui + Framer Motion

---

## Overall Score: 7.5 / 10

Strong domain thinking, clean architecture, solid component foundation. Gaps are mostly polish, consistency, and missing wiring — not fundamental design problems. Fixing the high-priority items moves this to 9/10.

---

## Table of Contents

1. [Critical Bugs & Broken Code](#1-critical-bugs--broken-code)
2. [Brand Identity Inconsistency](#2-brand-identity-inconsistency)
3. [Date Picker Issues](#3-date-picker-issues)
4. [Unified Login Page — Brand + Creator Choice](#4-unified-login-page--brand--creator-choice)
5. [Registration Page Redesign — 3D Animation](#5-registration-page-redesign--3d-animation)
6. [Dashboard Improvements](#6-dashboard-improvements)
7. [Navigation Gaps](#7-navigation-gaps)
8. [Color System Fixes](#8-color-system-fixes)
9. [Onboarding Simplification](#9-onboarding-simplification)
10. [Accessibility Fixes](#10-accessibility-fixes)
11. [Mobile Experience](#11-mobile-experience)
12. [React + Vite Advanced Features to Use](#12-react--vite-advanced-features-to-use)
13. [Priority Fix Table](#13-priority-fix-table)

---

## 1. Critical Bugs & Broken Code

### 1.1 Broken "Forgot Password" Link — `brand-login.tsx:120`

**Current (broken):**
```tsx
<a to="#" className="text-sm text-blue-400 hover:text-blue-300">
  Forgot password?
</a>
```
`to` is a React Router prop. On a native `<a>` tag it does nothing. The `href="#"` causes a page jump.

**Fix:**
```tsx
<button
  type="button"
  onClick={() => navigate('/brand/forgot-password')}
  className="text-sm text-blue-400 hover:text-blue-300"
>
  Forgot password?
</button>
```
> Also create `/brand/forgot-password` route with email input + "Send reset link" flow.

---

### 1.2 Notification Bell is Dead — `brand-layout.tsx:258`

The bell icon shows an unread count badge but has no `onClick` handler. Clicking it does nothing. This is a broken affordance — the badge trains users to click and then disappoints them.

**Fix options (choose one):**
- Add a `Popover` dropdown with a notification list
- Navigate to a `/brand/notifications` page
- At minimum, remove the badge until the panel is built

```tsx
// Minimum viable fix — open a notifications sheet
<button
  onClick={() => setNotificationsOpen(true)}
  className="relative p-1.5 hover:bg-accent rounded-lg transition-colors"
  aria-label="View notifications"
>
  <Bell className="h-5 w-5 text-muted-foreground" />
  {unreadCount > 0 && (
    <span className="absolute -top-0.5 -right-0.5 ...">
      {unreadCount > 9 ? '9+' : unreadCount}
    </span>
  )}
</button>
```

---

### 1.3 Wrong Icon in Quick Links — `dashboard-page.tsx:488`

Settings quick link uses a `Users` icon instead of `Settings`:

```tsx
// WRONG — current code
<Button variant="outline" size="sm" ... asChild>
  <Link to="/brand/settings">
    <Users className="h-4 w-4" />   {/* ← Wrong icon */}
    <span className="text-xs">Settings</span>
  </Link>
</Button>

// CORRECT
<Link to="/brand/settings">
  <Settings className="h-4 w-4" />
  <span className="text-xs">Settings</span>
</Link>
```

---

### 1.4 Raw `<input>` and `<select>` Elements — `brand-login.tsx`, `brand-register.tsx`

Brand login and register pages use raw HTML `<input>` and `<select>` with manually written Tailwind classes. The project has a complete `Input`, `Select` component library from shadcn/ui. This creates visual inconsistency.

**Affected files:**
- `src/pages/brand-login.tsx` — lines 75–108 (email, password inputs)
- `src/pages/brand-register.tsx` — lines 122–157 (company name, industry select, team size select)

**Fix:** Replace all raw inputs with `<Input />`, `<Select />` from `@/components/ui/`.

---

### 1.5 Help & Support — Dead Link — `brand-layout.tsx:199`

```tsx
<DropdownMenuItem>
  <HelpCircle className="mr-2 h-4 w-4" />
  Help & Support     {/* No onClick, no href */}
</DropdownMenuItem>
```
Either link to an external docs URL or an internal `/brand/help` page.

---

## 2. Brand Identity Inconsistency

**Problem:** Three different brand identities exist across the same product.

| Screen | Brand Name Shown | Logo/Icon |
|---|---|---|
| `brand-login.tsx` | "Creator OS" | Zap icon, blue-to-cyan gradient |
| `brand-register.tsx` | "Creator OS" | Zap icon, blue-to-cyan gradient |
| `creator-login.tsx` | "Creator OS" | "C" letter, violet gradient |
| `creator-register.tsx` | "Creator OS" | Violet gradient |
| `brand-layout.tsx` (sidebar) | "Influora" | "IN" initials, primary blue |
| `creator-layout.tsx` | Different styling | Different |

**Required fixes:**
1. Replace every instance of `"Creator OS"` with `"Influora"` across all auth pages
2. Replace the `Zap` icon with the same `IN` logo block used in the sidebar
3. Use a single shared `<InfluoraLogo />` component — one source of truth

```tsx
// Create: src/components/shared/influora-logo.tsx
export function InfluoraLogo({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) {
  const sizes = { sm: 'h-7 w-7 text-[10px]', md: 'h-8 w-8 text-xs', lg: 'h-10 w-10 text-sm' };
  return (
    <div className={`flex items-center justify-center rounded-lg bg-primary ${sizes[size]}`}>
      <span className="font-bold text-primary-foreground">IN</span>
    </div>
  );
}
```

---

## 3. Date Picker Issues

### 3.1 Current State
The project uses shadcn/ui's `calendar.tsx` component (Radix UI based) but there is no dedicated date picker wired into campaign creation or deadline fields. The `brand-new-campaign.tsx` form likely uses raw `<input type="date">` which is browser-native and unstyled.

### 3.2 Problems with Native `<input type="date">`
- Renders differently on every OS/browser — breaks design consistency
- No support for date ranges (campaign start → end)
- No min/max date enforcement with visual feedback
- Not keyboard-accessible in the same way as the rest of the UI
- Does not inherit the dark theme

### 3.3 Recommended Fix — shadcn DatePicker + react-day-picker

The `calendar.tsx` component is already in the project (uses `react-day-picker` under the hood). Build a reusable `DatePicker` and `DateRangePicker` wrapper:

```tsx
// src/components/ui/date-picker.tsx
import * as React from 'react';
import { format } from 'date-fns';
import { Calendar as CalendarIcon } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { cn } from '@/lib/utils';

interface DatePickerProps {
  value?: Date;
  onChange: (date: Date | undefined) => void;
  placeholder?: string;
  minDate?: Date;
  maxDate?: Date;
  disabled?: boolean;
}

export function DatePicker({ value, onChange, placeholder = 'Pick a date', minDate, maxDate, disabled }: DatePickerProps) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          disabled={disabled}
          className={cn(
            'w-full justify-start text-left font-normal',
            !value && 'text-muted-foreground'
          )}
        >
          <CalendarIcon className="mr-2 h-4 w-4" />
          {value ? format(value, 'PPP') : placeholder}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0" align="start">
        <Calendar
          mode="single"
          selected={value}
          onSelect={onChange}
          disabled={(date) => {
            if (minDate && date < minDate) return true;
            if (maxDate && date > maxDate) return true;
            return false;
          }}
          initialFocus
        />
      </PopoverContent>
    </Popover>
  );
}
```

**Date Range Picker** for Campaign Start/End:

```tsx
// src/components/ui/date-range-picker.tsx
import { DateRange } from 'react-day-picker';

interface DateRangePickerProps {
  value?: DateRange;
  onChange: (range: DateRange | undefined) => void;
  minDate?: Date;
}

export function DateRangePicker({ value, onChange, minDate }: DateRangePickerProps) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" className={cn('w-full justify-start text-left font-normal', !value && 'text-muted-foreground')}>
          <CalendarIcon className="mr-2 h-4 w-4" />
          {value?.from ? (
            value.to ? (
              `${format(value.from, 'LLL dd')} – ${format(value.to, 'LLL dd, y')}`
            ) : format(value.from, 'LLL dd, y')
          ) : 'Select date range'}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0" align="start">
        <Calendar
          mode="range"
          selected={value}
          onSelect={onChange}
          numberOfMonths={2}
          disabled={(date) => (minDate ? date < minDate : false)}
          initialFocus
        />
      </PopoverContent>
    </Popover>
  );
}
```

**Where to apply:**
| Form | Field | Component |
|---|---|---|
| `brand-new-campaign.tsx` | Campaign start date, end date | `DateRangePicker` |
| `brand-new-campaign.tsx` | Submission deadline | `DatePicker` with `minDate={today}` |
| `brand-edit-campaign.tsx` | All date fields | Same as above |
| Dashboard action items | SLA deadline display | `format(date, 'PPP')` from `date-fns` |
| Wallet page | Transaction date filter | `DateRangePicker` |

---

## 4. Unified Login Page — Brand + Creator Choice

### 4.1 Current Problem
- There are **4 separate auth pages**: `/brand/login`, `/brand/register`, `/creator/login`, `/creator/register`
- No root `/login` or `/` landing page — the app routes to `/brand/login` by default
- First-time visitors have no way to know which portal to enter
- The brand login says "Creator OS" while the sidebar says "Influora" — creates confusion

### 4.2 Proposed Solution — Unified Entry Page at `/`

Create a new `src/pages/landing-entry.tsx` as the root route. This page:
- Shows the **Influora brand identity** front and center
- Presents a **two-card choice**: "I'm a Brand" vs "I'm a Creator"
- Each card describes the value proposition in 1 sentence
- Clicking routes to the respective login

**Routing change in `App.tsx`:**
```tsx
// Before
<Route path="/" element={<Navigate to="/brand/login" />} />

// After
<Route path="/" element={<LandingEntryPage />} />
<Route path="/login" element={<LandingEntryPage />} />
<Route path="/brand/login" element={<BrandLoginPage />} />
<Route path="/creator/login" element={<CreatorLoginPage />} />
```

### 4.3 Layout Design — Unified Entry Page

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│              [IN]  Influora                                 │
│         The Creator Collaboration OS                        │
│                                                             │
│  ┌──────────────────────┐  ┌──────────────────────────┐    │
│  │   🏢  I'm a Brand    │  │   🎥  I'm a Creator      │    │
│  │                      │  │                          │    │
│  │  Launch campaigns,   │  │  Discover deals, sign    │    │
│  │  manage collabs,     │  │  contracts, get paid     │    │
│  │  track performance   │  │  on time                 │    │
│  │                      │  │                          │    │
│  │  [Get Started →]     │  │  [Join Now →]            │    │
│  └──────────────────────┘  └──────────────────────────┘    │
│                                                             │
│         Already have an account?  [Sign In]                 │
│                                                             │
│    Trusted by 500+ brands · 50,000+ creators · RBI Ready   │
└─────────────────────────────────────────────────────────────┘
```

### 4.4 Login Page — Cross-Portal Link

Each login page must link to the other portal at the bottom:
- Brand login: "Are you a creator? → Creator Sign In"
- Creator login: "Are you a brand? → Brand Sign In"

Both pages should also link back to `/` for "Not sure? See options".

---

## 5. Registration Page Redesign — 3D Animation

### 5.1 Current State Problems
- Both `brand-register.tsx` and `creator-register.tsx` are plain flat forms on a dark slate gradient
- No visual engagement — nothing makes a first-time user feel excited to join
- 2-step flow with basic progress bar — minimal
- Raw HTML inputs without design system components
- No value proposition shown during registration

### 5.2 Design Direction — Immersive Split Layout with 3D

**Layout:** Full-screen split — Left panel (40%) animated 3D visual, Right panel (60%) clean form.

**On mobile:** Animation collapses to a compact header band, form takes full screen.

### 5.3 Advanced 3D Techniques Available in This Stack

#### Option A — CSS 3D + Framer Motion (Already Installed — Zero Extra Deps)

Use `framer-motion` (already in `package.json`) with CSS `perspective` and `rotateX/Y` for a floating card scene:

```tsx
import { motion, useMotionValue, useTransform, useSpring } from 'framer-motion';

// Floating 3D card that follows mouse cursor
function FloatingCard({ children }: { children: React.ReactNode }) {
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const rotateX = useTransform(y, [-100, 100], [15, -15]);
  const rotateY = useTransform(x, [-100, 100], [-15, 15]);
  const springRotateX = useSpring(rotateX, { stiffness: 100, damping: 30 });
  const springRotateY = useSpring(rotateY, { stiffness: 100, damping: 30 });

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    x.set(e.clientX - rect.left - rect.width / 2);
    y.set(e.clientY - rect.top - rect.height / 2);
  };
  const handleMouseLeave = () => { x.set(0); y.set(0); };

  return (
    <motion.div
      style={{ rotateX: springRotateX, rotateY: springRotateY, transformPerspective: 1000 }}
      onMouseMove={handleMouseMove}
      onMouseLeave={handleMouseLeave}
      className="relative"
    >
      {children}
    </motion.div>
  );
}
```

**Floating stat cards** orbiting the main panel:
```tsx
// Animated floating metrics — appear with stagger
const floatingStats = [
  { label: '50K+ Creators', delay: 0 },
  { label: '₹10Cr+ Paid', delay: 0.2 },
  { label: '500+ Brands', delay: 0.4 },
];

{floatingStats.map((stat, i) => (
  <motion.div
    key={stat.label}
    initial={{ opacity: 0, y: 20 }}
    animate={{ opacity: 1, y: [0, -8, 0] }}
    transition={{ delay: stat.delay, duration: 3, repeat: Infinity, ease: 'easeInOut' }}
    className="absolute bg-card/80 backdrop-blur-sm border border-border rounded-xl px-4 py-2 text-sm font-medium shadow-xl"
  >
    {stat.label}
  </motion.div>
))}
```

#### Option B — Three.js via @react-three/fiber (New Dependency — Most Impressive)

Add `@react-three/fiber` + `@react-three/drei` for a WebGL 3D scene:

```bash
pnpm add @react-three/fiber @react-three/drei three
pnpm add -D @types/three
```

Scene idea for registration left panel: Floating sphere made of connection nodes (creators + brands) with animated lines between them — visualizes the collaboration network.

```tsx
import { Canvas } from '@react-three/fiber';
import { Sphere, MeshDistortMaterial, Float, Stars } from '@react-three/drei';

function NetworkSphere() {
  return (
    <Canvas camera={{ position: [0, 0, 5] }}>
      <ambientLight intensity={0.5} />
      <pointLight position={[10, 10, 10]} />
      <Stars radius={50} depth={10} count={2000} factor={2} />
      <Float speed={2} rotationIntensity={1} floatIntensity={1}>
        <Sphere args={[1.5, 64, 64]}>
          <MeshDistortMaterial
            color="#6366f1"
            attach="material"
            distort={0.4}
            speed={2}
            roughness={0.2}
            metalness={0.8}
          />
        </Sphere>
      </Float>
    </Canvas>
  );
}
```

#### Option C — CSS-only 3D Gradient Orb (Lightest — No JS)

For a lightweight option that still looks premium:

```css
/* Animated gradient orb using CSS only */
.registration-orb {
  width: 400px;
  height: 400px;
  border-radius: 50%;
  background: conic-gradient(
    from 0deg,
    oklch(0.65 0.22 260),
    oklch(0.60 0.22 300),
    oklch(0.65 0.22 260)
  );
  filter: blur(40px);
  animation: orb-spin 8s linear infinite;
  transform: translateZ(0);
}

@keyframes orb-spin {
  from { transform: rotate(0deg) scale(1); }
  50%  { transform: rotate(180deg) scale(1.1); }
  to   { transform: rotate(360deg) scale(1); }
}
```

### 5.4 Registration Form Improvements

**Brand Registration — Improved Flow:**

| Step | Current | Improved |
|---|---|---|
| 1 | Company name, industry, team size | Full name, Company name, Role (CTA: industry-autofill from company) |
| 2 | Email + password + confirm | Email, password with strength meter, agree to terms |
| — | Missing | Password strength indicator (weak / fair / strong / excellent) |
| — | Missing | Real-time email validation (debounced) |
| — | Missing | Show what they're getting (3 feature cards visible during form fill) |

**Password Strength Meter (add to both Brand + Creator register):**
```tsx
function PasswordStrength({ password }: { password: string }) {
  const strength = getPasswordStrength(password); // 0-4
  const labels = ['', 'Weak', 'Fair', 'Strong', 'Excellent'];
  const colors = ['', 'bg-red-500', 'bg-orange-500', 'bg-blue-500', 'bg-green-500'];

  if (!password) return null;
  return (
    <div className="mt-2 space-y-1">
      <div className="flex gap-1">
        {[1,2,3,4].map(i => (
          <div key={i} className={cn('h-1 flex-1 rounded-full transition-colors', i <= strength ? colors[strength] : 'bg-muted')} />
        ))}
      </div>
      <p className="text-xs text-muted-foreground">{labels[strength]}</p>
    </div>
  );
}
```

**Creator Registration — Improved Flow:**

Creators are mobile-first users. Their flow should be:
1. **Platform connect** — Instagram / YouTube OAuth (they are already familiar)
2. **Name + phone** — pre-filled from OAuth where possible
3. **OTP verify** — already implemented, keep this
4. **Category pick** — 6 niche tiles to tap (Fashion, Tech, Food, Fitness, Travel, Finance)

---

## 6. Dashboard Improvements

### 6.1 Remove Redundant Quick Links Card

The bottom-right Quick Links 2×2 grid (`dashboard-page.tsx:466-495`) duplicates the sidebar exactly. Replace with one of:

**Option A — Recent Campaigns Widget:**
```tsx
<Card>
  <CardHeader className="pb-2">
    <div className="flex items-center justify-between">
      <CardTitle className="text-base font-medium">Recent Campaigns</CardTitle>
      <Button variant="ghost" size="sm" onClick={() => navigate('/brand/campaigns')}>
        View All <ArrowRight className="h-3 w-3 ml-1" />
      </Button>
    </div>
  </CardHeader>
  <CardContent className="space-y-2">
    {recentCampaigns.slice(0, 3).map(campaign => (
      <div key={campaign.id} className="flex items-center justify-between py-1">
        <span className="text-sm truncate">{campaign.name}</span>
        <Badge variant="outline" className="text-xs">{campaign.status}</Badge>
      </div>
    ))}
  </CardContent>
</Card>
```

### 6.2 Add Trend % to "This Month" Stats

Current stats cards show numbers with no context. Add delta vs last month:

```tsx
// Before
<span className="font-semibold">12 collabs</span>

// After
<div className="flex items-center gap-2">
  <span className="font-semibold">12 collabs</span>
  <span className="text-xs text-green-600 flex items-center">
    <TrendingUp className="h-3 w-3 mr-0.5" />+20%
  </span>
</div>
```

### 6.3 Pipeline Bar — Add Click Navigation

The pipeline bar segments are hover-able but not clickable. Each segment should navigate to `/brand/campaigns?status=<stage>`:

```tsx
<div
  onClick={() => navigate(`/brand/campaigns?status=${stage.stage.toLowerCase()}`)}
  className={cn('... cursor-pointer', stage.color)}
  title={`${stage.stage}: ${stage.count} — Click to view`}
>
```

### 6.4 Empty States for All Data Sections

All data sections currently show hardcoded mock data. Each needs an empty state:

```tsx
// Generic empty state component
function EmptyState({ icon: Icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-10 text-center">
      <div className="h-12 w-12 rounded-full bg-muted flex items-center justify-center mb-3">
        <Icon className="h-6 w-6 text-muted-foreground" />
      </div>
      <p className="font-medium">{title}</p>
      <p className="text-sm text-muted-foreground mt-1">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
```

---

## 7. Navigation Gaps

### 7.1 Contracts Page Missing from Sidebar

`brand-contracts.tsx` exists and is a full feature page (e-signatures, deliverable tracking, approval workflow) but has **no route in `App.tsx`** and **no entry in the sidebar nav**.

**Fix — Add to `App.tsx`:**
```tsx
import BrandContractsPage from '@/pages/brand-contracts';
// ...
<Route path="/brand/contracts" element={
  <BrandLayoutWrapper><BrandContractsPage /></BrandLayoutWrapper>
} />
```

**Fix — Add to sidebar `navItems` in `brand-layout.tsx`:**
```tsx
import { FileSignature } from 'lucide-react';

const navItems = [
  { label: 'Home',      href: '/brand/dashboard', icon: Home },
  { label: 'Campaigns', href: '/brand/campaigns',  icon: Megaphone },
  { label: 'Creators',  href: '/brand/discover',   icon: Users2 },
  { label: 'Deal Room', href: '/brand/chat',        icon: MessageCircle },
  { label: 'Contracts', href: '/brand/contracts',   icon: FileSignature },  // ADD
  { label: 'Wallet',    href: '/brand/wallet',      icon: Wallet },
  { label: 'Settings',  href: '/brand/settings',    icon: Settings },
];
```

### 7.2 No 404 / Not Found Page

The app has no catch-all route. Mistyped URLs render a blank white screen.

```tsx
// Add to App.tsx at the end of Routes
<Route path="*" element={<NotFoundPage />} />
```

---

## 8. Color System Fixes

### 8.1 Hardcoded Light-Mode Colors in Dark Theme Components

Multiple dashboard components use hardcoded Tailwind color classes that are baked for light mode. In dark mode these look washed out or invisible.

**Affected patterns:**
```
bg-red-50/50     →  bg-destructive/10
bg-red-100       →  bg-destructive/15
text-red-700     →  text-destructive
border-red-200   →  border-destructive/30

bg-orange-50     →  bg-warning/10
bg-orange-100    →  bg-warning/15
text-orange-700  →  text-warning
border-orange-200 → border-warning/30

bg-green-50/30   →  bg-success/10
bg-green-100     →  bg-success/15
text-green-600   →  text-success

bg-blue-100      →  bg-primary/15
text-blue-600    →  text-primary
```

**Affected files:**
- `src/components/brand/dashboard/dashboard-page.tsx` — lines 151-156, 227-228, 313-334, 344-350
- Any component using `getPriorityColor()` returning raw Tailwind colors

**The CSS variables already exist in `globals.css`:**
```css
--destructive: oklch(0.55 0.22 27);
--success: oklch(0.60 0.17 155);
--warning: oklch(0.75 0.16 70);
```
Use them via `bg-destructive/10`, `text-destructive`, etc.

---

## 9. Onboarding Simplification

### 9.1 Current: 6 Steps Is a Conversion Killer

| Step | Name | Should Be |
|---|---|---|
| 1 | Account Setup | ✅ Keep — essential |
| 2 | Company Details | ✅ Keep — essential |
| 3 | Verification Docs | ❌ Defer — blocks new users |
| 4 | Team Setup | ❌ Defer — not Day 1 critical |
| 5 | Trust Primer | ❌ Remove — show inline as needed |
| 6 | Wallet Funding | ❌ Defer — only needed before first payment |

### 9.2 Recommended: 3-Step Onboarding + Progressive Unlock

**Core flow (Day 1):**
1. Account Setup (name, role)
2. Company Details (brand name, industry, logo)
3. Dashboard → Explore freely

**Progressive prompts (contextual):**
- When user tries to publish a campaign → "Verify your business to go live"
- When user tries to release a payment → "Add wallet funds first"
- When user invites a team member → "Set up your team"

```tsx
// In brand-onboarding.tsx — reduce totalSteps
const totalSteps = 3; // Was 6

const renderStep = () => {
  switch (currentStep) {
    case 1: return <AccountSetupStep ... />;
    case 2: return <CompanyDetailsStep ... />;
    case 3: return <ReadyToGoStep onComplete={handleComplete} />; // New — celebration screen
  }
};
```

---

## 10. Accessibility Fixes

### 10.1 Icon-Only Buttons Missing `aria-label`

All icon-only buttons must have `aria-label` for screen readers and keyboard users:

```tsx
// Before
<button className="relative p-1.5 hover:bg-accent rounded-lg">
  <Bell className="h-5 w-5 text-muted-foreground" />
</button>

// After
<button
  aria-label={`Notifications${unreadCount > 0 ? `, ${unreadCount} unread` : ''}`}
  className="relative p-1.5 hover:bg-accent rounded-lg"
>
  <Bell className="h-5 w-5 text-muted-foreground" />
</button>
```

**Affected buttons** (add `aria-label` to all):
- Bell/notification button — `brand-layout.tsx:258`
- Mobile hamburger menu button — `brand-layout.tsx:219`
- Mobile search button — `brand-layout.tsx:253`
- Password show/hide button — `brand-login.tsx:97`, `brand-register.tsx:197`
- All nav items in sidebar (have labels but no role="navigation" on `<nav>`)

### 10.2 Avatar Fallback — Static "BR" for All Users

```tsx
// Before — hardcoded
<AvatarFallback className="text-xs bg-primary/10 text-primary">BR</AvatarFallback>

// After — dynamic initials from user data
<AvatarFallback className="text-xs bg-primary/10 text-primary">
  {user?.displayName
    ? user.displayName.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase()
    : 'IN'}
</AvatarFallback>
```

### 10.3 Form Labels Not Associated with Inputs

In `brand-login.tsx` the `<label>` elements use `className="block..."` but no `htmlFor` attribute. Screen readers cannot associate them with the input:

```tsx
// Before
<label className="block text-sm font-medium text-slate-300 mb-2">
  Email Address
</label>
<input id="email" type="email" ... />

// After
<label htmlFor="email" className="block text-sm font-medium text-slate-300 mb-2">
  Email Address
</label>
<input id="email" type="email" ... />
```

---

## 11. Mobile Experience

### 11.1 Action Items — Critical Data Hidden on Mobile

In `dashboard-page.tsx:234-244`, the amount and priority badge are hidden below `sm:` breakpoint:

```tsx
<div className="text-right hidden sm:block">  {/* amount hidden on mobile */}
<Badge className={cn('hidden sm:flex', ...)}>  {/* badge hidden on mobile */}
```

On mobile the user sees: title + subtitle + action button. No amount, no priority. This removes critical decision context.

**Fix:** Show at minimum the amount and time remaining. Condense to one line:
```tsx
{/* Mobile: show compact info below title */}
<div className="sm:hidden flex items-center gap-2 mt-0.5">
  <span className="text-xs font-medium">{formatINR(item.amount)}</span>
  <span className="text-xs text-muted-foreground">·</span>
  <span className={cn('text-xs', item.priority === 'urgent' ? 'text-orange-600' : 'text-muted-foreground')}>
    {timeLeft}
  </span>
</div>
```

### 11.2 Pipeline Legend — Color Dots Without Labels on Mobile

```tsx
<span className="text-muted-foreground ml-1 hidden sm:inline">{stage.stage}</span>
```

On mobile: 6 colored dots with numbers. No one can read this. Fix:
```tsx
// Always show stage name, abbreviate if needed
<span className="text-muted-foreground ml-1 text-[10px]">
  {stage.stage.length > 6 ? stage.stage.slice(0, 5) + '.' : stage.stage}
</span>
```

### 11.3 Consider Bottom Tab Bar for Mobile

On mobile, the sidebar is hidden behind a hamburger menu. For a B2B SaaS accessed on mobile, this is acceptable — but consider adding a bottom navigation bar with the 4 most-used items:

```
[ Home ] [ Campaigns ] [ Deal Room ] [ Wallet ]
```

This is an optional enhancement but significantly improves one-thumb navigation.

---

## 12. React + Vite Advanced Features to Use

### 12.1 Vite — Code Splitting & Lazy Loading

Currently all pages are eagerly imported in `App.tsx`. With 26 pages, this inflates the initial bundle. Use `React.lazy` + `Suspense`:

```tsx
// App.tsx — convert all page imports to lazy
const BrandDashboardPage = React.lazy(() => import('@/pages/brand-dashboard'));
const BrandCampaignsPage = React.lazy(() => import('@/pages/brand-campaigns'));
const BrandDiscoverPage  = React.lazy(() => import('@/pages/brand-discover'));
// ... all 26 pages

// Wrap routes in Suspense
<Suspense fallback={<PageLoadingSpinner />}>
  <Routes>
    {/* ... all routes */}
  </Routes>
</Suspense>
```

**Impact:** Initial JS bundle drops ~60-70%. Dashboard loads instantly. Other pages load on first visit.

---

### 12.2 Vite — Bundle Analysis

Add `rollup-plugin-visualizer` to see exactly what is in the bundle:

```bash
pnpm add -D rollup-plugin-visualizer
```

```ts
// vite.config.ts
import { visualizer } from 'rollup-plugin-visualizer';

export default defineConfig({
  plugins: [
    react(),
    visualizer({ open: true, filename: 'dist/bundle-stats.html' }),
  ],
});
```

Run `pnpm build` and the browser opens a treemap of every package. Useful for spotting accidental large imports.

---

### 12.3 Vite — Environment-Based API Switching

Currently `lib/api.ts` has hardcoded mock data. Set up proper env switching:

```
.env.development   →  VITE_API_URL=http://localhost:8000
.env.production    →  VITE_API_URL=https://api.influora.com
.env.staging       →  VITE_API_URL=https://staging-api.influora.com
```

```ts
// lib/api.ts
const BASE_URL = import.meta.env.VITE_API_URL;
const IS_MOCK = import.meta.env.VITE_USE_MOCK === 'true';
```

---

### 12.4 React 19 — `useOptimistic` for Real-Time UI

React 19 ships `useOptimistic` — update UI immediately before the API call completes, roll back on error. Perfect for deal room actions:

```tsx
import { useOptimistic } from 'react';

function ProposalCard({ proposal, onAccept }) {
  const [optimisticStatus, setOptimisticStatus] = useOptimistic(proposal.status);

  const handleAccept = async () => {
    setOptimisticStatus('accepted'); // UI updates immediately
    try {
      await acceptProposal(proposal.id); // API call
    } catch {
      setOptimisticStatus(proposal.status); // rollback on error
    }
  };

  return <Badge>{optimisticStatus}</Badge>;
}
```

---

### 12.5 React 19 — `use()` Hook for Data Fetching

Replace some `useEffect` + `useState` data patterns with the new `use()` hook + Suspense:

```tsx
import { use, Suspense } from 'react';

// Fetch returns a Promise
const campaignPromise = fetchCampaign(id);

function CampaignDetail() {
  const campaign = use(campaignPromise); // Suspends until resolved
  return <div>{campaign.name}</div>;
}

// Parent wraps with Suspense + error boundary
<Suspense fallback={<CampaignSkeleton />}>
  <CampaignDetail />
</Suspense>
```

---

### 12.6 React 19 — `useTransition` for Non-Blocking Filter Updates

The Creator Discovery page (`brand-discover.tsx` — 1417 lines) has complex filtering. Heavy re-renders can freeze the UI. Use `useTransition` to keep the search input responsive:

```tsx
import { useTransition } from 'react';

function CreatorDiscovery() {
  const [isPending, startTransition] = useTransition();
  const [filters, setFilters] = useState(defaultFilters);

  const handleFilterChange = (newFilters) => {
    startTransition(() => {
      setFilters(newFilters); // Heavy re-render deferred
    });
  };

  return (
    <>
      <FilterPanel onChange={handleFilterChange} />
      {isPending && <div className="opacity-50 pointer-events-none">...</div>}
      <CreatorGrid filters={filters} />
    </>
  );
}
```

---

### 12.7 Framer Motion — Page Transition Animations (Already Installed)

`framer-motion` is installed but not used for page transitions. Add route-level animations:

```tsx
// src/components/shared/page-transition.tsx
import { motion } from 'framer-motion';

export function PageTransition({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8 }}
      transition={{ duration: 0.15, ease: 'easeOut' }}
    >
      {children}
    </motion.div>
  );
}

// Wrap main content in BrandLayout
<main className="flex-1">
  <AnimatePresence mode="wait">
    <PageTransition key={location.pathname}>
      {children}
    </PageTransition>
  </AnimatePresence>
</main>
```

---

### 12.8 TanStack Query — Prefetching for Instant Navigation

The app uses TanStack Query but likely doesn't prefetch adjacent routes. Add hover-based prefetch on nav items:

```tsx
import { useQueryClient } from '@tanstack/react-query';

function NavItem({ item }) {
  const queryClient = useQueryClient();

  const handleMouseEnter = () => {
    // Prefetch data for the page the user is about to visit
    if (item.href === '/brand/campaigns') {
      queryClient.prefetchQuery({ queryKey: ['campaigns'], queryFn: fetchCampaigns });
    }
    if (item.href === '/brand/discover') {
      queryClient.prefetchQuery({ queryKey: ['creators'], queryFn: fetchCreators });
    }
  };

  return (
    <button onMouseEnter={handleMouseEnter} onClick={() => navigate(item.href)} ...>
      {item.label}
    </button>
  );
}
```

---

### 12.9 Vite PWA Plugin — Offline Support

For brand managers checking deal room status on mobile, PWA support is a differentiator:

```bash
pnpm add -D vite-plugin-pwa
```

```ts
// vite.config.ts
import { VitePWA } from 'vite-plugin-pwa';

VitePWA({
  registerType: 'autoUpdate',
  manifest: {
    name: 'Influora',
    short_name: 'Influora',
    theme_color: '#6366f1',
    icons: [{ src: '/icon-192.png', sizes: '192x192', type: 'image/png' }],
  },
  workbox: {
    runtimeCaching: [
      { urlPattern: /\/api\/campaigns/, handler: 'StaleWhileRevalidate' },
    ],
  },
})
```

---

### 12.10 Zustand — Persist Middleware Already Used — Add Selective Hydration

`store.ts` uses Zustand `persist` middleware. Make sure sensitive data is excluded:

```ts
// lib/store.ts — exclude sensitive fields from localStorage
persist(
  (set) => ({ ... }),
  {
    name: 'influora-ui-state',
    partialize: (state) => ({
      // Only persist non-sensitive UI state
      mobileMenuOpen: state.mobileMenuOpen,
      // DO NOT persist: tokens, emails, payment data
    }),
  }
)
```

---

## 13. Priority Fix Table

| # | Issue | File | Impact | Effort | Sprint |
|---|---|---|---|---|---|
| 1 | Brand name "Creator OS" → "Influora" everywhere | brand-login, brand-register, creator-login, creator-register | 🔴 High | 🟢 Low (30 min) | Now |
| 2 | Broken "Forgot password" `<a to="#">` | brand-login.tsx:120 | 🔴 High | 🟢 Low (15 min) | Now |
| 3 | Notification bell has no panel/action | brand-layout.tsx:258 | 🔴 High | 🟡 Medium | Sprint 1 |
| 4 | Contracts page missing from sidebar + App.tsx routes | brand-layout.tsx, App.tsx | 🔴 High | 🟢 Low (20 min) | Now |
| 5 | Add DatePicker component + apply to all date fields | New: ui/date-picker.tsx | 🔴 High | 🟡 Medium | Sprint 1 |
| 6 | Replace hardcoded `bg-red-50`/`bg-orange-50` with CSS vars | dashboard-page.tsx | 🟠 High | 🟢 Low (1 hr) | Now |
| 7 | Fix wrong Settings icon in Quick Links card | dashboard-page.tsx:488 | 🟡 Medium | 🟢 Low (2 min) | Now |
| 8 | Create unified `/` entry page — Brand vs Creator choice | New: pages/landing-entry.tsx | 🟠 High | 🟡 Medium | Sprint 1 |
| 9 | Registration redesign with 3D animation | brand-register.tsx, creator-register.tsx | 🟠 High | 🔴 High | Sprint 2 |
| 10 | Replace Quick Links card with Recent Campaigns | dashboard-page.tsx:466 | 🟡 Medium | 🟢 Low | Sprint 1 |
| 11 | Add trend % to "This Month" stats | dashboard-page.tsx:421 | 🟡 Medium | 🟢 Low | Sprint 1 |
| 12 | Add `aria-label` to all icon-only buttons | brand-layout.tsx | 🟡 Medium | 🟢 Low (1 hr) | Now |
| 13 | Fix `htmlFor` associations on all form labels | brand-login.tsx, brand-register.tsx | 🟡 Medium | 🟢 Low (30 min) | Now |
| 14 | Dynamic avatar initials from user data | brand-layout.tsx:179 | 🟡 Medium | 🟢 Low | Sprint 1 |
| 15 | Replace raw inputs with shadcn Input/Select components | brand-login.tsx, brand-register.tsx | 🟡 Medium | 🟡 Medium | Sprint 1 |
| 16 | Reduce onboarding to 3 steps | brand-onboarding.tsx | 🟠 High | 🟡 Medium | Sprint 2 |
| 17 | React.lazy() code splitting for all 26 pages | App.tsx | 🟠 High | 🟢 Low (2 hrs) | Sprint 1 |
| 18 | useTransition for Creator Discovery filter | brand-discover.tsx | 🟡 Medium | 🟢 Low | Sprint 1 |
| 19 | Framer Motion page transitions | brand-layout.tsx | 🟢 Low | 🟢 Low | Sprint 2 |
| 20 | Add 404 / Not Found page | App.tsx | 🟡 Medium | 🟢 Low | Now |
| 21 | Mobile: show amount + time on action items at all sizes | dashboard-page.tsx:234 | 🟡 Medium | 🟢 Low | Sprint 1 |
| 22 | Pipeline legend: always show stage names | dashboard-page.tsx:298 | 🟡 Medium | 🟢 Low | Sprint 1 |
| 23 | TanStack Query prefetching on nav hover | brand-layout.tsx | 🟢 Low | 🟡 Medium | Sprint 3 |
| 24 | PWA support via vite-plugin-pwa | vite.config.ts | 🟢 Low | 🟡 Medium | Sprint 3 |
| 25 | Help & Support dead link | brand-layout.tsx:199 | 🟡 Medium | 🟢 Low | Sprint 1 |

---

## Effort Legend
- 🟢 Low — Under 2 hours, single file change
- 🟡 Medium — Half day, touches multiple files
- 🔴 High — Full day or more, new components/routes

## Impact Legend
- 🔴 High — Breaks UX or loses users (bugs, dead links, missing navigation)
- 🟠 High — Reduces quality or conversion significantly
- 🟡 Medium — Noticeable improvement
- 🟢 Low — Polish and nice-to-have

---

*Last updated: 2026-05-17*
