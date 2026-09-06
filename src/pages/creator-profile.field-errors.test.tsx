/**
 * F-0668 (dead-plumbing) — `ApiError.field`/`.fields` (carried through since F-0466) had ZERO
 * consumers anywhere outside src/lib/api.ts: a repo-wide search found every server-named field
 * validation error still surfacing as one generic toast message, exactly the symptom F-0466
 * described, meaning F-0466 was closed too generously.
 *
 * `PATCH /me/creator-profile` (CreatorProfilePatchRequest, influora-api CreatorProfileDtos.java)
 * carries real `@Size`/`@DecimalMin` bean validation on displayName/bio/city/username/rateMin/
 * rateMax — `GlobalExceptionHandler.handleValidation` turns a failure into a `VALIDATION_ERROR`
 * envelope whose `fields: [{field, message}]` array is keyed by these exact record component
 * names (`MethodArgumentNotValidException` → `FieldError.getField()`). This is a REAL, reachable
 * end-to-end path — not a fixture invented to justify the wiring — because none of these six
 * inputs enforce a client-side max length/non-negative bound, so a user genuinely can submit a
 * request the server rejects per-field.
 *
 * This is the ONE real form on a page this agent may edit whose PATCH endpoint actually emits
 * per-field errors, so it becomes the first real consumer end to end: `err.fields` is mapped onto
 * the matching input's inline error text, not folded into one generic message that names no
 * field.
 *
 * Run: npx vitest run src/pages/creator-profile.field-errors.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorProfilePage from './creator-profile';
import { toast } from '@/hooks/use-toast';
import type { CreatorProfileSelfResponse } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

const getMeMock = vi.fn();
const patchMeMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorProfile: {
        ...actual.api.creatorProfile,
        getMe: (...a: unknown[]) => getMeMock(...a),
        patchMe: (...a: unknown[]) => patchMeMock(...a),
      },
    },
  };
});

const BASE_PROFILE: CreatorProfileSelfResponse = {
  id: 'cr_1',
  userId: 'user_1',
  displayName: 'Priya Creates',
  username: 'priya_creates',
  bio: 'Fashion & lifestyle content creator.',
  avatarUrl: '',
  coverImageUrl: '',
  city: 'Mumbai',
  phone: null,
  categories: ['Fashion & Lifestyle'],
  languages: ['Hindi', 'English'],
  contentStyles: [],
  platforms: [],
  rateMin: null,
  rateMax: null,
  currency: 'INR',
  discoverable: true,
  verified: false,
  totalFollowers: 0,
  engagementRate: null,
  onboardingComplete: true,
  profileCompleteness: 40,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/profile']}>
      <CreatorProfilePage />
    </MemoryRouter>,
  );
}

describe('CreatorProfilePage — F-0668 ApiError.fields wired to the edit-profile form', () => {
  beforeEach(() => {
    getMeMock.mockReset();
    patchMeMock.mockReset();
    getMeMock.mockResolvedValue(BASE_PROFILE);
  });

  it('shows the server-named field errors on their matching inputs instead of one generic toast', async () => {
    const user = userEvent.setup();
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    patchMeMock.mockRejectedValue(
      new ApiError(
        'VALIDATION_ERROR',
        'Request validation failed',
        400,
        undefined, // details (INSUFFICIENT_FUNDS-only)
        undefined, // linkedCreatorProfileId (CREATOR_ALREADY_ON_INFLUORA-only)
        undefined, // field (single-field errors only — this endpoint always sends `fields`)
        [
          { field: 'bio', message: 'size must be between 0 and 2000' },
          { field: 'rateMin', message: 'must be greater than or equal to 0' },
        ],
      ),
    );

    renderPage();
    await user.click(await screen.findByText('Add rates'));
    await user.click(await screen.findByRole('button', { name: /save/i }));

    // The exact server-authored messages land on the exact server-named fields.
    expect(await screen.findByText('size must be between 0 and 2000')).toBeInTheDocument();
    expect(screen.getByText('must be greater than or equal to 0')).toBeInTheDocument();

    // A field the server did NOT name (city) must stay clean — this is per-field wiring, not a
    // blanket "highlight everything" fallback.
    const cityInput = screen.getByLabelText('City');
    expect(cityInput).not.toHaveAttribute('aria-invalid', 'true');

    const bioInput = screen.getByLabelText('Bio');
    expect(bioInput).toHaveAttribute('aria-invalid', 'true');
  });

  it('clears a field error the moment its own input changes, without touching the others', async () => {
    const user = userEvent.setup();
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    patchMeMock.mockRejectedValue(
      new ApiError(
        'VALIDATION_ERROR',
        'Request validation failed',
        400,
        undefined,
        undefined,
        undefined,
        [
          { field: 'bio', message: 'size must be between 0 and 2000' },
          { field: 'rateMin', message: 'must be greater than or equal to 0' },
        ],
      ),
    );

    renderPage();
    await user.click(await screen.findByText('Add rates'));
    await user.click(await screen.findByRole('button', { name: /save/i }));
    await screen.findByText('size must be between 0 and 2000');

    await user.type(screen.getByLabelText('Bio'), '!');

    expect(screen.queryByText('size must be between 0 and 2000')).not.toBeInTheDocument();
    // rateMin's error is untouched by typing into bio.
    expect(screen.getByText('must be greater than or equal to 0')).toBeInTheDocument();
  });

  it('a save that fails for a non-field reason still falls back to the generic toast path (no field highlighted)', async () => {
    const user = userEvent.setup();
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    patchMeMock.mockRejectedValue(new ApiError('SERVER_ERROR', 'Something went wrong', 500));

    renderPage();
    await user.click(await screen.findByText('Add rates'));
    await user.click(await screen.findByRole('button', { name: /save/i }));

    expect(screen.getByLabelText('Bio')).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Display Name')).not.toHaveAttribute('aria-invalid', 'true');
  });
});


/**
 * F-0681 (partial-fix-worse-than-none) — F-0668 wired SIX of `CreatorProfilePatchRequest`'s
 * thirteen constrained fields and then titled every validation failure "Please fix the
 * highlighted fields". For the seven it did not wire — categories, languages, contentStyles,
 * avatarUrl, coverImageUrl, phone — the creator was told to fix a highlight that existed nowhere
 * on screen, which is strictly worse than the single generic toast F-0668 replaced.
 *
 * `categories` is the reachable one: the add-category handler applies NO client-side cap while
 * the DTO declares `@Size(max = 3)`, so a fourth category is one Enter keypress from a server
 * rejection. It is now highlighted inline. Anything still unhighlightable is NAMED in the toast
 * body under a title that does not promise a highlight.
 *
 * Run: npx vitest run src/pages/creator-profile.field-errors.test.tsx
 */
