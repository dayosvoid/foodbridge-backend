package com.tesa.foodbridge;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
    @Test
    void modulesDoNotAccessOtherModulesInternals() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.tesa.foodbridge");
        for (String module : java.util.List.of("user","listing","reservation","handover","activity","auth","dashboard")) {
            ArchRuleDefinition.noClasses().that().resideOutsideOfPackage("com.tesa.foodbridge."+module+"..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "com.tesa.foodbridge."+module+".domain..",
                    "com.tesa.foodbridge."+module+".repository..",
                    "com.tesa.foodbridge."+module+".service..",
                    "com.tesa.foodbridge."+module+".web..")
                .check(classes);
        }
        com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
            .matching("com.tesa.foodbridge.(*)..")
            .should().beFreeOfCycles().check(classes);
    }
}
