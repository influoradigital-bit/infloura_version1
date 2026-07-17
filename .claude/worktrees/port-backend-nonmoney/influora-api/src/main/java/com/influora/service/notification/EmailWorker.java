package com.influora.service.notification;

import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.EmailOutboxStatus;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOutboxRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled worker that processes the email outbox (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md).
 * Polls every 30 seconds, picks up pending emails, sends via MSG91, and updates status.
 *
 * <p>Uses exponential backoff on failures (30s, 90s, 270s, 810s) with max 5 retries.
 */
@Component
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);
    private static final int BATCH_SIZE = 50;

    private final EmailOutboxRepository emailOutboxRepository;
    private final Msg91EmailClient msg91Client;

    public EmailWorker(EmailOutboxRepository emailOutboxRepository, Msg91EmailClient msg91Client) {
        this.emailOutboxRepository = emailOutboxRepository;
        this.msg91Client = msg91Client;
    }

    /**
     * Polls the email outbox every 30 seconds and processes pending emails.
     */
    @Scheduled(fixedDelay = 30000) // 30 seconds
    @Transactional
    public void processOutbox() {
        List<EmailOutbox> pending =
                emailOutboxRepository.findPendingForSend(
                        EmailOutboxStatus.PENDING, Instant.now(), PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) {
            return;
        }

        log.info("Processing {} pending emails", pending.size());

        for (EmailOutbox outbox : pending) {
            processOne(outbox);
        }
    }

    private void processOne(EmailOutbox outbox) {
        try {
            boolean success =
                    msg91Client.sendTemplateEmail(
                            outbox.getToEmail(), outbox.getTemplateKey(), outbox.getTemplateData());

            if (success) {
                outbox.markSent();
                emailOutboxRepository.save(outbox);
                log.debug(
                        "Email sent successfully: id={}, toEmail={}, templateKey={}",
                        outbox.getId(),
                        outbox.getToEmail(),
                        outbox.getTemplateKey());
            } else {
                handleFailure(outbox, "MSG91 returned failure");
            }
        } catch (Exception e) {
            handleFailure(outbox, e.getMessage());
        }
    }

    private void handleFailure(EmailOutbox outbox, String errorMessage) {
        outbox.markFailed(errorMessage);
        emailOutboxRepository.save(outbox);

        if (outbox.canRetry()) {
            log.warn(
                    "Email send failed, will retry: id={}, attempt={}, nextRetry={}, error={}",
                    outbox.getId(),
                    outbox.getRetryCount(),
                    outbox.getNextRetryAt(),
                    errorMessage);
        } else {
            log.error(
                    "Email send failed permanently: id={}, toEmail={}, templateKey={}, error={}",
                    outbox.getId(),
                    outbox.getToEmail(),
                    outbox.getTemplateKey(),
                    errorMessage);
        }
    }
}
