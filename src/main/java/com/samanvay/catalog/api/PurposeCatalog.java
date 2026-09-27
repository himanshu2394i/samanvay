package com.samanvay.catalog.api;

import java.util.Optional;

/** The catalog's registry of purpose codes. Access grants may only name an active one. */
public interface PurposeCatalog {

    Optional<Purpose> byCode(String code);

    default boolean isActive(String code) {
        return code != null && byCode(code).map(Purpose::active).orElse(false);
    }
}
