package com.influora;

import com.influora.config.AdminMfaProperties;
import com.influora.config.AdminSecurityProperties;
import com.influora.config.AnalyzeSiteAiProperties;
import com.influora.config.BrandSafetyAiProperties;
import com.influora.config.BrandSafetyScoringProperties;
import com.influora.config.BrandSafetyServiceTokenProperties;
import com.influora.config.ClamAvProperties;
import com.influora.config.CompanyTaxProperties;
import com.influora.config.ConversionWebhookProperties;
import com.influora.config.CreatorCopilotProperties;
import com.influora.config.CreatorSuggestionAiProperties;
import com.influora.config.InternalServiceTokenProperties;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.config.JwtProperties;
import com.influora.config.MeeraChatAiProperties;
import com.influora.config.MeeraInteractionLogRetentionProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.config.MetaApiProperties;
import com.influora.config.PiiEncryptionProperties;
import com.influora.config.R2Properties;
import com.influora.config.RazorpayProperties;
import com.influora.config.ShopifyProperties;
import com.influora.config.TrendSparkAiProperties;
import com.influora.config.TrendSparkProperties;
import com.influora.config.WalletProperties;
import com.influora.config.WooCommerceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
    R2Properties.class,
    JwtProperties.class,
    MeeraStreamProperties.class,
    RazorpayProperties.class,
    InternalServiceTokenProperties.class,
    MetaApiProperties.class,
    CompanyTaxProperties.class,
    AnalyzeSiteAiProperties.class,
    ClamAvProperties.class,
    // Streaming-first refactor (Priya, Wave 2): MeeraChatAiClient (the synchronous Java->Python
    // /chat caller this property class configured) was retired -- the browser now streams
    // directly from influora-ai's /chat with its own on-behalf JWT. This properties class is
    // kept registered only because SecretsStartupValidator#validateAiServiceUrls still checks
    // its base-url isn't a prod-unsafe localhost default; no runtime code reads it anymore.
    MeeraChatAiProperties.class,
    // Brand-safety scoring feature flag + per-run cap (Priya ruling) — gates whether
    // ScoreCalculationJob calls BrandSafetyScoreService. Injected by @Component
    // ScoreCalculationJob, so it must be registered here like every other properties class below.
    BrandSafetyScoringProperties.class,
    // Wallet business limits (Kabir Option-1 audit P1 must-fix) — max-topup ceiling read by
    // WalletTopUpService#initiateTopUp.
    WalletProperties.class,
    // Creator AI Co-pilot Tier-1 — registered here explicitly (not left for the comment block
    // below to later discover missing) precisely BECAUSE that block documents how easy it is for
    // a @ConfigurationProperties class to go unregistered and silently break boot: CreatorNudgeService
    // and CreatorThemeTaggingJob inject CreatorCopilotProperties, CreatorSuggestionAiClient injects
    // CreatorSuggestionAiProperties — both are live-bean dependencies from day one.
    CreatorCopilotProperties.class,
    CreatorSuggestionAiProperties.class,
    // meera_interaction_log retention purge (Priya's PARTIAL-2 hard gate,
    // wiki/build/partials-resolution-plan.md; Kabir L1, wiki/build/phase2-kabir-security.md) —
    // injected by @Component MeeraInteractionLogRetentionPurgeJob, so it must be registered here
    // like every other properties class in this list.
    MeeraInteractionLogRetentionProperties.class,
    // ---------------------------------------------------------------------
    // Everything below was @ConfigurationProperties but registered NOWHERE: no
    // @EnableConfigurationProperties entry, no @ConfigurationPropertiesScan, no
    // stereotype, no @Bean. Spring therefore could not create them — yet each is
    // constructor-injected by a LIVE bean, so the context could not start. The
    // application has never booted; `mvn` green only ever proved it COMPILED.
    //
    //   BrandSafetyAiProperties           -> @Component BrandSafetyAiClient
    //   TrendSparkAiProperties            -> @Component TrendSparkAiClient
    //   BrandSafetyServiceTokenProperties -> @Service BrandSafetyServiceTokenService,
    //                                        @Configuration SecretsStartupValidator
    //   TrendSparkProperties              -> ContentGapService, TrendSparkNudgeService
    //   PiiEncryptionProperties           -> CreatorBankPiiCipher, EmailPhonePiiCipher
    //   JwksSigningKeyProperties          -> SpringJwksKeyService, SecretsStartupValidator
    //   AdminMfaProperties                -> AdminMfaSecretCipher, SecretsStartupValidator
    //   AdminSecurityProperties           -> AdminAuthService
    //   ShopifyProperties                 -> ShopifyOAuthService, ShopifyTokenStorage
    //   WooCommerceProperties             -> WooCommerce integration
    //   ConversionWebhookProperties       -> (not injected today; registered for hygiene)
    //
    // Why nothing caught it: the ONLY tests that build a real context are the 4
    // @SpringBootTest classes, and every one errors out on testcontainers/Docker
    // discovery here — while unit tests construct these beans by hand with
    // new/Mockito and so can never observe the wiring. That blind spot, not bad
    // luck, is why this survived. ConfigurationPropertiesRegistrationTest now
    // guards this list and needs NO Docker to fail.
    BrandSafetyAiProperties.class,
    TrendSparkAiProperties.class,
    BrandSafetyServiceTokenProperties.class,
    TrendSparkProperties.class,
    PiiEncryptionProperties.class,
    JwksSigningKeyProperties.class,
    AdminMfaProperties.class,
    AdminSecurityProperties.class,
    ShopifyProperties.class,
    WooCommerceProperties.class,
    ConversionWebhookProperties.class
})
// Required for @Scheduled to actually fire (EmailWorker, and Phase 2's MetricsPollingJob) — was
// missing before Phase 2; without it every @Scheduled method in the app is silently inert.
@EnableScheduling
// D2 — every handler in NotificationListener is annotated @Async, but without this,
// @Async is silently inert (Spring just runs the method synchronously on the calling
// thread) rather than erroring — so the domain service that published the event (DealService,
// ContractService, etc.) blocked on notification/email-outbox work as part of its own request
// instead of returning immediately. Was missing the whole time; nothing broke loudly because
// synchronous execution is still *correct*, just serializes notification work onto the
// request path instead of offloading it.
@EnableAsync
public class InfluoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluoraApiApplication.class, args);
    }
}
