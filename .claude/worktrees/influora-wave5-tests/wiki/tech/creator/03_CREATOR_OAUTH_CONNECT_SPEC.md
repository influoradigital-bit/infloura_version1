# Creator OAuth Connect Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. OAuth Connection Flow Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       PLATFORM CONNECTION FLOW                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ Creator  │ →  │ Influora │ →  │ Platform │ →  │ Callback │              │
│  │ Clicks   │    │ Redirect │    │ OAuth    │    │ Handler  │              │
│  │ Connect  │    │ to OAuth │    │ Consent  │    │ + Import │              │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘              │
│                                                                              │
│       ↓                                                                      │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ Store    │ →  │ Fetch    │ →  │ Calculate│ →  │ Display  │              │
│  │ Encrypted│    │ Profile  │    │ Scores   │    │ Connected│              │
│  │ Tokens   │    │ + Stats  │    │          │    │ Status   │              │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Platform-Specific OAuth Flows

### 2.1 Instagram (Meta Business API)

```
┌─────────────────────────────────────────────────────────────────┐
│  Connect Instagram                                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  📸 Connect your Instagram account to:                          │
│                                                                  │
│  • Verify your follower count                                   │
│  • Import engagement metrics                                    │
│  • Show authentic stats to brands                               │
│  • Auto-update your profile                                     │
│                                                                  │
│  ⚠️ We only request read access. We cannot:                    │
│  - Post on your behalf                                          │
│  - Access your DMs                                              │
│  - Modify your profile                                          │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  📸 Connect Instagram Business Account                  │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ℹ️ Requires Instagram Business or Creator account              │
│     linked to a Facebook Page                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**OAuth Scopes Required:**
```
instagram_basic
instagram_manage_insights
pages_show_list
pages_read_engagement
business_management
```

### 2.2 YouTube (Google OAuth)

```
┌─────────────────────────────────────────────────────────────────┐
│  Connect YouTube                                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ▶️ Connect your YouTube channel to:                            │
│                                                                  │
│  • Verify your subscriber count                                 │
│  • Import video performance metrics                             │
│  • Show authentic stats to brands                               │
│  • Auto-update your profile                                     │
│                                                                  │
│  ⚠️ We only request read access to your channel.               │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  ▶️ Connect YouTube Channel                              │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ℹ️ You'll be redirected to Google to authorize                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**OAuth Scopes Required:**
```
https://www.googleapis.com/auth/youtube.readonly
https://www.googleapis.com/auth/yt-analytics.readonly
```

### 2.3 Facebook Page

```
┌─────────────────────────────────────────────────────────────────┐
│  Connect Facebook Page                                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  📘 Connect your Facebook Page to:                              │
│                                                                  │
│  • Verify your page followers                                   │
│  • Import post engagement metrics                               │
│  • Show authentic stats to brands                               │
│                                                                  │
│  ⚠️ Only creator/business pages can be connected.              │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  📘 Connect Facebook Page                                │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**OAuth Scopes Required:**
```
pages_show_list
pages_read_engagement
pages_read_user_content
read_insights
```

---

## 3. Database Schema

### 3.1 SocialConnection Entity

```java
@Entity
@Table(name = "social_connections")
public class SocialConnection {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    @Enumerated(EnumType.STRING)
    private Platform platform;  // INSTAGRAM, YOUTUBE, FACEBOOK
    
    // Platform identifiers
    private String platformUserId;      // Instagram user ID, YouTube channel ID
    private String platformUsername;    // @handle or channel name
    private String platformDisplayName; // Display name on platform
    private String profilePictureUrl;   // Platform profile picture
    
    // Encrypted tokens (AES-256-GCM)
    @Column(columnDefinition = "BYTEA")
    private byte[] accessTokenEncrypted;
    
    @Column(columnDefinition = "BYTEA")
    private byte[] refreshTokenEncrypted;
    
    private Instant tokenExpiresAt;
    
    // Connection status
    @Enumerated(EnumType.STRING)
    private ConnectionStatus status;  // ACTIVE, EXPIRED, REVOKED, ERROR
    
    private String lastErrorMessage;
    private Instant lastErrorAt;
    
    // Timestamps
    private Instant connectedAt;
    private Instant lastSyncedAt;
    private Instant disconnectedAt;
    
    // Audit
    private String connectedIp;
    private String connectedUserAgent;
}

