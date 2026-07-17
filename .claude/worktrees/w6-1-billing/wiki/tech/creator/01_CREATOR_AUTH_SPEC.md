# Creator Auth Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Signup Flow

### 1.1 Signup Options

```
┌─────────────────────────────────────────────────────────────┐
│  Join Influora as a Creator                                  │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  📧 Continue with Email                              │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  📱 Continue with Phone                              │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ───────────────── or ─────────────────                      │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  📸 Continue with Instagram                          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  Already have an account? Log in                             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Email Signup Flow

```
Step 1: Enter Email
→ POST /api/v1/auth/creator/signup/email
→ { email: "creator@example.com" }
→ Response: { challenge_id: "ch_xxx", expires_in: 300 }

Step 2: Enter OTP (6 digits, sent via MSG91)
→ POST /api/v1/auth/creator/signup/verify-otp
→ { challenge_id: "ch_xxx", otp: "123456" }
→ Response: { user_id: "usr_xxx", access_token: "...", refresh_token: "..." }

Step 3: Set Password + Name
→ POST /api/v1/auth/creator/signup/complete
→ { user_id: "usr_xxx", password: "...", full_name: "Riya Sharma" }
→ Response: { success: true, redirect_to: "/creator/onboarding" }
```

### 1.3 Phone Signup Flow

```
Step 1: Enter Phone
→ POST /api/v1/auth/creator/signup/phone
→ { phone: "+919876543210" }
→ Response: { challenge_id: "ch_xxx", expires_in: 300 }

Step 2: Enter OTP (6 digits, sent via MSG91)
→ POST /api/v1/auth/creator/signup/verify-otp
→ { challenge_id: "ch_xxx", otp: "123456" }
→ Response: { user_id: "usr_xxx", access_token: "...", refresh_token: "..." }

Step 3: Set Password + Name + Email (optional)
→ POST /api/v1/auth/creator/signup/complete
→ { user_id: "usr_xxx", password: "...", full_name: "Riya Sharma", email: "..." }
```

### 1.4 Instagram OAuth Signup

```
Step 1: Click "Continue with Instagram"
→ Redirect to: /connect/instagram/signup

Step 2: Instagram OAuth flow
→ Returns with access token + profile data

Step 3: Extract profile info
→ Name from Instagram display name
→ Profile photo from Instagram
→ Auto-create CreatorProfile with connected account

Step 4: Set email + password (for account recovery)
→ POST /api/v1/auth/creator/signup/complete
→ { user_id: "usr_xxx", password: "...", email: "..." }
```

---

## 2. Login Flow

### 2.1 Email Login

```
POST /api/v1/auth/creator/login
{
    "email": "creator@example.com",
    "password": "..."
}

Response (success):
{
    "user_id": "usr_xxx",
    "access_token": "eyJ...",
    "refresh_token": "...",
    "expires_in": 900,
    "user": {
        "id": "usr_xxx",
        "email": "creator@example.com",
        "full_name": "Riya Sharma",
        "type": "CREATOR",
        "profile_complete": true
    }
}
```

### 2.2 Phone Login

```
Step 1: Enter phone
→ POST /api/v1/auth/creator/login/phone
→ { phone: "+919876543210" }
→ Send OTP

Step 2: Enter OTP
→ POST /api/v1/auth/creator/login/verify-otp
→ { challenge_id: "ch_xxx", otp: "123456" }
→ Return tokens
```

### 2.3 Remember Me

- With "Remember me": refresh token lasts 30 days
- Without: refresh token lasts 7 days
- Access token always 15 minutes

---

## 3. Password Reset

```
Step 1: Request reset
→ POST /api/v1/auth/creator/password/reset-request
→ { email: "creator@example.com" }
→ Send reset link via email (valid 1 hour)

Step 2: Click link in email
→ GET /reset-password?token=xxx
→ Show password reset form

Step 3: Set new password
→ POST /api/v1/auth/creator/password/reset
→ { token: "xxx", new_password: "..." }
→ Invalidate all existing sessions
```

---

## 4. Backend Implementation

### 4.1 CreatorAuthController.java

```java
@RestController
@RequestMapping("/api/v1/auth/creator")
public class CreatorAuthController {
    
