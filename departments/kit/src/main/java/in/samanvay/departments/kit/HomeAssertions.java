package in.samanvay.departments.kit;

/** Signs the department's login assertion (docs/contracts/login-assertion.md) for a citizen who just signed in on its own portal. */
public interface HomeAssertions {

    /** A signed assertion for this person with a fresh state and nonce, carrying their name and birth date when known. */
    String issue(Person person);
}
