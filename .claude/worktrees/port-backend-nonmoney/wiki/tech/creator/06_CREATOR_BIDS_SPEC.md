# Creator Bids & Negotiation Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Bidding & Negotiation Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BID & NEGOTIATION FLOW                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CREATOR                                    BRAND                            │
│  ┌──────────┐                              ┌──────────┐                     │
│  │ Submit   │ ─────────────────────────→  │ Review   │                     │
│  │ Bid      │                              │ Bid      │                     │
│  └──────────┘                              └──────────┘                     │
│                                                 │                           │
│                                                 ↓                           │
│                                            ┌─────────┐                      │
│                                            │ Accept/ │                      │
│                                            │ Counter/│                      │
│                                            │ Reject  │                      │
│                                            └─────────┘                      │
│                                                 │                           │
│         ┌───────────────────────────────────────┼───────────────────┐       │
│         ↓                                       ↓                   ↓       │
│  ┌──────────┐                            ┌──────────┐        ┌──────────┐  │
│  │ Bid      │ ←─────Counter Offer────── │ Counter  │        │ Rejected │  │
│  │ Accepted │                            │ Received │        │          │  │
│  └──────────┘                            └──────────┘        └──────────┘  │
│       │                                       │                            │
│       │                                       ↓                            │
│       │                               ┌─────────────┐                      │
│       │                               │ Accept/     │                      │
│       │                               │ Counter/    │                      │
│       │                               │ Decline     │                      │
│       │                               └─────────────┘                      │
│       │                                       │                            │
│       ↓                                       ↓                            │
│  ┌──────────┐                          ┌──────────┐                        │
│  │ Contract │ ←────────────────────── │ Agreement│                        │
│  │ Created  │                          │ Reached  │                        │
│  └──────────┘                          └──────────┘                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Bid States

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              BID STATE MACHINE                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐                                                               │
│  │ PENDING  │ ──→ Brand reviews bid                                        │
│  └──────────┘                                                               │
│       │                                                                      │
│       ├──────→ ACCEPTED ──→ Contract generation                             │
│       │                                                                      │
│       ├──────→ REJECTED ──→ End                                             │
│       │                                                                      │
│       ├──────→ COUNTER_SENT ──→ Creator reviews                             │
│       │              │                                                       │
│       │              ├──→ COUNTER_ACCEPTED ──→ Contract                     │
│       │              │                                                       │
│       │              ├──→ COUNTER_REJECTED ──→ End                          │
│       │              │                                                       │
│       │              └──→ CREATOR_COUNTER ──→ Brand reviews (loop)          │
│       │                                                                      │
│       └──────→ WITHDRAWN ──→ Creator withdrew                               │
│                                                                              │
│       └──────→ EXPIRED ──→ No response within deadline                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 Bid Entity

```java
@Entity
@Table(name = "bids")
public class Bid {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "application_id")
    private CampaignApplication application;
    
    @ManyToOne
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    @ManyToOne
    @JoinColumn(name = "brand_id")
    private BrandProfile brand;
    
    // Bid amounts by deliverable type
    @Convert(converter = JsonMapConverter.class)
    private Map<String, BigDecimal> deliverableRates;
    // { "INSTAGRAM_REEL": 25000, "INSTAGRAM_STORY": 5000 }
    
    private BigDecimal totalAmount;
    
    // Timeline
    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    
    // Status
    @Enumerated(EnumType.STRING)
    private BidStatus status;
    
    // Negotiation tracking
    private Integer negotiationRound;  // 1, 2, 3...
    private String latestMessage;      // Most recent negotiation note
    
    // Response deadline
    private Instant responseDeadline;  // 48-72 hours typically
    
    // Timestamps
    private Instant createdAt;
    private Instant updatedAt;
    private Instant respondedAt;
}

public enum BidStatus {
    PENDING,           // Initial bid, awaiting brand response
    ACCEPTED,          // Brand accepted, ready for contract
    REJECTED,          // Brand rejected bid
    COUNTER_SENT,      // Brand sent counter-offer
    COUNTER_ACCEPTED,  // Creator accepted counter-offer
    COUNTER_REJECTED,  // Creator rejected counter-offer
    CREATOR_COUNTER,   // Creator sent counter to brand's counter
    WITHDRAWN,         // Creator withdrew bid
    EXPIRED            // No response within deadline
}
```

### 3.2 BidHistory Entity (Negotiation Trail)

