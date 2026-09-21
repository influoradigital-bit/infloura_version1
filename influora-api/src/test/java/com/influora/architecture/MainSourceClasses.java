package com.influora.architecture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunction;

/**
 * T-MEERA-CREATOR-PHASE-B — the one answer in this package to "what classes exist under {@code
 * src/main/java}, and which of them are controllers".
 *
 * <p><b>Why this is a shared class and not a method copied into each gate.</b> Both
 * {@link FloorBarrierTest} (D-01) and {@link CreatorSendGateTest} (D-02) are default-deny controls
 * whose strength is exactly their denominator: whatever set of classes they enumerate is the set they
 * protect, and anything outside it is a hole that no assertion can report. D-01 shipped with that hole
 * once already — it filtered the walk to files named {@code *Controller.java}, so a nested or
 * differently-named {@code @RestController} was not counted — and D-02 shipped it a second time in a
 * different form, by naming {@code CreatorMeeraToolController} directly, so a send route in any other
 * class was invisible. Two copies of "which classes are controllers" is two chances to narrow one of
 * them and not the other, silently: the gate that was not edited keeps passing, and its green is
 * meaningless. One implementation cannot drift from itself. Widening or narrowing it here moves every
 * gate at once, which is the property that makes it reviewable.
 *
 * <p>Deliberately <b>not</b> a method on either test class with the other delegating to it. That
 * would make one gate's scope depend on the other gate's continued existence and shape: deleting or
 * refactoring {@code FloorBarrierTest} — an ordinary thing to do to a test — would silently take
 * D-02's scope with it. A test class is not an API surface. A named helper is, and a compile error is
 * the loudest possible failure if it is removed.
 *
 * <p><b>Not a test class.</b> The name matches none of surefire's default includes ({@code Test*},
 * {@code *Test}, {@code *Tests}, {@code *TestCase}), so it contributes no tests to the suite count.
 *
 * <p><b>Reflection over a source walk, and why.</b> There is no ArchUnit dependency in this project
 * ({@code pom.xml}) and adding one is outside these tasks' authority, so the class list is built by
 * walking {@code src/main/java} for {@code .java} files and loading each resulting name. Classes are
 * loaded with {@code initialize=false}: enumerating hundreds of classes must not run anybody's static
 * initialiser, and annotations, record components and method signatures are all readable without
 * initialisation.
 */
final class MainSourceClasses {

    /**
     * The floor below which {@link #controllerClasses()} refuses to answer.
     *
     * <p>A default-deny gate whose enumeration silently returns a short list does not fail — it
     * passes, for every class it forgot. So the non-vacuity check lives here, in the shared
     * enumeration, rather than only in each caller's assertions: a third gate written next month
     * inherits it without its author having to think of it. 50 is far under the count at this HEAD
     * (91 {@code *Controller.java} files, all annotated), so this is a tripwire for a broken walk —
     * wrong working directory, a moved source root, a classloader that resolves nothing — not a
     * number anyone has to maintain.
     */
    private static final int MINIMUM_EXPECTED_CONTROLLERS = 50;

    /**
     * The reactive router type, by name: {@code spring-webflux} is not a dependency at this HEAD
     * ({@code pom.xml} declares {@code spring-boot-starter-web} only), so it cannot be a class literal
     * without breaking compilation. Resolved optionally at class-init so a module switch that adds
     * webflux is covered the day it lands rather than quietly reopening the hole.
     */
    private static final String REACTIVE_ROUTER_FUNCTION =
            "org.springframework.web.reactive.function.server.RouterFunction";

