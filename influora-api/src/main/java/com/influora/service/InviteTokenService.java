package com.influora.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — signs and verifies the creator-invite link embedded in
 * {@code creator.join_invitation} ({@code AdminCreatorConnectionService#sendJoinInvitationEmail}).
 * Before this, {@code signup_url} carried a bare {@code ?handle={igUsername}} that {@code
 * creator-register.tsx} never even read — and, worse, a raw handle string is not a claim: nothing
 * stops an attacker from typing a targeted external creator's handle into that URL param by hand
 * (the same class of spoof Q5.4 closed for the profile-username path). A signed, single-use token
 * bound to the specific {@link com.influora.domain.entity.ExternalCreator} row is the correct
 * shape — never a bare handle.
 *
 * <p>Same HMAC-SHA256 / base64url / constant-time-compare shape as {@link
 * com.influora.service.notification.UnsubscribeTokenService}, the established pattern in this
 * codebase for a link a recipient clicks from their inbox with no session to authenticate with.
 *
 * <p><b>No new storage.</b> This fix's file scope does not include a migration, so "single-use" is
 * enforced WITHOUT a dedicated consumed-token table: the token embeds the exact {@code invitedAt}
 * timestamp that was on the row when it was issued. {@code
 * AdminCreatorConnectionService#invite}/re-invite always re-stamps {@code invitedAt} to {@code
 * Instant.now()} ({@code ExternalCreator#markInvited}), so a re-invite silently invalidates every
 * previously-issued token for that row, and {@link #consume} additionally requires the row to
 * still be in {@code INVITED} status — once the JOINED hook flips it (a real Meta connection was
 * made), the token can never link a row again. Together: expiry, re-invite invalidation, and a
 * one-way status transition give single-use semantics off existing columns.
 */
@Service
public class InviteTokenService {

    private static final Logger log = LoggerFactory.getLogger(InviteTokenService.class);

    private static final java.time.Duration TOKEN_TTL = java.time.Duration.ofDays(30);

    /**
     * The literal committed default at :47 below — also referenced by {@code .env.example}. Any
     * deploy that never overrides {@code CREATOR_INVITE_TOKEN_SECRET} is running with this exact
     * value, which is public (it is in git history), so a token signed with it is forgeable by
     * anyone who reads this source.
     */
    static final String DEV_DEFAULT_SECRET =
            "dev-creator-invite-token-secret-change-in-production-min-32-chars";

    private static final int MIN_SECRET_LENGTH = 32;

    private final String secret;

    /**
     * Q5.5 security guard (CTO-ordered). {@link #DEV_DEFAULT_SECRET} is a literal committed to
     * this file, so a prod/staging deploy that forgets to set {@code CREATOR_INVITE_TOKEN_SECRET}
     * (or sets one shorter than {@value #MIN_SECRET_LENGTH} chars) would otherwise still boot and
     * sign tokens with a secret every reader of this repo already has — anyone could forge a
     * creator-invite token and link themselves to an arbitrary {@code external_creators} row (the
     * exact spoof class Q5.4 closed for the profile-username path). Outside the {@code dev}/{@code
     * test} Spring profiles, an insecure secret makes this service fail CLOSED: {@link #verify}
     * rejects every token unconditionally, so {@code RegistrationService#consumeInviteToken} can
     * never link anything until a real secret is configured. Logged once at construction time
     * (this is a singleton bean — one boot, one log line) rather than per-request, so the failure
     * is loud at startup without spamming logs on every register attempt.
     */
    private final boolean failClosed;

    public InviteTokenService(
            @Value(
                            "${CREATOR_INVITE_TOKEN_SECRET:dev-creator-invite-token-secret-change-in-production-min-32-chars}")
                    String secret,
            Environment environment) {
        this.secret = secret;
        boolean insecureSecret = isInsecureSecret(secret);
        boolean devOrTestProfile = environment.acceptsProfiles(Profiles.of("dev", "test"));
        this.failClosed = insecureSecret && !devOrTestProfile;
        if (this.failClosed) {
            log.error(
                    "CREATOR_INVITE_TOKEN_SECRET is the committed dev default (or shorter than {}"
                            + " chars) outside the dev/test Spring profile. Every creator-invite"
                            + " token will be REJECTED until a real, random secret >= {} chars is"
                            + " set via CREATOR_INVITE_TOKEN_SECRET.",
                    MIN_SECRET_LENGTH,
                    MIN_SECRET_LENGTH);
        }
    }

    private static boolean isInsecureSecret(String secret) {
        return secret == null || secret.equals(DEV_DEFAULT_SECRET) || secret.length() < MIN_SECRET_LENGTH;
    }

    /**
     * Issues a token binding {@code externalCreatorId} to the row's CURRENT {@code invitedAt}
     * (already re-stamped by the caller's {@code markInvited} before this is called) and a fixed
     * TTL from now.
     *
     * <p>The embedded {@code invitedAt} is truncated to whole SECONDS before signing. {@code
     * external_creators.invited_at} is a MySQL {@code TIMESTAMP} with no fractional-seconds
     * precision (fsp 0 — see the migration), so it can never round-trip a millisecond value back
     * out of the database; embedding {@code Instant#toEpochMilli()} meant {@link #verify}'s
     * parsed {@code invitedAt} could equal the live row's value only in the roughly 1-in-1000 case
     * where {@code markInvited}'s {@code Instant.now()} happened to land on an exact second —
     * every other legitimately-issued token was rejected in {@code
     * RegistrationService#consumeInviteToken} as "superseded by a re-invite" when it never was.
     * Truncating on both the issue and verify side (mirrored in {@code RegistrationService}'s
     * comparison) keeps the two sides comparing at the same precision the column can actually
     * store, independent of whatever fsp a given DB/test config uses.
     */
    public String issue(String externalCreatorId, Instant invitedAt) {
        long invitedAtSeconds = invitedAt != null ? invitedAt.truncatedTo(ChronoUnit.SECONDS).getEpochSecond() : 0L;
        long expiresAtMillis = Instant.now().plus(TOKEN_TTL).toEpochMilli();
        String payload = externalCreatorId + ":" + invitedAtSeconds + ":" + expiresAtMillis;
        String raw = payload + ":" + hmacHex(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies signature and expiry only — does NOT check the token against the live {@code
     * ExternalCreator} row (status/current {@code invitedAt}). Callers MUST additionally re-check
     * those against the current row before linking (see {@link #issue}'s javadoc); this method
     * alone is not a sufficient authorization decision.
     */
    public Optional<Parsed> verify(String token) {
        if (failClosed) {
            // Q5.5 security guard — see the `failClosed` field javadoc. No signature check is
            // even attempted: an insecure secret means a "valid" signature proves nothing.
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sigSep = raw.lastIndexOf(':');
            if (sigSep < 0) {
                return Optional.empty();
            }
            String payload = raw.substring(0, sigSep);
            String signature = raw.substring(sigSep + 1);
            if (!constantTimeEquals(hmacHex(payload), signature)) {
                return Optional.empty();
            }
            String[] parts = payload.split(":");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String externalCreatorId = parts[0];
            long invitedAtSeconds = Long.parseLong(parts[1]);
            long expiresAtMillis = Long.parseLong(parts[2]);
            if (Instant.now().toEpochMilli() > expiresAtMillis) {
                return Optional.empty();
            }
            return Optional.of(
                    new Parsed(externalCreatorId, Instant.ofEpochSecond(invitedAtSeconds), Instant.ofEpochMilli(expiresAtMillis)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public record Parsed(String externalCreatorId, Instant invitedAt, Instant expiresAt) {}
}
