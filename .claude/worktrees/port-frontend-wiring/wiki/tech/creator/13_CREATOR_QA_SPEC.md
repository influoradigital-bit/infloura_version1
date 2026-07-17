# Creator QA Test Plan

> **Owner:** Kavya (QA Lead)  
> **Date:** 2026-07-07

---

## 1. Test Coverage Requirements

| Module | Unit Tests | Integration Tests | E2E Tests | Coverage Target |
|--------|------------|-------------------|-----------|-----------------|
| Auth | Required | Required | Required | 90% |
| Profile | Required | Required | Required | 85% |
| OAuth | Required | Required | Required | 90% |
| Discovery | Required | Required | Sample | 80% |
| Campaigns | Required | Required | Required | 85% |
| Bids | Required | Required | Required | 90% |
| Contracts | Required | Required | Required | 90% |
| Chat | Required | Required | Sample | 80% |
| Deliverables | Required | Required | Required | 85% |
| Payments | Required | Required | Required | 95% |
| Analytics | Required | Required | Sample | 75% |

---

## 2. Auth Tests

### Unit Tests
```java
// CreatorAuthServiceTest.java
@Test void shouldSendOtpOnEmailSignup()
@Test void shouldRejectInvalidEmailFormat()
@Test void shouldRejectDuplicateEmail()
@Test void shouldHashOtpBeforeStorage()
@Test void shouldVerifyCorrectOtp()
@Test void shouldRejectExpiredOtp()
@Test void shouldRejectWrongOtp()
@Test void shouldLockAfter3FailedAttempts()
@Test void shouldCreateUserOnSuccessfulVerification()
@Test void shouldCreateEmptyCreatorProfile()
@Test void shouldHashPassword()
@Test void shouldGenerateJwtOnLogin()
@Test void shouldRotateRefreshToken()
@Test void shouldInvalidateAllSessionsOnPasswordReset()
```

### Integration Tests
```java
@Test void fullEmailSignupFlow()
@Test void fullPhoneSignupFlow()
@Test void loginWithCorrectCredentials()
@Test void loginWithWrongPassword()
@Test void refreshTokenFlow()
@Test void passwordResetFlow()
@Test void rateLimitingOnLogin()
```

### E2E Tests (Playwright)
```typescript
test('creator can sign up with email', async ({ page }) => {
  await page.goto('/creator/signup');
  await page.click('text=Continue with Email');
  await page.fill('input[name=email]', 'test@example.com');
  await page.click('button:has-text("Continue")');
  
  // OTP page should appear
  await expect(page.locator('text=Enter verification code')).toBeVisible();
  
  // Enter OTP (from test mailbox)
  const otp = await getTestOtp('test@example.com');
  await page.fill('input[name=otp]', otp);
  
  // Should redirect to complete profile
  await expect(page).toHaveURL('/creator/signup/complete');
});
```

---

## 3. Profile Tests

### Unit Tests
```java
@Test void shouldCreateProfileOnSignup()
@Test void shouldUpdateProfileFields()
@Test void shouldValidateUsernameFormat()
@Test void shouldRejectUsernameWithSpecialChars()
@Test void shouldRejectDuplicateUsername()
@Test void shouldCalculateProfileCompleteness()
@Test void shouldUploadProfilePhoto()
@Test void shouldResizeProfilePhoto()
@Test void shouldValidateCategories()
@Test void shouldLimitTo3Categories()
@Test void shouldValidateRateRange()
```

### Integration Tests
```java
@Test void fullOnboardingFlow()
@Test void updateProfileAndVerify()
@Test void uploadPhotoAndRetrieve()
@Test void generateMediaKitPdf()
@Test void publicProfileHidesPrivateFields()
```

---

## 4. OAuth Tests

### Unit Tests
```java
@Test void shouldBuildCorrectOAuthUrl()
@Test void shouldIncludePkceChallenge()
@Test void shouldValidateState()
@Test void shouldExchangeCodeForToken()
@Test void shouldEncryptTokenBeforeStorage()
@Test void shouldDecryptTokenForUse()
@Test void shouldRefreshExpiredToken()
@Test void shouldRevokeTokenOnDisconnect()
@Test void shouldImportProfileData()
@Test void shouldFetchMetrics()
```

