# Creator Contracts Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Contract Flow Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CONTRACT LIFECYCLE                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│  │ Bid      │ →  │ Contract │ →  │ Creator  │ →  │ Brand    │             │
│  │ Accepted │    │ Generated│    │ Reviews  │    │ Signs    │             │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘             │
│                                                                              │
│                                       │                                      │
│                                       ↓                                      │
│                              ┌─────────────────┐                            │
│                              │ Both Signed     │                            │
│                              │ Escrow Funded   │                            │
│                              │ Campaign Active │                            │
│                              └─────────────────┘                            │
│                                       │                                      │
│                    ┌──────────────────┼──────────────────┐                  │
│                    ↓                  ↓                  ↓                  │
│             ┌──────────┐       ┌──────────┐       ┌──────────┐             │
│             │ Milestone│       │ Milestone│       │ Milestone│             │
│             │ 1: 30%   │   →   │ 2: 40%   │   →   │ 3: 30%   │             │
│             │ Content  │       │ Approval │       │ Metrics  │             │
│             └──────────┘       └──────────┘       └──────────┘             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Contract States

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CONTRACT STATE MACHINE                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DRAFT ───────→ PENDING_CREATOR ───────→ PENDING_BRAND                      │
│    │                   │                       │                            │
│    │                   │                       │                            │
│    │                   ↓                       ↓                            │
│    │            CREATOR_REQUESTED        FULLY_SIGNED                       │
│    │              _CHANGES                     │                            │
│    │                   │                       │                            │
│    │                   │                       ↓                            │
│    │                   │              ESCROW_FUNDED ──→ ACTIVE              │
│    │                   │                                   │                │
│    ↓                   ↓                                   ↓                │
│  CANCELLED ←────── CANCELLED                        COMPLETED/DISPUTED     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 Contract Entity

```java
@Entity
@Table(name = "contracts")
public class Contract {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "collaboration_id")
    private Collaboration collaboration;
    
    @ManyToOne
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    @ManyToOne
    @JoinColumn(name = "brand_id")
    private BrandProfile brand;
    
    @ManyToOne
    @JoinColumn(name = "bid_id")
    private Bid bid;
    
    // Contract details
    private String contractNumber;  // INF-2026-001234
    private Integer version;        // For amendments
    
    // Terms
    private BigDecimal totalAmount;
    private String currency;  // INR
    
    @Convert(converter = JsonMapConverter.class)
    private Map<String, BigDecimal> deliverableRates;
    
    @Convert(converter = JsonListConverter.class)
    private List<Deliverable> deliverables;
    
    // Timeline
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate contentDeadline;
    private LocalDate postingDeadline;
    
    // Milestones
    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL)
    private List<PaymentMilestone> milestones;
    
    // Status
    @Enumerated(EnumType.STRING)
    private ContractStatus status;
    
    // Signatures
    private String creatorSignatureHash;
    private Instant creatorSignedAt;
    private String creatorSignedIp;
    
    private String brandSignatureHash;
    private Instant brandSignedAt;
    private String brandSignedIp;
    
    // Terms acceptance
    private Boolean creatorAcceptedTerms;
    private Boolean brandAcceptedTerms;
    
    // PDF
    private String pdfUrl;
    private String pdfHash;  // SHA-256 for integrity
    
    // Change requests
    @Convert(converter = JsonListConverter.class)
    private List<ContractChangeRequest> changeRequests;
    
    // Timestamps
    private Instant generatedAt;
    private Instant sentAt;
    private Instant completedAt;
    private Instant cancelledAt;
}

public enum ContractStatus {
    DRAFT,                    // Being generated
    PENDING_CREATOR,          // Awaiting creator signature
    CREATOR_REQUESTED_CHANGES, // Creator requested modifications
    PENDING_BRAND,            // Creator signed, awaiting brand
    FULLY_SIGNED,             // Both signed, awaiting escrow
    ESCROW_FUNDED,            // Escrow deposited
    ACTIVE,                   // Campaign in progress
    COMPLETED,                // All milestones complete
    DISPUTED,                 // Under dispute
    CANCELLED                 // Cancelled
}
```

### 3.2 PaymentMilestone Entity