    @PostMapping("/signup/email")
    public ChallengeResponse initiateEmailSignup(@RequestBody EmailSignupRequest req) {
        // 1. Check email not already registered
        // 2. Create OTP challenge
        // 3. Send OTP via MSG91
        // 4. Return challenge_id
    }
    
    @PostMapping("/signup/phone")
    public ChallengeResponse initiatePhoneSignup(@RequestBody PhoneSignupRequest req) {
        // Same flow for phone
    }
    
    @PostMapping("/signup/verify-otp")
    public TokenResponse verifySignupOtp(@RequestBody OtpVerifyRequest req) {
        // 1. Verify OTP
        // 2. Create User with type=CREATOR
        // 3. Create empty CreatorProfile
        // 4. Generate tokens
    }
    
    @PostMapping("/signup/complete")
    public SuccessResponse completeSignup(
        @RequestBody CompleteSignupRequest req,
        @AuthenticationPrincipal AuthPrincipal principal
    ) {
        // 1. Set password
        // 2. Update name
        // 3. Mark signup complete
    }
    
    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginRequest req) {
        // Standard email/password login
    }
    
    @PostMapping("/login/phone")
    public ChallengeResponse initiatePhoneLogin(@RequestBody PhoneLoginRequest req) {
        // OTP-based phone login
    }
    
    @PostMapping("/refresh")
    public TokenResponse refreshToken(@RequestBody RefreshRequest req) {
        // Refresh access token
    }
    
    @PostMapping("/logout")
    public SuccessResponse logout(@AuthenticationPrincipal AuthPrincipal principal) {
        // Invalidate refresh token
    }
}
```

### 4.2 CreatorAuthService.java

```java
@Service
public class CreatorAuthService {
    
    private final UserRepository userRepo;
    private final CreatorProfileRepository profileRepo;
    private final EmailOtpChallengeRepository otpRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Msg91EmailClient emailClient;
    
    @Transactional
    public User createCreatorAccount(String email, String phone, String name) {
        var user = User.builder()
            .id(Ulids.generate())
            .email(email)
            .phone(phone)
            .fullName(name)
            .type(UserType.CREATOR)
            .status(UserStatus.ACTIVE)
            .build();
        userRepo.save(user);
        
        // Create empty profile
        var profile = CreatorProfile.builder()
            .id(Ulids.generate())
            .userId(user.getId())
            .verificationStatus(VerificationStatus.PENDING)
            .build();
        profileRepo.save(profile);
        
        return user;
    }
}
```

---

## 5. Frontend Implementation

### 5.1 Pages (Ananya)

```
src/app/creator/
├── signup/
│   ├── page.tsx              # Signup options
│   ├── email/page.tsx        # Email signup
│   ├── phone/page.tsx        # Phone signup
│   ├── verify/page.tsx       # OTP verification
│   └── complete/page.tsx     # Set password/name
├── login/
│   ├── page.tsx              # Login options
│   └── phone/page.tsx        # Phone OTP login
└── reset-password/
    ├── page.tsx              # Request reset
    └── [token]/page.tsx      # Enter new password
