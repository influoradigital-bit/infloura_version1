package com.influora.integration.msg91;

/**
 * Public wrapper for {@link Msg91EmailClient#renderPreview} — the subject/HTML/plain-text a real
 * send would produce for a given {@code templateKey}/data, without dispatching anything.
 *
 * <p>Exists because {@link EmailTemplateRegistry} (and its {@code Rendered} record) are
 * package-private by design — this class is the one public seam callers outside {@code
 * com.influora.integration.msg91} (e.g. {@code AdminCustomEmailService}'s preview endpoint) are
 * meant to render through, so every caller goes through the exact same rendering path a real send
 * uses instead of a second copy of the shell markup.
 */
public record EmailPreviewResult(String subject, String html, String plainText) {}
