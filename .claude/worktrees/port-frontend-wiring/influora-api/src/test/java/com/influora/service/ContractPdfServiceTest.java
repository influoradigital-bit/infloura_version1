package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.PaymentMilestone;
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
        contract.recordBrandSignature();
        contract.recordCreatorSignature();

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
}
