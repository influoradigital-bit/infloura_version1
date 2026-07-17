# Influora — Cursor AI Prompt Library
**Purpose:** Copy-paste prompts for Cursor AI Composer to implement every fix from UI-UX-IMPROVEMENT-PLAN.md  
**Stack Context:** React 19 · Vite 6 · TypeScript · Tailwind CSS 4 · shadcn/ui · Framer Motion · Zustand · TanStack Query  
**Theme:** Dark mode · OKLCh color variables · Primary = indigo/blue oklch(0.65 0.22 260)

> **How to use:** Open Cursor AI → press `Ctrl+I` (Composer) → paste the prompt exactly → press Enter.
> Each prompt is self-contained. Run them in the order listed for best results.
> Always attach the relevant file(s) using `@filename` before pasting.

---

## SPRINT 0 — Critical Bugs (Fix Today, Zero Risk)

---

### PROMPT 01 — Fix Broken "Forgot Password" + Dead Help Link + Wrong Settings Icon

```
@src/pages/brand-login.tsx
@src/components/brand/brand-layout.tsx
@src/components/brand/dashboard/dashboard-page.tsx

You are fixing 3 critical bugs in an Influora B2B SaaS app (React 19, TypeScript, Tailwind CSS 4, shadcn/ui, dark theme).

BUG 1 — brand-login.tsx line ~120
Find this broken element:
  <a to="#" className="text-sm text-blue-400 hover:text-blue-300">
    Forgot password?
  </a>
Replace it with a proper button:
  <button
    type="button"
    onClick={() => navigate('/brand/forgot-password')}
    className="text-sm text-blue-400 hover:text-blue-300 hover:underline transition-colors"
  >
    Forgot password?
  </button>
Make sure `navigate` is already imported from react-router-dom — it should be, but check.

BUG 2 — brand-layout.tsx line ~199
Find the Help & Support DropdownMenuItem that has no onClick:
  <DropdownMenuItem>
    <HelpCircle className="mr-2 h-4 w-4" />
    Help & Support
  </DropdownMenuItem>
Replace with:
  <DropdownMenuItem onClick={() => window.open('https://help.influora.com', '_blank')}>
    <HelpCircle className="mr-2 h-4 w-4" />
    Help & Support
  </DropdownMenuItem>

BUG 3 — dashboard-page.tsx line ~488
Find the Settings quick link that has a Users icon instead of Settings icon:
  <Link to="/brand/settings">
    <Users className="h-4 w-4" />
    <span className="text-xs">Settings</span>
  </Link>
Replace Users with Settings icon. Make sure Settings is imported from lucide-react — add it to the import if missing.

Do not change anything else in these files. Do not reformat unrelated code.
```

---

### PROMPT 02 — Replace Raw Inputs with shadcn/ui Components in Brand Auth Pages

```
@src/pages/brand-login.tsx
@src/pages/brand-register.tsx
@src/components/ui/input.tsx
@src/components/ui/select.tsx
@src/components/ui/label.tsx

You are fixing design inconsistency in the Influora brand auth pages (React 19, TypeScript, Tailwind CSS 4, shadcn/ui).

The brand-login.tsx and brand-register.tsx pages use raw <input> and <select> HTML elements with manually written Tailwind classes. The project already has a complete shadcn/ui component library. Replace all raw inputs with the design system components.

TASK 1 — brand-login.tsx
Replace every raw <input> element with <Input /> from @/components/ui/input.
Replace every raw <label> with <Label /> from @/components/ui/label and add matching htmlFor="fieldId" on label and id="fieldId" on input.
Fields to fix: email input, password input.
Keep the password show/hide toggle button logic exactly as-is — just swap the raw input wrapper.
Keep all existing className styling on Input as variant overrides if needed.

TASK 2 — brand-register.tsx  
Replace every raw <input> with <Input />.
Replace every raw <select> with the shadcn <Select>, <SelectTrigger>, <SelectValue>, <SelectContent>, <SelectItem> pattern.
Replace every raw <label> with <Label /> with correct htmlFor.
Fields to fix: company name input, industry select, team size select, email input, password input, confirm password input.
Keep all validation logic (useState, onChange handlers) exactly as-is — only change the markup.

RULES:
- Do not change any business logic, navigation, or state management.
- Do not change the page layout or styling outside of the input elements.
- Keep all existing className overrides on the new components.
- Add missing imports at the top of each file.
- TypeScript must remain valid — do not introduce type errors.
```

---

### PROMPT 03 — Fix All Form Label Accessibility (htmlFor Associations)

```
@src/pages/brand-login.tsx
@src/pages/brand-register.tsx

You are fixing accessibility bugs in the Influora brand auth pages. These fixes are required for WCAG 2.1 AA compliance.

PROBLEM: Every <label> element is missing a htmlFor attribute. Screen readers cannot associate labels with their inputs. Every <input> is missing an id attribute.

TASK — For every label+input pair in both files:
1. Add a unique id attribute to each input (e.g., id="email", id="password", id="confirm-password", id="company-name", id="industry", id="team-size")
2. Add matching htmlFor on the corresponding label (e.g., htmlFor="email")

Also add aria-label to the password show/hide toggle buttons:
  aria-label={showPassword ? 'Hide password' : 'Show password'}

Add aria-label to the mobile hamburger button if present:
  aria-label={mobileMenuOpen ? 'Close navigation menu' : 'Open navigation menu'}

Do not change any other code. TypeScript must remain valid.
```

---

### PROMPT 04 — Add Contracts Route to Sidebar Navigation and App Router

```
@src/components/brand/brand-layout.tsx
@src/App.tsx
@src/pages/brand-contracts.tsx

You are wiring up a missing page in the Influora B2B SaaS app. The brand-contracts.tsx page exists but is completely unreachable — it has no route and no sidebar entry.

TASK 1 — brand-layout.tsx
Find the navItems array at the top of the file. It currently has 6 items. Add a Contracts entry between Deal Room and Wallet:

Import FileSignature from lucide-react (add to the existing lucide-react import line).

Add to navItems:
  { label: 'Contracts', href: '/brand/contracts', icon: FileSignature }

Position: after Deal Room (/brand/chat), before Wallet (/brand/wallet).

TASK 2 — App.tsx
Find the section where brand routes are defined with BrandLayoutWrapper.
Add this route in the correct position with the other brand routes:
  <Route
    path="/brand/contracts"
    element={
      <BrandLayoutWrapper>
        <BrandContractsPage />
      </BrandLayoutWrapper>
    }
  />
Add the import at the top: import BrandContractsPage from '@/pages/brand-contracts';

Do not touch any other routes or components. TypeScript must remain valid.
```

---

### PROMPT 05 — Add 404 Not Found Page

