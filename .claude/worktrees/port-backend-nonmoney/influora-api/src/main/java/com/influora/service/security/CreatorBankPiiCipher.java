package com.influora.service.security;

import com.influora.config.PiiEncryptionProperties;
import org.springframework.stereotype.Service;

/**
 * Spec 12 §3.1 / §4 bank account + UPI AES-256-GCM (Kabir M-K6-C3-2).
 *
 * <p>Encrypt-before-ship gate: {@link com.influora.service.payout.CreatorBankAccountService} is the
 * only persistence path — plaintext bank/UPI must never reach JDBC.
 */
@Service
public class CreatorBankPiiCipher {

    private final AesGcmCipher cipher;

    public CreatorBankPiiCipher(PiiEncryptionProperties props) {
        byte[] key =
                AesGcmCipher.decodeKey(
                        props.getBankEncryptionKey(), "influora.pii.bank-encryption-key");
        this.cipher = new AesGcmCipher(key, "Bank/UPI PII");
    }

    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    public String decrypt(String encrypted) {
        return cipher.decrypt(encrypted);
    }
}