    /**
     * Framework types whose appearance as a bean factory method's <b>declared return type</b> means
     * that method registers HTTP routes outside the {@code @Controller} stereotype.
     *
     * <p><b>Matched by assignability, never by name equality.</b> The first version of this check was
     * {@code Set<String>} of fully-qualified names compared with {@code contains(returnType)}, and a
     * single subtype defeated it: three live, ungated send routes went past it, each leaving the suite
     * at {@code Failures: 0}. A {@code @Bean} returning {@code ProbeSendRouter implements
     * RouterFunction<ServerResponse>} is a live route — {@code RouterFunctionMapping} collects its
     * beans with {@code getBeanNamesForType(RouterFunction.class)}, which is itself assignability —
     * yet the declared name {@code com.…ProbeSendRouter} is in no plausible name set. Declaring a bean
     * as its own implementation type is ordinary Spring, not an exotic evasion, so the check must be
     * the same relation the container uses: {@code framework.isAssignableFrom(returnType)}. That
     * relation is pinned by a standing assertion — see {@code
     * CreatorSendGateTest#theFunctionalEndpointDetectorMatchesBySubtypeNotByNameEquality()} — because
     * a silent regression to string comparison is exactly what happened, twice.
     *
     * <p>Because matching is assignability, a supertype subsumes its subtypes: {@link HandlerMapping}
     * alone covers {@code AbstractHandlerMapping}, {@code SimpleUrlHandlerMapping} and {@code
     * RequestMappingHandlerMapping}, which the name-equality version had to list one by one and which
     * are therefore no longer listed. {@link ServletContextInitializer} likewise subsumes {@link
     * ServletRegistrationBean} and {@link FilterRegistrationBean}; those two are kept explicit anyway,
     * because they are the shapes an author actually writes and a reader of a failure message should
     * see the name they typed. Nothing the name-equality version caught is uncovered here.
     */
    private static final List<Class<?>> ROUTE_REGISTERING_RETURN_TYPES =
            buildRouteRegisteringReturnTypes();

    private static List<Class<?>> buildRouteRegisteringReturnTypes() {
        List<Class<?>> types =
                new ArrayList<>(
                        List.of(
                                RouterFunction.class,
                                HandlerMapping.class,
                                ServletContextInitializer.class,
                                ServletRegistrationBean.class,
                                FilterRegistrationBean.class));
        Class<?> reactiveRouterFunction = loadOrNull(REACTIVE_ROUTER_FUNCTION);
        if (reactiveRouterFunction != null) {
            types.add(reactiveRouterFunction);
        }
        return List.copyOf(types);
    }

    /**
     * The framework types {@link #routeRegisteringDeclarationsOn(Class)} matches against, exposed so a
     * gate can assert the set has not been silently narrowed. A default-deny control whose type list
     * shrinks does not fail — it passes, for every shape it stopped knowing about.
     */
    static List<Class<?>> routeRegisteringReturnTypes() {
        return ROUTE_REGISTERING_RETURN_TYPES;
    }

    private MainSourceClasses() {}

