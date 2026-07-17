# Creator Chat & Messaging Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Messaging Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CREATOR MESSAGING SYSTEM                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────┐        ┌────────────────────────────┐          │
│  │     AI ASSISTANT       │        │      BRAND MESSAGING       │          │
│  │       (MEERA)          │        │                            │          │
│  ├────────────────────────┤        ├────────────────────────────┤          │
│  │ • Profile help         │        │ • Campaign discussions     │          │
│  │ • Rate suggestions     │        │ • Contract negotiations    │          │
│  │ • Campaign guidance    │        │ • Deliverable feedback     │          │
│  │ • Growth tips          │        │ • Payment queries          │          │
│  │ • Platform FAQs        │        │                            │          │
│  └────────────────────────┘        └────────────────────────────┘          │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  📨 Inbox                                                     🔔 3    │   │
│  ├──────────────────────────────────────────────────────────────────────┤   │
│  │                                                                       │   │
│  │  ┌─────────────────────────────────────────────────────────────┐     │   │
│  │  │ HealthKart                                        2 min ago │     │   │
│  │  │ "Great! Let's finalize the posting schedule..."              │     │   │
│  │  └─────────────────────────────────────────────────────────────┘     │   │
│  │                                                                       │   │
│  │  ┌─────────────────────────────────────────────────────────────┐     │   │
│  │  │ 🤖 Meera (AI)                                    Yesterday  │     │   │
│  │  │ "I've analyzed your profile and have some suggestions..."    │     │   │
│  │  └─────────────────────────────────────────────────────────────┘     │   │
│  │                                                                       │   │
│  │  ┌─────────────────────────────────────────────────────────────┐     │   │
│  │  │ Nike India                                      3 days ago  │     │   │
│  │  │ "Thanks for the great content! Payment released."           │     │   │
│  │  └─────────────────────────────────────────────────────────────┘     │   │
│  │                                                                       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Chat Types

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CHAT TYPES                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. AI_ASSISTANT (Meera)                                                    │
│     ├── General Help & FAQs                                                 │
│     ├── Profile Optimization                                                │
│     ├── Rate Calculator                                                     │
│     ├── Campaign Matching                                                   │
│     └── Growth Coaching                                                     │
│                                                                              │
│  2. BRAND_CONVERSATION                                                      │
│     ├── Pre-Campaign (inquiry, negotiation)                                │
│     ├── Active Campaign (deliverables, feedback)                           │
│     └── Post-Campaign (reviews, future opportunities)                      │
│                                                                              │
│  3. SUPPORT_TICKET                                                          │
│     ├── Technical Issues                                                    │
│     ├── Payment Disputes                                                    │
│     └── Account Problems                                                    │
│                                                                              │
│  4. SYSTEM_NOTIFICATIONS                                                    │
│     ├── Campaign Updates                                                    │
│     ├── Payment Notifications                                               │
│     └── Platform Announcements                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 Conversation Entity

```java
@Entity
@Table(name = "conversations")
public class Conversation {
    
    @Id
    private String id;
    
    @Enumerated(EnumType.STRING)
    private ConversationType type;  // AI_ASSISTANT, BRAND_CONVERSATION, SUPPORT
    
    // Participants
    private String creatorId;
    private String brandId;      // Null for AI/support
    private String campaignId;   // Optional, links to specific campaign
    
    // AI conversation context
    private String aiConversationId;  // Links to AiConversation table
    
    // Status
    @Enumerated(EnumType.STRING)
    private ConversationStatus status;  // ACTIVE, ARCHIVED, RESOLVED
    
    // Metadata
    private String subject;         // For support tickets
    private String lastMessagePreview;
    private Instant lastMessageAt;
    
    // Unread tracking
    private Integer creatorUnreadCount;
    private Integer brandUnreadCount;
    
    // Timestamps
    private Instant createdAt;
    private Instant updatedAt;
}

public enum ConversationType {
    AI_ASSISTANT,
    BRAND_CONVERSATION,
    SUPPORT_TICKET,
    SYSTEM
}

public enum ConversationStatus {
    ACTIVE,
    ARCHIVED,
    RESOLVED,
    BLOCKED
}
```

