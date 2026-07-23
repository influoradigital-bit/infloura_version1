package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.influora.common.ApiException;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.config.JwtProperties;
import com.influora.domain.enums.UserType;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.service.meera.OnBehalfTokenService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SECURITY FIX #1 ({@code docs/security/meera-onbehalf-auth-security-design.md} §2/§3): proves
 * {@link OnBehalfAuthResolver} now validates the dedicated per-turn on-behalf token contract
 * (signature against Spring's own ES256 JWKS, {@code iss}, {@code aud=="meera-onbehalf"}, {@code
 * exp}, required {@code workspaceId}) and — the headline regression this fix closes — no longer
 * accepts a full public-API access token (the pre-fix {@code
 * localStorage.getItem('brand_token')} credential) as a valid on-behalf credential.
 */
class OnBehalfAuthResolverTest {

    private static final String WORKSPACE_ID = "ws-001";
    private static final String OTHER_WORKSPACE_ID = "ws-002";
    private static final String USER_ID = "user-001";

    private SpringJwksKeyService jwksKeyService;
    private OnBehalfTokenService onBehalfTokenService;
    private JwtService publicApiJwtService;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private OnBehalfAuthResolver resolver;

    @BeforeEach
    void setUp() {
        JwksSigningKeyProperties jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-onbehalf");
        jwksKeyService = new SpringJwksKeyService(jwksProps);
        onBehalfTokenService = new OnBehalfTokenService(jwksKeyService);

        JwtProperties jwtProps = new JwtProperties();
        jwtProps.setAccessSecret("public-api-access-secret-totally-unrelated-to-jwks-32bytes+");
        jwtProps.setAccessExpirySeconds(900);
        publicApiJwtService = new JwtService(jwtProps);

        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        resolver = new OnBehalfAuthResolver(onBehalfTokenService, workspaceMemberRepository);
    }

    private String validOnBehalfToken() {
        return onBehalfTokenService.mint(WORKSPACE_ID, "conv-001", "turn-001", USER_ID, UserType.BRAND);
    }

    /**
     * Builds a raw on-behalf token with an arbitrary {@code scope} claim -- {@link
     * OnBehalfTokenService#mint} always mints {@link OnBehalfTokenService#SCOPE_READ_ONLY}, so
     * scope-enforcement tests that need a DIFFERENT scope (or a token whose scope covers a write
     * tool) build the token directly, matching the pattern the other hand-built-token tests in
     * this class already use.
     */
    private String tokenWithScope(String scope) {
        return Jwts.builder()
                .header()
                .keyId(jwksKeyService.kid())
                .and()
                .id("scope-test-jti")
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

    @Test
    @DisplayName(
            "SECURITY FIX (Kabir follow-up #1): resolveForWorkspaceRequiringScope accepts a"
                    + " read-scoped token calling a read tool it is scoped for")
    void testScopeCheckAcceptsReadScopedTokenForReadTool() {
        String readScopedToken = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY);

        var ctx =
                resolver.resolveForWorkspaceRequiringScope(readScopedToken, WORKSPACE_ID, "show_creators");

        assertEquals(USER_ID, ctx.userId());
    }

    @Test
    @DisplayName(
            "SECURITY FIX (Kabir follow-up #1) headline case: resolveForWorkspaceRequiringScope"
                    + " REJECTS (403) a read-scoped token calling a WRITE tool -- 'read-only' is now a"
                    + " structural credential property, not just a deploy convention")
    void testScopeCheckRejectsReadScopedTokenForWriteTool() {
        String readScopedToken = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                resolver.resolveForWorkspaceRequiringScope(
                                        readScopedToken, WORKSPACE_ID, "create_campaign"));

