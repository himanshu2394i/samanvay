package in.samanvay.simulators.department;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fake, deterministic records for the sandbox department service. Nothing here is real: names, incomes
 * and marks are invented, and the same key always yields the same record (a few named fixtures, the
 * rest derived from the key with {@link String#hashCode()}, which is specified and stable across JVMs).
 */
final class SandboxDepartmentData {

    record Income(String annualIncome, String annualIncomeDisplay, String holderName, String district, String issuerOffice) {}

    record Marks(String studentId, String percentage, String board, String exam) {}

    private static final Map<String, Income> INCOME_FIXTURES = Map.of(
            "RC-1001", new Income("742000", "Rs 7,42,000", "Sandbox Holder", "Pune", "Tahsildar, Haveli"),
            "RC-1002", new Income("185000", "Rs 1,85,000", "Asha Patil", "Nashik", "Tahsildar, Nashik"));

    private static final Map<String, Marks> MARKS_FIXTURES = Map.of(
            "S-1001", new Marks("S-1001", "91", "icse", "ISC 2025"),
            "S-1002", new Marks("S-1002", "81", "cbse", "AISSCE 2025"));

    private static final List<String> HOLDERS = List.of("Meera Kulkarni", "Vikas Jadhav", "Sunita Pawar", "R. Deshmukh");
    private static final List<String> DISTRICTS = List.of("Pune", "Nashik", "Nagpur", "Satara");
    private static final List<String> BOARDS = List.of("cbse", "icse", "msbshse", "cisce");

    private SandboxDepartmentData() {}

    static Income income(String rationCard) {
        Income known = INCOME_FIXTURES.get(rationCard.toUpperCase(Locale.ROOT));
        if (known != null) {
            return known;
        }
        int h = Math.floorMod(rationCard.hashCode(), 1_000_000);
        long income = 100_000L + (h % 90) * 10_000L;
        String district = DISTRICTS.get(h % DISTRICTS.size());
        return new Income(
                Long.toString(income),
                "Rs " + income,
                HOLDERS.get(h % HOLDERS.size()),
                district,
                "Tahsildar, " + district);
    }

    static Marks marks(String studentId) {
        Marks known = MARKS_FIXTURES.get(studentId.toUpperCase(Locale.ROOT));
        if (known != null) {
            return known;
        }
        int h = Math.floorMod(studentId.hashCode(), 1_000_000);
        return new Marks(studentId, Integer.toString(50 + h % 50), BOARDS.get(h % BOARDS.size()), "HSC 2025");
    }
}
