package com.samanvay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
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
    static final ArchRule notifications_does_not_depend_on_connector = noClasses()
            .that()
            .resideInAPackage("com.samanvay.notifications..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.samanvay.connector..");

    /**
     * Department simulators (simulators/, package in.samanvay.simulators) are a
     * separate app reached only over HTTP. First guard: the main pom has no
     * dependency on them, so this can't compile. This rule is the second guard,
     * e.g. against the simulator being added as a dependency later.
     */
    static final String SIMULATOR_PACKAGE = "in.samanvay.simulators..";

    @ArchTest
    static final ArchRule main_app_does_not_depend_on_simulators = noClasses()
            .that()
            .resideInAPackage("com.samanvay..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(SIMULATOR_PACKAGE)
            .because("simulators are external stand-ins reached over HTTP; no simulator-only code paths in the main app");

    @ArchTest
    static void simulator_classes_are_not_on_the_main_classpath(JavaClasses ignored) {
        JavaClasses leaked = new ClassFileImporter().importPackages("in.samanvay.simulators");
        assertThat(leaked.stream().map(JavaClass::getName))
                .as("simulator classes must never be on samanvay-core's classpath")
                .isEmpty();
    }

    @ArchTest
    static void modulith_is_respected(JavaClasses classes) {
        ApplicationModules.of(SamanvayApplication.class).verify();
    }
}
