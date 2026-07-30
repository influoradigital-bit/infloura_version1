import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ErrorBoundary from '@/components/ErrorBoundary';
import { Toaster } from '@/components/ui/toaster';
import { DemoModeBanner } from '@/components/DemoModeBanner';
import BrandLoginPage from '@/pages/brand-login';
import BrandRegisterPage from '@/pages/brand-register';
import BrandForgotPasswordPage from '@/pages/brand-forgot-password';
import BrandResetPasswordPage from '@/pages/brand-reset-password';
import BrandDashboardPage from '@/pages/brand-dashboard';
import BrandMeeraPage from '@/pages/brand-meera';
import BrandOnboardingPage from '@/pages/brand-onboarding';
import BrandCampaignsPage from '@/pages/brand-campaigns';
import BrandDealsPage from '@/pages/brand-deals';
import BrandPipelinePage from '@/pages/brand-pipeline';
import BrandNewCampaignPage from '@/pages/brand-new-campaign';
import BrandNewHypeCampaignPage from '@/pages/brand-new-hype-campaign';
import BrandDiscoverPage from '@/pages/brand-discover';
import BrandCampaignDetailPage from '@/pages/brand-campaign-detail';
import BrandEditCampaignPage from '@/pages/brand-edit-campaign';
import BrandCreatorProfilePage from '@/pages/brand-creator-profile';
import BrandWalletPage from '@/pages/brand-wallet';
import BrandSettingsPage from '@/pages/brand-settings';
import BrandBillingSettingsPage from '@/pages/brand-billing-settings';
import BrandChatPage from '@/pages/brand-chat';
import BrandContractsPage from '@/pages/brand-contracts';
import BrandMessagesPage from '@/pages/brand-messages';
import NotFoundPage from '@/pages/not-found';
import LandingPage from '@/pages/landing';
import PricingPage from '@/pages/pricing';
import AdminLoginPage from '@/pages/admin-login';
import AdminConsolePage from '@/pages/admin-console';
import StaticPage from '@/pages/static-page';
import { BrandLayout } from '@/components/brand/brand-layout';
import BrandAnalyticsPage from '@/pages/brand-analytics';
import BrandCampaignTrackingPage from '@/pages/brand-campaign-tracking';
import BrandCreatorAnalyticsPage from '@/pages/brand-creator-analytics';
import BrandDisputesPage from '@/pages/brand-disputes';
import BrandReviewsPage from '@/pages/brand-reviews';
import BrandHelpPage from '@/pages/brand-help';
import AboutPage from '@/pages/about';
import ContactPage from '@/pages/contact';
import HowItWorksBrandsPage from '@/pages/how-it-works-brands';
import HowItWorksCreatorsPage from '@/pages/how-it-works-creators';
import EscrowFeaturePage from '@/pages/features/escrow';
import DealRoomFeaturePage from '@/pages/features/deal-room';
import HypeFeaturePage from '@/pages/features/hype';
import BlogIndexPage from '@/pages/blog/index';
import BlogPostPage from '@/pages/blog/post';

// Creator Pages
import CreatorLoginPage from '@/pages/creator-login';
import CreatorRegisterPage from '@/pages/creator-register';
import CreatorForgotPasswordPage from '@/pages/creator-forgot-password';
import CreatorOnboardingPage from '@/pages/creator-onboarding';
import CreatorDealsPage from '@/pages/creator-deals';
import CreatorWalletPage from '@/pages/creator-wallet';
import CreatorProfilePage from '@/pages/creator-profile';
import CreatorSettingsPage from '@/pages/creator-settings';
import CreatorChatPage from '@/pages/creator-chat';
import CreatorPortfolioEditorPage from '@/pages/creator-portfolio-editor';
import CreatorPortfolioPublicPage from '@/pages/creator-portfolio-public';
import CreatorAnalyticsPage from '@/pages/creator-analytics';
import CreatorDashboardPage from '@/pages/creator-dashboard';
import CreatorCampaignsPage from '@/pages/creator-campaigns';
import CreatorApplicationsPage from '@/pages/creator-applications';
import CreatorCopilotPage from '@/pages/creator-copilot';
import CreatorCampaignDetailPage from '@/pages/creator-campaign-detail';
import CreatorDisputesPage from '@/pages/creator-disputes';
import CreatorReviewsPage from '@/pages/creator-reviews';
import CreatorCouponsPage from '@/pages/creator-coupons';
import CreatorAffiliateEarningsPage from '@/pages/creator-affiliate-earnings';
import CreatorMetaCallbackPage from '@/pages/creator-meta-callback';
import DevMotionSkillsPage from '@/pages/dev-motion-skills';
import { CreatorLayout } from '@/components/creator/creator-layout';