```
@src/App.tsx

You are adding a 404 Not Found page to the Influora app (React 19, TypeScript, Tailwind CSS 4, shadcn/ui, dark theme).

TASK 1 — Create src/pages/not-found.tsx
Build a clean, on-brand 404 page with:
- The Influora logo at the top (use a div with "IN" initials, rounded-lg bg-primary text-primary-foreground, same as the sidebar logo)
- A large "404" heading in muted-foreground
- Subtitle: "Page not found"
- Body text: "The page you're looking for doesn't exist or has been moved."
- A primary Button: "Go to Dashboard" → onClick navigates to /brand/dashboard
- A ghost Button: "Go back" → onClick calls navigate(-1) from react-router-dom
- Center everything vertically and horizontally on min-h-screen bg-background
- Use only shadcn/ui components and Tailwind. No external libraries.

TASK 2 — App.tsx
At the very end of the <Routes> block, add a catch-all route:
  <Route path="*" element={<NotFoundPage />} />
Add the import: import NotFoundPage from '@/pages/not-found';

TypeScript must remain valid. The 404 page should work for both brand and creator URL paths.
```

---

## SPRINT 1 — UI Quality & Feature Completion

---

### PROMPT 06 — Brand Identity: Create Shared InfluoraLogo Component + Fix All Auth Pages

```
@src/pages/brand-login.tsx
@src/pages/brand-register.tsx
@src/pages/creator-login.tsx
@src/pages/creator-register.tsx

You are fixing a critical brand identity inconsistency in the Influora app. Currently the auth pages show "Creator OS" as the brand name with a Zap icon or "C" letter. The real brand name is "Influora" with an "IN" logo.

TASK 1 — Create src/components/shared/influora-logo.tsx
Create a new reusable logo component:

interface InfluoraLogoProps {
  size?: 'sm' | 'md' | 'lg';
  showName?: boolean;
}

Sizes:
  sm: h-7 w-7, text-[10px], brand name text-sm
  md: h-8 w-8, text-xs, brand name text-base (default)
  lg: h-10 w-10, text-sm, brand name text-xl

The logo block: rounded-lg bg-primary flex items-center justify-center, shows "IN" in font-bold text-primary-foreground.
When showName={true} (default), render the logo block + "Influora" text in font-semibold text-foreground next to it in a flex row with gap-2.5.

TASK 2 — Update all 4 auth pages
In each file:
1. Remove the existing logo/brand section at the top of the page (the div with Zap icon or "C" letter and "Creator OS" text)
2. Import InfluoraLogo from @/components/shared/influora-logo
3. Replace the old logo section with <InfluoraLogo size="lg" showName={true} /> centered or left-aligned as fits the existing layout
4. Replace every string "Creator OS" with "Influora" anywhere it appears in JSX text

Do not change any auth logic, form fields, navigation, or page layout beyond the logo area.
Do not change the color scheme or background of any page.
TypeScript must remain valid.
```

---

### PROMPT 07 — Color System Fix: Replace Hardcoded Light-Mode Colors with CSS Variables

```
@src/components/brand/dashboard/dashboard-page.tsx

You are fixing a dark mode color system bug in the Influora dashboard. The dashboard uses hardcoded Tailwind color classes that are baked for light mode and look washed out or invisible in dark mode. The design system already has semantic CSS variables for these colors. Use those instead.

REPLACEMENT RULES — apply these substitutions throughout the entire file:

Destructive / Error (red):
  bg-red-50/50    → bg-destructive/10
  bg-red-50       → bg-destructive/10
  bg-red-100      → bg-destructive/15
  text-red-700    → text-destructive
  text-red-600    → text-destructive
  border-red-200  → border-destructive/30
  border-red-300  → border-destructive/40

Warning (orange):
  bg-orange-50    → bg-warning/10
  bg-orange-100   → bg-warning/15
  text-orange-700 → text-warning
  text-orange-600 → text-warning
  border-orange-200 → border-warning/30
  border-orange-300 → border-warning/40

Success (green):
  bg-green-50/30  → bg-success/10
  bg-green-50     → bg-success/10
  bg-green-100    → bg-success/15
  text-green-600  → text-success
  text-green-500  → text-success

Primary (blue):
  bg-blue-100     → bg-primary/15
  text-blue-600   → text-primary
  text-blue-400   → text-primary

ALSO — find the getPriorityColor function. It returns hardcoded light-mode Tailwind strings like 'bg-red-100 text-red-700 border-red-200'. Update each case to return the semantic variable equivalents from the rules above.

Do not change any logic, data, layout, or component structure.
Do not change color classes that are intentional brand colors on buttons or backgrounds.
After substitution, verify all className strings are still valid Tailwind expressions.
TypeScript must remain valid.
```

---

### PROMPT 08 — Dashboard: Replace Quick Links + Add Trend % + Pipeline Click Navigation

```
@src/components/brand/dashboard/dashboard-page.tsx

You are improving the Influora brand dashboard (React 19, TypeScript, Tailwind CSS 4, shadcn/ui). Make these 3 improvements:

IMPROVEMENT 1 — Replace the redundant Quick Links card
Find the Quick Links card at the bottom of the right column (around line 466-495). It has a 2×2 grid of buttons that duplicate the sidebar. Replace the entire card with a "Recent Campaigns" card:

The new card should have:
  - CardHeader with title "Recent Campaigns" and a "View All" ghost button that navigates to /brand/campaigns
  - CardContent with 3 mock campaign rows. Each row: flex justify-between, campaign name (text-sm truncate), status Badge (variant="outline", text-xs)
  - Mock data to display: [{ name: 'Summer Fashion 2024', status: 'Active' }, { name: 'Tech Review Series', status: 'Negotiating' }, { name: 'Wellness Campaign', status: 'Draft' }]
  - If the array is empty, show an empty state: a centered PackageOpen icon (from lucide-react), text "No campaigns yet", a small Button "Create Campaign" navigating to /brand/campaigns/new

IMPROVEMENT 2 — Add trend percentage to "This Month" stats
Find the stats card titled "This Month". For each stat row, add a trend indicator after the value number.
Add a mock trends object at the top of the component (or alongside the stats data):
  const trends = { completed: '+20%', reach: '+8%', spent: '-5%', cpe: '+12%' }
After each <span className="font-semibold"> value, add:
  <span className="text-xs text-success flex items-center gap-0.5">
    <TrendingUp className="h-3 w-3" />+20%
  </span>
For negative trends use text-destructive and TrendingDown icon.
Import TrendingDown from lucide-react if not already imported.

IMPROVEMENT 3 — Make pipeline bar segments clickable
Find the pipeline bar map. Each colored div currently has a title but no onClick. Add:
  onClick={() => navigate(`/brand/campaigns?status=${stage.stage.toLowerCase().replace(' ', '-')}`)}
  className add: cursor-pointer hover:opacity-90 hover:ring-2 hover:ring-white/20
Also add a tooltip text update: title={`${stage.stage}: ${stage.count} deals — Click to filter`}

Do not change any other part of the dashboard. TypeScript must remain valid.
```

