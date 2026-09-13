package com.samanvay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.samanvay", importOptions = ImportOption.DoNotIncludeTests.class)
class NoJourneyHardcodingTest {

    @ArchTest
    static final ArchRule no_department_for_switches = noMethods()
            .that()
            .haveName("departmentFor")
            .should()
            .beDeclaredInClassesThat()
            .resideInAPackage("com.samanvay..");

    @ArchTest
    static final ArchRule no_categories_for_switches = noMethods()
            .that()
            .haveName("categoriesFor")
            .should()
            .beDeclaredInClassesThat()
            .resideInAPackage("com.samanvay..");
}