describe('CreatorProfilePage — F-0681 no validation error is silently swallowed', () => {
  beforeEach(() => {
    getMeMock.mockReset();
    patchMeMock.mockReset();
    getMeMock.mockResolvedValue(BASE_PROFILE);
    vi.mocked(toast).mockClear();
  });

  async function saveWithFields(fields: Array<{ field: string; message: string }>) {
    const user = userEvent.setup();
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    patchMeMock.mockRejectedValue(
      new ApiError(
        'VALIDATION_ERROR',
        'Request validation failed',
        400,
        undefined,
        undefined,
        undefined,
        fields,
      ),
    );
    renderPage();
    await user.click(await screen.findByText('Add rates'));
    await user.click(await screen.findByRole('button', { name: /save/i }));
  }

  it('highlights a categories violation inline — the field F-0668 left unwired and reachable', async () => {
    await saveWithFields([{ field: 'categories', message: 'size must be between 0 and 3' }]);

    expect(await screen.findByText('size must be between 0 and 3')).toBeInTheDocument();
    expect(screen.getByLabelText('Categories')).toHaveAttribute('aria-invalid', 'true');
    // A highlight really exists now, so promising one is honest.
    expect(vi.mocked(toast)).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Please fix the highlighted fields' }),
    );
  });

  it('maps a per-item violation named `categories[0]` onto the categories control', async () => {
    // Spring names a violation on a LIST ELEMENT `categories[0]`, never `categories`. Matching the
    // raw wire name would swallow it exactly the way F-0668 swallowed the unwired fields.
    await saveWithFields([{ field: 'categories[0]', message: 'size must be between 0 and 80' }]);

    expect(await screen.findByText('size must be between 0 and 80')).toBeInTheDocument();
    expect(screen.getByLabelText('Categories')).toHaveAttribute('aria-invalid', 'true');
  });

  it('names a field it cannot highlight instead of claiming a highlight that does not exist', async () => {
    // `phone` is constrained on the DTO but has no control in this dialog.
    await saveWithFields([{ field: 'phone', message: 'size must be between 0 and 20' }]);

    expect(vi.mocked(toast)).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Please fix the following',
        description: 'Phone: size must be between 0 and 20',
      }),
    );
    // and it must NOT claim a highlight
    expect(vi.mocked(toast)).not.toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Please fix the highlighted fields' }),
    );
  });
});