// Protected Route Component
const ProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const isAuthenticated = localStorage.getItem('brand_token');
  // Demo/test bypass — dev-only. `import.meta.env.DEV` is a compile-time
  // constant (Vite `define`s it to a literal boolean per mode), so a
  // production build statically resolves this branch to `false` and the
  // dead-stripped code path can never honor `?demo=true` (Kabir A2).
  const isDemoMode = import.meta.env.DEV && new URLSearchParams(window.location.search).get('demo') === 'true';
  return isAuthenticated || isDemoMode ? <>{children}</> : <Navigate to="/brand/login" />;
};

// Layout wrapper for brand pages
const BrandLayoutWrapper = ({ children }: { children: React.ReactNode }) => {
  return (
    <ProtectedRoute>
      <BrandLayout>{children}</BrandLayout>
    </ProtectedRoute>
  );
};

// Creator Protected Route Component
const CreatorProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const isAuthenticated = localStorage.getItem('creator_token');
  // Demo/test bypass — dev-only, see ProtectedRoute above (Kabir A2).
  const isDemoMode = import.meta.env.DEV && new URLSearchParams(window.location.search).get('demo') === 'true';
  return isAuthenticated || isDemoMode ? <>{children}</> : <Navigate to="/creator/login" />;
};

// Admin Protected Route Component — gates the real operations console.
// Mirrors the brand/creator guards but keys off `admin_token`, which
// AdminLoginPage sets on a successful `authApi.login()`. Unauthenticated
// visitors are redirected to the unguarded /admin/login. No dev `?demo=true`
// bypass here: the admin console handles money/KYC/moderation, so it must
// never be reachable without a real session (Kabir — auth gate).
const AdminProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const isAuthenticated = localStorage.getItem('admin_token');
  return isAuthenticated ? <>{children}</> : <Navigate to="/admin/login" replace />;
};

// /brand/deals/:id now deep-links directly into the Deal Room dashboard, which
// reads the :id route param (and legacy ?deal= query) via useParams/
// useSearchParams and auto-selects the matching deal once the list loads.
// See src/components/brand/deals/deal-room-dashboard.tsx.

// Single shared client for every `useQuery`/`useMutation` call in the app
// (admin console hooks + brand billing settings) — previously missing entirely,
// so any component using react-query would throw "No QueryClient set" at runtime.
const queryClient = new QueryClient();

/**
 * CR-10 — the error boundary lives INSIDE `<BrowserRouter>` and resets on every
 * navigation.
 *
 * It used to wrap the Router from the outside with no reset path, so a single
 * transient throw on a single page unmounted the entire routing tree for the
 * rest of the session: every following tab click re-rendered the same dead
 * fallback because there was no Router left to route with. Mounted here, a
 * throw takes out only the current route's render, and moving to any other
 * route (or pressing "Try again") brings the app back.
 */
