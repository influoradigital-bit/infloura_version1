package com.influora.domain.entity;

/**
 * Which Instagram Platform configuration produced a {@link MetaOAuthToken} (T-IGLOGIN-0820).
 *
 * <p>Verified against Meta's Instagram Platform Overview (updated 2026-03-09): the Facebook Page
 * requirement is a property of the CONFIGURATION, not of Instagram. Tokens from the two paths are
 * not interchangeable — they differ in token type, API host, how the Instagram user id is obtained,
 * and how they are refreshed. Anything that talks to Graph must branch on this, never assume.
 */
public enum MetaAuthPath {

    /**
     * Facebook Login for Business. Requires the creator's Instagram professional account to be
     * linked to a Facebook Page on which they can perform admin-equivalent tasks. Host is
     * {@code graph.facebook.com}; the IG Business Account id is resolved via {@code /me/accounts}.
     * Only this path can reach hashtag search, product tagging and Partnership Ads.
     */
    FACEBOOK_LOGIN,

    /**
     * Business Login for Instagram. No Facebook Page required. Host is
     * {@code graph.instagram.com}; the Instagram user id arrives with the token exchange, so
     * {@code FacebookPageClient} must never be called on this path. Cannot access ads or tagging.
     */
    INSTAGRAM_LOGIN
}
