package com.samanvay.shared;

import java.util.Optional;

/**
 * Infra port (HLD P6). Audit checkpoints and later consent grants resolve keys here —
 * never from the database.
 */
public interface SecretStore {

    record Secret(byte[] bytes) {}

    Secret resolve(String key);

    /**
     * The secret only if it was actually provisioned: never generated, never a
     * dev default. Credentials for external sources are read through this. The
     * default suits stores that hold only provisioned secrets; stores that can
     * generate (like {@link EnvSecretStore}) override it.
     */
    default Optional<Secret> find(String key) {
        return Optional.ofNullable(resolve(key));
    }
}