### Integration Tests (with mocked OAuth)
```java
@Test void fullInstagramConnectFlow()
@Test void fullYoutubeConnectFlow()
@Test void disconnectAndReconnect()
@Test void handleRateLimiting()
@Test void handleTokenExpiry()
```

---

## 5. Campaign Tests

### Unit Tests
```java
@Test void shouldListOpenCampaigns()
@Test void shouldFilterByCategory()
@Test void shouldFilterByFollowerRange()
@Test void shouldSortByRelevance()
@Test void shouldCalculateMatchScore()
@Test void shouldSubmitApplication()
@Test void shouldRejectDuplicateApplication()
@Test void shouldTrackApplicationStatus()
```

### Integration Tests
```java
@Test void browseAndApplyToCampaign()
@Test void viewApplicationStatus()
@Test void campaignClosesNoMoreApplications()
```

---

## 6. Bid Tests

### Unit Tests
```java
@Test void shouldSubmitBid()
@Test void shouldValidateBidAmount()
@Test void shouldRejectBidBelowMinimum()
@Test void shouldCounterBid()
@Test void shouldLimit5CounterRounds()
@Test void shouldAcceptBid()
@Test void shouldRejectBid()
@Test void shouldExpireBidAfterDeadline()
@Test void shouldTransitionToContractOnAccept()
```

### Integration Tests
```java
@Test void fullNegotiationFlow()
@Test void bidAcceptedCreatesContract()
@Test void bidRejectedNotifiesCreator()
@Test void bidExpiredNotifiesBothParties()
```

---

## 7. Contract Tests

### Unit Tests
```java
@Test void shouldGenerateContractFromBid()
@Test void shouldCalculateMilestones()
@Test void shouldGeneratePdf()
@Test void shouldSignContract()
@Test void shouldRequireBothSignatures()
@Test void shouldCreateEscrowOnSign()
@Test void shouldDeclineContract()
```

### Integration Tests
```java
@Test void fullContractSigningFlow()
@Test void contractDeclineFlow()
@Test void escrowCreatedAfterBothSign()
```

---

## 8. Chat Tests

### Unit Tests
```java
@Test void shouldSendMessage()
@Test void shouldReceiveMessage()
@Test void shouldMarkAsRead()
@Test void shouldUploadAttachment()
@Test void shouldCallMeeraAi()
@Test void shouldStreamAiResponse()
```

### Integration Tests
```java
@Test void brandCreatorConversation()
@Test void meeraAiConversation()
@Test void fileSharing()
@Test void realTimeWebSocket()
```

---

## 9. Deliverable Tests

### Unit Tests
```java
@Test void shouldUploadDeliverable()
@Test void shouldValidateVideoFormat()
@Test void shouldRejectOversizedFile()
@Test void shouldSubmitForReview()
@Test void shouldRequestRevision()
@Test void shouldApproveDeliverable()
@Test void shouldReportMetrics()
```

### Integration Tests
```java
@Test void fullDeliverableSubmissionFlow()
@Test void revisionFlow()
@Test void approvalTriggersEscrowRelease()
```

---

## 10. Payment Tests (CRITICAL)

### Unit Tests
```java
// These tests MUST pass before any payment code deploys
@Test void shouldCalculateWalletBalance()
@Test void shouldNotOverdraw()
@Test void shouldVerifyBankAccount()
@Test void shouldInitiateWithdrawal()
@Test void shouldRequireOtpForLargeWithdrawal()
@Test void shouldEnforceWithdrawalLimits()
@Test void shouldBeIdempotent()
@Test void shouldPreventDoubleSpend()
@Test void shouldRecordAuditTrail()
@Test void shouldCalculateAffiliateEarnings()
@Test void shouldProcessMonthlySettlement()
```

### Integration Tests
```java
@Test void fullWithdrawalFlow()
@Test void withdrawalFailsInsufficientBalance()
@Test void withdrawalRejectedNewBankAccount()
@Test void affiliateEarningsFlow()
@Test void escrowReleaseFlow()
```

### Concurrency Tests
```java
@Test void concurrentWithdrawalsNoDoubleSpend() {
    // Start 10 concurrent withdrawals for same amount
    // Only one should succeed
    var futures = IntStream.range(0, 10)
        .mapToObj(i -> executor.submit(() -> 
            walletService.withdraw(creatorId, amount, key + i)))
        .toList();
    
    var results = futures.stream().map(this::getResult).toList();
    var successes = results.stream().filter(r -> r.isSuccess()).count();
    
    assertThat(successes).isEqualTo(1);
}
```

