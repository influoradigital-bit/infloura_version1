/**
 * Shared client-side validators that mirror the backend campaign contract
 * (influora-api CampaignDtos.CampaignWriteRequest). Both the standard wizard
 * (campaign-form.tsx) and the Hype form (brand-new-hype-campaign.tsx) build
 * their own payloads and validate independently, so these helpers exist to keep
 * the two from drifting out of sync with the server — the `title` @Size(5,300)
 * gap was missed twice before this was centralized.
 */

/** Backend: `@NotBlank @Size(min = 5, max = 300) String title`. */
export const TITLE_MIN = 5;
export const TITLE_MAX = 300;

/**
 * Returns an error message if the (trimmed) title violates the backend
 * @Size(5,300) contract, or `undefined` when valid. Callers should send the
 * trimmed value to the API so the validated string matches what's submitted.
 */
export function validateCampaignTitle(raw: string): string | undefined {
  const title = raw.trim();
  if (!title) return 'Campaign title is required';
  if (title.length < TITLE_MIN) return `Title must be at least ${TITLE_MIN} characters`;
  if (title.length > TITLE_MAX) return `Title must be ${TITLE_MAX} characters or fewer`;
  return undefined;
}
