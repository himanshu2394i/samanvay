package com.samanvay.catalog.api;

import java.util.List;

public interface JourneyCatalog {
    JourneyDefinition byCode(String journeyCode);

    JourneyPolicy policy(String journeyCode);

    List<JourneyDefinition> all();
}
