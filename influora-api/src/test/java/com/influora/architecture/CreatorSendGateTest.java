package com.influora.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.service.meera.CreatorToolScopes;
import com.influora.web.CreatorMeeraToolController;
import jakarta.servlet.http.HttpServlet;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

/**
 * T-MEERA-CREATOR-PHASE-B, defect D-02 (PENDING-0912.md; QA-TECH-0912.md question 15) — the control
 * that makes the commit adding a {@code send_routine_reply} route stop and deal with the standing
 * grant it activates.
 *
 * <p><b>The defect this exists for.</b> {@code send_routine_reply} is in the {@code scope} claim of
 * every level-1 and level-2 creator's on-behalf token today ({@code CreatorToolScopes.SCOPE_LEVEL_1}),
 * minted per turn, because scope is deliberately a ceiling and {@code
 * OnBehalfAuthResolver#requireScope} is bare string membership against that claim with no registry
 * behind it. The name is inert only because no route serves it. So there is no intermediate state in
 * which the route exists and the grant does not — the capability goes live on the code deploy that
 * adds the route, for a tool that puts a message in front of a brand under the creator's name.
 *
 * <p><b>And nothing noticed.</b> {@code MeeraContextServiceTest#testCreatorContextCarriesWiredToolNames}
 * is the test that looks like it covers this and does not: it asserts set equality between {@code
 * CreatorToolScopes.WIRED_TOOL_NAMES} and the controller's {@code @PostMapping} set, so a route
 * landing together with its name — which is what {@code CreatorToolScopes}' own javadoc instructs —
 * makes it go <b>green on the very commit that turns the send live</b>. It is a wiring-consistency
 * check, not a capability gate.
 *
 * <p><b>The scale correction, recorded so nobody re-inflates it — and the part of it that was wrong
 * the first time.</b> On-behalf tokens are minted with a hard 120-second TTL ({@code
 * OnBehalfTokenService.MAX_TTL_SECONDS}, applied directly as {@code exp} and checked by {@code
 * verify}), so a token minted before the route deploys expires long before it: "tokens already minted
 * become live send grants" is a two-minute tail, not a durable grant. That is the whole of the
 * correction. What it is <b>not</b> is a claim that the token is server-side or single-use — it is
 * neither, and an earlier draft of this paragraph implied otherwise by calling the tail "not a bearer
 * grant". It is exactly a bearer grant: {@code CreatorMeeraController#sendTurn} returns {@code
 * result.onBehalfToken()} inside {@code MeeraDtos.SendTurnResponse}, in the HTTP body, to the
 * session-authenticated ({@code @AuthenticationPrincipal}) creator's browser; and {@code OnBehalfAuthResolver}'s class javadoc records that {@code jti} single-use/replay
 * enforcement is <b>not implemented</b>. So within its 120 seconds the token is visible to the
 * browser (and to anything with the response) and replayable an unbounded number of times. The
 * severity is MEDIUM because the window is 120 seconds with no refresh or cache path, not because the
 * credential is confined or spent. Do not restate it as either — a comment asserting a weaker threat
 * model than the real one is how the next author under-protects this.
 *
 * <p><b>Least privilege was considered and rejected — on one argument, not two.</b> Removing {@code
 * send_routine_reply} from {@code SCOPE_LEVEL_1} until the route exists buys no window: the scope is
 * read at mint time, so the claim of every token issued after the deploy names whatever the constant
 * says at that moment — the name and the route go live in the same deploy either way. That argument
 * stands on its own. A second argument, that the populated level-1 constant is what makes {@code
 * scopeFor}'s degrade path work, does <b>not</b>: {@code scopeFor} clamps an unexpected approval level
 * to {@code SCOPE_LEVEL_0} by returning that constant directly, whatever {@code SCOPE_LEVEL_1}
 * happens to contain, so emptying level 1 would not weaken the clamp at all. Keeping the name is
 * therefore acceptable for exactly one reason: <b>the flag gate below holds</b>. "Scope is a ceiling,
 * dispatch is the gate" is not a property of the ceiling — it is conditional on dispatch having a
 * gate, which is what this class enforces and what did not exist before it. If this class is ever
 * deleted or defanged, the ceiling stops being defensible and the name must come out of {@code
 * SCOPE_LEVEL_1}.
 *
 * <p><b>Mechanism.</b> The send-capable name set is <i>derived</i> at runtime from {@code
 * CreatorToolScopes.scopeFor} — the capabilities granted at approval level 1+ and not at level 0 — so
 * a future commit-like tool is covered without anyone editing a list here, and so no {@code static
 * final String} is read directly (javac inlines those into the reading class, which is how a stale
 * value survives an incremental run; {@code scopeFor} is a method and cannot go stale). The route side
 * is read through {@code AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class)} rather
 * than {@code getAnnotation(PostMapping.class)}, because the first version of this gate <b>was
 * bypassable by changing one word</b>: {@code @PostMapping(path = "/send_routine_reply")} is the
 * identical live route, and a raw annotation read resolves no {@code @AliasFor}, so {@code value()}
 * came back empty and the tripwire passed with the pin empty and no flag check. {@code
 * @RequestMapping(method = POST)} was invisible for the same reason. See {@link
 * #mappedRouteSegmentsOn} for every hole that closed and {@link
 * #everyMappingShapeThatCanServeTheSendRouteIsDetected()} for the stub per hole. "Gated on the flag"
 * is checked by searching the serving class's <b>compiled class file</b> for a reference to {@code
 * isCreatorSendEnabled} — the constant pool records the method name of every {@code invokevirtual}, so
 * this cannot be fooled by a javadoc mention the way two grep gates in this repo already were.
 *
 * <p><b>Scope — the part that shipped wrong twice.</b> The detector above was solid and the scan was
 * not: the second version of this gate still asked {@code
 * mappedRouteSegmentsOn(CreatorMeeraToolController.class)}, one hardcoded class, so the send route only
 * had to live in a different file. The tester proved it by adding a real {@code @RestController} in
 * {@code web/} with the creator prefix, {@code @PostMapping("/send_routine_reply")} and no flag read
 * anywhere; this class stayed green at {@code Tests run: 6, Failures: 0}. {@code FloorBarrierTest}
 * (D-01) had already found and fixed the same defect in its own enumeration — a default-deny control is
 * only as wide as its denominator, and "files named {@code *Controller.java}" and "the one class I
 * named" are the same mistake. So the scan now covers <b>every class carrying a {@code @Controller}
 * stereotype</b> under {@code src/main/java}, nested and differently-named ones included, through the
 * single enumeration both gates share ({@link MainSourceClasses}) — one implementation, because two
 * copies of "which classes are controllers" drift and the drift is silent.
 *
 * <p><b>The third form of the same hole, found while falsifying the second.</b> Enumerating {@code
 * @Controller} classes rests on an assumption nobody had written down: that every route in this
 * codebase is declared by an annotated handler method. It is not a safe assumption — a {@code
 * @Configuration} class returning {@code RouterFunctions.route().POST("/internal/meera/creator/
 * send_routine_reply", …).build()} was compiled into {@code src/main/java} and this scan stayed
 * <b>green</b> over a live, ungated send route, because a functional endpoint carries no {@code
 * @Controller} stereotype anywhere. Documenting that as a known limit would have left the third
 * bypass sitting open behind a comment, so the assumption is asserted instead: {@link
 * MainSourceClasses#functionalEndpointDeclarations()} is empty at this HEAD, on both gates' behalf.
 *
 * <p><b>And the fourth form: the assertion above was itself name-equality, and one subtype defeated
 * it.</b> An earlier version of this javadoc claimed "the commit that introduces the first functional
 * endpoint goes red here". That was false as written. The detector compared the declared return type's
 * fully-qualified <i>name</i> against a fixed set, so three more live, ungated send routes went past
 * it at {@code Failures: 0}: a {@code @Bean} returning its own {@code RouterFunction} implementation
 * type, a {@code @Bean} returning a {@code SimpleUrlHandlerMapping} subclass, and a {@code
 * ServletRegistrationBean} mapped to the send path with no Spring MVC in the picture. Matching is now
 * assignability — the same relation the container uses to collect these beans — and {@link
 * #theFunctionalEndpointDetectorMatchesBySubtypeNotByNameEquality()} is the standing assertion that it
 * stays that way, since the thing that kept regressing was the control rather than the code. What the
 * check does and does not cover is enumerated as declared gaps on {@link
 * MainSourceClasses#functionalEndpointDeclarations()}; read them before reading a green run here as
 * "no unannotated route exists".
 *
 * <p><b>The one remaining limit, stated rather than implied.</b> The flag check is <b>class-level</b>:
 * it proves the serving class consults the flag somewhere, not that the send handler specifically
 * does. Accidental omission is caught; deliberate circumvention is not. Rule 3 of the deploy-order
 * ruling — a flag-off refusal test whose code is not {@code ON_BEHALF_SCOPE_INSUFFICIENT} — is what
 * closes that, and it can only be written once the route exists. Interface-declared mappings are
 * <b>not</b> a limit, and an earlier version of this javadoc was wrong to say so — see {@link
 * #mappedRouteSegmentsOn} and the stub for them in {@link
 * #everyMappingShapeThatCanServeTheSendRouteIsDetected()}.
 */