---

### PROMPT 09 — Dashboard: Fix Mobile Responsiveness for Action Items + Pipeline Legend

```
@src/components/brand/dashboard/dashboard-page.tsx

You are fixing mobile UX bugs on the Influora dashboard. Two sections hide critical information on small screens.

FIX 1 — Action Items: amount and priority hidden on mobile
Find the action items map inside the "Requires Your Action" card.
There are elements with className containing "hidden sm:block" (the amount + time div) and "hidden sm:flex" (the priority badge).
These hide critical information on mobile screens.

Below the existing <p className="font-medium truncate">{item.title}</p> and its subtitle line, add a new mobile-only row:
  <div className="flex items-center gap-2 mt-0.5 sm:hidden">
    <span className="text-xs font-semibold">{formatINR(item.amount)}</span>
    <span className="text-xs text-muted-foreground">·</span>
    <span className={cn(
      'text-xs font-medium',
      isOverdue ? 'text-destructive' : item.priority === 'urgent' ? 'text-warning' : 'text-muted-foreground'
    )}>
      {isOverdue ? 'Overdue' : timeLeft}
    </span>
  </div>
Keep the existing desktop-only divs exactly as they are (hidden sm:block, hidden sm:flex).

FIX 2 — Pipeline legend: stage names hidden on mobile
Find the pipeline legend grid. There is a <span> with className containing "hidden sm:inline" that shows the stage name.
Change it so the name is always visible but abbreviated on very small screens:
  <span className="text-muted-foreground ml-1 text-[10px]">
    {stage.stage.length > 7 ? stage.stage.slice(0, 6) + '.' : stage.stage}
  </span>
Remove the "hidden sm:inline" class.

Do not change any other part of the file. TypeScript must remain valid.
```

---

### PROMPT 10 — Add Notification Panel to Brand Layout

```
@src/components/brand/brand-layout.tsx
@src/components/ui/popover.tsx
@src/components/ui/scroll-area.tsx
@src/components/ui/badge.tsx

You are adding a notifications popover panel to the Influora brand layout header. Currently the notification bell shows an unread badge but has no click behavior — a broken affordance.

DESIGN SYSTEM: React 19, TypeScript, Tailwind CSS 4, shadcn/ui, dark theme.

TASK — Wire up the notification bell button:

1. Add state at the top of BrandLayout component:
   const [notificationsOpen, setNotificationsOpen] = React.useState(false);

2. Create mock notification data inside the component (above the return):
   const mockNotifications = [
     { id: '1', type: 'deal', title: 'New proposal received', body: 'Priya Sharma sent a counter-offer on Summer Fashion Campaign', time: '5 min ago', read: false },
     { id: '2', type: 'payment', title: 'Payment released', body: 'Milestone 1 payment of ₹25,000 was released to Sneha Reddy', time: '1 hr ago', read: false },
     { id: '3', type: 'contract', title: 'Contract signed', body: 'Rahul Verma signed the Product Launch contract', time: '3 hrs ago', read: true },
     { id: '4', type: 'system', title: 'Campaign approved', body: 'Your Tech Review campaign has been approved and is now live', time: '1 day ago', read: true },
   ];

3. Replace the existing static bell button with a Popover:
   Wrap the bell button in <Popover open={notificationsOpen} onOpenChange={setNotificationsOpen}>
   The trigger is the bell button (add PopoverTrigger asChild).
   The content is <PopoverContent> with:
     - align="end", width w-80
     - Header row: "Notifications" title (text-sm font-semibold) + "Mark all read" ghost button (text-xs text-muted-foreground)
     - A <ScrollArea className="h-80"> containing the notification list
     - Each notification item:
         flex gap-3 p-3 hover:bg-accent cursor-pointer rounded-lg transition-colors
         Left: a colored dot indicator (bg-primary h-2 w-2 rounded-full mt-1.5 shrink-0) — only show if !notification.read
         Middle: notification title (text-sm font-medium) + body (text-xs text-muted-foreground line-clamp-2) + time (text-xs text-muted-foreground mt-1)
         Unread items have bg-primary/5 background
     - Footer: a "View all notifications" button that navigates to /brand/notifications (if that page exists) or just closes the popover

4. Add required imports: Popover, PopoverContent, PopoverTrigger from @/components/ui/popover, ScrollArea from @/components/ui/scroll-area.

5. Add aria-label to the bell button: aria-label={`Notifications, ${unreadCount} unread`}

Keep all existing notification badge logic. Do not break any other part of the layout.
TypeScript must remain valid.
```

---

### PROMPT 11 — Add Dynamic Avatar Initials Across All Layout Files

```
@src/components/brand/brand-layout.tsx
@src/components/creator/creator-layout.tsx

You are fixing hardcoded avatar fallback text in the Influora app. Both layout files show static "BR" or "C" initials for all users regardless of who is logged in.

TASK — In both files, find every AvatarFallback component.

Replace hardcoded initials with a dynamic helper. Add this utility function inside the component (before the return):

  const getInitials = (name?: string | null, fallback = 'IN') => {
    if (!name?.trim()) return fallback;
    return name
      .trim()
      .split(/\s+/)
      .map(word => word[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
  };

Then replace every <AvatarFallback> that has hardcoded text like "BR", "CR", "C", or "IN":
  <AvatarFallback className="text-xs bg-primary/10 text-primary">
    {getInitials(user?.displayName || user?.name)}
  </AvatarFallback>

Apply this to all avatar instances in both files — sidebar avatar, mobile header avatar, and any other avatars that show user initials.

Do not change AvatarFallback components that are intentionally showing creator initials in lists (those should already use the creator's name from data).

TypeScript must remain valid.
```

---

### PROMPT 12 — Build DatePicker and DateRangePicker Components

