# Kavya QA Test Plan - Influora Analytics Features

**Author:** Kavya (QA Lead)  
**Date:** 2026-07-06  
**Version:** 1.0  
**Status:** Draft

---

## Executive Summary

This document outlines the comprehensive QA specification for Influora's new analytics features including Meta API integration, UTM/coupon tracking, and scoring algorithms.

**Current State:**
- `influora-api` (Java): Only 7 test files - **critical gap**
- `influora-ai` (Python): 113 tests passing - **excellent baseline**

**Target:** Achieve 80%+ coverage for all new code before production release.

---

## 1. Test Coverage Requirements

| Category | Minimum Coverage | Rationale |
|----------|-----------------|-----------|
| All new code | 80% line coverage | Industry standard for production code |
| Security-critical paths | 100% coverage | Token handling, coupon redemption, auth flows |
| Public APIs | 100% integration test coverage | Contract validation |
| Database migrations | 100% rollback tested | Data integrity protection |

### Coverage Enforcement
- Jacoco coverage reports generated on every build
- PR blocked if coverage drops below threshold
- SonarQube quality gates configured

---

## 2. Unit Tests Required

### 2.1 Meta Integration Tests

#### `MetaGraphApiClientTest.java`
```
Test Cases:
- testSuccessfulApiCall_ReturnsValidResponse
- testApiTimeout_ThrowsTimeoutException
- testRateLimitExceeded_RetriesWithBackoff
- testInvalidAccessToken_ThrowsAuthException
- testMalformedResponse_HandlesGracefully
- testNetworkError_RetriesConfiguredTimes
- testPagination_FetchesAllPages
- testFieldSelection_RequestsCorrectFields
```

#### `InstagramInsightsClientTest.java`
```
Test Cases:
- testParseImpressions_CorrectValue
- testParseReach_CorrectValue
- testParseEngagement_CalculatesRate
- testParseDemographics_MapsCorrectly
- testParseStoryMetrics_HandlesExpiry
- testRateLimitHeader_ParsesCorrectly
- testRateLimitApproaching_SlowsRequests
- testEmptyMetrics_ReturnsZeroNotNull
- testPartialResponse_HandlesGracefully
```

#### `OAuthTokenServiceTest.java`
```
Test Cases:
- testEncryptToken_ProducesValidCiphertext
- testDecryptToken_ReturnsOriginal
- testRefreshToken_UpdatesAccessToken
- testRefreshToken_UpdatesExpiry
- testRevokeToken_CallsMetaEndpoint
- testRevokeToken_ClearsLocalStorage
- testExpiredToken_TriggersRefresh
- testInvalidRefreshToken_NotifiesUser
- testTokenStorage_NeverLogsPlaintext
- testTokenEncryption_UsesAES256GCM
```

### 2.2 Scoring Algorithm Tests

#### `FakeFollowerDetectionServiceTest.java`
```
Test Cases:
- testKnownFakePattern_HighSuspicionScore (bulk follows, no posts)
- testKnownRealPattern_LowSuspicionScore (organic engagement)
- testFollowerGrowthSpike_FlagsAnomaly
- testEngagementRatioTooHigh_FlagsBotActivity
- testEngagementRatioTooLow_FlagsDeadFollowers
- testGenericUsernames_IncreasesScore
- testNoProfilePhoto_IncreasesScore
- testPrivateAccount_HandlesGracefully
- testInsufficientData_ReturnsUncertain
- testScoreRange_AlwaysBetween0And100
```

#### `QualityScoreServiceTest.java`
```
Test Cases:
- testConsistentInputs_ProduceConsistentScore
- testEngagementWeight_AppliedCorrectly
- testFollowerQualityWeight_AppliedCorrectly
- testContentQualityWeight_AppliedCorrectly
- testScoreNormalization_Within0To100
- testEdgeCases_ZeroFollowers
- testEdgeCases_NewAccount
- testEdgeCases_ViralPost
- testWeightSum_Equals100Percent
- testScoreHistory_TracksChanges
```

#### `BrandSafetyScoreServiceTest.java`
```
Test Cases:
- testGARMClassification_Adult_Correct
- testGARMClassification_Violence_Correct
- testGARMClassification_Political_Correct
- testGARMClassification_Safe_Correct
- testMultipleCategories_HighestRiskReturned
- testHistoricalContent_WeightedByRecency
- testCaptionAnalysis_FlagsRiskyWords
- testImageAnalysis_FlagsRiskyContent
- testFalsePositive_AllowsAppeal
- testBrandSpecificBlacklist_Honored
```

#### `RateEstimationServiceTest.java`
```
Test Cases:
- testBaseRate_CalculatedFromFollowers
- testEngagementMultiplier_AppliedCorrectly
- testNicheMultiplier_AppliedCorrectly
- testHistoricalPerformance_InfluencesRate
- testMarketComparison_AdjustsRange
- testMinimumRate_NeverBelowThreshold
- testMaximumRate_CappedReasonably
- testRateRange_MinLessThanMax
- testCurrency_FormatsCorrectly
- testRateHistory_TracksChanges
```

### 2.3 UTM/Coupon Tests

#### `CouponCodeServiceTest.java`
```
Test Cases:
- testGenerateCode_ReturnsUniqueCode
- testGenerateCode_MatchesFormat (BRAND-CREATOR-XXXX)
- testGenerateBatch_AllUnique
- testGenerateBatch_NoCollisions
- testCodeFormat_AlphanumericOnly
- testCodeFormat_CorrectLength
- testCodeFormat_NoProfanity
- testCodeCase_Uppercase
- testExistingCode_RejectsGeneration
- testCustomPrefix_HonoredInGeneration
```

#### `RedemptionServiceTest.java`
```
Test Cases:
- testRedemption_Idempotent (same order, same result)
- testRedemption_AtomicOperation
- testRedemption_UpdatesUsageCount
- testRedemption_RecordsTimestamp
- testRedemption_AssociatesCreator
- testExpiredCoupon_RejectsRedemption
- testMaxUsageReached_RejectsRedemption
- testConcurrentRedemption_OnlyOneSucceeds
- testPartialRedemption_Rollback
- testRedemptionHistory_Queryable
```

#### `ConversionTrackingServiceTest.java`
```
Test Cases:
- testUTMCapture_ParsesAllParams
- testUTMCapture_HandlesEncoding
- testFunnelStage_Click_Recorded
- testFunnelStage_Visit_Recorded
- testFunnelStage_AddToCart_Recorded
- testFunnelStage_Purchase_Recorded
- testAttribution_AssignsToCreator
- testAttribution_HandlesMultiTouch
- testConversionValue_CalculatedCorrectly
- testConversionWindow_Respected (30 days default)
```

### 2.4 Job Tests

#### `MetricsPollingJobTest.java`
```
Test Cases:
- testSchedule_RunsAtConfiguredInterval
- testFailure_RetriesWithBackoff
- testFailure_AlertsAfterMaxRetries
- testPartialFailure_ContinuesOthers
- testRateLimit_PausesAndResumes
- testTokenExpired_SkipsCreator
- testNewCreator_AddsToPollingList
- testRemovedCreator_RemovesFromPolling
- testJobLock_PreventsConcurrentRuns
- testMetrics_StoredWithTimestamp
```

#### `ScoreCalculationJobTest.java`
```
Test Cases:
- testBatchProcessing_HandlesLargeBatches
- testBatchProcessing_ChunksCorrectly
- testScoreUpdate_TriggersNotification
- testScoreChange_RecordsDelta
- testNewCreator_CalculatesInitialScore
- testInactiveCreator_SkipsCalculation
- testJobFailure_DoesNotCorruptExisting
- testJobCompletion_LogsSummary
- testJobPerformance_Under5Minutes
- testJobRetry_PicksUpWhereLeftOff
```

