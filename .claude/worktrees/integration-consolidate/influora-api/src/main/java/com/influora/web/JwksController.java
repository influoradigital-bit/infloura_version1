package com.influora.web;

import com.influora.security.SpringJwksKeyService;
import io.jsonwebtoken.security.JwkSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave E task E-JWKS ({@code wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md}) —
 * the new public, unauthenticated surface this ADR's fix requires.
 *
 * <p><b>This endpoint serves ONLY public key material, nothing else.</b> {@code GET
 * /.well-known/jwks.json} is the standard discovery location (RFC 7517-adjacent convention,
 * mirrors OAuth2/OIDC JWKS endpoints) influora-ai's {@code PyJWKClient}
 * ({@code app/auth/service_token.py::HttpJwksSource}) polls to resolve the signing key for a given
 * {@code kid} on every Spring-issued service/stream token it verifies.
 *
 * <p>Deliberately unauthenticated: JWKS endpoints are public by design (anyone can already derive
 * the public key from any token Spring issues; publishing it directly is not a disclosure) —
 * see {@code SecurityConfig} for the {@code permitAll()} wiring, listed alongside the other
 * public webhook/pixel exceptions per {@code REMAINING_WORK_PLAN.md}'s standing rule that public
 * unscoped endpoints must be justified.
 *
 * <p>{@link SpringJwksKeyService#publicJwkSet()} is built exclusively from the public key — this
 * controller has no access to the private key at all (it only depends on the key service, and the
 * key service never exposes the private key through any JSON-serializable method).
 */
@RestController
public class JwksController {

    private final SpringJwksKeyService jwksKeyService;

    public JwksController(SpringJwksKeyService jwksKeyService) {
        this.jwksKeyService = jwksKeyService;
    }

    @GetMapping("/.well-known/jwks.json")
    public JwkSet jwks() {
        return jwksKeyService.publicJwkSet();
    }
}