### 3.2 Message Entity

```java
@Entity
@Table(name = "messages")
public class Message {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;
    
    // Sender
    @Enumerated(EnumType.STRING)
    private SenderType senderType;  // CREATOR, BRAND, AI, SYSTEM
    
    private String senderId;        // User ID or "meera" for AI
    
    // Content
    @Column(columnDefinition = "TEXT")
    private String content;
    
    @Enumerated(EnumType.STRING)
    private MessageType messageType;  // TEXT, IMAGE, FILE, RICH_CARD, ACTION
    
    // Rich content (for AI responses, cards, etc.)
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> richContent;
    // { "type": "rate_suggestion", "data": { "suggested": 25000, "reasoning": "..." } }
    
    // Attachments
    @Convert(converter = JsonListConverter.class)
    private List<MessageAttachment> attachments;
    
    // Reply context
    private String replyToMessageId;
    
    // Status
    @Enumerated(EnumType.STRING)
    private MessageStatus status;  // SENT, DELIVERED, READ, FAILED
    
    private Instant deliveredAt;
    private Instant readAt;
    
    // AI metadata (for AI messages)
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> aiMetadata;
    // { "model": "claude-3", "tokens": 150, "intent": "rate_advice" }
    
    private Instant sentAt;
}

public enum SenderType {
    CREATOR,
    BRAND,
    AI,
    SYSTEM
}

public enum MessageType {
    TEXT,
    IMAGE,
    FILE,
    RICH_CARD,
    ACTION,      // Action buttons
    TYPING       // Typing indicator
}
```

### 3.3 MessageAttachment

```java
@Embeddable
public class MessageAttachment {
    
    private String id;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private String url;
    private String thumbnailUrl;  // For images
    
    @Enumerated(EnumType.STRING)
    private AttachmentType type;  // IMAGE, VIDEO, DOCUMENT, MEDIA_KIT
}
```

### 3.4 AiConversation Entity (Meera Context)

```java
@Entity
@Table(name = "ai_conversations")
public class AiConversation {
    
    @Id
    private String id;
    
    private String creatorId;
    
    // Context for AI
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> creatorContext;
    // { "profile": {...}, "activeGampaigns": [...], "recentActivity": [...] }
    
    // Conversation history (last N messages for context)
    @Convert(converter = JsonListConverter.class)
    private List<AiMessage> messageHistory;
    
    // Current session
    private String currentIntent;  // "rate_advice", "campaign_help", "general"
    private String currentTopic;
    
    // Timestamps
    private Instant lastInteractionAt;
    private Instant createdAt;
}
```

---

## 4. API Endpoints

### 4.1 Conversations

```
GET /api/v1/creator/conversations
Query Parameters:
  type      - Filter by type (AI_ASSISTANT, BRAND_CONVERSATION)
  status    - Filter by status
  page, size

Response:
{
    "conversations": [
        {
            "id": "conv_xxx",
            "type": "BRAND_CONVERSATION",
            "participant": {
                "id": "brand_xxx",
                "name": "HealthKart",
                "logo": "https://...",
                "type": "BRAND"
            },
            "campaign": {
                "id": "camp_xxx",
                "title": "Summer Fitness Challenge"
            },
            "lastMessage": {
                "preview": "Great! Let's finalize the posting schedule...",
                "sentAt": "2026-07-07T14:30:00Z",
                "senderType": "BRAND"
            },
            "unreadCount": 2,
            "status": "ACTIVE"
        },
        {
            "id": "conv_yyy",
            "type": "AI_ASSISTANT",
            "participant": {
                "id": "meera",
                "name": "Meera",
                "avatar": "https://...meera-avatar.png",
                "type": "AI"
            },
            "lastMessage": {
                "preview": "I've analyzed your profile and have some suggestions...",
                "sentAt": "2026-07-06T10:00:00Z",
                "senderType": "AI"
            },
            "unreadCount": 0,
            "status": "ACTIVE"
        }
    ],
    "summary": {
        "totalUnread": 3,
        "activeConversations": 5
    }
}
```

