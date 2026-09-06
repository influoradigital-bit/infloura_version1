package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * [Kabir M-3] Pins {@link AuthRateLimitFilter#bucketFor} classifying {@code POST
 * /admin/campaigns/&#123;campaignId&#125;/coupons} into the {@code admin-coupon-issue} bucket.
 *
 * <p>Before this, the admin coupon-issuing endpoint (T-FESTIVALBOX-0905 phase 11) fell through
 * {@code bucketFor} unclassified and was throttled not at all. RBAC (SUPER_ADMIN/ADMIN + MFA)
 * decided WHO could call it and nothing decided HOW MANY TIMES — while every call server-mints a
 * live, immediately-redeemable discount code and inserts a {@code coupon_codes} row.
 *
 * <p>Also pins the near-misses that must stay unclassified, so a later "simplify this to an
 * {@code /admin/campaigns/} prefix" refactor cannot quietly drag unrelated admin campaign routes
 * into a coupon throttle, and pins that the bucket is user-keyed with its own window.
 *
 * <p>Reflection over the private {@code bucketFor}, same harness style as the sibling {@code
 * AuthRateLimitFilter*BucketTest} classes.
 */
class AuthRateLimitFilterAdminCouponBucketTest {

    private static final String BUCKET = "admin-coupon-issue";

    private AuthRateLimitFilter filter;
    private Method bucketForMethod;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthRateLimitFilter(null);
        bucketForMethod =
                AuthRateLimitFilter.class.getDeclaredMethod("bucketFor", HttpServletRequest.class);
        bucketForMethod.setAccessible(true);
    }

    private String bucketFor(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        return (String) bucketForMethod.invoke(filter, request);
    }

    @Test
    @DisplayName("[M-3] POST /admin/campaigns/{id}/coupons classifies into admin-coupon-issue")
    void adminCouponIssue_isClassified() throws Exception {
        assertEquals(BUCKET, bucketFor("POST", "/api/v1/admin/campaigns/01HCAMPAIGN01/coupons"));
    }

    @Test
    @DisplayName("[M-3] the bucket does not depend on the campaign id")
    void anyCampaignId_sameBucket() throws Exception {
        assertEquals(BUCKET, bucketFor("POST", "/api/v1/admin/campaigns/other-id-entirely/coupons"));
    }

    @Test
    @DisplayName("[M-3] a percent-encoded path cannot dodge the bucket")
    void percentEncodedPath_stillClassified() throws Exception {
        // bucketFor decodes before matching (Kabir NEW-1). Without that, /c%6Fupons would slip
        // through unclassified and be unthrottled — the same bypass the decode step exists to stop,
        // which is worth re-pinning for each new literal-path bucket rather than assumed.
        assertEquals(BUCKET, bucketFor("POST", "/api/v1/admin/campaigns/01HCAMPAIGN01/c%6Fupons"));
    }

    @Test
    @DisplayName("[M-3] the campaign itself is NOT throttled by this bucket")
    void adminCampaignDetail_isNotClassified() throws Exception {
        assertNull(bucketFor("GET", "/api/v1/admin/campaigns/01HCAMPAIGN01"));
    }

    @Test
    @DisplayName("[M-3] a sibling admin campaign sub-resource is NOT dragged into the bucket")
    void otherAdminCampaignSubresource_isNotClassified() throws Exception {
        assertNull(bucketFor("GET", "/api/v1/admin/campaigns/01HCAMPAIGN01/deliverables"));
        assertNull(bucketFor("POST", "/api/v1/admin/campaigns/01HCAMPAIGN01/approve"));
    }

    @Test
    @DisplayName("[M-3] the brand-facing coupon endpoint is untouched by this admin bucket")
    void brandCampaignCoupons_isNotThisBucket() throws Exception {
        // POST /campaigns/{id}/coupons is the brand's own endpoint. It is a different actor, a
        // different authz path, and out of M-3's scope — this test exists so that adding the admin
        // bucket is not silently mistaken for having throttled the brand one too.
        assertNull(bucketFor("POST", "/api/v1/campaigns/01HCAMPAIGN01/coupons"));
    }

    @Test
    @DisplayName("[M-3] the bucket is user-keyed and has its own non-default window")
    void bucketIsUserKeyedWithOwnWindow() throws Exception {
        Method isUserKeyed =
                AuthRateLimitFilter.class.getDeclaredMethod("isUserKeyedBucket", String.class);
        isUserKeyed.setAccessible(true);
        assertTrue(
                (Boolean) isUserKeyed.invoke(null, BUCKET),
                "must be keyed by admin identity — an IP-keyed bound on a proven identity is"
                        + " resettable by changing IP, and would let one admin throttle the team");

        // @Value fields are all 0 in this plain-constructor harness, so asserting on them as-is
        // would compare 0 to 0 and prove nothing. Setting them to distinguishable values first is
        // what makes the branch assertion real.
        setField("adminCouponIssueWindowSeconds", 3600L);
        setField("windowSeconds", 60L);
        setField("withdrawWindowSeconds", 7200L);

        Method windowFor = AuthRateLimitFilter.class.getDeclaredMethod("windowSecondsFor", String.class);
        windowFor.setAccessible(true);

        assertEquals(
                3600L,
                (long) (Long) windowFor.invoke(filter, BUCKET),
                "must use its own window — the shared 60s default is meaningless for an action"
                        + " nobody performs in bursts");
        assertEquals(
                60L,
                (long) (Long) windowFor.invoke(filter, "sensitive"),
                "the new branch must not have changed every other bucket's window");
        assertEquals(
                7200L,
                (long) (Long) windowFor.invoke(filter, "creator-withdraw"),
                "creator-withdraw must still get ITS own window, not the admin one");
    }

    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = AuthRateLimitFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(filter, value);
    }
}
