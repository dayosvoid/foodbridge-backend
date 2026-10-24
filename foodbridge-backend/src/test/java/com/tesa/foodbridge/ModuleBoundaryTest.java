package com.tesa.foodbridge;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
    @Test
    void modulesDoNotAccessOtherModulesInternals() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.tesa.foodbridge");
        // Concrete module dependencies will be checked when implementation classes exist.
        // TODO: add ArchUnit layered/module rules before merging feature branches.
        org.junit.jupiter.api.Assertions.assertFalse(classes.isEmpty());
    }
}
