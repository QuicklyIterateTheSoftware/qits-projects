package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.entity.AgentSessionRef;
import eu.wohlben.qits.domain.command.entity.AgentSessionSource;
import eu.wohlben.qits.domain.command.entity.Command;
import eu.wohlben.qits.domain.command.entity.CommandKind;
import eu.wohlben.qits.domain.command.entity.CommandStatus;
import eu.wohlben.qits.domain.command.persistence.CommandRepository;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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

  @Inject WorkspaceRepository workspaceRepository;

  @Inject CommandRepository commandRepository;

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
    // Creation is lazy; provision so the delete below has a live container to tear down.
    workspaceService.ensureContainer(repo.id, "work");

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

  /** Seeds an exited agent command with one persisted session ref in the given workspace. */
  @Transactional
  String seedCommandWithAgentSession(String repoId, String workspaceId) {
    var workspace =
        workspaceRepository.findActiveByRepositoryAndWorkspaceId(repoId, workspaceId).orElseThrow();
    Command command =
        Command.builder()
            .id(UUID.randomUUID().toString())
            .workspace(workspace)
            .branch(workspace.branch)
            .commitHash("abcdef1234567890")
            .actionName("Claude Code")
            .executeScript("exec claude")
            .interactive(false)
            .kind(CommandKind.CHAT)
            .status(CommandStatus.EXITED)
            .build();
    command.agentSessions.add(
        new AgentSessionRef(
            UUID.randomUUID().toString(), AgentSessionSource.PINNED, null, null, Instant.now()));
    commandRepository.persist(command);
    return command.id;
  }

  @Test
  public void deleteRepositoryCascadesCommandAgentSessionRows() throws Exception {
    // Regression for docs/issues/2026-07-10_project-delete-fails-on-command-agent-session-fk.md:
    // command rows are removed by the DB-level cascade off the repository delete (not the entity
    // manager), so the command_agent_session FK must cascade too (V32) — before that migration this
    // delete failed with a referential-integrity violation once the workspace agent had been used.
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Agent Session Delete Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    String commandId = seedCommandWithAgentSession(repo.id, "work");

    projectService.delete(project.id);

    assertTrue(
        commandRepository.findByIdOptional(commandId).isEmpty(),
        "command (and its agent session rows) should cascade off the repository delete");
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