### 4.2 Get Messages

```
GET /api/v1/creator/conversations/{conversationId}/messages
Query Parameters:
  before    - Cursor for pagination (message ID)
  limit     - Number of messages (default 50)

Response:
{
    "messages": [
        {
            "id": "msg_xxx",
            "senderType": "BRAND",
            "senderId": "brand_xxx",
            "senderName": "HealthKart",
            "senderAvatar": "https://...",
            "content": "Great! Let's finalize the posting schedule...",
            "messageType": "TEXT",
            "attachments": [],
            "status": "READ",
            "sentAt": "2026-07-07T14:30:00Z"
        },
        {
            "id": "msg_yyy",
            "senderType": "CREATOR",
            "senderId": "cr_xxx",
            "senderName": "You",
            "content": "I can post on Monday and Thursday next week.",
            "messageType": "TEXT",
            "attachments": [
                {
                    "id": "att_xxx",
                    "fileName": "content-calendar.pdf",
                    "mimeType": "application/pdf",
                    "fileSize": 245000,
                    "url": "https://..."
                }
            ],
            "status": "DELIVERED",
            "sentAt": "2026-07-07T14:25:00Z"
        }
    ],
    "hasMore": true,
    "nextCursor": "msg_zzz"
}
```

### 4.3 Send Message

```
POST /api/v1/creator/conversations/{conversationId}/messages
{
    "content": "I can post on Monday and Thursday next week.",
    "messageType": "TEXT",
    "attachments": ["att_xxx"],  // Pre-uploaded attachment IDs
    "replyToMessageId": null
}

Response:
{
    "id": "msg_xxx",
    "status": "SENT",
    "sentAt": "2026-07-07T14:35:00Z"
}
```

### 4.4 Upload Attachment

```
POST /api/v1/creator/conversations/{conversationId}/attachments
Content-Type: multipart/form-data

file: <binary>

Response:
{
    "id": "att_xxx",
    "fileName": "content-draft.mp4",
    "mimeType": "video/mp4",
    "fileSize": 15000000,
    "url": "https://...",
    "thumbnailUrl": "https://..."
}
```

### 4.5 Mark as Read

```
POST /api/v1/creator/conversations/{conversationId}/read
{
    "upToMessageId": "msg_xxx"
}

Response:
{
    "success": true,
    "markedAsRead": 3
}
```

### 4.6 Start AI Conversation

```
POST /api/v1/creator/ai/start
{
    "intent": "rate_advice",  // Optional, AI will detect
    "initialMessage": "What should I charge for Instagram Reels?"
}

Response:
{
    "conversationId": "conv_xxx",
    "message": {
        "id": "msg_xxx",
        "senderType": "AI",
        "content": "Based on your profile with 125K followers and 4.2% engagement...",
        "richContent": {
            "type": "rate_suggestion",
            "data": {
                "suggested": {
                    "instagramReel": 25000,
                    "instagramStory": 5000
                },
                "reasoning": "Your engagement rate is above average...",
                "comparisons": [...]
            }
        }
    }
}
```

### 4.7 Send AI Message

```
POST /api/v1/creator/ai/message
{
    "conversationId": "conv_xxx",
    "message": "What about YouTube Shorts?"
}

Response:
{
    "message": {
        "id": "msg_yyy",
        "senderType": "AI",
        "content": "For YouTube Shorts, considering your subscriber count...",
        "richContent": {...}
    }
}
```

---

## 5. Backend Implementation

### 5.1 Messaging Service