```
@src/components/ui/calendar.tsx
@src/components/ui/popover.tsx
@src/components/ui/button.tsx

You are building two reusable date picker components for the Influora app (React 19, TypeScript, Tailwind CSS 4, shadcn/ui). The project already has calendar.tsx, popover.tsx, and button.tsx. Build on top of them — no new dependencies needed since react-day-picker is already installed via shadcn calendar.

COMPONENT 1 — Create src/components/ui/date-picker.tsx

Export a DatePicker component with these props:
  interface DatePickerProps {
    value?: Date;
    onChange: (date: Date | undefined) => void;
    placeholder?: string;
    minDate?: Date;
    maxDate?: Date;
    disabled?: boolean;
    className?: string;
  }

Behavior:
- Renders a Button (variant="outline") as the trigger showing the selected date formatted as 'PPP' from date-fns, or the placeholder text if no date selected
- Trigger has CalendarIcon on the left from lucide-react
- Unselected state: text-muted-foreground on the button
- Opens a Popover with Calendar inside (mode="single")
- The Calendar disabled prop blocks dates before minDate and after maxDate
- After selection, close the popover automatically
- The button width is w-full justify-start text-left font-normal
- Wrap in cn() for className merging

COMPONENT 2 — Create src/components/ui/date-range-picker.tsx

Export a DateRangePicker component with these props:
  interface DateRangePickerProps {
    value?: { from?: Date; to?: Date };
    onChange: (range: { from?: Date; to?: Date } | undefined) => void;
    placeholder?: string;
    minDate?: Date;
    className?: string;
  }

Behavior:
- Same trigger button pattern as DatePicker
- When only 'from' is selected: shows "Jan 15, 2025 →"
- When both are selected: shows "Jan 15 – Feb 28, 2025"
- Unselected: shows placeholder
- Calendar mode="range", numberOfMonths={2}
- Disable dates before minDate
- Use date-fns format function: 'LLL dd' and 'LLL dd, y'

Both components must:
- Have proper TypeScript types (no 'any')
- Use cn() from @/lib/utils for className merging
- Import Calendar from @/components/ui/calendar
- Import Popover, PopoverContent, PopoverTrigger from @/components/ui/popover
- Import Button from @/components/ui/button
- Import format from 'date-fns'
- Import CalendarIcon from 'lucide-react'
- Export as named exports

After creating both files, confirm there are no TypeScript errors.
```

---

### PROMPT 13 — Apply DatePicker to Campaign Forms + Wallet Filter

```
@src/pages/brand-new-campaign.tsx
@src/pages/brand-edit-campaign.tsx
@src/pages/brand-wallet.tsx
@src/components/ui/date-picker.tsx
@src/components/ui/date-range-picker.tsx

You are replacing all native <input type="date"> elements in the Influora app with the new custom DatePicker and DateRangePicker components. The custom components are already created at the paths above.

TASK 1 — brand-new-campaign.tsx
Find every <input type="date"> or native date input in the campaign creation form.
Replace campaign start date + end date fields with a single <DateRangePicker> with minDate={new Date()}.
Replace submission deadline field with <DatePicker> with minDate={new Date()}.
Update state to store Date objects instead of strings. Update the onChange handlers accordingly.
Import DatePicker from @/components/ui/date-picker and DateRangePicker from @/components/ui/date-range-picker.

TASK 2 — brand-edit-campaign.tsx  
Same as Task 1 but for the edit form. Pre-populate the pickers with existing campaign date values (convert existing string dates to Date objects where needed).

TASK 3 — brand-wallet.tsx
Find any date filter or date range filter in the transactions section.
Replace any native date input with <DateRangePicker> for transaction date filtering.
The onChange should update whatever filter state controls the transaction list.

Rules:
- Do not change any validation logic beyond what's needed for Date vs string type change.
- Do not change form layout, labels, or other fields.
- All date display text in the UI (showing deadline, campaign duration etc.) should use format() from date-fns for consistent formatting.
- TypeScript must remain valid with proper Date types.
```

---

### PROMPT 14 — React.lazy Code Splitting for All 26 Pages

```
@src/App.tsx

You are adding React.lazy() code splitting to the Influora app to reduce the initial bundle size by ~65%. Currently all 26 page components are eagerly imported at the top of App.tsx.

TASK:
1. Replace every eager static import of a page component with a React.lazy() dynamic import.
   Pattern to follow:
     BEFORE: import BrandDashboardPage from '@/pages/brand-dashboard';
     AFTER:  const BrandDashboardPage = React.lazy(() => import('@/pages/brand-dashboard'));
   
   Apply this to ALL page imports in the file (both brand and creator pages).
   Do NOT lazy-load layout components (BrandLayout, CreatorLayout) — keep those as eager imports.
   Do NOT lazy-load the ProtectedRoute or BrandLayoutWrapper components.

2. Create a loading fallback component inline inside App.tsx (before the App function):
   function PageLoader() {
     return (
       <div className="flex min-h-screen items-center justify-center bg-background">
         <div className="flex flex-col items-center gap-3">
           <div className="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
           <p className="text-sm text-muted-foreground">Loading...</p>
         </div>
       </div>
     );
   }

3. Wrap the entire <Routes> block inside App's return with React Suspense:
   <Suspense fallback={<PageLoader />}>
     <Routes>
       {/* all existing routes unchanged */}
     </Routes>
   </Suspense>

4. Add Suspense to the React import: import React, { Suspense } from 'react';

Do not change any route paths, route structure, or component composition.
Do not change ProtectedRoute logic.
TypeScript must remain valid.
```

---

## SPRINT 2 — New Pages & Major Features

---

### PROMPT 15 — Create Unified Brand + Creator Entry Page at Root Route

```
@src/App.tsx
@src/components/shared/influora-logo.tsx

You are creating the root landing/entry page for the Influora app (React 19, TypeScript, Tailwind CSS 4, shadcn/ui, Framer Motion, dark theme). First-time visitors currently get dumped straight to /brand/login. This new page lets users choose whether they are a Brand or a Creator.

TASK 1 — Create src/pages/landing-entry.tsx

Design spec:
- Full screen: min-h-screen bg-background flex flex-col
- Top bar: Influora logo (use <InfluoraLogo size="lg" showName={true} />) centered or top-left, with subtle top padding
- Hero section centered: 
    Heading: "The Creator Collaboration OS" (text-3xl sm:text-4xl font-bold text-foreground text-center)
    Subheading: "Connect brands with creators. Negotiate, contract, deliver, get paid." (text-muted-foreground text-center mt-2)
- Choice cards section: two cards side by side (grid grid-cols-1 sm:grid-cols-2 gap-4 max-w-2xl mx-auto mt-10)
  
  BRAND CARD:
  - Card with hover:border-primary/50 transition-colors cursor-pointer border-2 border-border
  - Icon: Building2 from lucide-react, h-10 w-10 text-primary, inside a rounded-xl bg-primary/10 p-2 mb-4
  - Title: "I'm a Brand" (text-xl font-semibold)
  - Description: "Launch influencer campaigns, manage collaborations, and track performance — all in one place." (text-sm text-muted-foreground mt-2)
  - Feature list (3 items with CheckCircle2 icons, text-xs): "Campaign management", "Creator discovery", "Escrow payments"
  - CTA Button at bottom: "Get Started" (variant="default", full width, mt-4) → navigate('/brand/register')
  - Secondary link below button: "Already have an account? Sign in" → navigate('/brand/login')

  CREATOR CARD:
  - Same card structure, hover:border-violet-500/50
  - Icon: Users from lucide-react, h-10 w-10 text-violet-500, inside rounded-xl bg-violet-500/10 p-2 mb-4
  - Title: "I'm a Creator"
  - Description: "Discover brand deals, negotiate terms, sign contracts, and get paid on time — every time."
  - Feature list (3 items): "Deal room & negotiation", "Contract signing", "Instant payouts"
  - CTA Button: "Join Now" (className with violet background: bg-violet-600 hover:bg-violet-700 text-white, full width) → navigate('/creator/register')
  - Secondary link: "Already have an account? Sign in" → navigate('/creator/login')

- Animate the two cards with Framer Motion:
    initial={{ opacity: 0, y: 24 }}
    animate={{ opacity: 1, y: 0 }}
    transition={{ delay: index * 0.1, duration: 0.4, ease: 'easeOut' }}

- Trust bar at the bottom: centered, text-xs text-muted-foreground
    "Trusted by 500+ brands · 50,000+ creators · RBI compliant payments"

TASK 2 — Update App.tsx
Change the root route from <Navigate to="/brand/login" /> to <LandingEntryPage />
Add route: <Route path="/login" element={<LandingEntryPage />} />
Add the lazy import: const LandingEntryPage = React.lazy(() => import('@/pages/landing-entry'));
Keep /brand/login and /creator/login routes unchanged.

TASK 3 — Update brand-login.tsx and creator-login.tsx
At the bottom of each login page, add a cross-portal link section:
Brand login: add below the "Sign up" link:
  <div className="text-center mt-4">
    <button onClick={() => navigate('/creator/login')} className="text-xs text-muted-foreground hover:text-foreground transition-colors">
      Are you a creator? Sign in here →
    </button>
  </div>
Creator login: add equivalent link to /brand/login.

TypeScript must remain valid. Use only existing dependencies (framer-motion is already installed).
```

