package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RepositoryServiceTest.TestProfile.class)
public class RepositoryServiceTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject RepositoryService repositoryService;

  @Inject ProjectService projectService;

  @Inject WorkspaceService workspaceService;

  @Inject ContainerRuntime containerRuntime;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @Test
  public void testClone() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Clone Project", null);
    System.out.println("FIXTURE URL: " + fixtureUrl);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    System.out.println("CLONED: " + repo.id);
  }

  @Test
  public void deleteRepositoryRemovesContainersAndOnDiskData() throws Exception {
    // Regression: deleting a repository (directly or via a project/seed reset) must tear down its
    // workspace containers and on-disk clone, not just the DB row — otherwise re-seeds accumulate
    // orphaned data. See RepositoryService.delete / ProjectService.delete.
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Delete Cleanup Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");

    Path repoDir = Path.of(dataDir, repo.id);
    assertTrue(Files.exists(repoDir), "clone dir should exist before delete");
    assertFalse(
        containerRuntime.listWorkspaceContainers(repo.id).isEmpty(),
        "workspace container should exist before delete");

    // Delete via the aggregate root (project) — the path a seed reset takes.
    projectService.delete(project.id);

    assertFalse(Files.exists(repoDir), "clone dir should be removed after delete");
    assertTrue(
        containerRuntime.listWorkspaceContainers(repo.id).isEmpty(),
        "containers should be removed after delete");
    assertThrows(NotFoundException.class, () -> repositoryService.get(repo.id));
  }

  @Test
  public void testCloneRejectsDangerousUrls() {
    var project = projectService.create("Reject Project", null);
    // ext:: transport can run arbitrary commands; a dash-leading value smuggles a git flag.
    assertThrows(
        BadRequestException.class,
        () -> repositoryService.cloneRepository("ext::sh -c id", null, project));
    assertThrows(
        BadRequestException.class,
        () -> repositoryService.cloneRepository("--upload-pack=touch pwned", null, project));
  }
}
