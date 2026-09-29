package com.samanvay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import org.junit.jupiter.api.Test;

/** The dev stub still generates, and now generates a matching audit verifying key for each signing key id. */
class EnvSecretStoreAuditPairTest {

    @Test
    void generatedAuditSigningAndVerifyingKeysArePerIdPairs() throws Exception {
        EnvSecretStore store = new EnvSecretStore();
        assertThat(store.mayGenerate()).isTrue();

        for (String suffix : new String[] {"", "-v2"}) {
            byte[] sk = store.resolve("audit-checkpoint-signing-key" + suffix).bytes();
            byte[] vk = store.resolve("audit-checkpoint-verifying-key" + suffix).bytes();
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(sk)));
            signer.update(new byte[] {1, 2, 3});
            byte[] sig = signer.sign();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(vk)));
            verifier.update(new byte[] {1, 2, 3});
            assertThat(verifier.verify(sig)).as("pair" + suffix).isTrue();
        }
        assertThat(store.resolve("audit-checkpoint-signing-key").bytes())
                .isNotEqualTo(store.resolve("audit-checkpoint-signing-key-v2").bytes());
    }

    @Test
    void findNeverGeneratesAuditKeys() {
        assertThat(new EnvSecretStore().find("audit-checkpoint-verifying-key-v9")).isEmpty();
    }
}
