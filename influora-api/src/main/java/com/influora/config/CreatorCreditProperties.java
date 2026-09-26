package com.influora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §2, owner rulings R1-R8) — the flag and every numeric constant of
 * the creator credits system. Plain {@code @Value}-injected {@code @Component}, NOT {@code
 * @ConfigurationProperties} — same documented reasoning as {@link MeeraCreatorFeatureProperties}
 * (a {@code @ConfigurationProperties} class that is not separately registered can fail to
 * construct at all and crash boot for every dependent bean; a {@code @Value} bean needs no such
 * registration step).
 *
 * <p>{@code enabled} defaults {@code false} (R8): with it false, creator Meera is exactly today's
 * behaviour (free, only the pre-existing $3/day USD hard cap) — this is NOT part of the 2026-09-21
 * launch.
 */
@Component
public class CreatorCreditProperties {

    private final boolean enabled;
    private final int turnCost;
    private final int voiceSurcharge;
    private final int briefCost;
    private final int dailyCap;
    private final int welcomeGrant;
    private final int monthlyGrant;
    private final int paidValidityDays;
    private final String zone;
    private final java.math.BigDecimal usdBackstopMonthly;
    private final java.math.BigDecimal usdBriefBackstopMonthly;
    private final int voiceSpeaksPerTurn;
    private final int scriptCost;
    private final int profileReviewCost;

    @org.springframework.beans.factory.annotation.Autowired
    public CreatorCreditProperties(
            @Value("${influora.creator-credits.enabled:false}") boolean enabled,
            @Value("${influora.creator-credits.turn-cost:1}") int turnCost,
            @Value("${influora.creator-credits.voice-surcharge:1}") int voiceSurcharge,
            @Value("${influora.creator-credits.brief-cost:3}") int briefCost,
            @Value("${influora.creator-credits.daily-cap:30}") int dailyCap,
            @Value("${influora.creator-credits.welcome-grant:40}") int welcomeGrant,
            @Value("${influora.creator-credits.monthly-grant:15}") int monthlyGrant,
            @Value("${influora.creator-credits.paid-validity-days:90}") int paidValidityDays,
            @Value("${influora.creator-credits.zone:Asia/Kolkata}") String zone,
            @Value("${influora.creator-credits.usd-backstop-monthly:25.00}") java.math.BigDecimal usdBackstopMonthly,
            @Value("${influora.creator-credits.usd-brief-backstop-monthly:12.00}")
                    java.math.BigDecimal usdBriefBackstopMonthly,
            @Value("${influora.creator-credits.voice-speaks-per-turn:3}") int voiceSpeaksPerTurn,
            @Value("${influora.creator-credits.script-cost:3}") int scriptCost,
            @Value("${influora.creator-credits.profile-review-cost:3}") int profileReviewCost) {
        this.enabled = enabled;
        this.turnCost = turnCost;
        this.voiceSurcharge = voiceSurcharge;
        this.briefCost = briefCost;
        this.dailyCap = dailyCap;
        this.welcomeGrant = welcomeGrant;
        this.monthlyGrant = monthlyGrant;
        this.paidValidityDays = paidValidityDays;
        this.zone = zone;
        this.usdBackstopMonthly = usdBackstopMonthly;
        this.usdBriefBackstopMonthly = usdBriefBackstopMonthly;
        this.voiceSpeaksPerTurn = voiceSpeaksPerTurn;
        this.scriptCost = scriptCost;
        this.profileReviewCost = profileReviewCost;
    }

    /** Pre-2026-09-22 shape (tests and hand-built fixtures): script and profile review at 3. */
    public CreatorCreditProperties(
            boolean enabled,
            int turnCost,
            int voiceSurcharge,
            int briefCost,
            int dailyCap,
            int welcomeGrant,
            int monthlyGrant,
            int paidValidityDays,
            String zone,
            java.math.BigDecimal usdBackstopMonthly,
            java.math.BigDecimal usdBriefBackstopMonthly,
            int voiceSpeaksPerTurn) {
        this(enabled, turnCost, voiceSurcharge, briefCost, dailyCap, welcomeGrant, monthlyGrant, paidValidityDays,
                zone, usdBackstopMonthly, usdBriefBackstopMonthly, voiceSpeaksPerTurn, 3, 3);
    }

    /** "Write a script" button (2026-09-22): credits for one script turn. */
    public int getScriptCost() {
        return scriptCost;
    }

    /** "Review my profile" button (2026-09-22): credits for one profile-review turn. */
    public int getProfileReviewCost() {
        return profileReviewCost;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getTurnCost() {
        return turnCost;
    }

    public int getVoiceSurcharge() {
        return voiceSurcharge;
    }

    /** Total cost of a voice-reply turn — {@link #getTurnCost()} + {@link #getVoiceSurcharge()}. */
    public int getVoiceTurnCost() {
        return turnCost + voiceSurcharge;
    }

    public int getBriefCost() {
        return briefCost;
    }

    public int getDailyCap() {
        return dailyCap;
    }

    public int getWelcomeGrant() {
        return welcomeGrant;
    }

    public int getMonthlyGrant() {
        return monthlyGrant;
    }

    public int getPaidValidityDays() {
        return paidValidityDays;
    }

    public String getZone() {
        return zone;
    }

    public java.time.ZoneId zoneId() {
        return java.time.ZoneId.of(zone);
    }

    public java.math.BigDecimal getUsdBackstopMonthly() {
        return usdBackstopMonthly;
    }

    public java.math.BigDecimal getUsdBriefBackstopMonthly() {
        return usdBriefBackstopMonthly;
    }

    public int getVoiceSpeaksPerTurn() {
        return voiceSpeaksPerTurn;
    }
}
