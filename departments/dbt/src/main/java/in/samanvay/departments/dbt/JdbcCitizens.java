package in.samanvay.departments.dbt;

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
 * <p>ponytail: no lockout or throttling; a mobile that is not registered returns a little faster than a wrong password.
 */
@Component
@ConditionalOnExpression("'${dbt.db.url:}' != ''")
class JdbcCitizens implements CitizenStore {

    private final JdbcClient jdbc;

    JdbcCitizens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> authenticate(String mobile, String password) {
        if (mobile == null || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT person_id FROM citizen_login WHERE mobile = :m AND password_hash = crypt(:p, password_hash)")
                .param("m", mobile).param("p", password).query(String.class).optional();
    }

    @Override
    public Optional<Person> person(String personId) {
        return jdbc.sql("SELECT full_name, date_of_birth FROM beneficiary WHERE person_id = :id").param("id", personId)
                .query((rs, n) -> new Person(personId, rs.getString("full_name"), rs.getObject("date_of_birth", LocalDate.class))).optional();
    }
}
