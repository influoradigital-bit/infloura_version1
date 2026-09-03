package com.influora.domain.enums;

/**
 * Scope of a creator's category/brand exclusivity commitment on a {@code Collaboration}
 * (T-MEERA-CREATOR-PHASE-A SPEC.md 1.1, A2). {@code NAMED_BRANDS} pairs with {@code
 * Collaboration.exclusivityBrands} (a JSON list of the excluded brand names); {@code CATEGORY}
 * and {@code NONE} carry no brand list.
 */
public enum ExclusivityScope {
    NONE,
    NAMED_BRANDS,
    CATEGORY
}