```java
@Service
public class MessagingService {
    
    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;
    private final WebSocketService wsService;
    private final NotificationService notificationService;
    
    @Transactional
    public Message sendMessage(String creatorId, String conversationId, SendMessageRequest request) {
        Conversation conversation = conversationRepo.findById(conversationId)
            .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        
        // Validate access
        if (!conversation.getCreatorId().equals(creatorId)) {
            throw new UnauthorizedException("Not your conversation");
        }
        
        // Validate conversation is active
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new InvalidStateException("Conversation is not active");
        }
        
        // Create message
        Message message = Message.builder()
            .id(Ulids.generate())
            .conversation(conversation)
            .senderType(SenderType.CREATOR)
            .senderId(creatorId)
            .content(request.getContent())
            .messageType(request.getMessageType())
            .attachments(processAttachments(request.getAttachments()))
            .replyToMessageId(request.getReplyToMessageId())
            .status(MessageStatus.SENT)
            .sentAt(Instant.now())
            .build();
        
        messageRepo.save(message);
        
        // Update conversation
        conversation.setLastMessagePreview(truncate(request.getContent(), 100));
        conversation.setLastMessageAt(Instant.now());
        conversation.setBrandUnreadCount(conversation.getBrandUnreadCount() + 1);
        conversationRepo.save(conversation);
        
        // Send real-time update via WebSocket
        wsService.sendToUser(conversation.getBrandId(), 
            new MessageEvent("NEW_MESSAGE", message));
        
        // Send push notification if recipient offline
        notificationService.notifyNewMessage(conversation.getBrandId(), message);
        
        return message;
    }
    
    public Page<Message> getMessages(String creatorId, String conversationId, 
                                      String beforeCursor, int limit) {
        Conversation conversation = conversationRepo.findById(conversationId)
            .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        
        // Validate access
        if (!conversation.getCreatorId().equals(creatorId)) {
            throw new UnauthorizedException("Not your conversation");
        }
        
        // Fetch messages
        Instant before = beforeCursor != null 
            ? messageRepo.findById(beforeCursor).map(Message::getSentAt).orElse(Instant.now())
            : Instant.now();
        
        return messageRepo.findByConversationIdAndSentAtBeforeOrderBySentAtDesc(
            conversationId, before, PageRequest.of(0, limit)
        );
    }
    
    @Transactional
    public void markAsRead(String creatorId, String conversationId, String upToMessageId) {
        Conversation conversation = conversationRepo.findById(conversationId)
            .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        
        if (!conversation.getCreatorId().equals(creatorId)) {
            throw new UnauthorizedException("Not your conversation");
        }
        
        // Mark messages as read
        Message upToMessage = messageRepo.findById(upToMessageId).orElseThrow();
        
        messageRepo.markAsRead(
            conversationId,
            SenderType.BRAND,  // Mark brand messages as read
            upToMessage.getSentAt()
        );
        
        // Reset unread count
        conversation.setCreatorUnreadCount(0);
        conversationRepo.save(conversation);
        
        // Notify sender of read receipt
        wsService.sendToUser(conversation.getBrandId(),
            new ReadReceiptEvent(conversationId, upToMessageId));
    }
}
```

### 5.2 AI Chat Service (Meera)

