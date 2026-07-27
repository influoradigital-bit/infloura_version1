package com.influora.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.service.notification.event.NotificationEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * W3-1 contract test — every concrete {@link NotificationEvent} in the {@code event} package must
 * have at least one publisher (something actually calls {@code
 * ApplicationEventPublisher.publishEvent(new XEvent(...))}) AND at least one listener (a method in
 * {@link NotificationListener} annotated {@code @EventListener}/{@code
 * @TransactionalEventListener} taking that event type). Modeled on {@code
 * ConfigurationPropertiesRegistrationTest} (same package-shaped guard): plain reflection +
 * source-text scanning, NO Spring context, NO Docker — this must run green or red on a machine
 * where every {@code @SpringBootTest} errors out on Testcontainers discovery.
 *
 * <p><b>What this catches:</b> the exact class of bug this task fixes — an event class that exists,
 * compiles, and LOOKS wired (constructed, passed around, maybe even handed to a service) but that
 * nothing on the classpath actually publishes through {@code ApplicationEventPublisher}, or that no
 * listener handles, so the notification/email it was supposed to produce silently never fires. Two
 * concrete examples this test would have caught on this codebase before this task: {@code
 * PortfolioContactEvent} was constructed in {@code PortfolioService#contact} and handed directly to
 * {@code NotificationService.notify} — never actually published through the event bus, and had no
 * listener either. {@code ContractReadyForEscrowEvent}/{@code SubscriptionHaltedEvent} were
 * published but had no listener at all.
 *
 * <p><b>Publisher detection is source-text scanning, not bytecode/call-graph analysis</b> — it
 * greps every {@code .java} file under {@code src/main/java} (excluding the {@code event} package
 * itself, which only ever CONSTRUCTS these types, never publishes them) for the literal pattern
 * {@code new <EventSimpleName>(}. This is a deliberately blunt but zero-dependency, zero-Spring-
 * context technique — false positives are possible only if some unrelated class shares the exact
 * same simple name and is separately {@code new}'d somewhere in {@code com.influora}, which does
 * not happen today (every event class lives solely under {@code service.notification.event} and is
 * uniquely named).
 */
class NotificationEventContractTest {

    /**
     * Events with a deliberately documented publisher gap. Every entry needs a citable reason —
     * growing this set is a decision to make explicitly in a code review, not a silent omission.
     * If an event below gains a real publisher, this test starts failing on the "stale entry" half
     * of the assertion until the entry is removed — the allowlist cannot drift out of sync with
     * reality in either direction.
     */
    private static final Map<String, String> KNOWN_MISSING_PUBLISHERS =
            Map.ofEntries(
                    // Removed 2026-07-26 — the follow-ups these three described have shipped, and
                    // this guard correctly flagged the entries as stale:
                    //   SubscriptionPaymentFailedEvent → RazorpayWebhookController:382
                    //                                    (subscription.pending is routed now)
                    //   ShipmentCreatedEvent           → ShipmentService:316
                    //   ShipmentReceivedEvent          → ShipmentService:341
                    // The allowlist only earns its keep if entries leave it when the reason
                    // expires; each was verified to have a real publisher before deletion.
                    Map.entry(
                            "KycApprovedEvent",
                            "No KYC review service/flow exists anywhere in this codebase yet (zero KYC"
                                    + " service classes) — the owning feature is unbuilt, not just unwired."),
                    Map.entry("KycRejectedEvent", "Same as KycApprovedEvent — no KYC review flow exists."),
                    Map.entry(
                            "WalletLowBalanceEvent",
                            "No low-balance threshold check exists anywhere in this codebase — a new"
                                    + " feature (deciding the threshold, where to evaluate it), out of this"
                                    + " task's scope."),
                    Map.entry(
                            "MonthlyStatementEvent",
                            "No monthly-statement generation job exists anywhere in this codebase — a new"
                                    + " feature, out of this task's scope."),
                    Map.entry(
                            "SiteAnalyzedEvent",
                            "Meera/AI event — explicitly out of scope per the prior task boundary (do not"
                                    + " touch service/meera or AI entities)."),
                    Map.entry(
                            "CampaignRecommendedEvent", "Meera/AI event — same out-of-scope boundary as SiteAnalyzedEvent."),
                    Map.entry(
                            "CreditsExhaustedEvent", "Meera/AI event — same out-of-scope boundary as SiteAnalyzedEvent."),
                    Map.entry(
                            "CreditsResetEvent", "Meera/AI event — same out-of-scope boundary as SiteAnalyzedEvent."),
                    Map.entry(
                            "AuthOtpEvent",
                            "BrandEmailOtpService#deliverOtp already sends the OTP synchronously via a"
                                    + " direct MSG91 template call (bc81ff3 MSG91 fail-fast). Also publishing"
                                    + " this event would double-send the OTP through two independent delivery"
                                    + " channels — needs a deliberate consolidation, not a blind wire-up."),
                    Map.entry(
                            "CampaignCreatedEvent",
                            "Modeled as a per-matching-creator broadcast (\"new campaign in your category\"),"
                                    + " which needs a creator-matching query CampaignService does not have —"
                                    + " CampaignService publishes nothing on campaign creation today. New"
                                    + " feature, out of this task's scope."));

    @Test
    @DisplayName("every concrete NotificationEvent has at least one @EventListener/@TransactionalEventListener handler")
    void everyEventHasAListener() {
        Set<Class<?>> handled = listenedEventTypes();
        Set<String> missing = new TreeSet<>();
        for (Class<?> event : concreteEventClasses()) {
            if (!handled.contains(event)) {
                missing.add(event.getSimpleName());
            }
        }
        assertThat(missing)
                .as(
                        "These NotificationEvent classes have no on(...) handler in NotificationListener, so"
                            + " publishing them does nothing — add an @Async @TransactionalEventListener"
                            + " method for each.")
                .isEmpty();
    }

    @Test
    @DisplayName("every concrete NotificationEvent has at least one publisher, or a documented reason it doesn't")
    void everyEventHasAPublisherOrADocumentedReason() {
        Set<String> publishedSimpleNames = publishedEventSimpleNames();

        Set<String> missingUndocumented = new TreeSet<>();
        Set<String> staleAllowlistEntries = new TreeSet<>();

        for (Class<?> event : concreteEventClasses()) {
            String simpleName = event.getSimpleName();
            boolean published = publishedSimpleNames.contains(simpleName);
            boolean allowlisted = KNOWN_MISSING_PUBLISHERS.containsKey(simpleName);

            if (!published && !allowlisted) {
                missingUndocumented.add(simpleName);
            }
            if (published && allowlisted) {
                staleAllowlistEntries.add(simpleName);
            }
        }

        assertThat(missingUndocumented)
                .as(
                        "These NotificationEvent classes have no publisher anywhere under src/main/java and"
                            + " no documented reason in KNOWN_MISSING_PUBLISHERS — either wire a"
                            + " publishEvent(new XEvent(...)) call from the owning domain service, or add a"
                            + " citable reason to the allowlist.")
                .isEmpty();
        assertThat(staleAllowlistEntries)
                .as(
                        "These events are listed in KNOWN_MISSING_PUBLISHERS but a publisher now exists —"
                            + " remove the stale entry so the allowlist doesn't drift from reality.")
                .isEmpty();
    }

    /** Sanity guard: if either scan comes back empty, the scan itself is broken, not the codebase. */
    @Test
    @DisplayName("sanity: the scans actually find events, listeners, and publishers")
    void sanityScansAreNotVacuous() {
        assertThat(concreteEventClasses()).as("event/ package scan").isNotEmpty();
        assertThat(listenedEventTypes()).as("NotificationListener handler scan").isNotEmpty();
        assertThat(publishedEventSimpleNames()).as("src/main/java publisher source-scan").isNotEmpty();
    }

    /** Every concrete record permitted by the sealed {@link NotificationEvent} interface. */
    private static Set<Class<?>> concreteEventClasses() {
        Class<?>[] permitted = NotificationEvent.class.getPermittedSubclasses();
        assertThat(permitted)
                .as("sanity: NotificationEvent must be a sealed interface with permitted subclasses")
                .isNotNull();
        return new LinkedHashSet<>(Set.of(permitted));
    }

    /** Every event type accepted by an {@code @EventListener}/{@code @TransactionalEventListener} method. */
    private static Set<Class<?>> listenedEventTypes() {
        Set<Class<?>> handled = new LinkedHashSet<>();
        for (Method method : NotificationListener.class.getDeclaredMethods()) {
            boolean isListener =
                    method.isAnnotationPresent(EventListener.class)
                            || method.isAnnotationPresent(TransactionalEventListener.class);
            if (!isListener) {
                continue;
            }
            Parameter[] params = method.getParameters();
            if (params.length == 1) {
                handled.add(params[0].getType());
            }
        }
        return handled;
    }

    /**
     * Simple class names for which some {@code .java} file under {@code src/main/java} (outside the
     * {@code event} package) contains the literal text {@code new <SimpleName>(}.
     */
    private static Set<String> publishedEventSimpleNames() {
        Path mainJava = findMainJavaRoot();
        Path eventPackageDir = mainJava.resolve("com/influora/service/notification/event");

        Set<String> eventSimpleNames = new LinkedHashSet<>();
        for (Class<?> event : concreteEventClasses()) {
            eventSimpleNames.add(event.getSimpleName());
        }

        Set<String> published = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(eventPackageDir))
                    .forEach(
                            p -> {
                                String content;
                                try {
                                    content = Files.readString(p);
                                } catch (IOException e) {
                                    throw new UncheckedIOException("failed reading " + p, e);
                                }
                                for (String simpleName : eventSimpleNames) {
                                    if (content.contains("new " + simpleName + "(")) {
                                        published.add(simpleName);
                                    }
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed walking " + mainJava, e);
        }
        return published;
    }

    /**
     * Resolves {@code src/main/java} regardless of whether the test runner's working directory is
     * the module root ({@code influora-api/}, the common case) or something else — walks up from
     * {@code user.dir} looking for a directory that has {@code src/main/java/com/influora} as a
     * child, same defensive resolution shape tests in this codebase use elsewhere for filesystem
     * fixtures.
     */
    private static Path findMainJavaRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("src/main/java/com/influora");
            if (Files.isDirectory(candidate)) {
                return dir.resolve("src/main/java");
            }
        }
        throw new IllegalStateException(
                "Could not locate src/main/java/com/influora starting from user.dir="
                        + System.getProperty("user.dir"));
    }
}