```java
@Entity
@Table(name = "payment_milestones")
public class PaymentMilestone {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "contract_id")
    private Contract contract;
    
    private String name;           // "Content Submission", "Brand Approval", "Final Metrics"
    private String description;
    
    private Integer sequenceNumber;  // 1, 2, 3...
    
    // Payment
    private BigDecimal amount;
    private Integer percentageOfTotal;  // 30, 40, 30
    
    // Completion criteria
    @Enumerated(EnumType.STRING)
    private MilestoneTrigger trigger;  // CONTENT_SUBMITTED, BRAND_APPROVED, METRICS_VERIFIED
    
    @Convert(converter = JsonListConverter.class)
    private List<String> requiredDeliverableTypes;  // Which deliverables trigger this
    
    // Status
    @Enumerated(EnumType.STRING)
    private MilestoneStatus status;  // PENDING, IN_PROGRESS, COMPLETED, RELEASED, DISPUTED
    
    // Completion
    private Instant completedAt;
    private String completedByUserId;
    private String completionNotes;
    
    // Payment release
    private Instant releasedAt;
    private String releaseTransactionId;
    
    // Deadline
    private LocalDate deadline;
}

public enum MilestoneStatus {
    PENDING,      // Not started
    IN_PROGRESS,  // Work ongoing
    COMPLETED,    // Criteria met, awaiting release
    RELEASED,     // Payment released
    DISPUTED      // Under dispute
}

public enum MilestoneTrigger {
    CONTENT_SUBMITTED,    // Creator uploads content
    BRAND_APPROVED,       // Brand approves content
    CONTENT_POSTED,       // Content goes live
    METRICS_VERIFIED,     // Performance metrics verified
    MANUAL_APPROVAL       // Admin/manual trigger
}
```

### 3.3 ContractTemplate Entity

```java
@Entity
@Table(name = "contract_templates")
public class ContractTemplate {
    
    @Id
    private String id;
    
    private String name;
    private String version;
    
    // Template sections (Mustache/Handlebars format)
    @Column(columnDefinition = "TEXT")
    private String headerTemplate;
    
    @Column(columnDefinition = "TEXT")
    private String scopeTemplate;
    
    @Column(columnDefinition = "TEXT")
    private String paymentTemplate;
    
    @Column(columnDefinition = "TEXT")
    private String deliverablesTemplate;
    
    @Column(columnDefinition = "TEXT")
    private String termsTemplate;
    
    @Column(columnDefinition = "TEXT")
    private String signatureTemplate;
    
    // Legal terms
    @Column(columnDefinition = "TEXT")
    private String standardTerms;  // Non-editable legal terms
    
    private Boolean isActive;
    private Boolean isDefault;
    
    private Instant createdAt;
    private Instant updatedAt;
}
```

---

## 4. API Endpoints

### 4.1 Get Contract (Creator View)

```
GET /api/v1/creator/contracts/{contractId}

Response:
{
    "id": "cont_xxx",
    "contractNumber": "INF-2026-001234",
    "version": 1,
    "status": "PENDING_CREATOR",
    "campaign": {
        "id": "camp_xxx",
        "title": "Summer Fitness Challenge",
        "brand": {
            "name": "HealthKart",
            "logo": "https://..."
        }
    },
    "terms": {
        "totalAmount": 55000,
        "currency": "INR",
        "startDate": "2026-07-22",
        "endDate": "2026-08-15",
        "contentDeadline": "2026-08-01",
        "postingDeadline": "2026-08-10"
    },
    "deliverables": [
        {
            "type": "INSTAGRAM_REEL",
            "quantity": 2,
            "rate": 22000,
            "description": "60-90 second workout reel",
            "requirements": [...]
        },
        {
            "type": "INSTAGRAM_STORY",
            "quantity": 3,
            "rate": 4000,
            "description": "Story sequence with swipe-up"
        }
    ],
    "milestones": [
        {
            "id": "mile_xxx",
            "name": "Content Submission",
            "amount": 16500,
            "percentageOfTotal": 30,
            "trigger": "CONTENT_SUBMITTED",
            "deadline": "2026-08-01",
            "status": "PENDING"
        },
        {
            "id": "mile_yyy",
            "name": "Brand Approval",
            "amount": 22000,
            "percentageOfTotal": 40,
            "trigger": "BRAND_APPROVED",
            "deadline": "2026-08-05",
            "status": "PENDING"
        },
        {
            "id": "mile_zzz",
            "name": "Final Metrics",
            "amount": 16500,
            "percentageOfTotal": 30,
            "trigger": "METRICS_VERIFIED",
            "deadline": "2026-08-20",
            "status": "PENDING"
        }
    ],
    "legal": {
        "standardTerms": "...",
        "contentRights": "Brand receives perpetual license...",
        "exclusivity": "No competing brands for 30 days...",
        "cancellationPolicy": "..."
    },
    "pdfUrl": "https://r2.../contract-INF-2026-001234.pdf",
    "signature": {
        "creatorSigned": false,
        "brandSigned": true,
        "brandSignedAt": "2026-07-08T10:00:00Z"
    },
    "actions": {
        "canSign": true,
        "canRequestChanges": true,
        "canDecline": true
    },
    "expiresAt": "2026-07-12T14:30:00Z"  // 3 days to sign
}
```

