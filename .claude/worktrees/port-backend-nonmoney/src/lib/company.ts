/**
 * Single source of truth for Influora Digital Private Limited's legal &
 * contact details. Consumed by the footer, contact page, legal pages, and
 * JSON-LD Organization schema. Update here only.
 *
 * Confirmed by CEO from official docs (Certificate of Incorporation + GST
 * Registration Certificate), 2026-07-13.
 */
export const COMPANY = {
  /** Exact registered legal name — use verbatim in all legal text. */
  legalName: 'Influora Digital Private Limited',
  /** Consumer-facing brand name. */
  brandName: 'Influora',

  /** Corporate Identity Number (MCA). U73100MH2024PTC434321 → unlisted, NIC 73100 (advertising), Maharashtra, 2024, Pvt Ltd. */
  cin: 'U73100MH2024PTC434321',
  /** Company PAN. Semi-private — used on invoices, NOT displayed publicly in the footer. */
  pan: 'AAHCI9032N',
  /** GSTIN — 27 = Maharashtra. Legally displayed on the site. */
  gstin: '27AAHCI9032N1Z2',

  /** Registered state (from CIN / GSTIN prefix 27). */
  state: 'Maharashtra',
  /**
   * TODO(CEO): full registered street address + city + pincode from the GST
   * "Principal Place of Business". PDF text extraction unavailable in this env;
   * awaiting the address as text. Until then the footer shows state only.
   */
  registeredAddress: '', // e.g. 'Flat/Building, Street, City, Maharashtra – PIN'

  /** Support / general contact. */
  email: 'info@influora.in',
  phone: '+91 80 6957 8296',
  /** E.164 form for tel: links. */
  phoneHref: '+918069578296',

  /**
   * Grievance Officer (IT Rules 2021 / Consumer Protection E-Commerce Rules
   * 2020 — a NAMED individual is legally required to publish /grievance).
   * TODO(CEO): provide officer name. Email/phone default to the company channel.
   */
  grievanceOfficer: {
    name: '', // TODO(CEO): required before /grievance can publish
    email: 'info@influora.in',
    phone: '+91 80 6957 8296',
  },

  socials: {
    // TODO: add real handles when live; used for Organization sameAs schema.
    instagram: '',
    linkedin: '',
  },
} as const;
