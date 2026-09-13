package com.samanvay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "com.samanvay", importOptions = ImportOption.DoNotIncludeTests.class)
class NoJourneyHardcodingTest {

    static final ArchRule no_journey_code_literals = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.samanvay.orchestration..",
                    "com.samanvay.consent..",
                    "com.samanvay.connector..",
                    "com.samanvay.identity..",
                    "com.samanvay.tracking..")
            .should(new NoHardcodedJourneyCodes());

    @ArchTest
    static final ArchRule production_code_has_no_journey_literals = no_journey_code_literals;

    @ArchTest
    static final ArchRule no_department_for_switches = noMethods().should().haveName("departmentFor");

    @ArchTest
    static final ArchRule no_categories_for_switches = noMethods().should().haveName("categoriesFor");

    @Test
    void hardcoding_rule_fails_on_named_journey_equals() {
        var imported = new ClassFileImporter().importClasses(NamedJourneySwitch.class);
        assertThatThrownBy(() -> no_journey_code_literals.check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("POST_MATRIC_SCHOLARSHIP");
    }

    static final class NamedJourneySwitch {
        boolean matches(String journeyCode) {
            return journeyCode.equals("POST_MATRIC_SCHOLARSHIP") || journeyCode.equals("BUSINESS_NOC");
        }
    }
}