### 4.2 List Creator Contracts

```
GET /api/v1/creator/contracts
Query Parameters:
  status    - Filter by status
  sort      - (created_at, amount, deadline)
  page, size

Response:
{
    "contracts": [
        {
            "id": "cont_xxx",
            "contractNumber": "INF-2026-001234",
            "campaign": {...},
            "status": "PENDING_CREATOR",
            "totalAmount": 55000,
            "pendingAction": "SIGN",
            "expiresAt": "2026-07-12T14:30:00Z",
            "createdAt": "2026-07-08T10:00:00Z"
        }
    ],
    "summary": {
        "pendingSignature": 2,
        "active": 3,
        "completed": 15,
        "totalEarned": 750000
    }
}
```

### 4.3 Sign Contract

```
POST /api/v1/creator/contracts/{contractId}/sign
{
    "signatureData": "data:image/png;base64,iVBORw0KGgo...",  // Drawn signature
    "acceptedTerms": true,
    "confirmations": {
        "readTerms": true,
        "understandDeliverables": true,
        "agreeToTimeline": true,
        "confirmLegalCapacity": true
    }
}

Response:
{
    "contractId": "cont_xxx",
    "status": "PENDING_BRAND",  // or "FULLY_SIGNED" if brand already signed
    "signedAt": "2026-07-09T14:30:00Z",
    "nextStep": "Awaiting brand signature",
    "pdfUrl": "https://r2.../contract-INF-2026-001234-signed.pdf"
}
```

### 4.4 Request Contract Changes

```
POST /api/v1/creator/contracts/{contractId}/request-changes
{
    "changeRequests": [
        {
            "section": "TIMELINE",
            "currentValue": "Content deadline: Aug 1",
            "requestedValue": "Content deadline: Aug 5",
            "reason": "Need additional time for high-quality production"
        },
        {
            "section": "DELIVERABLES",
            "currentValue": "2x Instagram Reels",
            "requestedValue": "2x Instagram Reels + 1x Behind-the-scenes",
            "reason": "Can provide extra content for same rate"
        }
    ],
    "message": "I'd like to request these changes before signing..."
}

Response:
{
    "contractId": "cont_xxx",
    "status": "CREATOR_REQUESTED_CHANGES",
    "message": "Change request submitted. The brand will review.",
    "changeRequestId": "cr_xxx"
}
```

### 4.5 Decline Contract

```
POST /api/v1/creator/contracts/{contractId}/decline
{
    "reason": "Terms don't align with my availability",
    "feedback": "The posting deadline is too tight for quality content"
}

Response:
{
    "success": true,
    "message": "Contract declined. The brand has been notified."
}
```

### 4.6 Download Contract PDF

```
GET /api/v1/creator/contracts/{contractId}/pdf
→ Returns PDF file download

GET /api/v1/creator/contracts/{contractId}/pdf/preview
→ Returns inline PDF for preview
```

---

## 5. Backend Implementation

### 5.1 Contract Generation Service