---

## 3. Integration Tests

### 3.1 API Integration Tests

#### `MetaAnalyticsControllerIntegrationTest.java`
```
Test Cases:
- testGetAnalytics_ReturnsValidResponse
- testGetAnalytics_RequiresAuthentication
- testGetAnalytics_RespectsWorkspaceIsolation
- testGetAnalytics_DateRangeFilter
- testGetAnalytics_MetricSelection
- testGetAnalytics_Pagination
- testGetAnalytics_InvalidCreatorId_404
- testGetAnalytics_ExpiredToken_RefreshesAutomatically
- testGetAnalytics_CachesResponse
- testGetAnalytics_ResponseTimeUnder500ms
```

#### `CampaignTrackingControllerIntegrationTest.java`
```
Test Cases:
- testCreateCampaign_GeneratesUTM
- testCreateCampaign_GeneratesCoupons
- testGetCampaignStats_AggregatesCorrectly
- testUpdateCampaign_ReflectsChanges
- testDeleteCampaign_SoftDeletes
- testCampaignCreatorAssignment_Works
- testCampaignDateRange_Enforced
- testCampaignBudget_TracksSpend
- testCampaignExport_GeneratesCSV
- testCampaignWebhook_FiresOnConversion
```

### 3.2 Full Flow Integration Tests

#### `OAuthToAnalyticsFlowTest.java`
```
Flow Test:
1. Brand initiates OAuth → Meta authorization page
2. User grants permissions → Redirect with code
3. Exchange code for tokens → Tokens encrypted and stored
4. Polling job fetches metrics → Stored in TimescaleDB
5. Brand queries analytics API → Returns aggregated data
6. Token expires → Auto-refresh works
7. Brand disconnects → Tokens revoked and deleted
```

#### `CampaignConversionFlowTest.java`
```
Flow Test:
1. Brand creates campaign → UTM links generated
2. Creator shares link → Click tracked
3. User visits site → Visit recorded with UTM
4. User adds to cart → Funnel event captured
5. User uses coupon code → Redemption recorded
6. Purchase completes → Conversion attributed to creator
7. Brand views dashboard → All metrics accurate
```

### 3.3 Database Tests

#### `TimescaleDBIntegrationTest.java`
```
Test Cases:
- testHypertableInsert_PerformanceUnder10ms
- testTimeSeriesQuery_LastHour_Fast
- testTimeSeriesQuery_Last30Days_Fast
- testTimeSeriesQuery_Last90Days_Acceptable
- testAggregation_Sum_Correct
- testAggregation_Average_Correct
- testAggregation_Max_Correct
- testAggregation_ByDay_Correct
- testAggregation_ByWeek_Correct
- testCompression_ReducesStorage
```

#### `MigrationRollbackTest.java`
```
Test Cases:
- testMigration_AppliesCleanly
- testMigration_Rollback_RestoresPrevious
- testMigration_Idempotent
- testMigration_DataPreserved
- testMigration_IndexesCreated
- testMigration_ConstraintsEnforced
```

---

## 4. Security Tests (with Kabir)

### 4.1 Access Control Tests

```
Test Cases:
- testCrossWorkspaceAccess_Blocked
- testCrossWorkspaceCreator_NotVisible
- testCrossWorkspaceCampaign_NotVisible
- testCrossWorkspaceAnalytics_NotVisible
- testUnauthorizedUser_401Response
- testExpiredSession_401Response
- testInvalidToken_401Response
- testRolePermissions_Enforced
```

### 4.2 Token Security Tests

```
Test Cases:
- testTokenStorage_Encrypted
- testTokenEncryption_AES256GCM
- testTokenLogs_NeverContainPlaintext
- testTokenResponse_NeverExposed
- testTokenRefresh_SecureTransport
- testTokenRevocation_Immediate
```

### 4.3 Coupon Security Tests

```
Test Cases:
- testCouponBruteForce_RateLimited (max 10/min)
- testCouponEnumeration_Prevented
- testCouponTiming_ConstantTime
- testCouponValidation_NoTimingLeak
- testInvalidCoupon_GenericError
```

### 4.4 Input Sanitization Tests

```
Test Cases:
- testSQLInjection_Prevented
- testXSSInjection_Sanitized
- testPathTraversal_Blocked
- testHeaderInjection_Blocked
- testCSRFToken_Required
- testInputLength_MaxEnforced
```

---

## 5. Performance Tests

### 5.1 Polling Job Performance

```
Test Scenarios:
- 100 creators polling simultaneously
- Target: Complete all within 5 minutes
- Max memory: 512MB
- No thread starvation
- Graceful degradation under rate limits
```

### 5.2 Analytics Dashboard Performance

```
Test Scenarios:
- Dashboard load: < 500ms p50, < 1s p99
- Analytics query (7 days): < 200ms p50
- Analytics query (30 days): < 500ms p50
- Analytics query (90 days): < 1s p50
- Concurrent users: 100 simultaneous
```

### 5.3 Coupon Generation Performance

```
Test Scenarios:
- Single coupon: < 50ms
- Batch (100 coupons): < 2s
- Validation: < 20ms
- Redemption: < 100ms
```

---

## 6. Frontend Tests (for Ananya)

### 6.1 Component Unit Tests (Jest + React Testing Library)

```
Components to Test:
- AnalyticsDashboard.test.tsx
- MetricsChart.test.tsx
- DateRangePicker.test.tsx
- CampaignTable.test.tsx
- CouponGenerator.test.tsx
- ConversionFunnel.test.tsx
- CreatorScoreCard.test.tsx
- UTMLinkBuilder.test.tsx
```

### 6.2 Chart Rendering Tests

```
Test Cases:
- testChart_RendersWithData
- testChart_EmptyState_ShowsMessage
- testChart_Loading_ShowsSkeleton
- testChart_Error_ShowsRetry
- testChart_Tooltip_DisplaysCorrectly
- testChart_Legend_Toggleable
- testChart_Responsive_ResizesCorrectly
```

### 6.3 Accessibility Tests (axe-core)

```
WCAG AA Requirements:
- Color contrast ratio >= 4.5:1
- Focus indicators visible
- Keyboard navigation complete
- Screen reader labels present
- Error messages associated with inputs
- Charts have text alternatives
```

### 6.4 Responsive Layout Tests

```
Breakpoints to Test:
- Mobile: 320px, 375px, 414px
- Tablet: 768px, 1024px
- Desktop: 1280px, 1440px, 1920px
```

---

## 7. End-to-End Tests (Playwright)

### 7.1 Instagram Connection Flow

```
Scenario: Brand connects Instagram and sees analytics
Given: Brand is logged into Influora
When: Brand clicks "Connect Instagram"
And: Brand authorizes on Meta
Then: Redirect back to Influora
And: Creator profile shows connected
And: Analytics start appearing within 1 hour
```

### 7.2 Campaign Creation Flow

```
Scenario: Brand creates campaign with tracking
Given: Brand is on campaign creation page
When: Brand fills campaign details
And: Brand selects creators
And: Brand enables UTM tracking
And: Brand enables coupon codes
Then: Campaign is created
And: UTM links are generated for each creator
And: Coupon codes are generated for each creator
And: Creators receive notification
```

### 7.3 Conversion Tracking Flow

