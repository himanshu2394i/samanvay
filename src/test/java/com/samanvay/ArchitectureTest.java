package com.samanvay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.modulith.core.ApplicationModules;

@AnalyzeClasses(packages = "com.samanvay", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modules_only_touch_their_own_tables = classes()
            .that()
            .resideInAPackage("..internal.repository..")
            .should(new TablePrefixMatchesModuleCondition());

    @ArchTest
    static void modulith_is_respected(JavaClasses classes) {
        ApplicationModules.of(SamanvayApplication.class).verify();
    }
}