    /**
     * Every controller class under {@code src/main/java} — <b>by annotation, not by filename, and not
     * by name</b>, loaded uninitialised.
     *
     * <p>{@link AnnotatedElementUtils#hasAnnotation} is used rather than {@code
     * isAnnotationPresent} so that {@code @RestController} — which is meta-annotated {@code
     * @Controller} — is matched without enumerating stereotypes, and so a project-specific composed
     * stereotype would be matched too. {@code @ControllerAdvice}/{@code @RestControllerAdvice} are
     * meta-annotated {@code @Component}, not {@code @Controller}, so advice classes stay out
     * ({@code GlobalExceptionHandler} is the only one at this HEAD, and it serves no route).
     *
     * <p><b>Route-registration shapes this cannot see</b>, stated rather than implied. The first is a
     * functional endpoint. A {@code RouterFunction} bean built with {@code
     * RequestPredicates.POST("/x")}, or a hand-registered {@code RequestMappingHandlerMapping} entry,
     * serves a live route while carrying no {@code @Controller} stereotype anywhere, so no
     * annotation-based enumeration can find it. This is not hypothetical: a {@code @Configuration}
     * class returning {@code RouterFunctions.route().POST("/internal/meera/creator/send_routine_reply",
     * …).build()} was compiled into {@code src/main/java} and D-02's scan stayed green over it. Rather
     * than leave a proven hole documented and open, {@link #functionalEndpointDeclarations()} makes the
     * <i>assumption itself</i> the thing that is checked — for the shapes it can see. The shapes it
     * cannot are enumerated as declared gaps on that method, and they are real: read them before
     * treating a green run here as "no unannotated route exists".
     *
     * @throws IllegalStateException if the walk finds implausibly few controllers, rather than letting
     *     every caller's default-deny assertion pass over a list that is short because the walk broke
     */
    static List<Class<?>> controllerClasses() throws IOException {
        List<Class<?>> controllers = new ArrayList<>();
        for (Class<?> candidate : classesUnder("com/influora")) {
            if (AnnotatedElementUtils.hasAnnotation(candidate, Controller.class)) {
                controllers.add(candidate);
            }
        }
        if (controllers.size() < MINIMUM_EXPECTED_CONTROLLERS) {
            throw new IllegalStateException(
                    "MainSourceClasses.controllerClasses() found only "
                            + controllers.size()
                            + " @Controller classes under "
                            + mainSourceRoot()
                            + ", which is below the sanity floor of "
                            + MINIMUM_EXPECTED_CONTROLLERS
                            + ". Every architecture gate in this package is default-deny over this"
                            + " list, so a short list means those gates are passing for the classes"
                            + " they no longer see. Fix the walk; do not lower the floor.");
        }
        return controllers;
    }

