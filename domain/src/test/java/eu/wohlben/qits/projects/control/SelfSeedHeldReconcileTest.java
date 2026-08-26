package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The seed with {@code qits.startup-seed.reconcile-repositories=false} — a first bootstrap holding
 * the wrapper walk until it has registered the platform's bares itself (see the field's javadoc on
 * {@link SelfSeedService} for why an empty, UUID-keyed git host makes the walk destructive).
 *
 * <p>A class of its own because a {@code @TestProfile} is per class and the default — the key
 * unset, the walk running — is every other self-seed test; {@link SelfSeedServiceTest} is the
 * regression for it.
 */
@QuarkusTest
@TestProfile(SelfSeedHeldReconcileTest.ReconcileHeld.class)
public class SelfSeedHeldReconcileTest {

  /** {@link SelfSeedServiceTest.TestProfile}'s fixtures, with the walk held. */
  public static class ReconcileHeld extends SelfSeedServiceTest.TestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
      overrides.put("qits.startup-seed.reconcile-repositories", "false");
      return overrides;
    }
  }

  @Inject SelfSeedService selfSeedService;
  @Inject ProjectService projectService;
  @Inject RepositoryRepository repositoryRepository;

  @BeforeEach
  void clean() {
    List.copyOf(projectService.list()).forEach(p -> projectService.delete(p.id));
  }

  private static String fixture(String name) {
    return GitFixtures.path(name);
  }

  private Project qitsProject() {
    var projects = projectService.list().stream().filter(p -> "qits".equals(p.name)).toList();
    assertEquals(1, projects.size(), "exactly one 'qits' project");
    return projects.get(0);
  }

  /**
   * The project and its wrapper are created exactly as always — the held boot leaves a complete
   * project, not a half-written one.
   */
  @Test
  public void theProjectAndItsWrapperAreStillSeeded() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    Repository wrapper = projectService.findWrapper(project.id).orElseThrow();
    assertEquals(RepositoryArchetype.PROJECT, wrapper.archetype);
    assertEquals(
        fixture(SelfSeedServiceTest.QITS_WRAPPER_FIXTURE),
        wrapper.url,
        "the wrapper carries its origin, so its .gitmodules is there to walk later");
  }

  /**
   * And nothing else: no entry of the wrapper's {@code .gitmodules} is adopted, and none is cloned.
   * This is the whole point — on a seed boot every entry would miss the adopt arm and be mirrored in
   * from GitHub before the bootstrap had seeded a single bare.
   */
  @Test
  public void noWrapperEntryIsAdoptedOrCloned() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    assertEquals(
        1,
        repositoryRepository.count("project.id", project.id),
        "the wrapper alone — the walk that would have registered submodule-shared and"
            + " submodule-grandchild never ran");
  }

  /** Nor is anything reported: the pass that names rows no wrapper entry declares is the walk. */
  @Test
  public void aPlaceableRowIsNotReported() {
    Project project =
        projectService.create(
            "qits", "qits", "pre-existing", fixture(SelfSeedServiceTest.QITS_WRAPPER_FIXTURE));
    Repository stray =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-a.git"), RepositoryArchetype.SERVICE);

    selfSeedService.reconcile();

    assertEquals(
        1,
        repositoryRepository.count("id = ?1", stray.id),
        "a SERVICE row the wrapper does not declare keeps its row while the walk is held");
  }

  /** Re-running is a no-op too, so a bootstrap that restarts the service twice loses nothing. */
  @Test
  public void reRunIsAFullNoOp() {
    selfSeedService.reconcile();
    String wrapperId = projectService.findWrapper(qitsProject().id).orElseThrow().id;

    selfSeedService.reconcile();

    assertEquals(wrapperId, projectService.findWrapper(qitsProject().id).orElseThrow().id);
    assertEquals(1, repositoryRepository.count("project.id", qitsProject().id));
    qitsProject(); // still exactly one project — matched by name, not recreated
  }
}