```

### 5.2 SignupEmailPage.tsx

```tsx
export default function SignupEmailPage() {
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  
  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    
    try {
      const { challenge_id } = await api.post('/auth/creator/signup/email', { email });
      // Store challenge_id in session storage
      sessionStorage.setItem('signup_challenge', challenge_id);
      router.push('/creator/signup/verify');
    } catch (error) {
      toast.error(error.message);
    } finally {
      setLoading(false);
    }
  };
  
  return (
    <div className="min-h-screen flex items-center justify-center">
      <Card className="w-full max-w-md p-6">
        <h1 className="text-2xl font-bold mb-6">Create your creator account</h1>
        
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <Label htmlFor="email">Email address</Label>
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
            />
          </div>
          
          <Button type="submit" className="w-full" disabled={loading}>
            {loading ? <Spinner /> : 'Continue'}
          </Button>
        </form>
        
        <p className="text-sm text-muted-foreground mt-4 text-center">
          Already have an account?{' '}
          <Link href="/creator/login" className="text-primary">Log in</Link>
        </p>
      </Card>
    </div>
  );
}
```

### 5.3 OTP Verification Component

```tsx
export function OtpInput({ 
  length = 6, 
  onComplete 
}: { 
  length?: number; 
  onComplete: (otp: string) => void;
}) {
  const [values, setValues] = useState<string[]>(Array(length).fill(''));
  const inputRefs = useRef<HTMLInputElement[]>([]);
  
  const handleChange = (index: number, value: string) => {
    if (!/^\d*$/.test(value)) return;
    
    const newValues = [...values];
    newValues[index] = value.slice(-1);
    setValues(newValues);
    
    // Auto-focus next input
    if (value && index < length - 1) {
      inputRefs.current[index + 1]?.focus();
    }
    
    // Check if complete
    const otp = newValues.join('');
    if (otp.length === length) {
      onComplete(otp);
    }
  };
  
  return (
    <div className="flex gap-2 justify-center">
      {values.map((value, index) => (
        <Input
          key={index}
          ref={(el) => (inputRefs.current[index] = el!)}
          type="text"
          inputMode="numeric"
          maxLength={1}
          value={value}
          onChange={(e) => handleChange(index, e.target.value)}
          className="w-12 h-12 text-center text-2xl"
        />
      ))}
    </div>
  );
}
```

---

## 6. Security Requirements (Kabir)

### 6.1 OTP Security
- 6 digits, cryptographically random
- Expires in 5 minutes
- Max 3 attempts per challenge
- Rate limit: 3 OTP requests per email/phone per hour
- Hash OTP in database, never store plain

### 6.2 Password Requirements
- Minimum 8 characters
- Must contain: uppercase, lowercase, number
- Check against common passwords list
- Hash with bcrypt (cost factor 12)

### 6.3 Token Security
- Access token: JWT, RS256, 15 min expiry
- Refresh token: opaque, stored hashed in DB
- Rotate refresh token on each use
- Invalidate all tokens on password change

### 6.4 Rate Limiting
- Login attempts: 5 per minute per IP
- Signup attempts: 3 per minute per IP
- Password reset: 3 per hour per email

### 6.5 Audit Logging
- Log all auth events (login, logout, password change)
- Never log passwords or tokens
- Include IP, user agent, timestamp

---

## 7. Test Cases (Kavya)

```java
// Signup Tests
@Test void shouldSendOtpOnEmailSignup()
@Test void shouldRejectDuplicateEmail()
@Test void shouldVerifyOtpAndCreateUser()
@Test void shouldRejectExpiredOtp()
@Test void shouldRejectInvalidOtp()
@Test void shouldRateLimitOtpRequests()

// Login Tests
@Test void shouldLoginWithCorrectCredentials()
@Test void shouldRejectWrongPassword()
@Test void shouldRateLimitFailedLogins()
@Test void shouldRefreshAccessToken()
@Test void shouldInvalidateTokenOnLogout()

// Password Reset Tests
@Test void shouldSendResetEmail()
@Test void shouldResetPasswordWithValidToken()
@Test void shouldRejectExpiredResetToken()
@Test void shouldInvalidateAllSessionsOnPasswordReset()
```

---

## 8. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/auth/creator/signup/email` | POST | None | Initiate email signup |
| `/auth/creator/signup/phone` | POST | None | Initiate phone signup |
| `/auth/creator/signup/verify-otp` | POST | None | Verify OTP |
| `/auth/creator/signup/complete` | POST | JWT | Complete profile |
| `/auth/creator/login` | POST | None | Email/password login |
| `/auth/creator/login/phone` | POST | None | Phone OTP login |
| `/auth/creator/refresh` | POST | None | Refresh access token |
| `/auth/creator/logout` | POST | JWT | Logout |
| `/auth/creator/password/reset-request` | POST | None | Request reset |
| `/auth/creator/password/reset` | POST | None | Reset password |
