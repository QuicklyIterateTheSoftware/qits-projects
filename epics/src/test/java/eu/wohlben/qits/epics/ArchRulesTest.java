package eu.wohlben.qits.epics;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.CausationRowRules;

/**
 * The platform's shared ArchUnit rules over this module's classes — the twin of {@code domain}'s,
 * and separate for the reason that class's javadoc gives: this module depends on nothing and must
 * carry its own guard so a lift-out takes it along. The package is {@code eu.wohlben.qits.epics},
 * not {@code …projects…}: these four entities were never under the projects namespace, and a rule
 * pointed at the wrong root would pass by seeing nothing.
 *
 * <p>Today the one rule set is the causation-row completeness guard: every {@code @Entity} either
 * implements {@code CausedRow} — all four here do — or declares {@code @Uncaused} with its reason.
 * A new entity that skips the decision fails this build naming the class.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.epics",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);
}