```
Scenario: Full conversion funnel
Given: Campaign with UTM link exists
When: User clicks creator's UTM link
Then: Click is recorded
When: User browses product page
Then: Visit is recorded
When: User adds to cart
Then: Event is recorded
When: User applies coupon at checkout
Then: Coupon validated
When: User completes purchase
Then: Conversion recorded with correct attribution
And: Brand dashboard updates
```

---

## 8. Test Data

### 8.1 Mock Meta API Responses

**Location:** `src/test/resources/meta-api-mocks/`

```
Files:
- insights_success.json - Normal response with all metrics
- insights_partial.json - Some metrics missing
- insights_empty.json - No data for period
- insights_rate_limited.json - 429 response
- insights_unauthorized.json - 401 response
- insights_server_error.json - 500 response
- oauth_token_success.json - Valid token response
- oauth_token_expired.json - Expired token response
- oauth_refresh_success.json - Token refresh response
```

### 8.2 Seed Creators

**Location:** `src/test/resources/seed-data/creators.json`

```
Creator Types:
- Verified real influencer (high engagement, organic growth)
- Known fake pattern (bulk followers, no engagement)
- Mixed audience (some fake, some real)
- New creator (limited history)
- Inactive creator (no recent posts)
- High volume creator (many posts/day)
```

### 8.3 Test Campaigns

**Location:** `src/test/resources/seed-data/campaigns.json`

```
Campaign Types:
- Active campaign with conversions
- Completed campaign with full data
- Campaign with no conversions
- Campaign with high redemption rate
- Campaign with suspicious activity
```

---

## 9. CI/CD Gates

### 9.1 Pre-Merge Requirements

| Gate | Threshold | Blocking |
|------|-----------|----------|
| Unit tests | 100% pass | Yes |
| Integration tests | 100% pass | Yes |
| Coverage (new code) | >= 80% | Yes |
| Coverage (overall) | >= 60% | No (warning) |
| Security scan | No critical/high | Yes |
| Lint errors | 0 | Yes |
| Type errors | 0 | Yes |

### 9.2 CI Pipeline Steps

```yaml
stages:
  - lint
  - unit-test
  - integration-test
  - security-scan
  - coverage-report
  - performance-test (main branch only)
```

### 9.3 Coverage Reporting

- Jacoco reports uploaded to SonarQube
- Coverage diff shown in PR comments
- Coverage badges in README
- Historical coverage trend tracked

### 9.4 Performance Regression Alerts

- Benchmark tests run nightly
- Alert if p99 increases > 20%
- Alert if memory usage increases > 10%
- Results posted to #engineering-alerts Slack

---

## 10. QA Sign-off Checklist

### Pre-Production Checklist

Before any feature ships to production, all items must be checked:

#### Testing
- [ ] All unit tests passing
- [ ] All integration tests passing
- [ ] All E2E tests passing
- [ ] Coverage threshold met (80%+ new code)

#### Security (Kabir Approval Required)
- [ ] Security tests passing
- [ ] No new vulnerabilities introduced
- [ ] Token handling reviewed
- [ ] Access control verified
- [ ] Input sanitization confirmed

#### Performance
- [ ] Performance benchmarks met
- [ ] No memory leaks detected
- [ ] Database queries optimized
- [ ] Cache hit rates acceptable

#### Frontend Quality
- [ ] No console errors
- [ ] No network errors (except handled)
- [ ] Charts render correctly
- [ ] Loading states implemented
- [ ] Error states implemented

#### Accessibility
- [ ] WCAG AA audit passed (axe-core)
- [ ] Keyboard navigation tested
- [ ] Screen reader tested
- [ ] Color contrast verified

#### Manual Testing
- [ ] Smoke test on staging environment
- [ ] Happy path verified
- [ ] Edge cases tested
- [ ] Error scenarios tested
- [ ] Cross-browser tested (Chrome, Firefox, Safari)
- [ ] Mobile responsive verified

#### Documentation
- [ ] API documentation updated
- [ ] Test cases documented
- [ ] Known issues documented

---

## Appendix A: Test File Locations

```
influora-api/
├── src/test/java/com/influora/
│   ├── meta/
│   │   ├── MetaGraphApiClientTest.java
│   │   ├── InstagramInsightsClientTest.java
│   │   └── OAuthTokenServiceTest.java
│   ├── scoring/
│   │   ├── FakeFollowerDetectionServiceTest.java
│   │   ├── QualityScoreServiceTest.java
│   │   ├── BrandSafetyScoreServiceTest.java
│   │   └── RateEstimationServiceTest.java
│   ├── tracking/
│   │   ├── CouponCodeServiceTest.java
│   │   ├── RedemptionServiceTest.java
│   │   └── ConversionTrackingServiceTest.java
│   ├── jobs/
│   │   ├── MetricsPollingJobTest.java
│   │   └── ScoreCalculationJobTest.java
│   └── integration/
│       ├── MetaAnalyticsControllerIntegrationTest.java
│       ├── CampaignTrackingControllerIntegrationTest.java
│       └── TimescaleDBIntegrationTest.java
└── src/test/resources/
    ├── meta-api-mocks/
    └── seed-data/
```

---

## Appendix B: Test Dependencies

```xml
<!-- pom.xml additions -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.github.tomakehurst</groupId>
    <artifactId>wiremock-jre8</artifactId>
    <scope>test</scope>
</dependency>
```

---

**Document Control:**
- Created: 2026-07-06 by Kavya
- Review Required: Arjun (Engineering Lead), Kabir (Security)
- Next Review: Before sprint planning

---

# ADDENDUM: New Test Requirements (2026-07-06 Update)

---

## 11. Unique Coupon Tests

```java
@Test
void shouldGenerateUniqueCouponPerCreator() {
    var campaign = createCampaign("SUMMER25");
    var riya = createCreator("riya");
    var priya = createCreator("priya");
    
    var coupon1 = couponService.addCreatorToCampaign(campaign.getId(), riya.getId());
    var coupon2 = couponService.addCreatorToCampaign(campaign.getId(), priya.getId());
    
    assertThat(coupon1.getCode()).isEqualTo("RIYA_SUMMER25");
    assertThat(coupon2.getCode()).isEqualTo("PRIYA_SUMMER25");
    assertThat(coupon1.getCode()).isNotEqualTo(coupon2.getCode());
}

@Test
void shouldAttributeRedemptionToCorrectCreator() {
    // Setup: Riya and Priya both in same campaign with unique codes
    // When: Customer uses RIYA_SUMMER25
    // Then: Redemption attributed to Riya, not Priya
}
```

---

## 12. Shopify Integration Tests

```java
@Test
void shouldVerifyShopifyHmacSignature() {
    var rawBody = "{\"id\":123}";
    var validHmac = generateHmac(rawBody, webhookSecret);
    var invalidHmac = "invalid";
    
    assertThat(verifier.verify(rawBody, validHmac, webhookSecret)).isTrue();
    assertThat(verifier.verify(rawBody, invalidHmac, webhookSecret)).isFalse();
}

@Test
void shouldRejectWebhookWithInvalidSignature() {
    mockMvc.perform(post("/webhooks/shopify/redemption")
            .header("X-Shopify-Hmac-SHA256", "invalid")
            .content(orderJson))
        .andExpect(status().isUnauthorized());
}

@Test
void shouldProcessValidShopifyWebhook() {
    var orderJson = loadTestOrder();
    var hmac = generateHmac(orderJson, webhookSecret);
    
    mockMvc.perform(post("/webhooks/shopify/redemption")
            .header("X-Shopify-Hmac-SHA256", hmac)
            .content(orderJson))
        .andExpect(status().isOk());
    
    // Verify redemption recorded
    assertThat(redemptionRepo.findByOrderId("ORD-123")).isPresent();
}
```