function RoutedErrorBoundary({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  return <ErrorBoundary resetKey={location.pathname}>{children}</ErrorBoundary>;
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
    <BrowserRouter>
    <RoutedErrorBoundary>
      <Routes>
        {/* Auth Routes */}
        <Route path="/brand/login" element={<BrandLoginPage />} />
        <Route path="/brand/register" element={<BrandRegisterPage />} />
        <Route path="/brand/forgot-password" element={<BrandForgotPasswordPage />} />
        {/* BR-03: bare path, unprefixed — matches the link AuthService actually emails
            (webBaseUrl + "/reset-password?token=..."), not /brand/reset-password. Role-agnostic
            page (see brand-reset-password.tsx) since the same email link is sent for both
            brand and creator forgot-password requests. */}
        <Route path="/reset-password" element={<BrandResetPasswordPage />} />

        {/* Onboarding Routes */}
        <Route path="/brand/onboarding" element={<BrandOnboardingPage />} />

        {/* Protected Routes with Layout */}
        <Route
          path="/brand/dashboard"
          element={
            <BrandLayoutWrapper>
              <BrandDashboardPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/campaigns"
          element={
            <BrandLayoutWrapper>
              <BrandCampaignsPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/campaigns/new"
          element={
            <BrandLayoutWrapper>
              <BrandNewCampaignPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/campaigns/new/hype"
          element={
            <BrandLayoutWrapper>
              <BrandNewHypeCampaignPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/campaigns/:id"
          element={
            <BrandLayoutWrapper>
              <BrandCampaignDetailPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/campaigns/:id/edit"
          element={
            <BrandLayoutWrapper>
              <BrandEditCampaignPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/discover"
          element={
            <BrandLayoutWrapper>
              <BrandDiscoverPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/creators/:id"
          element={
            <BrandLayoutWrapper>
              <BrandCreatorProfilePage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/wallet"
          element={
            <BrandLayoutWrapper>
              <BrandWalletPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/chat"
          element={
            <BrandLayoutWrapper>
              <BrandChatPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/meera"
          element={
            <BrandLayoutWrapper>
              <BrandMeeraPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/contracts"
          element={
            <BrandLayoutWrapper>
              <BrandContractsPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/messages"
          element={
            <BrandLayoutWrapper>
              <BrandMessagesPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/settings"
          element={
            <BrandLayoutWrapper>
              <BrandSettingsPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/settings/billing"
          element={
            <BrandLayoutWrapper>
              <BrandBillingSettingsPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/analytics"
          element={
            <BrandLayoutWrapper>
              <BrandAnalyticsPage />
            </BrandLayoutWrapper>
          }
        />
        {/* :creatorId (not :id) — matches the useParams() destructure inside
            brand-creator-analytics.tsx and the route documented in that
            file's own header comment. */}
        <Route
          path="/brand/analytics/:creatorId"
          element={
            <BrandLayoutWrapper>
              <BrandCreatorAnalyticsPage />
            </BrandLayoutWrapper>
          }
        />
        {/* :campaignId (not :id) — matches the useParams<{ campaignId }>()
            destructure inside brand-campaign-tracking.tsx. */}
        <Route
          path="/brand/campaigns/:campaignId/tracking"
          element={
            <BrandLayoutWrapper>
              <BrandCampaignTrackingPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/disputes"
          element={
            <BrandLayoutWrapper>
              <BrandDisputesPage />
            </BrandLayoutWrapper>
          }
        />
        {/* No BrandLayoutWrapper — BrandReviewsPage self-wraps <BrandLayout>,
            so wrapping it again here would double-nest the sidebar/header
            (documented + verified bug, see brand-disputes.tsx header comment). */}
        <Route
          path="/brand/reviews"
          element={
            <ProtectedRoute>
              <BrandReviewsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/brand/help"
          element={
            <BrandLayoutWrapper>
              <BrandHelpPage />
            </BrandLayoutWrapper>
          }
        />

        <Route
          path="/brand/deals"
          element={
            <BrandLayoutWrapper>
              <BrandDealsPage />
            </BrandLayoutWrapper>
          }
        />
        {/* Deep-link to a single deal — same dashboard, auto-selects :id */}
        <Route
          path="/brand/deals/:id"
          element={
            <BrandLayoutWrapper>
              <BrandDealsPage />
            </BrandLayoutWrapper>
          }
        />
        <Route
          path="/brand/pipeline"
          element={
            <BrandLayoutWrapper>
              <BrandPipelinePage />
            </BrandLayoutWrapper>
          }
        />

        {/* ==================== CREATOR ROUTES ==================== */}

        {/* Creator Auth Routes */}
        <Route path="/creator/login" element={<CreatorLoginPage />} />
        <Route path="/creator/register" element={<CreatorRegisterPage />} />
        <Route path="/creator/forgot-password" element={<CreatorForgotPasswordPage />} />
        {/* Meta OAuth landing — unguarded (not CreatorProtectedRoute). Meta
            redirects the browser here directly with ?code=&state=, and the
            page reads those off window.location.search itself; gating it
            behind the auth guard would risk bouncing the browser to
            /creator/login before the callback can run if the session token
            is momentarily missing. Path matches influora.meta.redirect-uri
            (see creator-meta-callback.tsx header comment + the
            /creator/settings/meta/callback mock in src/lib/api.ts) rather
            than the /creator/meta/callback path in the original task spec —
            using the spec's path would mean Meta's real redirect never hits
            this route. */}
        <Route path="/creator/settings/meta/callback" element={<CreatorMetaCallbackPage />} />

        {/* Creator Onboarding */}
        <Route
          path="/creator/onboarding"
          element={
            <CreatorProtectedRoute>
              <CreatorOnboardingPage />
            </CreatorProtectedRoute>
          }
        />

        {/* Creator Protected Routes */}
        {/* Overview/home page — CreatorLayout's 3-item nav (Deals/Wallet) doesn't
            link here yet (deliberate per creator-layout.tsx's own header comment
            on the collapsed nav), but the page itself is real and coherent: wallet
            + deals + pending-action rollup, all sourced from existing live api.*
            clients (api.wallet.get, api.deals.list, api.creatorDeliverables.listForDeal
            — no new backend). Routed so it's reachable at a stable URL. */}
        <Route
          path="/creator/dashboard"
          element={
            <CreatorProtectedRoute>
              <CreatorDashboardPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/deals"
          element={
            <CreatorProtectedRoute>
              <CreatorDealsPage />
            </CreatorProtectedRoute>
          }
        />
        {/* Legacy redirects — Inbox + Active now live inside /creator/deals */}
        <Route path="/creator/inbox" element={<Navigate to="/creator/deals?status=new" replace />} />
        <Route path="/creator/active" element={<Navigate to="/creator/deals?status=in_progress" replace />} />
        <Route
          path="/creator/copilot"
          element={
            <CreatorProtectedRoute>
              <CreatorCopilotPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/wallet"
          element={
            <CreatorProtectedRoute>
              <CreatorWalletPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/profile"
          element={
            <CreatorProtectedRoute>
              <CreatorProfilePage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/settings"
          element={
            <CreatorProtectedRoute>
              <CreatorSettingsPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/chat"
          element={
            <CreatorProtectedRoute>
              <CreatorChatPage />
            </CreatorProtectedRoute>
          }
        />
        {/* Creator: manage own public portfolio page */}
        <Route
          path="/creator/portfolio"
          element={
            <CreatorProtectedRoute>
              <CreatorPortfolioEditorPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/analytics"
          element={
            <CreatorProtectedRoute>
              <CreatorAnalyticsPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/campaigns"
          element={
            <CreatorProtectedRoute>
              <CreatorCampaignsPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/campaigns/:id"
          element={
            <CreatorProtectedRoute>
              <CreatorCampaignDetailPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/applications"
          element={
            <CreatorProtectedRoute>
              <CreatorApplicationsPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/disputes"
          element={
            <CreatorProtectedRoute>
              <CreatorDisputesPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/reviews"
          element={
            <CreatorProtectedRoute>
              <CreatorReviewsPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/coupons"
          element={
            <CreatorProtectedRoute>
              <CreatorCouponsPage />
            </CreatorProtectedRoute>
          }
        />
        <Route
          path="/creator/affiliate"
          element={
            <CreatorProtectedRoute>
              <CreatorAffiliateEarningsPage />
            </CreatorProtectedRoute>
          }
        />

        {/* ==================== ADMIN ROUTES ==================== */}
        {/* Unguarded login; everything under /admin/* requires an admin_token.
            AdminConsolePage is a nested router (see src/pages/admin-console.tsx)
            so the splat path is required for its internal routes to resolve. */}
        <Route path="/admin/login" element={<AdminLoginPage />} />
        <Route
          path="/admin/*"
          element={
            <AdminProtectedRoute>
              <AdminConsolePage />
            </AdminProtectedRoute>
          }
        />

        {/* Public landing page */}
        <Route path="/" element={<LandingPage />} />

        {/* Dev-only motion skills test — not in production builds */}
        {import.meta.env.DEV && (
          <Route path="/dev/motion-skills" element={<DevMotionSkillsPage />} />
        )}

        {/* Static pages — must precede the /:handle catch-all below, otherwise
            single-segment paths get swallowed by the public-portfolio route. */}
        <Route path="/pricing" element={<PricingPage />} />
        <Route path="/about" element={<AboutPage />} />
        <Route path="/contact" element={<ContactPage />} />
        <Route path="/how-it-works/brands" element={<HowItWorksBrandsPage />} />
        <Route path="/how-it-works/creators" element={<HowItWorksCreatorsPage />} />
        <Route path="/features/escrow" element={<EscrowFeaturePage />} />
        <Route path="/features/deal-room" element={<DealRoomFeaturePage />} />
        <Route path="/features/hype" element={<HypeFeaturePage />} />
        <Route path="/blog" element={<BlogIndexPage />} />
        <Route path="/blog/category/:category" element={<BlogIndexPage />} />
        <Route path="/blog/:slug" element={<BlogPostPage />} />
        <Route
          path="/terms"
          element={
            <StaticPage
              title="Terms of Service"
              description="Our terms of service are being finalized and will be available here soon."
            />
          }
        />
        <Route
          path="/privacy"
          element={
            <StaticPage
              title="Privacy Policy"
              description="Our privacy policy is being finalized and will be available here soon."
            />
          }
        />
        <Route
          path="/support"
          element={
            <StaticPage
              title="Support"
              description="Need help? Support resources are being set up and will be available here soon."
            />
          }
        />

        {/* ==================== PUBLIC PORTFOLIO ==================== */}
        {/* influora.com/@username — no auth, indexable. Spec: docs/CREATOR-PORTFOLIO-PAGE.md
            NOTE: React Router 7 cannot match a literal-prefixed param ("/@:username"),
            so we capture the whole first segment as :handle and strip the leading "@"
            inside the page. Placed last so all known routes win on specificity. */}
        <Route path="/:handle" element={<CreatorPortfolioPublicPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
      {/* App-wide toast outlet — previously the <Toaster> was never mounted, so
          every toast({variant:'destructive'}) call across ~17 components silently
          rendered nothing. Mounted once here, all of them now surface.
          Inside the Router (CR-10) so it keeps rendering alongside the routes. */}
      <Toaster />
      <DemoModeBanner />
    </RoutedErrorBoundary>
    </BrowserRouter>
    </QueryClientProvider>
  );
}