public enum Platform {
    INSTAGRAM,
    YOUTUBE,
    FACEBOOK,
    TIKTOK  // Future
}

public enum ConnectionStatus {
    ACTIVE,
    EXPIRED,      // Token expired, needs re-auth
    REVOKED,      // User revoked access on platform
    ERROR,        // API error during sync
    DISCONNECTED  // User disconnected in Influora
}
```

### 3.2 SocialStats Entity (Cached Stats)

```java
@Entity
@Table(name = "social_stats")
public class SocialStats {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "connection_id")
    private SocialConnection connection;
    
    // Follower metrics
    private Integer followers;
    private Integer following;
    
    // Engagement metrics
    private Integer totalPosts;
    private Double avgLikesPerPost;
    private Double avgCommentsPerPost;
    private Double engagementRate;  // (likes + comments) / followers * 100
    
    // Growth metrics
    private Integer followerGrowth7d;
    private Integer followerGrowth30d;
    private Double growthRate7d;
    private Double growthRate30d;
    
    // Platform-specific (stored as JSON)
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> platformSpecific;
    // Instagram: reels_plays, stories_views, reach
    // YouTube: subscribers, total_views, avg_watch_time
    // Facebook: page_likes, post_reach
    
    // Demographics (if available from platform)
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> audienceDemographics;
    // { "age_ranges": {"18-24": 35, "25-34": 45}, "genders": {"M": 40, "F": 60}, "top_cities": [...] }
    
    private Instant fetchedAt;
    private Instant nextScheduledSync;
}
```

### 3.3 OAuthState Entity (CSRF Protection)

```java
@Entity
@Table(name = "oauth_states")
public class OAuthState {
    
    @Id
    private String state;  // Random 32-byte hex string
    
    private String creatorId;
    private Platform platform;
    private String redirectUri;
    private String codeVerifier;  // For PKCE
    
    private Instant createdAt;
    private Instant expiresAt;  // 10 minutes
    
    private Boolean used;
}
```

---

## 4. API Endpoints

### 4.1 Initiate OAuth Connection

```
POST /api/v1/creator/connect/{platform}
→ { "platform": "instagram" }

Response:
{
    "authorization_url": "https://www.facebook.com/v18.0/dialog/oauth?client_id=xxx&redirect_uri=xxx&state=xxx&scope=xxx",
    "state": "abc123...",
    "expires_in": 600
}
```

### 4.2 OAuth Callback Handler

```
GET /api/v1/oauth/callback/{platform}
Query params: code, state, error, error_description

Success → Redirect to: /creator/settings/accounts?connected=instagram
Error → Redirect to: /creator/settings/accounts?error=access_denied
```

### 4.3 Connection Management

```
GET /api/v1/creator/connections
→ List all connected accounts

Response:
{
    "connections": [
        {
            "id": "conn_xxx",
            "platform": "INSTAGRAM",
            "username": "@riya_fitness",
            "displayName": "Riya Sharma",
            "profilePicture": "https://...",
            "status": "ACTIVE",
            "stats": {
                "followers": 125000,
                "engagementRate": 4.2,
                "lastSynced": "2026-07-07T10:30:00Z"
            },
            "connectedAt": "2026-06-15T14:22:00Z"
        }
    ]
}

DELETE /api/v1/creator/connections/{connectionId}
→ Disconnect account

POST /api/v1/creator/connections/{connectionId}/refresh
→ Manually trigger stats refresh
```

### 4.4 Stats Sync

```
POST /api/v1/creator/connections/{connectionId}/sync
→ Trigger manual sync

GET /api/v1/creator/connections/{connectionId}/stats
→ Get detailed stats for a connection

Response:
{
    "followers": 125000,
    "following": 856,
    "engagementRate": 4.2,
    "avgLikes": 5250,
    "avgComments": 128,
    "recentGrowth": {
        "7d": { "followers": 1250, "rate": 1.0 },
        "30d": { "followers": 4800, "rate": 4.0 }
    },
    "demographics": {
        "ageRanges": { "18-24": 35, "25-34": 45, "35-44": 15, "45+": 5 },
        "genders": { "female": 65, "male": 35 },
        "topCities": ["Mumbai", "Delhi", "Bangalore"]
    },
    "lastUpdated": "2026-07-07T10:30:00Z"
}
```

---

## 5. Backend Implementation

### 5.1 OAuth Service

```java
@Service
public class OAuthService {
    