```java
@Service
public class ContractGenerationService {
    
    private final ContractRepository contractRepo;
    private final ContractTemplateRepository templateRepo;
    private final PdfGenerationService pdfService;
    private final R2StorageService storageService;
    
    @Transactional
    public Contract generateContract(Bid acceptedBid) {
        // Get default template
        ContractTemplate template = templateRepo.findByIsDefaultTrue()
            .orElseThrow(() -> new TemplateNotFoundException());
        
        // Generate contract number
        String contractNumber = generateContractNumber();
        
        // Create contract
        Contract contract = Contract.builder()
            .id(Ulids.generate())
            .bid(acceptedBid)
            .collaboration(acceptedBid.getApplication().getCollaboration())
            .campaign(acceptedBid.getCampaign())
            .creator(acceptedBid.getCreator())
            .brand(acceptedBid.getBrand())
            .contractNumber(contractNumber)
            .version(1)
            .totalAmount(acceptedBid.getTotalAmount())
            .currency("INR")
            .deliverableRates(acceptedBid.getDeliverableRates())
            .deliverables(buildDeliverables(acceptedBid))
            .startDate(acceptedBid.getProposedStartDate())
            .endDate(acceptedBid.getProposedEndDate())
            .contentDeadline(calculateContentDeadline(acceptedBid))
            .postingDeadline(calculatePostingDeadline(acceptedBid))
            .status(ContractStatus.DRAFT)
            .generatedAt(Instant.now())
            .build();
        
        // Generate milestones
        contract.setMilestones(generateMilestones(contract));
        
        // Generate PDF
        byte[] pdfBytes = pdfService.generateContractPdf(contract, template);
        String pdfHash = hashPdf(pdfBytes);
        
        // Upload to R2
        String pdfUrl = storageService.uploadContractPdf(
            contractNumber + ".pdf",
            pdfBytes
        );
        
        contract.setPdfUrl(pdfUrl);
        contract.setPdfHash(pdfHash);
        contract.setStatus(ContractStatus.PENDING_CREATOR);
        contract.setSentAt(Instant.now());
        
        contractRepo.save(contract);
        
        // Notify creator
        notificationService.notifyCreator(contract.getCreator().getId(),
            NotificationType.CONTRACT_READY,
            Map.of(
                "contractId", contract.getId(),
                "campaignTitle", contract.getCampaign().getTitle(),
                "amount", contract.getTotalAmount()
            )
        );
        
        return contract;
    }
    
    private List<PaymentMilestone> generateMilestones(Contract contract) {
        List<PaymentMilestone> milestones = new ArrayList<>();
        
        // Standard 3-milestone structure: 30% / 40% / 30%
        
        // Milestone 1: Content Submission
        milestones.add(PaymentMilestone.builder()
            .id(Ulids.generate())
            .contract(contract)
            .name("Content Submission")
            .description("Creator submits all required content for review")
            .sequenceNumber(1)
            .percentageOfTotal(30)
            .amount(contract.getTotalAmount().multiply(new BigDecimal("0.30")))
            .trigger(MilestoneTrigger.CONTENT_SUBMITTED)
            .status(MilestoneStatus.PENDING)
            .deadline(contract.getContentDeadline())
            .build());
        
        // Milestone 2: Brand Approval
        milestones.add(PaymentMilestone.builder()
            .id(Ulids.generate())
            .contract(contract)
            .name("Brand Approval")
            .description("Brand approves submitted content")
            .sequenceNumber(2)
            .percentageOfTotal(40)
            .amount(contract.getTotalAmount().multiply(new BigDecimal("0.40")))
            .trigger(MilestoneTrigger.BRAND_APPROVED)
            .status(MilestoneStatus.PENDING)
            .deadline(contract.getContentDeadline().plusDays(3))
            .build());
        
        // Milestone 3: Final Metrics
        milestones.add(PaymentMilestone.builder()
            .id(Ulids.generate())
            .contract(contract)
            .name("Final Metrics")
            .description("Content posted and performance metrics verified")
            .sequenceNumber(3)
            .percentageOfTotal(30)
            .amount(contract.getTotalAmount().multiply(new BigDecimal("0.30")))
            .trigger(MilestoneTrigger.METRICS_VERIFIED)
            .status(MilestoneStatus.PENDING)
            .deadline(contract.getEndDate().plusDays(7))
            .build());
        
        return milestones;
    }
}
```

### 5.2 Contract Signing Service

