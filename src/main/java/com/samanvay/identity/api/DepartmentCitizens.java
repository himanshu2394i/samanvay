package com.samanvay.identity.api;

import java.util.UUID;

/**
 * What a department portal asks of Samanvay about its citizens, after the citizen signed in on the portal itself. Samanvay has no
 * citizen sign in: a person is known by the department login they proved (docs/contracts/login-assertion.md).
 */
public interface DepartmentCitizens {

    /** @param created true when this sign in made the citizen record */
    record Resolution(UUID citizenId, boolean created) {}

    /**
     * Verifies the home department's signed assertion (accepted once) and returns the citizen it belongs to, creating the citizen
     * with a link to that department on the first sign in. Refused with {@link LinkProofInvalidException} when the proof is bad.
     */
    Resolution resolve(String departmentCode, String assertion, String actorId);

    /**
     * Saves the link proven by {@code departmentCode}'s assertion (it must carry the state issued to this citizen). If the
     * person is already linked to another citizen and this citizen is an empty record made by a home sign in, the two are merged
     * (one session just proved both identities) and the surviving citizen ID is returned. Any other collision is a
     * {@link DuplicateLocalIdException}, including a citizen who is already linked at that department to a DIFFERENT person.
     */
    UUID link(UUID citizenId, String departmentCode, String assertion, String actorId);
}
