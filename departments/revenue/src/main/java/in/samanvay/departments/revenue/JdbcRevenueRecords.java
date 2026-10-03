package in.samanvay.departments.revenue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Revenue's records from its own Postgres (see {@code db/schema.sql}). Every value reaches SQL as a bound parameter. */
@Component
@ConditionalOnExpression("'${revenue.db.url:}' != ''")
class JdbcRevenueRecords implements RevenueRecords {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS = "cert_no, cert_type, person_id, issued_on, fields::text AS fields";

    private final JdbcClient jdbc;

    JdbcRevenueRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean personExists(String personId) {
        return jdbc.sql("SELECT count(*) FROM person WHERE person_id = :id").param("id", personId).query(Integer.class).single() > 0;
    }

    @Override
    public List<Doc> forPerson(String personId, String type) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM certificate WHERE person_id = :p AND cert_type = :t ORDER BY issued_on DESC, cert_no DESC")
                .param("p", personId).param("t", type).query(JdbcRevenueRecords::doc).list();
    }

    @Override
    public Optional<Doc> byKey(String type, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM certificate WHERE cert_type = :t AND cert_no = :k")
                .param("t", type).param("k", key).query(JdbcRevenueRecords::doc).optional();
    }

    @SuppressWarnings("unchecked")
    private static Doc doc(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Map<String, Object> fields = new LinkedHashMap<>(JSON.readValue(rs.getString("fields"), Map.class));
        return new Doc(rs.getString("cert_type"), rs.getString("cert_no"), rs.getString("person_id"),
                rs.getDate("issued_on").toLocalDate(), fields);
    }
}