```java
@Entity
@Table(name = "bid_history")
public class BidHistory {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "bid_id")
    private Bid bid;
    
    @Enumerated(EnumType.STRING)
    private BidAction action;
    
    // Who performed action
    @Enumerated(EnumType.STRING)
    private ActorType actorType;  // CREATOR, BRAND
    
    private String actorId;
    
    // Amounts at this point
    @Convert(converter = JsonMapConverter.class)
    private Map<String, BigDecimal> deliverableRates;
    
    private BigDecimal totalAmount;
    
    // Message/note
    private String message;
    
    // Previous and new status
    @Enumerated(EnumType.STRING)
    private BidStatus previousStatus;
    
    @Enumerated(EnumType.STRING)
    private BidStatus newStatus;
    
    private Instant createdAt;
}

public enum BidAction {
    SUBMITTED,         // Initial bid submission
    VIEWED,            // Other party viewed
    ACCEPTED,          // Accepted as-is
    REJECTED,          // Rejected
    COUNTER_OFFERED,   // Sent counter-offer
    WITHDRAWN,         // Withdrawn by creator
    EXPIRED            // Auto-expired
}
```

### 3.3 NegotiationChat Entity

```java
@Entity
@Table(name = "negotiation_chats")
public class NegotiationChat {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "bid_id")
    private Bid bid;
    
    @Enumerated(EnumType.STRING)
    private SenderType senderType;  // CREATOR, BRAND
    
    private String senderId;
    
    private String message;
    
    @Convert(converter = JsonListConverter.class)
    private List<String> attachmentUrls;  // Media kit, portfolio, etc.
    
    private Boolean isRead;
    private Instant readAt;
    
    private Instant sentAt;
}
```

---

## 4. API Endpoints

### 4.1 Submit Bid (From Application)

```
POST /api/v1/creator/applications/{applicationId}/bid
{
    "deliverableRates": {
        "INSTAGRAM_REEL": 25000,
        "INSTAGRAM_STORY": 5000
    },
    "totalAmount": 55000,
    "proposedStartDate": "2026-07-22",
    "proposedEndDate": "2026-08-10",
    "message": "I'm excited to work on this campaign. Based on my experience..."
}

Response:
{
    "bidId": "bid_xxx",
    "status": "PENDING",
    "responseDeadline": "2026-07-10T14:30:00Z",
    "message": "Bid submitted! The brand has 48 hours to respond."
}
```

### 4.2 Get My Bids

```
GET /api/v1/creator/bids
Query Parameters:
  status    - Filter by status
  sort      - (created_at, amount, deadline)
  page, size

Response:
{
    "bids": [
        {
            "id": "bid_xxx",
            "campaign": {
                "id": "camp_xxx",
                "title": "Summer Fitness Challenge",
                "brand": {...}
            },
            "status": "COUNTER_SENT",
            "originalAmount": 55000,
            "currentAmount": 50000,
            "negotiationRound": 2,
            "responseDeadline": "2026-07-10T14:30:00Z",
            "pendingAction": "RESPOND_TO_COUNTER",
            "latestMessage": "We can offer 50K for this package...",
            "createdAt": "2026-07-07T10:00:00Z",
            "updatedAt": "2026-07-08T15:00:00Z"
        }
    ],
    "summary": {
        "pending": 3,
        "counterReceived": 2,
        "accepted": 1,
        "total": 6
    }
}
```

### 4.3 Get Bid Details

```
GET /api/v1/creator/bids/{bidId}

Response:
{
    "id": "bid_xxx",
    "campaign": {...},
    "status": "COUNTER_SENT",
    "deliverableRates": {
        "INSTAGRAM_REEL": 25000,
        "INSTAGRAM_STORY": 5000
    },
    "totalAmount": 55000,
    "proposedStartDate": "2026-07-22",
    "proposedEndDate": "2026-08-10",
    "negotiationRound": 2,
    "responseDeadline": "2026-07-10T14:30:00Z",
    "counterOffer": {
        "deliverableRates": {
            "INSTAGRAM_REEL": 22000,
            "INSTAGRAM_STORY": 4000
        },
        "totalAmount": 50000,
        "message": "We can offer 50K for this package. This is in line with our budget...",
        "sentAt": "2026-07-08T15:00:00Z"
    },
    "history": [
        {
            "action": "SUBMITTED",
            "actorType": "CREATOR",
            "amount": 55000,
            "message": "Initial bid",
            "createdAt": "2026-07-07T10:00:00Z"
        },
        {
            "action": "COUNTER_OFFERED",
            "actorType": "BRAND",
            "amount": 50000,
            "message": "We can offer 50K...",
            "createdAt": "2026-07-08T15:00:00Z"
        }
    ],
    "chat": [
        {
            "senderType": "BRAND",
            "message": "Hi! Thanks for applying. Can you share more examples?",
            "sentAt": "2026-07-07T14:00:00Z"
        },
        {
            "senderType": "CREATOR",
            "message": "Sure! Here's my portfolio...",
            "attachments": ["https://..."],
            "sentAt": "2026-07-07T14:30:00Z"
        }
    ]
}
```

