package com.influora.domain.enums;

/**
 * Which side of the /festival-box audience toggle produced an enquiry (T-FESTIVALBOX-0905).
 *
 * <p>This is not a role and never becomes one: an enquiry is pre-signup, so a {@code BRAND}
 * enquiry has no {@code Workspace} and a {@code CREATOR} enquiry has no {@code CreatorProfile}.
 * It only decides which half of the {@code festival_enquiries} row is populated and which
 * admin columns are worth rendering.
 */
public enum FestivalEnquiryType {
    /** A brand asking to sponsor a slot — carries company/tier/product-category. */
    BRAND,
    /** A creator applying to the roster — carries handle/followers/city. */
    CREATOR
}
