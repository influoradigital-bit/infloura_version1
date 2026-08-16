/**
 * DeliverableSubmission — CR-53 submit-only retry after a successful upload.
 *
 * WHY THIS TEST EXISTS
 * ---------------------
 * `creator-chat.tsx#handleSubmitDeliverableForm` uploads a file, then submits it, as two
 * sequential awaited calls. If upload succeeds but submit fails (network blip, validation
 * error), the handler tags the thrown error with `uploaded: true` so this dialog can tell
 * "upload also failed" apart from "upload is done, only submit needs a retry". Without that
 * distinction, the natural failure mode is that the dialog looks identical to a fresh, empty
 * form and clicking submit again silently re-uploads the file — wasted round trip at best, a
 * duplicate server-side version at worst — with no visible sign to the creator that anything
 * was already saved.
 *
 * This test only covers the dialog's own contract (what it does with an `uploaded`-tagged
 * error), not the parent's upload-skip logic in creator-chat.tsx — that page requires the
 * wide, heavy API mock harness already established in creator-chat-refresh.test.tsx, and the
 * skip/no-skip decision itself is a plain reference-equality check with no other component
 * dependency worth re-mounting the whole page for.
 *
 * Run: npx vitest run src/components/creator/deal-room/deliverable-submission.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DeliverableSubmission } from './deliverable-submission';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
}));

const deliverables = [
  { id: 'del_1', title: 'Instagram Reel', description: 'Main deliverable', completed: false },
];

function makeFile() {
  return new File(['content'], 'reel.mp4', { type: 'video/mp4' });
}

async function fillForm(user: ReturnType<typeof userEvent.setup>) {
  const file = makeFile();
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  await user.upload(input, file);
  await user.type(screen.getByLabelText(/Caption/i), 'Check this out');
  return file;
}

describe('DeliverableSubmission — CR-53 retry-only-submit after successful upload', () => {
  it('keeps the file/caption and offers "Retry Submit" when the error is tagged uploaded:true', async () => {
    const user = userEvent.setup({ delay: null });
    const uploadedError = Object.assign(new Error('submit failed'), { uploaded: true });
    const onSubmit = vi.fn().mockRejectedValue(uploadedError);

    render(
      <DeliverableSubmission
        open
        onOpenChange={vi.fn()}
        deliverables={deliverables}
        onSubmit={onSubmit}
      />,
    );

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Submit Deliverable' }));

    // The dialog must not discard the uploaded file reference or caption on a submit-only
    // failure — otherwise the creator is forced to re-pick and re-upload the file.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Retry Submit' })).toBeInTheDocument();
    });
    expect(screen.getAllByText(/reel\.mp4/).length).toBeGreaterThan(0);
    expect(screen.getByLabelText(/Caption/i)).toHaveValue('Check this out');
    expect(screen.getByText(/uploaded successfully, but submitting/i)).toBeInTheDocument();

    // Retrying calls onSubmit again with the same File reference — it is the parent's job
    // (creator-chat.tsx) to recognize that reference and skip re-uploading.
    onSubmit.mockResolvedValueOnce(undefined);
    await user.click(screen.getByRole('button', { name: 'Retry Submit' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
    const [firstCall, secondCall] = onSubmit.mock.calls;
    expect(secondCall[0].file).toBe(firstCall[0].file);
  });

  it('shows the generic error (no "Retry Submit" label) when the error is not tagged uploaded', async () => {
    const user = userEvent.setup({ delay: null });
    const plainError = new Error('network down');
    const onSubmit = vi.fn().mockRejectedValue(plainError);

    render(
      <DeliverableSubmission
        open
        onOpenChange={vi.fn()}
        deliverables={deliverables}
        onSubmit={onSubmit}
      />,
    );

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Submit Deliverable' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    // Button keeps its normal label — nothing was uploaded, so there is nothing to "retry only".
    expect(screen.getByRole('button', { name: 'Submit Deliverable' })).toBeInTheDocument();
    expect(screen.queryByText(/uploaded successfully, but submitting/i)).not.toBeInTheDocument();
  });

  it('clears the retry-only state once a new file is picked', async () => {
    const user = userEvent.setup({ delay: null });
    const uploadedError = Object.assign(new Error('submit failed'), { uploaded: true });
    const onSubmit = vi.fn().mockRejectedValue(uploadedError);

    render(
      <DeliverableSubmission
        open
        onOpenChange={vi.fn()}
        deliverables={deliverables}
        onSubmit={onSubmit}
      />,
    );

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Submit Deliverable' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Retry Submit' })).toBeInTheDocument();
    });

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(input, new File(['other'], 'reel-v2.mp4', { type: 'video/mp4' }));

    expect(screen.getByRole('button', { name: 'Submit Deliverable' })).toBeInTheDocument();
    expect(screen.queryByText(/uploaded successfully, but submitting/i)).not.toBeInTheDocument();
  });
});
