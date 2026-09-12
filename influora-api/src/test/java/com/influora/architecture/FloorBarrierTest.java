package com.influora.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-MEERA-CREATOR-PHASE-B, defect D-01 (PENDING-0912.md; QA-TECH-0912.md question 6) — the control
 * that actually prevents a creator's negotiating floor reaching a brand-readable payload.
 *
 * <p><b>Why this class exists at all.</b> {@code InfoBarrierTest} forbids exactly one thing: an
 * {@code import} of {@code CreatorAgentPreferencesRepository} from outside a three-name allow-list.
 * That is a check on who may READ the floor row, not on where the floor may TRAVEL. Declaring
 * {@code @JsonProperty("quote") CreatorToolDtos.PackageQuote quote} on a brand-readable DTO turned
 * <b>no test red</b> before this class: the offending file imports {@code CreatorToolDtos}, never
 * the repository, so the allow-list never sees it. {@code InfoBarrierRuntimeTest} is narrower
 * still — it asserts on one payload (the BRAND context) and one audit detail, using a distinctive
 * floor VALUE, so it cannot see a floor field on any other DTO and cannot see an empty-but-present
 * {@code floor_total} key at all.
 *
 * <p><b>The invariant, stated once.</b> {@code PackageQuote} carries {@code floor_total},
 * {@code floor_total_value}, {@code anchor}/{@code anchor_value} and {@code range_min}/{@code
 * range_max} — the creator's floor is her negotiating position and a brand seeing it is the single
 * worst outcome this feature can produce. So: <i>no type reachable from a response served by a
 * controller that is not explicitly creator-gated may carry a floor-bearing field, at any depth.</i>
 *
 * <p><b>Default-deny, and transitive.</b> Two things make this a barrier rather than a decoration:
 *
 * <ol>
 *   <li><b>Every</b> controller in {@code src/main/java} is treated as brand-readable unless its
 *       simple name is in {@link #FLOOR_PERMITTED_CONTROLLERS}. A controller added next month is
 *       covered the day it is written, without anyone remembering this file exists. That is the
 *       property {@code InfoBarrierTest}'s original "simple name contains Brand" filter lacked, and
 *       the reason it was widened once already. "Every" means <b>every class carrying a {@code
 *       @Controller} stereotype</b>, resolved through meta-annotations — not every file named
 *       {@code *Controller.java}, which is what {@link #controllerClasses()} originally counted and
 *       which left a nested or differently-named {@code @RestController} out of the denominator.
 *   <li>Reachability is computed over the <b>whole declared type graph</b>, not one hop. A brand DTO
 *       that embeds a type that embeds a {@code PackageQuote} fails exactly like the direct case —
 *       which matters, because the direct case is the one a careless author is least likely to
 *       write. Generic type arguments are walked, so {@code List<Wrapper>}, {@code
 *       ResponseEntity<ApiResponse<X>>} and {@code Map<String, Wrapper>} are all followed.
 * </ol>
 *
 * <p><b>Reflection, not a source scan — deliberately.</b> This repo has burned two gates on a
 * source-text scan matching the comment that explained the banned pattern (see the reworded comments
 * in {@code CreatorToolScopes}). Reading {@link RecordComponent}s and {@link JsonProperty}
 * annotations off loaded classes cannot false-positive on a javadoc mention, and cannot be fooled by
 * a line break inside a generic parameter list. Classes are loaded with {@code initialize=false} so
 * no static initialiser runs. There is no ArchUnit dependency in this project ({@code pom.xml}) and
 * adding one is out of this task's authority, so the class enumeration is done by walking source
 * paths — the same documented fallback {@code InfoBarrierTest} uses.
 *
 * <p><b>Known limits, written down rather than implied.</b> A floor smuggled inside an opaque
 * {@code String} cannot be seen here — {@code CreatorBrief.quoteJson} is exactly that, and is why
 * {@code quote_json} is in {@link #isFloorName}: the KEY is bannable even though the value is
 * opaque. A floor written into a {@code Map<String, String>} under a runtime-computed key is also
 * invisible; that is what {@code InfoBarrierRuntimeTest}'s serialise-and-scan assertions are for,
 * and why both halves are kept.
 */
class FloorBarrierTest {

    /**
     * JSON property names that carry, or render, a creator floor. {@code anchor} and {@code
     * range_min}/{@code range_max} are included because they are the same secret at a different
     * precision: the anchor is what Meera tells the creator to ASK for, and the range is the band
     * she will settle inside. Verified against the whole DTO surface at this HEAD — each of these
     * names occurs on {@code PackageQuote} and nowhere else, so none of them is banned at the cost
     * of an existing false positive.
     *
     * <p>If a future, genuinely unrelated field wants one of these names, <b>rename the field</b>.
     * Do not relax this set. The same instruction is written on the two grep gates in this repo that
     * went red on their own explanatory comments, for the same reason: a gate that is relaxed once
     * to clear a false positive never gets tightened again.
     */
    private static final String FLOOR_SUBSTRING = "floor";

    /**
     * Names that ARE a floor without containing the word. Checked against the JSON name and the Java
     * name, both lowercased with {@code _} stripped, so {@code anchor_value}/{@code anchorValue} are
     * one entry.
     *
     * <p>{@code reserveprice}, {@code walkaway} and {@code minacceptable} are the floor under the three
     * names a negotiation author reaches for when not writing "floor" — a reserve price, a walk-away
     * number and a minimum acceptable are the same secret with the same consequence. None of them
     * occurs anywhere on the DTO surface at this HEAD (test 1's pinned carrier set and test 2's route
     * scan both stay green with them added), so each is banned before the field exists rather than
     * after. The instruction above applies to these too: rename the field, do not relax the set.
     */
    private static final Set<String> EXTRA_FLOOR_NAMES =
            Set.of(
                    "anchor",
                    "anchorvalue",
                    "rangemin",
                    "rangemax",
                    "quotejson",
                    "reserveprice",
                    "walkaway",
                    "minacceptable");

    /**
     * Any name containing {@code floor} is a floor, plus {@link #EXTRA_FLOOR_NAMES}.
     *
     * <p><b>A substring rule, not an enumeration — because the enumeration was already wrong.</b>
     * The first draft of this class listed the nine floor names visible on {@code PackageQuote} and
     * {@code CreatorContextResponse} and ran green. It was missing {@code reel_floor}, {@code
     * story_set_floor} and {@code post_floor} — the three per-deliverable floors on {@code
     * CreatorAgentDtos}, which are the creator's floor in its most literal form and are the rows
     * everything else derives from. A hand-kept list of floor names is the same defect as a
     * hand-kept list of wired tool names: it has to be edited by the same author who is adding the
     * field it is supposed to catch. {@code carousel_floor} is covered by this rule today, before
     * anyone writes it.
     */
    private static boolean isFloorName(String name) {
        String normalised = name.toLowerCase(java.util.Locale.ROOT).replace("_", "");
        return normalised.contains(FLOOR_SUBSTRING) || EXTRA_FLOOR_NAMES.contains(normalised);
    }

    /**
     * The complete set of wire types permitted to carry a floor, pinned as an explicit literal.
     *
     * <p><b>Not a subset assertion.</b> "The floor-bearing set contains PackageQuote" would pass
     * vacuously for any new carrier added beside it — the same failure mode written up on {@code
     * CreatorToolScopes.SCOPE_LEVEL_2}. Pinning the exact set means a new carrier turns this red and
     * forces the author to name it here, in a file whose only subject is the info barrier, where the
     * addition is reviewable.
     *
     * <p>Each entry, and why it is allowed to hold a floor:
     *
     * <ul>
     *   <li>{@code PackageQuote} / {@code QuoteLine} / {@code EstimateMyRateResult} — the quote
     *       itself, served only by {@code CreatorMeeraToolController} ({@code estimate_my_rate}).
     *   <li>{@code GetBriefResult}, {@code CampaignFit}, {@code RankOpenCampaignsResult} — creator
     *       tool results with no route at all yet (Waves 4 and B7).
     *   <li>{@code CreatorAgentDtos.PreferencesResponse} / {@code UpdatePreferencesRequest} — the
     *       creator reading and writing her OWN floor rows ({@code reel_floor}, {@code
     *       story_set_floor}, {@code post_floor}). {@code CreatorAgentController} resolves the
     *       acting creator strictly from {@code principal.getUserId()}, never a path or body id.
     *   <li>{@code AdminCreatorAgentDtos.RateCalibrationResponse} — {@code sample_floor} on the
     *       internal calibration report, under {@code /admin/**} and therefore behind {@code
     *       SecurityConfig}'s structural {@code hasRole("ADMIN")} matcher.
     * </ul>
     *
     * <p>{@code CreatorContextResponse} is here because it declares {@code @JsonProperty("floors")
     * Map<String, String> floors} and {@code floor_currency} — the creator's floor by its OTHER
     * name, and the name a careless author is most likely to copy. It is <b>not</b> visible to
     * {@link #noFloorBearingTypeIsReachableFromABrandReadableResponse}: the only route that serves it
     * is {@code MeeraInternalController#context}, declared {@code
     * ResponseEntity<ApiResponse<Object>>}, so the reachability walk finds no {@code com.influora}
     * type on it at all. That route branches on the JWT-verified {@code userType} and is the one
     * place a BRAND caller and a CREATOR caller share a handler, which is exactly why {@code
     * InfoBarrierRuntimeTest} serialises the assembled BRAND context and scans it for these keys at
     * runtime. Neither half covers the other; both are required.
     */
    private static final Set<String> EXPECTED_FLOOR_BEARING_TYPES =
            Set.of(
                    "CreatorToolDtos.PackageQuote",
                    "CreatorToolDtos.QuoteLine",
                    "CreatorToolDtos.EstimateMyRateResult",
                    "CreatorToolDtos.GetBriefResult",
                    "CreatorToolDtos.CampaignFit",
                    "CreatorToolDtos.RankOpenCampaignsResult",
                    "CreatorAgentDtos.PreferencesResponse",
                    "CreatorAgentDtos.UpdatePreferencesRequest",
                    "AdminCreatorAgentDtos.RateCalibrationResponse",
                    "MeeraContextDtos.CreatorContextResponse");

    /**
     * Controllers permitted to serve a floor-bearing response, each because its gate proves the
     * caller is the floor's owner or an operator — never a brand. Verified by reading each one:
     *
     * <ul>
     *   <li>{@code CreatorMeeraToolController} — {@code handleRead} runs {@code
     *       requireCreatorPrincipal} for every route, so a BRAND-audience on-behalf token is
     *       refused with {@code AUDIENCE_PRINCIPAL_MISMATCH}.
     *   <li>{@code CreatorAgentController} — every route resolves the acting creator from {@code
     *       principal.getUserId()}, never a path or body id (class javadoc, and {@code
     *       CreatorAgentPreferencesService#requireCreatorProfile}). She reads her own floor.
     *   <li>{@code AdminCreatorAgentController} — {@code @RequestMapping("/admin/creator-agent")},
     *       so admin auth is structural: {@code SecurityConfig}'s {@code hasRole("ADMIN")} matcher
     *       on {@code /admin/**}.
     * </ul>
     *
     * <p>A controller whose name merely starts with "Creator" does <b>not</b> qualify and must not be
     * added here on that basis — {@code PublicCreatorController} is unauthenticated, and a creator
     * controller can still serve a payload a brand reads. Adding a name here widens the barrier, so
     * it is the one edit in this file that needs a second reader.
     */
    private static final Set<String> FLOOR_PERMITTED_CONTROLLERS =
            Set.of(
                    "CreatorMeeraToolController",
                    "CreatorAgentController",
                    "AdminCreatorAgentController");

    /** Spring mapping annotations, by simple name — avoids importing six annotation types. */
    private static final Set<String> MAPPING_ANNOTATIONS =
            Set.of(
                    "RequestMapping",
                    "GetMapping",
                    "PostMapping",
                    "PutMapping",
                    "PatchMapping",
                    "DeleteMapping");

    private static final String INFLUORA_PACKAGE = "com.influora.";

    // ---------------------------------------------------------------------------------------------
    // Test 1 — the carriers themselves
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "D-01(a) — the set of wire types that transitively carry a creator floor is exactly the"
                    + " declared set: adding a floor field, or a floor-bearing type, to any DTO turns"
                    + " this red")
    void theFloorBearingWireSurfaceIsExactlyTheDeclaredSet() throws IOException {
        TypeGraph graph = TypeGraph.overDtoSurface();

        // Non-vacuity, asserted before the real assertion: a walker that silently found nothing
        // would otherwise make every assertion below pass. Both numbers are far under the real
        // counts at this HEAD (hundreds of DTO types), so this does not become a maintenance tax.
        assertThat(graph.nodes())
                .as("the DTO type graph is empty or tiny — the walker found nothing and every"
                        + " assertion in this class would pass vacuously")
                .hasSizeGreaterThan(100);

        Set<String> actual = graph.floorBearingTypeNames();

        assertThat(actual)
                .as("PackageQuote itself is not detected as floor-bearing — the floor-key detection"
                        + " is broken, and this whole barrier is decoration")
                .contains("CreatorToolDtos.PackageQuote");

        assertThat(actual)
                .withFailMessage(
                        """
                        The set of types that transitively carry a creator floor has changed.

                          expected (declared in FloorBarrierTest.EXPECTED_FLOOR_BEARING_TYPES):
                            %s
                          actual:
                            %s
                          newly floor-bearing (these are the offenders):
                            %s
                          no longer floor-bearing (remove them from the declared set):
                            %s

                        A type is floor-bearing if it declares a field whose JSON name or Java name
                        contains "floor", or is one of %s, OR if any component
                        of it — at any depth, through generics — is floor-bearing. The chain for
                        each new offender:
                        %s

                        If this is a creator-private payload that legitimately carries a floor, add
                        it to EXPECTED_FLOOR_BEARING_TYPES and make sure its route proves a CREATOR
                        principal. If it is brand-readable, the floor must not be on it at all.
                        (T-MEERA-CREATOR-PHASE-B, D-01)""",
                        sorted(EXPECTED_FLOOR_BEARING_TYPES),
                        sorted(actual),
                        sorted(minus(actual, EXPECTED_FLOOR_BEARING_TYPES)),
                        sorted(minus(EXPECTED_FLOOR_BEARING_TYPES, actual)),
                        sorted(EXTRA_FLOOR_NAMES),
                        graph.explainFloorChains(minus(actual, EXPECTED_FLOOR_BEARING_TYPES)))
                .isEqualTo(EXPECTED_FLOOR_BEARING_TYPES);
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2 — the route surface
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "D-01(b) — no floor-bearing type is reachable from a response served by any controller"
                    + " that is not explicitly creator-gated")
    void noFloorBearingTypeIsReachableFromABrandReadableResponse() throws IOException {
        TypeGraph graph = TypeGraph.overDtoSurface();
        List<Class<?>> controllers = controllerClasses();

        assertThat(controllers)
                .as("no controllers were found — this assertion cannot pass vacuously")
                .hasSizeGreaterThan(50);

        List<String> offences = new ArrayList<>();
        int mappedMethods = 0;

        for (Class<?> controller : controllers) {
            if (FLOOR_PERMITTED_CONTROLLERS.contains(controller.getSimpleName())) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (!hasMappingAnnotation(method)) {
                    continue;
                }
                mappedMethods++;
                for (Class<?> responseType : graph.influoraTypesIn(method.getGenericReturnType())) {
                    List<String> chain = graph.chainToFloor(responseType);
                    if (chain != null) {
                        offences.add(
                                "%s#%s  returns  %s"
                                        .formatted(
                                                controller.getName(),
                                                method.getName(),
                                                String.join(" -> ", chain)));
                    }
                }
            }
        }

        assertThat(mappedMethods)
                .as("no @*Mapping handler methods were found across %d controllers — this assertion"
                        + " cannot pass vacuously", controllers.size())
                .isGreaterThan(200);

        assertThat(sorted(new LinkedHashSet<>(offences)))
                .withFailMessage(
                        """
                        A creator's negotiating floor is reachable from a brand-readable response.

                        %s

                        Each line is controller#handler followed by the declared type chain from the
                        response type down to the floor. Every controller is treated as brand-readable
                        unless its simple name is in FloorBarrierTest.FLOOR_PERMITTED_CONTROLLERS
                        (currently %s) — a controller that proves a CREATOR principal on every route.

                        Fix the payload, not this list. Strip the floor before it reaches the wire
                        type (SPEC.md B1 createSecureLink builds a stripped package_json for exactly
                        this reason). (T-MEERA-CREATOR-PHASE-B, D-01)""",
                        offences.isEmpty() ? "(none)" : "  " + String.join("\n  ", sorted(new LinkedHashSet<>(offences))),
                        FLOOR_PERMITTED_CONTROLLERS)
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // The declared-type graph
    // ---------------------------------------------------------------------------------------------

    /**
     * The declared type graph over the wire surface: nodes are {@code com.influora.*} classes, edges
     * are "declares a component/field of this type", following generic type arguments.
     *
     * <p>Seeded from every type under {@code web/dto/**} and then <b>expanded through anything it
     * references</b>, so a server-internal type pulled onto a DTO (e.g. {@code
     * service.risk.RiskContext}, which holds a {@code PackageQuote}) is drawn into the graph by the
     * act of referencing it, rather than being invisible because it lives outside the seed roots.
     */
    private static final class TypeGraph {

        private final Map<Class<?>, Set<Class<?>>> edges = new LinkedHashMap<>();
        private final Set<Class<?>> floorBearing = new LinkedHashSet<>();

        static TypeGraph overDtoSurface() throws IOException {
            TypeGraph graph = new TypeGraph();
            graph.expandFrom(MainSourceClasses.classesUnder("com/influora/web/dto"));
            graph.computeFloorBearing();
            return graph;
        }

        Set<Class<?>> nodes() {
            return edges.keySet();
        }

        /** Breadth-first: add each class, its declared component types, and so on. */
        private void expandFrom(List<Class<?>> seeds) {
            Deque<Class<?>> queue = new ArrayDeque<>(seeds);
            while (!queue.isEmpty()) {
                Class<?> current = queue.poll();
                if (current == null || edges.containsKey(current)) {
                    continue;
                }
                Set<Class<?>> children = declaredComponentTypes(current);
                edges.put(current, children);
                queue.addAll(children);
            }
        }

        /**
         * Pulls a type into the graph on demand — used for controller return types, which may name a
         * class that nothing under {@code web/dto/**} happens to reference.
         */
        private void ensurePresent(Class<?> type) {
            if (!edges.containsKey(type)) {
                expandFrom(List.of(type));
                computeFloorBearing();
            }
        }

        /** Fixpoint: seed with the types that declare a floor key, then propagate up the edges. */
        private void computeFloorBearing() {
            for (Class<?> node : edges.keySet()) {
                if (declaresFloorKey(node)) {
                    floorBearing.add(node);
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Map.Entry<Class<?>, Set<Class<?>>> entry : edges.entrySet()) {
                    if (floorBearing.contains(entry.getKey())) {
                        continue;
                    }
                    for (Class<?> child : entry.getValue()) {
                        if (floorBearing.contains(child)) {
                            floorBearing.add(entry.getKey());
                            changed = true;
                            break;
                        }
                    }
                }
            }
        }

        Set<String> floorBearingTypeNames() {
            Set<String> names = new LinkedHashSet<>();
            for (Class<?> type : floorBearing) {
                names.add(nestedSimpleName(type));
            }
            return names;
        }

        /**
         * The declared-type chain from {@code from} down to the field that carries the floor, or
         * {@code null} if there is none. Breadth-first, so the chain reported is a shortest one — the
         * most useful thing to put in a failure message.
         */
        List<String> chainToFloor(Class<?> from) {
            ensurePresent(from);
            if (!floorBearing.contains(from)) {
                return null;
            }
            Map<Class<?>, Class<?>> parent = new HashMap<>();
            Deque<Class<?>> queue = new ArrayDeque<>();
            Set<Class<?>> seen = new HashSet<>();
            queue.add(from);
            seen.add(from);
            Class<?> terminal = null;
            while (!queue.isEmpty()) {
                Class<?> current = queue.poll();
                if (declaresFloorKey(current)) {
                    terminal = current;
                    break;
                }
                for (Class<?> child : edges.getOrDefault(current, Set.of())) {
                    if (floorBearing.contains(child) && seen.add(child)) {
                        parent.put(child, current);
                        queue.add(child);
                    }
                }
            }
            if (terminal == null) {
                return List.of(nestedSimpleName(from), "(floor-bearing, chain not resolved)");
            }
            List<String> chain = new ArrayList<>();
            for (Class<?> step = terminal; step != null; step = parent.get(step)) {
                chain.add(0, nestedSimpleName(step));
            }
            chain.add(floorKeyOn(terminal));
            return chain;
        }

        String explainFloorChains(Set<String> typeNames) {
            if (typeNames.isEmpty()) {
                return "  (none)";
            }
            List<String> lines = new ArrayList<>();
            for (Class<?> node : new ArrayList<>(floorBearing)) {
                if (typeNames.contains(nestedSimpleName(node))) {
                    lines.add("  " + String.join(" -> ", chainToFloor(node)));
                }
            }
            return String.join("\n", sorted(new LinkedHashSet<>(lines)));
        }

        /** Every {@code com.influora.*} class named anywhere in a (possibly generic) type. */
        Set<Class<?>> influoraTypesIn(Type type) {
            Set<Class<?>> found = new LinkedHashSet<>();
            collectInfluoraTypes(type, found, new HashSet<>());
            return found;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reflection helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * The {@code com.influora.*} types declared by {@code owner}'s record components (or, for a
     * non-record, its declared instance fields). Static, synthetic and {@code this$0} fields are
     * skipped: a static constant is not part of the payload, and the synthetic outer reference on a
     * nested class would otherwise make every nested DTO "contain" its container.
     */
    private static Set<Class<?>> declaredComponentTypes(Class<?> owner) {
        Set<Class<?>> found = new LinkedHashSet<>();
        if (owner.isRecord()) {
            for (RecordComponent component : owner.getRecordComponents()) {
                collectInfluoraTypes(component.getGenericType(), found, new HashSet<>());
            }
            return found;
        }
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            collectInfluoraTypes(field.getGenericType(), found, new HashSet<>());
        }
        return found;
    }

    private static void collectInfluoraTypes(Type type, Set<Class<?>> into, Set<Type> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        if (type instanceof Class<?> clazz) {
            Class<?> element = clazz;
            while (element.isArray()) {
                element = element.getComponentType();
            }
            if (element.getName().startsWith(INFLUORA_PACKAGE) && !element.isEnum()) {
                into.add(element);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            collectInfluoraTypes(parameterized.getRawType(), into, seen);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectInfluoraTypes(argument, into, seen);
            }
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                collectInfluoraTypes(bound, into, seen);
            }
        }
    }

    /** True if this type declares a component or field that IS a floor, by JSON name or Java name. */
    private static boolean declaresFloorKey(Class<?> owner) {
        return floorKeyOn(owner) != null;
    }

    /** The first floor-bearing name declared on this type, for the failure message; else null. */
    private static String floorKeyOn(Class<?> owner) {
        if (owner.isRecord()) {
            for (RecordComponent component : owner.getRecordComponents()) {
                String hit =
                        floorKeyFor(
                                component.getName(),
                                component,
                                declaredFieldOrNull(owner, component.getName()),
                                component.getAccessor());
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            String hit = floorKeyFor(field.getName(), field);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Resolves the effective JSON name and checks both it and the Java name.
     *
     * <p><b>Why several candidate elements and not just the record component.</b> Jackson's {@code
     * @JsonProperty} declares {@code @Target({ANNOTATION_TYPE, FIELD, METHOD, PARAMETER})} — it is
     * <i>not</i> applicable to {@code RECORD_COMPONENT}, so javac does not record it in a record's
     * {@code RecordComponents} attribute and {@link RecordComponent#getAnnotation} returns
     * {@code null} for every {@code @JsonProperty} in {@code CreatorToolDtos}. The annotation is
     * propagated to the backing field, the accessor and the constructor parameter instead. Checking
     * the component alone would have silently reduced this barrier to its Java-name half — which
     * would still have caught {@code floorTotal}, and therefore would have looked like it worked.
     */
    private static String floorKeyFor(String javaName, AnnotatedElement... candidates) {
        String jsonName = javaName;
        for (AnnotatedElement candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            JsonProperty annotation = candidate.getAnnotation(JsonProperty.class);
            if (annotation != null && !annotation.value().isBlank()) {
                jsonName = annotation.value();
                break;
            }
        }
        if (isFloorName(jsonName)) {
            return jsonName;
        }
        if (isFloorName(javaName)) {
            return javaName;
        }
        return null;
    }

    private static Field declaredFieldOrNull(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static boolean hasMappingAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (MAPPING_ANNOTATIONS.contains(annotation.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Class enumeration — delegated, deliberately
    // ---------------------------------------------------------------------------------------------

    /**
     * Every controller class under {@code src/main/java} — <b>by annotation, not by filename</b>,
     * loaded uninitialised. Now {@link MainSourceClasses#controllerClasses()}.
     *
     * <p>The first version of this method filtered the source walk to files ending {@code
     * Controller.java}. That is the denominator of a default-deny control, and a filename is not what
     * makes a class serve routes: a {@code @RestController} in {@code Endpoints.java}, or one nested
     * inside another class, was invisible to it — so a floor could reach the wire through a controller
     * this barrier silently was not counting. There is no such class at this HEAD (all 91 {@code
     * *Controller.java} files carry the annotation, and the only other annotated type is {@code
     * GlobalExceptionHandler}, which is {@code @RestControllerAdvice} and serves no route), which is
     * exactly why the hole was worth closing while it costs nothing.
     *
     * <p><b>Why it moved out of this class.</b> {@code CreatorSendGateTest} (D-02) needed the same
     * enumeration and shipped with the same class of hole in a different form — it named one
     * controller, so a send route in any other class was invisible. Two copies of "which classes are
     * controllers" drift silently: whichever one is not edited keeps passing over the classes it stopped
     * seeing. So there is one implementation, in {@link MainSourceClasses}, and both gates call it.
     * The delegating method is kept here, rather than inlining the call at the one use site, because
     * this javadoc is the record of why the filename filter was wrong.
     */
    private static List<Class<?>> controllerClasses() throws IOException {
        return MainSourceClasses.controllerClasses();
    }

    /** {@code Outer.Inner}, the name an author would actually search for. */
    private static String nestedSimpleName(Class<?> type) {
        String name = type.getName();
        int lastDot = name.lastIndexOf('.');
        return (lastDot < 0 ? name : name.substring(lastDot + 1)).replace('$', '.');
    }

    private static Set<String> minus(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static Set<String> sorted(Set<String> values) {
        return new TreeSet<>(values);
    }
}