---

## 13. Affiliate Campaign Tests

```java
@Test
void shouldCalculateCommissionCorrectly() {
    var campaign = createAffiliateCampaign(commissionPercent: 15);
    var redemption = createRedemption(orderTotal: 1000);
    
    affiliateService.recordAffiliateEarning(redemption);
    
    var earning = earningRepo.findByRedemptionId(redemption.getId());
    assertThat(earning.getCommissionAmount()).isEqualByComparingTo("150.00");
}

@Test
void shouldApplyCommissionCap() {
    var campaign = createAffiliateCampaign(commissionPercent: 20, commissionCap: 100);
    var redemption = createRedemption(orderTotal: 1000);  // Would be 200 without cap
    
    affiliateService.recordAffiliateEarning(redemption);
    
    var earning = earningRepo.findByRedemptionId(redemption.getId());
    assertThat(earning.getCommissionAmount()).isEqualByComparingTo("100.00");  // Capped
}

@Test
void shouldCreateMonthlySettlement() {
    // Setup: 10 earnings for creator in July
    // When: Settlement job runs on Aug 1
    // Then: Single settlement created with correct totals
}

@Test
void shouldPreventDoubleSettlement() {
    // Setup: Settlement already exists for July
    // When: Job runs again
    // Then: No duplicate settlement, existing earnings not re-processed
}
```

---

## 14. Integration Health Tests

```java
@Test
void shouldBlockSaleCampaignWithoutIntegration() {
    // No integration configured for workspace
    
    var request = CreateCampaignRequest.builder()
        .type(CampaignType.SALE)
        .hasCoupons(true)
        .build();
    
    assertThrows(ApiException.class, () -> 
        campaignService.createCampaign(request, workspaceId));
}

@Test
void shouldAllowSaleCampaignWithIntegration() {
    // Configure Shopify integration
    setupShopifyIntegration(workspaceId);
    
    var request = CreateCampaignRequest.builder()
        .type(CampaignType.SALE)
        .hasCoupons(true)
        .build();
    
    var campaign = campaignService.createCampaign(request, workspaceId);
    assertThat(campaign).isNotNull();
}
```

---

## 15. End-to-End: Full Affiliate Flow

```java
@Test
void fullAffiliateFlowE2E() {
    // 1. Brand creates affiliate campaign (15% commission)
    var campaign = createAffiliateCampaign();
    
    // 2. Creator joins, gets unique coupon
    var coupon = addCreatorToCampaign(campaign, creator);
    assertThat(coupon.getCode()).startsWith(creator.getSlug().toUpperCase());
    
    // 3. Shopify webhook fires (order with coupon)
    simulateShopifyOrder(coupon.getCode(), orderTotal: 1000);
    
    // 4. Verify redemption recorded
    var redemption = redemptionRepo.findByCode(coupon.getCode());
    assertThat(redemption).isPresent();
    
    // 5. Verify affiliate earning created
    var earning = earningRepo.findByRedemptionId(redemption.get().getId());
    assertThat(earning.getCommissionAmount()).isEqualByComparingTo("150.00");
    
    // 6. Run monthly settlement
    settlementJob.processMonthlySettlements();
    
    // 7. Verify settlement created
    var settlement = settlementRepo.findByCreatorIdAndPeriod(creator.getId(), lastMonth);
    assertThat(settlement.getTotalCommission()).isEqualByComparingTo("150.00");
}
```

---

## QA Sign-off Additions

Before affiliate feature ships:

- [ ] Unique coupon generation tested (100+ creators)
- [ ] Shopify webhook HMAC verification working
- [ ] WooCommerce webhook working
- [ ] Commission calculation accurate
- [ ] Commission cap enforced
- [ ] Monthly settlement job tested
- [ ] No double-settlement possible
- [ ] Integration health check blocks campaigns correctly
- [ ] Kabir security sign-off on affiliate payouts

---

**End of Addendum**

---

## 16. Creator Campaign Browse/Apply Tests (Task #7 / #12)

