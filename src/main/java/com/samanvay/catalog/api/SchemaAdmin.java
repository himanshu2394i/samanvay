package com.samanvay.catalog.api;

import java.util.List;

/**
 * Reading and adding to the central schema. A schema is never edited in place, because published connectors map onto it:
 * a change is a new version ({@code @2}). Onboarding picks the highest version for a category.
 */
public interface SchemaAdmin {

    List<SchemaSummary> summaries();

    /** @throws com.samanvay.shared.InvalidRequestException when the draft is not valid or the ref already exists */
    SchemaSummary create(SchemaDraft draft);
}
