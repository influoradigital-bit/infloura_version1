# Feature: Invoicing & GST

**Business Purpose** — India-GST-compliant invoicing. Influora issues **three distinct documents** with their own tables, numbering series, and tax treatment, so brands and creators have compliant records for filing. This is a legal/finance requirement of operating a marketplace in India.

**Who uses it** — Brands and creators (view/download their invoices), the system (auto-issues them at webhook/release/publish).

## User Roles
Brand (subscription + commission-brand-leg + campaign-service invoices), Creator (commission-creator-leg + campaign-service invoices), Admin (oversight).

## Permissions
Read-only endpoints, row-scoped by workspace/creator. Issuance is internal (escrow/fee/webhook flows).

## The three documents
| Doc | Supplier→Customer | Table | Tax |
|---|---|---|---|
| **#1 Subscription** | Influora → Brand | `invoices` | GST-inclusive amount (paise); base = amount/1.18; CGST/SGST/IGST |
| **#2 Campaign Service** | Creator → Brand (Influora facilitates) | `campaign_service_invoices` | no GST; 1% report-only TCS |
| **#3 Platform Commission** | Influora → Brand/Creator | `platform_commission_invoices` | 18% GST (lump; split at render) |

## Business Flow
```
Subscription charged (webhook) → Doc#1 (base/CGST/SGST/IGST, invoice_number INF/SUB/FY/seq)
Campaign publish → Doc#3 brand-leg (INF/CMB/...)
Escrow release → Doc#2 (creatorInvoiceCode/FY/seq) + Doc#3 creator-leg (INF/CMC/...)
```

## Frontend
- **Components**: brand/creator invoice lists, `creator/TaxIdentityForm`.
- **Hooks**: `creator/useServiceInvoices`.
- **API**: `api.billing` (brand), `api.creatorInvoicing` (creator).

## Backend
- **Controllers**: `BrandInvoicingController`, `CreatorInvoicingController` (+ `BillingController` for Doc#1 PDF).
- **Services**: `service/billing/InvoiceService` (Doc#1), `CampaignServiceInvoiceService` (Doc#2), `CommissionInvoiceService` (Doc#3), `InvoiceNumberService`, `GstSplitUtil`, `HsnSacCodeService`, `CreatorInvoiceCodeService`.
- **PDF**: OpenPDF (`com.lowagie.text`).

## Database
`invoices` (V54/V20260715170000), `campaign_service_invoices` (V20260715130000), `platform_commission_invoices` (V20260715140000), `invoice_number_sequences` (V20260715150000), `hsn_sac_codes` (V20260715160000), creator tax identity (V20260715120000). See [../database.md](../database.md).

## APIs
`GET /billing/{campaign-invoices,commission-invoices}` (+`/{id}/pdf`), mirrored `/creator/*`, `GET /billing/invoices/{id}/pdf` (Doc#1).

## AI
Not involved.

## Notifications
`InvoiceReadyEvent` (**no listener currently** — see [../known-limitations.md](../known-limitations.md)).

## Dependencies
- **Depends on**: escrow (Doc#2/#3-creator), campaign publish (Doc#3-brand), subscription webhook (Doc#1), creator tax identity.
- **Depended on by**: brand/creator finance records.

## Connected Files
`InvoiceService`, `CampaignServiceInvoiceService`, `CommissionInvoiceService`, `InvoiceNumberService`, `GstSplitUtil`, `HsnSacCodeService`, `CreatorInvoiceCodeService`, `domain/entity/{Invoice,CampaignServiceInvoice,PlatformCommissionInvoice,InvoiceNumberSequence,HsnSacCode}`.

## Execution Flow
```
Numbering: InvoiceNumberService.consumeNext (prefix/FY/%06d) under SELECT...FOR UPDATE (per series+FY+creatorCode)
GST split: GstSplitUtil.compute(supplierGstin, customerGstin, totalGst) → intra-state CGST+SGST (half/half),
  inter-state or missing GSTIN → full IGST
```

## Error Handling
Best-effort GST breakup on Doc#1 (a GST failure never fails the paid webhook); idempotent on `razorpay_invoice_id`/`escrow_hold_id`; invoice-code collision retried.

## Security
Amounts server-derived; row-scoped reads; PDFs via presigned GET.

## Performance
`@Cacheable` HSN/SAC lookups; sequence locks held to end of transaction.

## Testing
Invoice/GST split tests. Regression risks: FY boundary (Apr-1 IST), intra/inter-state split, numbering races.

## Production Readiness
- **Health**: 6/10 · **Completion**: ~70% (structurally complete, not filing-ready)
- **Known issues**: placeholder company GSTIN forces **IGST** for everything; `company.state-code` is dead (split uses GSTIN prefixes); Doc#2 emits no GST for registered creators; Doc#3 split recomputed at render (not persisted); HSN/SAC placeholders pending CA; `CreatorTaxRegistrationStatus` stored but unused. See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
