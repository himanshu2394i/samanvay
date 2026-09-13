package com.samanvay.catalog.api;

public interface JourneyCatalog {
    JourneyDefinition byCode(String journeyCode);

    JourneyPolicy policy(String journeyCode);
}