### 4.4 Respond to Counter-Offer

```
POST /api/v1/creator/bids/{bidId}/respond
{
    "action": "ACCEPT",  // ACCEPT, REJECT, COUNTER
    "message": "I accept this offer. Looking forward to working together!"
}

Response:
{
    "bidId": "bid_xxx",
    "status": "COUNTER_ACCEPTED",
    "message": "Counter-offer accepted! Contract will be generated shortly.",
    "nextStep": "AWAIT_CONTRACT"
}
```

### 4.5 Submit Counter to Counter-Offer

```
POST /api/v1/creator/bids/{bidId}/counter
{
    "deliverableRates": {
        "INSTAGRAM_REEL": 24000,
        "INSTAGRAM_STORY": 4500
    },
    "totalAmount": 52500,
    "message": "I can meet you halfway at 52.5K. This accounts for..."
}

Response:
{
    "bidId": "bid_xxx",
    "status": "CREATOR_COUNTER",
    "message": "Counter-offer sent! The brand has 48 hours to respond.",
    "responseDeadline": "2026-07-12T14:30:00Z"
}
```

### 4.6 Withdraw Bid

```
DELETE /api/v1/creator/bids/{bidId}
{
    "reason": "Schedule conflict with another commitment"
}

Response:
{
    "success": true,
    "message": "Bid withdrawn successfully"
}
```

### 4.7 Negotiation Chat

```
POST /api/v1/creator/bids/{bidId}/messages
{
    "message": "Can you provide more details about the posting schedule?",
    "attachments": []
}

GET /api/v1/creator/bids/{bidId}/messages
→ List all messages in negotiation thread
```

---

## 5. Backend Implementation

### 5.1 Bid Service