    private final OAuthStateRepository stateRepo;
    private final SocialConnectionRepository connectionRepo;
    private final EncryptionService encryptionService;
    private final InstagramApiClient instagramClient;
    private final YouTubeApiClient youtubeClient;
    
    public String initiateOAuth(String creatorId, Platform platform) {
        // Generate PKCE code verifier and challenge
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        
        // Generate state for CSRF protection
        String state = generateSecureState();
        
        // Store state
        var oauthState = OAuthState.builder()
            .state(state)
            .creatorId(creatorId)
            .platform(platform)
            .codeVerifier(codeVerifier)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .used(false)
            .build();
        stateRepo.save(oauthState);
        
        // Build authorization URL
        return buildAuthorizationUrl(platform, state, codeChallenge);
    }
    
    @Transactional
    public SocialConnection handleCallback(String code, String state) {
        // 1. Validate state (CSRF protection)
        OAuthState oauthState = stateRepo.findByStateAndUsedFalse(state)
            .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new InvalidOAuthStateException());
        
        // Mark state as used
        oauthState.setUsed(true);
        stateRepo.save(oauthState);
        
        // 2. Exchange code for tokens
        TokenResponse tokens = exchangeCodeForTokens(
            oauthState.getPlatform(),
            code,
            oauthState.getCodeVerifier()
        );
        
        // 3. Fetch user profile from platform
        PlatformProfile profile = fetchPlatformProfile(
            oauthState.getPlatform(),
            tokens.getAccessToken()
        );
        
        // 4. Check if already connected (different account warning)
        checkExistingConnection(oauthState.getCreatorId(), oauthState.getPlatform(), profile);
        
        // 5. Store encrypted tokens
        SocialConnection connection = SocialConnection.builder()
            .id(Ulids.generate())
            .creatorId(oauthState.getCreatorId())
            .platform(oauthState.getPlatform())
            .platformUserId(profile.getId())
            .platformUsername(profile.getUsername())
            .platformDisplayName(profile.getDisplayName())
            .profilePictureUrl(profile.getProfilePicture())
            .accessTokenEncrypted(encryptionService.encrypt(tokens.getAccessToken()))
            .refreshTokenEncrypted(encryptionService.encrypt(tokens.getRefreshToken()))
            .tokenExpiresAt(Instant.now().plusSeconds(tokens.getExpiresIn()))
            .status(ConnectionStatus.ACTIVE)
            .connectedAt(Instant.now())
            .build();
        
        connectionRepo.save(connection);
        
        // 6. Trigger initial stats sync
        statsSyncService.scheduleImmediateSync(connection.getId());
        
        return connection;
    }
}
```

### 5.2 Token Encryption Service

```java
@Service
public class TokenEncryptionService {
    
    private final SecretKey encryptionKey;  // From AWS Secrets Manager
    
    public byte[] encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = generateSecureIv();
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, spec);
            
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            
            // Prepend IV to ciphertext
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            
            return result;
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt token", e);
        }
    }
    
    public String decrypt(byte[] encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            
            // Extract IV (first 12 bytes)
            byte[] iv = Arrays.copyOfRange(encrypted, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, 12, encrypted.length);
            
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, spec);
            
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt token", e);
        }
    }
}
```

### 5.3 Stats Sync Service

```java
@Service
public class StatsSyncService {
    
    @Scheduled(cron = "0 0 */6 * * *")  // Every 6 hours
    public void syncAllActiveConnections() {
        List<SocialConnection> activeConnections = connectionRepo
            .findByStatusAndNextScheduledSyncBefore(
                ConnectionStatus.ACTIVE,
                Instant.now()
            );
        
        for (SocialConnection conn : activeConnections) {
            try {
                syncConnection(conn);
            } catch (RateLimitException e) {
                // Backoff and reschedule
                scheduleRetry(conn, Duration.ofMinutes(30));
            } catch (TokenExpiredException e) {
                // Try to refresh token
                refreshToken(conn);
            } catch (Exception e) {
                log.error("Sync failed for connection {}", conn.getId(), e);
                markConnectionError(conn, e.getMessage());
            }
        }
    }
    