**Added:** 2026-07-09 by Kavya (Task #12 QA)  
**Scope:** `CreatorCampaignController`, `CreatorCampaignService`, `CampaignSpecs` browse specs  
**Reference:** `wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md` §7

### 16.1 `CreatorCampaignServiceTest.java` (implemented — 12 tests)

```
Test Cases (implemented):
- testApplyHappyPath — saves Collaboration once, status=APPLIED, source=APPLICATION
- testApplySequentialDuplicateRejected — existsBy true → 409 ALREADY_APPLIED, never saves
- testApplyConcurrentRaceLoserGetsFriendly409 — DataIntegrityViolationException → 409, not 500
- testApplyRejectsNonActiveCampaign — PAUSED → 409 CAMPAIGN_NOT_OPEN
- testApplyRejectsPastDeadline — deadline yesterday → 409 APPLICATION_DEADLINE_PASSED
- testApplyRejectsDraftCampaignAsNotFound — DRAFT → 404 CAMPAIGN_NOT_FOUND
- testApplyRejectsPrivateCampaignWithoutInvitationAsNotFound — isPrivate, no collab → 404
- testApplyUnknownCampaignNotFound — missing id → 404
- testGetDetailHappyPath — brand summary, applicationStatus null pre-apply
- testGetDetailPrivateCampaignVisibleWhenInvited — invited private → INVITED status
- testBrowseReturnsPagedResultsWithApplicationStatus — maps APPLIED from existing collab
- testBrowseFiltersByPlatformInMemory — platform match/miss; page-only total when filtered
```

### 16.2 `CreatorCampaignControllerTest.java` (implemented — 3 tests)

```
Test Cases (implemented):
- testBrowse — GET /creator/campaigns delegates, forwards filter/paging params
- testGet — GET /creator/campaigns/{id} delegates
- testApply — POST /creator/campaigns/{id}/apply returns 201 CREATED
```

### 16.3 Recommended Follow-Up Tests (not yet implemented)

```
CreatorCampaignServiceTest additions:
- testGetDetailRejectsPrivateCampaignWithoutInvitationAsNotFound — symmetry with apply 404
- testBrowseFiltersByNicheInMemory — niche post-filter + page-only total/hasMore=false
- testBrowsePostFilteredHasMoreAlwaysFalse — explicit hasMore assertion when niche/platform active
- testBrowseClampsPageAndLimit — page<1 → 1, limit>100 → 100

CreatorCampaignControllerIntegrationTest (blocked on Testcontainers/Docker debt):
- testBrowseRequiresAuthentication — no JWT → 401
- testBrowseRejectsBrandPrincipal — brand JWT → 403 WRONG_USER_TYPE
- testApplyRejectsOversizedMessage — message > 2000 chars → 400 validation error

Security (Kabir):
- testPrivateCampaignIdEnumeration_Returns404Not403 — no existence leak
- testApplyRateLimit_MaxTenPerHour — spec §7.2 (not implemented yet)
```

### 16.4 Hostile Test Matrix (QA sign-off criteria)

| Scenario | Minimum Expected | Covered By |
|----------|------------------|------------|
| Cross-creator apply | Impossible — identity from JWT only | `CreatorContextService` (Task #11 PASS) |
| Duplicate apply | 409 ALREADY_APPLIED | Service tests (sequential + race) |
| Expired campaign | 409 APPLICATION_DEADLINE_PASSED | Service test |
| Private uninvited | 404 CAMPAIGN_NOT_FOUND | Service test (apply path) |
| Private invited | 200 with INVITED | Service test (detail path) |

---

**QA Sign-off (Task #12):** APPROVED 2026-07-09 — see `wiki/errors/creator-campaign-browse-T12-kavya-qa.md`

---

## 17. Creator Deal Room Chat Live API Tests (Task #14 / #15)

**Added:** 2026-07-09 by Kavya (Task #15 QA)  
**Scope:** `src/pages/creator-chat.tsx` live wiring vs `DealController` (Task #9)  
**Reference:** `TASK_INBOX.md` Task #14 (Ananya ship), Task #15 (Kavya QA)

### 17.1 Frontend Build Gate

```
Test Cases:
- testCreatorChatBuild — npm run build must PASS (esbuild + tsc)
- testCreatorChatNoUndefinedMappers — mapDealToChatRoom, mapDealMessageToTimelineEvent used consistently
- testMockTimelineSyntax — mockTimelineEvents const + mergeMockTimelineEvents function body valid
```

### 17.2 Live API Wiring (`isApiLive() === true`)

```
Test Cases:
- testFetchDealsCallsListCreator — api.deals.list('creator') on mount
- testSelectDealLoadsMessages — api.messages.list('creator', dealId)
- testSelectDealMarksRead — api.messages.markRead('creator', dealId); unreadCount zeroed locally
- testSendMessageAppendsEvent — api.messages.send + timeline append
- testAcceptProposalRefreshes — api.deals.accept → fetchDeals + loadMessages
- testRejectProposalClearsSelection — api.deals.reject → fetchDeals; ?deal removed from URL
- testCounterFormSubmits — api.deals.counter({ amount, message }) → refresh
- testDealsErrorShowsRetry — ApiError → Alert + fetchDeals retry button
- testMessagesErrorShowsRetry — ApiError → Alert + loadMessages retry button
- testEmptyDealsCopy — no deals, no error → centered empty state
- testEmptyMessagesCopy — loaded deal, zero messages → "No messages yet"
```

### 17.3 Mock Mode (`!isApiLive()`)

```
Test Cases:
- testMockModeSkipsNetworkList — fetchDeals sets mockDealRooms without api.deals.list
- testMockModeSkipsNetworkMessages — loadMessages uses mergeMockTimelineEvents
- testMockModePersistedSend — addPersistedMessage + merge on send
- testMockModeInitialState — pre-selects mock deal when ?deal= present
```

### 17.4 Backend Contract Alignment (DealController #9)

```
Cross-check matrix:
- GET  /deals                    ↔ api.deals.list(role, status)
- GET  /deals/:id/messages       ↔ api.messages.list(role, dealId)
- POST /deals/:id/messages       ↔ api.messages.send(role, dealId, content)
- POST /deals/:id/messages/read  ↔ api.messages.markRead(role, dealId)
- POST /deals/:id/accept         ↔ api.deals.accept(id)
- POST /deals/:id/reject         ↔ api.deals.reject(id)
- POST /deals/:id/counter        ↔ api.deals.counter(id, payload)

Access isolation: creator JWT only — no client-supplied user id (Task #13 PASS, unchanged)
```

### 17.5 Recommended Follow-Up Tests (not yet implemented)

```
creator-chat.test.tsx (RTL):
- testProposalActionsVisibleWhenMetadataPending — status === 'pending' shows Accept/Counter/Decline
- testSendingMessageDisablesButton — sendingMessage guard
- testCounterDialogDoubleSubmitGuard — M-1 fix verification

Integration (blocked on Testcontainers / E2E harness):
- testLiveProposalAcceptEndToEnd — creator session → accept → status TERMS_AGREED in list
- testForeignDealMessages403 — IDOR negative (backend DealServiceTest covers; FE should surface error)

Metadata alignment (Vikram + Ananya):
- testCreatorCounterRendersCounterCard — requires proposalType: 'counter' in API metadata OR sender-aware UI
```

### 17.6 Hostile Test Matrix (QA sign-off criteria)

| Scenario | Minimum Expected | Task #15 Status |
|----------|------------------|-----------------|
| Build compiles | `npm run build` PASS | ✅ PASS (4587 modules, re-QA) |
| Mapper symbols resolve | tsc clean on creator-chat | ✅ PASS (H-2 resolved) |
| Mock only when `!isApiLive()` | No mock data seed in live init | ✅ Code review PASS |
| Deal list error recovery | Alert + retry | ✅ Code review PASS |
| Message error recovery | Alert + retry | ✅ Code review PASS |
| markRead on open | POST read + unread badge clear | ✅ Code review PASS |
| Creator counter UX | Counter card / correct alignment | ⚠️ M-2 metadata gap |

---

**QA Sign-off (Task #15):** ✅ **APPROVED** 2026-07-09 ~14:30 IST (re-review) — see `wiki/errors/creator-chat-T15-kavya-qa.md`

---

## 18. Platform Fee Transparency Tests (Tasks #26 / #27 / #31)

**Added:** 2026-07-09 by Kavya (Kv2 batch)  
**Scope:** `PlatformFeeService`, `CreatorPlatformFeeController`, `creator-wallet.tsx` fee transparency card  
**Reference:** `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §4 P0-V1/V2; `10_CREATOR_PAYMENTS_SPEC.md` §1A/§7A; `wiki/errors/creator-platform-fee-T26-kavya-qa.md`, `wiki/errors/creator-platform-fee-T27-kavya-qa.md`, `wiki/errors/creator-wallet-fee-T31-kavya-qa.md`

### 18.1 `PlatformFeeServiceTest.java` (implemented — 6 tests)

```
Test Cases (implemented):
- testDeductAtReleaseAppliesFifteenPercent — 1500 bps on ₹10,000 → ₹1,500 fee, ₹8,500 net
- testDeductAtReleaseFeeLandsInPlatformLedger — PLATFORM_FEE leg to revenue wallet; milestone reference
- testDeductAtReleaseNoDirectBalanceMutation — only WalletLedgerService.post; no entity balance writes
- testDeductAtReleaseReadsFeeFromDbConfig — stub 1200 bps; math follows DB not Java constant
- testDeductAtReleaseSkipsPostingWhenFeeIsZero — zero-fee path skips ledger post
- testSplitMath — split() helper correctness
```

### 18.2 `EscrowServiceReleaseTest.java` (implemented — 2 tests)

```
Test Cases (implemented):
- testReleaseDeductsFeeBeforeCreatorCredit — deductAtRelease invoked before creator ESCROW_RELEASE credit
- testConfirmFundedDoesNotDeductPlatformFee — funding path never calls deductAtRelease
```

### 18.3 `CreatorPlatformFeeServiceTest.java` + `CreatorPlatformFeeControllerTest.java` (implemented — 3 tests)

```
Test Cases (implemented):
- testGetCurrentFeeReturnsGlobalConfig — feeBps/feePercent/source from resolveCreatorFeeBps()
- testResponseContainsNoPii — only feeBps, feePercent, source in response
- testGetCurrentFee — GET /creator/platform-fee delegates; 200 + ApiResponse envelope
```

### 18.4 Frontend Fee Transparency (`creator-wallet.tsx` — Task #31)

```
Test Cases (code review + build gate):
- testPlatformFeeFromApiNotHardcoded — no literal 15% in JSX; formatFeePercentLabel(platformFee.feePercent)
- testWalletPlatformFeeLivePath — api.wallet.platformFee() → GET /creator/platform-fee role=creator
- testTransparencyCardLoading — Skeleton while platformFeeLoading
- testTransparencyCardErrorRetry — destructive Alert + fetchPlatformFee retry (live only)
- testPayoutBreakdownDynamicFeeLabel — Platform Fee ({formatFeePercentLabel(...)}) in payout dialog
- testCreatorWalletBuild — npm run build PASS (4597 modules)
```

### 18.5 Recommended Follow-Up Tests (not yet implemented)

```
PlatformFeeServiceTest additions:
- testDeductAtReleaseIdempotentReplay — release-fee:{escrowHoldId} replay does not double-debit (L-T26-1)

CreatorPlatformFeeControllerIntegrationTest (blocked on Testcontainers):
- testGetPlatformFeeRequiresAuthentication — no JWT → 401
- testGetPlatformFeeRejectsBrandJwt — brand JWT → 403 WRONG_USER_TYPE

creator-wallet.test.tsx (RTL):
- testPayoutDialogFeeLabelBeforeFetchComplete — L-31-3: brief 0% if dialog opened before fee resolves
```

### 18.6 Hostile Test Matrix (QA sign-off criteria)

| Scenario | Minimum Expected | Covered By |
|----------|------------------|------------|
| Fee at release only, not funding | No deductAtRelease on confirmFunded | EscrowServiceReleaseTest |
| Fee from DB config | resolveCreatorFeeBps() → platform_fee_config | PlatformFeeServiceTest |
| Ledger traceability | PLATFORM_FEE → revenue wallet | PlatformFeeServiceTest |
| No direct balance mutation | WalletLedgerService.post only | PlatformFeeServiceTest |
| Creator-only read endpoint | requireCreatorProfile; no path params | CreatorPlatformFeeServiceTest |
| UI matches backend rate | wallet.platformFee() not hardcoded % | creator-wallet.tsx review (T31) |

---

**QA Sign-off (Tasks #26/#27/#31):** ✅ **APPROVED** 2026-07-09 — see `wiki/errors/creator-platform-fee-T26-kavya-qa.md`, `wiki/errors/creator-platform-fee-T27-kavya-qa.md`, `wiki/errors/creator-wallet-fee-T31-kavya-qa.md`

---

## 19. Creator Coupon Read Tests (Tasks #28 / #32)

**Added:** 2026-07-09 by Kavya (Kv2 batch)  
**Scope:** `CreatorCouponController`, `CreatorCouponService`, `creator-coupons.tsx` live wire  
**Reference:** `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §4 P0-V3; `wiki/errors/creator-coupons-T28-kavya-qa.md`, `wiki/errors/creator-coupons-T32-kavya-qa.md`

### 19.1 `CreatorCouponServiceTest.java` (implemented — 4 tests)

```
Test Cases (implemented):
- testCrossCreatorIsolation — findByCreatorId keyed on authenticated profile only; never queries foreign profile
- testListHappyPathEnrichment — campaign title, workspace name, trackingUrl from scoped UTM lookup
- testListEmpty — empty coupon set; skips campaign/workspace batch fetch
- testListWithoutTrackingUrl — no UTM row → trackingUrl null/empty; no cross-creator bleed
```

### 19.2 `CreatorCouponControllerTest.java` (implemented — 1 test)

```
Test Cases (implemented):
- testList — GET /creator/coupons delegates to service; 200 + list envelope
```

### 19.3 Frontend Coupon Live Wire (`creator-coupons.tsx` — Task #32)

```
Test Cases (code review + build gate):
- testCreatorCouponsLivePath — api.creatorCoupons.list() → GET /creator/coupons role=creator
- testLoadingSpinner — Loader2 while hook loading
- testErrorAlertWithRetry — destructive Alert + refresh() button
- testEmptyState — EmptyState when data.length === 0 && !error
- testMockModeIllustrativeRows — mockOr(MOCK_CREATOR_COUPONS) when !isApiLive()
- testNotImplementedBannerRemoved — no amber gap banner post-T28
- testCreatorCouponsBuild — npm run build PASS
```

### 19.4 Recommended Follow-Up Tests (not yet implemented)

```
CreatorCouponControllerIntegrationTest (blocked on Testcontainers):
- testListRequiresAuthentication — no JWT → 401
- testListRejectsBrandJwt — brand JWT → 403 WRONG_USER_TYPE
- testListPaginationCap — unbounded list acceptable MVP (L-T28-1); document if/when paginated

useCreatorCoupons.test.ts (RTL):
- testRetryClearsErrorAndReloads — refresh() state machine
```

### 19.5 Hostile Test Matrix (QA sign-off criteria)

| Scenario | Minimum Expected | Covered By |
|----------|------------------|------------|
| Cross-creator coupon isolation | Repository keyed on JWT profile id | CreatorCouponServiceTest |
| Cross-creator UTM isolation | findByCampaignIdAndCreatorProfileId with auth profile | CreatorCouponServiceTest |
| Read-only surface | GET only; no save/delete | Code review |
| Safe enrichment | Batch IDs from scoped rows only | testListHappyPathEnrichment |
| Live wire honest errors | ApiError surfaced; retry available | creator-coupons.tsx (T32) |

---

**QA Sign-off (Tasks #28/#32):** ✅ **APPROVED** 2026-07-09 — see `wiki/errors/creator-coupons-T28-kavya-qa.md`, `wiki/errors/creator-coupons-T32-kavya-qa.md`

---

## 20. Collaboration Reviews Tests (Tasks #29 / #33)

**Added:** 2026-07-09 by Kavya (Kv2 batch)  
**Scope:** `ReviewService`, `CreatorReviewController`, `BrandReviewController`, `creator-reviews.tsx` / `brand-reviews.tsx`  
**Reference:** `wiki/tech/creator/14_CREATOR_REVIEWS_SPEC.md`; `wiki/errors/creator-reviews-T29-kavya-qa.md`, `wiki/errors/creator-reviews-T33-kavya-qa.md`

### 20.1 `ReviewServiceTest.java` (implemented — 12 tests)

```
Test Cases (implemented):
- creatorCreateHappyPath — 201; reviewerType=CREATOR; reviewerUserId from JWT
- creatorCreateRejectsNotCompleted — status ≠ COMPLETED → 409 COLLABORATION_NOT_COMPLETED
- creatorCreateRejectsDuplicate — existsBy → 409 ALREADY_REVIEWED
- creatorCreateRaceDuplicate — DataIntegrityViolationException → 409 ALREADY_REVIEWED
- creatorCreateIdorForeignCollaboration — foreign collab → 404 COLLABORATION_NOT_FOUND
- creatorCreateSanitizesText — TextSanitizer strips HTML in review body
- creatorCreateBlankTextBecomesNull — whitespace/HTML-only → null stored
- brandCreateHappyPath — reviewerType=BRAND
- brandCreateIdorForeignCollaboration — workspace-scoped join → 404
- creatorFlagHappyPath — ContentFlagType.REVIEW; flagId + PENDING status
- creatorFlagIdorForeignReview — foreign review → 404 REVIEW_NOT_FOUND
- brandFlagIdorForeignReview — workspace-scoped flag IDOR → 404
```

### 20.2 Frontend Reviews Write Path (Task #33)

```
Test Cases (code review + build gate):
- testRateableDealsCompletedOnly — deals.list(role, 'completed') + status === 'COMPLETED' filter
- testPostReviewLivePath — POST /creator/reviews | /brand/reviews with collaborationId, stars, text?
- testAlreadyReviewedHandling — ALREADY_REVIEWED → user message + reviewedIds session update
- testCollaborationNotCompletedHandling — COLLABORATION_NOT_COMPLETED → dedicated copy
- testReceivedTabHonestGap — listReceived() NOT_IMPLEMENTED in live; amber banner; no fabricated rows
- testStarRatingAndTextForm — StarRatingInput + Textarea maxLength 1000; stars >= 1 required
- testLoadingErrorEmptyBothTabs — Loader2, destructive Alert + retry, EmptyRateState / EmptyReceivedState
- testReviewsPagesBuild — npm run build PASS
```

### 20.3 Recommended Follow-Up Tests (not yet implemented)

```
ReviewServiceTest additions:
- testFlagRejectsBlankReason — blank reason after sanitize → 400 INVALID_REQUEST

CreatorReviewControllerTest / BrandReviewControllerTest (L-T29-2):
- testCreateDelegates — POST body validation + service delegation
- testFlagDelegates — POST /reviews/{id}/flag

collaboration-reviews-panel.test.tsx (RTL):
- testSubmitDisablesWhileSending — double-submit guard
- testNavLinkToReviewsPages — L-T33-1 carry-forward

Integration (pre-prod hardening — M-T29-1/M-T29-2):
- testReviewWriteRateLimit — creator-review-write / brand-review-write buckets
- testDuplicateFlagSpamGuard — per-user flag idempotency
- testGetReceivedReviews — GET /{role}/reviews/received (P1 future wave per spec §12)
```

### 20.4 Hostile Test Matrix (QA sign-off criteria)

| Scenario | Minimum Expected | Covered By |
|----------|------------------|------------|
| COMPLETED-only gate | Strict equality, not terminal-or-later | ReviewServiceTest |
| No double review | App check + DB unique + race catch | ReviewServiceTest |
| Creator IDOR create/flag | Join-scoped collaboration | ReviewServiceTest |
| Brand IDOR create/flag | Workspace-scoped join | ReviewServiceTest |
| Text sanitization | TextSanitizer on body + flag reason | ReviewServiceTest |
| No anonymous reviews | reviewer_user_id from JWT, NOT NULL | ReviewServiceTest + schema |
| Cross-role endpoints | WRONG_USER_TYPE 403 | CreatorContextService / BrandContextService |
| Live received tab honest | NOT_IMPLEMENTED banner; no fake data | collaboration-reviews-panel (T33) |

---

**QA Sign-off (Tasks #29/#33):** ✅ **APPROVED** 2026-07-09 — see `wiki/errors/creator-reviews-T29-kavya-qa.md`, `wiki/errors/creator-reviews-T33-kavya-qa.md`

---

## 21. Collaboration Disputes Tests (Task #34)

**Added:** 2026-07-09 by Kavya (Kv2 batch)  
**Scope:** `DisputeService`, `DealController.openDispute`, `AdminDisputeController`, `EscrowService.freezeUnreleasedForDispute`  
**Reference:** `wiki/tech/creator/15_CREATOR_DISPUTES_SPEC.md`; `wiki/errors/creator-dispute-T34-kavya-qa.md`

### 21.1 `DisputeServiceTest.java` (implemented — 8 tests)

```
Test Cases (implemented):
- creatorOpenHappyPath — OPEN status; freezeUnreleasedForDispute called; collaboration → DISPUTED
- brandOpenHappyPath — openedByType=BRAND; same freeze + status transition
- creatorOpenIdorForeignDeal — foreign dealId → 404 DEAL_NOT_FOUND
- brandOpenIdorForeignDeal — foreign workspace deal → 404 DEAL_NOT_FOUND
- openRejectsDuplicateActiveDispute — OPEN/UNDER_REVIEW exists → 409 DISPUTE_ALREADY_OPEN
- openRejectsNoFundedEscrow — no FUNDED hold → 409 NO_FUNDED_ESCROW
- adminResolveHappyPath — RESOLVED_CREATOR; resolvedByAdminId + notes persisted
- adminResolveRejectsInvalidResolution — OPEN/UNDER_REVIEW as resolution → 400 INVALID_RESOLUTION
```

### 21.2 `EscrowServiceTest.java` (+1 freeze test)

```
Test Cases (implemented):
- freezeUnreleasedForDisputeMarksFundedHolds — FUNDED → FROZEN for collaboration holds only
```

### 21.3 Recommended Follow-Up Tests (not yet implemented)

```
DisputeServiceTest additions:
- testOpenConcurrentDuplicateRace — TOCTOU two OPEN rows (L-T34-1; Kabir K1)
- testAdminResolveAlreadyResolved — 409 DISPUTE_NOT_ACTIVE
- testAdminResolveForeignDisputeId — 404 DISPUTE_NOT_FOUND
- testOpenSanitizesReason — TextSanitizer on reason; blank → 400
- testResolveSanitizesNotes — notes sanitized; blank → null

AdminDisputeControllerTest (new):
- testResolveRequiresAdminMfa — SUPPORT JWT → 403
- testCreatorJwtCannotResolve — filter-chain 403 on /admin/disputes/**

Integration (Kabir K1 — LOAD-BEARING):
- testConcurrentDisputeOpenVsEscrowRelease — freeze must win or release rejects FROZEN holds
- testReleasedHoldsUntouchedOnOpen — RELEASED holds never modified (CEO §1.3)

DealControllerTest:
- testOpenDisputeDelegates — POST /deals/{dealId}/disputes wiring
```

### 21.4 Hostile Test Matrix (QA sign-off criteria)

| Scenario | Minimum Expected | Task #34 Status |
|----------|------------------|-----------------|
| Either party may open | CREATOR \| BRAND JWT; opener server-derived | ✅ DisputeServiceTest |
| Funded escrow required | NO_FUNDED_ESCROW 409 | ✅ DisputeServiceTest |
| Freeze on open | FUNDED → FROZEN; no auto-refund | ✅ Service + EscrowServiceTest |
| One active dispute | OPEN + UNDER_REVIEW gate | ✅ App layer (race → Kabir K1) |
| Creator/brand IDOR | Uniform 404 DEAL_NOT_FOUND | ✅ DisputeServiceTest |
| Admin-only resolve | SUPER_ADMIN \| ADMIN + MFA | ✅ Code review (thin tests) |
| v1 no money movement | Status stub only on resolve | ✅ Code review |
| DISPUTED blocks reviews | Cross-policy: reviews need COMPLETED | Spec §9 (manual trace) |

---

**QA Sign-off (Task #34):** ⚠️ **APPROVED WITH FINDINGS** 2026-07-09 ~20:45 IST — see `wiki/errors/creator-dispute-T34-kavya-qa.md`. **Kabir K1** dispute-freeze race review pending.

---

## 22. Creator Self Analytics Tests (Task #35)

**Added:** 2026-07-09 by Kavya (Kv2 batch — brief)  
**Scope:** `CreatorAnalyticsController`, `CreatorAnalyticsService` — principal-scoped `GET /creator/analytics/me/*`  
**Reference:** `11_CREATOR_ANALYTICS_SPEC.md`; `wiki/errors/creator-analytics-T35-kabir-redteam.md` (Kabir PASS WITH FINDINGS)

### 22.1 Implemented Unit Tests (6/6 — Meera M2 PASS)

```
CreatorAnalyticsServiceTest (3):
- testGetMyMetricsCrossCreatorIsolation — profile from JWT only; no path-param creatorId
- testGetMyScoresCrossCreatorIsolation — same isolation on scores path
- testGetMyDemographicsCrossCreatorIsolation — same isolation on demographics path

CreatorAnalyticsControllerTest (3):
- testGetMyMetrics — GET /creator/analytics/me/metrics delegates
- testGetMyScores — GET /creator/analytics/me/scores delegates
- testGetMyDemographics — GET /creator/analytics/me/demographics delegates
```

**Meera gate command:**
```bash
cd influora-api && mvn test -Dtest=CreatorAnalyticsServiceTest,CreatorAnalyticsControllerTest
```

### 22.2 Hostile Test Matrix (brief)

| Scenario | Minimum Expected | Status |
|----------|------------------|--------|
| No path-param creatorId | CreatorContextService → findByUserId(JWT sub) | ✅ Service tests |
| Cross-creator isolation | Foreign profile data unreachable | ✅ Service tests |
| Graceful empty demographics | hasData: false when no snapshot | ✅ Kabir code trace |
| Date range parsing | Instant.parse on optional startDate/endDate | ✅ Kabir PASS |

### 22.3 Follow-Up (Ananya A5 + Kv3 E2E)

- Wire `creator-analytics.tsx` to live endpoints (A5 in flight)
- Integration tests for unbounded date window (Kabir Low carry-forward)
- Full E2E creator analytics walkthrough deferred to Kv3

---

**QA Sign-off (Task #35):** ⏳ **Kv2 test-plan only** — per-task QA report pending A5 wire; Kabir IDOR **PASS WITH FINDINGS** (`wiki/errors/creator-analytics-T35-kabir-redteam.md`)

---

## 23. Kv3 Full E2E Kickoff Execution Log (Tick #30)

**Added:** 2026-07-09 by Kavya (Kv3 slice 1)  
**Scope:** `13_CREATOR_QA_SPEC.md` §1–§5 + Week 4 QA Gate mid-journey + §16–§22 reconciliation  
**Report:** `wiki/errors/creator-e2e-Kv3-kickoff.md`

### 23.1 Execution Status (`13_CREATOR_QA_SPEC` §1–§5)

| Section | Kickoff status | Unit/automated | Live E2E |
|---------|----------------|----------------|----------|
| §1 Coverage meta | ⚠️ PARTIAL | 844 `@Test` inventoried; no Jacoco | N/A |
| §2 Auth | ⚠️ PARTIAL | `AuthServiceTest` 4/4 race paths only | BLOCKED (OTP/MSG91) |
| §3 Profile | ⚠️ PARTIAL | `CreatorProfileServiceTest` 5/5 + controller 2/2 | BLOCKED |
| §4 OAuth | ✅ PASS (unit) | Meta suite 40+ tests | BLOCKED (Meta sandbox) |
| §5 Campaign | ✅ PASS | §16 — 15/15 unit (T12) | BLOCKED |

### 23.2 Week 4 QA Gate Checklist (kickoff)

#### Auth (exec plan item 1)
- [x] Creator register duplicate → 409 (unit — `AuthServiceTest`)
- [ ] Signup with email OTP works (live)
- [ ] Signup with phone OTP works (live)
- [ ] Login with email + password works (live)
- [ ] Password reset works (live)
- [ ] JWT refresh works (live)
- [ ] Invalid credentials rejected (live)

#### Profile (exec plan item 2)
- [x] Profile patch validation (unit — `CreatorProfileServiceTest`)
- [x] Controller GET/PATCH delegation (unit — `MeCreatorProfileControllerTest`)
- [ ] Profile editor saves data (live)
- [ ] Portfolio items add/delete (live)
- [ ] OAuth connect works Instagram (live)
- [ ] Profile displays on public page (live)

#### Campaign / Deal (exec plan items 3)
- [x] Campaign browse/apply hostile matrix (unit — §16 T12 APPROVED)
- [x] Deal accept/counter/message IDOR (unit — `DealServiceTest` + `DealControllerTest`)
- [x] Deal-room UI build gate (T15 APPROVED — M-2 counter metadata open)
- [ ] Filters work correctly (live)
- [ ] Counter-offer flow works (live)

#### Contract (exec plan item 4)
- [x] Dual-signature + escrow prompt (unit — `ContractServiceTest` 16/16)
- [x] Creator IDOR on sign/PDF (unit)
- [ ] Contract PDF displays (live)
- [ ] E-sign captures signature (live)
- [ ] Escrow hold triggered (live)

#### Deliverable (exec plan item 5)
- [x] Upload/submit/metrics IDOR + XSS strip (unit — 24/24 + brand 10/10)
- [x] Controller delegation (unit — 5/5)
- [ ] Deliverable upload succeeds (live R2)
- [ ] Brand approve/reject (live)
- [ ] Milestone progress updates (live)

#### Payment (exec plan item 6)
- [x] Wallet balance/withdrawal IDOR (unit — `WalletServiceTest` 19/19)
- [x] Platform fee at release (unit — §18 T26/T27/T31 APPROVED)
- [ ] Withdrawal request succeeds (live gateway)
- [ ] Transaction history accurate (live)
- [ ] Affiliate earnings tracked (live)

#### Analytics (exec plan item 7)
- [x] Creator-self analytics isolation (unit — §22 Meera 6/6)
- [x] A5 page build + live wire (`creator-analytics.tsx`)
- [ ] Dashboard displays data (live)
- [ ] Charts render correctly (live)
- [ ] AI insights load (deferred — wave 2)

### 23.3 §16–§22 Re-execution (kickoff)

- [x] §16 Campaign — inventory + T12 gate reconciled
- [x] §17 Deal chat — T15 gate reconciled (M-2 open)
- [x] §18 Platform fee — T26/T27/T31 reconciled
- [x] §19 Coupons — T28/T32 reconciled
- [x] §20 Reviews — T29/T33 reconciled
- [x] §21 Disputes — T34 reconciled; Meera 19/19 cited; M-T34-1/2 pre-prod
- [x] §22 Analytics — 6/6 unit cited; live walkthrough **pending** Kv3 slice 2

### 23.4 §10 Pre-Production Checklist (Kv3 partial)

#### Testing
- [ ] All unit tests passing — **Meera M-Kv3-1** (`mvn test` full 844)
- [ ] All integration tests passing — BLOCKED (Testcontainers/Docker)
- [ ] All E2E tests passing — ✅ **smoke harness green** (Kv-GA-2); full §11 live journeys still BLOCKED
- [ ] Coverage threshold met (80%+ new code) — **~82% P2 slices / ~68% full E2E checklist** (was ~78% / ~58%; Kv-GA-2 interim ~62%)

#### Frontend Quality
- [x] `npm run build` PASS (4599 modules, Kv3 kickoff)
- [x] Vitest creator FE **33/33** (10 files) + admin suite — mock-pinned via `vitest.config.ts`
- [x] Playwright scaffold + **4/4** smoke PASS (`npm run test:e2e`, mock/demo fail-closed)
- [ ] No console errors (live smoke — pending)
- [ ] Loading/error states implemented — code review PASS on shipped pages

**Kv3 slice 1 verdict:** ⚠️ **IN PROGRESS** — see `wiki/errors/creator-e2e-Kv3-kickoff.md`.  
**Kv-GA-2 update (2026-07-10):** Playwright scaffold + `e2e/creator-journey.spec.ts` **PASS** (mock/demo). E2E checklist **~58% → ~62%**. Disputes FE RTL **10/10** (`creator-disputes.test.tsx`).  
**Kv3b update (2026-07-10):** Expanded creator page Vitest (coupons/analytics/deals + API helpers) + hardened smoke (`creator-dashboard.smoke.spec.ts`). Combined Playwright **4/4**. E2E checklist **~62% → ~68%**. P2 RTL/unit **~82%**. **80% gate NOT MET** (live OTP/Meta/R2 + Jacoco + G-Kv3-1). **NEXT:** Meera `test:e2e` + M-GA-4 → Vikram G-Kv3-1 → staging live pass.
