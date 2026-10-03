package com.samanvay.catalog.api;

import java.util.List;

public interface JourneyCatalog {
    JourneyDefinition byCode(String journeyCode);

    JourneyPolicy policy(String journeyCode);

    List<JourneyDefinition> all();

    /** Where the department's own portal offers this journey (from its manifest), empty when it published none. */
    default java.util.Optional<String> portalUrl(String journeyCode) {
        return java.util.Optional.empty();
    }
}
