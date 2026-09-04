/**
 * INFLUORA ADMIN PANEL — Users Page Tests (Brands table)
 * Owner: Ananya (Frontend)
 * Reference: F7 — admin Brands table must render BOTH `ownerPhone` and
 * `workspacePhone` (AdminBrandDtos.java), distinctly, per column, matching the
 * `+91`-prefix rule that applies to `ownerPhone` only (strict Indian mobile) and
 * NOT to `workspacePhone` (loose international, may already carry a country code).
 *
 * Run: npx vitest run src/admin/pages/UsersPage.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import UsersPage from './UsersPage';
import type { Brand } from '../types/admin.types';
import { KycStatus } from '../types/admin.types';

const mockUseBrandList = vi.fn();
vi.mock('../hooks/useBrandList', () => ({
  useBrandList: () => mockUseBrandList(),
  BRAND_LIST_PAGE_SIZE: 20,
}));

// Not under test here — stub to isolate the Brands tab.
vi.mock('../hooks/useCreatorList', () => ({
  useCreatorList: () => ({
    creators: [],
    totalCount: 0,
    totalPages: 0,
    isLoading: false,
    error: null,
    filters: {},
    setFilters: vi.fn(),
    page: 1,
    setPage: vi.fn(),
  }),
  CREATOR_LIST_PAGE_SIZE: 20,
}));

vi.mock('../hooks/useCreatorApplications', () => ({
  useCreatorApplications: () => ({
    applications: [],
    totalCount: 0,
    totalPages: 0,
    isLoading: false,
    error: null,
    page: 1,
    setPage: vi.fn(),
  }),
  PENDING_APPLICATIONS_PAGE_SIZE: 20,
}));

const BASE_BRAND: Brand = {
  id: 'brand-1',
  name: 'Acme Corp',
  email: 'contact@acme.test',
  workspacePhone: null,
  ownerPhone: null,
  industry: 'Fashion',
  size: 'SMB',
  kycStatus: KycStatus.PENDING,
  campaignCount: 3,
  totalSpend: 50000,
  isSuspended: false,
  createdAt: '2026-01-15T10:30:00Z',
};

function renderBrandsTab(brands: Brand[]) {
  mockUseBrandList.mockReturnValue({
    brands,
    totalCount: brands.length,
    totalPages: 1,
    isLoading: false,
    error: null,
    filters: {},
    setFilters: vi.fn(),
    page: 1,
    setPage: vi.fn(),
  });

  return render(
    <MemoryRouter initialEntries={['/']}>
      <UsersPage />
    </MemoryRouter>,
  );
}

describe('UsersPage — Brands table (F7 phone columns)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders both phone columns with the Workspace/Owner vocabulary', () => {
    renderBrandsTab([BASE_BRAND]);

    expect(screen.getByRole('columnheader', { name: 'Owner Phone' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Workspace Phone' })).toBeInTheDocument();
  });

  it('renders ownerPhone with a +91 prefix and workspacePhone as-is when both present', () => {
    renderBrandsTab([
      { ...BASE_BRAND, ownerPhone: '9876543210', workspacePhone: '+44 20 7946 0958' },
    ]);

    // ownerPhone: strict Indian mobile -> +91-prefixed.
    expect(screen.getByText('+91 9876543210')).toBeInTheDocument();
    // workspacePhone: loose international, already carries its own country code ->
    // rendered verbatim, never re-prefixed with +91.
    expect(screen.getByText('+44 20 7946 0958')).toBeInTheDocument();
    expect(screen.queryByText('+91 +44 20 7946 0958')).not.toBeInTheDocument();
  });

  it('shows "— Not provided" for each field independently when null', () => {
    renderBrandsTab([{ ...BASE_BRAND, ownerPhone: null, workspacePhone: '+1 415 555 0100' }]);

    const notProvidedCells = screen.getAllByText('— Not provided');
    expect(notProvidedCells).toHaveLength(1); // only ownerPhone is null here
    expect(screen.getByText('+1 415 555 0100')).toBeInTheDocument();
  });

  it('shows "— Not provided" for both fields when both are null (pre-PHONE-0904 brand)', () => {
    renderBrandsTab([BASE_BRAND]); // ownerPhone: null, workspacePhone: null

    expect(screen.getAllByText('— Not provided')).toHaveLength(2);
  });

  it('renders one distinguishable value per row when owner and workspace numbers differ', () => {
    renderBrandsTab([
      { ...BASE_BRAND, ownerPhone: '9876543210', workspacePhone: '9123456780' },
    ]);

    // Same 10-digit shape, but only ownerPhone gets +91 — the two must not collide as text.
    expect(screen.getByText('+91 9876543210')).toBeInTheDocument();
    expect(screen.getByText('9123456780')).toBeInTheDocument();
    expect(screen.queryByText('+91 9123456780')).not.toBeInTheDocument();
  });

  it('keeps the empty-state colSpan and loading-skeleton cell count in sync with the 8-column table', () => {
    renderBrandsTab([]);

    expect(screen.getByText('No brands match the current filters.')).toBeInTheDocument();
    const emptyCell = screen.getByText('No brands match the current filters.').closest('td');
    expect(emptyCell).toHaveAttribute('colspan', '8');

    // Header carries exactly 8 columns (Brand, Owner Phone, Workspace Phone, Industry,
    // KYC Status, Campaigns, Spend, Status).
    expect(screen.getAllByRole('columnheader')).toHaveLength(8);
  });

  it('renders 8 skeleton cells per loading row, matching the 8-column header', () => {
    mockUseBrandList.mockReturnValue({
      brands: [],
      totalCount: 0,
      totalPages: 0,
      isLoading: true,
      error: null,
      filters: {},
      setFilters: vi.fn(),
      page: 1,
      setPage: vi.fn(),
    });

    render(
      <MemoryRouter initialEntries={['/']}>
        <UsersPage />
      </MemoryRouter>,
    );

    // 5 skeleton rows x 8 cells each = 40 pulse placeholders.
    expect(document.querySelectorAll('tbody tr').length).toBe(5);
    const firstSkeletonRow = document.querySelectorAll('tbody tr')[0];
    expect(firstSkeletonRow?.querySelectorAll('td').length).toBe(8);
  });
});
