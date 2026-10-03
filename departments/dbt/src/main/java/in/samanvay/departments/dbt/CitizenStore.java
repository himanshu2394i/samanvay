package in.samanvay.departments.dbt;

import java.util.Optional;

/**
 * Who may sign in to this department's citizen login. A citizen signs in with the mobile number registered with the
 * department and a password; the answer is the department's own person ID for them.
 */
interface CitizenStore {

    /** The person ID for a correct mobile and password, else empty. */
    Optional<String> authenticate(String mobile, String password);
}