---

### PROMPT 16 — Registration Redesign: Split Layout with Framer Motion 3D Effects

```
@src/pages/brand-register.tsx
@src/pages/creator-register.tsx
@src/components/shared/influora-logo.tsx

You are redesigning both registration pages for the Influora app with an immersive split-screen layout and Framer Motion 3D animations. Framer Motion is already installed. No new dependencies needed.

DESIGN PATTERN — Apply to BOTH pages (brand and creator):

LAYOUT:
  min-h-screen flex flex-col lg:flex-row bg-background
  LEFT PANEL (lg:w-[42%]): hidden on mobile, visible from lg breakpoint — the animated visual side
  RIGHT PANEL (lg:w-[58%]): the form, always visible on all screen sizes

LEFT PANEL — Animated Visual (brand-register.tsx):
  Background: bg-gradient-to-br from-primary/20 via-background to-violet-500/10
  Centered content with perspective-[1000px] and transformStyle preserve-3d
  
  1. Main floating card (use Framer Motion):
     motion.div with:
       animate={{ rotateY: [0, 3, -3, 0], rotateX: [0, 2, -2, 0] }}
       transition={{ duration: 8, repeat: Infinity, ease: 'easeInOut' }}
       style={{ transformPerspective: 1000 }}
     Inside: a Card with bg-card/90 backdrop-blur border-border shadow-2xl p-6 rounded-2xl w-72
     Content: Influora logo, "Launch your next campaign" heading, 3 feature rows with icons + text

  2. Floating stat badges (3 cards orbiting):
     Each is a motion.div with:
       animate={{ y: [0, -10, 0] }}
       transition={{ delay: index * 0.8, duration: 3, repeat: Infinity, ease: 'easeInOut' }}
     Position them absolutely: top-1/4 left-8, bottom-1/3 right-8, top-2/3 left-12
     Each badge: bg-card/80 backdrop-blur border border-border rounded-xl px-3 py-2 text-sm font-semibold shadow-lg
     Content: "500+ Brands", "₹10Cr+ Paid Out", "50K+ Creators"
  
  3. Glowing orb behind everything:
     Absolutely positioned div: h-64 w-64 rounded-full bg-primary/20 blur-3xl pointer-events-none
     Animate with: animate={{ scale: [1, 1.2, 1], opacity: [0.3, 0.5, 0.3] }} transition={{ duration: 6, repeat: Infinity }}

LEFT PANEL — Creator version (creator-register.tsx):
  Same pattern but use violet/purple gradient: from-violet-500/20 via-background to-purple-500/10
  Stat badges: "50K+ Creators", "₹10Cr+ Paid", "500+ Brands"
  Floating card content: "Start earning from your audience" heading

RIGHT PANEL — Form Section (both pages):
  Scrollable: overflow-y-auto flex-1 flex items-center justify-center p-6 lg:p-12
  Max width: max-w-md w-full mx-auto space-y-6
  
  Header:
    Mobile only: show <InfluoraLogo showName={true} /> (hidden on lg)
    Heading: "Create your account" (text-2xl font-bold)
    Subheading: "Join thousands of brands on Influora" (text-muted-foreground text-sm)

  Animate the form entry:
    motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} transition={{ duration: 0.4 }}

  FORM IMPROVEMENTS — apply to both pages:
  
  1. Use <Input /> and <Label /> from shadcn/ui for all form fields (not raw HTML inputs)
  2. Add PASSWORD STRENGTH METER after the password input:
     Create a small inline component:
       const strength = computed from password (0-4 scale):
         0 = empty, 1 = <6 chars, 2 = 6+ chars no variety, 3 = mixed case + number, 4 = all of the above + special char
       Render 4 bar segments (flex gap-1 mt-2):
         Each: h-1 flex-1 rounded-full transition-all duration-300
         Colors based on strength: bg-destructive (1), bg-warning (2-3), bg-success (4), bg-muted (unfilled)
       Label text below: text-xs text-muted-foreground — "Weak" / "Fair" / "Strong" / "Excellent"
  
  3. Add real-time email validation visual feedback:
     While typing (debounced 500ms): show a checkmark icon (CheckCircle2, text-success) or X icon (text-destructive) inline in the email input right side when format is valid/invalid
  
  4. Progress bar at the top of the form showing step progress — keep the existing step logic but style it better:
     Replace the basic h-1 bars with: rounded-full h-1.5, animated width transition, bg-primary for completed steps

  5. Step indicator text: "Step {currentStep} of {totalSteps}" (text-xs text-muted-foreground above the progress bar)

  Keep all existing form validation logic, useState, step navigation, and API simulation.
  Keep the "Sign in" link at the bottom.

  Add cross-portal link at very bottom:
  Brand register: <button onClick={() => navigate('/creator/register')}>Are you a creator? Sign up here</button>
  Creator register: <button onClick={() => navigate('/brand/register')}>Are you a brand? Sign up here</button>

TypeScript must remain valid. Use only framer-motion (already installed) and existing shadcn/ui components.
```

---

### PROMPT 17 — Simplify Onboarding to 3 Steps + Add Celebration Screen