```java
@Service
public class ContractSigningService {
    
    @Transactional
    public Contract signContract(String creatorId, String contractId, SignContractRequest request) {
        Contract contract = contractRepo.findById(contractId)
            .orElseThrow(() -> new ContractNotFoundException(contractId));
        
        // Validate ownership
        if (!contract.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedException("Not your contract");
        }
        
        // Validate state
        if (contract.getStatus() != ContractStatus.PENDING_CREATOR) {
            throw new InvalidStateException("Contract not pending your signature");
        }
        
        // Validate all confirmations
        validateConfirmations(request.getConfirmations());
        
        // Process signature
        String signatureHash = processSignature(request.getSignatureData());
        
        contract.setCreatorSignatureHash(signatureHash);
        contract.setCreatorSignedAt(Instant.now());
        contract.setCreatorSignedIp(getClientIp());
        contract.setCreatorAcceptedTerms(true);
        
        // Update status based on whether brand has signed
        if (contract.getBrandSignedAt() != null) {
            contract.setStatus(ContractStatus.FULLY_SIGNED);
            
            // Trigger escrow funding (async)
            escrowService.initiateEscrowFunding(contract);
        } else {
            contract.setStatus(ContractStatus.PENDING_BRAND);
        }
        
        // Regenerate PDF with signature
        regeneratePdfWithSignature(contract);
        
        contractRepo.save(contract);
        
        // Record audit
        auditService.recordContractSigning(contract, creatorId);
        
        // Notify brand
        notificationService.notifyBrand(contract.getBrand().getId(),
            NotificationType.CONTRACT_SIGNED_BY_CREATOR,
            Map.of("contractId", contractId)
        );
        
        return contract;
    }
    
    private String processSignature(String signatureData) {
        // Decode base64 signature image
        byte[] signatureBytes = Base64.getDecoder().decode(
            signatureData.replace("data:image/png;base64,", "")
        );
        
        // Store signature image (encrypted)
        String signatureUrl = storageService.uploadEncrypted(
            "signatures/" + Ulids.generate() + ".png",
            signatureBytes
        );
        
        // Return hash of signature for verification
        return DigestUtils.sha256Hex(signatureBytes);
    }
    
    private void validateConfirmations(SignatureConfirmations confirmations) {
        if (!Boolean.TRUE.equals(confirmations.getReadTerms())) {
            throw new ValidationException("Must confirm reading terms");
        }
        if (!Boolean.TRUE.equals(confirmations.getUnderstandDeliverables())) {
            throw new ValidationException("Must confirm understanding deliverables");
        }
        if (!Boolean.TRUE.equals(confirmations.getAgreeToTimeline())) {
            throw new ValidationException("Must agree to timeline");
        }
        if (!Boolean.TRUE.equals(confirmations.getConfirmLegalCapacity())) {
            throw new ValidationException("Must confirm legal capacity");
        }
    }
}
```

### 5.3 PDF Generation Service

