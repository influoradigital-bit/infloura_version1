# Creator Profile Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Profile Setup Flow (Onboarding)

### Step-by-Step Onboarding

```
┌─────────────────────────────────────────────────────────────┐
│  Step 1 of 5: Basic Info                                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Profile Photo                                               │
│  ┌─────────┐                                                │
│  │  📷     │  [Upload Photo]                                │
│  └─────────┘                                                │
│                                                              │
│  Display Name                                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Riya Sharma                                         │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  Username (your unique URL)                                  │
│  influora.com/creator/ ┌───────────────────────────────┐    │
│                        │ riya_fitness                   │    │
│                        └───────────────────────────────┘    │
│  ✓ Available                                                │
│                                                              │
│                              [Continue →]                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Step 2 of 5: Your Niche                                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Select your primary categories (up to 3):                   │
│                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ Fitness  │ │ Fashion  │ │ Beauty   │ │ Food     │       │
│  │    ✓     │ │          │ │          │ │          │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ Travel   │ │ Tech     │ │ Lifestyle│ │ Parenting│       │
│  │          │ │          │ │    ✓     │ │          │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ Gaming   │ │ Education│ │ Finance  │ │ Health   │       │
│  │          │ │          │ │          │ │    ✓     │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│                                                              │
│                              [Continue →]                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Step 3 of 5: About You                                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Bio (shown to brands)                                       │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Certified fitness trainer helping busy              │    │
│  │ professionals stay healthy. 500K+ community of      │    │
│  │ fitness enthusiasts. Brand collaborations with      │    │
│  │ Nike, Puma, and HealthKart.                        │    │
│  └─────────────────────────────────────────────────────┘    │
│  142/500 characters                                          │
│                                                              │
│  Location                                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Mumbai, Maharashtra, India                          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  Languages                                                   │
│  [Hindi ✓] [English ✓] [Marathi] [+ Add]                    │
│                                                              │
│                              [Continue →]                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Step 4 of 5: Your Rates                                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Set your starting rates (you can negotiate per campaign):  │
│                                                              │
│  Instagram Post                                              │
│  ₹ ┌────────────────┐                                       │
│    │ 15,000         │                                       │
│    └────────────────┘                                       │
│                                                              │
│  Instagram Reel                                              │
│  ₹ ┌────────────────┐                                       │
│    │ 25,000         │                                       │
│    └────────────────┘                                       │
│                                                              │
│  Instagram Story                                             │
│  ₹ ┌────────────────┐                                       │
│    │ 5,000          │                                       │
│    └────────────────┘                                       │
│                                                              │
│  YouTube Video                                               │
│  ₹ ┌────────────────┐                                       │
│    │ 50,000         │                                       │
│    └────────────────┘                                       │
│                                                              │
│  💡 Based on your niche, creators charge ₹10K-30K/post     │
│                                                              │
│                              [Continue →]                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Step 5 of 5: Connect Your Accounts                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Connect at least one platform to verify your audience:     │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  📸 Instagram                                        │    │
│  │  Connect to import your followers and engagement     │    │
│  │                                   [Connect]          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  ▶️ YouTube                                          │    │
│  │  Connect to import your subscribers and views        │    │
│  │                                   [Connect]          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  📘 Facebook Page                                    │    │
│  │  Connect if you have a Facebook creator page         │    │
│  │                                   [Connect]          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│                              [Complete Setup →]              │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Database Schema

### 2.1 CreatorProfile Entity

```java
@Entity
@Table(name = "creator_profiles")
public class CreatorProfile {
    
    @Id
    private String id;
    
    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;
    
    // Basic Info
    private String displayName;
    private String username;  // Unique, URL-safe slug
    private String bio;
    private String profilePhotoUrl;
    private String coverPhotoUrl;
    
    // Location
    private String city;
    private String state;
    private String country;
    
    // Categories/Niche
    @Convert(converter = JsonListConverter.class)
    private List<String> categories;  // ["fitness", "lifestyle", "health"]
    
    // Languages
    @Convert(converter = JsonListConverter.class)
    private List<String> languages;  // ["en", "hi", "mr"]
    
    // Rates (in INR)
    private BigDecimal rateInstagramPost;
    private BigDecimal rateInstagramReel;
    private BigDecimal rateInstagramStory;
    private BigDecimal rateYoutubeVideo;
    private BigDecimal rateYoutubeShort;
    
