package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V186/V188: every consent-relevant purpose attribute is catalog data and reaches Java intact. */
@SpringBootTest(classes = SamanvayApplication.class)
class PurposeCatalogColumnsIT extends PostgresIntegrationTest {

    @Autowired
    PurposeCatalog purposes;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.samanvay.catalog.api.JourneyCatalog journeys;

    @Test
    void everyColumnRoundTrips() {
        jdbc.update("""
                INSERT INTO catalog_purpose (code, text, category_type, status, requester_department, data_categories,
                    data_types, requester_rule, max_duration_days, duration_rule, frequency,
                    label_en, label_mr, label_en_status, label_mr_status, separate_opt_in)
                VALUES ('RT_PURPOSE', 'Round trip', 'JOURNEY', 'ACTIVE', 'REVENUE', ARRAY['MARKS'],
                    ARRAY['TYPE_A','TYPE_B'], 'PRIOR_AWARD_DEPARTMENT', 42, 'UNTIL_X', 'ONCE_PER_YEAR',
                    'English label', 'मराठी लेबल', 'APPROVED', 'DRAFT', true)
                ON CONFLICT DO NOTHING
                """);
        Purpose p = purposes.byCode("RT_PURPOSE").orElseThrow();
        assertThat(p.active()).isTrue();
        assertThat(p.requesterDepartment()).isEqualTo("REVENUE");
        assertThat(p.dataCategories()).containsExactly("MARKS");
        assertThat(p.dataTypes()).containsExactly("TYPE_A", "TYPE_B");
        assertThat(p.requesterRule()).isEqualTo(Purpose.RequesterRule.PRIOR_AWARD_DEPARTMENT);
        assertThat(p.maxDurationDays()).isEqualTo(42);
        assertThat(p.durationRule()).isEqualTo("UNTIL_X");
        assertThat(p.frequency()).isEqualTo(Purpose.Frequency.ONCE_PER_YEAR);
        assertThat(p.labelEn()).isEqualTo("English label");
        assertThat(p.labelMr()).isEqualTo("मराठी लेबल");
        assertThat(p.labelEnStatus()).isEqualTo(Purpose.LabelStatus.APPROVED);
        assertThat(p.labelMrStatus()).isEqualTo(Purpose.LabelStatus.DRAFT);
        assertThat(p.separateOptIn()).isTrue();
    }

    @Test
    void misspelledFrequencyIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO catalog_purpose (code, text, category_type, status, requester_department, data_categories,
                    frequency)
                VALUES ('TYPO_FREQ', 'typo', 'JOURNEY', 'ACTIVE', 'SCHOLARSHIP', ARRAY['MARKS'],
                    'ONCE_PER_DOCUMENT_PER_APPLICTION')
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("catalog_purpose_frequency_known");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_purpose WHERE code = 'TYPO_FREQ'", Integer.class))
                .isZero();
        // every seeded value (V186) is allowed, and NULL stays allowed for legacy purposes
        assertThat(jdbc.queryForList("SELECT DISTINCT frequency FROM catalog_purpose WHERE frequency IS NOT NULL",
                        String.class))
                .containsExactlyInAnyOrder("ONCE", "ONCE_PER_DOCUMENT_PER_APPLICATION", "ONCE_PER_PAYMENT", "ONCE_PER_YEAR");
        assertThat(purposes.byCode("SCHOLARSHIP_ELIGIBILITY").orElseThrow().frequency()).isNull();
        assertThat(purposes.byCode("SCH_BANK_VERIFY").orElseThrow().frequency()).isEqualTo(Purpose.Frequency.ONCE_PER_PAYMENT);
    }

    @Test
    void scholarshipPurposesAreSeededAsCatalogData() {
        assertThat(List.of("SCH_ELIGIBILITY_CHECK", "SCH_BANK_VERIFY", "SCH_IDENTITY_REVIEW", "SCH_RENEWAL_CHECK"))
                .allSatisfy(code -> {
                    Purpose p = purposes.byCode(code).orElseThrow();
                    assertThat(p.active()).isTrue();
                    assertThat(p.requesterDepartment()).isEqualTo("SCHOLARSHIP");
                    assertThat(p.labelEnStatus()).isEqualTo(Purpose.LabelStatus.APPROVED);
                    assertThat(p.labelMrStatus()).isEqualTo(Purpose.LabelStatus.DRAFT);
                    assertThat(p.labelMr()).isNotBlank();
                    assertThat(p.dataTypes()).isNotEmpty();
                    assertThat(p.maxDurationDays()).isPositive();
                });
        Purpose renewal = purposes.byCode("SCH_RENEWAL_CHECK").orElseThrow();
        assertThat(renewal.separateOptIn()).isTrue();
        assertThat(renewal.requesterRule()).isEqualTo(Purpose.RequesterRule.PRIOR_AWARD_DEPARTMENT);
        assertThat(purposes.byCode("SCH_ELIGIBILITY_CHECK").orElseThrow().separateOptIn()).isFalse();
        assertThat(purposes.byCode("SCH_ELIGIBILITY_CHECK").orElseThrow().maxDurationDays()).isEqualTo(180);
        assertThat(purposes.byCode("SCH_IDENTITY_REVIEW").orElseThrow().maxDurationDays()).isEqualTo(30);
    }

    @Test
    void legacyJourneyPurposesKeepDefaults() {
        Purpose p = purposes.byCode("SCHOLARSHIP_ELIGIBILITY").orElseThrow();
        assertThat(p.requesterRule()).isEqualTo(Purpose.RequesterRule.CATALOG_DEPARTMENT);
        assertThat(p.maxDurationDays()).isNull();
        assertThat(p.labelEnStatus()).isEqualTo(Purpose.LabelStatus.MISSING);
        assertThat(p.separateOptIn()).isFalse();
    }

    @Test
    void academicYearStartMonthIsSchemeConfigNotPurposeData() {
        assertThat(columns("catalog_purpose"))
                .as("academic year belongs to the scheme's journey config, never to catalog_purpose")
                .doesNotContain("academic_year_start_month");
        assertThat(jdbc.queryForList("SELECT * FROM catalog_purpose")).allSatisfy(row -> assertThat(row.keySet())
                .noneMatch(k -> k.toLowerCase().contains("academic")));
        assertThat(columns("catalog_journey")).contains("academic_year_start_month");
        assertThat(journeys.byCode("POST_MATRIC_SCHOLARSHIP").academicYearStartMonth())
                .as("scholarship scheme: academic year starts in June")
                .isEqualTo(6);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE catalog_journey SET academic_year_start_month = 13 WHERE code = 'POST_MATRIC_SCHOLARSHIP'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?", String.class, table);
    }

    @Test
    void labelStatusAndRuleAreConstrained() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO catalog_purpose (code, text, category_type, label_en_status) "
                        + "VALUES ('BAD_LABEL', 'x', 'JOURNEY', 'APPROVED')"))
                .as("APPROVED needs a label")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO catalog_purpose (code, text, category_type, label_mr, label_mr_status) "
                        + "VALUES ('BAD_STATUS', 'x', 'JOURNEY', 'y', 'FINAL')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO catalog_purpose (code, text, category_type, requester_rule) "
                        + "VALUES ('BAD_RULE', 'x', 'JOURNEY', 'BODY')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
