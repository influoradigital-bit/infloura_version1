package com.influora.service.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.BrandProfile;
import com.influora.integration.ai.AnalyzeSiteAiClient;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.Data;
import com.influora.repository.BrandProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * C1 (Kabir P1-B audit, condition 1) — {@code price_source} must round-trip from influora-ai's
 * analyze-site result all the way into {@link BrandProfile#getProductCatalogJson()}, and a missing
 * {@code price_source} on any entry must be normalized to {@code "inferred"} (fail safe: unknown
 * provenance is never persisted as trusted).
 *
 * <p>Exercises {@link AnalyzeSiteTriggerService#applyChatResult} (the Meera chat write-back path —
 * simplest entry point that reaches {@code toCallback}/{@code applyCallback} without requiring a
 * real {@link AnalyzeSiteAiClient} call) against a real {@link BrandProfile} entity, with a minimal
 * no-op {@link PlatformTransactionManager} so {@code TransactionTemplate} has something to talk to.
 */
class AnalyzeSiteTriggerServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrandProfileRepository brandProfileRepository;
    private AnalyzeSiteTriggerService service;
    private final AtomicReference<BrandProfile> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        brandProfileRepository = mock(BrandProfileRepository.class);
        when(brandProfileRepository.findByWorkspaceId("ws1"))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(brandProfileRepository.save(any(BrandProfile.class)))
                .thenAnswer(
                        inv -> {
                            BrandProfile profile = inv.getArgument(0);
                            stored.set(profile);
                            return profile;
                        });

        AnalyzeSiteAiClient aiClient = mock(AnalyzeSiteAiClient.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        PlatformTransactionManager noopTransactionManager =
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus();
                    }

                    @Override
                    public void commit(TransactionStatus status) {}

                    @Override
                    public void rollback(TransactionStatus status) {}
                };

        service =
                new AnalyzeSiteTriggerService(
                        brandProfileRepository,
                        aiClient,
                        taskScheduler,
                        eventPublisher,
                        noopTransactionManager);
    }

    @Test
    @DisplayName("price_source round-trips verbatim into BrandProfile.productCatalogJson when present")
    void testPriceSourceRoundTripsWhenPresent() throws Exception {
        Data data =
                new Data(
                        "https://acme.example.com",
                        List.of(),
                        Map.of(),
                        null,
                        List.of(
                                Map.of(
                                        "name", "Scraped Widget",
                                        "price", 499.0,
                                        "currency", "INR",
                                        "price_source", "scraped")));

        service.applyChatResult("ws1", "https://acme.example.com", true, data, null);

        JsonNode catalog = MAPPER.readTree(stored.get().getProductCatalogJson());
        assertEquals(1, catalog.size());
        assertEquals("scraped", catalog.get(0).get("price_source").asText());
    }

    @Test
    @DisplayName("C1 fail-safe: an entry with no price_source at all is persisted as \"inferred\", never dropped or assumed scraped")
    void testMissingPriceSourceNormalizedToInferredOnPersist() throws Exception {
        Data data =
                new Data(
                        "https://acme.example.com",
                        List.of(),
                        Map.of(),
                        null,
                        List.of(
                                Map.of(
                                        "name", "Guessed Gadget",
                                        "price", 999.0,
                                        "currency", "INR")));

        service.applyChatResult("ws1", "https://acme.example.com", true, data, null);

        JsonNode catalog = MAPPER.readTree(stored.get().getProductCatalogJson());
        assertEquals(1, catalog.size());
        assertEquals("Guessed Gadget", catalog.get(0).get("name").asText());
        assertEquals("inferred", catalog.get(0).get("price_source").asText());
    }

    @Test
    @DisplayName("mixed catalog: scraped entries keep price_source=scraped, entries missing it become inferred")
    void testMixedCatalogNormalizesOnlyMissingEntries() throws Exception {
        Data data =
                new Data(
                        "https://acme.example.com",
                        List.of(),
                        Map.of(),
                        null,
                        List.of(
                                Map.of(
                                        "name", "Scraped Widget",
                                        "price", 499.0,
                                        "currency", "INR",
                                        "price_source", "scraped"),
                                Map.of(
                                        "name", "Guessed Gadget",
                                        "price", 999.0,
                                        "currency", "INR")));

        service.applyChatResult("ws1", "https://acme.example.com", true, data, null);

        JsonNode catalog = MAPPER.readTree(stored.get().getProductCatalogJson());
        assertEquals(2, catalog.size());
        assertEquals("scraped", catalog.get(0).get("price_source").asText());
        assertEquals("inferred", catalog.get(1).get("price_source").asText());
    }
}
