package in.samanvay.departments.kit;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The department's manifest signing key, kept in a file and created on first start. Samanvay pins its thumbprint when an admin
 * approves the signed manifest, and later accepts consent statements only if they are signed with this same key. The manifest signer
 * ({@link SignedManifestFilter}) and the consent signer both read it from here, so both always use one key. The file is created
 * owner-only and moved into place atomically ({@link SecretFiles}).
 */
public final class ManifestKey {

    private ManifestKey() {}

    /** Synchronized so the manifest filter and the consent signer, built at the same moment, cannot each make a different key. */
    public static synchronized ECKey loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                return ECKey.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
            ECKey created = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
            SecretFiles.writeOwnerOnly(file, created.toJSONString());
            return created;
        } catch (IOException | java.text.ParseException | com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("cannot load or create the manifest signing key " + file, e);
        }
    }
}