```
@src/pages/brand-onboarding.tsx
@src/components/brand/onboarding/onboarding-steps.tsx
@src/components/brand/onboarding/onboarding-layout.tsx

You are simplifying the Influora brand onboarding from 6 steps down to 3 steps. The current flow requires verification docs, team setup, a trust primer, and wallet funding before users can do anything. This kills conversion. Defer non-essential steps to in-app contextual prompts.

TASK 1 — brand-onboarding.tsx
Change totalSteps from 6 to 3.

Update the renderStep switch to only handle cases 1, 2, 3:
  case 1: AccountSetupStep (keep as-is)
  case 2: CompanyDetailsStep (keep as-is)  
  case 3: ReadyToGoStep (new — see Task 2)

Remove cases 3, 4, 5, 6 from the switch.

TASK 2 — Create a ReadyToGoStep component inside onboarding-steps.tsx

Props: { onComplete: () => void; isSubmitting: boolean }

Design:
  Centered layout, max-w-md mx-auto text-center space-y-6
  
  Animated entry (Framer Motion if available, else just render):
    A large checkmark or sparkle: use Sparkles icon from lucide-react in a rounded-full bg-primary/15 h-20 w-20 flex items-center justify-center mx-auto
    Sparkles icon: h-10 w-10 text-primary
  
  Heading: "You're all set!" (text-2xl font-bold)
  Subheading: "Your Influora workspace is ready. Start discovering creators and launching campaigns." (text-muted-foreground)
  
  A 3-item feature unlock list with CheckCircle2 icons:
    "Discover 50,000+ verified creators"
    "Create and manage campaigns"
    "Negotiate deals in your deal room"
  Each item: flex items-center gap-2 text-sm, CheckCircle2 h-4 w-4 text-success
  
  A primary Button: "Go to Dashboard" (full width, disabled={isSubmitting})
    onClick={onComplete}
    Shows "Setting up your workspace..." with Loader2 spinner when isSubmitting
  
  Small note below: text-xs text-muted-foreground
    "You can complete business verification and wallet setup anytime from Settings."

TASK 3 — onboarding-layout.tsx
If the layout shows a step list/sidebar with all 6 steps, update it to only show 3 steps:
  Step 1: Account Setup
  Step 2: Company Details
  Step 3: Ready to Go
Do not show the removed steps.

TASK 4 — Add deferred prompt banners (add to brand-layout.tsx or dashboard-page.tsx)
Create a dismissible InlineBanner component:
  Props: { id: string; icon: LucideIcon; title: string; description: string; actionLabel: string; onAction: () => void }
  Style: rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 flex items-center justify-between
  Right side: a small action Button (variant="outline", size="sm") + X dismiss button
  Use localStorage to remember dismissed state: key = "banner_dismissed_" + id

Show this banner at top of the dashboard under the page header (only if not dismissed) for:
  - id="verify-business": when user hasn't completed verification (check localStorage for 'brand_docs_verified')
    title="Complete business verification", description="Required to publish campaigns and release payments", action navigates to /brand/settings
  - id="fund-wallet": when user hasn't added wallet funds (always show initially)
    title="Add funds to your wallet", description="Fund your escrow wallet before running campaigns", action navigates to /brand/wallet

TypeScript must remain valid.
```

---

## SPRINT 3 — Performance + Polish

---

### PROMPT 18 — Add Framer Motion Page Transitions to Brand Layout

```
@src/components/brand/brand-layout.tsx

You are adding smooth page transition animations to the Influora app using Framer Motion (already installed).

TASK:
1. Add these imports at the top of brand-layout.tsx:
   import { AnimatePresence, motion } from 'framer-motion';
   import { useLocation } from 'react-router-dom'; (already imported, just confirm)

2. Inside BrandLayout, the location is already read from useLocation(). Use location.pathname as the animation key.

3. Find the <main className="flex-1">{children}</main> element.
   Replace it with:
   <AnimatePresence mode="wait">
     <motion.main
       key={location.pathname}
       className="flex-1"
       initial={{ opacity: 0, y: 6 }}
       animate={{ opacity: 1, y: 0 }}
       exit={{ opacity: 0, y: -6 }}
       transition={{ duration: 0.18, ease: [0.4, 0, 0.2, 1] }}
     >
       {children}
     </motion.main>
   </AnimatePresence>

4. Do the same for creator-layout.tsx if it exists — apply the identical pattern.

The animation must:
- Be subtle — 6px Y offset, not 30px
- Be fast — 180ms, not 500ms
- Use "wait" mode so exit animation completes before the next page enters
- Not cause any layout shift or overflow during transition

Do not change any other part of the layout. TypeScript must remain valid.
```

---

### PROMPT 19 — Add useTransition to Creator Discovery Filters

```
@src/pages/brand-discover.tsx

You are adding React 19 useTransition to the Creator Discovery page to prevent the UI from freezing when filters are applied. This page has 1417 lines and complex filtering — heavy re-renders block the search input from feeling responsive.

TASK:
1. Add useTransition to the import: import React, { useState, useTransition } from 'react';

2. Find where filters state is declared (useState for the filter object or individual filter states).
   Add: const [isPending, startTransition] = useTransition();

3. Find every filter change handler (onChange, onValueChange, onClick for filter options).
   Wrap the state update inside startTransition:
   BEFORE:
     setFilters(newFilters);
   AFTER:
     startTransition(() => {
       setFilters(newFilters);
     });
   
   Apply to ALL filter state updates (platform filter, niche filter, follower range, engagement rate, location, sort order, etc.)
   Do NOT wrap the search input's onChange in startTransition — the text input must remain instant.

4. Find the creator grid/list render section.
   Add a visual pending indicator:
   Wrap the grid with:
   <div className={cn('transition-opacity duration-200', isPending && 'opacity-60 pointer-events-none')}>
     {/* existing creator grid */}
   </div>

5. If there's a "Filters" panel or sidebar, add a small loading indicator when isPending:
   Inside the filter panel header area, add:
   {isPending && (
     <span className="text-xs text-muted-foreground flex items-center gap-1">
       <div className="h-3 w-3 animate-spin rounded-full border border-primary border-t-transparent" />
       Filtering...
     </span>
   )}

TypeScript must remain valid. Do not change any data fetching, filter logic, or UI layout.
```

---

### PROMPT 20 — Add TanStack Query Prefetching on Nav Hover

