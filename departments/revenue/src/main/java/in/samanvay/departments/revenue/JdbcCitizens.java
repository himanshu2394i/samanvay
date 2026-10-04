package in.samanvay.departments.revenue;

import in.samanvay.departments.kit.Person;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Logins from the department's own Postgres. Passwords are stored as bcrypt hashes (pgcrypto) and checked in SQL, so the
 * plaintext never lives in this service's memory beyond the request, and SQL in a value does nothing (bound parameters).
 *
 * <p>Throttling is the caller's job ({@code SignInThrottle}); an unregistered mobile still costs one bcrypt check, like a wrong password.
 */
@Component
@ConditionalOnExpression("'${revenue.db.url:}' != ''")
class JdbcCitizens implements CitizenStore {

    /** A valid bcrypt setting (cost 6, what gen_salt('bf') makes) used to do the same work for a mobile that is not registered. */
    static final String DUMMY_SETTING = "$2a$06$abcdefghijklmnopqrstuu";

    private final JdbcClient jdbc;

    JdbcCitizens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> authenticate(String mobile, String password) {
        if (mobile == null || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        // One bcrypt check whether or not the mobile is registered, so the time taken does not tell an attacker which mobiles exist.
        Optional<Optional<String>> row = jdbc.sql("SELECT person_id, password_hash = crypt(:p, password_hash) AS ok FROM citizen_login WHERE mobile = :m")
                .param("m", mobile).param("p", password)
                .query((rs, n) -> rs.getBoolean("ok") ? Optional.of(rs.getString("person_id")) : Optional.<String>empty()).optional();
        if (row.isEmpty()) {
            jdbc.sql("SELECT crypt(:p, :h)").param("p", password).param("h", DUMMY_SETTING).query(String.class).single();
        }
        return row.orElse(Optional.empty());
    }

    @Override
    public Optional<Person> person(String personId) {
        return jdbc.sql("SELECT full_name, date_of_birth FROM person WHERE person_id = :id").param("id", personId)
                .query((rs, n) -> new Person(personId, rs.getString("full_name"), rs.getObject("date_of_birth", LocalDate.class))).optional();
    }
}