```java
@Service
public class AiChatService {
    
    private final AiConversationRepository aiConvoRepo;
    private final AnthropicClient anthropicClient;
    private final CreatorContextService contextService;
    
    @Transactional
    public AiChatResponse chat(String creatorId, String conversationId, String userMessage) {
        // Get or create AI conversation
        AiConversation aiConvo = aiConvoRepo.findById(conversationId)
            .orElseGet(() -> createNewAiConversation(creatorId));
        
        // Build context
        Map<String, Object> creatorContext = contextService.buildCreatorContext(creatorId);
        aiConvo.setCreatorContext(creatorContext);
        
        // Detect intent
        String intent = detectIntent(userMessage);
        aiConvo.setCurrentIntent(intent);
        
        // Build system prompt
        String systemPrompt = buildMeeraSystemPrompt(creatorContext, intent);
        
        // Build message history
        List<AnthropicMessage> messages = buildMessageHistory(aiConvo, userMessage);
        
        // Call Claude API
        AnthropicResponse response = anthropicClient.createMessage(
            CreateMessageRequest.builder()
                .model("claude-3-5-sonnet-20241022")
                .maxTokens(1024)
                .system(systemPrompt)
                .messages(messages)
                .build()
        );
        
        String aiResponse = response.getContent().get(0).getText();
        
        // Parse for rich content
        RichContent richContent = parseForRichContent(aiResponse, intent);
        
        // Update conversation history
        aiConvo.getMessageHistory().add(new AiMessage("user", userMessage));
        aiConvo.getMessageHistory().add(new AiMessage("assistant", aiResponse));
        aiConvo.setLastInteractionAt(Instant.now());
        
        // Keep only last 20 messages for context
        if (aiConvo.getMessageHistory().size() > 20) {
            aiConvo.setMessageHistory(
                aiConvo.getMessageHistory().subList(
                    aiConvo.getMessageHistory().size() - 20,
                    aiConvo.getMessageHistory().size()
                )
            );
        }
        
        aiConvoRepo.save(aiConvo);
        
        // Save to main conversation
        saveAiMessageToConversation(aiConvo, aiResponse, richContent);
        
        return AiChatResponse.builder()
            .conversationId(aiConvo.getId())
            .message(aiResponse)
            .richContent(richContent)
            .intent(intent)
            .build();
    }
    
    private String buildMeeraSystemPrompt(Map<String, Object> context, String intent) {
        return String.format("""
            You are Meera, an AI assistant for creators on Influora, an influencer marketing platform.
            
            Your role is to help creators:
            - Set competitive rates for their content
            - Find and apply to relevant campaigns
            - Optimize their profiles
            - Grow their audience and engagement
            - Navigate the platform
            
            Creator Profile:
            %s
            
            Current Intent: %s
            
            Guidelines:
            - Be friendly, professional, and encouraging
            - Give specific, actionable advice
            - Use data when available (their stats, market rates)
            - Keep responses concise but helpful
            - If discussing rates, always explain your reasoning
            - Encourage creators to value their work appropriately
            
            For rate suggestions, consider:
            - Follower count and engagement rate
            - Niche (fitness, fashion, tech, etc.)
            - Content type (reels, posts, stories)
            - Market rates in India
            """, formatContext(context), intent);
    }
    
    private RichContent parseForRichContent(String response, String intent) {
        // Detect if response should include rich content
        if (intent.equals("rate_advice")) {
            // Extract rate suggestions and format as card
            return extractRateSuggestionCard(response);
        }
        if (intent.equals("campaign_match")) {
            // Format campaign suggestions as cards
            return extractCampaignCards(response);
        }
        return null;
    }
}
```

### 5.3 WebSocket Handler

```java
@ServerEndpoint("/ws/chat")
@Component
public class ChatWebSocketHandler {
    
    private static Map<String, Session> userSessions = new ConcurrentHashMap<>();
    
    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) {
        String userId = validateTokenAndGetUserId(token);
        userSessions.put(userId, session);
    }
    
    @OnClose
    public void onClose(Session session, @PathParam("token") String token) {
        String userId = validateTokenAndGetUserId(token);
        userSessions.remove(userId);
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        // Handle typing indicators, read receipts, etc.
        WebSocketMessage wsMessage = parseMessage(message);
        
        switch (wsMessage.getType()) {
            case "TYPING" -> handleTypingIndicator(wsMessage);
            case "READ" -> handleReadReceipt(wsMessage);
        }
    }
    
    public void sendToUser(String userId, Object payload) {
        Session session = userSessions.get(userId);
        if (session != null && session.isOpen()) {
            session.getAsyncRemote().sendText(toJson(payload));
        }
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Inbox Page

```tsx
export function InboxPage() {
  const { data: conversations, isLoading } = useConversations();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  
  const selectedConversation = conversations?.find(c => c.id === selectedId);
  
  return (
    <div className="flex h-[calc(100vh-4rem)]">
      {/* Conversation List */}
      <div className="w-80 border-r flex flex-col">
        <div className="p-4 border-b">
          <h1 className="text-lg font-semibold">Messages</h1>
        </div>
        
        <div className="flex-1 overflow-y-auto">
          {isLoading ? (
            <div className="p-4 space-y-4">
              {Array(5).fill(0).map((_, i) => <Skeleton key={i} className="h-16" />)}
            </div>
          ) : (
            conversations?.map((conv) => (
              <ConversationItem
                key={conv.id}
                conversation={conv}
                isSelected={conv.id === selectedId}
                onClick={() => setSelectedId(conv.id)}
              />
            ))
          )}
        </div>
      </div>
      
      {/* Chat Area */}
      <div className="flex-1 flex flex-col">
        {selectedConversation ? (
          <ChatView conversation={selectedConversation} />
        ) : (
          <div className="flex-1 flex items-center justify-center text-muted-foreground">
            Select a conversation to start chatting
          </div>
        )}
      </div>
    </div>
  );
}
```

### 6.2 Conversation Item

```tsx
interface ConversationItemProps {
  conversation: Conversation;
  isSelected: boolean;
  onClick: () => void;
}

