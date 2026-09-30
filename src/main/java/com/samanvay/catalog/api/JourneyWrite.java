package com.samanvay.catalog.api;

/** Register and publish journeys onboarded from a department's manifest. */
public interface JourneyWrite {

    /** Create a journey as DRAFT. 400 if the code is blank/duplicate or no categories are given. */
    JourneyDefinition createJourney(JourneyDraft draft);

    /**
     * Publish a DRAFT journey (make it live to citizens). 400 if it is not ready — i.e. some required
     * category has no PUBLISHED connector for the department that provides it.
     */
    JourneyDefinition publishJourney(String code);
}