class CreatorSendGateTest {

    /** The accessor that proves the send flag is being consulted. */
    private static final String FLAG_READER = "isCreatorSendEnabled";

    /** The property that gates the send capability, and its required default. */
    private static final String SEND_PROPERTY = "influora.meera.creator-send-enabled";

    private static final String SEND_PROPERTY_PLACEHOLDER =
            "creator-send-enabled: ${MEERA_CREATOR_SEND_ENABLED:false}";

    /**
     * Every send-capable route served by any controller in {@code src/main/java}, pinned as an
     * explicit set of {@code fully.qualified.ControllerClass#route_segment} entries.
     *
     * <p><b>Empty, and that is the assertion.</b> Pinned rather than expressed as "no ungated send
     * route", so the commit that adds {@code send_routine_reply} goes red <b>even if it also adds the
     * flag check</b>. That is deliberate: the author must come here, read the deploy-order rule on
     * {@code CreatorToolScopes}, and record the route in a diff a reviewer will see. Widening this set
     * is not the fix on its own — see the failure message.
     *
     * <p><b>Keyed by class, not by route name alone.</b> The class is half of what a reviewer needs:
     * "{@code send_routine_reply} is allowed" would license the route from anywhere, including a
     * controller with no creator-principal check, while
     * {@code com.influora.web.CreatorMeeraToolController#send_routine_reply} names the gate it is
     * behind. The fully qualified name is used rather than the simple one so a nested controller class
     * cannot collide with a top-level one of the same simple name.
     */
    private static final Set<String> EXPECTED_SEND_CAPABLE_ROUTES = Set.of();

    /**
     * Every bean factory method under {@code src/main/java} that registers HTTP routes outside the
     * {@code @Controller} stereotype, pinned as the exact declaration lines {@link
     * MainSourceClasses#functionalEndpointDeclarations()} produces. Empty at this HEAD.
     *
     * <p><b>Why a pin and not {@code isEmpty()}.</b> It was {@code isEmpty()}, which made the check
     * <i>unsatisfiable</i>: a functional endpoint added for a good reason, correctly gated, could never
     * be green, and the only way to ship it was to delete the assertion. A control with no satisfiable
     * direction does not get extended, it gets removed — so there is a green path, and it runs through a
     * diff a reviewer reads.
     *
     * <p><b>Its one condition, stated rather than implied.</b> Unlike {@link
     * #EXPECTED_SEND_CAPABLE_ROUTES}, which has the independent flag-reader check behind it, this pin is
     * the whole gate for this shape: the declared return type says nothing about which <i>path</i> the
     * bean registers, so nothing here can demand that a router bean consult the send flag without also
     * demanding it of an unrelated static-resource handler mapping. Widening this set therefore does
     * silence this assertion, by design and by necessity. That is why the failure message asks for the
     * live {@code RouterFunctionMapping} bean-map assertion as well: the pin records the decision, it
     * does not prove the route is gated. Listed as a declared gap on {@link
     * MainSourceClasses#functionalEndpointDeclarations()}.
     */
    private static final Set<String> EXPECTED_ROUTE_REGISTERING_DECLARATIONS = Set.of();