    // Aggregated Stats (updated by polling jobs)
    private Integer totalFollowers;
    private Double avgEngagementRate;
    private Integer totalPosts;
    
    // Scores (calculated by scoring algorithms)
    private Double qualityScore;
    private Double authenticityScore;
    private Double brandSafetyScore;
    
    // Verification
    @Enumerated(EnumType.STRING)
    private VerificationStatus verificationStatus;
    private Instant verifiedAt;
    
    // Profile completeness
    private Boolean onboardingComplete;
    private Integer profileCompleteness;  // 0-100%
    
    // Discoverability
    private Boolean isDiscoverable;  // Show in brand search
    private Boolean acceptingCollabs;  // Open to new work
    
    // Timestamps
    private Instant createdAt;
    private Instant updatedAt;
}
```

### 2.2 CreatorCategory Enum

```java
public enum CreatorCategory {
    FITNESS("Fitness & Health"),
    FASHION("Fashion & Style"),
    BEAUTY("Beauty & Skincare"),
    FOOD("Food & Cooking"),
    TRAVEL("Travel & Adventure"),
    TECH("Technology & Gadgets"),
    LIFESTYLE("Lifestyle"),
    PARENTING("Parenting & Family"),
    GAMING("Gaming"),
    EDUCATION("Education & Learning"),
    FINANCE("Finance & Business"),
    HEALTH("Health & Wellness"),
    ENTERTAINMENT("Entertainment"),
    SPORTS("Sports"),
    ART("Art & Design"),
    MUSIC("Music"),
    COMEDY("Comedy & Humor"),
    MOTIVATION("Motivation & Self-Help");
    
    private final String displayName;
}
```

---

## 3. API Endpoints

### 3.1 Profile CRUD

```
GET /api/v1/creator/profile
→ Returns current creator's profile

PUT /api/v1/creator/profile
→ Update profile fields
{
    "displayName": "Riya Sharma",
    "bio": "...",
    "categories": ["fitness", "lifestyle"],
    "rateInstagramPost": 15000
}

POST /api/v1/creator/profile/photo
→ Upload profile photo (multipart/form-data)
→ Returns { url: "https://r2.../photo.jpg" }

POST /api/v1/creator/profile/cover
→ Upload cover photo
```

### 3.2 Username

```
GET /api/v1/creator/username/check?username=riya_fitness
→ { available: true }

PUT /api/v1/creator/profile/username
→ { username: "riya_fitness" }
→ Returns 409 if taken
```

### 3.3 Onboarding

```
GET /api/v1/creator/onboarding/status
→ {
    step: 3,
    totalSteps: 5,
    completedSteps: ["basic_info", "niche"],
    nextStep: "about"
}

