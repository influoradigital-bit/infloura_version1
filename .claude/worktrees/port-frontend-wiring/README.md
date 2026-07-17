# Creator Collaboration OS - Brand Frontend

A comprehensive React + Vite frontend application for brand-side management of the Creator Collaboration OS platform. Built with TypeScript, Tailwind CSS, and modern React patterns.

## Project Overview

This is the **Brand Dashboard** - a B2B SaaS platform where marketing teams and brands can:

- Create and manage influencer marketing campaigns
- Discover and search for creators
- Negotiate collaboration terms in deal rooms
- Manage contracts and track deliverables
- Monitor payments and settlements
- Resolve disputes

## Architecture & Features

### Core Features

1. **Brand Onboarding** (`/brand/onboarding`)
   - Multi-step company setup wizard
   - Brand profile customization
   - Initial team configuration

2. **Dashboard** (`/brand/dashboard`)
   - Overview of active campaigns
   - Recent deals and pending actions
   - Campaign performance metrics
   - Quick access navigation

3. **Campaign Management** (`/brand/campaigns`)
   - List all active campaigns
   - Create new campaigns with detailed briefs
   - Define deliverables and budgets
   - Set campaign timeline and requirements

4. **Creator Discovery** (`/brand/discover`)
   - Search and filter creators by niche/platform
   - View detailed creator profiles
   - Initiate collaboration requests
   - Compare multiple creators

5. **Deal Rooms** (`/brand/deals`)
   - Negotiate terms with creators
   - Version control for proposals
   - Real-time messaging
   - Deal status tracking

6. **Contracts & Deliverables** (`/brand/contracts`)
   - E-signature contract management
   - Deliverable submission tracking
   - Approval workflows
   - Payment schedule management

### Database Models (Reference)

The application interfaces with these core entities:

- **Workspace**: Brand account and team workspace
- **Campaign**: Influencer marketing campaign
- **Collaboration**: Creator-Brand partnership proposal
- **Contract**: Signed agreement between parties
- **Deliverable**: Content deliverable (Instagram post, TikTok video, etc)
- **Wallet**: Payment and settlement tracking
- **Dispute**: Conflict resolution

## Tech Stack

- **Framework**: React 19 with Vite
- **Language**: TypeScript
- **Styling**: Tailwind CSS 4
- **UI Components**: Radix UI + shadcn/ui
- **Routing**: React Router 7
- **State Management**: Zustand
- **Form Handling**: React Hook Form
- **Data Fetching**: TanStack Query (React Query)
- **Icons**: Lucide React

## Project Structure

```
src/
├── App.tsx                    # Main app with routing
├── main.tsx                   # Vite entry point
├── pages/
│   ├── brand-login.tsx       # Login page
│   ├── brand-register.tsx    # Registration page
│   ├── brand-dashboard.tsx   # Dashboard wrapper
│   ├── brand-onboarding.tsx  # Onboarding wrapper
│   ├── brand-campaigns.tsx   # Campaigns wrapper
│   ├── brand-discover.tsx    # Discovery wrapper
│   ├── brand-deals.tsx       # Deals wrapper
│   └── brand-contracts.tsx   # Contracts wrapper
├── components/
│   ├── ui/                   # Shadcn UI components
│   ├── brand/
│   │   ├── brand-layout.tsx           # Main layout wrapper
│   │   ├── dashboard/
│   │   │   └── dashboard-page.tsx     # Dashboard component
│   │   ├── onboarding/
│   │   │   ├── onboarding-layout.tsx  # Onboarding wrapper
│   │   │   └── onboarding-steps.tsx   # Multi-step form
│   │   ├── campaigns/
│   │   │   ├── campaigns-list.tsx     # Campaign list view
│   │   │   └── campaign-form.tsx      # Create campaign form
│   │   ├── discover/
│   │   │   └── creator-discovery.tsx  # Creator search & filter
│   │   ├── deals/
│   │   │   └── deal-room-dashboard.tsx # Deal room interface
│   │   └── contracts/
│   │       └── contracts-and-deliverables.tsx # Contract management
│   └── nav/                  # Navigation components
├── lib/
│   ├── types.ts              # TypeScript interfaces
│   ├── store.ts              # Zustand state stores
│   ├── utils.ts              # Utility functions
│   └── api.ts                # API client (to be added)
├── hooks/
│   ├── useAuth.ts            # Auth hook
│   └── ...                   # Other custom hooks
└── app/
    └── globals.css           # Global styles & design tokens
```

