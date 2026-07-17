package com.influora.common;

/**
 * Ownership-bound R2 object keys for metrics proof screenshots (Kabir M-24-1 / §4.7).
 *
 * <p>Canonical shape: {@code proof/{creatorUserId}/{deliverableId}/{ulid}.{ext}}
 */
public final class ProofObjectKeys {

    private static final int MAX_KEY_LENGTH = 500;
    private static final String PREFIX = "proof/";

    private ProofObjectKeys() {}

    public static String build(String creatorUserId, String deliverableId, String objectId, String extension) {
        String ext = extension == null || extension.isBlank() ? "png" : extension.replaceAll("[^a-zA-Z0-9]", "");
        if (ext.isBlank()) {
            ext = "png";
        }
        return PREFIX + creatorUserId + "/" + deliverableId + "/" + objectId + "." + ext.toLowerCase();
    }

    /**
     * Returns true when {@code key} is a non-traversing proof object owned by {@code creatorUserId}
     * for {@code deliverableId}.
     */
    public static boolean isOwnedProofKey(String key, String creatorUserId, String deliverableId) {
        if (key == null || creatorUserId == null || deliverableId == null) {
            return false;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_KEY_LENGTH) {
            return false;
        }
        if (trimmed.contains("..") || trimmed.startsWith("/") || trimmed.contains("\\")) {
            return false;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return false;
        }
        String expectedPrefix = PREFIX + creatorUserId + "/" + deliverableId + "/";
        if (!trimmed.startsWith(expectedPrefix)) {
            return false;
        }
        String remainder = trimmed.substring(expectedPrefix.length());
        return !remainder.isBlank() && !remainder.contains("/");
    }

    /**
     * Legacy milestone metrics path — bind to creator only (no deliverable id on that API).
     * Still rejects absolute URLs and path traversal.
     */
    public static boolean isOwnedByCreator(String key, String creatorUserId) {
        if (key == null || creatorUserId == null) {
            return false;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_KEY_LENGTH) {
            return false;
        }
        if (trimmed.contains("..") || trimmed.startsWith("/") || trimmed.contains("\\")) {
            return false;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return false;
        }
        String expectedPrefix = PREFIX + creatorUserId + "/";
        if (!trimmed.startsWith(expectedPrefix)) {
            return false;
        }
        String remainder = trimmed.substring(expectedPrefix.length());
        // creatorUserId / deliverableId / object
        return remainder.chars().filter(ch -> ch == '/').count() == 1 && !remainder.endsWith("/");
    }
}
