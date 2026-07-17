# Design System Documentation

## Overview

The Creator OS Brand Frontend uses a professional dark theme with a blue accent color. The design system is built on shadcn/ui components with Tailwind CSS, ensuring consistency and accessibility across the application.

## Color Palette

### Primary Colors
- **Background**: `#0f172a` (Slate-950) - Main page background
- **Surface**: `#1e293b` (Slate-800) - Cards and containers
- **Foreground**: `#f1f5f9` (Slate-100) - Text color

### Accent Colors
- **Primary**: `#3b82f6` (Blue-500) - Buttons, links, active states
- **Primary Dark**: `#1e40af` (Blue-800) - Hover states
- **Secondary**: `#06b6d4` (Cyan-500) - Secondary actions

### Semantic Colors
- **Success**: `#10b981` (Emerald-500) - Positive states
- **Warning**: `#f59e0b` (Amber-500) - Warning states
- **Error**: `#ef4444` (Red-500) - Error states
- **Info**: `#3b82f6` (Blue-500) - Info states

### Neutral Colors
- `#0f172a` - Slate-950 (Background)
- `#1e293b` - Slate-800 (Surface)
- `#334155` - Slate-700 (Border)
- `#64748b` - Slate-500 (Muted text)
- `#94a3b8` - Slate-400 (Secondary text)

## Typography

### Font Stack
- **Primary**: System font (uses default OS fonts for best performance)
- **Monospace**: `SF Mono`, `Monaco`, monospace

### Font Sizes
- **Display**: `3xl` (30px) - Main headings
- **Heading 1**: `2xl` (24px) - Page titles
- **Heading 2**: `xl` (20px) - Section titles
- **Heading 3**: `lg` (18px) - Subsection titles
- **Body**: `base` (16px) - Regular text
- **Small**: `sm` (14px) - Secondary text
- **Extra Small**: `xs` (12px) - Labels, captions

### Font Weights
- **Bold**: 700 - Headings, strong emphasis
- **Semibold**: 600 - Section headers, labels
- **Regular**: 400 - Body text
- **Medium**: 500 - Used sparingly for mid-weight emphasis

### Line Heights
- **Tight**: 1.25 - Headings
- **Snug**: 1.375 - Subheadings
- **Normal**: 1.5 - Body text
- **Relaxed**: 1.625 - Data tables

## Spacing Scale

Based on 4px grid:
```
0 = 0px
1 = 4px
2 = 8px
3 = 12px
4 = 16px
6 = 24px
8 = 32px
12 = 48px
16 = 64px
20 = 80px
24 = 96px
32 = 128px
```

Use these consistently for:
- Padding: `p-4`, `px-6`, `py-2`
- Margin: `m-4`, `mx-auto`, `mb-8`
- Gap: `gap-4`, `space-y-3`

## Components

### Button Variants

**Primary Button**
```tsx
<Button className="bg-blue-600 hover:bg-blue-700 text-white">
  Action Button
</Button>
```
- Use for main CTAs
- Highest priority actions
- Success, submit, confirm

**Secondary Button**
```tsx
<Button className="bg-slate-700 hover:bg-slate-600 text-white">
  Secondary Action
</Button>
```
- Use for alternative actions
- Lower priority than primary
- Cancel, skip, back

**Outline Button**
```tsx
<Button className="border border-slate-600 text-white hover:bg-slate-800">
  Outline Button
</Button>
```
- Use for less emphasized actions
- Filters, toggles

### Card Layout

All cards follow this pattern:
```tsx
<Card className="bg-slate-800/50 border-slate-700">
  <div className="p-6 border-b border-slate-700">
    {/* Header */}
  </div>
  <div className="p-6">
    {/* Content */}
  </div>
</Card>
```

### Form Elements

**Input**
```tsx
<input
  className="px-4 py-2 bg-slate-700 border border-slate-600 rounded-lg 
             text-white placeholder-slate-500 focus:ring-2 focus:ring-blue-500"
/>
```

**Select**
```tsx
<select className="px-4 py-2 bg-slate-700 border border-slate-600 rounded-lg text-white">
  <option>Option</option>
</select>
```

**Checkbox**
```tsx
<input type="checkbox" className="rounded bg-slate-700 border-slate-600 cursor-pointer accent-blue-500" />
```

### Status Badges

