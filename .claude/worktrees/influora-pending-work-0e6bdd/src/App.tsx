import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useParams } from 'react-router-dom';
import BrandLoginPage from '@/pages/brand-login';
import BrandRegisterPage from '@/pages/brand-register';
import BrandForgotPasswordPage from '@/pages/brand-forgot-password';
import BrandDashboardPage from '@/pages/brand-dashboard';
import BrandMeeraPage from '@/pages/brand-meera';
import BrandOnboardingPage from '@/pages/brand-onboarding';
import BrandCampaignsPage from '@/pages/brand-campaigns';
import BrandNewCampaignPage from '@/pages/brand-new-campaign';
import BrandNewHypeCampaignPage from '@/pages/brand-new-hype-campaign';
import BrandDiscoverPage from '@/pages/brand-discover';
import BrandCampaignDetailPage from '@/pages/brand-campaign-detail';
import BrandEditCampaignPage from '@/pages/brand-edit-campaign';
import BrandCreatorProfilePage from '@/pages/brand-creator-profile';
import BrandWalletPage from '@/pages/brand-wallet';
import BrandSettingsPage from '@/pages/brand-settings';
import BrandChatPage from '@/pages/brand-chat';
import BrandContractsPage from '@/pages/brand-contracts';
import BrandMessagesPage from '@/pages/brand-messages';
import NotFoundPage from '@/pages/not-found';
import LandingPage from '@/pages/landing';
import AdminDashboardPage from '@/pages/admin-dashboard';
import StaticPage from '@/pages/static-page';
import { BrandLayout } from '@/components/brand/brand-layout';

// Creator Pages
import CreatorLoginPage from '@/pages/creator-login';
import CreatorRegisterPage from '@/pages/creator-register';
import CreatorOnboardingPage from '@/pages/creator-onboarding';
import CreatorDealsPage from '@/pages/creator-deals';
import CreatorWalletPage from '@/pages/creator-wallet';
import CreatorProfilePage from '@/pages/creator-profile';
import CreatorSettingsPage from '@/pages/creator-settings';
import CreatorChatPage from '@/pages/creator-chat';
import CreatorPortfolioEditorPage from '@/pages/creator-portfolio-editor';
import CreatorPortfolioPublicPage from '@/pages/creator-portfolio-public';
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

// Deal Room was consolidated into the chat-first surface at /brand/chat.
// Redirect the retired /brand/deals/:id URLs, preserving the deal id as ?deal=.
const DealRedirect = () => {
  const { id } = useParams();
  return <Navigate to={id ? `/brand/chat?deal=${id}` : '/brand/chat'} replace />;
};

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Auth Routes */}
        <Route path="/brand/login" element={<BrandLoginPage />} />
        <Route path="/brand/register" element={<BrandRegisterPage />} />
        <Route path="/brand/forgot-password" element={<BrandForgotPasswordPage />} />

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

        {/* Retired surfaces — Deal Room + Pipeline now live at /brand/chat */}
        <Route path="/brand/deals" element={<Navigate to="/brand/chat" replace />} />
        <Route path="/brand/deals/:id" element={<DealRedirect />} />
        <Route path="/brand/pipeline" element={<Navigate to="/brand/chat" replace />} />

        {/* ==================== CREATOR ROUTES ==================== */}

        {/* Creator Auth Routes */}
        <Route path="/creator/login" element={<CreatorLoginPage />} />
        <Route path="/creator/register" element={<CreatorRegisterPage />} />

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

        {/* Admin operations console — demo data only until M2 backend lands */}
        <Route path="/admin" element={<AdminDashboardPage />} />

        {/* Public landing page */}
        <Route path="/" element={<LandingPage />} />

        {/* Dev-only motion skills test — not in production builds */}
        {import.meta.env.DEV && (
          <Route path="/dev/motion-skills" element={<DevMotionSkillsPage />} />
        )}

        {/* Static pages — must precede the /:handle catch-all below, otherwise
            single-segment paths get swallowed by the public-portfolio route. */}
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
    </BrowserRouter>
  );
}
