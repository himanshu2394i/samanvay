package in.samanvay.departments.education;

import java.util.Optional;

/**
 * The marks statement the Board holds for a student (fake). One student ID unlocks it, so there is no resolve step.
 *
 * <p>Two stores: {@link JdbcMarksRecords} reads the Board's own Postgres when {@code education.db.url} is set (a deployment),
 * {@link SeedMarksRecords} is the built-in fake seed used by tests and a bare local run.
 */
interface MarksRecords {

    record Marks(String studentId, String percentage, String board, String exam) {}

    /** The student's latest marks statement. */
    Optional<Marks> find(String studentId);
}
