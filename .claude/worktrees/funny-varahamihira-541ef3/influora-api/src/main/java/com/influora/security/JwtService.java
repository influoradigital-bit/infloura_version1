package com.influora.security;

import com.influora.config.JwtProperties;
import com.influora.domain.enums.UserType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        this.props = props;
    }

    public String createAccessToken(String userId, UserType userType, String email, String workspaceId) {
        Instant exp = Instant.now().plusSeconds(props.getAccessExpirySeconds());
        var builder =
                Jwts.builder()
                        .id(UUID.randomUUID().toString())
                        .subject(userId)
                        .claim("userType", userType.name())
                        .claim("email", email)
                        .issuedAt(Date.from(Instant.now()))
                        .expiration(Date.from(exp));
        if (workspaceId != null) {
            builder.claim("workspaceId", workspaceId);
        }
        return builder.signWith(accessKey()).compact();
    }

    public String createRefreshTokenValue() {
        return UUID.randomUUID().toString() + "." + UUID.randomUUID();
    }

    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(accessKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessExpirySeconds() {
        return props.getAccessExpirySeconds();
    }

    public long getRefreshExpirySeconds() {
        return props.getRefreshExpirySeconds();
    }

    public static String hashToken(String raw) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private SecretKey accessKey() {
        return Keys.hmacShaKeyFor(props.getAccessSecret().getBytes(StandardCharsets.UTF_8));
    }
}