    public void syncConnection(SocialConnection conn) {
        String accessToken = encryptionService.decrypt(conn.getAccessTokenEncrypted());
        
        SocialStats stats = switch (conn.getPlatform()) {
            case INSTAGRAM -> fetchInstagramStats(accessToken, conn.getPlatformUserId());
            case YOUTUBE -> fetchYouTubeStats(accessToken, conn.getPlatformUserId());
            case FACEBOOK -> fetchFacebookStats(accessToken, conn.getPlatformUserId());
        };
        
        stats.setConnection(conn);
        stats.setFetchedAt(Instant.now());
        stats.setNextScheduledSync(Instant.now().plusHours(6));
        
        statsRepo.save(stats);
        
        // Update aggregated stats on creator profile
        updateCreatorProfileStats(conn.getCreatorId());
    }
}
```

### 5.4 Platform API Clients

```java
@Component
public class InstagramApiClient {
    
    private final WebClient webClient;
    private final RateLimiter rateLimiter;
    
    public InstagramProfile fetchProfile(String accessToken) {
        rateLimiter.acquire();
        
        return webClient.get()
            .uri("https://graph.facebook.com/v18.0/me?fields=id,username,name,profile_picture_url,followers_count,follows_count,media_count")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(InstagramProfile.class)
            .block();
    }
    
