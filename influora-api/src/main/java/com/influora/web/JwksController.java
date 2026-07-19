package com.influora.web;

import com.influora.security.SpringJwksKeyService;
import io.jsonwebtoken.security.Jwk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 *
 * <p><b>Never return the {@code JwkSet}/{@code Jwk} objects themselves from this endpoint.</b>
 * Live-verified: Spring's default Jackson converter throws {@code HttpMessageConversionException:
 * Type definition error: [simple type, class io.jsonwebtoken.impl.lang.RedactedSupplier]} when
 * asked to serialize jjwt's concrete {@code Jwk} implementation directly — some internal field
 * jjwt uses for its own redaction-on-{@code toString()} safeguard isn't a type Jackson's generic
 * bean introspection can handle, independent of the actual (public, safe-to-serve) key values it
 * wraps. This 500'd {@code GET /.well-known/jwks.json} entirely, which cascades into every
 * consumer that resolves a signing key from it — influora-ai's {@code PyJWKClient} verifying a
 * Meera stream token, in particular, fails closed with {@code jwks_lookup_failed} on every real
 * chat turn. {@link Jwk} implements {@code Map<String, Object>}, so copying each entry into a
 * plain {@link LinkedHashMap} (guaranteed to resolve to plain values via the {@code Map} contract,
 * not whatever hidden fields Jackson's reflection found) produces the identical JSON shape the
 * JWKS spec expects, serializable with zero special-casing.
 */
@RestController
public class JwksController {

    private final SpringJwksKeyService jwksKeyService;

    public JwksController(SpringJwksKeyService jwksKeyService) {
        this.jwksKeyService = jwksKeyService;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        List<Map<String, Object>> keys =
                jwksKeyService.publicJwkSet().getKeys().stream()
                        .map(this::toPlainMap)
                        .collect(Collectors.toList());
        return Map.of("keys", keys);
    }

    private Map<String, Object> toPlainMap(Jwk<?> jwk) {
        return new LinkedHashMap<>(jwk);
    }
}
