# Quick Start Guide - Creator OS Brand Frontend

## Getting Started in 2 Minutes

### 1. Prerequisites
Ensure you have:
- Node.js 18 or higher
- pnpm (or npm/yarn)

### 2. Installation
```bash
# Install all dependencies
pnpm install

# Start the development server
pnpm dev
```

The app will be available at `http://localhost:3000`

### 3. Demo Login
Use these credentials to test:
```
Email: brand@demo.com
Password: demo123
```

## Key Features

### Dashboard (`/brand/dashboard`)
Your hub for managing all campaigns and collaborations:
- Campaign status overview
- Recent deal activity
- Performance metrics
- Quick action buttons

### Campaigns (`/brand/campaigns`)
Create and manage marketing campaigns:
- List all campaigns with status filtering
- Create new campaigns with detailed briefs
- Define deliverables and budgets
- Set timelines and requirements
- Upload campaign assets

### Creator Discovery (`/brand/discover`)
Find the perfect creators for your campaigns:
- Search creators by name or niche
- Filter by platform, engagement rate, followers
- View detailed creator profiles
- Compare multiple creators side-by-side
- Save favorite creators

### Deal Rooms (`/brand/deals`)
Negotiate collaboration terms:
- List all active deal rooms
- View proposal history with version control
- Send messages directly to creators
- Track deal status and progress
- Submit counter-proposals

### Contracts & Deliverables (`/brand/contracts`)
Manage agreements and track content:
- View and e-sign contracts
- Track deliverable submissions
- Review and approve content
- Request revisions if needed
- Monitor payment schedules

## Mobile Optimization

All pages are fully responsive:
- Touch-friendly buttons (min 44x44px)
- Adaptive layouts for all screen sizes
- Collapsible navigation on mobile
- Optimized forms and inputs
- Mobile-first design approach

## Architecture

### Frontend Stack
- **React 19** - UI framework
- **Vite** - Fast build tool
- **TypeScript** - Type safety
- **Tailwind CSS** - Styling
- **React Router** - Navigation
- **Zustand** - State management
- **React Query** - Data fetching
- **Lucide React** - Icons

### Project Structure
```
src/
├── pages/          # Page components
├── components/     # Reusable components
├── lib/           # Utilities and hooks
├── hooks/         # Custom React hooks
└── App.tsx        # Main app with routing
```

## Common Tasks

### Create a Campaign
1. Navigate to Campaigns
2. Click "New Campaign"
3. Fill out campaign details (name, budget, timeline)
4. Define deliverables
5. Select target creator profile
6. Submit

### Search for Creators
1. Go to Discover
2. Use search bar to find creators by name
3. Apply filters (platform, engagement, followers)
4. Click on creator profile to view details
5. Click "Initiate Collaboration" to start deal

### Approve a Deliverable
1. Go to Contracts & Deliverables
2. Select a contract
3. Click on "Deliverables" tab
4. Review submitted content
5. Click "Approve" or "Request Revisions"

### Send a Message
1. Go to Deal Rooms
2. Select a deal
3. Click on "Messages" tab
4. Type your message
5. Click "Send"

## Customization

### Changing Colors
Edit `src/app/globals.css` to modify the design tokens:
```css
@theme {
  --color-primary: your-color;
  --color-accent: your-color;
}
```

### Adding a New Page
1. Create component in `src/components/brand/`
2. Create page wrapper in `src/pages/`
3. Add route in `src/App.tsx`
4. Add navigation link in `src/components/brand/brand-layout.tsx`

### Using the API Client
All API calls go through `src/lib/api.ts`:
```typescript
import { brandApi } from '@/lib/api';

// Mock data - replace with real API
await brandApi.getCampaigns();
await brandApi.createCampaign(campaignData);
```

## Deployment

### Deploy to Vercel (Recommended)
```bash
# Install Vercel CLI
npm i -g vercel

# Deploy
vercel
```

### Build for Production
```bash
pnpm build      # Creates optimized build
pnpm preview    # Preview production build locally
```

## Troubleshooting

### Port Already in Use
If port 3000 is busy:
```bash
# Use a different port
pnpm dev -- --port 3001
```

### Dependencies Not Installing
```bash
# Clear cache and reinstall
rm -rf node_modules pnpm-lock.yaml
pnpm install
```

### Components Not Importing
Ensure path aliases are correct in `tsconfig.json`:
```json
{
  "compilerOptions": {
    "paths": {
      "@/*": ["./src/*"]
    }
  }
}
```

## Performance Tips

- All images are loaded from unsplash (external)
- Components are code-split with React.lazy()
- State management is optimized with Zustand
- Data queries cached with React Query
- Tailwind CSS is purged for production

## Browser Support

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+
- Mobile Safari (iOS 14+)
- Chrome Mobile

## Need Help?

1. Check the README.md for detailed documentation
2. Review component examples in each feature folder
3. Check console for error messages with `[v0]` prefix
4. All components follow shadcn/ui patterns

## Next Steps

1. Log in with demo credentials
2. Complete the onboarding flow
3. Create a test campaign
4. Search for creators
5. Initiate a deal room
6. Explore all features

Happy building!
