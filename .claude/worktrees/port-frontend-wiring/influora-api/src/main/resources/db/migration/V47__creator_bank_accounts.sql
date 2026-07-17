-- M-K6-C3-2: creator bank/UPI encrypt-before-ship (Spec 12 §3.1 / §4).
-- Ciphertext-only columns; 24h cool-down via usable_after. No plaintext bank/UPI.

CREATE TABLE creator_bank_accounts (
    id                VARCHAR(26)  COLLATE utf8mb4_unicode_ci NOT NULL PRIMARY KEY,
    creator_user_id   VARCHAR(26)  COLLATE utf8mb4_unicode_ci NOT NULL,
    account_ciphertext TEXT         COLLATE utf8mb4_unicode_ci NOT NULL,
    ifsc_ciphertext   TEXT          COLLATE utf8mb4_unicode_ci NULL,
    instrument_type   VARCHAR(16)   COLLATE utf8mb4_unicode_ci NOT NULL,
    display_mask      VARCHAR(32)   COLLATE utf8mb4_unicode_ci NOT NULL,
    usable_after      TIMESTAMP(3) NOT NULL,
    created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_creator_bank_user FOREIGN KEY (creator_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_creator_bank_user ON creator_bank_accounts (creator_user_id);
