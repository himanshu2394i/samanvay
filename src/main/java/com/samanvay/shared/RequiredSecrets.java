package com.samanvay.shared;

import java.util.List;

/**
 * A module's statement of which {@link SecretStore} keys it cannot run without. Every
 * bean of this type feeds the production boot guard, which refuses to start outside the
 * dev/demo profiles unless each listed key is actually provisioned ({@link SecretStore#find}),
 * so a missing key stops the boot with the key's name instead of failing on first use or,
 * worse, being replaced by an ephemeral one.
 */
public interface RequiredSecrets {

    /** SecretStore key names only, never values. */
    List<String> keys();
}
