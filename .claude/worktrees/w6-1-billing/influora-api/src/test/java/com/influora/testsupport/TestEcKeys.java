package com.influora.testsupport;

/**
 * Test-only EC (P-256) keypairs for Wave E task E-JWKS unit tests. Deliberately DISTINCT from the
 * dev-default keypair committed in {@code application.yml} / {@code .env.example} — tests should
 * never depend on (or accidentally validate against) the real dev/prod-adjacent key material.
 *
 * <p>Generated with:
 *
 * <pre>
 * openssl ecparam -name prime256v1 -genkey -noout -out sec1.pem
 * openssl pkcs8 -topk8 -nocrypt -in sec1.pem -out pkcs8.pem
 * openssl ec -in sec1.pem -pubout
 * </pre>
 */
public final class TestEcKeys {

    private TestEcKeys() {}

    public static final String PRIVATE_KEY_PEM =
            "-----BEGIN PRIVATE KEY-----\n"
                    + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgX5T5bBJD+18v3WNE\n"
                    + "4+cqnoInko4OqROvV3J3vywCCjChRANCAASKsTizIquXu2CkfqivD0LtMjx7ispW\n"
                    + "Ev5j8J0isKx24DL7Dya/frZJy9lQwsIcq7T17UPsD0CWEIOnYPMUAo9T\n"
                    + "-----END PRIVATE KEY-----";

    public static final String PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n"
                    + "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEirE4syKrl7tgpH6orw9C7TI8e4rK\n"
                    + "VhL+Y/CdIrCsduAy+w8mv362ScvZUMLCHKu09e1D7A9AlhCDp2DzFAKPUw==\n"
                    + "-----END PUBLIC KEY-----";

    /** An unrelated keypair — its private key must never successfully sign a token this test suite treats as valid. */
    public static final String WRONG_PRIVATE_KEY_PEM =
            "-----BEGIN PRIVATE KEY-----\n"
                    + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgiJ+32UmdLERZt5Pl\n"
                    + "kOel5PlQQag2+eH5M3TSF2K0rAKhRANCAATvGjgRn+GOkZwsHQPTwsZFfsbf1x8X\n"
                    + "j3Gehv2yXfsoOHHR6Cn+HY/jbdD2NwM0SnWzAy1dz5cza98c6tbkqRvx\n"
                    + "-----END PRIVATE KEY-----";

    public static final String WRONG_PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n"
                    + "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7xo4EZ/hjpGcLB0D08LGRX7G39cf\n"
                    + "F49xnob9sl37KDhx0egp/h2P423Q9jcDNEp1swMtXc+XM2vfHOrW5Kkb8Q==\n"
                    + "-----END PUBLIC KEY-----";
}
