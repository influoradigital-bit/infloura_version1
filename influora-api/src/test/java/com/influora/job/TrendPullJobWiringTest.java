package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.influora.config.TrendIngestProperties;
import com.influora.integration.ai.BrandSafetyAiClient;
import com.influora.service.creatorcopilot.TrendHeadlineScreener;
import com.influora.service.trendspark.ThemeMatchService;
import com.influora.service.trendspark.ingest.TrendIngestWriter;
import com.influora.service.trendspark.ingest.TrendSourceClient;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * EV-001 [vikram · 2026-09-19] — influora-api could not boot in ANY profile: {@link TrendPullJob}
 * is an unconditional {@code @Component} with two constructors and neither was
 * {@code @Autowired}, so Spring fell back to a no-arg constructor that does not exist
 * (BeanCreationException "No default constructor found"). {@code TrendPullJobTest} calls the
 * constructors directly with {@code new}, so it could never see this.
 *
 * <p>This test goes through real Spring constructor resolution with NO Docker and NO database.
 * {@link TrendPullJob} is registered with {@link AnnotationConfigApplicationContext#register}
 * (an annotated bean definition — the same resolution path component scanning uses), NOT
 * {@code registerBean(Class)}, whose class-derived definition silently prefers the single public
 * constructor and would green this test even without the fix.
 */
class TrendPullJobWiringTest {

    @Test
    @DisplayName("EV-001: Spring can instantiate TrendPullJob via its production constructor")
    void springResolvesProductionConstructor() throws Exception {
        TrendSourceClient source = mock(TrendSourceClient.class);
        BrandSafetyAiClient brandSafetyAiClient = mock(BrandSafetyAiClient.class);
        ThemeMatchService themeMatchService = mock(ThemeMatchService.class);
        TrendIngestWriter writer = mock(TrendIngestWriter.class);
        TrendIngestProperties props = new TrendIngestProperties();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("newsSource", TrendSourceClient.class, () -> source);
            ctx.registerBean(BrandSafetyAiClient.class, () -> brandSafetyAiClient);
            ctx.registerBean(ThemeMatchService.class, () -> themeMatchService);
            ctx.registerBean(TrendIngestWriter.class, () -> writer);
            ctx.registerBean(TrendIngestProperties.class, () -> props);
            ctx.register(TrendPullJob.class);

            ctx.refresh();

            TrendPullJob job = ctx.getBean(TrendPullJob.class);
            assertNotNull(job);

            // Collaborators were injected from the context (not left null by a fallback path).
            assertEquals(List.of(source), readField(job, "sourceClients"));
            assertSame(brandSafetyAiClient, readField(job, "brandSafetyAiClient"));
            assertSame(writer, readField(job, "writer"));
            assertSame(props, readField(job, "props"));

            // Production constructor => the REAL TrendHeadlineScreener word filter is wired.
            String unsafe = "5 killed today";
            assertFalse(
                    TrendHeadlineScreener.isSafeForCreatorCopy(unsafe),
                    "precondition: the real screener must reject this headline");
            Object screener = readField(job, "screener");
            java.lang.reflect.Method accessor = screener.getClass().getDeclaredMethod("isSafe");
            accessor.setAccessible(true);
            @SuppressWarnings("unchecked")
            Predicate<String> isSafe = (Predicate<String>) accessor.invoke(screener);
            assertFalse(isSafe.test(unsafe));
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = TrendPullJob.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
