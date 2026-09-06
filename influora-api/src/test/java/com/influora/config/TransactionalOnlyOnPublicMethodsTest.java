package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Kabir M-4] Repo-wide invariant: {@code @Transactional} must never sit on a non-public method.
 *
 * <h2>Why this is a whole test and not a code comment</h2>
 *
 * Spring's default {@code AnnotationTransactionAttributeSource} is built with {@code
 * publicMethodsOnly = true}. For a {@code protected}, package-private or {@code private} method it
 * returns no transaction attribute at all — <b>no warning, no error, no startup failure</b>. The
 * annotation sits there looking like a guarantee and does nothing.
 *
 * <p>This exact failure has now been found in this codebase three separate times, each time by a
 * human reading code rather than by anything automated:
 *
 * <ul>
 *   <li>{@code RedemptionService#doRedeem} — self-invocation from a lambda; fixed by extracting
 *       {@code RedemptionWriter} as a real bean.
 *   <li>{@code IdempotencyService} — a fifth write path kept calling {@code this.foo(...)} after
 *       the others were converted (see that class's javadoc).
 *   <li>{@code AffiliateEarningsService#doRecordEarning} — the self-proxy call was added to fix the
 *       self-invocation half, and the annotation STILL did nothing because the method was
 *       {@code protected}. Its two writes were only atomic when a caller happened to supply a
 *       transaction; on the reconciliation-cron path, which has none, they were not.
 * </ul>
 *
 * <p>Three occurrences of one silent failure mode is the definition of something that needs a
 * mechanical check rather than another comment asking people to remember.
 *
 * <h2>Scope, honestly stated</h2>
 *
 * This catches the annotation-visibility mistake only. It does NOT catch self-invocation
 * ({@code this.foo(...)} bypassing the proxy), which is the other half of the same family and is
 * not detectable by reflection — a public {@code @Transactional} method called via {@code this}
 * still silently does nothing. Passing this test does not mean a method's transaction is live; it
 * means one specific way of making it dead is absent.
 *
 * <p>It also covers PRODUCTION classes only — {@code @Transactional} on a package-private JUnit
 * test method is correct and idiomatic (a different Spring mechanism handles it). See
 * {@code loadAllClasses}.
 */
class TransactionalOnlyOnPublicMethodsTest {

    private static final String BASE_PACKAGE = "com.influora";

    @Test
    @DisplayName("[M-4] no @Transactional method anywhere in com.influora is non-public")
    void noTransactionalOnNonPublicMethods() {
        List<Class<?>> classes = loadAllClasses();

        // The scan itself must be proven to have worked. Without this, a broken classpath walk
        // (wrong directory, empty target/, a changed build layout) would report zero violations
        // and read as a pass — the exact vacuous-green this test exists to avoid. The threshold is
        // deliberately far below the real count so it never becomes a maintenance burden, while
        // still being impossible to satisfy with a scan that found nothing.
        assertTrue(
                classes.size() > 200,
                "class scan found only " + classes.size() + " classes under " + BASE_PACKAGE
                        + " — the scan is broken, so a green result here would prove nothing");

        List<String> violations = new ArrayList<>();
        for (Class<?> clazz : classes) {
            Method[] methods;
            try {
                methods = clazz.getDeclaredMethods();
            } catch (NoClassDefFoundError unresolvable) {
                // A class whose signature references an optional dependency. Skipped, not failed —
                // it cannot carry a transactional method we could act on anyway.
                continue;
            }
            for (Method method : methods) {
                if (!method.isAnnotationPresent(Transactional.class)) {
                    continue;
                }
                if (!Modifier.isPublic(method.getModifiers())) {
                    violations.add(
                            clazz.getName() + "#" + method.getName() + " is "
                                    + Modifier.toString(method.getModifiers()));
                }
            }
        }

        // EXACT equality, not containsAll. A subset check would let the baseline rot in both
        // directions: a new violation could hide if someone widened the list, and a FIXED one
        // would linger forever with nobody noticing it was already done. Exact equality means
        // touching this set is always a deliberate, reviewed edit — see KNOWN_INERT's javadoc for
        // what each entry costs and what it would take to remove it.
        List<String> unexpected = new ArrayList<>(violations);
        unexpected.removeAll(KNOWN_INERT);
        List<String> staleAllowlistEntries = new ArrayList<>(KNOWN_INERT);
        staleAllowlistEntries.removeAll(violations);

        assertTrue(
                unexpected.isEmpty(),
                "NEW @Transactional on a non-public method. Spring's default"
                        + " AnnotationTransactionAttributeSource (publicMethodsOnly = true) silently"
                        + " ignores these — the method looks transactional and is not. Either make it"
                        + " public AND call it through a proxy (not this.foo(...)), or drop the"
                        + " annotation so nobody trusts it:\n  "
                        + String.join("\n  ", unexpected));

        assertTrue(
                staleAllowlistEntries.isEmpty(),
                "KNOWN_INERT lists methods that are no longer non-public @Transactional. Good news —"
                        + " remove them from the allowlist so it keeps meaning something:\n  "
                        + String.join("\n  ", staleAllowlistEntries));
    }

    /**
     * Pre-existing inert annotations, each deliberately NOT fixed as part of [Kabir M-4], with the
     * reason. This is a baseline to stop the bleeding, not an endorsement.
     *
     * <p><b>Deliberately inert — do NOT "fix" these by making them public.</b> Both wrap a wallet
     * debit followed by an external RazorpayX call. Their own javadoc argues the case at length:
     * a local transaction cannot span an external gateway, and rolling the debit back after a send
     * has partially succeeded would reintroduce the double-pay hole that the debit-first ordering
     * exists to prevent. Safety comes from a durable {@code Payout} intent row plus a reconciler,
     * not from atomicity. The annotation is nonetheless a live trap — the day someone makes one of
     * these public it silently becomes a real transaction and reintroduces that hole — so the right
     * eventual change is to DELETE the annotation, which is a provable no-op today. Left in place
     * here only because money-path edits belong in their own reviewed change, not smuggled into a
     * security-finding sweep:
     *
     * <ul>
     *   <li>{@code PayoutService#doQueuePayout}
     *   <li>{@code WalletService#doProcessWithdrawal}
     * </ul>
     *
     * <p><b>Probably real bugs, not investigated here.</b> These have no documented justification,
     * and — unlike {@code AffiliateEarningsService#doRecordEarning}, which was already routed
     * through a self-proxy and needed only the visibility fix — every one of them is ALSO called as
     * a plain {@code this.doX(...)} self-invocation from inside its own {@code executeOnce} lambda.
     * That means each is dead twice over, and making it public alone would fix nothing while
     * looking like it had. Each needs the {@code self}-proxy treatment
     * ({@code ConfirmLaunchExecutor} in the same package shows the correct shape) and its own
     * assessment of what breaks when its writes actually become atomic:
     *
     * <ul>
     *   <li>{@code MeeraSessionService#doSendTurn}
     *   <li>{@code MeeraSessionService#doPersistAssistantWriteback}
     *   <li>{@code meera.tool.CreateCampaignExecutor#doExecute}
     *   <li>{@code meera.tool.RequestPaymentExecutor#doExecute}
     *   <li>{@code job.PlatformStatsAggregationJob#aggregateOne} (package-private)
     * </ul>
     */
    private static final List<String> KNOWN_INERT =
            List.of(
                    "com.influora.job.PlatformStatsAggregationJob#aggregateOne is ",
                    "com.influora.service.PayoutService#doQueuePayout is protected",
                    "com.influora.service.WalletService#doProcessWithdrawal is protected",
                    "com.influora.service.meera.MeeraSessionService#doPersistAssistantWriteback is protected",
                    "com.influora.service.meera.MeeraSessionService#doSendTurn is protected",
                    "com.influora.service.meera.tool.CreateCampaignExecutor#doExecute is protected",
                    "com.influora.service.meera.tool.RequestPaymentExecutor#doExecute is protected");

    @Test
    @DisplayName("[M-4] the scan can actually see @Transactional — proves the detector works")
    void detectorSeesAKnownTransactionalMethod() {
        // A green result above could also mean "the annotation check never matches anything".
        // This pins that the detector finds a real, known-transactional production method, so the
        // main test's empty violation list means "none are non-public" and not "none were seen".
        long transactionalMethods =
                loadAllClasses().stream()
                        .flatMap(
                                c -> {
                                    try {
                                        return Stream.of(c.getDeclaredMethods());
                                    } catch (NoClassDefFoundError e) {
                                        return Stream.empty();
                                    }
                                })
                        .filter(m -> m.isAnnotationPresent(Transactional.class))
                        .count();

        assertFalse(
                transactionalMethods == 0,
                "found zero @Transactional methods in the whole codebase — the detector is broken,"
                        + " not the codebase clean");
    }

    /**
     * Walks PRODUCTION compiled output only ({@code target/classes}), never {@code
     * target/test-classes}.
     *
     * <p>This distinction is load-bearing, and the first version of this test got it wrong. Looking
     * {@link #BASE_PACKAGE} up through the context classloader resolves against the whole test
     * classpath, which includes test classes — and it immediately reported eight "violations", all
     * of them JUnit test methods. Those are correct as written: {@code @Transactional} on a test
     * method is handled by Spring's {@code TransactionalTestExecutionListener}, which has no
     * visibility requirement at all, and JUnit 5 deliberately encourages package-private test
     * methods. Flagging them would have made this test a false-positive generator that someone
     * would eventually silence, taking the real check with it.
     *
     * <p>Anchoring on a known production class's code source resolves to exactly {@code
     * target/classes}, whatever the build layout.
     */
    private static List<Class<?>> loadAllClasses() {
        URL codeSource =
                com.influora.service.AffiliateEarningsService.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation();

        Path base;
        try {
            base = Path.of(codeSource.toURI()).resolve(BASE_PACKAGE.replace('.', '/'));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("production code source is not a file path: " + codeSource, e);
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException(
                    "expected production classes directory at " + base + " (from " + codeSource + ")");
        }

        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(base)) {
            files.filter(p -> p.toString().endsWith(".class"))
                    .forEach(
                            p -> {
                                String relative = base.relativize(p).toString().replace('\\', '/');
                                String className =
                                        BASE_PACKAGE + "." + relative.substring(0, relative.length() - ".class".length())
                                                .replace('/', '.');
                                try {
                                    // initialize = false: loading a class must not run its static
                                    // initializers, which could touch config or open connections.
                                    classes.add(
                                            Class.forName(
                                                    className,
                                                    false,
                                                    Thread.currentThread().getContextClassLoader()));
                                } catch (ClassNotFoundException | LinkageError skip) {
                                    // Unloadable class (optional dependency missing).
                                    // LinkageError covers NoClassDefFoundError. Skipped —
                                    // the count assertion above is what guards against skipping
                                    // everything.
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return classes;
    }
}
