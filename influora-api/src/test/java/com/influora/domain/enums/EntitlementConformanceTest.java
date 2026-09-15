package com.influora.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.domain.entity.Plan;
import com.influora.security.RequiresPlan;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fails when an {@link Entitlement} constant exists with no real enforcement — the gate
 * {@code wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md} §4 calls for. NO Spring context (every
 * {@code @SpringBootTest} in this module dies on Testcontainers/Docker discovery here — same
 * discipline as {@code ConfigurationPropertiesRegistrationTest} / {@code
 * PlanGateFilterRegistrationTest}), and deliberately NOT MockMvc for the same reason.
 *
 * <p><b>Why this is not the vacuous "enum is non-empty" gate.</b> Every check below inspects the
 * actual compiled bytecode (via {@code org.springframework.asm}, already on this project's
 * classpath as part of spring-core), the actual runtime {@link RequiresPlan}/{@code @*Mapping}
 * annotations, or the actual limit-resolution behaviour — never a hand-written string taken at
 * face value. Per-shape, the evidence required:
 *
 * <ul>
 *   <li><b>CAPACITY</b> ({@link Entitlement#SEATS}): a real call site — anywhere under {@code
 *       com.influora} — where {@code EntitlementService.requireCapacity(...)} is invoked with that
 *       exact {@link Entitlement} constant as the only entitlement loaded into the call.
 *   <li><b>METERED, UsageCounterService-backed</b> ({@link Entitlement#CREATOR_ANALYTICS_VIEWS}):
 *       the same check against {@code EntitlementService.consume(...)}.
 *   <li><b>METERED, AI_CREDITS</b>: {@link Entitlement#AI_CREDITS} does not route through {@code
 *       EntitlementService} at all (see that class's javadoc) — its own mechanism, {@code
 *       AICreditService}, has a daily hard cap and refund/release semantics {@code
 *       EntitlementService.consume} does not model. Evidence here is a real call site to {@code
 *       AICreditService.tryConsume}/{@code tryConsumeForTurn} from outside that class.
 *   <li><b>FLAG</b> ({@link Entitlement#EXPORT}, {@link Entitlement#CAMPAIGN_TEMPLATES}): a real
 *       runtime method annotated {@code @RequiresPlan(feature = PlanFeature.&lt;matching name&gt;)}.
 *   <li><b>RATE</b> ({@link Entitlement#BRAND_FEE_BPS}): not a gate by design (redesign doc §3.1) —
 *       evidence is a real call site to {@code BrandCampaignFeeService.resolveBrandFeeBps}/{@code
 *       chargeOnPublish} from OUTSIDE that class.
 * </ul>
 *
 * <p><b>Wiring alone is not enforcement</b> (2026-09-12 rework). The first cut of this gate stopped
 * at the bytecode co-occurrence above, and that was provably not enough: {@code
 * EntitlementService}'s static primitives took the already-resolved limit as a parameter and never
 * read their {@code entitlement} argument, so changing {@code OptionalInt.of(plan.getSeatLimit())}
 * to {@code OptionalInt.empty()} in {@code WorkspaceMemberService} disabled seat enforcement
 * outright while this test stayed green. The primitives now resolve the limit from the constant
 * ({@link Entitlement#limitIn}), and {@link #limitShapedEntitlementsResolveFromDistinctPlanState()}
 * asserts that resolution against distinct sentinel plan values — so the "make it unlimited" and
 * "point it at the wrong column" mutations are both RED here now, not just the "delete the call
 * site" one.
 *
 * <p><b>Scanner precision.</b> The CAPACITY/METERED scan is still method-scoped rather than a real
 * data-flow analysis, but it is no longer a blanket "any constant anywhere in this method body".
 * It credits a constant only when (a) its {@code GETSTATIC} precedes the primitive call, (b) it is
 * the ONLY entitlement constant loaded since the previous primitive call in that method, and (c)
 * the invoked descriptor actually takes an {@link Entitlement} parameter. A method that loads two
 * different constants before one gate call credits NEITHER (recorded in {@link #ambiguousCallSites}
 * and surfaced in the failure message) rather than over-crediting both — a gate must fail closed.
 * What remains uncovered: a method that loads constant A, gates on A, then separately loads B and
 * gates on B would credit both, which is correct; and a constant passed through a local variable
 * or a field is credited to nothing, which fails closed.
 *
 * <p><b>Disclosed blind spot — BRAND_FEE_BPS has two callers, this checks for one.</b> The RATE
 * evidence is "somebody outside {@code BrandCampaignFeeService} calls it", and the brand fee is
 * charged from TWO independent publish paths: {@code CampaignService.publish} and {@code
 * ConfirmLaunchExecutor.doExecute} (Meera's launch tool). Falsified 2026-09-12: deleting BOTH calls
 * turns this RED; deleting only the {@code CampaignService} one leaves it GREEN even though brands
 * publishing through the normal UI would then be charged nothing. Closing that would need a
 * per-publish-path gate naming both call sites, which belongs with the fee service, not with this
 * registry check — recorded here so the next reader does not mistake this for coverage it has not
 * got.
 *
 * <p><b>Disclosed falsification gap — AI_CREDITS.</b> {@code AICreditService.tryConsumeForTurn}'s
 * only production call site is {@code MeeraSessionService.doSendTurn}, and {@code
 * MeeraSessionService.java} is on the do-not-edit list for this work (a concurrent session owns
 * it). The AI_CREDITS branch of {@link #everyEntitlementIsEnforced} has therefore never been
 * falsified against its real call site — only the detection mechanism was, against a scratch clone.
 * That branch is one {@code assertThat(aiCreditsEvidence).isTrue()}; a previous revision also
 * carried a second test asserting the identical line under the name "documents the gap", which
 * disclosed nothing the branch did not already assert and has been deleted rather than left
 * standing as an apparently independent check.
 */
class EntitlementConformanceTest {

    private static final String ENTITLEMENT_SERVICE_OWNER = "com/influora/service/EntitlementService";
    private static final String AI_CREDIT_SERVICE_OWNER = "com/influora/service/meera/AICreditService";
    private static final String BRAND_FEE_SERVICE_OWNER = "com/influora/service/BrandCampaignFeeService";
    private static final String ENTITLEMENT_OWNER = "com/influora/domain/enums/Entitlement";
    private static final String ENTITLEMENT_ENUM_DESC = "Lcom/influora/domain/enums/Entitlement;";

    private static Set<String> capacityEvidence;
    private static Set<String> meteredEvidence;
    private static List<String> ambiguousCallSites;
    private static boolean aiCreditsEvidence;
    private static boolean brandFeeBpsEvidence;
    private static Set<PlanFeature> flagEvidence;
    private static Set<String> declaredRoutes;

    @BeforeAll
    static void scanCompiledClasses() throws Exception {
        capacityEvidence = new HashSet<>();
        meteredEvidence = new HashSet<>();
        ambiguousCallSites = new ArrayList<>();
        flagEvidence = new HashSet<>();
        declaredRoutes = new HashSet<>();

        for (Path classFile : allInfluoraClassFiles()) {
            scanBytecode(Files.readAllBytes(classFile));
        }

        for (Class<?> clazz : allInfluoraClasses()) {
            for (Method method : safeDeclaredMethods(clazz)) {
                RequiresPlan requiresPlan = method.getAnnotation(RequiresPlan.class);
                if (requiresPlan != null) {
                    flagEvidence.add(requiresPlan.feature());
                }
            }
            collectRoutes(clazz);
        }
    }

    private static void scanBytecode(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    private String currentClassName;

                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        this.currentClassName = name;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        final String methodName = name;
                        return new MethodVisitor(Opcodes.ASM9) {
                            /**
                             * Entitlement constants loaded since the last gate call in this method
                             * body, in bytecode order. Exactly one at the call = unambiguous
                             * evidence; more than one = ambiguous, credited to nobody.
                             */
                            private final List<String> pending = new ArrayList<>();

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                                if (opcode == Opcodes.GETSTATIC
                                        && owner.equals(ENTITLEMENT_OWNER)
                                        && fieldDesc.equals(ENTITLEMENT_ENUM_DESC)
                                        && !pending.contains(fieldName)) {
                                    pending.add(fieldName);
                                }
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String calledName, String calledDesc, boolean isInterface) {
                                boolean capacityCall =
                                        owner.equals(ENTITLEMENT_SERVICE_OWNER) && calledName.equals("requireCapacity");
                                boolean meteredCall =
                                        owner.equals(ENTITLEMENT_SERVICE_OWNER) && calledName.equals("consume");

                                if (capacityCall || meteredCall) {
                                    // (c) the primitive must actually take an Entitlement -- a
                                    // primitive that does not cannot be enforcing one.
                                    if (calledDesc.contains(ENTITLEMENT_ENUM_DESC)) {
                                        if (pending.size() == 1) {
                                            (capacityCall ? capacityEvidence : meteredEvidence).add(pending.get(0));
                                        } else if (pending.size() > 1) {
                                            ambiguousCallSites.add(
                                                    currentClassName + "#" + methodName + " -> " + calledName + " "
                                                            + pending);
                                        }
                                    }
                                    pending.clear();
                                    return;
                                }

                                if (owner.equals(AI_CREDIT_SERVICE_OWNER)
                                        && (calledName.equals("tryConsume") || calledName.equals("tryConsumeForTurn"))
                                        // From OUTSIDE the class only: AICreditService.tryConsumeForTurn calls
                                        // its own tryConsume(workspaceId, cost) internally, which would
                                        // otherwise make this check vacuously true regardless of whether
                                        // MeeraSessionService (or anything else) really calls it. Caught by
                                        // falsifying this exact check against a scratch-mutated clone of
                                        // MeeraSessionService.java (see the class javadoc) -- the naive
                                        // unqualified check stayed "true" even with the real call site
                                        // removed, because of this exact self-call.
                                        && !AI_CREDIT_SERVICE_OWNER.equals(currentClassName)) {
                                    aiCreditsEvidence = true;
                                } else if (owner.equals(BRAND_FEE_SERVICE_OWNER)
                                        && (calledName.equals("resolveBrandFeeBps") || calledName.equals("chargeOnPublish"))
                                        // "from OUTSIDE that class" per class javadoc -- BrandCampaignFeeService's
                                        // own internal call from chargeOnPublish to resolveBrandFeeBps must not
                                        // count as its own evidence of a real caller.
                                        && !BRAND_FEE_SERVICE_OWNER.equals(currentClassName)) {
                                    brandFeeBpsEvidence = true;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    // ===================== per-constant assertions =====================

    @ParameterizedTest(name = "{0} has real enforcement")
    @EnumSource(Entitlement.class)
    @DisplayName("every Entitlement constant is enforced somewhere real, per its own Shape")
    void everyEntitlementIsEnforced(Entitlement entitlement) {
        switch (entitlement.shape()) {
            case CAPACITY ->
                    assertThat(capacityEvidence)
                            .as(
                                    "%s is CAPACITY-shaped and declares endpoint %s %s, but no call site under"
                                        + " com.influora invokes EntitlementService.requireCapacity(...) with"
                                        + " Entitlement.%s as the one entitlement loaded into that call. Wire"
                                        + " it, or this constant is exactly the SAVED_CREATORS failure mode the"
                                        + " redesign doc diagnosed: sold/declared, never enforced.%s",
                                    entitlement,
                                    entitlement.httpMethod(),
                                    entitlement.endpoint(),
                                    entitlement,
                                    ambiguityNote())
                            .contains(entitlement.name());
            case METERED -> {
                if (entitlement == Entitlement.AI_CREDITS) {
                    assertThat(aiCreditsEvidence)
                            .as(
                                    "AI_CREDITS is METERED via its own mechanism (AICreditService), not"
                                        + " EntitlementService.consume -- no call site under com.influora"
                                        + " invokes AICreditService.tryConsume/tryConsumeForTurn.")
                            .isTrue();
                } else {
                    assertThat(meteredEvidence)
                            .as(
                                    "%s is METERED-shaped and declares endpoint %s %s, but no call site under"
                                        + " com.influora invokes EntitlementService.consume(...) with"
                                        + " Entitlement.%s as the one entitlement loaded into that call.%s",
                                    entitlement,
                                    entitlement.httpMethod(),
                                    entitlement.endpoint(),
                                    entitlement,
                                    ambiguityNote())
                            .contains(entitlement.name());
                }
            }
            case FLAG -> {
                PlanFeature matching = matchingPlanFeature(entitlement);
                assertThat(matching)
                        .as(
                                "%s is FLAG-shaped with no matching com.influora.domain.enums.PlanFeature"
                                    + " constant of the same name -- the RequiresPlan/PlanFeature mapping"
                                    + " this conformance check relies on does not exist for it.",
                                entitlement)
                        .isNotNull();
                assertThat(flagEvidence)
                        .as(
                                "%s is FLAG-shaped and declares endpoint %s %s, but no method under"
                                    + " com.influora.web carries @RequiresPlan(feature = PlanFeature.%s).",
                                entitlement, entitlement.httpMethod(), entitlement.endpoint(), matching)
                        .contains(matching);
            }
            case RATE ->
                    assertThat(brandFeeBpsEvidence)
                            .as(
                                    "%s is RATE-shaped (money path, not a gate) but no call site outside"
                                        + " BrandCampaignFeeService invokes resolveBrandFeeBps/chargeOnPublish --"
                                        + " it is resolved nowhere real.",
                                    entitlement)
                            .isTrue();
        }
    }

    private static String ambiguityNote() {
        return ambiguousCallSites.isEmpty()
                ? ""
                : " NOTE: these gate call sites loaded more than one Entitlement constant and were"
                        + " therefore credited to NEITHER (the scan fails closed): "
                        + ambiguousCallSites;
    }

    /**
     * The enforcement half of this gate: wiring a constant into a call proves a marker was written
     * down, not that anything is capped. This asserts that each limit-shaped constant resolves to
     * the plan column it is supposed to, using values distinct from each other so that both the
     * "make it unlimited" mutation ({@code OptionalInt.empty()}) and the "point it at the wrong
     * column" mutation are caught. The two production gates resolve their limit through exactly
     * this method ({@code EntitlementService.requireCapacity}/{@code consume} call {@link
     * Entitlement#limitIn}), so this is the behaviour they run, not a parallel re-implementation.
     */
    @Test
    @DisplayName("the Entitlement constant, not the call site, decides which plan limit is enforced")
    void limitShapedEntitlementsResolveFromDistinctPlanState() {
        Plan plan =
                Plan.builder()
                        .id("plan-sentinel")
                        .code(PlanCode.PRO)
                        .name("Sentinel")
                        .seatLimit(7)
                        .creatorAnalyticsMonthlyLimit(3)
                        .aiMonthlyAllotment(11)
                        .build();

        assertThat(Entitlement.SEATS.limitIn(plan))
                .as(
                        "SEATS must resolve to the plan's seat_limit column (7). Empty here means seat"
                            + " enforcement is disabled outright: EntitlementService.requireCapacity"
                            + " returns without comparing anything when the limit is empty.")
                .hasValue(7);
        assertThat(Entitlement.CREATOR_ANALYTICS_VIEWS.limitIn(plan))
                .as("CREATOR_ANALYTICS_VIEWS must resolve to creator_analytics_monthly_limit (3)")
                .hasValue(3);
        assertThat(Entitlement.AI_CREDITS.limitIn(plan))
                .as("AI_CREDITS must resolve to ai_monthly_allotment (11)")
                .hasValue(11);

        Plan unlimitedAnalytics =
                Plan.builder()
                        .id("plan-sentinel-2")
                        .code(PlanCode.PRO)
                        .name("Sentinel")
                        .seatLimit(7)
                        .creatorAnalyticsMonthlyLimit(null)
                        .aiMonthlyAllotment(11)
                        .build();
        assertThat(Entitlement.CREATOR_ANALYTICS_VIEWS.limitIn(unlimitedAnalytics))
                .as("a null creator_analytics_monthly_limit column is the ONLY unlimited case (Pro)")
                .isEmpty();
        assertThat(Entitlement.SEATS.limitIn(unlimitedAnalytics))
                .as("SEATS is never unlimited -- seat_limit is a NOT NULL column")
                .hasValue(7);
    }

    /**
     * (d) The declared HTTP boundary of every non-RATE constant must name a route a controller
     * really handles. Written after {@link Entitlement#AI_CREDITS} was found declaring {@code POST
     * /meera/chat} — an endpoint that has never existed — and two others naming a {@code {id}} path
     * variable no handler declares. Matching is exact against the concatenated class-level +
     * method-level mapping, path-variable names included, so the metadata cannot drift back into
     * plausible-looking fiction.
     */
    @ParameterizedTest(name = "{0}''s declared endpoint exists")
    @EnumSource(Entitlement.class)
    @DisplayName("every declared HTTP boundary resolves to a real controller handler")
    void everyDeclaredEndpointResolvesToARealHandler(Entitlement entitlement) {
        assertThat(declaredRoutes)
                .as("sanity: the controller scan must find routes at all")
                .isNotEmpty();

        if (entitlement.endpoint() == null) {
            assertThat(entitlement.shape())
                    .as("%s declares no endpoint; only RATE entitlements may do that", entitlement)
                    .isEqualTo(Entitlement.Shape.RATE);
            return;
        }

        assertThat(declaredRoutes)
                .as(
                        "%s declares it is enforced at %s %s, but no @RestController under com.influora"
                            + " maps that route. Either the route moved/never existed (fix the constant)"
                            + " or the endpoint was deleted (the entitlement is now unenforceable).",
                        entitlement, entitlement.httpMethod(), entitlement.endpoint())
                .contains(entitlement.httpMethod() + " " + entitlement.endpoint());
    }

    private static PlanFeature matchingPlanFeature(Entitlement entitlement) {
        for (PlanFeature feature : PlanFeature.values()) {
            if (feature.name().equals(entitlement.name())) {
                return feature;
            }
        }
        return null;
    }

    // ===================== controller route discovery =====================

    private static void collectRoutes(Class<?> clazz) {
        if (clazz.getAnnotation(RestController.class) == null && clazz.getAnnotation(Controller.class) == null) {
            return;
        }
        RequestMapping base = clazz.getAnnotation(RequestMapping.class);
        List<String> basePaths = base == null ? List.of("") : pathsOf(base.value(), base.path());

        for (Method method : safeDeclaredMethods(clazz)) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                addRoutes(basePaths, pathsOf(get.value(), get.path()), "GET");
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                addRoutes(basePaths, pathsOf(post.value(), post.path()), "POST");
            }
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (put != null) {
                addRoutes(basePaths, pathsOf(put.value(), put.path()), "PUT");
            }
            PatchMapping patch = method.getAnnotation(PatchMapping.class);
            if (patch != null) {
                addRoutes(basePaths, pathsOf(patch.value(), patch.path()), "PATCH");
            }
            DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
            if (delete != null) {
                addRoutes(basePaths, pathsOf(delete.value(), delete.path()), "DELETE");
            }
            RequestMapping generic = method.getAnnotation(RequestMapping.class);
            if (generic != null) {
                for (RequestMethod verb : generic.method()) {
                    addRoutes(basePaths, pathsOf(generic.value(), generic.path()), verb.name());
                }
            }
        }
    }

    private static void addRoutes(List<String> basePaths, List<String> methodPaths, String verb) {
        for (String basePath : basePaths) {
            for (String methodPath : methodPaths) {
                String joined = (basePath + methodPath).replace("//", "/");
                declaredRoutes.add(verb + " " + (joined.isEmpty() ? "/" : joined));
            }
        }
    }

    private static List<String> pathsOf(String[] value, String[] path) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(List.of(value));
        paths.addAll(List.of(path));
        return paths.isEmpty() ? List.of("") : List.copyOf(paths);
    }

    // ===================== compiled-classes discovery =====================

    private static Path classesRoot() throws URISyntaxException {
        var location = Entitlement.class.getProtectionDomain().getCodeSource().getLocation();
        return new File(location.toURI()).toPath();
    }

    private static List<Path> allInfluoraClassFiles() throws IOException, URISyntaxException {
        Path root = classesRoot().resolve("com").resolve("influora");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> found = walk.filter(p -> p.toString().endsWith(".class")).toList();
            assertThat(found).as("sanity: must find compiled com.influora classes at all").isNotEmpty();
            return found;
        }
    }

    private static List<Class<?>> allInfluoraClasses() throws IOException, URISyntaxException {
        Path root = classesRoot();
        List<Class<?>> classes = new ArrayList<>();
        for (Path classFile : allInfluoraClassFiles()) {
            String relative = root.relativize(classFile).toString();
            String className =
                    relative.substring(0, relative.length() - ".class".length()).replace(File.separatorChar, '.');
            try {
                classes.add(Class.forName(className, false, EntitlementConformanceTest.class.getClassLoader()));
            } catch (Throwable unloadable) {
                // Anonymous/synthetic/lambda classes and the rare class with an unresolved
                // dependency at classload time are not what this scan is looking for (annotated
                // controller methods) -- skip rather than fail the whole scan on them.
            }
        }
        return classes;
    }

    private static List<Method> safeDeclaredMethods(Class<?> clazz) {
        try {
            return List.of(clazz.getDeclaredMethods());
        } catch (Throwable unloadable) {
            return List.of();
        }
    }
}
