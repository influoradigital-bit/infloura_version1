package com.influora.domain.enums;

/** {@code creator_captions.tag_status} — whether {@code CreatorThemeTaggingJob} has processed this
 * caption yet. {@code FAILED} is a defensive terminal state for an unexpected per-item exception
 * during tagging (the tagger itself, {@code ThemeMatchService.themesForText}, is documented to
 * never throw — this only guards against something else going wrong, e.g. the save itself). */
public enum CaptionTagStatus {
    PENDING,
    TAGGED,
    FAILED
}