POST /api/v1/creator/onboarding/step/{step}
→ Submit step data, advance to next
```

### 3.4 Public Profile

```
GET /api/v1/creators/{username}
→ Public profile view (for brands)
{
    displayName: "Riya Sharma",
    username: "riya_fitness",
    bio: "...",
    categories: ["fitness", "lifestyle"],
    stats: {
        instagramFollowers: 125000,
        engagementRate: 4.2,
        avgLikes: 5200
    },
    rates: {
        instagramPost: 15000,
        instagramReel: 25000
    },
    scores: {
        quality: 8.5,
        authenticity: 9.2,
        brandSafety: 9.8
    },
    recentWork: [...]  // Portfolio items
}
```

---

## 4. Frontend Components (Ananya)

### 4.1 Onboarding Wizard

```tsx
export function OnboardingWizard() {
  const [step, setStep] = useState(1);
  const [formData, setFormData] = useState<OnboardingData>({});
  
  const steps = [
    { id: 'basic', component: BasicInfoStep },
    { id: 'niche', component: NicheSelectionStep },
    { id: 'about', component: AboutYouStep },
    { id: 'rates', component: RatesStep },
    { id: 'connect', component: ConnectAccountsStep },
  ];
  
  const handleNext = async (data: Partial<OnboardingData>) => {
    const updated = { ...formData, ...data };
    setFormData(updated);
    
    // Save to backend
    await api.post(`/creator/onboarding/step/${steps[step - 1].id}`, data);
    
    if (step < steps.length) {
      setStep(step + 1);
    } else {
      // Complete onboarding
      router.push('/creator/dashboard');
    }
  };
  
  const CurrentStep = steps[step - 1].component;
  
  return (
    <div className="max-w-2xl mx-auto py-8">
      {/* Progress bar */}
      <div className="mb-8">
        <div className="flex justify-between mb-2">
          {steps.map((s, i) => (
            <div
              key={s.id}
              className={cn(
                "w-8 h-8 rounded-full flex items-center justify-center",
                i + 1 <= step ? "bg-primary text-white" : "bg-muted"
              )}
            >
              {i + 1}
            </div>
          ))}
        </div>
        <Progress value={(step / steps.length) * 100} />
      </div>
      
      {/* Current step */}
      <CurrentStep
        data={formData}
        onNext={handleNext}
        onBack={() => setStep(step - 1)}
      />
    </div>
  );
}
```

### 4.2 Category Selector

```tsx
export function CategorySelector({
  selected,
  onChange,
  max = 3,
}: {
  selected: string[];
  onChange: (categories: string[]) => void;
  max?: number;
}) {
  const categories = [
    { id: 'fitness', label: 'Fitness', icon: Dumbbell },
    { id: 'fashion', label: 'Fashion', icon: Shirt },
    { id: 'beauty', label: 'Beauty', icon: Sparkles },
    // ... more categories
  ];
  
  const toggle = (id: string) => {
    if (selected.includes(id)) {
      onChange(selected.filter(c => c !== id));
    } else if (selected.length < max) {
      onChange([...selected, id]);
    }
  };
  
  return (
    <div className="grid grid-cols-4 gap-3">
      {categories.map((cat) => {
        const Icon = cat.icon;
        const isSelected = selected.includes(cat.id);
        
        return (
          <button
            key={cat.id}
            onClick={() => toggle(cat.id)}
            className={cn(
              "p-4 rounded-lg border text-center transition-colors",
              isSelected
                ? "border-primary bg-primary/10"
                : "border-muted hover:border-primary/50"
            )}
          >
            <Icon className="h-6 w-6 mx-auto mb-2" />
            <span className="text-sm">{cat.label}</span>
            {isSelected && (
              <Check className="h-4 w-4 text-primary mx-auto mt-1" />
            )}
          </button>
        );
      })}
    </div>
  );
}
```

### 4.3 Rate Input

```tsx
export function RateInput({
  label,
  value,
  onChange,
  suggestion,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
  suggestion?: { min: number; max: number };
}) {
  return (
    <div className="space-y-2">
      <Label>{label}</Label>
      <div className="relative">
        <span className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
          ₹
        </span>
        <Input
          type="number"
          value={value}
          onChange={(e) => onChange(parseInt(e.target.value) || 0)}
          className="pl-8"
        />
      </div>
      {suggestion && (
        <p className="text-xs text-muted-foreground">
          Suggested range: ₹{suggestion.min.toLocaleString()} - ₹{suggestion.max.toLocaleString()}
        </p>
      )}
    </div>
  );
}
```

### 4.4 Profile Editor

```tsx
export function ProfileEditor() {
  const { data: profile, mutate } = useCreatorProfile();
  const [editing, setEditing] = useState(false);
  const [formData, setFormData] = useState(profile);
  
  const handleSave = async () => {
    await api.put('/creator/profile', formData);
    mutate();
    setEditing(false);
    toast.success('Profile updated');
  };
  
  return (
    <div className="space-y-6">
      {/* Profile Header */}
      <div className="relative">
        <div className="h-32 bg-gradient-to-r from-primary/20 to-primary/10 rounded-t-lg" />
        <div className="absolute -bottom-12 left-6">
          <Avatar className="h-24 w-24 border-4 border-background">
            <AvatarImage src={profile?.profilePhotoUrl} />
            <AvatarFallback>{profile?.displayName?.[0]}</AvatarFallback>
          </Avatar>
          {editing && (
            <Button size="sm" className="absolute bottom-0 right-0">
              <Camera className="h-4 w-4" />
            </Button>
          )}
        </div>
      </div>
      
      <div className="pt-14 px-6">
        {editing ? (
          <ProfileEditForm
            data={formData}
            onChange={setFormData}
            onSave={handleSave}
            onCancel={() => setEditing(false)}
          />
        ) : (
          <ProfileView
            profile={profile}
            onEdit={() => setEditing(true)}
          />
        )}
      </div>
    </div>
  );
}
```

---

## 5. Media Kit Generation

### 5.1 Auto-Generated Media Kit

```tsx
export function MediaKit({ creatorId }: { creatorId: string }) {
  const { data: profile } = useCreatorProfile(creatorId);
  const { data: stats } = useCreatorStats(creatorId);
  
  const downloadPdf = async () => {
    const response = await api.get(`/creator/${creatorId}/media-kit/pdf`, {
      responseType: 'blob',
    });
    downloadBlob(response, `${profile.username}-media-kit.pdf`);
  };
  
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-4">
          <Avatar className="h-16 w-16">
            <AvatarImage src={profile?.profilePhotoUrl} />
          </Avatar>
          <div>
            <h2 className="text-xl font-bold">{profile?.displayName}</h2>
            <p className="text-muted-foreground">@{profile?.username}</p>
          </div>
        </div>
      </CardHeader>
      
      <CardContent className="space-y-6">
        {/* Stats Grid */}
        <div className="grid grid-cols-3 gap-4 text-center">
          <div>
            <p className="text-2xl font-bold">
              {formatNumber(stats?.instagramFollowers)}
            </p>
            <p className="text-sm text-muted-foreground">Followers</p>
          </div>
          <div>
            <p className="text-2xl font-bold">{stats?.engagementRate}%</p>
            <p className="text-sm text-muted-foreground">Engagement</p>
          </div>
          <div>
            <p className="text-2xl font-bold">{formatNumber(stats?.avgLikes)}</p>
            <p className="text-sm text-muted-foreground">Avg. Likes</p>
          </div>
        </div>
        
        {/* Categories */}
        <div>
          <h3 className="font-semibold mb-2">Categories</h3>
          <div className="flex gap-2">
            {profile?.categories?.map((cat) => (
              <Badge key={cat} variant="secondary">{cat}</Badge>
            ))}
          </div>
        </div>
        
        {/* Rates */}
        <div>
          <h3 className="font-semibold mb-2">Collaboration Rates</h3>
          <div className="space-y-2">
            {profile?.rateInstagramPost && (
              <div className="flex justify-between">
                <span>Instagram Post</span>
                <span className="font-medium">₹{profile.rateInstagramPost.toLocaleString()}</span>
              </div>
            )}
            {/* More rates... */}
          </div>
        </div>
        
        <Button onClick={downloadPdf} className="w-full">
          <Download className="h-4 w-4 mr-2" />
          Download Media Kit PDF
        </Button>
      </CardContent>
    </Card>
  );
}
```

---

## 6. Profile Completeness Calculation

```java
@Service
public class ProfileCompletenessService {
    
