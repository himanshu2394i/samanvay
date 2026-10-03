package in.samanvay.departments.education;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Logins from configuration ({@code education.login.users}, entries {@code mobile|password|personId}): the built-in fake accounts
 * used when no database is configured. Every entry is compared so timing does not reveal which mobiles exist.
 *
 * <p>ponytail: plaintext dev passwords in config; a deployment uses {@link JdbcCitizens} with hashed passwords.
 */
@Component
@ConditionalOnExpression("'${education.db.url:}' == ''")
class ConfiguredCitizens implements CitizenStore {

    private record User(String mobile, byte[] password, String personId) {}

    private final List<User> users;

    ConfiguredCitizens(@Value("${education.login.users}") List<String> users) {
        this.users = users.stream().map(String::trim).filter(u -> !u.isEmpty()).map(u -> {
            String[] p = u.split("\\|");
            return new User(p[0], p[1].getBytes(StandardCharsets.UTF_8), p[2]);
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
}