    public InstagramInsights fetchInsights(String accessToken, String userId) {
        rateLimiter.acquire();
        
        // Fetch media for engagement calculation
        var media = webClient.get()
            .uri("https://graph.facebook.com/v18.0/{userId}/media?fields=id,like_count,comments_count,timestamp&limit=25", userId)
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(MediaListResponse.class)
            .block();
        
        // Fetch audience demographics (requires instagram_manage_insights)
        var demographics = webClient.get()
            .uri("https://graph.facebook.com/v18.0/{userId}/insights?metric=audience_city,audience_country,audience_gender_age&period=lifetime", userId)
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(InsightsResponse.class)
            .block();
        
        return InstagramInsights.builder()
            .avgLikes(calculateAvgLikes(media))
            .avgComments(calculateAvgComments(media))
            .demographics(parseDemographics(demographics))
            .build();
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Connect Accounts Page

```tsx
export function ConnectAccountsPage() {
  const { data: connections, mutate } = useConnections();
  
  const platforms = [
    {
      id: 'instagram',
      name: 'Instagram',
      icon: Instagram,
      description: 'Connect your Instagram Business or Creator account',
      color: 'bg-gradient-to-r from-purple-500 to-pink-500',
    },
    {
      id: 'youtube',
      name: 'YouTube',
      icon: Youtube,
      description: 'Connect your YouTube channel',
      color: 'bg-red-500',
    },
    {
      id: 'facebook',
      name: 'Facebook Page',
      icon: Facebook,
      description: 'Connect your Facebook Creator or Business page',
      color: 'bg-blue-600',
    },
  ];
  
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Connected Accounts</h1>
        <p className="text-muted-foreground">
          Connect your social accounts to verify your audience and import metrics
        </p>
      </div>
      
      <div className="grid gap-4">
        {platforms.map((platform) => {
          const connection = connections?.find(c => c.platform === platform.id.toUpperCase());
          
          return (
            <PlatformConnectionCard
              key={platform.id}
              platform={platform}
              connection={connection}
              onConnect={() => initiateOAuth(platform.id)}
              onDisconnect={() => disconnectAccount(connection.id)}
              onRefresh={() => refreshStats(connection.id)}
            />
          );
        })}
      </div>
    </div>
  );
}
```

### 6.2 Platform Connection Card

```tsx
interface PlatformConnectionCardProps {
  platform: Platform;
  connection?: SocialConnection;
  onConnect: () => void;
  onDisconnect: () => void;
  onRefresh: () => void;
}

export function PlatformConnectionCard({
  platform,
  connection,
  onConnect,
  onDisconnect,
  onRefresh,
}: PlatformConnectionCardProps) {
  const isConnected = connection?.status === 'ACTIVE';
  const hasError = connection?.status === 'ERROR' || connection?.status === 'EXPIRED';
  
  return (
    <Card className={cn(
      "p-4",
      hasError && "border-destructive"
    )}>
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-4">
          {/* Platform Icon */}
          <div className={cn(
            "w-12 h-12 rounded-lg flex items-center justify-center text-white",
            platform.color
          )}>
            <platform.icon className="h-6 w-6" />
          </div>
          
          <div>
            <h3 className="font-semibold">{platform.name}</h3>
            {isConnected ? (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Avatar className="h-5 w-5">
                  <AvatarImage src={connection.profilePicture} />
                </Avatar>
                <span>@{connection.username}</span>
                <Check className="h-4 w-4 text-green-500" />
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">{platform.description}</p>
            )}
          </div>
        </div>
        
        {/* Actions */}
        <div className="flex items-center gap-2">
          {isConnected ? (
            <>
              {/* Stats Badge */}
              <div className="text-right mr-4">
                <p className="text-sm font-medium">
                  {formatNumber(connection.stats?.followers)} followers
                </p>
                <p className="text-xs text-muted-foreground">
                  {connection.stats?.engagementRate}% engagement
                </p>
              </div>
              
              <Button variant="ghost" size="sm" onClick={onRefresh}>
                <RefreshCw className="h-4 w-4" />
              </Button>
              
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm">
                    <MoreVertical className="h-4 w-4" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent>
                  <DropdownMenuItem onClick={onDisconnect} className="text-destructive">
                    <Unlink className="h-4 w-4 mr-2" />
                    Disconnect
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </>
          ) : (
            <Button onClick={onConnect}>
              Connect
            </Button>
          )}
        </div>
      </div>
      
      {/* Error State */}
      {hasError && (
        <Alert variant="destructive" className="mt-4">
          <AlertTriangle className="h-4 w-4" />
          <AlertDescription>
            {connection.status === 'EXPIRED' 
              ? 'Your connection has expired. Please reconnect.'
              : connection.lastErrorMessage || 'An error occurred. Please reconnect.'}
          </AlertDescription>
          <Button variant="outline" size="sm" className="mt-2" onClick={onConnect}>
            Reconnect
          </Button>
        </Alert>
      )}
      
      {/* Last Synced */}
      {isConnected && (
        <p className="text-xs text-muted-foreground mt-3">
          Last synced {formatRelativeTime(connection.stats?.lastSynced)}
        </p>
      )}
    </Card>
  );
}
```

### 6.3 OAuth Redirect Handler

```tsx
// app/oauth/callback/[platform]/page.tsx

export default function OAuthCallbackPage() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { platform } = useParams();
  
  useEffect(() => {
    const code = searchParams.get('code');
    const error = searchParams.get('error');
    const state = searchParams.get('state');
    
    if (error) {
      toast.error(`Failed to connect: ${searchParams.get('error_description') || error}`);
      router.push('/creator/settings/accounts?error=' + error);
      return;
    }
    
    // The actual token exchange happens on the backend
    // This page just shows a loading state while redirecting
    
  }, [searchParams, router, platform]);
  
  return (
    <div className="min-h-screen flex items-center justify-center">
      <div className="text-center">
        <Spinner className="h-8 w-8 mx-auto mb-4" />
        <p className="text-muted-foreground">Connecting your account...</p>
      </div>
    </div>
  );
}
```

### 6.4 Connection Stats Detail Modal

```tsx
export function ConnectionStatsModal({
  connection,
  open,
  onClose,
}: {
  connection: SocialConnection;
  open: boolean;
  onClose: () => void;
}) {
  const { data: stats, isLoading } = useConnectionStats(connection.id);
  
  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Avatar>
              <AvatarImage src={connection.profilePicture} />
            </Avatar>
            {connection.displayName}
          </DialogTitle>
        </DialogHeader>
        
        {isLoading ? (
          <Skeleton className="h-64" />
        ) : (
          <div className="space-y-6">
            {/* Main Stats */}
            <div className="grid grid-cols-3 gap-4 text-center">
              <div>
                <p className="text-2xl font-bold">{formatNumber(stats.followers)}</p>
                <p className="text-sm text-muted-foreground">Followers</p>
              </div>
              <div>
                <p className="text-2xl font-bold">{stats.engagementRate}%</p>
                <p className="text-sm text-muted-foreground">Engagement</p>
              </div>
              <div>
                <p className="text-2xl font-bold">{formatNumber(stats.avgLikes)}</p>
                <p className="text-sm text-muted-foreground">Avg. Likes</p>
              </div>
            </div>
            
            {/* Growth */}
            <div>
              <h4 className="font-semibold mb-2">Recent Growth</h4>
              <div className="grid grid-cols-2 gap-4">
                <div className="p-3 bg-muted rounded-lg">
                  <p className="text-sm text-muted-foreground">Last 7 days</p>
                  <p className="text-lg font-semibold flex items-center gap-1">
                    {stats.recentGrowth?.['7d']?.rate > 0 ? (
                      <TrendingUp className="h-4 w-4 text-green-500" />
                    ) : (
                      <TrendingDown className="h-4 w-4 text-red-500" />
                    )}
                    {stats.recentGrowth?.['7d']?.rate}%
                  </p>
                  <p className="text-xs text-muted-foreground">
                    +{formatNumber(stats.recentGrowth?.['7d']?.followers)} followers
                  </p>
                </div>
                <div className="p-3 bg-muted rounded-lg">
                  <p className="text-sm text-muted-foreground">Last 30 days</p>
                  <p className="text-lg font-semibold flex items-center gap-1">
                    {stats.recentGrowth?.['30d']?.rate > 0 ? (
                      <TrendingUp className="h-4 w-4 text-green-500" />
                    ) : (
                      <TrendingDown className="h-4 w-4 text-red-500" />
                    )}
                    {stats.recentGrowth?.['30d']?.rate}%
                  </p>
                  <p className="text-xs text-muted-foreground">
                    +{formatNumber(stats.recentGrowth?.['30d']?.followers)} followers
                  </p>
                </div>
              </div>
            </div>
            
            {/* Demographics */}
            {stats.demographics && (
              <div>
                <h4 className="font-semibold mb-2">Audience Demographics</h4>
                <div className="space-y-3">
                  <div>
                    <p className="text-sm text-muted-foreground mb-1">Age Distribution</p>
                    <div className="flex gap-1">
                      {Object.entries(stats.demographics.ageRanges).map(([range, pct]) => (
                        <div key={range} className="flex-1">
                          <div 
                            className="bg-primary rounded-t h-12"
                            style={{ height: `${pct}%`, minHeight: '8px' }}
                          />
                          <p className="text-xs text-center mt-1">{range}</p>
                        </div>
                      ))}
                    </div>
                  </div>
                  
                  <div>
                    <p className="text-sm text-muted-foreground mb-1">Top Cities</p>
                    <div className="flex flex-wrap gap-2">
                      {stats.demographics.topCities?.map((city) => (
                        <Badge key={city} variant="secondary">{city}</Badge>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
```

---

## 7. Rate Limit Handling

### 7.1 Platform Rate Limits

| Platform | Endpoint | Limit | Window |
|----------|----------|-------|--------|
| Instagram (Meta) | Graph API | 200 calls | per user per hour |
| YouTube | Data API | 10,000 quota | per day |
| Facebook | Graph API | 200 calls | per user per hour |

### 7.2 Rate Limiter Implementation

```java
@Component
public class PlatformRateLimiter {
    
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    
    public void acquire(Platform platform, String userId) {
        String key = platform.name() + ":" + userId;
        
        RateLimiter limiter = rateLimiters.computeIfAbsent(key, k -> {
            return switch (platform) {
                case INSTAGRAM, FACEBOOK -> RateLimiter.create(200.0 / 3600); // 200/hour
                case YOUTUBE -> RateLimiter.create(100.0 / 86400);  // Conservative daily quota
            };
        });
        
        if (!limiter.tryAcquire(Duration.ofSeconds(30))) {
            throw new RateLimitException("Rate limit exceeded for " + platform);
        }
    }
}
```

### 7.3 Retry with Exponential Backoff

```java
@Service
public class ApiRetryHandler {
    
    private final RetryTemplate retryTemplate;
    
    @PostConstruct
    public void init() {
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(60000);
        
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3);
        
        retryTemplate = new RetryTemplate();
        retryTemplate.setBackOffPolicy(backoff);
        retryTemplate.setRetryPolicy(retryPolicy);
    }
    
    public <T> T executeWithRetry(Supplier<T> operation) {
        return retryTemplate.execute(context -> operation.get());
    }
}
```

---

## 8. Security Requirements (Kabir)

### 8.1 Token Security
- **Encryption:** All tokens encrypted with AES-256-GCM before storage
- **Key Management:** Encryption key stored in AWS Secrets Manager, rotated quarterly
- **IV:** Unique 12-byte IV per encryption, prepended to ciphertext
- **No Logging:** Never log tokens, even in debug mode

### 8.2 OAuth Security
- **PKCE:** Required for all OAuth flows (code_verifier + code_challenge)
- **State Parameter:** Random 32-byte hex string for CSRF protection
- **State Expiry:** OAuth states expire after 10 minutes
- **Single Use:** State can only be used once

### 8.3 Callback Validation
- **Exact Redirect URI:** Must match registered redirect URI exactly
- **HTTPS Only:** All redirect URIs must use HTTPS
- **No Open Redirects:** Redirect destination validated against allowlist

### 8.4 Token Refresh
- **Proactive Refresh:** Refresh tokens 5 minutes before expiry
- **Revocation Detection:** Handle platform revocation gracefully
- **Secure Storage:** Refresh tokens encrypted same as access tokens

### 8.5 Data Access
- **Minimum Scopes:** Only request scopes needed for functionality
- **Read Only:** Never request write/post permissions
- **Audit Trail:** Log all data imports with timestamps

---

## 9. Test Cases (Kavya)

### 9.1 OAuth Flow Tests

```java
@Test void shouldInitiateInstagramOAuth()
@Test void shouldInitiateYouTubeOAuth()
@Test void shouldInitiateFacebookOAuth()
@Test void shouldGenerateUniqueState()
@Test void shouldGeneratePKCEChallenge()
@Test void shouldRejectExpiredState()
@Test void shouldRejectReusedState()
@Test void shouldExchangeCodeForTokens()
@Test void shouldEncryptTokensBeforeStorage()
@Test void shouldDecryptTokensForApiCalls()
```

### 9.2 Connection Management Tests

```java
@Test void shouldListAllConnections()
@Test void shouldDisconnectAccount()
@Test void shouldHandleDuplicateConnection()
@Test void shouldRefreshExpiredToken()
@Test void shouldMarkConnectionAsError()
@Test void shouldHandlePlatformRevocation()
```

### 9.3 Stats Sync Tests

```java
@Test void shouldFetchInstagramStats()
@Test void shouldFetchYouTubeStats()
@Test void shouldFetchFacebookStats()
@Test void shouldCalculateEngagementRate()
@Test void shouldCalculateGrowthMetrics()
@Test void shouldHandleRateLimiting()
@Test void shouldRetryOnTransientError()
@Test void shouldUpdateCreatorProfileStats()
```

### 9.4 Security Tests

```java
@Test void shouldBlockInvalidState()
@Test void shouldBlockCsrfAttempt()
@Test void shouldRequirePKCE()
@Test void shouldNotLogTokens()
@Test void shouldEncryptAllTokens()
@Test void shouldValidateRedirectUri()
```

---

## 10. Error Handling

### 10.1 Error Codes

| Code | Description | User Message |
|------|-------------|--------------|
| `OAUTH_STATE_INVALID` | Invalid or expired state | Something went wrong. Please try again. |
| `OAUTH_CODE_INVALID` | Invalid authorization code | Authorization failed. Please try again. |
| `TOKEN_EXPIRED` | Access token expired | Your connection expired. Please reconnect. |
| `TOKEN_REVOKED` | User revoked access on platform | Please reconnect your account. |
| `RATE_LIMITED` | Platform rate limit exceeded | Please try again in a few minutes. |
| `PLATFORM_ERROR` | Platform API error | Unable to connect. Please try later. |
| `ACCOUNT_MISMATCH` | Different account connected | This account is different from before. |

### 10.2 Error Recovery Flow

```
┌────────────────────────────────────────────────────────────────┐
│  Error Detection → Categorization → Recovery Action            │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Token Expired    →  Try refresh token  →  If fail, prompt    │
│  Token Revoked    →  Mark disconnected  →  Prompt reconnect   │
│  Rate Limited     →  Exponential backoff →  Retry later       │
│  Platform Down    →  Circuit breaker    →  Notify user        │
│  Network Error    →  Retry 3x          →  Mark error          │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## 11. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/connect/{platform}` | POST | JWT | Initiate OAuth flow |
| `/oauth/callback/{platform}` | GET | None | OAuth callback handler |
| `/creator/connections` | GET | JWT | List all connections |
| `/creator/connections/{id}` | DELETE | JWT | Disconnect account |
| `/creator/connections/{id}/sync` | POST | JWT | Trigger manual sync |
| `/creator/connections/{id}/stats` | GET | JWT | Get detailed stats |
