package com.samanvay.catalog.api;

import java.util.List;

/**
 * A governed purpose code (DEPA consent purpose shape: code, refUri, text, category type).
 *
 * @param requesterDepartment the department that may request consent under this purpose
 * @param dataCategories the data categories a consent for this purpose covers
 */
public record Purpose(
        String code,
        String text,
        String refUri,
        String categoryType,
        boolean active,
        String requesterDepartment,
        List<String> dataCategories) {

    public Purpose {
        dataCategories = dataCategories == null ? List.of() : List.copyOf(dataCategories);
    }
}
