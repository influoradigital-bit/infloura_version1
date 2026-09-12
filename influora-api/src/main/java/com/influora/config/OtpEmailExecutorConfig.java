package com.influora.config;

import com.influora.service.OtpEmailDispatcher;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * [F2] A dedicated pool for OTP email delivery, separate from Boot's shared
 * {@code applicationTaskExecutor}.
 *
 * <p><b>Why not the shared one.</b> It also carries {@code NotificationListener} and the creator
 * sync jobs, and its auto-configured queue is effectively unbounded — a burst against the
 * unauthenticated send endpoint would grow it without limit and push unrelated notification work
 * behind a queue of OTP sends.
 *
 * <p><b>Why AbortPolicy, and why CallerRunsPolicy would be a bug.</b> The usual instinct for a full
 * queue is {@code CallerRunsPolicy}, which runs the task on the calling thread. Here the calling
 * thread is the HTTP request thread and the task is a blocking SMTP transaction — precisely the
 * thing moving delivery off-thread exists to eliminate. Under load that would silently restore the
 * enumeration oracle for whichever requests got unlucky, and it would do so only when the system
 * was already struggling, which is the hardest condition in which to notice. Aborting instead costs
 * one undelivered code that the visitor can retry, and keeps every response the same shape and
 * duration. The rejection is caught at the call site so it never reaches the response.
 *
 * <p><b>Sizing</b> follows the real ceiling rather than a guess: {@code BrandEmailOtpService} caps
 * sends at 8/hour per origin and 3/hour per address, so sustained legitimate volume is small; the
 * queue exists to absorb a burst, not to store a backlog.
 */
@Configuration
public class OtpEmailExecutorConfig {

    @Bean(name = OtpEmailDispatcher.EXECUTOR)
    public Executor otpEmailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("otp-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