export function ConversationItem({ conversation, isSelected, onClick }: ConversationItemProps) {
  const isAI = conversation.type === 'AI_ASSISTANT';
  
  return (
    <button
      onClick={onClick}
      className={cn(
        "w-full p-4 text-left hover:bg-muted/50 transition-colors border-b",
        isSelected && "bg-muted"
      )}
    >
      <div className="flex gap-3">
        <div className="relative">
          <Avatar>
            <AvatarImage src={conversation.participant.logo || conversation.participant.avatar} />
            <AvatarFallback>
              {isAI ? <Bot className="h-4 w-4" /> : conversation.participant.name[0]}
            </AvatarFallback>
          </Avatar>
          
          {isAI && (
            <div className="absolute -bottom-1 -right-1 h-4 w-4 bg-primary rounded-full flex items-center justify-center">
              <Sparkles className="h-2.5 w-2.5 text-white" />
            </div>
          )}
        </div>
        
        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between">
            <span className="font-medium truncate">
              {conversation.participant.name}
            </span>
            <span className="text-xs text-muted-foreground">
              {formatRelativeTime(conversation.lastMessage.sentAt)}
            </span>
          </div>
          
          {conversation.campaign && (
            <p className="text-xs text-muted-foreground truncate">
              {conversation.campaign.title}
            </p>
          )}
          
          <p className="text-sm text-muted-foreground truncate">
            {conversation.lastMessage.senderType === 'CREATOR' && 'You: '}
            {conversation.lastMessage.preview}
          </p>
        </div>
        
        {conversation.unreadCount > 0 && (
          <Badge className="ml-2">{conversation.unreadCount}</Badge>
        )}
      </div>
    </button>
  );
}
```

### 6.3 Chat View

```tsx
interface ChatViewProps {
  conversation: Conversation;
}

