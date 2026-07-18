/**
 * Contract PDF Generator
 * Creates PDF contracts from accepted proposals
 */

import { api, ApiError, type Role } from '@/lib/api';
import type { ContractStatus } from '@/lib/types';

export interface ContractData {
  contractId: string;
  brandName: string;
  creatorName: string;
  campaignName: string;
  amount: number;
  deliverables: Array<{
    title: string;
    description: string;
    quantity: number;
  }>;
  deadline: string;
  usageRights: string;
  exclusivity: string;
  revisionCap: number;
  customClauses: string[];
  createdAt: Date;
}

/**
 * Generate contract PDF content as HTML (can be rendered or converted)
 */
export function generateContractHTML(data: ContractData): string {
  const formattedAmount = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
  }).format(data.amount);

  const deliverablesList = data.deliverables
    .map(
      (d, i) =>
        `${i + 1}. ${d.title} (Qty: ${d.quantity}): ${d.description}`
    )
    .join('<br/>')

  const customClausesList = data.customClauses
    .map((clause, i) => `${i + 7}. ${clause}`)
    .join('<br/>')

  return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="UTF-8">
      <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; padding: 40px; }
        .header { text-align: center; margin-bottom: 30px; }
        .title { font-size: 24px; font-weight: bold; margin-bottom: 10px; }
        .subtitle { font-size: 12px; color: #666; }
        .section { margin-bottom: 20px; }
        .section-title { font-weight: bold; font-size: 14px; margin-bottom: 10px; border-bottom: 2px solid #007bff; }
        .party { margin: 10px 0; }
        .party-label { font-weight: bold; }
        table { width: 100%; border-collapse: collapse; margin: 10px 0; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background-color: #f5f5f5; }
        .terms-list { margin-left: 20px; }
        .signature-area { margin-top: 40px; display: flex; justify-content: space-around; }
        .signature-line { border-top: 1px solid #000; width: 150px; text-align: center; margin-top: 50px; }
        .footer { text-align: center; margin-top: 30px; font-size: 10px; color: #999; }
      </style>
    </head>
    <body>
      <div class="header">
        <div class="title">CONTENT CREATION AGREEMENT</div>
        <div class="subtitle">Contract ID: ${data.contractId}</div>
        <div class="subtitle">Date: ${new Date(data.createdAt).toLocaleDateString('en-IN')}</div>
      </div>

      <div class="section">
        <div class="section-title">PARTIES TO THE AGREEMENT</div>
        <div class="party">
          <span class="party-label">BRAND:</span> ${data.brandName}
        </div>
        <div class="party">
          <span class="party-label">CREATOR:</span> ${data.creatorName}
        </div>
        <div class="party">
          <span class="party-label">CAMPAIGN:</span> ${data.campaignName}
        </div>
      </div>

      <div class="section">
        <div class="section-title">DELIVERABLES</div>
        <table>
          <tr>
            <th>#</th>
            <th>Deliverable</th>
            <th>Description</th>
            <th>Qty</th>
          </tr>
          ${data.deliverables
            .map(
              (d, i) => `
            <tr>
              <td>${i + 1}</td>
              <td>${d.title}</td>
              <td>${d.description}</td>
              <td>${d.quantity}</td>
            </tr>
          `
            )
            .join('')}
        </table>
      </div>

      <div class="section">
        <div class="section-title">FINANCIAL TERMS</div>
        <table>
          <tr>
            <td>Total Amount:</td>
            <td><strong>${formattedAmount}</strong></td>
          </tr>
          <tr>
            <td>Payment Terms:</td>
            <td>Release upon approval of final deliverables</td>
          </tr>
          <tr>
            <td>Escrow:</td>
            <td>Full amount held in platform escrow</td>
          </tr>
        </table>
      </div>

      <div class="section">
        <div class="section-title">TERMS & CONDITIONS</div>
        <div class="terms-list">
          <p>1. <strong>Delivery Deadline:</strong> Creator agrees to deliver all content by ${new Date(data.deadline).toLocaleDateString('en-IN')}</p>
          
          <p>2. <strong>Usage Rights:</strong> ${data.usageRights}</p>
          
          <p>3. <strong>Exclusivity:</strong> ${data.exclusivity}</p>
          
          <p>4. <strong>Revisions:</strong> Brand may request up to ${data.revisionCap} revision rounds. Additional revisions may incur additional fees as agreed.</p>
          
          <p>5. <strong>Quality Standards:</strong> Content must be of professional quality, free from watermarks (unless agreed), and comply with platform guidelines.</p>
          
          <p>6. <strong>Payment Release:</strong> Payment will be released from escrow within 7 days of brand approval of final deliverables.</p>
          
          <p>7. <strong>Dispute Resolution:</strong> Any disputes shall be resolved through the platform's arbitration process before pursuing legal action.</p>
          
          ${
            data.customClauses.length > 0
              ? `${data.customClauses
                  .map((clause, i) => `<p>${i + 8}. <strong>Custom Term:</strong> ${clause}</p>`)
                  .join('')}`
              : ''
          }
        </div>
      </div>

      <div class="section">
        <div class="section-title">SIGNATURES</div>
        <div class="signature-area">
          <div>
            <div class="signature-line">_______________________</div>
            <div>Brand Representative</div>
            <div style="font-size: 10px; color: #999;">Date: _______________</div>
          </div>
          <div>
            <div class="signature-line">_______________________</div>
            <div>Creator</div>
            <div style="font-size: 10px; color: #999;">Date: _______________</div>
          </div>
        </div>
      </div>

      <div class="footer">
        <p>This is an electronically generated contract. Both parties agree to the terms outlined above.</p>
        <p>Generated on ${new Date().toLocaleString('en-IN')}</p>
      </div>
    </body>
    </html>
  `
}

/**
 * Generate a downloadable PDF from contract data
 * Uses browser's print-to-PDF or requires backend PDF library
 */
export function downloadContractPDF(data: ContractData, filename: string = 'contract.pdf') {
  const html = generateContractHTML(data)
  const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  
  // Open in new window for user to print/save as PDF
  const win = window.open(url, '_blank')
  if (win) {
    // Auto-trigger print dialog after content loads
    win.addEventListener('load', () => {
      win.print()
    })
  }
}

/**
 * Sign a contract via the real backend.
 * Calls `api.contracts.sign` (POST /contracts/:id/sign — ContractController.java:78),
 * which persists the signature and returns the contract's post-sign status.
 * In demo/mock mode, `api.contracts.sign` internally falls back to a mocked
 * response — this function does not simulate anything itself.
 *
 * Throws `ApiError` (or rethrows whatever `api.contracts.sign` throws) on
 * failure; callers are expected to catch and surface the error.
 */
export async function signContract(
  contractId: string,
  signedBy: Role,
  signerName: string,
): Promise<{
  success: boolean;
  status: ContractStatus;
  timestamp: Date;
}> {
  const agreedAt = new Date();
  try {
    const result = await api.contracts.sign(signedBy, contractId, {
      name: signerName,
      agreedAt: agreedAt.toISOString(),
    });
    return {
      success: true,
      status: result.status,
      timestamp: agreedAt,
    };
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new Error('Could not sign contract. Please try again.');
  }
}