```java
@Service
public class BidService {
    
    private final BidRepository bidRepo;
    private final BidHistoryRepository historyRepo;
    private final NotificationService notificationService;
    private final ContractService contractService;
    
    @Transactional
    public Bid submitBid(String creatorId, String applicationId, BidRequest request) {
        CampaignApplication application = applicationRepo.findById(applicationId)
            .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        
        // Validate creator owns application
        if (!application.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedException("Not your application");
        }
        
        // Validate application is in correct state
        if (application.getStatus() != ApplicationStatus.SHORTLISTED) {
            throw new InvalidStateException("Can only bid on shortlisted applications");
        }
        
        // Validate rates are within reasonable bounds
        validateBidRates(request, application.getCampaign());
        
        // Create bid
        Bid bid = Bid.builder()
            .id(Ulids.generate())
            .application(application)
            .campaign(application.getCampaign())
            .creator(application.getCreator())
            .brand(application.getCampaign().getBrand())
            .deliverableRates(request.getDeliverableRates())
            .totalAmount(request.getTotalAmount())
            .proposedStartDate(request.getProposedStartDate())
            .proposedEndDate(request.getProposedEndDate())
            .status(BidStatus.PENDING)
            .negotiationRound(1)
            .latestMessage(request.getMessage())
            .responseDeadline(Instant.now().plus(48, ChronoUnit.HOURS))
            .createdAt(Instant.now())
            .build();
        
        bidRepo.save(bid);
        
        // Record history
        recordHistory(bid, BidAction.SUBMITTED, ActorType.CREATOR, creatorId,
            request.getTotalAmount(), request.getMessage());
        
        // Update application status
        application.setStatus(ApplicationStatus.NEGOTIATING);
        applicationRepo.save(application);
        
        // Notify brand
        notificationService.notifyBrand(bid.getBrand().getId(),
            NotificationType.NEW_BID,
            Map.of(
                "bidId", bid.getId(),
                "campaignTitle", bid.getCampaign().getTitle(),
                "creatorName", bid.getCreator().getDisplayName(),
                "amount", bid.getTotalAmount()
            )
        );
        
        return bid;
    }
    
    @Transactional
    public Bid respondToCounter(String creatorId, String bidId, BidResponseRequest request) {
        Bid bid = bidRepo.findById(bidId)
            .orElseThrow(() -> new BidNotFoundException(bidId));
        
        // Validate ownership
        if (!bid.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedException("Not your bid");
        }
        
        // Validate state
        if (bid.getStatus() != BidStatus.COUNTER_SENT) {
            throw new InvalidStateException("No pending counter-offer");
        }
        
        // Check deadline
        if (bid.getResponseDeadline().isBefore(Instant.now())) {
            throw new DeadlinePassedException("Response deadline has passed");
        }
        
        BidStatus newStatus;
        switch (request.getAction()) {
            case ACCEPT -> {
                newStatus = BidStatus.COUNTER_ACCEPTED;
                
                // Trigger contract generation
                contractService.generateContract(bid);
                
                // Update application
                bid.getApplication().setStatus(ApplicationStatus.ACCEPTED);
            }
            case REJECT -> {
                newStatus = BidStatus.COUNTER_REJECTED;
                bid.getApplication().setStatus(ApplicationStatus.REJECTED);
            }
            case COUNTER -> {
                throw new InvalidActionException("Use /counter endpoint for counter-offers");
            }
            default -> throw new InvalidActionException("Unknown action");
        }
        
        bid.setStatus(newStatus);
        bid.setRespondedAt(Instant.now());
        bid.setUpdatedAt(Instant.now());
        bidRepo.save(bid);
        
        // Record history
        recordHistory(bid, 
            request.getAction() == BidResponseAction.ACCEPT ? BidAction.ACCEPTED : BidAction.REJECTED,
            ActorType.CREATOR, creatorId, bid.getTotalAmount(), request.getMessage());
        
        // Notify brand
        notificationService.notifyBrand(bid.getBrand().getId(),
            request.getAction() == BidResponseAction.ACCEPT 
                ? NotificationType.BID_ACCEPTED 
                : NotificationType.BID_REJECTED,
            Map.of("bidId", bidId, "creatorName", bid.getCreator().getDisplayName())
        );
        
        return bid;
    }
    
    @Transactional
    public Bid submitCreatorCounter(String creatorId, String bidId, CounterOfferRequest request) {
        Bid bid = bidRepo.findById(bidId)
            .orElseThrow(() -> new BidNotFoundException(bidId));
        
        // Validate ownership
        if (!bid.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedException("Not your bid");
        }
        
        // Validate state - can counter if brand sent counter
        if (bid.getStatus() != BidStatus.COUNTER_SENT) {
            throw new InvalidStateException("Cannot counter at this stage");
        }
        
        // Validate max negotiation rounds
        if (bid.getNegotiationRound() >= 5) {
            throw new MaxNegotiationRoundsException("Maximum 5 negotiation rounds allowed");
        }
        
        // Update bid with new amounts
        bid.setDeliverableRates(request.getDeliverableRates());
        bid.setTotalAmount(request.getTotalAmount());
        bid.setLatestMessage(request.getMessage());
        bid.setStatus(BidStatus.CREATOR_COUNTER);
        bid.setNegotiationRound(bid.getNegotiationRound() + 1);
        bid.setResponseDeadline(Instant.now().plus(48, ChronoUnit.HOURS));
        bid.setUpdatedAt(Instant.now());
        
        bidRepo.save(bid);
        
        // Record history
        recordHistory(bid, BidAction.COUNTER_OFFERED, ActorType.CREATOR, creatorId,
            request.getTotalAmount(), request.getMessage());
        
        // Notify brand
        notificationService.notifyBrand(bid.getBrand().getId(),
            NotificationType.COUNTER_RECEIVED,
            Map.of("bidId", bidId, "newAmount", request.getTotalAmount())
        );
        
        return bid;
    }
    
    private void recordHistory(Bid bid, BidAction action, ActorType actorType, 
                               String actorId, BigDecimal amount, String message) {
        BidHistory history = BidHistory.builder()
            .id(Ulids.generate())
            .bid(bid)
            .action(action)
            .actorType(actorType)
            .actorId(actorId)
            .deliverableRates(bid.getDeliverableRates())
            .totalAmount(amount)
            .message(message)
            .previousStatus(bid.getStatus())
            .newStatus(bid.getStatus())
            .createdAt(Instant.now())
            .build();
        
        historyRepo.save(history);
    }
}
```

