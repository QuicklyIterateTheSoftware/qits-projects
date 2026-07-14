package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Host-side coverage of recursive submodule import (Commit 3). The container-side submodule
 * <em>checkout</em> is not faithful through {@code FakeContainerRuntime} (its clone-url rewrite
 * makes a relative {@code ../<childId>} resolve to a non-existent path) — that path is proven by
 * the real-docker {@code WorkspaceSubmoduleMaterializationIT}. Here we assert the rows/edges/dedup
 * the import produces from the committed {@code submodule-*.git} fixtures.
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
  @Inject WorkspaceRepository workspaceRepository;

  private String fixture(String name) throws Exception {
    return getClass().getResource("/fixtures/" + name).toURI().getPath();
  }

  /** The repositories in a project keyed by the trailing bare-repo name of their url. */
  private Map<String, Repository> reposByName(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .collect(Collectors.toMap(r -> Path.of(r.url).getFileName().toString(), r -> r));
  }

  @Test
  public void importsSubmodulesAsSiblingRepositoriesWithEdges() throws Exception {
    var project = projectService.create("Submodule Import", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project);

    // super -> child-a -> {shared, grandchild}; super -> shared. shared is the diamond (imported
    // once), so four distinct repositories total.
    Map<String, Repository> repos = reposByName(project.id);
    assertEquals(
        Set.of(
            "submodule-super.git",
            "submodule-child-a.git",
            "submodule-shared.git",
            "submodule-grandchild.git"),
        repos.keySet(),
        "each distinct submodule url is imported once as a sibling repository");

    // Edges: super->child-a, super->shared, child-a->shared, child-a->grandchild.
    assertEdge(superRepo.id, "child-a", repos.get("submodule-child-a.git").id);
    assertEdge(superRepo.id, "shared-direct", repos.get("submodule-shared.git").id);
    Repository childA = repos.get("submodule-child-a.git");
    assertEdge(childA.id, "shared", repos.get("submodule-shared.git").id);
    assertEdge(childA.id, "grandchild", repos.get("submodule-grandchild.git").id);

    // Diamond dedup: the shared child has exactly two edges pointing at it (from super + child-a).
    String sharedId = repos.get("submodule-shared.git").id;
    long edgesToShared =
        allEdges(project.id).stream().filter(e -> e.child.id.equals(sharedId)).count();
    assertEquals(2, edgesToShared, "the diamond child is imported once but linked twice");
  }

  @Test
  public void importedChildrenHaveNoMainWorkspaceButSuperprojectDoes() throws Exception {
    var project = projectService.create("Submodule Workspaces", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project);

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
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, projectA);
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, projectB);

    Repository sharedInA = reposByName(projectA.id).get("submodule-shared.git");
    Repository sharedInB = reposByName(projectB.id).get("submodule-shared.git");
    assertNotEquals(
        sharedInA.id, sharedInB.id, "two projects get independent mirrors of the same submodule");
  }

  @Test
  public void cyclicSubmodulesTerminate() throws Exception {
    var project = projectService.create("Cycle", null);
    // cycle-a -> cycle-b -> cycle-a: dedup + the visited guard must terminate the import.
    var cycleA = repositoryService.cloneRepository(fixture("submodule-cycle-a.git"), null, project);

    Map<String, Repository> repos = reposByName(project.id);
    assertEquals(
        Set.of("submodule-cycle-a.git", "submodule-cycle-b.git"),
        repos.keySet(),
        "the two-node cycle imports each node once and stops");
    assertNotEquals(cycleA.id, repos.get("submodule-cycle-b.git").id);
  }

  @Test
  public void plainRepositoryImportsWithZeroEdges() throws Exception {
    // No-op regression: a submodule-free repo produces no edges and no extra sibling repositories.
    var project = projectService.create("No Submodules", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);

    assertTrue(submoduleRepository.findByParentId(repo.id).isEmpty(), "no submodule edges");
    assertEquals(1, reposByName(project.id).size(), "only the single imported repository exists");
  }

  @Test
  public void projectDeleteRemovesSuperprojectChildrenAndEdges() throws Exception {
    var project = projectService.create("Submodule Delete", null);
    repositoryService.cloneRepository(fixture("submodule-super.git"), null, project);
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