    /**
     * Bean factory methods anywhere in {@code src/main/java} that register HTTP routes <b>without</b> a
     * {@code @Controller} stereotype — the one shape {@link #controllerClasses()} structurally cannot
     * enumerate. Empty at this HEAD; every entry is a route no annotation-based gate can see.
     *
     * <p><b>Why this exists instead of a comment.</b> {@link #controllerClasses()} is the denominator of
     * two default-deny gates, and both of them rest on an unstated assumption: that every route in this
     * codebase is declared by an annotated handler method. That assumption was tested and it holds
     * today — but "holds today" is what a comment records and what nothing enforces. A {@code
     * @Configuration} class with a single {@code RouterFunction} bean would serve {@code
     * send_routine_reply} with no flag read and turn neither gate red, which is D-02's original defect
     * in a third form. So the assumption is asserted rather than commented.
     *
     * <p><b>What the assertion actually covers, stated exactly.</b> A method declared anywhere under
     * {@code src/main/java} whose <i>declared return type is assignable to</i> one of {@link
     * #routeRegisteringReturnTypes()}. That is the shape a factory method for a functional endpoint, a
     * hand-built handler mapping, or a servlet/filter registration takes, and it is matched on the same
     * relation the container itself uses, so declaring the bean as its own implementation type does not
     * evade it. The author of such a commit is the one who has to extend the gates: a live {@code
     * RouterFunctionMapping}/{@code RequestMappingHandlerMapping} bean-map assertion inside a {@code
     * @SpringBootTest} is the mechanism, and it is only writable once such an endpoint exists.
     *
     * <p><b>An earlier version of this javadoc claimed more than the code did</b> — that "introducing
     * the first functional endpoint to this codebase goes red here", and that one "may not add one
     * silently". That was false as written. It held only while the bean was declared <i>as the
     * interface itself</i>: the check was fully-qualified-name equality on the declared return type, so
     * one subtype defeated it, and the tester got a {@code @Bean} returning its own implementation type
     * past it with the suite at {@code Failures: 0}. The claim is now the narrower true one — <i>a
     * route-registering bean factory method whose declared return type is assignable to a known
     * framework type cannot be added silently</i> — and the gaps below are what is left open.
     *
     * <p><b>Declared gaps: route-registration mechanisms this does NOT cover.</b> Listed because a
     * control with honest gaps is worth more than one that claims completeness and has undocumented
     * ones. Each is a hole no assertion in this package reports, and none of them is exotic Spring:
     *
     * <ol>
     *   <li><b>Imperative registration from a method that does not return the route.</b> Only declared
     *       return types are read, so a {@code void} {@code @PostConstruct}, an {@code
     *       ApplicationListener}, a {@code BeanFactoryPostProcessor}, or the <i>body</i> of a {@code
     *       WebMvcConfigurer}/{@code ServletContextInitializer#onStartup} calling {@code
     *       RequestMappingHandlerMapping#registerMapping} or {@code ServletContext#addServlet} on an
     *       injected instance is invisible. This is not hypothetical: {@code
     *       config/PlanGateWebConfig} is a {@code @Configuration} class implementing {@code
     *       WebMvcConfigurer} today, and its {@code void addInterceptors(InterceptorRegistry)} body
     *       registers {@code PlanGateInterceptor} and {@code AnalyticsUsageCapInterceptor} on an
     *       injected registry — exactly the invisible-return-type shape this gap describes, already
     *       live in this file. It is the gap a Wave 4 engineer is most likely to hit next week:
     *       adding another interceptor, or a route via {@code addViewControllers}, to that same
     *       method is routine work, and the detector reads declared return types only, so neither
     *       addition would be seen. This is the widest gap and the cheapest bypass.
     *   <li><b>Anything registered by stereotype rather than by a factory method.</b> Spring Boot
     *       auto-registers every {@code Filter} and {@code Servlet} bean, so a {@code @Component class
     *       XFilter extends OncePerRequestFilter} that answers a path itself declares no method
     *       returning a framework type and is not seen. Five such filter classes exist under {@code
     *       src/main/java} at this HEAD ({@code security/JwtAuthenticationFilter} and siblings) — which
     *       is also why {@code jakarta.servlet.Filter} is not simply added to the type set: being
     *       return-type-based, the detector would still not catch them, because they are not returned
     *       from anywhere.
     *   <li><b>A declared return type the detector cannot relate to a framework type.</b> {@code @Bean
     *       Object router()}, a generic type variable, a {@code FactoryBean<RouterFunction<…>>} or a
     *       {@code Supplier} resolved later: assignability is checked against the <i>declared</i> type,
     *       and none of those is assignable to anything in the set. No {@code FactoryBean} exists in
     *       main source today, so this one is theory rather than a live hole.
     *   <li><b>Routes contributed from outside {@code src/main/java}.</b> The walk covers source files
     *       in that one tree, so a {@code @Controller} or router bean arriving from a dependency jar,
     *       an auto-configuration listed in {@code AutoConfiguration.imports}, or generated code under
     *       {@code target/generated-sources} is outside the denominator entirely.
     *   <li><b>Routes served outside this JVM.</b> {@code docker/nginx.conf.template} rewrites and the
     *       separate {@code influora-ai} service are reachable HTTP surface no Java reflection can see.
     *   <li><b>Reactive routers while webflux is absent.</b> {@link #REACTIVE_ROUTER_FUNCTION} resolves
     *       to {@code null} at this HEAD and so is not in the set. A commit that adds the dependency
     *       and a reactive router together is caught (the type resolves before the walk runs), but
     *       nothing here asserts the dependency's absence, and no {@code @SpringBootTest} proves the
     *       servlet router mapping is the only one active.
     *   <li><b>Which path a detected bean actually serves.</b> A declared return type says a method
     *       registers routes; it cannot say <i>which</i>. So the gate over this list is a pin ({@code
     *       CreatorSendGateTest.EXPECTED_ROUTE_REGISTERING_DECLARATIONS}) with no independent second
     *       condition behind it, unlike the controller-route pin next to it, which has the flag-reader
     *       check. Widening that pin does silence the assertion. It records a reviewed decision; it does
     *       not prove the route is gated. The live bean-map assertion the failure message asks for is
     *       what would, and it is only writable once such an endpoint exists.
     * </ol>
     *
     * <p>Detected by <b>return type</b>, reflectively, not by a source-text scan: this repo has already
     * shipped two grep gates that went red on the comment explaining the pattern they banned, and the
     * text above contains the word {@code RouterFunction} many times over. A class whose methods cannot
     * be read at all is reported as an offence rather than skipped — an unreadable class is exactly the
     * case where silence would be indistinguishable from safety.
     */
    static List<String> functionalEndpointDeclarations() throws IOException {
        List<String> found = new ArrayList<>();
        for (Class<?> candidate : classesUnder("com/influora")) {
            found.addAll(routeRegisteringDeclarationsOn(candidate));
        }
        return found;
    }