### 5.2 Bid Expiration Job

```java
@Service
public class BidExpirationService {
    
    @Scheduled(fixedRate = 60000)  // Every minute
    public void expireOverdueBids() {
        List<Bid> expiredBids = bidRepo.findByResponseDeadlineBeforeAndStatusIn(
            Instant.now(),
            List.of(BidStatus.PENDING, BidStatus.COUNTER_SENT, BidStatus.CREATOR_COUNTER)
        );
        
        for (Bid bid : expiredBids) {
            bid.setStatus(BidStatus.EXPIRED);
            bid.setUpdatedAt(Instant.now());
            bidRepo.save(bid);
            
            // Record history
            BidHistory history = BidHistory.builder()
                .id(Ulids.generate())
                .bid(bid)
                .action(BidAction.EXPIRED)
                .actorType(ActorType.SYSTEM)
                .createdAt(Instant.now())
                .build();
            historyRepo.save(history);
            
            // Notify both parties
            notificationService.notifyCreator(bid.getCreator().getId(),
                NotificationType.BID_EXPIRED,
                Map.of("campaignTitle", bid.getCampaign().getTitle())
            );
            
            notificationService.notifyBrand(bid.getBrand().getId(),
                NotificationType.BID_EXPIRED,
                Map.of("creatorName", bid.getCreator().getDisplayName())
            );
        }
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Bids Dashboard

```tsx
export function BidsDashboard() {
  const { data: bids, isLoading } = useBids();
  const [filter, setFilter] = useState('all');
  
  const filteredBids = useMemo(() => {
    if (filter === 'all') return bids;
    if (filter === 'action_needed') {
      return bids?.filter(b => 
        b.status === 'COUNTER_SENT' || 
        (b.status === 'PENDING' && b.pendingAction)
      );
    }
    return bids?.filter(b => b.status === filter);
  }, [bids, filter]);
  
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">My Bids</h1>
        <p className="text-muted-foreground">Track and manage your campaign bids</p>
      </div>
      
      {/* Summary Cards */}
      <div className="grid grid-cols-4 gap-4">
        <SummaryCard
          title="Pending"
          value={bids?.summary?.pending || 0}
          icon={Clock}
          color="text-yellow-500"
        />
        <SummaryCard
          title="Action Needed"
          value={bids?.summary?.counterReceived || 0}
          icon={AlertCircle}
          color="text-orange-500"
        />
        <SummaryCard
          title="Accepted"
          value={bids?.summary?.accepted || 0}
          icon={CheckCircle}
          color="text-green-500"
        />
        <SummaryCard
          title="Total Value"
          value={formatCurrency(bids?.summary?.totalValue || 0)}
          icon={DollarSign}
          color="text-blue-500"
        />
      </div>
      
      {/* Filters */}
      <div className="flex gap-2">
        {[
          { id: 'all', label: 'All' },
          { id: 'action_needed', label: 'Action Needed' },
          { id: 'PENDING', label: 'Pending' },
          { id: 'COUNTER_SENT', label: 'Counter Received' },
          { id: 'ACCEPTED', label: 'Accepted' },
        ].map((f) => (
          <Button
            key={f.id}
            variant={filter === f.id ? 'default' : 'outline'}
            size="sm"
            onClick={() => setFilter(f.id)}
          >
            {f.label}
          </Button>
        ))}
      </div>
      
      {/* Bids List */}
      {isLoading ? (
        <div className="space-y-4">
          {Array(3).fill(0).map((_, i) => <Skeleton key={i} className="h-32" />)}
        </div>
      ) : (
        <div className="space-y-4">
          {filteredBids?.map((bid) => (
            <BidCard key={bid.id} bid={bid} />
          ))}
        </div>
      )}
    </div>
  );
}
```

### 6.2 Bid Card

```tsx
interface BidCardProps {
  bid: BidListItem;
}

