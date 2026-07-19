package eu.wohlben.qits.seeding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.control.ProjectService;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The startup self-seed gate (see {@code docs/epics/qits-live-deployment/features/2026-07-19_startup-qits-self-seed.md}): the
 * reconcile fires only on a packaged deployment ({@link LaunchMode#NORMAL}) with the kill-switch
 * on. The pure predicate is unit-tested directly; the {@code @QuarkusTest} boot (which runs in
 * {@link LaunchMode#TEST}) confirms the observer stayed inert — no "qits" project was seeded.
 */
@QuarkusTest
public class StartupSelfSeedGateTest {

  @Inject ProjectService projectService;

  @Test
  public void seedsOnlyWhenPackagedAndEnabled() {
    assertTrue(StartupSelfSeed.shouldSeed(LaunchMode.NORMAL, true), "packaged + enabled seeds");
    assertFalse(StartupSelfSeed.shouldSeed(LaunchMode.NORMAL, false), "the kill-switch is honored");
    assertFalse(
        StartupSelfSeed.shouldSeed(LaunchMode.DEVELOPMENT, true), "quarkus:dev never self-seeds");
    assertFalse(StartupSelfSeed.shouldSeed(LaunchMode.TEST, true), "tests never self-seed");
  }

  @Test
  public void testLaunchModeBootDidNotSelfSeed() {
    assertTrue(
        projectService.list().stream().noneMatch(p -> "qits".equals(p.name)),
        "no 'qits' project was seeded during a TEST-mode boot");
  }
}
