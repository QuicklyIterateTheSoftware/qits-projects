package eu.wohlben.qits.projects;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.CausationRowRules;

/**
 * The platform's shared ArchUnit rules over this module's classes. Today that is the causation-row
 * completeness guard: every {@code @Entity} either implements {@code CausedRow} (and lists {@code
 * CausationStamp} in its {@code @EntityListeners}) or declares {@code @Uncaused}, so a new entity
 * that skips the decision fails this build naming the class instead of leaving a silent hole in the
 * trace.
 *
 * <p><b>One of these per ENTITY module, not one in {@code service}.</b> A module owns its entities
 * and must fail its own build for them; {@code epics} in particular depends on nothing — a rule
 * enforced only from a module downstream of it would not see its four classes, and lifting it out
 * would take the entities and leave the guard behind. The package analysed is this module's, so the
 * sibling's classes are neither needed here nor missed.
 *
 * <p>The rule set lives in qits-arch-rules (qits-integrations-quarkus); a new set added there
 * arrives here as one more {@code @ArchTest} line.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.projects",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);
}
