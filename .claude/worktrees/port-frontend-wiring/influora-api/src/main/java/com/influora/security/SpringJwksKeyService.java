package com.influora.security;

import com.influora.config.JwksSigningKeyProperties;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.JwkSet;
import java.io.IOException;
import java.io.StringReader;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * Loads Spring's asymmetric (EC/ES256, see {@link JwksSigningKeyProperties} javadoc for the
 * RSA-vs-EC reasoning) service-token signing keypair from config and exposes:
 *
 * <ul>
 *   <li>the {@link PrivateKey}, for {@code BrandSafetyServiceTokenService} / {@code
 *       StreamTokenService} to sign with, and
 *   <li>the PUBLIC key only, wrapped as a JWK, for {@code com.influora.web.JwksController} to
 *       serve at {@code GET /.well-known/jwks.json}.
 * </ul>
 *
 * <p>Private and public PEMs are both supplied via config (generated together as one keypair —
 * see {@link JwksSigningKeyProperties#getPublicKeyPem()} javadoc for why the public key is not
 * derived from the private key at runtime: doing so needs third-party EC point-math, i.e. a new
 * Maven dependency, for something `openssl` already gives us for free at key-generation time).
 *
 * <p>The private key material never leaves this class — {@code JwksController} calls only {@link
 * #publicJwkSet()}, which is built exclusively from the public key. There is no method on this
 * service that returns the private key wrapped in anything JSON-serializable, only the raw {@link
 * PrivateKey} object for in-process signing, so a future refactor of the controller cannot
 * accidentally serve private key material.
 *
 * <p>Boot-time weak/default/missing-key rejection (non-dev) is {@link
 * com.influora.config.SecretsStartupValidator}'s job. This class separately fails fast with an
 * {@link IllegalStateException} on a structurally malformed PEM regardless of env — a key that
 * doesn't even parse is never valid, dev included — matching how every other credential-holding
 * {@code @Service} in this codebase refuses to silently no-op on bad config.
 */
@Service
public class SpringJwksKeyService {

    private final JwksSigningKeyProperties props;
    private final PrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final String kid;

    public SpringJwksKeyService(JwksSigningKeyProperties props) {
        this.props = props;
        this.kid = props.getKid();
        this.privateKey = parsePkcs8EcPrivateKey(props.getPrivateKeyPem());
        this.publicKey = parseX509EcPublicKey(props.getPublicKeyPem());
    }

    /** The EC private key Spring signs {@code BrandSafetyServiceTokenService}/{@code StreamTokenService} tokens with. */
    public PrivateKey signingKey() {
        return privateKey;
    }

    /** The `kid` header value that must accompany every token signed with {@link #signingKey()}. */
    public String kid() {
        return kid;
    }

    /**
     * The raw EC public key, for any in-process (Spring-side) signature verification of a token
     * Spring itself minted — e.g. {@code StreamTokenService#parse}. This is the SAME key material
     * published (wrapped as a JWK) at {@code GET /.well-known/jwks.json}; exposing the raw {@link
     * ECPublicKey} here is not a broader disclosure than the endpoint already is.
     */
    public ECPublicKey publicKey() {
        return publicKey;
    }

    /**
     * The public JWK Set, safe to return verbatim from an unauthenticated endpoint — built
     * exclusively from the public key, never touches {@link #privateKey}.
     */
    public JwkSet publicJwkSet() {
        var jwk =
                Jwks.builder()
                        .key(publicKey)
                        .id(kid)
                        .operations()
                        .add(Jwks.OP.VERIFY)
                        .and()
                        .build();
        return Jwks.set().add(jwk).build();
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    private static PrivateKey parsePkcs8EcPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "influora.jwks.private-key-pem is not configured — Spring cannot sign asymmetric"
                            + " service tokens without it");
        }
        try {
            byte[] der = decodePemBody(pem);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof ECPrivateKey)) {
                throw new IllegalStateException("configured influora.jwks.private-key-pem is not an EC key");
            }
            return key;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IOException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "influora.jwks.private-key-pem is not a valid PKCS#8 EC (P-256) private key PEM", e);
        }
    }

    private static ECPublicKey parseX509EcPublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "influora.jwks.public-key-pem is not configured — Spring cannot serve"
                            + " /.well-known/jwks.json without it");
        }
        try {
            byte[] der = decodePemBody(pem);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey key = keyFactory.generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof ECPublicKey ecKey)) {
                throw new IllegalStateException("configured influora.jwks.public-key-pem is not an EC key");
            }
            return ecKey;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IOException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "influora.jwks.public-key-pem is not a valid X.509 EC (P-256) public key PEM", e);
        }
    }

    /**
     * Strips PEM armor (`-----BEGIN ...-----` / `-----END ...-----`) and base64-decodes the body.
     *
     * <p>Tolerates BOTH real newlines (a PEM file read from disk, or a YAML block-scalar {@code |}
     * value) AND literal two-character {@code \n} escape sequences (a single-line env var value —
     * the only practical way to pass a multi-line PEM through a single environment variable in
     * most secrets-manager/container-orchestration setups, e.g. {@code JWKS_PRIVATE_KEY_PEM="----
     * -BEGIN...\n...\n-----END..."}). Un-escaping {@code \n} before line-splitting means both
     * forms converge on the same parse path.
     */
    private static byte[] decodePemBody(String pem) throws IOException {
        String normalized = pem.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.replace("\\n", "\n");

        StringBuilder base64 = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new StringReader(normalized))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("-----")) {
                    continue;
                }
                base64.append(line);
            }
        }
        if (base64.length() == 0) {
            throw new IOException("no PEM body found");
        }
        return Base64.getDecoder().decode(base64.toString());
    }
}
