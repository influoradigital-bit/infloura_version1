package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.PaymentMilestone;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P0 #2: ContractPdfService renders real PDF bytes from contract/milestone/party data — smoke
 * tests that it doesn't blow up on typical and edge-case (no milestones, unsigned) inputs and
 * produces a plausible, non-trivial PDF byte stream (starts with the "%PDF" magic header).
 */
class ContractPdfServiceTest {

    private final ContractPdfService service = new ContractPdfService();

    @Test
    @DisplayName("render: produces a valid PDF byte stream for a fully-signed contract with milestones")
    void testRenderFullySignedContractWithMilestones() {
        Contract contract =
                Contract.builder()
                        .id("01HCONTRACT123456789A")
                        .collaborationId("01HCOLLAB1234567890AB")
                        .workspaceId("01HWORKSPACE12345678A")
                        .totalAmount(new BigDecimal("15000.00"))
                        .currency("INR")
                        .effectiveDate(LocalDate.of(2026, 7, 1))
                        .expirationDate(LocalDate.of(2026, 8, 1))
                        .build();
        contract.recordBrandSignature("Test Brand Owner");
        contract.recordCreatorSignature("Test Creator");

        Campaign campaign =
                Campaign.builder()
                        .id("01HCAMPAIGN123456789A")
                        .workspaceId("01HWORKSPACE12345678A")
                        .title("Summer Launch")
                        .createdBy("brandUser")
                        .build();

        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id("01HMILESTONE1234567890")
                        .contractId(contract.getId())
                        .collaborationId(contract.getCollaborationId())
                        .sequenceNo(1)
                        .description("Instagram reel")
                        .amount(new BigDecimal("15000.00"))
                        .dueDate(LocalDate.of(2026, 7, 15))
                        .build();

        byte[] pdfBytes =
                service.render(contract, campaign, List.of(milestone), "Acme Brand", "Jane Creator");

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 100, "expected a non-trivial PDF byte stream");
        assertTrue(startsWithPdfMagicHeader(pdfBytes), "expected bytes to start with %PDF header");
    }

    @Test
    @DisplayName("render: does not throw when there are no milestones and no campaign/party data")
    void testRenderWithMissingOptionalData() {
        Contract contract =
                Contract.builder()
                        .id("01HCONTRACT223456789A")
                        .collaborationId("01HCOLLAB2234567890AB")
                        .workspaceId("01HWORKSPACE22345678A")
                        .totalAmount(new BigDecimal("500.00"))
                        .build();

        byte[] pdfBytes = service.render(contract, null, List.of(), null, null);

        assertNotNull(pdfBytes);
        assertTrue(startsWithPdfMagicHeader(pdfBytes));
    }

    private static boolean startsWithPdfMagicHeader(byte[] bytes) {
        if (bytes.length < 4) {
            return false;
        }
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /**
     * [F-0318, signed-artifact-omits-terms] Extracts the actual rendered text of a
     * single-page PDF using OpenPDF's own {@link PdfTextExtractor} — the same library this
     * service renders with. Asserting on real rendered content (not just that a method name
     * appears in source) is deliberate: a "fix" that calls {@code getTermsText()} into an
     * unused local, or that prints {@code getTermsJson()} instead, still passes a source-grep
     * check but produces a PDF that does not actually carry the agreed terms.
     */
    private static String extractPdfText(byte[] pdfBytes) throws Exception {
        PdfReader reader = new PdfReader(pdfBytes);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    @DisplayName(
            "render: the supplied contract terms text is actually present in the rendered PDF"
                    + " content (F-0318)")
    void testRenderIncludesTermsTextInPdfContent() throws Exception {
        String agreedTerms =
                "Creator retains usage rights for 90 days; brand gets exclusive category rights"
                        + " for the campaign duration.";
        Contract contract =
                Contract.builder()
                        .id("01HCONTRACT323456789A")
                        .collaborationId("01HCOLLAB3234567890AB")
                        .workspaceId("01HWORKSPACE32345678A")
                        .totalAmount(new BigDecimal("8000.00"))
                        .currency("INR")
                        // Deliberately also set the UNRELATED tamper-hash column
                        // (Contract#termsJson) to a distinguishable, recognizable marker so this
                        // test also falsifies a wrong fix that prints getTermsJson() instead of
                        // getTermsText() (F-0318's named failure mode).
                        .termsJson("{\"tamperHashSha256\":\"WRONG-FIELD-MARKER-DO-NOT-PRINT\"}")
                        .termsText(agreedTerms)
                        .build();

        byte[] pdfBytes = service.render(contract, null, List.of(), "Acme Brand", "Jane Creator");
        String text = extractPdfText(pdfBytes);

        assertTrue(
                text.contains(agreedTerms),
                "expected the agreed terms text to appear in the rendered PDF content");
        assertFalse(
                text.contains("WRONG-FIELD-MARKER-DO-NOT-PRINT"),
                "the tamper-hash column (termsJson) must never be rendered as contract terms");
    }

    @Test
    @DisplayName(
            "render: a contract with no terms text renders an honest statement, not a blank"
                    + " section (F-0318)")
    void testRenderWithNoTermsTextRendersHonestly() throws Exception {
        Contract contract =
                Contract.builder()
                        .id("01HCONTRACT423456789A")
                        .collaborationId("01HCOLLAB4234567890AB")
                        .workspaceId("01HWORKSPACE42345678A")
                        .totalAmount(new BigDecimal("2500.00"))
                        .currency("INR")
                        .build();

        byte[] pdfBytes = service.render(contract, null, List.of(), "Acme Brand", "Jane Creator");
        String text = extractPdfText(pdfBytes);

        assertTrue(
                text.toLowerCase().contains("no terms"),
                "expected an honest statement that no terms were specified, not a silently"
                        + " blank section under the 'Terms' heading");
    }
}
