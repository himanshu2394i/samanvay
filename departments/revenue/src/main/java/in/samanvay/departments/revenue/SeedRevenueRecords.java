package in.samanvay.departments.revenue;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The built-in fake seed: two people and their certificates. Used when no database is configured (tests, a bare local run).
 *
 * <p>ponytail: in-memory, restart resets it. A deployment points {@code revenue.db.url} at Postgres instead.
 */
@Component
@ConditionalOnExpression("'${revenue.db.url:}' == ''")
class SeedRevenueRecords implements RevenueRecords {

    private final Map<String, String> people = Map.of("RV-1001", "Asha Patil", "RV-1002", "Ravi Deshmukh");

    private final List<Doc> docs = List.of(
            income("INC-2025-0001", "RV-1001", "2025-04-10", "2024-25", 160000, "Asha Patil", "Nashik"),
            income("INC-2026-0007", "RV-1001", "2026-04-12", "2025-26", 185000, "Asha Patil", "Nashik"),
            income("INC-2025-0002", "RV-1002", "2025-05-02", "2024-25", 742000, "Ravi Deshmukh", "Pune"),
            new Doc("CASTE_CERTIFICATE", "CST-0001", "RV-1001", LocalDate.parse("2024-08-20"), fields(
                    "holderName", "Asha Patil", "caste", "Maratha", "casteCategory", "OBC", "issuerOffice", "Tahsildar, Nashik")),
            new Doc("DOMICILE_CERTIFICATE", "DOM-0001", "RV-1001", LocalDate.parse("2024-08-21"), fields(
                    "holderName", "Asha Patil", "state", "Maharashtra", "district", "Nashik", "issuerOffice", "Tahsildar, Nashik")),
            new Doc("DOMICILE_CERTIFICATE", "DOM-0002", "RV-1002", LocalDate.parse("2025-01-15"), fields(
                    "holderName", "Ravi Deshmukh", "state", "Maharashtra", "district", "Pune", "issuerOffice", "Tahsildar, Haveli")));

    @Override
    public boolean personExists(String personId) {
        return people.containsKey(personId);
    }

    @Override
    public List<Doc> forPerson(String personId, String type) {
        return docs.stream().filter(d -> d.personId().equals(personId) && d.type().equals(type))
                .sorted(Comparator.comparing(Doc::issuedOn).reversed()).toList();
    }

    @Override
    public Optional<Doc> byKey(String type, String key) {
        return docs.stream().filter(d -> d.type().equals(type) && d.key().equals(key)).findFirst();
    }

    private static Doc income(String key, String person, String issued, String fy, int amount, String name, String district) {
        return new Doc("INCOME_CERTIFICATE", key, person, LocalDate.parse(issued), fields(
                "annualIncome", Integer.toString(amount), "annualIncomeDisplay", "Rs " + amount,
                "holderName", name, "district", district, "issuerOffice", "Tahsildar, " + district,
                "financialYear", fy));
    }

    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
