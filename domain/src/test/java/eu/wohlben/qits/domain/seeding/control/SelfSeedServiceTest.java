package eu.wohlben.qits.domain.seeding.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.entity.RepositorySubmodule;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.RepositorySubmoduleRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reconcile coverage for the startup self-seed (see {@code
 * docs/features/2026-07-19_startup-qits-self-seed.md}), offline through the module's global {@code
 * FakeContainerRuntime} — no docker, no GitHub. The manifest urls are redirected to committed
 * fixtures: the qits-backend slot to {@code submodule-super.git} (direct children child-a + shared,
 * and child-a nests grandchild — the depth that exercises the one-level deep import, standing in
 * for the real quarkus-angular child's nested {@code webui} edge), the qits-angular slot to the
 * plain {@code testing-repo.git}. Asserts the additive, per-item-idempotent reconcile: creates the
 * project + both repos + siblings + the nested edge, a re-run is a full no-op, a half-seeded state
 * is completed, and rows the manifest does not own are untouched.
 */
@QuarkusTest
@TestProfile(SelfSeedServiceTest.TestProfile.class)
public class SelfSeedServiceTest {

  static final String QITS_BACKEND_FIXTURE = "submodule-super.git";
  static final String QITS_ANGULAR_FIXTURE = "testing-repo.git";

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-self-seed");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            // Padded on purpose (a trailing newline is how an env file / k8s ConfigMap value
            // arrives)
            // so the whole suite exercises the manifest-side trim: without it the second reconcile
            // in reRunIsAFullNoOp would re-clone a duplicate qits-backend.
            "qits.startup-seed.repo-url", "  " + fixturePath(QITS_BACKEND_FIXTURE) + "\n",
            "qits.startup-seed.angular-integration-url", fixturePath(QITS_ANGULAR_FIXTURE));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private static String fixturePath(String name) throws Exception {
      return SelfSeedServiceTest.class.getResource("/fixtures/" + name).toURI().getPath();
    }
  }

  @Inject SelfSeedService selfSeedService;
  @Inject ProjectService projectService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject RepositorySubmoduleRepository submoduleRepository;

  /** A clean slate each method — {@code @QuarkusTest} shares one in-memory DB across the class. */
  @BeforeEach
  void clean() {
    List.copyOf(projectService.list()).forEach(p -> projectService.delete(p.id));
  }

  private String fixture(String name) throws Exception {
    return getClass().getResource("/fixtures/" + name).toURI().getPath();
  }

  /** The qits project, or an assertion failure — there must be exactly one after a reconcile. */
  private Project qitsProject() {
    var projects = projectService.list().stream().filter(p -> "qits".equals(p.name)).toList();
    assertEquals(1, projects.size(), "exactly one 'qits' project");
    return projects.get(0);
  }

  /** The project's repositories keyed by the trailing bare-repo name of their url. */
  private Map<String, Repository> reposByName(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .collect(Collectors.toMap(r -> Path.of(r.url).getFileName().toString(), r -> r));
  }

  private long edgeCount(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .flatMap(r -> submoduleRepository.findByParentId(r.id).stream())
        .count();
  }

  private void assertEdge(String parentId, String path, String expectedChildId) {
    RepositorySubmodule edge =
        submoduleRepository.findByParentId(parentId).stream()
            .filter(e -> e.path.equals(path))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no edge at path " + path + " under " + parentId));
    assertEquals(expectedChildId, edge.child.id, "edge at " + path + " points at the right child");
  }

  @Test
  public void reconcileSeedsProjectBothReposSiblingsAndTheNestedDeepImportEdge() {
    selfSeedService.reconcile();

    Project project = qitsProject();
    Map<String, Repository> repos = reposByName(project.id);
    assertEquals(
        Set.of(
            "submodule-super.git", // the qits-backend slot
            "submodule-child-a.git", // its direct child
            "submodule-shared.git", // its direct child (diamond) + child-a's, deduped
            "submodule-grandchild.git", // reached only by the one-level deep import into child-a
            "testing-repo.git"), // the qits-angular slot, no submodules
        repos.keySet(),
        "both manifest repos, the direct siblings, and the deep-imported grandchild");

    // The deep import descended one level into every direct child of the superproject: child-a's
    // own
    // submodules were imported — the nested edge (the stand-in for the quarkus-angular webui edge).
    assertEdge(
        repos.get("submodule-child-a.git").id,
        "grandchild",
        repos.get("submodule-grandchild.git").id);

    // The qits-angular slot is submodule-free (importSubmodules=false in the manifest).
    assertTrue(
        submoduleRepository.findByParentId(repos.get("testing-repo.git").id).isEmpty(),
        "the @qits/angular slot has no submodule edges");
  }

  @Test
  public void reRunIsAFullNoOp() {
    selfSeedService.reconcile();
    long reposAfterFirst = repositoryRepository.count();
    long edgesAfterFirst = edgeCount(qitsProject().id);

    selfSeedService.reconcile();

    assertEquals(reposAfterFirst, repositoryRepository.count(), "re-run adds no repositories");
    assertEquals(edgesAfterFirst, edgeCount(qitsProject().id), "re-run adds no edges");
    qitsProject(); // still exactly one project (matched by name, not recreated)
  }

  @Test
  public void aWhitespacePaddedOverrideDoesNotReCloneADuplicate() {
    // Regression (review finding): the profile's repo-url override carries a trailing newline,
    // which
    // cloneOne stores trimmed. The manifest must trim its match key too — otherwise the untrimmed
    // override never re-matches its own stored row and a fresh qits-backend is cloned every boot.
    selfSeedService.reconcile();
    selfSeedService.reconcile();

    long superRows =
        repositoryRepository.find("project.id", qitsProject().id).list().stream()
            .filter(r -> Path.of(r.url).getFileName().toString().equals("submodule-super.git"))
            .count();
    assertEquals(1, superRows, "the padded override matched its trimmed row — exactly one clone");
  }

  @Test
  public void halfSeededStateIsCompletedOnTheNextReconcile() throws Exception {
    // Simulate a prior boot that created the project and only the angular repo (or a grown manifest
    // whose new qits-backend entry hasn't landed yet): reconcile must add exactly the missing
    // entry.
    Project project = projectService.create("qits", "pre-existing");
    projectService.createRepositoryUnderProject(
        project.id, fixture(QITS_ANGULAR_FIXTURE), RepositoryArchetype.SERVICE, false);

    selfSeedService.reconcile();

    Map<String, Repository> repos = reposByName(qitsProject().id);
    assertTrue(
        repos.containsKey("submodule-super.git"), "the missing qits-backend entry was added");
    assertTrue(repos.containsKey("submodule-grandchild.git"), "its deep import ran too");
    assertTrue(repos.containsKey("testing-repo.git"), "the already-present entry survived");
  }

  @Test
  public void rowsTheManifestDoesNotOwnAreLeftUntouched() throws Exception {
    // A user-added repository under a pre-existing "qits" project must survive an additive
    // reconcile.
    Project project = projectService.create("qits", "user's own project named qits");
    Repository userRepo =
        projectService.createRepositoryUnderProject(
            project.id, fixture("submodule-cycle-a.git"), RepositoryArchetype.SERVICE, false);

    selfSeedService.reconcile();

    Map<String, Repository> repos = reposByName(qitsProject().id);
    Repository stillThere = repos.get("submodule-cycle-a.git");
    assertNotNull(stillThere, "the user's repository is untouched");
    assertEquals(userRepo.id, stillThere.id, "same row, not recreated");
    assertTrue(
        repos.containsKey("submodule-super.git"), "manifest repos were still added alongside");
    assertTrue(repos.containsKey("testing-repo.git"), "both manifest repos added");
  }
}