    /**
     * The route-registration detector for a single class, split out of {@link
     * #functionalEndpointDeclarations()} so a gate can point it at a class of its own and prove the
     * mechanism still works. Without this seam the detector could only ever be exercised by the walk
     * whose emptiness it is supposed to make meaningful — and an empty result from a detector that has
     * quietly stopped detecting is indistinguishable from safety, which is this control's whole subject.
     *
     * <p>{@code framework.isAssignableFrom(method.getReturnType())}, never name equality. See {@link
     * #ROUTE_REGISTERING_RETURN_TYPES} for why that distinction is the entire point.
     */
    static List<String> routeRegisteringDeclarationsOn(Class<?> candidate) {
        List<String> found = new ArrayList<>();
        Method[] methods;
        try {
            methods = candidate.getDeclaredMethods();
        } catch (LinkageError e) {
            found.add(
                    candidate.getName()
                            + "  (methods unreadable: "
                            + e.getClass().getSimpleName()
                            + " -- cannot be shown to declare no functional endpoint)");
            return found;
        }
        for (Method method : methods) {
            Class<?> returnType;
            try {
                returnType = method.getReturnType();
            } catch (LinkageError e) {
                continue;
            }
            for (Class<?> framework : ROUTE_REGISTERING_RETURN_TYPES) {
                if (framework.isAssignableFrom(returnType)) {
                    found.add(
                            candidate.getName()
                                    + "#"
                                    + method.getName()
                                    + "  returns  "
                                    + returnType.getName()
                                    + "  (assignable to "
                                    + framework.getName()
                                    + ")");
                    break;
                }
            }
        }
        return found;
    }

    /** Every class declared under a source directory, including nested ones, loaded uninitialised. */
    static List<Class<?>> classesUnder(String relativeDir) throws IOException {
        Path root = mainSourceRoot();
        Path dir = root.resolve(relativeDir);
        List<Class<?>> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (Stream<Path> files = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Class<?> loaded = loadOrNull(fullyQualifiedName(root, file));
                if (loaded != null) {
                    addWithNested(loaded, found);
                }
            }
        }
        return found;
    }

    private static void addWithNested(Class<?> type, List<Class<?>> into) {
        if (type.isEnum() || into.contains(type)) {
            return;
        }
        into.add(type);
        Class<?>[] nestedTypes;
        try {
            nestedTypes = type.getDeclaredClasses();
        } catch (LinkageError e) {
            // Same tolerance as loadOrNull: this walk covers the whole main tree, so one
            // unresolvable nested type must not take every gate in this package down.
            return;
        }
        for (Class<?> nested : nestedTypes) {
            addWithNested(nested, into);
        }
    }

    private static String fullyQualifiedName(Path sourceRoot, Path javaFile) {
        String relative = sourceRoot.relativize(javaFile).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
    }

    /**
     * {@code initialize=false} — enumerating hundreds of classes must not run anybody's static
     * initialiser. Annotations and record components are all readable without initialisation.
     */
    private static Class<?> loadOrNull(String fullyQualifiedName) {
        try {
            return Class.forName(fullyQualifiedName, false, MainSourceClasses.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Repo root resolved relative to the working directory, exactly as {@code InfoBarrierTest} does —
     * Maven and IDE working directories differ (module root vs. repo root).
     */
    private static Path mainSourceRoot() throws IOException {
        Path here = Path.of("").toAbsolutePath();
        Path candidate = here.resolve("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = here.resolve("influora-api/src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IOException("Could not locate src/main/java from working directory " + here);
    }
}
