package in.samanvay.departments.agriculture;

import in.samanvay.departments.kit.Person;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Logins from configuration ({@code agriculture.login.users}, entries {@code mobile|password|personId[|name|dateOfBirth]}): the built-in fake accounts
 * used when no database is configured. Every entry is compared so timing does not reveal which mobiles exist.
 *
 * <p>ponytail: plaintext dev passwords in config; a deployment uses {@link JdbcCitizens} with hashed passwords.
 */
@Component
@ConditionalOnExpression("'${agriculture.db.url:}' == ''")
class ConfiguredCitizens implements CitizenStore {

    private record User(String mobile, byte[] password, String personId, String name, LocalDate dob) {}

    private final List<User> users;

    ConfiguredCitizens(@Value("${agriculture.login.users}") List<String> users) {
        this.users = users.stream().map(String::trim).filter(u -> !u.isEmpty()).map(u -> {
            String[] p = u.split("\\|");
            return new User(p[0], p[1].getBytes(StandardCharsets.UTF_8), p[2], p.length > 3 ? p[3] : "Farmer " + p[2],
                    p.length > 4 ? LocalDate.parse(p[4]) : null);
        }).toList();
    }

    @Override
    public Optional<String> authenticate(String mobile, String password) {
        if (mobile == null || password == null) {
            return Optional.empty();
        }
        byte[] given = password.getBytes(StandardCharsets.UTF_8);
        String found = null;
        for (User u : users) {
            boolean passOk = MessageDigest.isEqual(u.password(), given);
            if (passOk & u.mobile().equals(mobile)) {
                found = u.personId();
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public Optional<Person> person(String personId) {
        return users.stream().filter(u -> u.personId().equals(personId)).findFirst().map(u -> new Person(u.personId(), u.name(), u.dob()));
    }
}
