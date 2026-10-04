package in.samanvay.departments.education;

import in.samanvay.departments.kit.SignedManifestFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs this department's manifest (docs/contracts/manifest-signature.md). All of the work (the signature over the exact bytes, the
 * issue time and the audience, the key file, the discovery key, refusing {@code ;} paths) is the kit's {@link SignedManifestFilter};
 * this class only reads this department's property names.
 *
 * <p>The key is kept in the file {@code education.manifest.key-file} (the same one the consent signer uses); deleting it rotates the key and
 * Samanvay will ask an admin to approve the new one. If {@code education.manifest.discovery-key} is set, the manifest is shown only to a
 * caller that sends it in {@code X-Discovery-Key}. The audience is the origin of {@code education.public-base-url}.
 */
@Component
class ManifestSigningFilter extends SignedManifestFilter {

    ManifestSigningFilter(
            @Value("${education.manifest.key-file:manifest-signing-key.jwk}") String keyFile,
            @Value("${education.manifest.discovery-key:}") String discoveryKey,
            @Value("${education.public-base-url}") String publicBaseUrl) {
        super(keyFile, discoveryKey, publicBaseUrl);
    }
}
