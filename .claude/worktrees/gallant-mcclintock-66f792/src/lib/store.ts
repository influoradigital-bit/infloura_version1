// Creator Collaboration OS - Global State Management
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { 
  User, 
  Workspace, 
  Campaign, 
  Collaboration, 
  Notification,
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
// COLLABORATION STORE
// ============================================

interface CollaborationState {
  collaborations: Collaboration[];
  activeCollaboration: Collaboration | null;
  setCollaborations: (collaborations: Collaboration[]) => void;
  setActiveCollaboration: (collaboration: Collaboration | null) => void;
  updateCollaboration: (id: string, updates: Partial<Collaboration>) => void;
}

export const useCollaborationStore = create<CollaborationState>((set) => ({
  collaborations: [],
  activeCollaboration: null,
  setCollaborations: (collaborations) => set({ collaborations }),
  setActiveCollaboration: (collaboration) => set({ activeCollaboration: collaboration }),
  updateCollaboration: (id, updates) => set((state) => ({
    collaborations: state.collaborations.map((c) =>
      c.id === id ? { ...c, ...updates } : c
    ),
    activeCollaboration: state.activeCollaboration?.id === id
      ? { ...state.activeCollaboration, ...updates }
      : state.activeCollaboration
  })),
}));

// ============================================
// NOTIFICATION STORE
// ============================================

interface NotificationState {
  notifications: Notification[];
  unreadCount: number;
  setNotifications: (notifications: Notification[]) => void;
  addNotification: (notification: Notification) => void;
  markAsRead: (id: string) => void;
  markAllAsRead: () => void;
  clearNotifications: () => void;
}

export const useNotificationStore = create<NotificationState>((set) => ({
  notifications: [],
  unreadCount: 0,
  setNotifications: (notifications) => set({ 
    notifications,
    unreadCount: notifications.filter(n => !n.isRead).length
  }),
  addNotification: (notification) => set((state) => ({
    notifications: [notification, ...state.notifications],
    unreadCount: state.unreadCount + (notification.isRead ? 0 : 1)
  })),
  markAsRead: (id) => set((state) => ({
    notifications: state.notifications.map((n) =>
      n.id === id ? { ...n, isRead: true, readAt: new Date() } : n
    ),
    unreadCount: Math.max(0, state.unreadCount - 1)
  })),
  markAllAsRead: () => set((state) => ({
    notifications: state.notifications.map((n) => ({ 
      ...n, 
      isRead: true, 
      readAt: n.readAt || new Date() 
    })),
    unreadCount: 0
  })),
  clearNotifications: () => set({ notifications: [], unreadCount: 0 }),
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

export const useDiscoveryStore = create<DiscoveryState>((set, get) => ({
  creators: [],
  filteredCreators: [],
  filters: defaultFilters,
  searchQuery: '',
  isLoading: false,
  setCreators: (creators) => set({ creators, filteredCreators: creators }),
  setFilters: (filters) => set((state) => ({ 
    filters: { ...state.filters, ...filters } 
  })),
  setSearchQuery: (query) => set({ searchQuery: query }),
  resetFilters: () => set({ filters: defaultFilters }),
  applyFilters: () => {
    const { creators, filters, searchQuery } = get();
    let filtered = [...creators];

    // Search query
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter((c) =>
        c.displayName.toLowerCase().includes(query) ||
        c.bio?.toLowerCase().includes(query) ||
        c.categories.some((cat) => cat.toLowerCase().includes(query))
      );
    }

    // Platform filter
    if (filters.platforms.length > 0) {
      filtered = filtered.filter((c) =>
        c.platforms.some((p) => filters.platforms.includes(p.platform))
      );
    }

    // Categories filter
    if (filters.categories.length > 0) {
      filtered = filtered.filter((c) =>
        c.categories.some((cat) => filters.categories.includes(cat))
      );
    }

    // Follower range
    if (filters.followerRange) {
      filtered = filtered.filter(
        (c) =>
          c.totalFollowers >= filters.followerRange!.min &&
          c.totalFollowers <= filters.followerRange!.max
      );
    }

    // Engagement range
    if (filters.engagementRange) {
      filtered = filtered.filter(
        (c) =>
          c.engagementRate >= filters.engagementRange!.min &&
          c.engagementRate <= filters.engagementRange!.max
      );
    }

    // Verified only
    if (filters.isVerified !== null) {
      filtered = filtered.filter((c) => c.isVerified === filters.isVerified);
    }

    set({ filteredCreators: filtered });
  },
}));

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
}));
