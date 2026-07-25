package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositorySubmodule;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.RepositorySubmoduleRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
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
import org.junit.jupiter.api.Test;

/**
 * Host-side coverage of <b>full-closure</b> submodule import: cloning with the import toggle
 * recursively imports the whole submodule closure as sibling repositories under the same project
 * (dedup by url, cycle-guarded), and registers a project-scoped name alias per repository so the
 * git host can serve them as siblings for a native {@code --recurse-submodules} clone. Here we
 * assert the rows/edges/aliases/dedup the import produces from the committed {@code
 * submodule-*.git} fixtures; the container-side checkout itself is proven by the real-docker {@code
 * WorkspaceSubmoduleMaterializationIT}.
 */
@QuarkusTest
@TestProfile(RepositoryServiceSubmoduleTest.TestProfile.class)
public class RepositoryServiceSubmoduleTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-submodules");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject RepositoryService repositoryService;
  @Inject ProjectService projectService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject RepositorySubmoduleRepository submoduleRepository;

  @Inject
  eu.wohlben.qits.domain.repository.persistence.RepositoryNameRepository repositoryNameRepository;

  @Inject WorkspaceRepository workspaceRepository;
  @Inject GitExecutor git;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String fixture(String name) throws Exception {
    return getClass().getResource("/fixtures/" + name).toURI().getPath();
  }

  /** The repositories in a project keyed by the trailing bare-repo name of their url. */
  private Map<String, Repository> reposByName(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .collect(Collectors.toMap(r -> Path.of(r.url).getFileName().toString(), r -> r));
  }

  @Test
  public void importToggleImportsFullClosureWithDiamondDedup() throws Exception {
    var project = projectService.create("Submodule Import", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);

    // Full closure: super's direct children AND their children, recursively. The diamond's shared
    // child is imported once (dedup by url) and linked from both super and child-a.
    Map<String, Repository> repos = reposByName(project.id);
    assertEquals(
        Set.of(
            "submodule-super.git",
            "submodule-child-a.git",
            "submodule-shared.git",
            "submodule-grandchild.git"),
        repos.keySet(),
        "the creation toggle imports the whole submodule closure as siblings");
    Repository childA = repos.get("submodule-child-a.git");
    assertEdge(superRepo.id, "child-a", childA.id);
    assertEdge(superRepo.id, "shared-direct", repos.get("submodule-shared.git").id);
    assertEdge(childA.id, "shared", repos.get("submodule-shared.git").id);
    assertEdge(childA.id, "grandchild", repos.get("submodule-grandchild.git").id);

    String sharedId = repos.get("submodule-shared.git").id;
    long edgesToShared =
        allEdges(project.id).stream().filter(e -> e.child.id.equals(sharedId)).count();
    assertEquals(2, edgesToShared, "the diamond child is imported once but linked twice");

    // Nothing is left unimported at any level — the whole tree materialized.
    assertTrue(
        repositoryService.listUnimportedSubmodules(superRepo.id).isEmpty(),
        "the superproject has no unimported submodules after a full-closure import");
    assertTrue(
        repositoryService.listUnimportedSubmodules(childA.id).isEmpty(),
        "the imported child has no unimported submodules either");
  }

  @Test
  public void importRegistersProjectScopedNameAliases() throws Exception {
    var project = projectService.create("Submodule Aliases", null);
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);

    // Every imported repository is addressable by its url basename within the project, so the git
    // host serves them as siblings and native `../<name>.git` resolution works.
    Map<String, Repository> repos = reposByName(project.id);
    for (var entry : repos.entrySet()) {
      String base = entry.getKey().replaceFirst("\\.git$", "");
      assertEquals(
          entry.getValue().id,
          repositoryNameRepository
              .findRepositoryByProjectAndName(project.id, base)
              .map(r -> r.id)
              .orElse(null),
          "name alias '" + base + "' resolves to its repository");
    }
  }

  @Test
  public void reimportIsIdempotent() throws Exception {
    var project = projectService.create("Submodule Recurse", null);
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);
    Repository childA = reposByName(project.id).get("submodule-child-a.git");

    // The full closure is already imported at creation; re-importing from any node adds nothing.
    repositoryService.importDirectSubmodules(childA.id);
    assertEquals(4, reposByName(project.id).size(), "re-import adds no repositories");
    assertEquals(
        2, submoduleRepository.findByParentId(childA.id).size(), "re-import adds no edges");
  }

  @Test
  public void creationWithoutTheToggleImportsNothing() throws Exception {
    var project = projectService.create("Submodule Opt-Out", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, false);

    assertEquals(1, reposByName(project.id).size(), "no siblings imported");
    assertTrue(submoduleRepository.findByParentId(superRepo.id).isEmpty(), "no edges");
    assertEquals(
        Set.of("child-a", "shared-direct"),
        repositoryService.listUnimportedSubmodules(superRepo.id).stream()
            .map(s -> s.path())
            .collect(Collectors.toSet()),
        "both direct submodules stay available for a later manual import");
  }

  @Test
  public void importedChildrenHaveNoMainWorkspaceButSuperprojectDoes() throws Exception {
    var project = projectService.create("Submodule Workspaces", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);

    assertFalse(
        workspaceRepository.findByRepositoryId(superRepo.id).isEmpty(),
        "the top-level superproject keeps its default main workspace");
    for (Repository child : reposByName(project.id).values()) {
      if (child.id.equals(superRepo.id)) {
        continue;
      }
      assertTrue(
          workspaceRepository.findByRepositoryId(child.id).isEmpty(),
          "imported child " + child.url + " must not get an independent main workspace");
    }
  }

  @Test
  public void dedupIsScopedToProjectSoTwoProjectsGetIndependentChildren() throws Exception {
    var projectA = projectService.create("Isolate A", null);
    var projectB = projectService.create("Isolate B", null);
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, projectA, true);
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, projectB, true);

    Repository sharedInA = reposByName(projectA.id).get("submodule-shared.git");
    Repository sharedInB = reposByName(projectB.id).get("submodule-shared.git");
    assertNotEquals(
        sharedInA.id, sharedInB.id, "two projects get independent mirrors of the same submodule");
  }

  @Test
  public void cyclicSubmodulesLinkBackWithoutDuplicating() throws Exception {
    var project = projectService.create("Cycle", null);
    // cycle-a -> cycle-b -> cycle-a. The recursive full-closure import's visited guard terminates
    // the cycle: importing a brings in b, and b's back-edge finds a already present (dedup by url).
    var cycleA =
        repositoryService.cloneRepository(fixture("submodule-cycle-a.git"), null, project, true);

    Map<String, Repository> repos = reposByName(project.id);
    assertEquals(
        Set.of("submodule-cycle-a.git", "submodule-cycle-b.git"),
        repos.keySet(),
        "the two-node cycle imports each node once; the back-edge reuses the existing row");
    Repository cycleB = repos.get("submodule-cycle-b.git");
    assertNotEquals(cycleA.id, cycleB.id);
    assertEquals(
        cycleA.id,
        submoduleRepository.findByParentId(cycleB.id).get(0).child.id,
        "b's submodule edge points back at the existing a");
  }

  @Test
  public void plainRepositoryImportsWithZeroEdges() throws Exception {
    // No-op regression: a submodule-free repo produces no edges and no extra sibling repositories.
    var project = projectService.create("No Submodules", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project, true);

    assertTrue(submoduleRepository.findByParentId(repo.id).isEmpty(), "no submodule edges");
    assertTrue(repositoryService.listUnimportedSubmodules(repo.id).isEmpty(), "nothing available");
    assertEquals(1, reposByName(project.id).size(), "only the single imported repository exists");
  }

  @Test
  public void projectDeleteRemovesSuperprojectChildrenAndEdges() throws Exception {
    var project = projectService.create("Submodule Delete", null);
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);
    List<String> repoIds =
        reposByName(project.id).values().stream().map(r -> r.id).collect(Collectors.toList());
    assertFalse(allEdges(project.id).isEmpty(), "edges exist before delete");

    // Deletes repositories one at a time — the FK cascade on both endpoints must clear edges before
    // the second endpoint is deleted, else a referential-integrity violation (the V32 bug class).
    // Must not throw.
    projectService.delete(project.id);

    // Assert via count() queries (guaranteed DB round-trips) rather than get()/findByIdOptional,
    // which would hit Hibernate's L1 cache in this non-transactional test and return the
    // deleted-but-still-managed entity.
    assertEquals(
        0,
        repositoryRepository.count("project.id", project.id),
        "superproject + all imported children removed");
    assertEquals(
        0,
        submoduleRepository.count("parent.id in ?1", repoIds),
        "all submodule edges cascaded away");
  }

  @Test
  public void pullRefreshesImportedSubmoduleSiblingRepositories() throws Exception {
    // Private mutable upstreams: mirror the fixture bares side by side (so the super's relative
    // `../submodule-shared.git` keeps resolving), then advance them like a real remote would.
    Path upstreams = Files.createTempDirectory("qits-submodule-upstreams");
    Path superUpstream = upstreams.resolve("submodule-simple-super.git");
    Path childUpstream = upstreams.resolve("submodule-shared.git");
    git.exec(
        null,
        "git",
        "clone",
        "--mirror",
        fixture("submodule-simple-super.git"),
        superUpstream.toString());
    git.exec(
        null,
        "git",
        "clone",
        "--mirror",
        fixture("submodule-shared.git"),
        childUpstream.toString());

    var project = projectService.create("Submodule Pull", null);
    var superRepo =
        repositoryService.cloneRepository(superUpstream.toString(), null, project, true);
    Repository child = reposByName(project.id).get("submodule-shared.git");

    // Advance the child upstream and bump the super upstream's gitlink to the new tip — the exact
    // shape of an "update from main" arriving with a submodule pointer bump.
    String newChildSha = advanceUpstream(childUpstream);
    bumpGitlink(superUpstream, "lib", newChildSha);
    assertFalse(
        originHasCommit(child.id, newChildSha),
        "the imported sibling's origin is stale before the pull");

    repositoryService.pullRepository(superRepo.id);

    // Without the child refresh, the container's `submodule update` would fail with "Server does
    // not allow request for unadvertised object <sha>" — the git host can only serve what the
    // sibling's origin has.
    assertTrue(
        originHasCommit(child.id, newChildSha),
        "pulling the superproject refreshes the imported sibling, so the bumped gitlink is servable");
  }

  @Test
  public void childPullFailureWarnsWithoutFailingTheSuperprojectPull() throws Exception {
    Path upstreams = Files.createTempDirectory("qits-submodule-upstreams-gone");
    Path superUpstream = upstreams.resolve("submodule-simple-super.git");
    Path childUpstream = upstreams.resolve("submodule-shared.git");
    git.exec(
        null,
        "git",
        "clone",
        "--mirror",
        fixture("submodule-simple-super.git"),
        superUpstream.toString());
    git.exec(
        null,
        "git",
        "clone",
        "--mirror",
        fixture("submodule-shared.git"),
        childUpstream.toString());

    var project = projectService.create("Submodule Pull Degrade", null);
    var superRepo =
        repositoryService.cloneRepository(superUpstream.toString(), null, project, true);

    // The child's upstream vanishes: its pull must degrade loudly (a WARNING line in the output),
    // never block the superproject's own — already successful — pull.
    deleteRecursively(childUpstream);
    String output = repositoryService.pullRepository(superRepo.id);
    assertTrue(
        output.contains("WARNING: pull of imported submodule 'lib'"),
        "the unreachable child surfaces as a warning, not a failure: " + output);
  }

  @Test
  public void pullTerminatesOnCyclicSubmoduleImports() throws Exception {
    var project = projectService.create("Cycle Pull", null);
    // a -> b -> a, fully imported: the pull's visited guard must terminate the recursion.
    var cycleA =
        repositoryService.cloneRepository(fixture("submodule-cycle-a.git"), null, project, true);
    Repository cycleB = reposByName(project.id).get("submodule-cycle-b.git");
    repositoryService.importDirectSubmodules(cycleB.id);

    repositoryService.pullRepository(cycleA.id);
  }

  @Test
  public void prepareThenImportDedupsOntoTheServedSibling() throws Exception {
    var project = projectService.create("Prepare Onboard", null);
    // The simple-super has one submodule `lib` -> ../submodule-shared.git, left unimported here.
    var superRepo =
        repositoryService.cloneRepository(
            fixture("submodule-simple-super.git"), null, project, false);
    assertEquals(1, reposByName(project.id).size(), "only the superproject before prepare");

    // Pre-serve the submodule's real backend as a sibling (the onboarding convenience that breaks
    // the chicken-and-egg). The backend is the shared fixture beside the super's own bare — exactly
    // where the superproject's `../submodule-shared.git` folds.
    var prepared =
        repositoryService.prepareSubmoduleBackend(superRepo.id, fixture("submodule-shared.git"));
    assertEquals("submodule-shared", prepared.name());
    assertEquals("../submodule-shared.git", prepared.relativeUrl(), "the relative url to commit");

    Repository served = reposByName(project.id).get("submodule-shared.git");
    assertNotNull(served, "prepare pre-serves the sibling so an in-container add resolves");
    assertEquals(served.id, prepared.repositoryId());
    assertEquals(
        served.id,
        repositoryNameRepository
            .findRepositoryByProjectAndName(project.id, "submodule-shared")
            .map(r -> r.id)
            .orElse(null),
        "the sibling is addressable by name for a native ../submodule-shared.git clone");

    // Now the committed .gitmodules reference gets imported — it must DEDUP onto the pre-served
    // sibling (dedup by the canonical resolved url), never create a duplicate, and link the edge.
    repositoryService.importDirectSubmodules(superRepo.id);
    assertEquals(
        2, reposByName(project.id).size(), "import reuses the pre-served sibling, no duplicate");
    assertEdge(superRepo.id, "lib", served.id);
  }

  @Test
  public void prepareIsIdempotent() throws Exception {
    var project = projectService.create("Prepare Idempotent", null);
    var superRepo =
        repositoryService.cloneRepository(
            fixture("submodule-simple-super.git"), null, project, false);

    var first =
        repositoryService.prepareSubmoduleBackend(superRepo.id, fixture("submodule-shared.git"));
    var second =
        repositoryService.prepareSubmoduleBackend(superRepo.id, fixture("submodule-shared.git"));

    assertEquals(first.repositoryId(), second.repositoryId(), "second prepare reuses the sibling");
    assertEquals(2, reposByName(project.id).size(), "no duplicate sibling from re-preparing");
  }

  @Test
  public void prepareRejectsAQitsHostBackend() throws Exception {
    var project = projectService.create("Prepare Guard", null);
    var superRepo =
        repositoryService.cloneRepository(
            fixture("submodule-simple-super.git"), null, project, false);

    var ex =
        assertThrows(
            BadRequestException.class,
            () ->
                repositoryService.prepareSubmoduleBackend(
                    superRepo.id, "http://qits:8080/git/proj/qits-gateway"));
    assertTrue(ex.getMessage().contains("qits git host"), ex.getMessage());
  }

  @Test
  public void cloneRejectsAQitsHostUrl() throws Exception {
    var project = projectService.create("Self Clone Guard", null);
    var ex =
        assertThrows(
            BadRequestException.class,
            () ->
                repositoryService.cloneRepository(
                    "http://qits:8080/git/proj/thing", null, project, false));
    assertTrue(ex.getMessage().contains("qits git host"), ex.getMessage());
  }

  @Test
  public void importRejectsASubmoduleResolvingToTheQitsHost() throws Exception {
    // A superproject that committed the anti-pattern: an absolute qits-host submodule url. Import
    // must fail loudly rather than silently mirror qits' cache back onto itself as the sibling.
    var project = projectService.create("Import Guard", null);
    String gitmodules =
        "[submodule \"gw\"]\n\tpath = gw\n\turl = http://qits:8080/git/proj/qits-gateway\n";
    String superBare = bareRepoWithGitmodules(gitmodules);

    var ex =
        assertThrows(
            BadRequestException.class,
            () -> repositoryService.cloneRepository(superBare, null, project, true));
    assertTrue(ex.getMessage().contains("qits git host"), ex.getMessage());
  }

  /** A throwaway bare repo whose main branch commits the given {@code .gitmodules} content. */
  private String bareRepoWithGitmodules(String gitmodules) throws Exception {
    Path work = Files.createTempDirectory("qits-guard-super");
    git.exec(null, "git", "init", "-b", "main", work.toString());
    git.exec(work.toFile(), "git", "config", "user.email", "t@example.com");
    git.exec(work.toFile(), "git", "config", "user.name", "Test");
    Files.writeString(work.resolve(".gitmodules"), gitmodules);
    git.exec(work.toFile(), "git", "add", "-A");
    git.exec(work.toFile(), "git", "commit", "-m", "add gitmodules");
    Path bare = Files.createTempDirectory("qits-guard-bare").resolve("super.git");
    git.exec(null, "git", "clone", "--mirror", work.toString(), bare.toString());
    return bare.toString();
  }

  /** Pushes a new commit to the bare {@code upstream} and returns its sha. */
  private String advanceUpstream(Path upstream) throws Exception {
    Path worktree = Files.createTempDirectory("qits-upstream-worktree");
    git.exec(null, "git", "clone", upstream.toString(), worktree.toString());
    git.exec(worktree.toFile(), "git", "config", "user.email", "t@example.com");
    git.exec(worktree.toFile(), "git", "config", "user.name", "Test");
    Files.writeString(worktree.resolve("advanced.txt"), "advanced");
    git.exec(worktree.toFile(), "git", "add", "-A");
    git.exec(worktree.toFile(), "git", "commit", "-m", "advance upstream");
    git.exec(worktree.toFile(), "git", "push", "origin", "HEAD");
    return git.exec(worktree.toFile(), "git", "rev-parse", "HEAD").trim();
  }

  /** Commits a gitlink bump ({@code path} -> {@code sha}) to the bare super {@code upstream}. */
  private void bumpGitlink(Path upstream, String path, String sha) throws Exception {
    Path worktree = Files.createTempDirectory("qits-super-worktree");
    git.exec(null, "git", "clone", upstream.toString(), worktree.toString());
    git.exec(worktree.toFile(), "git", "config", "user.email", "t@example.com");
    git.exec(worktree.toFile(), "git", "config", "user.name", "Test");
    // The gitlink is bumped straight in the index — no need to materialize the submodule.
    git.exec(
        worktree.toFile(),
        "git",
        "update-index",
        "--add",
        "--cacheinfo",
        "160000," + sha + "," + path);
    git.exec(worktree.toFile(), "git", "commit", "-m", "bump gitlink");
    git.exec(worktree.toFile(), "git", "push", "origin", "HEAD");
  }

  /** Whether the qits-side origin of {@code repoId} contains {@code sha}. */
  private boolean originHasCommit(String repoId, String sha) throws Exception {
    return git.execAllowNonZero(
                Path.of(dataDir, repoId, "origin").toFile(),
                "git",
                "cat-file",
                "-e",
                sha + "^{commit}")
            .exitCode()
        == 0;
  }

  private void deleteRecursively(Path dir) throws Exception {
    try (var paths = Files.walk(dir)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
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

  private List<RepositorySubmodule> allEdges(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .flatMap(r -> submoduleRepository.findByParentId(r.id).stream())
        .collect(Collectors.toList());
  }
}
