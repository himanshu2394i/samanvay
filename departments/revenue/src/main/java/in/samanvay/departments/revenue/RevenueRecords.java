package in.samanvay.departments.revenue;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Revenue's own records: people and the certificates issued to them, held by this service only. Documents have their own
 * keys (certificate numbers); a person ID alone does not fetch them, which is why the manifest publishes a resolve step.
 *
 * <p>Two stores: {@link JdbcRevenueRecords} reads Revenue's own Postgres when {@code revenue.db.url} is set (a deployment),
 * {@link SeedRevenueRecords} is the built-in fake seed used by tests and a bare local run.
 */
interface RevenueRecords {

    /** {@code type} is the manifest category, e.g. INCOME_CERTIFICATE. */
    record Doc(String type, String key, String personId, LocalDate issuedOn, Map<String, Object> fields) {}

    boolean personExists(String personId);

    /** The person's documents of one type, newest first. */
    List<Doc> forPerson(String personId, String type);

    Optional<Doc> byKey(String type, String key);

    /** The newest document of a person and type (by issue date). */
    default Optional<Doc> latest(String personId, String type) {
        return forPerson(personId, type).stream().max(Comparator.comparing(Doc::issuedOn));
    }
}