## Design System

### Color Palette

- **Primary**: Slate/Dark theme (`#0f172a` - `#334155`)
- **Accent**: Blue (`#3b82f6` - `#1e40af`)
- **Success**: Green (`#10b981`)
- **Warning**: Amber (`#f59e0b`)
- **Error**: Red (`#ef4444`)

### Typography

- **Headings**: Default system font (configured in `globals.css`)
- **Body**: Default system font
- **Code**: Monospace

### Spacing & Layout

- Mobile-first responsive design
- Flexbox for most layouts
- CSS Grid for complex 2D layouts
- Tailwind spacing scale for consistency

## Mobile Optimization

All components are fully responsive:

- Mobile navigation with collapsible sidebar
- Touch-friendly button sizes (min 44x44px)
- Adaptive layouts for different screen sizes
- Mobile-optimized forms and inputs
- Responsive tables and data displays

## Getting Started

### Prerequisites

- Node.js 18+
- pnpm package manager

### Installation

```bash
# Install dependencies
pnpm install

# Start development server
pnpm dev

# Build for production
pnpm build

# Preview production build
pnpm preview
```

The app will be available at `http://localhost:3000`

### Demo Credentials

```
Email: brand@demo.com
Password: demo123
```

## Key Features Implementation

### Authentication Flow

1. User navigates to `/brand/login` or `/brand/register`
2. On successful login, token stored in localStorage
3. Protected routes redirect to login if not authenticated
4. After first login, user is directed to onboarding if not completed
5. Dashboard is main hub after onboarding completion

### Campaign Lifecycle

1. Create new campaign with details, budget, timeline
2. Define specific deliverables for the campaign
3. Search and discover creators matching campaign needs
4. Initiate collaboration request with selected creators
5. Negotiate terms in deal room with creators
6. Once agreement reached, generate and e-sign contract
7. Track deliverable submissions and approvals
8. Process payments upon completion

### Real-time Features

- Deal room messaging with creators
- Live proposal version tracking
- Real-time deliverable status updates
- Notification system for pending actions

## Advanced UX Features

### Data Visualization

- Campaign performance charts
- Creator matching algorithm results
- Payment settlement timelines
- Deal win rates and metrics

### Search & Filtering

- Creator discovery with multi-criteria filtering
- Campaign search with status filtering
- Deal room filtering by status, budget, timeline
- Contract search by creator or campaign

### Mobile Navigation

- Bottom tab navigation on mobile
- Collapsible sidebar on desktop
- Quick action buttons
- Context-aware back navigation

## API Integration (Future)

The app is structured to easily integrate with a backend:

- Create `src/lib/api.ts` with API client configuration
- Replace mock data in components with API calls
- Use React Query for data caching and synchronization
- Implement WebSocket for real-time messaging

## Browser Support

- Chrome/Edge (latest 2 versions)
- Firefox (latest 2 versions)
- Safari (latest 2 versions)
- Mobile browsers (iOS Safari, Chrome Mobile)

## Performance Optimizations

- Code splitting with React.lazy()
- Image optimization with proper sizing
- CSS-in-JS minimization
- Efficient re-renders with React hooks
- Lazy loading of route components

## Security Considerations

- All sensitive data cleared on logout
- Token stored in localStorage (for demo - use secure cookies in production)
- Protected routes with authentication checks
- Input validation on forms
- XSS prevention through React escaping

## Future Enhancements

- [ ] WebSocket integration for real-time messaging
- [ ] Advanced analytics dashboard
- [ ] Batch creator outreach tools
- [ ] AI-powered creator recommendations
- [ ] Integration with payment processors
- [ ] Calendar and scheduling features
- [ ] Document generation and management
- [ ] Compliance and audit logging

## Deployment

### Vercel (Recommended)

```bash
# Connect GitHub repository and deploy
# Environment variables automatically managed
```

### Docker

```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN pnpm install
COPY . .
RUN pnpm build
EXPOSE 3000
CMD ["pnpm", "preview"]
```

## Contributing

When adding new features:

1. Follow the existing component structure
2. Use TypeScript for type safety
3. Maintain mobile responsiveness
4. Update this README with major changes
5. Test all screen sizes before submitting

## License

© 2024 Creator Collaboration OS. All rights reserved.

## Support

For issues or questions, please refer to the project documentation or contact support.

---

**Built with** ❤️ **using React, Vite, and Tailwind CSS**
