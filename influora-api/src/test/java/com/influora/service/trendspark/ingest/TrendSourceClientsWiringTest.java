package com.influora.service.trendspark.ingest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.influora.config.TrendIngestProperties;
import java.lang.reflect.Field;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * EV-175 [vikram · 2026-09-19] — after EV-001 was fixed, a real boot failed on the NEXT bean with
 * the same defect: each of these three unconditional {@code @Component} trend sources declares a
 * public {@code (TrendIngestProperties)} constructor plus a package-private
 * {@code (TrendIngestProperties, RestClient)} test seam, and neither was {@code @Autowired}, so
 * Spring fell back to a no-arg constructor that does not exist ("No default constructor found").
 * The per-client tests call the seam directly with {@code new}, so they could never see this.
 *
 * <p>Same technique as {@code TrendPullJobWiringTest}: the class is registered with
 * {@link AnnotationConfigApplicationContext#register} (an annotated bean definition — the path
 * component scanning uses), NOT {@code registerBean(Class)}, whose class-derived definition
 * silently prefers the single public constructor and would green this test without the fix.
 * One case per class so each can be falsified on its own.
 */
class TrendSourceClientsWiringTest {

    @ParameterizedTest(name = "EV-175: Spring can instantiate {0} via its production constructor")
    @ValueSource(classes = {
        NewsApiTopHeadlinesClient.class,
        TmdbUpcomingClient.class,
        YouTubeMostPopularClient.class
    })
    void springResolvesProductionConstructor(Class<? extends TrendSourceClient> clientClass)
            throws Exception {
        TrendIngestProperties props = new TrendIngestProperties();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(TrendIngestProperties.class, () -> props);
            ctx.register(clientClass);

            ctx.refresh();

            TrendSourceClient client = ctx.getBean(clientClass);
            assertNotNull(client);
            // Props came from the context, and the production constructor (not the test seam)
            // ran: its RestClient is still unset, to be built lazily with timeouts on first use.
            assertSame(props, readField(clientClass, client, "props"));
            assertNull(readField(clientClass, client, "restClient"));
        }
    }

    private static Object readField(Class<?> type, Object target, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
