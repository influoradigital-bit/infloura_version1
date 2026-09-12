package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.service.meera.OnBehalfTokenService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counts the ES256 signature verifications {@link OnBehalfAuthResolver} spends per call, for every
 * one of its four public entry points.
 *
 * <p><b>The defect this exists to prevent.</b> {@code requireScope} used to re-parse the raw token
 * string to read one claim {@link OnBehalfAuthResolver#resolveForWorkspace} had already verified a
 * few lines earlier, so the two scope-checking entry points — which are the ones every creator tool
 * call and every C-tier brand tool call goes through — each paid TWO full curve operations per
 * request where one would do. Measured on the build machine (20k warmed iterations, jjwt, the
 * project's EC keys, a 543-character on-behalf token): ~1.2-1.8 ms per verify, i.e. ~2.4-3.6 ms of
 * pure waste per tool call. The javadoc on {@code requireScope} asserted the opposite ("cheap ...
 * no caching needed"), which is why it survived review; a cost claim that is wrong by a factor that
 * matters is not a comment, it is a defect.
 *
 * <p>This is NOT the unauthenticated verification amplifier in {@link AuthRateLimitFilter} (Kabir,
 * Wave 2 — already fixed, covered by {@code AuthRateLimitFilterCreatorToolBucketTest}). This path
 * sits behind {@link InternalServiceTokenFilter}'s service-mesh gate, so it is reachable only by an
 * authenticated caller: straight waste on the legitimate path, not a DoS vector. The reason to
 * count rather than time it is that the count is the thing the cost is proportional to, and a
 * timing assertion would be flaky.
 *
 * <p>The rejection-ordering tests below are load-bearing too: collapsing two parses into one must
 * not change WHICH code a caller gets, or at which point, for a bad signature, a workspace
 * mismatch, an insufficient role or an insufficient scope. A caller must not be able to tell the
 * difference except by timing.
 */
class OnBehalfAuthResolverVerifyCountTest {

    private static final String WORKSPACE_ID = "ws-count-001";
    private static final String OTHER_WORKSPACE_ID = "ws-count-002";
    private static final String USER_ID = "user-count-001";

    /** An R-tier tool that {@link OnBehalfTokenService#SCOPE_READ_ONLY} does authorize. */
    private static final String READ_TOOL = "show_creators";

    /** A C-tier tool that the read-only scope does NOT authorize. */
    private static final String WRITE_TOOL = "request_payment";

    private SpringJwksKeyService jwksKeyService;
    private CountingOnBehalfTokenService counting;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private OnBehalfAuthResolver resolver;

    /**
     * Counts the calls that actually reach the signature check. Same shape as {@code
     * AuthRateLimitFilterCreatorToolBucketTest#CountingOnBehalfTokenService} — subclass the real
     * service and delegate, so every verification counted is a real curve operation against the
     * real keys, not a mock that would hide the cost being measured.
     */
    private static final class CountingOnBehalfTokenService extends OnBehalfTokenService {
        private final AtomicInteger verifyCalls = new AtomicInteger();

        CountingOnBehalfTokenService(SpringJwksKeyService keyService) {
            super(keyService);
        }

        @Override
        public Claims verify(String token) {
            verifyCalls.incrementAndGet();
            return super.verify(token);
        }

        int verifies() {
            return verifyCalls.get();
        }
    }

    @BeforeEach
    void setUp() {
        JwksSigningKeyProperties jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-onbehalf-count");
        jwksKeyService = new SpringJwksKeyService(jwksProps);
        counting = new CountingOnBehalfTokenService(jwksKeyService);

        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        resolver = new OnBehalfAuthResolver(counting, workspaceMemberRepository);
    }

    /**
     * Builds a real, correctly-signed on-behalf token with an explicit {@code scope} claim (the
     * same hand-built shape {@code OnBehalfAuthResolverTest} uses, because {@link
     * OnBehalfTokenService#mint} always mints its own default scope).
     */
    private String tokenWithScope(String scope) {
        return Jwts.builder()
                .header()
                .keyId(jwksKeyService.kid())
                .and()
                .id("count-test-jti")
                .issuer(OnBehalfTokenService.ISSUER)
                .subject(USER_ID)
                .audience()
                .add(OnBehalfTokenService.ONBEHALF_AUDIENCE)
                .and()
                .claim("workspaceId", WORKSPACE_ID)
                .claim("userType", "BRAND")
                .claim("scope", scope)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(jwksKeyService.signingKey(), Jwts.SIG.ES256)
                .compact();
    }

    private void memberWithRole(MemberRole role) {
        WorkspaceMember member = mock(WorkspaceMember.class);
        when(member.getRole()).thenReturn(role);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(member));
    }

    // -------------------------------------------------------------------------------------------
    // The counts. One verification per call, on every path.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("resolveForWorkspaceRequiringScope verifies the signature EXACTLY ONCE (was twice)")
    void scopePath_verifiesExactlyOnce() {
        String token = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY);

        var ctx = resolver.resolveForWorkspaceRequiringScope(token, WORKSPACE_ID, READ_TOOL);

        assertNotNull(ctx);
        assertEquals(USER_ID, ctx.userId());
        assertEquals(
                1,
                counting.verifies(),
                "the hot path for every creator tool call: resolveForWorkspace verified the token,"
                        + " so requireScope must reuse those Claims instead of re-parsing the raw"
                        + " string (pre-fix this was 2, ~1.2-1.8 ms of wasted curve work each)");
    }

    @Test
    @DisplayName("resolveForWorkspaceRequiringElevatedRoleAndScope verifies EXACTLY ONCE (was twice)")
    void elevatedRoleAndScopePath_verifiesExactlyOnce() {
        memberWithRole(MemberRole.OWNER);
        String token = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY + " " + WRITE_TOOL);

        var ctx = resolver.resolveForWorkspaceRequiringElevatedRoleAndScope(token, WORKSPACE_ID, WRITE_TOOL);

        assertNotNull(ctx);
        assertEquals(
                1,
                counting.verifies(),
                "the C-tier money path: elevated-role resolution verified the token once, so the"
                        + " scope assertion must not buy a second verification (pre-fix this was 2)");
    }

    @Test
    @DisplayName("resolveForWorkspace verifies exactly once — the floor the other paths must not exceed")
    void plainPath_verifiesExactlyOnce() {
        resolver.resolveForWorkspace(tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY), WORKSPACE_ID);

        assertEquals(1, counting.verifies());
    }

    @Test
    @DisplayName("resolveForWorkspaceRequiringElevatedRole verifies exactly once — the role check is a DB read, not crypto")
    void elevatedRolePath_verifiesExactlyOnce() {
        memberWithRole(MemberRole.ADMIN);

        resolver.resolveForWorkspaceRequiringElevatedRole(
                tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY), WORKSPACE_ID);

        assertEquals(
                1,
                counting.verifies(),
                "this variant composes resolveForWorkspace with a membership lookup only — if it"
                        + " ever grows a second parse, the combined elevated+scope variant would"
                        + " start paying three");
    }

    // -------------------------------------------------------------------------------------------
    // Rejection codes and their ORDER must survive the collapse to one parse.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a workspace mismatch still beats the scope check, and still costs one verification")
    void workspaceMismatchStillPrecedesScopeCheck() {
        String token = tokenWithScope("nothing_relevant");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                resolver.resolveForWorkspaceRequiringScope(
                                        token, OTHER_WORKSPACE_ID, READ_TOOL));

        assertEquals("ON_BEHALF_WORKSPACE_MISMATCH", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertEquals(1, counting.verifies());
    }

    @Test
    @DisplayName("an insufficient scope is still 403 ON_BEHALF_SCOPE_INSUFFICIENT, now for one verification")
    void insufficientScopeStillRejectsWithTheSameCode() {
        String token = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> resolver.resolveForWorkspaceRequiringScope(token, WORKSPACE_ID, WRITE_TOOL));

        assertEquals("ON_BEHALF_SCOPE_INSUFFICIENT", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertEquals(
                1,
                counting.verifies(),
                "the rejecting path was paying double too, so a scope-refused call cost more curve"
                        + " work than an accepted one had any reason to");
    }

    @Test
    @DisplayName("the role check still runs BEFORE the scope check: a non-member gets NOT_A_MEMBER, not SCOPE_INSUFFICIENT")
    void roleFailureStillPrecedesScopeFailure() {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.empty());
        // Scope is ALSO insufficient here, so only the ordering decides which code comes out.
        String token = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                resolver.resolveForWorkspaceRequiringElevatedRoleAndScope(
                                        token, WORKSPACE_ID, WRITE_TOOL));

        assertEquals("ON_BEHALF_NOT_A_MEMBER", ex.getCode());
        assertEquals(1, counting.verifies());
    }

    @Test
    @DisplayName("a bad signature is still 401 ON_BEHALF_JWT_INVALID after exactly one verification")
    void badSignatureStillRejectsOnce() {
        String token = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY);
        int lastDot = token.lastIndexOf('.');
        char first = token.charAt(lastDot + 1);
        String corrupted =
                token.substring(0, lastDot + 1) + (first == 'A' ? 'B' : 'A') + token.substring(lastDot + 2);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                resolver.resolveForWorkspaceRequiringScope(
                                        corrupted, WORKSPACE_ID, READ_TOOL));

        assertEquals("ON_BEHALF_JWT_INVALID", ex.getCode());
        assertEquals(401, ex.getStatus().value());
        assertEquals(1, counting.verifies(), "a forgery must not buy a second curve operation either");
    }

    @Test
    @DisplayName("a blank JWT is rejected before any verification at all — zero curve operations")
    void blankTokenCostsNoVerification() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> resolver.resolveForWorkspaceRequiringScope("   ", WORKSPACE_ID, READ_TOOL));

        assertEquals("ON_BEHALF_JWT_MISSING", ex.getCode());
        assertEquals(401, ex.getStatus().value());
        assertEquals(0, counting.verifies());
    }
}
