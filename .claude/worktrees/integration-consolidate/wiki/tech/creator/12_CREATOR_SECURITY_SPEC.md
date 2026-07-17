# Creator Security Specification

> **Owner:** Kabir (Security Lead)  
> **Date:** 2026-07-07

---

## 1. Authentication Security

### 1.1 Password Requirements
- Minimum 8 characters
- At least 1 uppercase, 1 lowercase, 1 number
- Check against top 10,000 common passwords
- Hash with bcrypt, cost factor 12
- Never log passwords in any form

### 1.2 OTP Security
- 6 digits, cryptographically random (SecureRandom)
- Expires in 5 minutes
- Max 3 verification attempts per challenge
- Rate limit: 3 OTPs per email/phone per hour
- Store hashed OTP in database
- Delete challenge after successful verification

### 1.3 Token Security
| Token Type | Algorithm | Expiry | Storage |
|------------|-----------|--------|---------|
| Access Token | RS256 JWT | 15 min | Client memory only |
| Refresh Token | Opaque | 7-30 days | Hashed in DB |
| OAuth Token | Platform-specific | Varies | AES-256-GCM encrypted |

### 1.4 Session Management
- Rotate refresh token on each use
- Invalidate all sessions on password change
- Track active sessions per user
- Allow user to revoke specific sessions
- Automatic logout after 30 days inactivity

---

## 2. OAuth Security

### 2.1 Token Storage
```java
// REQUIRED: All OAuth tokens encrypted at rest
@Entity
public class SocialConnection {
    // NEVER store plain tokens
    @Column(name = "access_token_encrypted")
    private byte[] accessTokenEncrypted;
    
    @Column(name = "encryption_key_id")
    private String encryptionKeyId;  // For key rotation
}
```

### 2.2 OAuth Flow Security
- Use PKCE (code_verifier + code_challenge)
- State parameter: cryptographically random, single-use
- Validate redirect_uri matches registered
- Short-lived authorization codes (< 10 min)
- Token refresh in background, never expose refresh token to frontend

### 2.3 Scope Minimization
| Platform | Required Scopes | Rejected Scopes |
|----------|-----------------|-----------------|
| Instagram | instagram_basic, instagram_manage_insights | publishing_actions |
| YouTube | youtube.readonly, yt-analytics.readonly | youtube.upload |
| Facebook | pages_show_list, pages_read_engagement | publish_pages |

---

## 3. Data Privacy

### 3.1 PII Handling
| Data Type | Storage | Access | Retention |
|-----------|---------|--------|-----------|
| Email | Encrypted | Auth only | Account lifetime |
| Phone | Encrypted | Auth only | Account lifetime |
| Bank Account | Encrypted | Payments only | 7 years |
| UPI ID | Encrypted | Payments only | 7 years |
| Address | Encrypted | Shipping only | 2 years |
| Social Metrics | Plain | Analytics | 2 years |

### 3.2 Data Access Rules
- Creator can only access own data
- Brand can only access creators they're collaborating with
- Public profile exposes limited fields only
- Analytics data never exposes individual follower data

### 3.3 Right to Deletion
```java
@Service
public class CreatorDataDeletionService {
    
    @Transactional
    public void deleteCreatorData(String creatorId) {
        // 1. Revoke all OAuth tokens
        socialConnectionRepo.deleteByCreatorId(creatorId);
        
        // 2. Anonymize completed collaborations (keep for brand records)
        collaborationRepo.anonymizeCreator(creatorId);
        
        // 3. Delete pending/active collaborations
        collaborationRepo.deletePendingByCreator(creatorId);
        
        // 4. Delete profile and wallet
        walletRepo.deleteByCreatorId(creatorId);
        creatorProfileRepo.deleteById(creatorId);
        
        // 5. Delete user account
        userRepo.deleteById(creatorId);
        
        // 6. Audit log (anonymized)
        auditLog.log("CREATOR_DELETED", Map.of("id_hash", hash(creatorId)));
    }
}
```

---

## 4. Payment Security

### 4.1 Withdrawal Security
- Verify bank account via penny drop before first withdrawal
- UPI ID verification via collect request
- Require OTP for withdrawals > ₹10,000
- Daily withdrawal limit: ₹1,00,000
- Monthly withdrawal limit: ₹10,00,000 (KYC verified)
- Cool-down period for new bank accounts: 24 hours

