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
 * approves the signed manifest, and later accepts consent statements only if they are signed with this same key. The department's
 * ManifestSigningFilter reads the same file, so both always use one key.
 */
public final class ManifestKey {

    private ManifestKey() {}

    public static ECKey loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                return ECKey.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
            ECKey created = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, created.toJSONString(), StandardCharsets.UTF_8);
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
            file.toFile().setWritable(false, false);
            file.toFile().setWritable(true, true);
            return created;
        } catch (IOException | java.text.ParseException | com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("cannot load or create the manifest signing key " + file, e);
        }
    }
}
