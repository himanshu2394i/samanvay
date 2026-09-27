package in.samanvay.simulators;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Reverse half of the boundary (the forward half, main app -> simulator, is
 * com.samanvay.ArchitectureTest in the main build). The first guard is the
 * build itself: this pom has no dependency on samanvay-core, so main-app
 * classes can't be on this classpath. This rule catches the remaining leak:
 * main-app code copied or re-declared under com.samanvay inside this module.
 */
@AnalyzeClasses(packages = {"in.samanvay.simulators", "com.samanvay"}, importOptions = ImportOption.DoNotIncludeTests.class)
class SimulatorBoundaryTest {

    @ArchTest
    static final ArchRule simulator_does_not_depend_on_the_main_app = noClasses()
            .that()
            .resideInAPackage("in.samanvay.simulators..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.samanvay..")
            .because("the simulator is an external department stand-in; it reaches the main app only over HTTP, if at all");

    @ArchTest
    static final ArchRule no_main_app_packages_in_the_simulator_build = noClasses()
            .should()
            .resideInAPackage("com.samanvay..")
            .allowEmptyShould(true)
            .because("main-app code must never be copied into the simulator module");
}
