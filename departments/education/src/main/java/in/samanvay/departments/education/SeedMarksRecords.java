package in.samanvay.departments.education;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The built-in fake seed: two students. Used when no database is configured (tests, a bare local run).
 *
 * <p>ponytail: in-memory, restart resets it. A deployment points {@code education.db.url} at Postgres instead.
 */
@Component
@ConditionalOnExpression("'${education.db.url:}' == ''")
class SeedMarksRecords implements MarksRecords {

    private static final Map<String, Marks> MARKS = Map.of(
            "EDU-1001", new Marks("EDU-1001", "91", "msbshse", "HSC 2025"),
            "EDU-1002", new Marks("EDU-1002", "81", "msbshse", "HSC 2025"));

    @Override
    public Optional<Marks> find(String studentId) {
        return Optional.ofNullable(MARKS.get(studentId));
    }
}
