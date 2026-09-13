package com.samanvay.shared;

/**
 * Infra port (HLD P6). Audit checkpoints and later consent grants resolve keys here —
 * never from the database.
 */
public interface SecretStore {

    record Secret(byte[] bytes) {}

    Secret resolve(String key);
}