export function BidCard({ bid }: BidCardProps) {
  const router = useRouter();
  const timeLeft = useTimeLeft(bid.responseDeadline);
  const needsAction = bid.status === 'COUNTER_SENT';
  
  return (
    <Card className={cn(
      "hover:shadow-md transition-shadow",
      needsAction && "border-orange-300 bg-orange-50/50"
    )}>
      <CardContent className="p-4">
        <div className="flex items-start gap-4">
          {/* Campaign/Brand Info */}
          <Avatar className="h-12 w-12 rounded-lg">
            <AvatarImage src={bid.campaign.brand.logo} />
          </Avatar>
          
          <div className="flex-1 min-w-0">
            <div className="flex items-start justify-between">
              <div>
                <h3 className="font-semibold">{bid.campaign.title}</h3>
                <p className="text-sm text-muted-foreground">{bid.campaign.brand.name}</p>
              </div>
              
              <BidStatusBadge status={bid.status} />
            </div>
            
            {/* Amounts */}
            <div className="mt-3 flex items-center gap-6">
              <div>
                <p className="text-xs text-muted-foreground">Your Bid</p>
                <p className="font-semibold">{formatCurrency(bid.originalAmount)}</p>
              </div>
              
              {bid.currentAmount !== bid.originalAmount && (
                <>
                  <ArrowRight className="h-4 w-4 text-muted-foreground" />
                  <div>
                    <p className="text-xs text-muted-foreground">Counter Offer</p>
                    <p className="font-semibold text-orange-600">
                      {formatCurrency(bid.currentAmount)}
                    </p>
                  </div>
                </>
              )}
            </div>
            
            {/* Latest Message */}
            {bid.latestMessage && (
              <div className="mt-3 p-2 bg-muted rounded text-sm">
                <p className="text-muted-foreground line-clamp-1">
                  "{bid.latestMessage}"
                </p>
              </div>
            )}
            
            {/* Actions */}
            <div className="mt-4 flex items-center justify-between">
              <div className="text-sm text-muted-foreground">
                {needsAction && timeLeft && (
                  <span className="text-orange-600 font-medium">
                    Respond in {timeLeft}
                  </span>
                )}
                {bid.status === 'PENDING' && (
                  <span>Awaiting brand response</span>
                )}
              </div>
              
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => router.push(`/creator/bids/${bid.id}`)}
                >
                  View Details
                </Button>
                
                {needsAction && (
                  <Button size="sm">
                    Respond Now
                  </Button>
                )}
              </div>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
```

### 6.3 Bid Detail Page

```tsx
export function BidDetailPage({ bidId }: { bidId: string }) {
  const { data: bid, isLoading, refetch } = useBid(bidId);
  const [respondOpen, setRespondOpen] = useState(false);
  const [counterOpen, setCounterOpen] = useState(false);
  
  if (isLoading) return <BidDetailSkeleton />;
  if (!bid) return <NotFound />;
  
  const needsAction = bid.status === 'COUNTER_SENT';
  
  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold">{bid.campaign.title}</h1>
          <p className="text-muted-foreground">{bid.campaign.brand.name}</p>
        </div>
        <BidStatusBadge status={bid.status} large />
      </div>
      
      {/* Action Required Banner */}
      {needsAction && (
        <Alert className="bg-orange-50 border-orange-200">
          <AlertCircle className="h-4 w-4 text-orange-500" />
          <AlertTitle>Counter-Offer Received</AlertTitle>
          <AlertDescription>
            The brand has sent a counter-offer. You have until{' '}
            {formatDateTime(bid.responseDeadline)} to respond.
          </AlertDescription>
        </Alert>
      )}
      
      <div className="grid grid-cols-3 gap-6">
        {/* Main Content */}
        <div className="col-span-2 space-y-6">
          {/* Negotiation Timeline */}
          <Card>
            <CardHeader>
              <CardTitle>Negotiation History</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                {bid.history.map((event, i) => (
                  <div key={i} className="flex gap-4">
                    <div className={cn(
                      "h-8 w-8 rounded-full flex items-center justify-center",
                      event.actorType === 'CREATOR' 
                        ? "bg-blue-100 text-blue-600"
                        : "bg-purple-100 text-purple-600"
                    )}>
                      {event.actorType === 'CREATOR' ? (
                        <User className="h-4 w-4" />
                      ) : (
                        <Building className="h-4 w-4" />
                      )}
                    </div>
                    
                    <div className="flex-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">
                          {event.actorType === 'CREATOR' ? 'You' : bid.campaign.brand.name}
                        </span>
                        <span className="text-sm text-muted-foreground">
                          {formatRelativeTime(event.createdAt)}
                        </span>
                      </div>
                      
                      <p className="text-sm text-muted-foreground">
                        {BID_ACTION_LABELS[event.action]}
                        {event.totalAmount && ` - ${formatCurrency(event.totalAmount)}`}
                      </p>
                      
                      {event.message && (
                        <p className="mt-1 p-2 bg-muted rounded text-sm">
                          "{event.message}"
                        </p>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
          
          {/* Negotiation Chat */}
          <Card>
            <CardHeader>
              <CardTitle>Messages</CardTitle>
            </CardHeader>
            <CardContent>
              <NegotiationChat bidId={bid.id} messages={bid.chat} />
            </CardContent>
          </Card>
        </div>
        
        {/* Sidebar */}
        <div className="space-y-4">
          {/* Amount Comparison */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Bid Summary</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <p className="text-sm text-muted-foreground">Your Original Bid</p>
                <p className="text-xl font-bold">{formatCurrency(bid.totalAmount)}</p>
              </div>
              
              {bid.counterOffer && (
                <>
                  <Separator />
                  <div>
                    <p className="text-sm text-muted-foreground">Brand Counter-Offer</p>
                    <p className="text-xl font-bold text-orange-600">
                      {formatCurrency(bid.counterOffer.totalAmount)}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {calculateDifference(bid.totalAmount, bid.counterOffer.totalAmount)}
                    </p>
                  </div>
                </>
              )}
              
              {/* Deliverable Breakdown */}
              <Separator />
              <div className="space-y-2">
                <p className="text-sm font-medium">Deliverables</p>
                {Object.entries(bid.deliverableRates).map(([type, rate]) => (
                  <div key={type} className="flex justify-between text-sm">
                    <span>{formatDeliverableType(type)}</span>
                    <div className="text-right">
                      <span>{formatCurrency(rate)}</span>
                      {bid.counterOffer?.deliverableRates[type] && 
                        bid.counterOffer.deliverableRates[type] !== rate && (
                        <span className="text-orange-600 ml-2">
                          → {formatCurrency(bid.counterOffer.deliverableRates[type])}
                        </span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
          
          {/* Actions */}
          {needsAction && (
            <Card>
              <CardContent className="p-4 space-y-3">
                <Button className="w-full" onClick={() => setRespondOpen(true)}>
                  Accept Counter-Offer
                </Button>
                <Button 
                  variant="outline" 
                  className="w-full"
                  onClick={() => setCounterOpen(true)}
                >
                  Send Counter
                </Button>
                <Button variant="ghost" className="w-full text-destructive">
                  Decline
                </Button>
              </CardContent>
            </Card>
          )}
          
          {/* Timeline */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Timeline</CardTitle>
            </CardHeader>
            <CardContent className="text-sm space-y-2">
              <div className="flex justify-between">
                <span className="text-muted-foreground">Proposed Start</span>
                <span>{formatDate(bid.proposedStartDate)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Proposed End</span>
                <span>{formatDate(bid.proposedEndDate)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Response Deadline</span>
                <span className={cn(needsAction && "text-orange-600 font-medium")}>
                  {formatDateTime(bid.responseDeadline)}
                </span>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
      
      {/* Respond Modal */}
      <RespondModal
        bid={bid}
        open={respondOpen}
        onClose={() => setRespondOpen(false)}
        onSuccess={refetch}
      />
      
      {/* Counter Modal */}
      <CounterOfferModal
        bid={bid}
        open={counterOpen}
        onClose={() => setCounterOpen(false)}
        onSuccess={refetch}
      />
    </div>
  );
}
```

### 6.4 Counter-Offer Modal

```tsx
export function CounterOfferModal({
  bid,
  open,
  onClose,
  onSuccess,
}: {
  bid: BidDetail;
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [rates, setRates] = useState(bid.counterOffer?.deliverableRates || bid.deliverableRates);
  const [message, setMessage] = useState('');
  
  const totalAmount = useMemo(() => 
    Object.values(rates).reduce((sum, rate) => sum + (rate || 0), 0),
    [rates]
  );
  
  const { mutate: submitCounter, isLoading } = useMutation({
    mutationFn: (data: CounterOfferRequest) => 
      api.post(`/creator/bids/${bid.id}/counter`, data),
    onSuccess: () => {
      toast.success('Counter-offer sent!');
      onSuccess();
      onClose();
    },
  });
  
  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Send Counter-Offer</DialogTitle>
          <DialogDescription>
            Round {bid.negotiationRound + 1} of negotiation
          </DialogDescription>
        </DialogHeader>
        
        <div className="space-y-4">
          {/* Rate inputs */}
          {Object.entries(rates).map(([type, rate]) => (
            <div key={type} className="space-y-1">
              <Label>{formatDeliverableType(type)}</Label>
              <div className="flex items-center gap-2">
                <div className="relative flex-1">
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
                    
                  </span>
                  <Input
                    type="number"
                    className="pl-6"
                    value={rate}
                    onChange={(e) => setRates({
                      ...rates,
                      [type]: parseInt(e.target.value) || 0
                    })}
                  />
                </div>
                {bid.counterOffer?.deliverableRates[type] && (
                  <span className="text-sm text-muted-foreground">
                    Brand: {formatCurrency(bid.counterOffer.deliverableRates[type])}
                  </span>
                )}
              </div>
            </div>
          ))}
          
          <Separator />
          
          <div className="flex justify-between text-lg font-semibold">
            <span>Total</span>
            <span>{formatCurrency(totalAmount)}</span>
          </div>
          
          {/* Message */}
          <div>
            <Label>Message (optional)</Label>
            <Textarea
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder="Explain your counter-offer..."
              rows={3}
            />
          </div>
          
          {/* Comparison */}
          <div className="p-3 bg-muted rounded text-sm">
            <div className="flex justify-between">
              <span>Your original bid:</span>
              <span>{formatCurrency(bid.totalAmount)}</span>
            </div>
            <div className="flex justify-between">
              <span>Brand's counter:</span>
              <span>{formatCurrency(bid.counterOffer?.totalAmount)}</span>
            </div>
            <div className="flex justify-between font-medium mt-2 pt-2 border-t">
              <span>Your new counter:</span>
              <span>{formatCurrency(totalAmount)}</span>
            </div>
          </div>
        </div>
        
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button 
            onClick={() => submitCounter({
              deliverableRates: rates,
              totalAmount,
              message
            })}
            disabled={isLoading}
          >
            {isLoading ? <Spinner /> : 'Send Counter-Offer'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 Bid Access Control
- **Ownership Validation:** Only bid creator can view/modify their bids
- **State Transitions:** Enforce valid state machine transitions
- **Deadline Enforcement:** Server-side deadline validation

### 7.2 Rate Limits
- **Counter-Offers:** Maximum 5 rounds of negotiation
- **Response Time:** 48-72 hours default, configurable
- **Message Rate:** Max 20 messages per hour per bid

### 7.3 Data Validation
- **Amount Bounds:** Rates must be positive, within reasonable limits
- **Message Length:** Max 2000 characters per message
- **Attachment Validation:** Only allow specific file types

---

## 8. Test Cases (Kavya)

```java
// Bid Submission Tests
@Test void shouldSubmitBid()
@Test void shouldRejectBidOnNonShortlistedApplication()
@Test void shouldRejectDuplicateBid()
@Test void shouldValidateBidRates()

// Counter-Offer Tests
@Test void shouldAcceptCounterOffer()
@Test void shouldRejectCounterOffer()
@Test void shouldSubmitCreatorCounter()
@Test void shouldEnforceMaxNegotiationRounds()

// Deadline Tests
@Test void shouldExpireBidAfterDeadline()
@Test void shouldRejectResponseAfterDeadline()
@Test void shouldExtendDeadlineOnCounter()

// State Machine Tests
@Test void shouldTransitionPendingToAccepted()
@Test void shouldTransitionPendingToCounterSent()
@Test void shouldNotAllowInvalidTransition()

// Notification Tests
@Test void shouldNotifyBrandOnNewBid()
@Test void shouldNotifyCreatorOnCounter()
@Test void shouldNotifyOnExpiration()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/applications/{id}/bid` | POST | JWT | Submit bid |
| `/creator/bids` | GET | JWT | List my bids |
| `/creator/bids/{id}` | GET | JWT | Get bid details |
| `/creator/bids/{id}/respond` | POST | JWT | Accept/reject counter |
| `/creator/bids/{id}/counter` | POST | JWT | Submit counter-offer |
| `/creator/bids/{id}` | DELETE | JWT | Withdraw bid |
| `/creator/bids/{id}/messages` | GET | JWT | Get negotiation messages |
| `/creator/bids/{id}/messages` | POST | JWT | Send message |