export function ChatView({ conversation }: ChatViewProps) {
  const [message, setMessage] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  
  const { data: messages, fetchNextPage, hasNextPage } = useInfiniteQuery({
    queryKey: ['messages', conversation.id],
    queryFn: ({ pageParam }) => fetchMessages(conversation.id, pageParam),
    getNextPageParam: (lastPage) => lastPage.hasMore ? lastPage.nextCursor : undefined,
  });
  
  const { mutate: sendMessage, isLoading: sending } = useMutation({
    mutationFn: (content: string) => sendMessageApi(conversation.id, { content }),
    onSuccess: () => {
      setMessage('');
      queryClient.invalidateQueries(['messages', conversation.id]);
    },
  });
  
  // WebSocket for real-time updates
  useWebSocket(`/ws/chat?conversationId=${conversation.id}`, {
    onMessage: (event) => {
      const data = JSON.parse(event.data);
      if (data.type === 'NEW_MESSAGE') {
        queryClient.invalidateQueries(['messages', conversation.id]);
      }
      if (data.type === 'TYPING') {
        setIsTyping(true);
        setTimeout(() => setIsTyping(false), 3000);
      }
    },
  });
  
  // Scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);
  
  const isAI = conversation.type === 'AI_ASSISTANT';
  
  return (
    <>
      {/* Header */}
      <div className="p-4 border-b flex items-center gap-3">
        <Avatar>
          <AvatarImage src={conversation.participant.logo} />
          <AvatarFallback>
            {isAI ? <Bot className="h-4 w-4" /> : conversation.participant.name[0]}
          </AvatarFallback>
        </Avatar>
        
        <div>
          <h2 className="font-medium">{conversation.participant.name}</h2>
          {conversation.campaign && (
            <p className="text-sm text-muted-foreground">
              {conversation.campaign.title}
            </p>
          )}
        </div>
        
        {!isAI && (
          <div className="ml-auto">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon">
                  <MoreVertical className="h-4 w-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuItem>View Profile</DropdownMenuItem>
                <DropdownMenuItem>View Campaign</DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem className="text-destructive">
                  Report Issue
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        )}
      </div>
      
      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {/* Load more button */}
        {hasNextPage && (
          <div className="text-center">
            <Button variant="ghost" size="sm" onClick={() => fetchNextPage()}>
              Load earlier messages
            </Button>
          </div>
        )}
        
        {messages?.pages.flatMap(page => page.messages).reverse().map((msg) => (
          <MessageBubble key={msg.id} message={msg} isOwn={msg.senderType === 'CREATOR'} />
        ))}
        
        {/* Typing indicator */}
        {isTyping && (
          <div className="flex items-center gap-2 text-muted-foreground">
            <Avatar className="h-6 w-6">
              <AvatarImage src={conversation.participant.logo} />
            </Avatar>
            <div className="flex gap-1">
              <span className="w-2 h-2 bg-muted-foreground rounded-full animate-bounce" />
              <span className="w-2 h-2 bg-muted-foreground rounded-full animate-bounce delay-100" />
              <span className="w-2 h-2 bg-muted-foreground rounded-full animate-bounce delay-200" />
            </div>
          </div>
        )}
        
        <div ref={messagesEndRef} />
      </div>
      
      {/* Input */}
      <div className="p-4 border-t">
        <div className="flex gap-2">
          <AttachmentButton conversationId={conversation.id} />
          
          <Input
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                if (message.trim()) sendMessage(message.trim());
              }
            }}
            placeholder={isAI ? "Ask Meera anything..." : "Type a message..."}
          />
          
          <Button
            onClick={() => sendMessage(message.trim())}
            disabled={!message.trim() || sending}
          >
            {sending ? <Spinner /> : <Send className="h-4 w-4" />}
          </Button>
        </div>
      </div>
    </>
  );
}
```

### 6.4 Message Bubble

```tsx
interface MessageBubbleProps {
  message: Message;
  isOwn: boolean;
}

export function MessageBubble({ message, isOwn }: MessageBubbleProps) {
  const isAI = message.senderType === 'AI';
  
  return (
    <div className={cn(
      "flex gap-2",
      isOwn && "flex-row-reverse"
    )}>
      {!isOwn && (
        <Avatar className="h-8 w-8">
          <AvatarImage src={message.senderAvatar} />
          <AvatarFallback>
            {isAI ? <Bot className="h-4 w-4" /> : message.senderName[0]}
          </AvatarFallback>
        </Avatar>
      )}
      
      <div className={cn(
        "max-w-[70%] space-y-1",
        isOwn && "items-end"
      )}>
        {/* Message Content */}
        <div className={cn(
          "rounded-2xl px-4 py-2",
          isOwn 
            ? "bg-primary text-primary-foreground rounded-br-none"
            : isAI
              ? "bg-gradient-to-r from-purple-100 to-blue-100 rounded-bl-none"
              : "bg-muted rounded-bl-none"
        )}>
          <p className="whitespace-pre-wrap">{message.content}</p>
        </div>
        
        {/* Rich Content (AI responses) */}
        {message.richContent && (
          <RichContentCard content={message.richContent} />
        )}
        
        {/* Attachments */}
        {message.attachments?.length > 0 && (
          <div className="space-y-2">
            {message.attachments.map((att) => (
              <AttachmentPreview key={att.id} attachment={att} />
            ))}
          </div>
        )}
        
        {/* Timestamp and status */}
        <div className={cn(
          "flex items-center gap-1 text-xs text-muted-foreground",
          isOwn && "justify-end"
        )}>
          <span>{formatTime(message.sentAt)}</span>
          {isOwn && (
            <MessageStatus status={message.status} />
          )}
        </div>
      </div>
    </div>
  );
}
```

### 6.5 Rich Content Card (AI Suggestions)

```tsx
interface RichContentCardProps {
  content: RichContent;
}

