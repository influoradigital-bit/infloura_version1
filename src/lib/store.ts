// Creator Collaboration OS - Global State Management
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type {
  User,
  Workspace,
  Campaign,
  CreatorProfile
} from './types';

// ============================================
// AUTH STORE
// ============================================

interface AuthState {
  user: User | null;
  workspace: Workspace | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  setUser: (user: User | null) => void;
  setWorkspace: (workspace: Workspace | null) => void;
  login: (user: User, workspace?: Workspace) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      workspace: null,
      isAuthenticated: false,
      isLoading: true,
      setUser: (user) => set({ user, isAuthenticated: !!user }),
      setWorkspace: (workspace) => set({ workspace }),
      login: (user, workspace) => set({ 
        user, 
        workspace: workspace || null, 
        isAuthenticated: true,
        isLoading: false 
      }),
      logout: () => set({ 
        user: null, 
        workspace: null, 
        isAuthenticated: false,
        isLoading: false 
      }),
    }),
    {
      name: 'influora-auth',
      partialize: () => ({}),
    }
  )
);

// ============================================
// CAMPAIGN STORE
// ============================================

interface CampaignState {
  campaigns: Campaign[];
  activeCampaign: Campaign | null;
  isLoading: boolean;
  setCampaigns: (campaigns: Campaign[]) => void;
  setActiveCampaign: (campaign: Campaign | null) => void;
  addCampaign: (campaign: Campaign) => void;
  updateCampaign: (id: string, updates: Partial<Campaign>) => void;
  removeCampaign: (id: string) => void;
}

export const useCampaignStore = create<CampaignState>((set) => ({
  campaigns: [],
  activeCampaign: null,
  isLoading: false,
  setCampaigns: (campaigns) => set({ campaigns }),
  setActiveCampaign: (campaign) => set({ activeCampaign: campaign }),
  addCampaign: (campaign) => set((state) => ({ 
    campaigns: [...state.campaigns, campaign] 
  })),
  updateCampaign: (id, updates) => set((state) => ({
    campaigns: state.campaigns.map((c) => 
      c.id === id ? { ...c, ...updates } : c
    ),
    activeCampaign: state.activeCampaign?.id === id 
      ? { ...state.activeCampaign, ...updates } 
      : state.activeCampaign
  })),
  removeCampaign: (id) => set((state) => ({
    campaigns: state.campaigns.filter((c) => c.id !== id),
    activeCampaign: state.activeCampaign?.id === id ? null : state.activeCampaign
  })),
}));

// ============================================
// DISCOVERY STORE (for finding creators)
// ============================================

interface DiscoveryFilters {
  platforms: string[];
  categories: string[];
  followerRange: { min: number; max: number } | null;
  engagementRange: { min: number; max: number } | null;
  location: string | null;
  isVerified: boolean | null;
  budgetRange: { min: number; max: number } | null;
}

interface DiscoveryState {
  creators: CreatorProfile[];
  filteredCreators: CreatorProfile[];
  filters: DiscoveryFilters;
  searchQuery: string;
  isLoading: boolean;
  setCreators: (creators: CreatorProfile[]) => void;
  setFilters: (filters: Partial<DiscoveryFilters>) => void;
  setSearchQuery: (query: string) => void;
  resetFilters: () => void;
  applyFilters: () => void;
}

const defaultFilters: DiscoveryFilters = {
  platforms: [],
  categories: [],
  followerRange: null,
  engagementRange: null,
  location: null,
  isVerified: null,
  budgetRange: null,
};

// ============================================
// UI STORE
// ============================================

interface UIState {
  sidebarOpen: boolean;
  mobileMenuOpen: boolean;
  activeModal: string | null;
  modalData: Record<string, unknown> | null;
  toggleSidebar: () => void;
  setSidebarOpen: (open: boolean) => void;
  toggleMobileMenu: () => void;
  setMobileMenuOpen: (open: boolean) => void;
  closeMobileMenu: () => void;
  openModal: (modal: string, data?: Record<string, unknown>) => void;
  closeModal: () => void;
  /** Brand product-tour walkthrough (re-launchable from the Help menu). */
  tourOpen: boolean;
  openTour: () => void;
  closeTour: () => void;
}

export const useUIStore = create<UIState>((set) => ({
  sidebarOpen: true,
  mobileMenuOpen: false,
  activeModal: null,
  modalData: null,
  toggleSidebar: () => set((state) => ({ sidebarOpen: !state.sidebarOpen })),
  setSidebarOpen: (open) => set({ sidebarOpen: open }),
  toggleMobileMenu: () => set((state) => ({ mobileMenuOpen: !state.mobileMenuOpen })),
  setMobileMenuOpen: (open) => set({ mobileMenuOpen: open }),
  closeMobileMenu: () => set({ mobileMenuOpen: false }),
  openModal: (modal, data) => set({ activeModal: modal, modalData: data || null }),
  closeModal: () => set({ activeModal: null, modalData: null }),
  tourOpen: false,
  openTour: () => set({ tourOpen: true }),
  closeTour: () => set({ tourOpen: false }),
}));
