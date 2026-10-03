package in.samanvay.departments.agriculture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** A department is external to Samanvay: it must never depend on, or contain, main-app code. */
@AnalyzeClasses(packages = {"in.samanvay.departments", "com.samanvay"}, importOptions = ImportOption.DoNotIncludeTests.class)
class DepartmentBoundaryTest {

    @ArchTest
    static final ArchRule department_does_not_depend_on_the_main_app = noClasses()
            .that()
            .resideInAPackage("in.samanvay.departments..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.samanvay..")
            .because("a department is an external system; it reaches the main app only over HTTP, if at all");

    @ArchTest
    static final ArchRule no_main_app_packages_in_the_department_build = noClasses()
            .should()
            .resideInAPackage("com.samanvay..")
            .allowEmptyShould(true)
            .because("main-app code must never be copied into a department build");
}