    // ---------------------------------------------------------------------------------------------
    // 1 — the derived name set, pinned so the scan can never match nothing
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "D-02(a) — the commit-like tools are exactly the level-1 scope delta, so the route scan"
                    + " below can never be looking for an empty set of names")
    void theSendCapableScopeDeltaIsExactlyTheCommitLikeTools() {
        Set<String> sendCapable = sendCapableToolNames();

        assertThat(sendCapable)
                .as(
                        "the level-1 scope delta is empty, so every send-capable-route assertion in"
                                + " this class is looking for nothing and passes vacuously. Either"
                                + " SCOPE_LEVEL_1 no longer widens SCOPE_LEVEL_0, or scopeFor changed"
                                + " shape.")
                .isNotEmpty();

        assertThat(sendCapable)
                .withFailMessage(
                        """
                        The set of capabilities granted only at creator approval level 1+ has changed.

                          expected: %s
                          actual:   %s

                        This set is derived from CreatorToolScopes.scopeFor(1|2, false) minus
                        scopeFor(0, false) -- it is "the tools a creator only gets once a human has
                        approved her for commit-like actions". A new entry here is a new standing
                        grant in every level-1 token, inert only while it has no route.

                        If you added one deliberately: add it here, and make sure it is covered by the
                        same default-false flag discipline as send_routine_reply (CreatorToolScopes
                        class javadoc, deploy-order rule). Do not delete this assertion.
                        (T-MEERA-CREATOR-PHASE-B, D-02)""",
                        sorted(Set.of("send_routine_reply")),
                        sorted(sendCapable))
                .containsExactlyInAnyOrder("send_routine_reply");
    }

    // ---------------------------------------------------------------------------------------------
    // 2 — the flag itself
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "D-02(b) — the send capability has its own property, it defaults to false, and it is bound"
                    + " to a real placeholder in application.yml rather than only named in javadoc")
    void theSendFlagExistsDefaultsFalseAndIsActuallyBound() throws IOException {
        // The accessor exists and is a boolean read.
        Method reader = flagReaderOrNull();
        assertThat(reader)
                .as(
                        "MeeraCreatorFeatureProperties has no %s() -- D-02's gate has been removed and"
                                + " the send capability has no enable step of its own",
                        FLAG_READER)
                .isNotNull();
        assertThat(reader.getReturnType()).isEqualTo(boolean.class);

        // The @Value default is false, read off the constructor parameter rather than trusted.
        String expression = sendValueExpressionOrNull();
        assertThat(expression)
                .as(
                        "no MeeraCreatorFeatureProperties constructor parameter is annotated"
                                + " @Value(\"${%s:...}\") -- the field may exist but nothing binds it"
                                + " to configuration",
                        SEND_PROPERTY)
                .isNotNull();
        assertThat(expression)
                .as(
                        "the send capability's @Value default is not false. The safe state for a send"
                                + " is off: a default of true means a deploy enables it, which is the"
                                + " exact hazard D-02 is about.")
                .isEqualTo("${" + SEND_PROPERTY + ":false}");

        // And it actually reaches the accessor, in both directions.
        assertThat(new MeeraCreatorFeatureProperties(true, false).isCreatorSendEnabled())
                .as("the send flag is ignored: constructed false, read back true")
                .isFalse();
        assertThat(new MeeraCreatorFeatureProperties(true, true).isCreatorSendEnabled())
                .as(
                        "the send flag is hardcoded off rather than read: constructed true, read back"
                                + " false. A flag that cannot be turned on will be deleted by whoever"
                                + " needs to turn it on.")
                .isTrue();
        assertThat(new MeeraCreatorFeatureProperties(true, true).isCreatorEnabled())
                .as("the two flags are crossed: creator-enabled now reads the send value")
                .isTrue();
        assertThat(new MeeraCreatorFeatureProperties(false, true).isCreatorEnabled())
                .as("the two flags are crossed: creator-enabled now reads the send value")
                .isFalse();

        // A property name in javadoc is fiction without a ${VAR} placeholder in application.yml.
        String yaml = Files.readString(mainResource("application.yml"));
        assertThat(yaml)
                .as(
                        "application.yml has no placeholder binding %s. Without one the env var"
                                + " MEERA_CREATOR_SEND_ENABLED sets nothing and the flag can only ever"
                                + " hold its @Value default -- operators would have no way to enable"
                                + " the capability, and no way to prove it is off.",
                        SEND_PROPERTY)
                .contains(SEND_PROPERTY_PLACEHOLDER);
    }

    // ---------------------------------------------------------------------------------------------
    // 3 — the tripwire
    // ---------------------------------------------------------------------------------------------

    /**
     * The tripwire, over <b>every</b> controller in {@code src/main/java}.
     *
     * <p><b>The scope hole this closed, because it shipped twice.</b> The first version of this test
     * called {@code mappedRouteSegmentsOn(CreatorMeeraToolController.class)} — one named class. So the
     * send route only had to live somewhere else: the tester added a real {@code @RestController} in
     * {@code web/} with the creator prefix, a {@code @PostMapping("/send_routine_reply")} and no flag
     * read anywhere, and this test stayed green at {@code Tests run: 6, Failures: 0} with an ungated
     * live send route on disk. That is the identical defect {@code FloorBarrierTest} (D-01) had already
     * found and fixed next door — a default-deny control is only as wide as its denominator, and
     * "{@code *Controller.java}" and "one class I named" are the same mistake — which is why the
     * enumeration is now shared rather than re-derived here (see {@link MainSourceClasses}).
     */
    @Test
    @DisplayName(
            "D-02(c) — no send-capable route exists on ANY controller in src/main/java, and any that is"
                    + " added must be gated on the default-false send flag")
    void noSendCapableRouteExistsWithoutItsOwnDefaultFalseFlag() throws IOException {
        List<Class<?>> controllers = MainSourceClasses.controllerClasses();
        Set<String> sendCapable = sendCapableToolNames();

        // Non-vacuity, in the three places this can silently stop covering anything: the enumeration,
        // the one class the old version named, and the mapping reflection itself.
        assertThat(controllers)
                .as(
                        "the shared controller enumeration returned %d classes -- too few to be the"
                                + " whole main-source controller surface, so this default-deny scan is"
                                + " passing over the classes it can no longer see",
                        controllers.size())
                .hasSizeGreaterThan(50);
        assertThat(controllers)
                .as(
                        "the controller enumeration does not contain CreatorMeeraToolController, the"
                                + " class this gate previously named outright -- whatever it is"
                                + " enumerating, it is not the controller surface")
                .contains(CreatorMeeraToolController.class);

        // And the assumption the enumeration itself rests on: that every route in this codebase is
        // declared by an annotated handler method, so enumerating @Controller classes enumerates the
        // whole route surface. A functional endpoint is outside that denominator by construction.
        Set<String> routeRegistrations =
                new TreeSet<>(MainSourceClasses.functionalEndpointDeclarations());
        assertThat(routeRegistrations)
                .withFailMessage(
                        """
                        A route is being registered outside the @Controller stereotype:

                          %s

                        declared (expected):
                          %s

                        Both D-01 (FloorBarrierTest) and D-02 (this class) are default-deny over
                        MainSourceClasses.controllerClasses(), which can only enumerate annotated
                        handler classes. A RouterFunction bean serves a live POST route while appearing
                        in no such enumeration -- verified: a @Configuration class declaring
                        POST("/internal/meera/creator/send_routine_reply", ...) compiles and this scan
                        stayed green over it, which is D-02's original defect in a third form. Matching
                        is by ASSIGNABILITY on the declared return type, the same relation the container
                        uses, because the first version compared type names and a bean declared as its
                        own implementation type walked straight past it -- three times.

                        This is not a refusal to let you add one, and pinning the line above is a real
                        green path. But the pin records a decision; it does not prove the route is
                        gated, because a declared return type does not say which path it serves. So do
                        both: pin it here AND assert over the live RouterFunctionMapping /
                        RequestMappingHandlerMapping bean maps in a @SpringBootTest -- which is only
                        writable once such an endpoint exists. Do not delete this assertion.
                        (T-MEERA-CREATOR-PHASE-B, D-02)""",
                        String.join("\n  ", routeRegistrations),
                        String.join("\n  ", sorted(EXPECTED_ROUTE_REGISTERING_DECLARATIONS)))
                .isEqualTo(EXPECTED_ROUTE_REGISTERING_DECLARATIONS);

        Set<String> knownRoutes = mappedRouteSegmentsOn(CreatorMeeraToolController.class);
        assertThat(knownRoutes)
                .as(
                        "no request mapping was found on CreatorMeeraToolController -- the reflection"
                                + " is broken and this assertion cannot pass meaningfully")
                .isNotEmpty();
        assertThat(knownRoutes)
                .as(
                        "CreatorMeeraToolController's known Wave 2/3 routes are missing, so this"
                                + " reflection is not reading the class it thinks it is")
                .contains("get_my_deals", "estimate_my_rate");

        // The scan: every controller, not one.
        Map<Class<?>, Set<String>> offenders = new LinkedHashMap<>();
        int scannedSegments = 0;
        for (Class<?> controller : controllers) {
            Set<String> segments = mappedRouteSegmentsOn(controller);
            scannedSegments += segments.size();
            Set<String> sendRoutes = new TreeSet<>(segments);
            sendRoutes.retainAll(sendCapable);
            if (!sendRoutes.isEmpty()) {
                offenders.put(controller, sendRoutes);
            }
        }

        assertThat(scannedSegments)
                .as(
                        "the scan walked %d controllers but read almost no mapped path segments off"
                                + " them -- the per-class mapping reflection is returning empty and"
                                + " every controller looks route-free",
                        controllers.size())
                .isGreaterThan(200);

        Set<String> found = new TreeSet<>();
        offenders.forEach(
                (controller, routes) ->
                        routes.forEach(route -> found.add(controller.getName() + "#" + route)));

        assertThat(found)
                .withFailMessage(
                        """
                        A send-capable creator route now exists. Read this before changing anything.

                          send-capable routes found: %s
                          declared (expected):       %s
                          controllers scanned:       %d

                        %s is granted to every level-1 and level-2 creator by
                        CreatorToolScopes.SCOPE_LEVEL_1 already, and OnBehalfAuthResolver#requireScope
                        is bare string membership against that claim. There is no intermediate state:
                        the capability is live for every level-1 creator's next turn from the instant
                        this route reaches production. Which class serves it does not change that --
                        this scan covers every @Controller under src/main/java for exactly that reason.

                        Four things, not one (CreatorToolScopes class javadoc, deploy-order rule):
                          1. Gate the route on %s (default false) via
                             MeeraCreatorFeatureProperties.%s(), checked alongside
                             requireFeatureEnabled.
                          2. Deploy influora-api first, influora-ai second, flag false across both.
                          3. Add a test proving the grant is inert with the flag off: a level-1 token
                             whose scope names the tool is refused with a code that is NOT
                             ON_BEHALF_SCOPE_INSUFFICIENT, so the audit trail separates "switched off"
                             from "scope insufficient".
                          4. Only then add the Class#route entry to
                             CreatorSendGateTest.EXPECTED_SEND_CAPABLE_ROUTES.

                        Do not do 4 alone. And note that
                        MeeraContextServiceTest#testCreatorContextCarriesWiredToolNames goes GREEN on
                        this commit by design -- it compares WIRED_TOOL_NAMES to the route set and is
                        not a capability gate. (T-MEERA-CREATOR-PHASE-B, D-02)""",
                        sorted(found),
                        sorted(EXPECTED_SEND_CAPABLE_ROUTES),
                        controllers.size(),
                        String.join(", ", sorted(sendCapable)),
                        SEND_PROPERTY,
                        FLAG_READER)
                .isEqualTo(EXPECTED_SEND_CAPABLE_ROUTES);

        // Independent of the pin above: whichever class serves a send route must demonstrably read
        // the flag. Two reasons to go red, so widening the pinned set alone cannot silence this test.
        for (Map.Entry<Class<?>, Set<String>> offender : offenders.entrySet()) {
            assertThat(referencesFlagReader(offender.getKey()))
                    .withFailMessage(
                            """
                            %s serves send-capable route(s) %s but its compiled class file contains no
                            reference to %s -- the send capability is live with no enable step. Gate it
                            on %s (default false).
                            (T-MEERA-CREATOR-PHASE-B, D-02)""",
                            offender.getKey().getName(),
                            sorted(offender.getValue()),
                            FLAG_READER,
                            SEND_PROPERTY)
                    .isTrue();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4 — the positive control: the two detectors above, proven on inputs that do and do not offend
    // ---------------------------------------------------------------------------------------------

    /**
     * The reason test 3 is not a control that cannot fail.
     *
     * <p>At this HEAD the real controller serves no send-capable route, so test 3's set comparison is
     * empty-against-empty and its flag check does not run. That is inherent — the route it guards does
     * not exist yet — so the two detectors are pointed at synthetic controllers instead: one that
     * declares the send route and never reads the flag, and one that declares it and does. If either
     * detector silently stops working, this test goes red first and test 3's silence stops being
     * evidence of anything.
     */
    @Test
    @DisplayName(
            "D-02(d) — both detectors are proven against stub controllers that do and do not offend,"
                    + " so test 3's empty result is evidence rather than an absence of one")
    void bothDetectorsAreProvenAgainstStubsThatDoAndDoNotOffend() throws IOException {
        // The route detector sees a send-capable @PostMapping...
        assertThat(sendCapableRoutesOn(UngatedSendStub.class))
                .as(
                        "the send-capable ROUTE detector did not flag a controller whose only"
                                + " @PostMapping is /send_routine_reply -- it would not flag the real"
                                + " route either, and test 3 proves nothing")
                .containsExactly("send_routine_reply");

        // ...and does not invent one where there is none.
        assertThat(sendCapableRoutesOn(ReadOnlyStub.class))
                .as(
                        "the send-capable ROUTE detector flagged a read-only route, so it would flag"
                                + " the four real Wave 2/3 routes and test 3 would be red for the"
                                + " wrong reason")
                .isEmpty();

        // The flag detector distinguishes a handler that consults the flag from one that does not.
        assertThat(referencesFlagReader(UngatedSendStub.class))
                .as(
                        "the FLAG detector reported a class that never calls %s as gated -- it would"
                                + " pass an ungated real send route too",
                        FLAG_READER)
                .isFalse();
        assertThat(referencesFlagReader(GatedSendStub.class))
                .as(
                        "the FLAG detector could not see a direct %s() call in a compiled class --"
                                + " the constant-pool scan is broken, and it would report a correctly"
                                + " gated route as ungated",
                        FLAG_READER)
                .isTrue();
    }

    /** Declares the send route and never consults the flag — what the tripwire must catch. */
    static final class UngatedSendStub {
        @PostMapping("/send_routine_reply")
        public String send() {
            return "sent";
        }
    }

    /** Declares the send route and consults the flag — what the tripwire must accept. */
    static final class GatedSendStub {
        private final MeeraCreatorFeatureProperties featureProperties =
                new MeeraCreatorFeatureProperties(true, false);

        @PostMapping("/send_routine_reply")
        public String send() {
            if (!featureProperties.isCreatorSendEnabled()) {
                throw new IllegalStateException("CREATOR_SEND_DISABLED");
            }
            return "sent";
        }
    }

    /** A read route, to prove the route detector does not simply flag everything. */
    static final class ReadOnlyStub {
        @PostMapping("/get_my_deals")
        public String read() {
            return "read";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5 — the shapes that bypassed the first version of this gate
    // ---------------------------------------------------------------------------------------------

    /**
     * The four declaration shapes that serve {@code send_routine_reply} identically to {@code
     * @PostMapping("/send_routine_reply")}, each of which the first version of the route detector
     * could not see.
     *
     * <p>Not folded into test 4: these are regression cases with a named cause, and the cause is
     * worth reading next to the assertion. Three of them were invisible because {@code
     * getAnnotation(PostMapping.class).value()} resolves no {@code @AliasFor} and reads no
     * meta-annotation; the fourth because the route name can live entirely in the class-level prefix.
     * Any future change to {@link #mappedRouteSegmentsOn} that reintroduces one of the holes turns
     * this red rather than turning the real gate quietly inert.
     */
    @Test
    @DisplayName(
            "D-02(e) — every mapping shape that can serve the send route is detected: path= alias,"
                    + " @RequestMapping(method=POST), multi-path value, and class-prefix-only")
    void everyMappingShapeThatCanServeTheSendRouteIsDetected() {
        assertThat(sendCapableRoutesOn(AliasPathSendStub.class))
                .as(
                        "@PostMapping(path = ...) is not detected. Spring declares value and path as"
                                + " @AliasFor each other, so a raw getAnnotation read of value() is"
                                + " empty here while the route is live and identical -- this is the"
                                + " one-word bypass the first version of this gate shipped with")
                .containsExactly("send_routine_reply");

        assertThat(sendCapableRoutesOn(RequestMappingSendStub.class))
                .as(
                        "@RequestMapping(method = POST) is not detected. It declares the same POST"
                                + " route as @PostMapping, and reading only PostMapping.class misses"
                                + " it entirely")
                .containsExactly("send_routine_reply");

        assertThat(sendCapableRoutesOn(MultiPathSendStub.class))
                .as(
                        "a multi-path mapping whose SECOND entry is the send route is not detected --"
                                + " only the first path of an array is being read")
                .containsExactly("send_routine_reply");

        assertThat(sendCapableRoutesOn(ClassPrefixSendStub.class))
                .as(
                        "the send route is not detected when the name sits in the class-level"
                                + " @RequestMapping and the method-level mapping has no path of its"
                                + " own -- the class prefix is not being resolved")
                .containsExactly("send_routine_reply");

        // A verb-less @RequestMapping maps every verb, POST included, so it must not be excused.
        assertThat(sendCapableRoutesOn(VerblessMappingSendStub.class))
                .as(
                        "a @RequestMapping naming no method is not detected. It maps EVERY verb"
                                + " including POST, so filtering the scan for POST would be the same"
                                + " hole again")
                .containsExactly("send_routine_reply");

        // The two inheritance shapes this javadoc previously mis-described as uncovered.
        assertThat(sendCapableRoutesOn(InterfaceDeclaredSendStub.class))
                .as(
                        "a mapping declared on an INTERFACE method is not detected. This is the shape"
                                + " an earlier version of mappedRouteSegmentsOn's javadoc listed as a"
                                + " remaining limit; findMergedAnnotation resolves it, and the claim"
                                + " was corrected on the strength of this assertion -- if it ever goes"
                                + " red, the javadoc is the thing that is now wrong")
                .containsExactly("send_routine_reply");

        assertThat(sendCapableRoutesOn(InheritedSendStub.class))
                .as(
                        "a handler inherited from a SUPERCLASS is not detected -- a @RestController"
                                + " subclass of an abstract base that declares the send route serves it"
                                + " exactly as if it declared it itself")
                .containsExactly("send_routine_reply");
    }

    /** {@code path =} instead of {@code value =} — the bypass the tester demonstrated. */
    static final class AliasPathSendStub {
        @PostMapping(path = "/send_routine_reply")
        public String send() {
            return "sent";
        }
    }

    /** The same POST route, declared without {@code @PostMapping}. */
    static final class RequestMappingSendStub {
        @RequestMapping(value = "/send_routine_reply", method = RequestMethod.POST)
        public String send() {
            return "sent";
        }
    }

    /** The send route as the second entry of a multi-path mapping. */
    static final class MultiPathSendStub {
        @PostMapping({"/get_my_deals", "/send_routine_reply"})
        public String send() {
            return "sent";
        }
    }

    /** The route name entirely in the class-level prefix; the method-level mapping has no path. */
    @RequestMapping("/internal/meera/creator/send_routine_reply")
    static final class ClassPrefixSendStub {
        @PostMapping
        public String send() {
            return "sent";
        }
    }

    /** No verb named, so every verb is mapped — POST among them. */
    static final class VerblessMappingSendStub {
        @RequestMapping("/send_routine_reply")
        public String send() {
            return "sent";
        }
    }

    /** The mapping on the interface, not on the implementing class. */
    interface SendRouteContract {
        @PostMapping("/send_routine_reply")
        String send();
    }

    /** Implements the mapping above and declares no mapping of its own. */
    static final class InterfaceDeclaredSendStub implements SendRouteContract {
        @Override
        public String send() {
            return "sent";
        }
    }

    /** The mapping on an abstract superclass; the concrete class declares nothing. */
    abstract static class AbstractSendBase {
        @PostMapping("/send_routine_reply")
        public String send() {
            return "sent";
        }
    }

    /** Inherits the send route without redeclaring it. */
    static final class InheritedSendStub extends AbstractSendBase {}

    // ---------------------------------------------------------------------------------------------
    // 5 — the control on the control: the functional-endpoint detector matches by subtype, not by name
    // ---------------------------------------------------------------------------------------------

    /**
     * The standing assertion that has been missing for three rounds: that {@link
     * MainSourceClasses#functionalEndpointDeclarations()} decides by <b>assignability</b> and cannot
     * silently regress to the fully-qualified-name string equality it shipped with.
     *
     * <p><b>Why a test and not a code comment.</b> Test 3 asserts that the functional-endpoint list is
     * empty, which is a statement about the <i>codebase</i>. It says nothing about whether the detector
     * producing that list still works, and the detector is precisely what failed: {@code
     * ROUTE_REGISTERING_RETURN_TYPES.contains(returnType)} compared the declared return type's name
     * against a fixed set of names, so one subtype defeated it. Three live, ungated send routes went
     * past it — a {@code @Bean} returning {@code ProbeSendRouter implements RouterFunction}, a {@code
     * @Bean} returning a {@code SimpleUrlHandlerMapping} subclass, and a {@code
     * ServletRegistrationBean} mapped to the send path with no Spring MVC involvement at all — each
     * time leaving the suite green. The empty list was true and meaningless at the same time. So the
     * three shapes are the test cases, verbatim, and a regression to string comparison turns this red
     * before test 3's silence is offered as evidence of anything.
     *
     * <p><b>Assignability is the relation the container uses</b>, which is why nothing weaker will do:
     * {@code RouterFunctionMapping} collects routers via {@code
     * getBeanNamesForType(RouterFunction.class)}, so a bean declared as its own implementation type is
     * a live route by exactly the relation a name comparison ignores. Declaring a bean that way is
     * ordinary Spring.
     */
    @Test
    @DisplayName(
            "D-02(f) — the functional-endpoint detector matches by SUBTYPE, not by name equality: a"
                    + " RouterFunction subtype, a SimpleUrlHandlerMapping subclass and a"
                    + " ServletRegistrationBean bean are each detected, and javadoc prose is not")
    void theFunctionalEndpointDetectorMatchesBySubtypeNotByNameEquality() {
        // The type set itself, pinned: a default-deny control whose type list quietly shrinks does not
        // fail, it passes for every shape it stopped knowing about.
        assertThat(MainSourceClasses.routeRegisteringReturnTypes())
                .as(
                        "the route-registering framework type set has been narrowed. Every entry is a"
                                + " bean-return shape that registers HTTP routes with no @Controller"
                                + " stereotype; removing one reopens a bypass silently, because the"
                                + " gate that reads this set goes GREEN when it matches nothing.")
                .contains(
                        RouterFunction.class,
                        HandlerMapping.class,
                        ServletContextInitializer.class,
                        ServletRegistrationBean.class,
                        FilterRegistrationBean.class);

        // Probe 1 — a @Bean whose declared return type is its own implementation of RouterFunction.
        // Its name is in no plausible name set, and it is a live route: RouterFunctionMapping collects
        // by getBeanNamesForType(RouterFunction.class).
        assertThat(MainSourceClasses.routeRegisteringDeclarationsOn(RouterFunctionSubtypeBean.class))
                .as(
                        "a @Bean declared as its own RouterFunction implementation type is NOT"
                                + " detected. The check is comparing type NAMES, not assignability, so"
                                + " one subtype defeats it -- this is the exact shape that got a live,"
                                + " ungated send route past this gate with the suite at Failures: 0."
                                + " Fix MainSourceClasses to use framework.isAssignableFrom(...); do"
                                + " not relax this assertion.")
                .isNotEmpty();

        // Probe 2 — one subclass away from a type the set already named.
        assertThat(MainSourceClasses.routeRegisteringDeclarationsOn(HandlerMappingSubclassBean.class))
                .as(
                        "a @Bean returning a SimpleUrlHandlerMapping SUBCLASS is not detected, though"
                                + " the set names SimpleUrlHandlerMapping's supertype. Name equality"
                                + " again: a subclass of a listed type is a listed type's instance and"
                                + " serves its routes identically.")
                .isNotEmpty();

        // Probe 3 — no Spring MVC at all: a servlet registered straight onto the send path.
        assertThat(MainSourceClasses.routeRegisteringDeclarationsOn(ServletRegistrationSendBean.class))
                .as(
                        "a @Bean returning ServletRegistrationBean mapped to the send path is not"
                                + " detected. It needs no @Controller and no RouterFunction -- the"
                                + " servlet container serves it directly -- so omitting this shape"
                                + " leaves a send route that every annotation-based gate is blind to.")
                .isNotEmpty();

        // Direction. framework.isAssignableFrom(declared), not the reverse: a supertype of a framework
        // type must NOT be flagged, or every @Bean returning Object becomes an offence and the gate is
        // red for everything, which gets it deleted rather than fixed.
        assertThat(MainSourceClasses.routeRegisteringDeclarationsOn(SupertypeReturningBean.class))
                .as(
                        "a @Bean returning Object was flagged as a route registration. The"
                                + " assignability test is the wrong way round -- it must be"
                                + " framework.isAssignableFrom(declaredReturnType), never the reverse.")
                .isEmpty();

        // The textual control, which is the point of doing this reflectively. This repo has twice
        // shipped a gate that went red on its own documentation.
        assertThat(MainSourceClasses.routeRegisteringDeclarationsOn(ProseOnlyConfiguration.class))
                .as(
                        "a @Configuration that only MENTIONS RouterFunction, SimpleUrlHandlerMapping"
                                + " and the send path in prose was flagged. The detector has become"
                                + " textual rather than reflective, and it will now go red on the"
                                + " javadoc explaining what it bans -- which has happened twice here"
                                + " already.")
                .isEmpty();
    }

    /** A {@code RouterFunction} declared as its own implementation type — the tester's first probe. */
    static final class ProbeSendRouter implements RouterFunction<ServerResponse> {
        @Override
        public Optional<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return Optional.empty();
        }
    }

    /** The bean factory method for it: the declared return type is the subtype, not the interface. */
    @Configuration
    static class RouterFunctionSubtypeBean {
        @Bean
        ProbeSendRouter probeSendRouter() {
            return new ProbeSendRouter();
        }
    }

    /** One subclass away from a type the equality-based set already named — the second probe. */
    static class ProbeSendHandlerMapping extends SimpleUrlHandlerMapping {}

    /** Its factory method, again declared as the subclass. */
    @Configuration
    static class HandlerMappingSubclassBean {
        @Bean
        ProbeSendHandlerMapping probeSendHandlerMapping() {
            return new ProbeSendHandlerMapping();
        }
    }

    /** A plain servlet: no Spring MVC involvement whatsoever. */
    static final class ProbeSendServlet extends HttpServlet {}

    /** The servlet mapped onto the send path — the third probe. */
    @Configuration
    static class ServletRegistrationSendBean {
        @Bean
        ServletRegistrationBean<HttpServlet> probeSendServletRegistration() {
            return new ServletRegistrationBean<>(
                    new ProbeSendServlet(), "/internal/meera/creator/send_routine_reply");
        }
    }

    /** A supertype of the framework types, to pin the direction of the assignability test. */
    @Configuration
    static class SupertypeReturningBean {
        @Bean
        Object somethingUntyped() {
            return new Object();
        }
    }

    /**
     * The control that proves the mechanism is reflective rather than textual.
     *
     * <p>This class registers nothing. It talks about {@code RouterFunction} and {@code
     * SimpleUrlHandlerMapping} at length, and about the route {@code
     * /internal/meera/creator/send_routine_reply}, and about {@code RouterFunctions.route().POST(
     * "/internal/meera/creator/send_routine_reply", …).build()}, and about {@code RouterFunction} once
     * more for good measure, and about a {@code SimpleUrlHandlerMapping} whose {@code urlMap} names
     * {@code send_routine_reply}. A grep-based gate would be red on this paragraph. A reflective one
     * cannot be, because the only thing it reads is {@link java.lang.reflect.Method#getReturnType()},
     * and every method here returns {@code String}.
     */
    @Configuration
    static class ProseOnlyConfiguration {
        String describesRouterFunctionButRegistersNothing() {
            return "/internal/meera/creator/send_routine_reply";
        }

        String describesSimpleUrlHandlerMappingButRegistersNothing() {
            return "RouterFunction, SimpleUrlHandlerMapping, send_routine_reply";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * The capabilities a creator is granted at approval level 1 or 2 and not at level 0 — the
     * commit-like tools.
     *
     * <p>Read through {@code scopeFor}, a method, rather than off the {@code SCOPE_LEVEL_*} constants:
     * javac inlines a {@code static final String} into every class that reads it, so a test that
     * referenced the constants directly could keep asserting a stale value across an incremental
     * build until it was itself recompiled. {@code CreatorToolScopes}' own javadoc records that trap.
     */
    private static Set<String> sendCapableToolNames() {
        Set<String> grantedAboveLevelZero = new LinkedHashSet<>();
        grantedAboveLevelZero.addAll(namesIn(CreatorToolScopes.scopeFor(1, false)));
        grantedAboveLevelZero.addAll(namesIn(CreatorToolScopes.scopeFor(2, false)));
        grantedAboveLevelZero.removeAll(namesIn(CreatorToolScopes.scopeFor(0, false)));
        return grantedAboveLevelZero;
    }

    private static Set<String> namesIn(String spaceDelimitedScope) {
        return new LinkedHashSet<>(List.of(spaceDelimitedScope.trim().split("\\s+")));
    }

    /**
     * Every path segment a class maps, from any Spring request mapping, class-level prefix included.
     *
     * <p><b>This method is the one that was bypassable, so the reasoning is written out.</b> Its
     * first version read {@code method.getAnnotation(PostMapping.class).value()}. That had three
     * holes, each closed by one line here and each proved by a stub in
     * {@link #everyMappingShapeThatCanServeTheSendRouteIsDetected()}:
     *
     * <ol>
     *   <li><b>{@code @AliasFor} is not resolved by {@code getAnnotation}.</b> Spring declares {@code
     *       value} and {@code path} as aliases of each other, so {@code @PostMapping(path = "/x")}
     *       leaves {@code value()} <b>empty</b> on the raw annotation — a one-word change made a live,
     *       identical route invisible. {@link AnnotatedElementUtils#findMergedAnnotation} performs the
     *       alias resolution Spring's own {@code RequestMappingHandlerMapping} performs, and both
     *       attributes are unioned regardless.
     *   <li><b>{@code @PostMapping} is not the only way to declare a POST.</b> {@code
     *       @RequestMapping(method = POST)} is the same route and was invisible for the same reason —
     *       so the search is for the meta-annotation {@code @RequestMapping}, which every one of
     *       {@code @PostMapping}/{@code @GetMapping}/{@code @PutMapping}/{@code @PatchMapping}/{@code
     *       @DeleteMapping} carries. <b>No filter on {@code method()} is applied</b>, deliberately: a
     *       bare {@code @RequestMapping("/send_routine_reply")} names no verb and therefore maps
     *       <i>every</i> verb including POST, so filtering for POST would reintroduce the same class
     *       of hole. A send route under any verb is a send route.
     *   <li><b>The route name need not be in the method-level path at all.</b> A bare {@code
     *       @PostMapping} under {@code @RequestMapping("/internal/meera/creator/send_routine_reply")}
     *       serves the send route with an empty method-level path, so the class-level prefix is
     *       resolved the same merged way and prepended. Paths are then reduced to their segments, so
     *       the tool name is found wherever in the path it sits ({@code "/send_routine_reply/confirm"}
     *       included) — which is why the returned set also contains the prefix segments {@code
     *       internal}, {@code meera}, {@code creator} for the real controller. Harmless: matching is
     *       an intersection with the derived tool names, and no tool is named {@code internal}.
     * </ol>
     *
     * <p><b>Correction: interface-declared mappings are not a limit.</b> An earlier version of this
     * javadoc claimed a mapping declared on an <i>interface</i> method rather than on the implementing
     * class "is not resolved here". That was wrong, and understating this method's own coverage is not
     * a safe error: it invites the next author either to bolt on a redundant control for a shape that
     * is already covered, or to distrust this one and replace it. {@link
     * AnnotatedElementUtils#findMergedAnnotation} searches with {@code SearchStrategy.TYPE_HIERARCHY},
     * which for a {@code Method} walks the declaring class's superclasses <i>and its interfaces</i>
     * looking for the corresponding method — the same resolution Spring's own {@code
     * RequestMappingHandlerMapping} performs, which is why an interface-declared mapping is a live
     * route in the first place. The tester confirmed detection; {@link InterfaceDeclaredSendStub} pins
     * it so the claim is test-backed rather than asserted. The same applies to a class-level {@code
     * @RequestMapping} inherited from a superclass or interface, resolved identically by {@link
     * #pathsOn}. Superclass-declared handler methods are additionally reachable via {@link
     * Class#getMethods()} ({@link InheritedSendStub}). {@code CreatorMeeraToolController} itself
     * implements no interface and extends nothing.
     */
    private static Set<String> mappedRouteSegmentsOn(Class<?> controller) {
        Set<String> routes = new TreeSet<>();
        Set<String> classPrefixes = pathsOn(controller);
        for (Method method : declaredAndInheritedMethods(controller)) {
            RequestMapping mapping =
                    AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            Set<String> methodPaths = pathsIn(mapping);
            if (methodPaths.isEmpty()) {
                // A mapping with no path of its own: the class-level prefix IS the route.
                methodPaths = Set.of("");
            }
            for (String prefix : classPrefixes) {
                for (String methodPath : methodPaths) {
                    addSegments(prefix + "/" + methodPath, routes);
                }
            }
        }
        return routes;
    }

    /** The class-level mapping's paths, or a single empty prefix when the class declares none. */
    private static Set<String> pathsOn(Class<?> controller) {
        RequestMapping mapping =
                AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        Set<String> paths = mapping == null ? Set.of() : pathsIn(mapping);
        return paths.isEmpty() ? Set.of("") : paths;
    }

    /** {@code value()} unioned with {@code path()} — aliases, but never assume they agree. */
    private static Set<String> pathsIn(RequestMapping mapping) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(List.of(mapping.value()));
        paths.addAll(List.of(mapping.path()));
        return paths;
    }

    /** Path variables are dropped; every other non-empty segment is a candidate route name. */
    private static void addSegments(String path, Set<String> into) {
        for (String segment : path.split("/")) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("{")) {
                into.add(trimmed);
            }
        }
    }

    private static Set<Method> declaredAndInheritedMethods(Class<?> controller) {
        Set<Method> methods = new LinkedHashSet<>(List.of(controller.getDeclaredMethods()));
        methods.addAll(List.of(controller.getMethods()));
        return methods;
    }

    /** Those of {@link #mappedRouteSegmentsOn} that name a commit-like tool. */
    private static Set<String> sendCapableRoutesOn(Class<?> controller) {
        Set<String> sendCapable = sendCapableToolNames();
        Set<String> offending = new TreeSet<>(mappedRouteSegmentsOn(controller));
        offending.retainAll(sendCapable);
        return offending;
    }

    /**
     * True if this class's compiled form references {@link #FLAG_READER}.
     *
     * <p>Reads the {@code .class} bytes and searches for the method name as UTF-8. Every {@code
     * invokevirtual} records its target's name in the constant pool as a UTF8 entry, so a real call
     * is always visible here — and unlike a source scan, a javadoc or comment mention of the name is
     * not, which is the failure two grep gates in this repo have already shipped.
     */
    private static boolean referencesFlagReader(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in)
                    .as(
                            "could not open the compiled class file for %s -- the flag detector cannot"
                                    + " run and must not be treated as having passed",
                            type.getName())
                    .isNotNull();
            return contains(in.readAllBytes(), FLAG_READER.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static Method flagReaderOrNull() {
        for (Method method : MeeraCreatorFeatureProperties.class.getDeclaredMethods()) {
            if (method.getName().equals(FLAG_READER) && method.getParameterCount() == 0) {
                return method;
            }
        }
        return null;
    }

    /** The {@code @Value} expression on whichever constructor parameter binds the send property. */
    private static String sendValueExpressionOrNull() {
        for (var constructor : MeeraCreatorFeatureProperties.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null && value.value().contains(SEND_PROPERTY)) {
                    return value.value();
                }
            }
        }
        return null;
    }

    private static Set<String> sorted(Set<String> values) {
        return new TreeSet<>(values);
    }

    /**
     * Resolves a file under {@code src/main/resources}, tolerating both working directories Maven and
     * an IDE use — the same two-candidate probe {@code InfoBarrierTest} and {@code FloorBarrierTest}
     * use for {@code src/main/java}.
     */
    private static Path mainResource(String name) throws IOException {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate :
                List.of(
                        here.resolve("src/main/resources").resolve(name),
                        here.resolve("influora-api/src/main/resources").resolve(name))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException(
                "Could not locate src/main/resources/" + name + " from working directory " + here);
    }
}
