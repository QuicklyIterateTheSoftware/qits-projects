package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The <b>configured</b> half of the reserved set — the platform's environment names, arriving as
 * {@code qits.projects.reserved-slugs} (env {@code QITS_PROJECTS_RESERVED_SLUGS}).
 *
 * <p>A class of its own because a {@code @TestProfile} is per class and the shipped case — the key
 * unset, so the reservation is {@code ProjectService.RESERVED_SLUGS} and nothing else — has to be a
 * profile that does not carry it; that half lives in {@link ProjectServiceTest}.
 *
 * <p>What is under test is the union and the two reasons in it. An environment name cannot be a
 * project slug because the editor is served at {@code editor.<project>.<domain>} while applications
 * are served at {@code <app>.<environment>.<domain>} and the edge reads the first two host labels —
 * a different mechanism from the routing family's shadowed path segment, so a caller refused for
 * one is told which.
 */
@QuarkusTest
@TestProfile(ReservedSlugsTest.EnvironmentsConfigured.class)
public class ReservedSlugsTest {

  /**
   * Three environments, spelled the way one env var actually arrives: padded after a comma, an
   * empty element from a trailing separator, and one in the wrong case. None of those is a
   * different word.
   */
  public static class EnvironmentsConfigured implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.projects.reserved-slugs", "dev, prod ,,STAGING");
    }
  }

  @Inject ProjectService projectService;

  @Inject ReservedSlugs reservedSlugs;

  /** Trimmed, lowercased, blanks dropped — the list is one env var, not three careful values. */
  @Test
  public void theConfiguredListIsNormalized() {
    assertEquals(Set.of("dev", "prod", "staging"), reservedSlugs.environmentNames());
    assertTrue(reservedSlugs.isReserved("staging"), "case is not a different environment");
    assertFalse(reservedSlugs.isReserved("dev-2"), "a suffixed slug is not the environment");
    assertFalse(reservedSlugs.isReserved(""), "blank is not reserved, it is absent");
  }

  /**
   * A supplied environment name is a 400 that names the word and the host reading it would break —
   * the collision class the whole key exists for.
   */
  @Test
  public void aSuppliedEnvironmentNameIsRefusedAndSaysWhy() {
    var refusal =
        assertThrows(
            BadRequestException.class,
            () -> projectService.create("Dev Environment", "dev", null));

    assertEquals(400, refusal.statusCode());
    assertTrue(refusal.getMessage().contains("'dev'"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("platform environment"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("editor.dev.<domain>"), refusal.getMessage());
  }

  /** The configured family does not displace the static one, and each keeps its own reason. */
  @Test
  public void theRoutingFamilyIsStillRefusedWithItsOwnReason() {
    var refusal =
        assertThrows(
            BadRequestException.class, () -> projectService.create("Projects", "projects", null));

    assertTrue(refusal.getMessage().contains("first path segment"), refusal.getMessage());
    assertFalse(
        refusal.getMessage().contains("platform environment"),
        "a routing collision must not be explained as an environment one");
  }

  /**
   * A derived slug states nothing about the value, so it suffixes past a reserved environment
   * exactly as it suffixes past a reserved route: a project called "Dev" still creates.
   */
  @Test
  public void aDerivedEnvironmentNameTakesTheNextFreeSuffix() {
    var project = projectService.create("Dev", null, null);

    assertEquals("dev-2", project.slug);
  }

  /**
   * The shipped configuration, asserted where it can be built rather than only inferred: with no
   * list at all the reservation is the static set and nothing else.
   */
  @Test
  public void noConfiguredListLeavesTheStaticSetAlone() {
    ReservedSlugs unconfigured = ReservedSlugs.forEnvironments(null);

    assertTrue(unconfigured.environmentNames().isEmpty());
    assertFalse(unconfigured.isReserved("dev"));
    assertTrue(unconfigured.isReserved("projects"));
    assertTrue(ReservedSlugs.forEnvironments(List.of()).environmentNames().isEmpty());
  }
}
