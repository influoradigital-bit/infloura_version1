package com.influora.web.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.service.creatorcopilot.CreatorIntelligenceProfile;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PostStat;
import com.influora.web.CreatorMeeraToolController;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyContentPatternsResult;
import com.influora.web.dto.meera.CreatorToolDtos.PostReading;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ADR wiki/decisions/2026-09-26-creator-own-caption-to-meera.md -- the proof that the ONE
 * caption-bearing DTO record allowed by {@link NoBrandFacingCaptionExposureTest#CREATOR_ONLY_CAPTION_FIELDS}
 * ({@link PostReading#captionFirstLine()}), and the service record it is built from ({@link
 * PostStat#captionFirstLine()}, inside the {@link CreatorIntelligenceProfile} that {@code
 * CreatorIntelligenceService.profile} returns), are reachable only from the creator-scoped Meera
 * tool route, and from no brand route, brand DTO or brand code path.
 *
 * <p>Three independent checks, because each alone has a hole:
 *
 * <ol>
 *   <li><b>DTO graph.</b> Every class compiled from {@code com.influora.web.dto} (found on the
 *       classpath, not a hand list) is walked through its record components and fields, generic
 *       arguments included. The only DTO that holds a {@code PostReading}, directly or through
 *       another type, is {@link GetMyContentPatternsResult}.
 *   <li><b>Routes.</b> Every {@code @Controller}/{@code @RestController} handler method in
 *       {@code com.influora} is checked by its generic return type. The only one whose response can
 *       carry a {@code PostReading} or a {@code PostStat} (so any handler returning the service's
 *       {@code CreatorIntelligenceProfile}, bare or wrapped) is {@code POST /internal/meera/creator/get_my_content_patterns}
 *       on {@link CreatorMeeraToolController}, whose handler refuses any non-CREATOR principal
 *       ({@code requireCreatorPrincipal}, pinned by {@code CreatorMeeraToolControllerTest}).
 *   <li><b>Source.</b> A route returning {@code Object} or a {@code Map} escapes a type check, so
 *       every production source file that names the caption line, the record, the service record
 *       it is built from, the service or profile that holds it, or the executor that builds it
 *       (comments and string literals stripped) must be one of {@link #ALLOWED_SOURCE_FILES}. A
 *       brand executor that called {@code creatorIntelligenceService.profile(..)}, the creator
 *       executor or {@code bestPosts()} would turn this red. The only uses allowed elsewhere are
 *       {@link #CAPTION_FREE_USES}: the service's static number helpers and the caption-free
 *       {@code Evidence}/{@code FollowedStat} records, which {@code
 *       CreatorRecommendationOutcomeService} shares.
 * </ol>
 */
class CreatorOwnCaptionReachabilityTest {

    private static final String BASE = "com.influora";

    /** Production files allowed to name the caption line or the records/executor that carry it. */
    static final Set<String> ALLOWED_SOURCE_FILES =
            Set.of(
                    "com/influora/service/creatorcopilot/CreatorOwnCaption.java",
                    "com/influora/service/creatorcopilot/CreatorIntelligenceService.java",
                    "com/influora/service/creatorcopilot/CreatorIntelligenceProfile.java",
                    "com/influora/service/meera/tool/creator/GetMyContentPatternsExecutor.java",
                    "com/influora/web/dto/meera/CreatorToolDtos.java",
                    "com/influora/web/CreatorMeeraToolController.java");

    /** Identifiers that carry, build or expose the caption line. */
    private static final List<String> CAPTION_CARRIERS =
            List.of(
                    "captionFirstLine",
                    "CreatorOwnCaption",
                    "PostReading",
                    "PostStat",
                    "CreatorIntelligenceProfile",
                    "CreatorIntelligenceService",
                    "GetMyContentPatternsResult",
                    "GetMyContentPatternsExecutor",
                    "bestPosts",
                    "weakPosts");

    /**
     * The only qualified uses of a carrier allowed outside {@link #ALLOWED_SOURCE_FILES}: the
     * service's static number helpers and the two profile records that hold no caption text
     * (checked by {@link #theAllowedUsesCarryNoCaption}). They are blanked before the token scan,
     * so any OTHER use in the same file -- {@code profile(..)}, a {@code CreatorIntelligenceProfile}
     * value, {@code PostStat} -- is still an offender.
     */
    static final Pattern CAPTION_FREE_USES =
            Pattern.compile(
                    "\\bCreatorIntelligenceService\\s*(?:\\.|::)\\s*(?:dedupeToNewestReadingPerPost|isSettled|median)\\b"
                            + "|\\bCreatorIntelligenceProfile\\s*\\.\\s*(?:Evidence|FollowedStat)\\b");

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    // ---------------------------------------------------------------------------------------------
    // 1. DTO graph
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "ADR 2026-09-26: the only web.dto type that holds PostReading (or the service PostStat) is"
                    + " GetMyContentPatternsResult")
    void onlyTheCreatorToolResultHoldsPostReading() throws Exception {
        List<Class<?>> dtos = mainClassesUnder("com/influora/web/dto/**/*.class");
        assertTrue(dtos.size() > 80, "the classpath scan found too few DTO classes to be a real scan: " + dtos.size());

        Reach reach = new Reach(PostReading.class, PostStat.class);
        Set<String> holders = new TreeSet<>();
        for (Class<?> dto : dtos) {
            if (dto != PostReading.class && reach.reaches(dto)) {
                holders.add(dto.getName());
            }
        }
        assertEquals(
                Set.of(GetMyContentPatternsResult.class.getName()),
                holders,
                "a DTO other than the creator tool result now carries PostReading or PostStat (and with"
                        + " it the creator's own caption line); brand DTOs must never carry it");
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Routes
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "ADR 2026-09-26: the only HTTP handler whose response can carry PostReading or PostStat"
                    + " (CreatorIntelligenceProfile) is the creator-scoped POST"
                    + " /internal/meera/creator/get_my_content_patterns")
    void onlyTheCreatorScopedRouteReturnsIt() throws Exception {
        List<Class<?>> all = mainClassesUnder("com/influora/**/*.class");
        // PostStat too: a handler returning the service's CreatorIntelligenceProfile (bare, in
        // ResponseEntity/ApiResponse, in a list) reaches PostStat.captionFirstLine.
        Reach reach = new Reach(PostReading.class, PostStat.class);

        Set<String> routes = new TreeSet<>();
        int handlers = 0;
        for (Class<?> type : all) {
            if (!AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                handlers++;
                if (reach.reachesType(method.getGenericReturnType())) {
                    routes.add(type.getName() + "#" + method.getName());
                }
            }
        }
        assertTrue(handlers > 200, "too few handler methods found to be a real scan: " + handlers);
        assertEquals(
                Set.of(CreatorMeeraToolController.class.getName() + "#getMyContentPatterns"),
                routes,
                "a route other than the creator tool route can now return the creator's caption line");

        // And that route is the creator-audience surface, at the path influora-ai calls.
        RequestMapping base = CreatorMeeraToolController.class.getAnnotation(RequestMapping.class);
        assertEquals(List.of("/internal/meera/creator"), List.of(base.value()));
        Method handler = null;
        for (Method m : CreatorMeeraToolController.class.getDeclaredMethods()) {
            if (m.getName().equals("getMyContentPatterns")) {
                handler = m;
            }
        }
        assertTrue(handler != null);
        assertEquals(List.of("/get_my_content_patterns"), List.of(handler.getAnnotation(PostMapping.class).value()));
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Source
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "ADR 2026-09-26: only the creator-scoped files name the caption line, PostReading, PostStat,"
                    + " CreatorIntelligenceProfile/Service, bestPosts/weakPosts or the executor (comments"
                    + " and strings ignored; only the caption-free static helpers are allowed elsewhere)")
    void onlyCreatorScopedSourcesNameTheCarriers() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES), "run from influora-api/: " + MAIN_SOURCES.toAbsolutePath());
        Map<String, List<String>> offenders = new HashMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                scanned++;
                String rel = MAIN_SOURCES.relativize(file).toString().replace('\\', '/');
                String code =
                        CAPTION_FREE_USES
                                .matcher(codeOnly(Files.readString(file, StandardCharsets.UTF_8)))
                                .replaceAll(" ");
                List<String> named = new ArrayList<>();
                for (String token : CAPTION_CARRIERS) {
                    if (containsIdentifier(code, token)) {
                        named.add(token);
                    }
                }
                if (!named.isEmpty() && !ALLOWED_SOURCE_FILES.contains(rel)) {
                    offenders.put(rel, named);
                }
            }
        }
        assertTrue(scanned > 500, "too few source files scanned to be a real scan: " + scanned);
        assertTrue(
                offenders.isEmpty(),
                "production code outside the creator-scoped files names a caption carrier; the"
                        + " creator's own caption line must never reach a brand path: " + offenders);
    }

    @Test
    @DisplayName(
            "the uses allowed outside the creator files carry no caption: Evidence and FollowedStat"
                    + " cannot reach PostStat or PostReading, and the static helpers return no profile")
    void theAllowedUsesCarryNoCaption() throws Exception {
        Reach reach = new Reach(PostReading.class, PostStat.class);
        assertTrue(!reach.reaches(CreatorIntelligenceProfile.Evidence.class));
        assertTrue(!reach.reaches(CreatorIntelligenceProfile.FollowedStat.class));
        assertTrue(reach.reaches(CreatorIntelligenceProfile.class), "non-vacuity: the profile does hold PostStat");
        Class<?> service = Class.forName("com.influora.service.creatorcopilot.CreatorIntelligenceService");
        int helpers = 0;
        for (Method m : service.getDeclaredMethods()) {
            if (Set.of("dedupeToNewestReadingPerPost", "isSettled", "median").contains(m.getName())) {
                helpers++;
                assertTrue(Modifier.isStatic(m.getModifiers()), m.getName() + " must stay a static helper");
                assertTrue(
                        !reach.reachesType(m.getGenericReturnType()),
                        m.getName() + " now returns something that can carry the caption line");
            }
        }
        assertTrue(helpers >= 3, "non-vacuity: the allowed helpers must exist, found " + helpers);
    }

    @Test
    @DisplayName("the allowed-use blanking removes only the static helpers and caption-free records")
    void captionFreeUsesAreNarrow() {
        String code =
                "import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Evidence;\n"
                        + "x = CreatorIntelligenceService.median(a); y = CreatorIntelligenceService::isSettled;\n";
        String blanked = CAPTION_FREE_USES.matcher(code).replaceAll(" ");
        for (String token : CAPTION_CARRIERS) {
            assertTrue(!containsIdentifier(blanked, token), token + " survived: " + blanked);
        }
        String leak = "CreatorIntelligenceProfile p = creatorIntelligenceService.profile(id, now);"
                + " CreatorIntelligenceService s; CreatorIntelligenceProfile.PostStat t;";
        String kept = CAPTION_FREE_USES.matcher(leak).replaceAll(" ");
        assertTrue(containsIdentifier(kept, "CreatorIntelligenceProfile"), kept);
        assertTrue(containsIdentifier(kept, "CreatorIntelligenceService"), kept);
        assertTrue(containsIdentifier(kept, "PostStat"), kept);
    }

    /** Sanity for the stripper the source check relies on. */
    @Test
    @DisplayName("the comment/string stripper keeps code and drops comments, strings and text blocks")
    void codeOnlyStripsCommentsAndStrings() {
        String src =
                "class A { // PostReading in a comment\n"
                        + " /* bestPosts */ String s = \"captionFirstLine // not code\";\n"
                        + " String t = \"\"\"\n PostStat\n \"\"\"; char c = '\"';\n"
                        + " Object real = x.weakPosts(); }";
        String code = codeOnly(src);
        assertTrue(!containsIdentifier(code, "PostReading"));
        assertTrue(!containsIdentifier(code, "bestPosts"));
        assertTrue(!containsIdentifier(code, "captionFirstLine"));
        assertTrue(!containsIdentifier(code, "PostStat"));
        assertTrue(containsIdentifier(code, "weakPosts"), "real code must survive: " + code);
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    /** Production classes (never test classes) matching an ant pattern under the classpath. */
    private static List<Class<?>> mainClassesUnder(String antPattern) throws IOException, ClassNotFoundException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        ClassLoader loader = CreatorOwnCaptionReachabilityTest.class.getClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:" + antPattern)) {
            String url = resource.getURL().toString();
            if (url.contains("/test-classes/")) {
                continue;
            }
            String name = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            classes.add(Class.forName(name, false, loader));
        }
        return classes;
    }

    /** Whether a type, through its components/fields and generic arguments, can hold the target. */
    private static final class Reach {
        private final Set<Class<?>> targets;

        Reach(Class<?>... targets) {
            this.targets = Set.of(targets);
        }

        boolean reachesType(Type type) {
            return reachesType(type, new HashSet<>());
        }

        boolean reaches(Class<?> type) {
            return reachesClass(type, new HashSet<>());
        }

        private boolean reachesType(Type type, Set<Class<?>> visited) {
            if (type instanceof Class<?> c) {
                return c.isArray() ? reachesType(c.getComponentType(), visited) : reachesClass(c, visited);
            }
            if (type instanceof ParameterizedType p) {
                if (reachesType(p.getRawType(), visited)) {
                    return true;
                }
                for (Type arg : p.getActualTypeArguments()) {
                    if (reachesType(arg, visited)) {
                        return true;
                    }
                }
                return false;
            }
            if (type instanceof GenericArrayType g) {
                return reachesType(g.getGenericComponentType(), visited);
            }
            if (type instanceof WildcardType w) {
                for (Type bound : w.getUpperBounds()) {
                    if (reachesType(bound, visited)) {
                        return true;
                    }
                }
                for (Type bound : w.getLowerBounds()) {
                    if (reachesType(bound, visited)) {
                        return true;
                    }
                }
                return false;
            }
            if (type instanceof TypeVariable<?> v) {
                for (Type bound : v.getBounds()) {
                    if (reachesType(bound, visited)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean reachesClass(Class<?> c, Set<Class<?>> visited) {
            if (targets.contains(c)) {
                return true;
            }
            if (c.isPrimitive() || !c.getName().startsWith(BASE) || c.isEnum()) {
                return false;
            }
            if (!visited.add(c)) {
                return false; // already explored in this query: any path through it was tried
            }
            boolean result = false;
            if (c.isRecord()) {
                for (RecordComponent rc : c.getRecordComponents()) {
                    if (reachesType(rc.getGenericType(), visited)) {
                        result = true;
                        break;
                    }
                }
            }
            if (!result) {
                for (Class<?> k = c; k != null && k.getName().startsWith(BASE); k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                            continue;
                        }
                        if (reachesType(f.getGenericType(), visited)) {
                            result = true;
                            break;
                        }
                    }
                    if (result) {
                        break;
                    }
                }
            }
            return result;
        }
    }

    private static boolean containsIdentifier(String code, String identifier) {
        int from = 0;
        while (true) {
            int i = code.indexOf(identifier, from);
            if (i < 0) {
                return false;
            }
            int end = i + identifier.length();
            boolean startOk = i == 0 || !Character.isJavaIdentifierPart(code.charAt(i - 1));
            boolean endOk = end == code.length() || !Character.isJavaIdentifierPart(code.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = i + 1;
        }
    }

    /** Java source with comments, string/char literals and text blocks blanked out. */
    static String codeOnly(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char ch = src.charAt(i);
            if (ch == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (ch == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int close = src.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                out.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int close = src.indexOf("\"\"\"", i + 3);
                while (close > 0 && src.charAt(close - 1) == '\\') {
                    close = src.indexOf("\"\"\"", close + 1);
                }
                i = close < 0 ? n : close + 3;
                out.append("\"\"");
            } else if (ch == '"' || ch == '\'') {
                i++;
                while (i < n && src.charAt(i) != ch && src.charAt(i) != '\n') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append(ch).append(ch);
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }
}