```
@src/components/brand/brand-layout.tsx
@src/lib/api.ts

You are adding hover-based data prefetching to the Influora sidebar navigation using TanStack Query. This makes page navigation feel instant — data starts loading when the user hovers over a nav item.

TASK 1 — brand-layout.tsx
Add this import: import { useQueryClient } from '@tanstack/react-query';

Inside BrandLayout, add: const queryClient = useQueryClient();

Find the navItems map that renders the sidebar navigation buttons.
On each navigation button, add an onMouseEnter handler:

  const handleNavMouseEnter = (href: string) => {
    switch (href) {
      case '/brand/campaigns':
        queryClient.prefetchQuery({
          queryKey: ['campaigns'],
          queryFn: () => fetch('/api/campaigns').then(r => r.json()),
          staleTime: 30_000,
        });
        break;
      case '/brand/discover':
        queryClient.prefetchQuery({
          queryKey: ['creators', 'featured'],
          queryFn: () => fetch('/api/creators').then(r => r.json()),
          staleTime: 60_000,
        });
        break;
      case '/brand/wallet':
        queryClient.prefetchQuery({
          queryKey: ['wallet', 'summary'],
          queryFn: () => fetch('/api/wallet').then(r => r.json()),
          staleTime: 15_000,
        });
        break;
    }
  };

Add onMouseEnter={() => handleNavMouseEnter(item.href)} to each nav button.

TASK 2 — lib/api.ts
If the API is currently returning mock data synchronously, wrap it in a delay to simulate async behavior for the prefetch:
  export const fetchCampaigns = () => new Promise(resolve => setTimeout(() => resolve(mockCampaigns), 200));
  export const fetchCreators = () => new Promise(resolve => setTimeout(() => resolve(mockCreators), 200));
  export const fetchWalletSummary = () => new Promise(resolve => setTimeout(() => resolve(mockWalletData), 200));

Only add these if they don't already exist. Don't duplicate existing functions.

TypeScript must remain valid. The prefetching must not cause any visible errors in the console.
```

---

### PROMPT 21 — Zustand Store: Add Selective Persistence + Security Hardening

```
@src/lib/store.ts

You are hardening the Zustand persistence configuration in the Influora app to ensure sensitive data is never accidentally written to localStorage.

TASK:
For every Zustand store that uses the persist() middleware, add a partialize option that explicitly whitelists only non-sensitive fields for storage.

For the UI store (mobileMenuOpen, command bar state, etc.):
  partialize: (state) => ({
    mobileMenuOpen: false, // reset on page load
    commandBarOpen: false,
    // only persist user preferences like theme if applicable
  })

For the auth store (if it uses persist):
  partialize: (state) => ({
    // Store NOTHING from auth — tokens and user data should not persist to localStorage
    // Authentication state should be re-derived from the API token on app load
  })
  If auth data is currently being persisted (user object, email, tokens), remove those from partialize.

For the notification store (if it uses persist):
  partialize: (state) => ({
    // Only persist unread count if needed for badge display before data loads
    // Do not persist notification content
  })

For the campaign store (if it uses persist):
  partialize: (state) => ({
    // Do not persist campaign data — always fetch fresh from API
    // Only persist UI state like active filters or view mode preference
  })

Also ensure the Zustand store names are unique and descriptive:
  name: 'influora-ui' for UI store
  name: 'influora-notifications' for notification store

Do not change any store logic, actions, or state shape — only add/update the partialize option in persist() calls.
TypeScript must remain valid.
```

---

### PROMPT 22 — Vite Config: Add Bundle Analyzer + PWA + Environment Setup

```
@vite.config.ts
@package.json

You are enhancing the Vite configuration for the Influora app with bundle analysis, PWA support, and proper environment variable setup.

TASK 1 — Install the required dev dependencies (show the command only, do not run it):
  pnpm add -D rollup-plugin-visualizer vite-plugin-pwa

TASK 2 — Update vite.config.ts

Add these imports:
  import { visualizer } from 'rollup-plugin-visualizer';
  import { VitePWA } from 'vite-plugin-pwa';

Add to the plugins array (only in production build, not in dev):

BUNDLE VISUALIZER (only when ANALYZE=true env var is set):
  process.env.ANALYZE === 'true' && visualizer({
    open: true,
    filename: 'dist/bundle-stats.html',
    gzipSize: true,
    brotliSize: true,
  })

PWA CONFIG:
  VitePWA({
    registerType: 'autoUpdate',
    includeAssets: ['favicon.ico', 'icons/*.png'],
    manifest: {
      name: 'Influora',
      short_name: 'Influora',
      description: 'The Creator Collaboration OS',
      theme_color: '#6366f1',
      background_color: '#0a0a0f',
      display: 'standalone',
      start_url: '/',
      icons: [
        { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
        { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
      ],
    },
    workbox: {
      globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
      runtimeCaching: [
        {
          urlPattern: /^https:\/\/api\.influora\.com\/.*/i,
          handler: 'NetworkFirst',
          options: { cacheName: 'api-cache', networkTimeoutSeconds: 10 },
        },
      ],
    },
    devOptions: { enabled: false },
  })

Also add to the main vite config object:
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-ui': ['@radix-ui/react-dialog', '@radix-ui/react-popover'],
          'vendor-query': ['@tanstack/react-query'],
          'vendor-motion': ['framer-motion'],
          'vendor-charts': ['recharts'],
        },
      },
    },
    chunkSizeWarningLimit: 600,
  }

TASK 3 — Create environment files (show content only, do not create files):
  .env.development: VITE_API_URL=http://localhost:8000, VITE_USE_MOCK=true
  .env.production: VITE_API_URL=https://api.influora.com, VITE_USE_MOCK=false
  .env.staging: VITE_API_URL=https://staging-api.influora.com, VITE_USE_MOCK=false

TASK 4 — Add analyze script to package.json scripts:
  "analyze": "ANALYZE=true vite build"
On Windows use: "analyze": "cross-env ANALYZE=true vite build"
Add cross-env if not present: pnpm add -D cross-env

TypeScript in vite.config.ts must remain valid.
```

---

## QUICK FIX PROMPTS — Single-Line Patches

---

### PROMPT 23 — Batch Fix: All aria-labels on Icon-Only Buttons

```
@src/components/brand/brand-layout.tsx
@src/pages/brand-login.tsx
@src/pages/brand-register.tsx

Add aria-label attributes to every icon-only interactive element across these 3 files.

BRAND LAYOUT:
- Mobile hamburger button: aria-label={mobileMenuOpen ? 'Close menu' : 'Open menu'}
- Bell notification button: aria-label={`Notifications${unreadCount > 0 ? `, ${unreadCount} unread` : ''}`}
- Mobile search button: aria-label="Search"
- Mobile user avatar button: aria-label="User menu"
- Add role="navigation" to the <nav> element in the sidebar

BRAND LOGIN:
- Password show/hide button: aria-label={showPassword ? 'Hide password' : 'Show password'}

BRAND REGISTER:
- Password show/hide button: aria-label={showPassword ? 'Hide password' : 'Show password'}
- Confirm password show/hide button: aria-label={showConfirmPassword ? 'Hide confirm password' : 'Show confirm password'}

Do not change any other attributes, logic, or styling. TypeScript must remain valid.
```

---

### PROMPT 24 — Add Skeleton Loading States to Dashboard Cards