### 4.2 Idempotency
```java
// ALL payment mutations MUST be idempotent
@Transactional
public WithdrawalResult processWithdrawal(WithdrawalRequest req) {
    return idempotencyService.executeOnce(
        req.getIdempotencyKey(),
        () -> {
            // Actual withdrawal logic
        }
    );
}
```

### 4.3 Audit Trail
- Log all wallet transactions
- Log all withdrawal requests (success and failure)
- Log all bank account changes
- Retain audit logs for 7 years

---

## 5. Content Security

### 5.1 File Upload Security
- Max file size: 100MB for videos, 10MB for images
- Allowed types: jpg, png, gif, mp4, mov
- Virus scan before storage
- Strip EXIF metadata from images
- Generate unique filenames (never use user-provided names)
- Store on R2 with signed URLs (1-hour expiry)

### 5.2 Content Validation
```java
@Service
public class ContentValidationService {
    
    public void validateUpload(MultipartFile file) {
        // 1. Check file size
        if (file.getSize() > MAX_SIZE) {
            throw new ValidationException("File too large");
        }
        
        // 2. Check MIME type (from content, not header)
        String actualType = detectMimeType(file.getInputStream());
        if (!ALLOWED_TYPES.contains(actualType)) {
            throw new ValidationException("File type not allowed");
        }
        
        // 3. Check for embedded scripts/malware
        if (containsMaliciousContent(file)) {
            throw new SecurityException("Malicious content detected");
        }
    }
}
```

---

## 6. API Security

### 6.1 Rate Limiting
| Endpoint Category | Limit | Window |
|-------------------|-------|--------|
| Auth endpoints | 10 | 1 minute |
| Profile read | 100 | 1 minute |
| Profile write | 20 | 1 minute |
| File upload | 10 | 1 minute |
| Search/discovery | 60 | 1 minute |
| Withdrawal | 5 | 1 hour |

### 6.2 Input Validation
- Validate all inputs server-side (never trust client)
- Sanitize HTML in bio/description fields
- Reject SQL injection attempts
- Reject XSS payloads
- Validate enum values against whitelist

### 6.3 Authorization
```java
// Every endpoint MUST check authorization
@PreAuthorize("@authz.isCreator(#principal) && @authz.ownsProfile(#principal, #profileId)")
@GetMapping("/creator/profile/{profileId}/private")
public CreatorProfile getPrivateProfile(
    @AuthenticationPrincipal AuthPrincipal principal,
    @PathVariable String profileId
) {
    // Only reached if creator owns this profile
}
```

---

## 7. Communication Security

### 7.1 Message Security
- Messages encrypted in transit (TLS 1.3)
- Messages stored encrypted at rest
- No message content in push notifications (only "New message from Brand X")
- File attachments scanned before delivery

### 7.2 Notification Security
- Email notifications don't contain sensitive data
- Push notifications use data message, not notification message
- SMS notifications minimal (no amounts, no names)

---

## 8. Red Team Checklist

Before creator features ship, I (Kabir) will test:

### Authentication
- [ ] Brute force login attempts
- [ ] OTP enumeration
- [ ] Password reset token reuse
- [ ] Session hijacking
- [ ] JWT manipulation

### Authorization
- [ ] IDOR on profile endpoints
- [ ] Access other creator's wallet
- [ ] Access other creator's contracts
- [ ] Escalate to brand role

### OAuth
- [ ] CSRF on OAuth callback
- [ ] Token extraction from logs
- [ ] Scope escalation
- [ ] Token refresh manipulation

### Payments
- [ ] Withdraw more than balance
- [ ] Double-spend via race condition
- [ ] Change bank account and immediate withdraw
- [ ] Manipulate withdrawal amount

### Content
- [ ] Upload malicious files
- [ ] SSRF via file URL
- [ ] Path traversal in filenames
- [ ] XSS in bio/description

---

## 9. Security Sign-off Requirements

| Feature | Security Review | Penetration Test | Sign-off |
|---------|-----------------|------------------|----------|
| Creator Auth | Required | Required | Kabir |
| OAuth Connect | Required | Required | Kabir |
| Profile | Required | Sample | Kabir |
| Payments | Required | Required | Kabir + External |
| File Upload | Required | Required | Kabir |
| Messaging | Required | Sample | Kabir |

---

## 10. Incident Response

### If Breach Detected:
1. Immediately revoke affected tokens
2. Force password reset for affected accounts
3. Notify affected creators within 72 hours
4. Notify regulatory authorities if PII exposed
5. Conduct post-mortem and fix root cause
6. Update security measures

### Contact
- Security issues: security@influora.com
- Kabir direct escalation: SHARED_CONTEXT.md