export function RichContentCard({ content }: RichContentCardProps) {
  if (content.type === 'rate_suggestion') {
    return (
      <Card className="mt-2">
        <CardHeader className="pb-2">
          <CardTitle className="text-sm flex items-center gap-2">
            <DollarSign className="h-4 w-4 text-green-500" />
            Suggested Rates
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {Object.entries(content.data.suggested).map(([type, rate]) => (
            <div key={type} className="flex justify-between">
              <span className="text-sm">{formatDeliverableType(type)}</span>
              <span className="font-medium">{formatCurrency(rate as number)}</span>
            </div>
          ))}
          
          <Separator />
          
          <p className="text-xs text-muted-foreground">
            {content.data.reasoning}
          </p>
          
          <Button size="sm" variant="outline" className="w-full">
            Update My Rates
          </Button>
        </CardContent>
      </Card>
    );
  }
  
  if (content.type === 'campaign_match') {
    return (
      <div className="mt-2 space-y-2">
        <p className="text-sm font-medium">Matching Campaigns</p>
        {content.data.campaigns.map((campaign: any) => (
          <Card key={campaign.id} className="p-3">
            <div className="flex items-center gap-3">
              <Avatar className="h-10 w-10">
                <AvatarImage src={campaign.brand.logo} />
              </Avatar>
              <div>
                <p className="font-medium text-sm">{campaign.title}</p>
                <p className="text-xs text-muted-foreground">{campaign.brand.name}</p>
              </div>
              <Badge className="ml-auto">{campaign.matchScore}% match</Badge>
            </div>
          </Card>
        ))}
      </div>
    );
  }
  
  return null;
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 Message Security
- **Encryption:** Messages encrypted in transit (TLS 1.3)
- **Access Control:** Only conversation participants can read messages
- **Rate Limiting:** Max 60 messages per minute per user

### 7.2 File Security
- **Validation:** Validate file types and sizes
- **Scanning:** Virus scan all uploads
- **Storage:** Files stored encrypted in R2

### 7.3 AI Security
- **Context Isolation:** AI only sees authorized creator data
- **PII Protection:** Mask sensitive info in AI context
- **Rate Limiting:** Max 100 AI messages per hour per user

### 7.4 WebSocket Security
- **Authentication:** Token-based WS authentication
- **Timeout:** Auto-disconnect after 30 min inactivity
- **Validation:** Validate all WS messages

---

## 8. Test Cases (Kavya)

```java
// Messaging Tests
@Test void shouldSendMessage()
@Test void shouldDeliverMessageViaWebSocket()
@Test void shouldMarkMessagesAsRead()
@Test void shouldUploadAttachment()
@Test void shouldPaginateMessages()

// AI Chat Tests
@Test void shouldRespondToRateQuery()
@Test void shouldMaintainConversationContext()
@Test void shouldDetectUserIntent()
@Test void shouldGenerateRichContent()
@Test void shouldLimitAiTokenUsage()

// Security Tests
@Test void shouldBlockUnauthorizedAccess()
@Test void shouldRateLimitMessages()
@Test void shouldValidateAttachments()
@Test void shouldEncryptMessages()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/conversations` | GET | JWT | List conversations |
| `/creator/conversations/{id}/messages` | GET | JWT | Get messages |
| `/creator/conversations/{id}/messages` | POST | JWT | Send message |
| `/creator/conversations/{id}/attachments` | POST | JWT | Upload attachment |
| `/creator/conversations/{id}/read` | POST | JWT | Mark as read |
| `/creator/ai/start` | POST | JWT | Start AI conversation |
| `/creator/ai/message` | POST | JWT | Send AI message |
