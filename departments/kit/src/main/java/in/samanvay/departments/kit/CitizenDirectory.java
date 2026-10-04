package in.samanvay.departments.kit;

import java.util.Optional;

/** What the portal needs from a department's own citizen records: sign in, and the person behind a person ID. */
public interface CitizenDirectory {

    /** The person ID for a correct registered mobile number and password, else empty. */
    Optional<String> authenticate(String mobile, String password);

    Optional<Person> person(String personId);
}