        assertEquals("ON_BEHALF_SCOPE_INSUFFICIENT", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName(
            "resolveForWorkspaceRequiringScope accepts a token whose scope DOES include the write"
                    + " tool being called")
    void testScopeCheckAcceptsTokenWhoseScopeIncludesTheWriteTool() {
        String writeScopedToken = tokenWithScope("show_creators calculate_budget create_campaign");

        var ctx =
                resolver.resolveForWorkspaceRequiringScope(writeScopedToken, WORKSPACE_ID, "create_campaign");

        assertEquals(USER_ID, ctx.userId());
    }

    @Test
    @DisplayName("resolveForWorkspaceRequiringScope rejects a token with an empty/missing scope claim")
    void testScopeCheckRejectsMissingScope() {
        String noScopeToken = tokenWithScope("");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                resolver.resolveForWorkspaceRequiringScope(
                                        noScopeToken, WORKSPACE_ID, "show_creators"));

        assertEquals("ON_BEHALF_SCOPE_INSUFFICIENT", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName(
            "resolveForWorkspaceRequiringElevatedRoleAndScope rejects a read-scoped token for a"
                    + " C-tier write tool even when it would otherwise pass the elevated-role check")
    void testScopeCheckRejectsReadScopedTokenForCTierWriteTool() {
        String readScopedToken = tokenWithScope(OnBehalfTokenService.SCOPE_READ_ONLY);
        // Elevated-role membership lookup is never reached: the scope check runs after the role
        // check inside resolveForWorkspaceRequiringElevatedRoleAndScope, so for this assertion to
        // be meaningful the role check must actually pass first.
        var member = mock(com.influora.domain.entity.WorkspaceMember.class);
        org.mockito.Mockito.when(member.getRole()).thenReturn(com.influora.domain.enums.MemberRole.OWNER);
        org.mockito.Mockito.when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(
                        WORKSPACE_ID, USER_ID))
                .thenReturn(java.util.Optional.of(member));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                resolver.resolveForWorkspaceRequiringElevatedRoleAndScope(
                                        readScopedToken, WORKSPACE_ID, "request_payment"));

        assertEquals("ON_BEHALF_SCOPE_INSUFFICIENT", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName("resolveForWorkspace: accepts a well-formed on-behalf token matching the body workspace")
    void testAcceptsValidOnBehalfToken() {
        var ctx = resolver.resolveForWorkspace(validOnBehalfToken(), WORKSPACE_ID);

        assertEquals(USER_ID, ctx.userId());
        assertEquals(WORKSPACE_ID, ctx.workspaceId());
        assertEquals(UserType.BRAND, ctx.userType());
        assertEquals("conv-001", ctx.conversationId());
    }

    @Test
    @DisplayName(
            "BUG FIX (2026-07-23 create_campaign 409): resolveForWorkspace surfaces the JWT's"
                    + " conversationId claim on the returned context -- this is the tenant-safe source"
                    + " tool routes must use instead of an unverified request-body value")
    void testResolveForWorkspaceExposesConversationIdFromJwtClaim() {
        String token =
                onBehalfTokenService.mint(
                        WORKSPACE_ID, "conv-xyz-999", "turn-001", USER_ID, UserType.BRAND);

        var ctx = resolver.resolveForWorkspace(token, WORKSPACE_ID);

        assertEquals("conv-xyz-999", ctx.conversationId());
    }

    @Test
    @DisplayName(
            "SECURITY FIX #1 headline case: a full public-API access token (the pre-fix"
                    + " localStorage.getItem('brand_token') credential) is REJECTED, not accepted, as an"
                    + " on-behalf credential -- it is signed with a different key/algorithm entirely and"
                    + " carries no aud claim")
    void testRejectsFullPublicApiAccessToken() {
        String accessToken =
                publicApiJwtService.createAccessToken(USER_ID, UserType.BRAND, "brand@example.com", WORKSPACE_ID);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> resolver.resolveForWorkspace(accessToken, WORKSPACE_ID));

        assertEquals("ON_BEHALF_JWT_INVALID", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    @DisplayName("resolveForWorkspace: rejects an expired on-behalf token")
    void testRejectsExpiredOnBehalfToken() {
        String expired =
                Jwts.builder()
                        .header()
                        .keyId(jwksKeyService.kid())
                        .and()
                        .id("expired-jti")
                        .issuer(OnBehalfTokenService.ISSUER)
                        .subject(USER_ID)
                        .audience()
                        .add(OnBehalfTokenService.ONBEHALF_AUDIENCE)
                        .and()
                        .claim("workspaceId", WORKSPACE_ID)
                        .claim("userType", "BRAND")
                        .issuedAt(Date.from(Instant.now().minusSeconds(300)))
                        .expiration(Date.from(Instant.now().minusSeconds(180)))
                        .signWith(jwksKeyService.signingKey(), Jwts.SIG.ES256)
                        .compact();

        ApiException ex =
                assertThrows(ApiException.class, () -> resolver.resolveForWorkspace(expired, WORKSPACE_ID));

        assertEquals("ON_BEHALF_JWT_INVALID", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    @DisplayName("resolveForWorkspace: rejects a token with the wrong audience (e.g. a stream token)")
    void testRejectsWrongAudience() {
        var streamTokenService =
                new com.influora.service.meera.StreamTokenService(
                        new com.influora.config.MeeraStreamProperties(), jwksKeyService);
        String streamToken = streamTokenService.mint(WORKSPACE_ID, "conv-001", "turn-001", USER_ID);

        ApiException ex =
                assertThrows(ApiException.class, () -> resolver.resolveForWorkspace(streamToken, WORKSPACE_ID));

        assertEquals("ON_BEHALF_JWT_INVALID", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    @DisplayName("resolveForWorkspace: rejects a token signed with an unrelated keypair (bad signature)")
    void testRejectsBadSignature() {
        JwksSigningKeyProperties wrongProps = new JwksSigningKeyProperties();
        wrongProps.setPrivateKeyPem(TestEcKeys.WRONG_PRIVATE_KEY_PEM);
        wrongProps.setPublicKeyPem(TestEcKeys.WRONG_PUBLIC_KEY_PEM);
        wrongProps.setKid("wrong-kid");
        var wrongKeyService = new SpringJwksKeyService(wrongProps);
        var forgingService = new OnBehalfTokenService(wrongKeyService);

        String forged = forgingService.mint(WORKSPACE_ID, "conv-001", "turn-001", USER_ID, UserType.BRAND);

        ApiException ex =
                assertThrows(ApiException.class, () -> resolver.resolveForWorkspace(forged, WORKSPACE_ID));

        assertEquals("ON_BEHALF_JWT_INVALID", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    @DisplayName("resolveForWorkspace: rejects a token missing the workspaceId claim entirely")
    void testRejectsMissingWorkspaceIdClaim() {
        String noWorkspaceClaim =
                Jwts.builder()
                        .header()
                        .keyId(jwksKeyService.kid())
                        .and()
                        .id("no-ws-jti")
                        .issuer(OnBehalfTokenService.ISSUER)
                        .subject(USER_ID)
                        .audience()
                        .add(OnBehalfTokenService.ONBEHALF_AUDIENCE)
                        .and()
                        .claim("userType", "BRAND")
                        // deliberately no "workspaceId" claim
                        .issuedAt(Date.from(Instant.now()))
                        .expiration(Date.from(Instant.now().plusSeconds(60)))
                        .signWith(jwksKeyService.signingKey(), Jwts.SIG.ES256)
                        .compact();

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> resolver.resolveForWorkspace(noWorkspaceClaim, WORKSPACE_ID));

        assertEquals("ON_BEHALF_WORKSPACE_MISMATCH", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName("resolveForWorkspace: rejects a valid token whose workspaceId does not match the request body")
    void testRejectsWorkspaceMismatch() {
        String token = validOnBehalfToken(); // minted for WORKSPACE_ID

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> resolver.resolveForWorkspace(token, OTHER_WORKSPACE_ID));

        assertEquals("ON_BEHALF_WORKSPACE_MISMATCH", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName("resolveForWorkspace: rejects a null/blank on-behalf JWT before parsing anything")
    void testRejectsMissingToken() {
        ApiException exNull =
                assertThrows(ApiException.class, () -> resolver.resolveForWorkspace(null, WORKSPACE_ID));
        assertEquals("ON_BEHALF_JWT_MISSING", exNull.getCode());

        ApiException exBlank =
                assertThrows(ApiException.class, () -> resolver.resolveForWorkspace("   ", WORKSPACE_ID));
        assertEquals("ON_BEHALF_JWT_MISSING", exBlank.getCode());
    }
}
