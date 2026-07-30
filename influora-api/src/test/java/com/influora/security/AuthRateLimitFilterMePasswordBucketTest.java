package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * [SEC: Priya audit, e60d249 follow-up] Pins {@link AuthRateLimitFilter#bucketFor} classifying
 * {@code POST /me/password} into the "sensitive" bucket -- BR-05's in-session password change
 * re-authenticates {@code currentPassword} against a stored BCrypt hash, exactly the
 * brute-force / CPU-exhaustion shape the "sensitive" bucket exists for (login/register/reset
 * share it), and before this fix it fell through {@code bucketFor} unclassified (null) and was
 * throttled not at all.
 *
 * <p>Also pins three near-misses that must stay unclassified (null) — {@code GET /me}, {@code
 * PATCH /me}, {@code DELETE /me/account} — so a future refactor of the exact {@code
 * path.equals("/me/password")} check into a {@code /me/} prefix match can't silently pull
 * unrelated {@code /me/...} routes into the same throttle.
 *
 * <p>Invokes the private {@code bucketFor} method via reflection directly, same harness style as
 * the sibling {@code AuthRateLimitFilter*BucketTest} classes (no {@code @WebMvcTest}/{@code
 * @SpringBootTest} filter harness exists in this codebase), but skips the doFilter/429 machinery
 * since only the classification decision is under test here.
 */
class AuthRateLimitFilterMePasswordBucketTest {

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
    @DisplayName("POST /me/password classifies into the sensitive bucket")
    void mePassword_isSensitiveBucket() throws Exception {
        assertEquals("sensitive", bucketFor("POST", "/api/v1/me/password"));
    }

    @Test
    @DisplayName("GET /me stays unclassified -- exact-match must not catch other /me/... routes")
    void getMe_isNotClassified() throws Exception {
        assertNull(bucketFor("GET", "/api/v1/me"));
    }

    @Test
    @DisplayName("PATCH /me stays unclassified")
    void patchMe_isNotClassified() throws Exception {
        assertNull(bucketFor("PATCH", "/api/v1/me"));
    }

    @Test
    @DisplayName(
            "DELETE /me/account stays unclassified -- must not be pulled into sensitive by a /me/"
                    + " prefix refactor")
    void deleteMeAccount_isNotClassified() throws Exception {
        assertNull(bucketFor("DELETE", "/api/v1/me/account"));
    }
}
