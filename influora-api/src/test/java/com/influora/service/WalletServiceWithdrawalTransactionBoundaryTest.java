package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * [EV-003 regression gate] "No gateway call happens inside a transaction" is a property of the
 * code's SHAPE, not of any one execution, and a behavioural test cannot see it: Mockito replaces
 * {@code RazorpayXClient} with a stub that returns instantly, so a call made while holding a
 * pessimistic lock on the creator's wallet and on the platform clearing wallet passes every
 * ordering assertion you can write about it. That is why the original defect survived a green
 * {@code WalletServiceTest} — the tests proved the call happened, never where.
 *
 * <p>So this asserts the shape directly, in two independent ways, each of which fails on its own:
 *
 * <ol>
 *   <li>{@link #withdrawalEntryPointIsNotTransactional()} — reflection over the annotation that
 *       caused the defect. Putting {@code @Transactional} back on {@code
 *       requestCreatorWithdrawal} makes this red immediately.
 *   <li>{@link #noGatewayCallSitsInsideATransactionalMethod()} — reads {@code WalletService.java}
 *       and checks that the two methods which call {@code razorpayXClient} / {@code
 *       fundAccountService} are not themselves annotated. Reflection alone cannot do this: a
 *       private method has no annotation to find, so "no annotation" would be vacuously true for
 *       {@code processWithdrawal} however it was written.
 * </ol>
 *
 * <p>Falsified before being trusted: re-adding {@code @Transactional} to {@code
 * requestCreatorWithdrawal} turns both of these red, and only these.
 */
class WalletServiceWithdrawalTransactionBoundaryTest {

    @Test
    @DisplayName("requestCreatorWithdrawal is not @Transactional — that annotation WAS the defect")
    void withdrawalEntryPointIsNotTransactional() throws NoSuchMethodException {
        Method entryPoint =
                WalletService.class.getMethod(
                        "requestCreatorWithdrawal", String.class, BigDecimal.class, String.class);

        assertNull(
                entryPoint.getAnnotation(Transactional.class),
                "requestCreatorWithdrawal is @Transactional again. That wraps the RazorpayX payout"
                        + " call in a database transaction holding pessimistic write locks on the"
                        + " creator's wallet AND on the platform clearing wallet (the counterparty of"
                        + " every top-up, escrow movement and payout on the platform), so one slow"
                        + " gateway response blocks every other wallet posting; and a request that"
                        + " reaches RazorpayX whose response is lost rolls the debit back while the"
                        + " bank transfer stays in flight. Keep the three-step shape: "
                        + " CreatorWithdrawalOps#reserveWithdrawal, then the gateway, then"
                        + " CreatorWithdrawalOps#recordGatewayResult.");
    }

    @Test
    @DisplayName("neither method that makes an outbound call is annotated transactional")
    void noGatewayCallSitsInsideATransactionalMethod() {
        String source = readWalletServiceSource();

        for (String method : new String[] {"requestCreatorWithdrawal", "processWithdrawal"}) {
            assertTrue(
                    source.contains(method + "("),
                    "WalletService no longer declares " + method + " — this gate is now vacuous;"
                            + " re-point it at whatever replaced it before assuming EV-003 is still fixed");
            assertNull(
                    annotationImmediatelyBefore(source, method),
                    method
                            + " is annotated @Transactional. The RazorpayX payout call and the lazy"
                            + " fund-account provisioning call both run on this path; putting either"
                            + " inside a transaction is EV-003.");
        }

        // And the gateway really is still called from here — otherwise the two assertions above
        // would be true of a method that no longer does anything.
        assertTrue(
                source.contains("razorpayXClient.initiatePayout("),
                "WalletService no longer initiates the payout at all; this gate is measuring nothing");
    }

    @Test
    @DisplayName("the withdrawal's two transactions come from an injected bean, not self-invocation")
    void thePhasesGoThroughAnInjectedBean() {
        String source = readWalletServiceSource();

        // Spring's @Transactional is applied by a proxy, so `this.reserve...()` would be silently
        // non-transactional — the exact trap PayoutService#doQueuePayout documents itself falling
        // into. Calling through the injected bean is what makes the two commits real.
        assertTrue(
                source.contains("creatorWithdrawalOps.reserveWithdrawal("),
                "the reservation must go through the injected CreatorWithdrawalOps bean");
        assertTrue(
                source.contains("creatorWithdrawalOps.recordGatewayResult("),
                "the result must be recorded through the injected CreatorWithdrawalOps bean");
        assertEquals(
                0,
                countMatches(source, Pattern.compile("this\\s*\\.\\s*(reserveWithdrawal|recordGatewayResult)\\s*\\(")),
                "a self-invoked phase is not transactional at all");
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Returns the annotation text directly preceding {@code methodName}'s declaration, or {@code
     * null} if the declaration is preceded by no annotation. Deliberately narrow: it looks only at
     * the annotation lines immediately above the signature, so an unrelated {@code @Transactional}
     * elsewhere in this (large) class cannot make the gate pass or fail by accident.
     */
    private static String annotationImmediatelyBefore(String source, String methodName) {
        Matcher m =
                Pattern.compile(
                                "((?:^[ \\t]*@\\w[^\\n]*\\n)*)"
                                        + "^[ \\t]*(?:public |private |protected )?[\\w<>,\\[\\] .]+ "
                                        + Pattern.quote(methodName)
                                        + "\\s*\\(",
                                Pattern.MULTILINE)
                        .matcher(source);
        while (m.find()) {
            String annotations = m.group(1);
            if (annotations != null && annotations.contains("@Transactional")) {
                return annotations;
            }
        }
        return null;
    }

    private static int countMatches(String source, Pattern pattern) {
        Matcher m = pattern.matcher(source);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String readWalletServiceSource() {
        Path base = Paths.get(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path file = base.resolve("src/main/java/com/influora/service/WalletService.java");
        assertTrue(Files.exists(file), "WalletService.java not found at " + file);
        try {
            // Comments stripped: the javadoc on these methods talks about @Transactional at length,
            // and a gate that matched its own explanation would be red for the wrong reason.
            return Files.readString(file, StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("//[^\\n]*", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
