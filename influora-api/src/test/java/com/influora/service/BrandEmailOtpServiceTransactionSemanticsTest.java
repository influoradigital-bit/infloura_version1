package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.influora.common.ApiException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards the one property that keeps the OTP lockout alive.
 *
 * <p>Why this test is a reflection check and not a behavioural one: the bug it guards
 * (attempts++ rolled back by the ApiException that reports the wrong code, so the
 * >= MAX_ATTEMPTS guard never fires) is invisible to a Mockito test BY CONSTRUCTION.
 * BrandEmailOtpServiceTest stubs findFirstByEmailOrderByCreatedAtDesc to return the same
 * in-memory EmailOtpChallenge on every call, so incrementAttempts() accumulates on the heap
 * and the counter appears to work — there is no transaction, so there is nothing to roll
 * back. That suite even asserts save() was called 3 times, which is equally true in
 * production and equally meaningless there. No amount of mock tuning fixes this; only a real
 * transaction manager can observe the rollback, which needs Testcontainers and therefore does
 * not run on a local `mvn test` (the Docker-dependent classes are Skipped here).
 *
 * <p>So this asserts the annotation directly. It is narrow, but it fails the instant someone
 * "tidies up" the noRollbackFor back to a bare @Transactional, which is exactly how the bug
 * would return.
 */
class BrandEmailOtpServiceTransactionSemanticsTest {

    @Test
    @DisplayName("verifyOtp keeps the failed-attempt increment when it refuses with an ApiException")
    void verifyOtpMustNotRollBackOnApiException() throws Exception {
        Method verifyOtp =
                BrandEmailOtpService.class.getMethod("verifyOtp", String.class, String.class);

        Transactional tx = verifyOtp.getAnnotation(Transactional.class);
        assertNotNull(tx, "verifyOtp must stay @Transactional - it writes the challenge row.");

        assertArrayEquals(
                new Class<?>[] {ApiException.class},
                tx.noRollbackFor(),
                "verifyOtp must declare noRollbackFor = ApiException.class. Without it Spring's"
                        + " default RuntimeException rollback rule undoes the attempts++ on the"
                        + " wrong-code path, so every guess re-reads attempts = 0 and the"
                        + " 3-attempt lockout can never trigger - unlimited guesses against a live"
                        + " 300-second challenge.");
    }
}