```
@src/components/brand/dashboard/dashboard-page.tsx
@src/components/ui/skeleton.tsx

You are adding skeleton loading states to the Influora dashboard. Currently the dashboard renders immediately with hardcoded mock data. When real API data is added, there will be a loading flash. Add proper skeleton states now.

TASK:
1. Add an isLoading prop/state at the top of DashboardPage:
   const [isLoading, setIsLoading] = React.useState(true);
   React.useEffect(() => {
     const timer = setTimeout(() => setIsLoading(false), 800); // simulate load
     return () => clearTimeout(timer);
   }, []);

2. Import Skeleton from @/components/ui/skeleton.

3. If isLoading is true, render skeleton versions of the 3 main cards instead of the real cards:
   
   For the "Requires Your Action" card:
     Show 3 skeleton rows: each a flex row with:
       Skeleton h-10 w-10 rounded-full (icon placeholder)
       div flex-1: Skeleton h-4 w-48 mb-2, Skeleton h-3 w-32 (title + subtitle)
       Skeleton h-8 w-20 rounded-md (button placeholder)
   
   For the Wallet Health card:
     Skeleton h-8 w-32 mb-1 (balance), Skeleton h-4 w-20 (label), 
     two Skeleton h-16 rounded-lg (the 2-col mini cards),
     Skeleton h-2 w-full rounded-full (progress bar),
     Skeleton h-9 w-full rounded-md (button)
   
   For the Quick Stats card:
     4 rows of: Skeleton h-8 w-8 rounded-full + Skeleton h-4 w-24 + Skeleton h-4 w-16

4. Add transition from skeleton to real content:
   Wrap each card in: <div className={cn('transition-opacity duration-300', isLoading ? 'opacity-100' : 'opacity-100')}>
   (The real animation happens through the skeleton/content swap)

TypeScript must remain valid.
```

---

### PROMPT 25 — Final Polish: Add useOptimistic to Deal Room Proposal Actions

```
@src/components/brand/deal-room/proposal-card.tsx
@src/components/creator/deal-room/

You are adding React 19's useOptimistic hook to the deal room proposal actions in Influora. This makes accept/reject/counter actions feel instant instead of waiting for an API response.

TASK — In the proposal card component(s) where accept, reject, or counter-proposal buttons exist:

1. Replace static status display with optimistic state:
   import { useOptimistic } from 'react';
   
   Inside the component:
   const [optimisticStatus, setOptimisticStatus] = useOptimistic(
     proposal.status,
     (currentStatus, newStatus: string) => newStatus
   );

2. For each action button handler, wrap with optimistic update:
   
   Accept handler:
   const handleAccept = async () => {
     setOptimisticStatus('accepted');
     try {
       await acceptProposal(proposal.id);
     } catch (error) {
       setOptimisticStatus(proposal.status); // rollback
       toast.error('Failed to accept proposal. Please try again.');
     }
   };
   
   Reject handler:
   const handleReject = async () => {
     setOptimisticStatus('rejected');
     try {
       await rejectProposal(proposal.id);
     } catch {
       setOptimisticStatus(proposal.status);
       toast.error('Failed to reject proposal. Please try again.');
     }
   };

3. Use optimisticStatus (not proposal.status) for all status-based UI rendering:
   - Status badge display
   - Button disabled state (disable all action buttons when optimisticStatus !== proposal.status, meaning action is in flight)
   - Color coding

4. Import toast from 'sonner' for error feedback (it's already installed).

Apply this pattern to both brand-side and creator-side deal room components.
TypeScript must remain valid. Do not change any data shape or API call signatures.
```

---

## Master Prompt — Run Everything in Correct Order

> Use this as a reference checklist. Run each numbered prompt above individually in Cursor. Do NOT paste all prompts at once.

```
IMPLEMENTATION ORDER FOR CURSOR AI:

SPRINT 0 — Critical Bugs (run today, ~2 hours total)
  [ ] PROMPT 01 — Fix broken forgot password, help link, settings icon
  [ ] PROMPT 02 — Replace raw inputs with shadcn components
  [ ] PROMPT 03 — Fix all form label accessibility
  [ ] PROMPT 04 — Add Contracts to sidebar + App.tsx route
  [ ] PROMPT 05 — Add 404 Not Found page

SPRINT 1 — UI Quality (run this week, ~1 day total)
  [ ] PROMPT 06 — Brand identity: create InfluoraLogo, fix all auth pages
  [ ] PROMPT 07 — Color system: replace hardcoded colors with CSS variables
  [ ] PROMPT 08 — Dashboard: recent campaigns card, trend %, pipeline clicks
  [ ] PROMPT 09 — Dashboard: mobile fixes for action items + pipeline
  [ ] PROMPT 10 — Notification bell: add popover panel
  [ ] PROMPT 11 — Dynamic avatar initials
  [ ] PROMPT 12 — Build DatePicker + DateRangePicker components
  [ ] PROMPT 13 — Apply date pickers to campaign forms + wallet
  [ ] PROMPT 14 — React.lazy code splitting for all 26 pages
  [ ] PROMPT 23 — Batch aria-label fixes
  [ ] PROMPT 24 — Skeleton loading states

SPRINT 2 — New Pages & Major Features (~2-3 days)
  [ ] PROMPT 15 — Unified landing/entry page at root route
  [ ] PROMPT 16 — Registration redesign with 3D animations
  [ ] PROMPT 17 — Onboarding simplification to 3 steps

SPRINT 3 — Performance & Polish (~1 day)
  [ ] PROMPT 18 — Framer Motion page transitions
  [ ] PROMPT 19 — useTransition for creator discovery filters
  [ ] PROMPT 20 — TanStack Query prefetching on nav hover
  [ ] PROMPT 21 — Zustand selective persistence security
  [ ] PROMPT 22 — Vite config: bundle analyzer + PWA
  [ ] PROMPT 25 — useOptimistic for deal room actions

ESTIMATED TOTAL: 4-5 working days for complete implementation
EXPECTED RESULT: UI/UX score moves from 7.5/10 → 9.5/10
```

---

## Cursor AI Tips for This Project

**Always attach these context files before any prompt:**
- `@src/app/globals.css` — so Cursor knows your color variables
- `@src/lib/utils.ts` — so Cursor uses the correct cn() utility
- The specific file being modified

**Useful Cursor shortcuts:**
- `Ctrl+I` — Open Composer (paste prompts here)
- `Ctrl+K` — Inline edit (for small single-line fixes)
- `Ctrl+L` — Chat (for questions about the codebase)
- `@codebase` — Let Cursor index and search the full project

**After each prompt runs:**
1. Check TypeScript errors in the Problems panel
2. Run `pnpm dev` and visually verify the change
3. Test on mobile viewport (Chrome DevTools → Toggle Device Toolbar)
4. Check dark mode renders correctly

---

*Prompt Library Version: 1.0 — 2026-05-17*  
*Source: UI-UX-IMPROVEMENT-PLAN.md — All 25 priority items covered*