```java
@Service
public class PdfGenerationService {
    
    private final TemplateEngine templateEngine;
    private final PdfRendererService pdfRenderer;
    
    public byte[] generateContractPdf(Contract contract, ContractTemplate template) {
        // Build template context
        Map<String, Object> context = buildTemplateContext(contract);
        
        // Render each section
        String headerHtml = templateEngine.process(template.getHeaderTemplate(), context);
        String scopeHtml = templateEngine.process(template.getScopeTemplate(), context);
        String paymentHtml = templateEngine.process(template.getPaymentTemplate(), context);
        String deliverablesHtml = templateEngine.process(template.getDeliverablesTemplate(), context);
        String termsHtml = templateEngine.process(template.getTermsTemplate(), context);
        String signatureHtml = templateEngine.process(template.getSignatureTemplate(), context);
        
        // Combine into full HTML
        String fullHtml = String.join("\n",
            headerHtml,
            scopeHtml,
            deliverablesHtml,
            paymentHtml,
            termsHtml,
            signatureHtml
        );
        
        // Render to PDF
        return pdfRenderer.renderHtmlToPdf(fullHtml, PdfOptions.builder()
            .pageSize(PageSize.A4)
            .marginTop(20)
            .marginBottom(20)
            .marginLeft(25)
            .marginRight(25)
            .headerTemplate(buildHeaderTemplate(contract))
            .footerTemplate(buildFooterTemplate(contract))
            .build());
    }
    
    private Map<String, Object> buildTemplateContext(Contract contract) {
        return Map.of(
            "contractNumber", contract.getContractNumber(),
            "date", formatDate(contract.getGeneratedAt()),
            "creator", buildCreatorContext(contract.getCreator()),
            "brand", buildBrandContext(contract.getBrand()),
            "campaign", contract.getCampaign().getTitle(),
            "totalAmount", formatCurrency(contract.getTotalAmount()),
            "deliverables", contract.getDeliverables(),
            "milestones", contract.getMilestones(),
            "timeline", buildTimelineContext(contract),
            "terms", contract.getTerms()
        );
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Contract Review Page

```tsx
export function ContractReviewPage({ contractId }: { contractId: string }) {
  const { data: contract, isLoading, refetch } = useContract(contractId);
  const [signOpen, setSignOpen] = useState(false);
  const [changesOpen, setChangesOpen] = useState(false);
  
  if (isLoading) return <ContractSkeleton />;
  if (!contract) return <NotFound />;
  
  const needsSignature = contract.status === 'PENDING_CREATOR';
  
  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold">Contract Review</h1>
          <p className="text-muted-foreground">
            {contract.campaign.title} - {contract.campaign.brand.name}
          </p>
        </div>
        
        <div className="text-right">
          <Badge variant={getStatusVariant(contract.status)}>
            {CONTRACT_STATUS_LABELS[contract.status]}
          </Badge>
          <p className="text-sm text-muted-foreground mt-1">
            Contract #{contract.contractNumber}
          </p>
        </div>
      </div>
      
      {/* Action Banner */}
      {needsSignature && (
        <Alert>
          <FileSignature className="h-4 w-4" />
          <AlertTitle>Your signature is required</AlertTitle>
          <AlertDescription>
            Please review the contract terms and sign to proceed.
            This contract expires on {formatDate(contract.expiresAt)}.
          </AlertDescription>
        </Alert>
      )}
      
      <div className="grid grid-cols-3 gap-6">
        {/* Main Content */}
        <div className="col-span-2 space-y-6">
          {/* Contract PDF Preview */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle>Contract Document</CardTitle>
              <Button variant="outline" size="sm" asChild>
                <a href={contract.pdfUrl} download>
                  <Download className="h-4 w-4 mr-2" />
                  Download PDF
                </a>
              </Button>
            </CardHeader>
            <CardContent>
              <div className="aspect-[8.5/11] bg-muted rounded-lg overflow-hidden">
                <iframe
                  src={`${contract.pdfUrl}#view=FitH`}
                  className="w-full h-full"
                  title="Contract Preview"
                />
              </div>
            </CardContent>
          </Card>
          
          {/* Key Terms Summary */}
          <Card>
            <CardHeader>
              <CardTitle>Key Terms</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* Deliverables */}
              <div>
                <h4 className="font-medium mb-2">Deliverables</h4>
                <div className="space-y-2">
                  {contract.deliverables.map((d, i) => (
                    <div key={i} className="flex justify-between p-3 bg-muted rounded-lg">
                      <div>
                        <p className="font-medium">
                          {d.quantity}x {formatDeliverableType(d.type)}
                        </p>
                        <p className="text-sm text-muted-foreground">{d.description}</p>
                      </div>
                      <p className="font-medium">{formatCurrency(d.rate)}</p>
                    </div>
                  ))}
                </div>
              </div>
              
              {/* Timeline */}
              <div>
                <h4 className="font-medium mb-2">Timeline</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div className="p-3 bg-muted rounded-lg">
                    <p className="text-sm text-muted-foreground">Start Date</p>
                    <p className="font-medium">{formatDate(contract.terms.startDate)}</p>
                  </div>
                  <div className="p-3 bg-muted rounded-lg">
                    <p className="text-sm text-muted-foreground">End Date</p>
                    <p className="font-medium">{formatDate(contract.terms.endDate)}</p>
                  </div>
                  <div className="p-3 bg-muted rounded-lg">
                    <p className="text-sm text-muted-foreground">Content Deadline</p>
                    <p className="font-medium">{formatDate(contract.terms.contentDeadline)}</p>
                  </div>
                  <div className="p-3 bg-muted rounded-lg">
                    <p className="text-sm text-muted-foreground">Posting Deadline</p>
                    <p className="font-medium">{formatDate(contract.terms.postingDeadline)}</p>
                  </div>
                </div>
              </div>
              
              {/* Content Rights */}
              <div>
                <h4 className="font-medium mb-2">Content Rights</h4>
                <p className="text-sm text-muted-foreground">
                  {contract.legal.contentRights}
                </p>
              </div>
              
              {/* Exclusivity */}
              {contract.legal.exclusivity && (
                <div>
                  <h4 className="font-medium mb-2">Exclusivity</h4>
                  <p className="text-sm text-muted-foreground">
                    {contract.legal.exclusivity}
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
        
        {/* Sidebar */}
        <div className="space-y-4">
          {/* Payment Summary */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Payment Summary</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <p className="text-sm text-muted-foreground">Total Contract Value</p>
                <p className="text-2xl font-bold">{formatCurrency(contract.terms.totalAmount)}</p>
              </div>
              
              <Separator />
              
              <div>
                <p className="text-sm font-medium mb-2">Payment Milestones</p>
                <div className="space-y-3">
                  {contract.milestones.map((milestone, i) => (
                    <div key={i} className="flex items-center justify-between">
                      <div>
                        <p className="text-sm font-medium">{milestone.name}</p>
                        <p className="text-xs text-muted-foreground">
                          Due: {formatDate(milestone.deadline)}
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="text-sm font-medium">
                          {formatCurrency(milestone.amount)}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {milestone.percentageOfTotal}%
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </CardContent>
          </Card>
          
          {/* Signature Status */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Signatures</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  {contract.signature.brandSigned ? (
                    <CheckCircle className="h-5 w-5 text-green-500" />
                  ) : (
                    <Clock className="h-5 w-5 text-muted-foreground" />
                  )}
                  <span>{contract.campaign.brand.name}</span>
                </div>
                {contract.signature.brandSigned && (
                  <span className="text-xs text-muted-foreground">
                    {formatDate(contract.signature.brandSignedAt)}
                  </span>
                )}
              </div>
              
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  {contract.signature.creatorSigned ? (
                    <CheckCircle className="h-5 w-5 text-green-500" />
                  ) : (
                    <Clock className="h-5 w-5 text-muted-foreground" />
                  )}
                  <span>You</span>
                </div>
                {contract.signature.creatorSigned && (
                  <span className="text-xs text-muted-foreground">
                    {formatDate(contract.signature.creatorSignedAt)}
                  </span>
                )}
              </div>
            </CardContent>
          </Card>
          
          {/* Actions */}
          {needsSignature && (
            <Card>
              <CardContent className="p-4 space-y-3">
                <Button className="w-full" onClick={() => setSignOpen(true)}>
                  <FileSignature className="h-4 w-4 mr-2" />
                  Sign Contract
                </Button>
                
                <Button 
                  variant="outline" 
                  className="w-full"
                  onClick={() => setChangesOpen(true)}
                >
                  Request Changes
                </Button>
                
                <DeclineContractButton contractId={contract.id} />
              </CardContent>
            </Card>
          )}
        </div>
      </div>
      
      {/* Sign Modal */}
      <SignContractModal
        contract={contract}
        open={signOpen}
        onClose={() => setSignOpen(false)}
        onSuccess={refetch}
      />
      
      {/* Request Changes Modal */}
      <RequestChangesModal
        contract={contract}
        open={changesOpen}
        onClose={() => setChangesOpen(false)}
        onSuccess={refetch}
      />
    </div>
  );
}
```

### 6.2 Signature Modal

```tsx
export function SignContractModal({
  contract,
  open,
  onClose,
  onSuccess,
}: {
  contract: ContractDetail;
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [step, setStep] = useState(1);
  const [confirmations, setConfirmations] = useState({
    readTerms: false,
    understandDeliverables: false,
    agreeToTimeline: false,
    confirmLegalCapacity: false,
  });
  const [signatureData, setSignatureData] = useState<string | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  
  const allConfirmed = Object.values(confirmations).every(Boolean);
  
  const { mutate: sign, isLoading } = useMutation({
    mutationFn: () => signContract(contract.id, {
      signatureData,
      acceptedTerms: true,
      confirmations,
    }),
    onSuccess: () => {
      toast.success('Contract signed successfully!');
      onSuccess();
      onClose();
    },
  });
  
  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Sign Contract</DialogTitle>
          <DialogDescription>
            Contract #{contract.contractNumber}
          </DialogDescription>
        </DialogHeader>
        
        {step === 1 && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">
              Please confirm you have reviewed and agree to the following:
            </p>
            
            <div className="space-y-3">
              <label className="flex items-start gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/50">
                <Checkbox
                  checked={confirmations.readTerms}
                  onCheckedChange={(checked) => 
                    setConfirmations({ ...confirmations, readTerms: !!checked })
                  }
                />
                <div>
                  <p className="font-medium">I have read the contract terms</p>
                  <p className="text-sm text-muted-foreground">
                    Including payment terms, deadlines, and cancellation policy
                  </p>
                </div>
              </label>
              
              <label className="flex items-start gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/50">
                <Checkbox
                  checked={confirmations.understandDeliverables}
                  onCheckedChange={(checked) => 
                    setConfirmations({ ...confirmations, understandDeliverables: !!checked })
                  }
                />
                <div>
                  <p className="font-medium">I understand the deliverables required</p>
                  <p className="text-sm text-muted-foreground">
                    {contract.deliverables.length} deliverables with specific requirements
                  </p>
                </div>
              </label>
              
              <label className="flex items-start gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/50">
                <Checkbox
                  checked={confirmations.agreeToTimeline}
                  onCheckedChange={(checked) => 
                    setConfirmations({ ...confirmations, agreeToTimeline: !!checked })
                  }
                />
                <div>
                  <p className="font-medium">I agree to the timeline</p>
                  <p className="text-sm text-muted-foreground">
                    Content due by {formatDate(contract.terms.contentDeadline)}
                  </p>
                </div>
              </label>
              
              <label className="flex items-start gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/50">
                <Checkbox
                  checked={confirmations.confirmLegalCapacity}
                  onCheckedChange={(checked) => 
                    setConfirmations({ ...confirmations, confirmLegalCapacity: !!checked })
                  }
                />
                <div>
                  <p className="font-medium">I have the legal capacity to sign</p>
                  <p className="text-sm text-muted-foreground">
                    I am 18+ and authorized to enter this agreement
                  </p>
                </div>
              </label>
            </div>
          </div>
        )}
        
        {step === 2 && (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">
              Draw your signature below:
            </p>
            
            <div className="border rounded-lg p-2 bg-white">
              <SignatureCanvas
                ref={canvasRef}
                penColor="black"
                canvasProps={{
                  className: 'w-full h-32',
                }}
                onEnd={() => {
                  setSignatureData(canvasRef.current?.toDataURL() || null);
                }}
              />
            </div>
            
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                canvasRef.current?.clear();
                setSignatureData(null);
              }}
            >
              Clear
            </Button>
            
            <Alert>
              <Info className="h-4 w-4" />
              <AlertDescription>
                By signing, you agree to the contract terms and create a legally binding agreement.
              </AlertDescription>
            </Alert>
          </div>
        )}
        
        <DialogFooter>
          {step === 1 ? (
            <Button
              onClick={() => setStep(2)}
              disabled={!allConfirmed}
            >
              Continue to Sign
            </Button>
          ) : (
            <>
              <Button variant="outline" onClick={() => setStep(1)}>
                Back
              </Button>
              <Button
                onClick={() => sign()}
                disabled={!signatureData || isLoading}
              >
                {isLoading ? <Spinner /> : 'Sign Contract'}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 Signature Security
- **Signature Hash:** Store SHA-256 hash of signature image
- **Timestamp:** Record exact signing time (server-side)
- **IP Logging:** Log signing IP for audit trail
- **Encryption:** Store signature images encrypted at rest

### 7.2 PDF Integrity
- **Hash Verification:** SHA-256 hash of PDF stored
- **Version Control:** Track all PDF versions
- **Tamper Detection:** Verify hash on every access

### 7.3 Access Control
- **Ownership:** Only contract parties can view/sign
- **State Enforcement:** Only valid state transitions allowed
- **Deadline Enforcement:** Server-side expiration checks

### 7.4 Legal Compliance
- **Electronic Signature Act:** Compliant with IT Act 2000
- **Audit Trail:** Complete record of all actions
- **Non-Repudiation:** Cryptographic proof of signatures

---

## 8. Test Cases (Kavya)

```java
// Contract Generation Tests
@Test void shouldGenerateContractFromAcceptedBid()
@Test void shouldGenerateUniquContractNumber()
@Test void shouldCreateCorrectMilestones()
@Test void shouldGeneratePdfWithCorrectContent()

// Signing Tests
@Test void shouldSignContractAsCreator()
@Test void shouldRequireAllConfirmations()
@Test void shouldStoreSignatureHash()
@Test void shouldUpdateStatusAfterBothSign()

// Change Request Tests
@Test void shouldSubmitChangeRequest()
@Test void shouldNotifyBrandOfChangeRequest()
@Test void shouldRevertStatusOnChanges()

// PDF Tests
@Test void shouldVerifyPdfIntegrity()
@Test void shouldRegeneratePdfAfterSignature()
@Test void shouldIncludeBothSignaturesInFinalPdf()

// State Machine Tests
@Test void shouldTransitionPendingToSigned()
@Test void shouldNotAllowSigningExpiredContract()
@Test void shouldHandleContractDecline()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/contracts` | GET | JWT | List contracts |
| `/creator/contracts/{id}` | GET | JWT | Get contract details |
| `/creator/contracts/{id}/sign` | POST | JWT | Sign contract |
| `/creator/contracts/{id}/request-changes` | POST | JWT | Request changes |
| `/creator/contracts/{id}/decline` | POST | JWT | Decline contract |
| `/creator/contracts/{id}/pdf` | GET | JWT | Download PDF |
