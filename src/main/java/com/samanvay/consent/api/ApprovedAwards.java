package com.samanvay.consent.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The citizen's approved applications ("awards"), as far as consent needs them: a purpose
 * whose requester rule is {@code PRIOR_AWARD_DEPARTMENT} may only be requested by the
 * department that approved an award in the prior year.
 *
 * <p>Declared here and implemented by {@code tracking}, which owns application status, so
 * consent does not depend on tracking (tracking already depends on consent).
 */
public interface ApprovedAwards {

    List<Award> forCitizen(UUID citizenId);

    /**
     * @param journeyCode the catalog journey of the approved application (its policy names
     *     the department that decides it)
     * @param decidedAt when it was approved (or submitted, for rows with no decision time)
     */
    record Award(String referenceNo, String journeyCode, Instant decidedAt) {}
}
