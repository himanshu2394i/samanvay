package in.samanvay.departments.education;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The Board's marks from its own Postgres (see {@code db/schema.sql}): the student's latest statement, as the SOAP face returns it. */
@Component
@ConditionalOnExpression("'${education.db.url:}' != ''")
class JdbcMarksRecords implements MarksRecords {

    private final JdbcClient jdbc;

    JdbcMarksRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Marks> find(String studentId) {
        return jdbc.sql("SELECT person_id, percentage, board, exam, exam_year FROM marks_statement WHERE person_id = :id ORDER BY exam_year DESC, exam LIMIT 1")
                .param("id", studentId)
                .query((rs, i) -> new Marks(rs.getString("person_id"), plain(rs.getBigDecimal("percentage")), rs.getString("board"), rs.getString("exam")))
                .optional();
    }

    /** 91.00 -> "91", 86.40 -> "86.4": how the Board has always printed a percentage. */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