---

## 11. E2E Test Scenarios

### Happy Path: Creator Journey
```typescript
test('complete creator journey', async ({ page }) => {
  // 1. Signup
  await signupAsCreator(page);
  
  // 2. Complete onboarding
  await completeOnboarding(page, {
    name: 'Riya Sharma',
    categories: ['fitness', 'lifestyle'],
    rates: { instagramPost: 15000 }
  });
  
  // 3. Connect Instagram
  await connectInstagram(page);
  
  // 4. Apply to campaign
  await page.goto('/creator/campaigns');
  await page.click('text=Summer Fitness Campaign');
  await page.click('text=Apply');
  await page.fill('textarea[name=pitch]', 'I would love to...');
  await page.click('button:has-text("Submit Application")');
  
  // 5. Receive and accept bid
  await waitForBid(page);
  await page.click('text=Accept Bid');
  
  // 6. Sign contract
  await page.click('text=Sign Contract');
  await page.click('text=I agree');
  
  // 7. Submit deliverable
  await page.setInputFiles('input[type=file]', './test-video.mp4');
  await page.click('text=Submit for Review');
  
  // 8. Check wallet after approval
  await waitForApproval(page);
  await page.goto('/creator/wallet');
  await expect(page.locator('text=₹15,000')).toBeVisible();
});
```

### Edge Cases
```typescript
test('handle expired OTP', async ({ page }) => {
  // Wait for OTP to expire, verify error message
});

test('handle OAuth cancellation', async ({ page }) => {
  // User cancels OAuth, verify graceful handling
});

test('handle payment failure', async ({ page }) => {
  // Bank rejects withdrawal, verify retry flow
});
```

---

## 12. Performance Tests

### API Response Times
| Endpoint | Target p50 | Target p99 | Max |
|----------|------------|------------|-----|
| Profile read | 50ms | 200ms | 500ms |
| Campaign list | 100ms | 300ms | 1s |
| Search | 150ms | 500ms | 2s |
| File upload | - | - | 30s |

### Load Tests
```java
@Test void profileEndpointUnder100Concurrent() {
    // 100 concurrent requests, all under 500ms
}

@Test void searchEndpointUnder50Concurrent() {
    // 50 concurrent searches, all under 2s
}
```

---

## 13. Accessibility Tests

### WCAG AA Compliance
```typescript
test('signup form is accessible', async ({ page }) => {
  await page.goto('/creator/signup');
  
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test('dashboard is keyboard navigable', async ({ page }) => {
  await loginAsCreator(page);
  await page.goto('/creator/dashboard');
  
  // Tab through all interactive elements
  for (let i = 0; i < 20; i++) {
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(focused).not.toBe('BODY');
  }
});
```

---

## 14. QA Sign-off Checklist

### Before Feature Ships:
- [ ] All unit tests passing
- [ ] All integration tests passing
- [ ] E2E tests passing on staging
- [ ] Performance benchmarks met
- [ ] Accessibility audit passed
- [ ] Security sign-off from Kabir
- [ ] Manual smoke test on staging
- [ ] No console errors in browser
- [ ] Mobile responsive verified
- [ ] Cross-browser tested (Chrome, Safari, Firefox)

### Before Production:
- [ ] All above plus:
- [ ] Load test on staging
- [ ] Rollback plan documented
- [ ] Monitoring alerts configured
- [ ] Error tracking enabled
- [ ] Analytics events verified

---

## 15. Bug Severity Classification

| Severity | Definition | Response Time |
|----------|------------|---------------|
| P0 | Data loss, security breach, payment failure | Immediate |
| P1 | Feature broken, blocking user flow | 4 hours |
| P2 | Feature degraded, workaround exists | 24 hours |
| P3 | Minor issue, cosmetic | Next sprint |

---

## 16. Test Data

### Test Accounts (Staging)
```
Creator: testcreator@influora.com / Test@123
Brand: testbrand@influora.com / Test@123
```

### Test OAuth
- Instagram: Mock server at `oauth-mock.staging.influora.com`
- YouTube: Mock server at `oauth-mock.staging.influora.com`

### Test Payments
- Razorpay test mode enabled on staging
- Test bank: 1234567890, IFSC: RATN0VAAPIS