    public int calculateCompleteness(CreatorProfile profile) {
        int score = 0;
        int total = 0;
        
        // Basic info (20 points)
        total += 20;
        if (hasValue(profile.getDisplayName())) score += 5;
        if (hasValue(profile.getUsername())) score += 5;
        if (hasValue(profile.getProfilePhotoUrl())) score += 5;
        if (hasValue(profile.getBio())) score += 5;
        
        // Categories (15 points)
        total += 15;
        if (profile.getCategories() != null && !profile.getCategories().isEmpty()) {
            score += 15;
        }
        
        // Location (10 points)
        total += 10;
        if (hasValue(profile.getCity())) score += 5;
        if (hasValue(profile.getCountry())) score += 5;
        
        // Rates (15 points)
        total += 15;
        if (profile.getRateInstagramPost() != null) score += 5;
        if (profile.getRateInstagramReel() != null) score += 5;
        if (profile.getRateYoutubeVideo() != null) score += 5;
        
        // Connected accounts (40 points)
        total += 40;
        var connections = connectionRepo.findByCreatorId(profile.getId());
        if (connections.stream().anyMatch(c -> c.getPlatform() == Platform.INSTAGRAM)) {
            score += 20;
        }
        if (connections.stream().anyMatch(c -> c.getPlatform() == Platform.YOUTUBE)) {
            score += 20;
        }
        
        return (score * 100) / total;
    }
}
```

---

## 7. Test Cases (Kavya)

```java
@Test void shouldCreateProfileOnSignup()
@Test void shouldUpdateProfileFields()
@Test void shouldValidateUsernameFormat()
@Test void shouldRejectDuplicateUsername()
@Test void shouldUploadProfilePhoto()
@Test void shouldCalculateProfileCompleteness()
@Test void shouldReturnPublicProfile()
@Test void shouldFilterPrivateFieldsFromPublicProfile()
@Test void shouldGenerateMediaKitPdf()
```