```tsx
// Active
<span className="px-3 py-1 rounded-full text-sm font-medium bg-green-500/10 text-green-600">Active</span>

// Pending
<span className="px-3 py-1 rounded-full text-sm font-medium bg-amber-500/10 text-amber-600">Pending</span>

// Completed
<span className="px-3 py-1 rounded-full text-sm font-medium bg-blue-500/10 text-blue-600">Completed</span>

// Error
<span className="px-3 py-1 rounded-full text-sm font-medium bg-red-500/10 text-red-600">Error</span>
```

## Layout Patterns

### Header
- Height: `h-16` (64px)
- Padding: `px-4 sm:px-6 py-4`
- Border bottom: `border-b border-slate-700`
- Sticky with backdrop blur

### Sidebar
- Width: `w-64` (desktop), `w-16` (collapsed)
- Border right: `border-r border-slate-700`
- Smooth transitions: `transition-all duration-300`

### Grid Layouts

**2 Column (Desktop) / 1 Column (Mobile)**
```tsx
<div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
  {/* Content */}
</div>
```

**3 Column (Desktop)**
```tsx
<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
  {/* Content */}
</div>
```

### Flexbox Patterns

**Space Between**
```tsx
<div className="flex items-center justify-between gap-4">
  {/* Left content */}
  {/* Right content */}
</div>
```

**Center Content**
```tsx
<div className="flex items-center justify-center">
  {/* Centered content */}
</div>
```

## Responsive Breakpoints

- **Mobile**: `0px` - `640px` (No prefix, e.g., `text-sm`)
- **SM (Tablet)**: `640px+` (Prefix: `sm:`, e.g., `sm:text-base`)
- **MD**: `768px+` (Prefix: `md:`, e.g., `md:text-lg`)
- **LG (Desktop)**: `1024px+` (Prefix: `lg:`, e.g., `lg:text-xl`)
- **XL**: `1280px+` (Prefix: `xl:`)
- **2XL**: `1536px+` (Prefix: `2xl:`)

Always design mobile-first:
1. Write base styles for mobile
2. Add larger screen styles with prefixes
3. Test on multiple device sizes

## Shadows & Elevation

```css
/* Card shadow */
box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);

/* Modal shadow */
box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3);

/* Elevated UI */
box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.2);
```

## Transitions & Animations

**Standard transition**
```tsx
className="transition-all duration-300"
```

**Hover effects**
```tsx
className="hover:bg-slate-700 hover:text-white transition-colors"
```

**Focus state**
```tsx
className="focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
```

## Icons

- **Icon Size**: 16px (sm), 20px (md), 24px (lg)
- **Icon Color**: Inherit from parent text color or use `text-muted-foreground`
- **Icon Spacing**: Gap of 8px between icon and text

```tsx
<button className="flex items-center gap-2">
  <Icon className="w-4 h-4" />
  Button Text
</button>
```

## Accessibility

### Color Contrast
- Text on background: Minimum 4.5:1 ratio
- UI components: Minimum 3:1 ratio
- Focus indicators: Blue ring, minimum 2px

### Focus States
- Visible focus indicator on all interactive elements
- Tab order follows visual flow
- Focus ring: `ring-2 ring-blue-500`

### Screen Readers
- Use semantic HTML
- Add `aria-labels` where needed
- Hide decorative elements with `aria-hidden="true"`
- Use `sr-only` class for screen reader-only text

## Best Practices

1. **Consistency**
   - Use design tokens consistently
   - Follow component patterns
   - Maintain spacing scale

2. **Readability**
   - Sufficient color contrast
   - Adequate line spacing
   - Clear visual hierarchy

3. **Mobile First**
   - Start with mobile layout
   - Enhance for larger screens
   - Test touch interactions

4. **Performance**
   - Minimize custom CSS
   - Use Tailwind utilities
   - Optimize images

5. **Accessibility**
   - Test with keyboard
   - Use semantic HTML
   - Provide alternative text

## Dark Mode

The application uses a dark theme exclusively. To add light mode in the future:

1. Add theme toggle to user settings
2. Update globals.css with light mode tokens
3. Use `data-theme` attribute for switching
4. Update component classes with theme-aware variants

## Customization

To customize the design system:

1. **Colors**: Edit `globals.css` design tokens
2. **Typography**: Update font settings in Tailwind config
3. **Spacing**: Modify spacing scale in globals.css
4. **Components**: Override shadcn/ui component styles

All changes automatically propagate through the app using CSS variables.
