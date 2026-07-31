package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceLifecycle;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class RepositoryServiceTest {

  @Inject RepositoryService repositoryService;

  @Inject ProjectService projectService;

  @Inject RecordingWorkspaceLifecycle workspaceLifecycle;

  @Inject MetadataService metadataService;

  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @Test
  public void testClone() throws Exception {
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Clone Project", null);
    System.out.println("FIXTURE URL: " + fixtureUrl);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    System.out.println("CLONED: " + repo.id);
  }

  @Test
  public void deleteRepositoryRemovesOnDiskDataAndReleasesWorkspaces() throws Exception {
    // Regression: deleting a repository (directly or via a project/seed reset) must tear down its
    // on-disk clone, not just the DB row — otherwise re-seeds accumulate orphaned data. See
    // RepositoryService.deleteInternal / ProjectService.delete.
    //
    // SEAM (migration-plan.md §6): the monorepo also asserted here that the repository's workspace
    // CONTAINERS were gone, through ContainerRuntime. Containers are qits-workspaces', so what this
    // context can still prove is that it asked — the WorkspaceLifecycle.releaseRepository call that
    // replaced the inline docker teardown, inside the delete and before the row goes.
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Delete Cleanup Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);

    Path repoDir = Path.of(dataDir, repo.id);
    assertTrue(Files.exists(repoDir), "clone dir should exist before delete");

    // Delete via the aggregate root (project) — the path a seed reset takes.
    projectService.delete(project.id);

    assertFalse(Files.exists(repoDir), "clone dir should be removed after delete");
    assertTrue(
        workspaceLifecycle.releasedRepository(repo.id),
        "the workspaces context is asked to release the repository before its row goes");
    assertThrows(NotFoundException.class, () -> repositoryService.get(repo.id));
  }

  // SEAM: deleteRepositoryCascadesCommandAgentSessionRows is NOT carried over. It was the
  // regression test for docs/issues/2026-07-10_project-delete-fails-on-command-agent-session-fk.md
  // and asserted that `command` / `command_agent_session` rows cascade off the repository delete.
  // Those tables are daemon-commands' (migration-plan.md §3.3/§7) and are not in this context's
  // database, so there is no cascade here to test. It is UNOWNED as of this extraction and needs a
  // home in qits-workspace-daemon.

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

  /**
   * Stands in for the platform's own git host: a bare origin on the shared volume that this service
   * did not create, named by hand rather than by a UUID — exactly what {@code git init --bare -b
   * main /repos/qits-<name>/origin} leaves behind.
   */
  private Path seedBareOrigin(String repoId) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    Files.createDirectories(originPath.getParent());
    git.exec(null, "git", "init", "-q", "--bare", "-b", "main", originPath.toString());
    return originPath;
  }

  @Test
  public void adoptingAnExistingOriginKeysTheRowOnTheDirectoryName() throws Exception {
    // The whole point: CiRun.repoId, cd's applications and the git host route all carry the
    // directory name, so a row that attributes any of them must carry it as its id.
    seedBareOrigin("qits-adopt-me");
    var project = projectService.create("Adoption Project", null);

    var repo =
        repositoryService.adoptExistingOrigin(
            project,
            "qits-adopt-me",
            "https://github.com/QuicklyIterateTheSoftware/qits-adopt-me.git",
            RepositoryArchetype.SERVICE);

    assertEquals("qits-adopt-me", repo.id, "the id is the directory name, not a fresh UUID");
    assertEquals(
        "https://github.com/QuicklyIterateTheSoftware/qits-adopt-me.git",
        repo.url,
        "the forge repository backing it is declared");
    assertEquals(RepositoryArchetype.SERVICE, repo.archetype);
    assertEquals("main", repo.mainBranch, "read from the origin's HEAD, not assumed");
    assertEquals(project.id, repo.project.id);
    // The sidecar is what repository discovery restores url/archetype from on the next boot;
    // without it the adoption would silently blank both.
    var metadata = metadataService.readRepositoryMetadata("qits-adopt-me").orElseThrow();
    assertEquals(repo.url, metadata.url);
    assertEquals(RepositoryArchetype.SERVICE, metadata.archetype);
  }

  @Test
  public void adoptingIsIdempotentAndNeverModifiesTheRowItFinds() throws Exception {
    seedBareOrigin("qits-adopt-twice");
    var project = projectService.create("Adoption Idempotence Project", null);
    var first =
        repositoryService.adoptExistingOrigin(
            project,
            "qits-adopt-twice",
            "https://example.com/first.git",
            RepositoryArchetype.LIBRARY);

    var second =
        repositoryService.adoptExistingOrigin(
            project,
            "qits-adopt-twice",
            "https://example.com/second.git",
            RepositoryArchetype.APPLICATION);

    assertEquals(first.id, second.id, "the same row, matched by id");
    assertEquals(
        "https://example.com/first.git", second.url, "a re-run rewrites nothing it did not create");
    assertEquals(RepositoryArchetype.LIBRARY, second.archetype);
  }

  @Test
  public void adoptionRegistersNothingItCannotServe() throws Exception {
    var project = projectService.create("Adoption Guard Project", null);

    // No origin on the volume: a row here would name a repository the git host answers 404 for.
    assertFalse(repositoryService.hasExistingOrigin("qits-never-seeded"));
    assertThrows(
        NotFoundException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project, "qits-never-seeded", "https://example.com/x.git", null));

    // The id becomes a path segment under the data dir and a git-host route segment.
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project, "../escape", "https://example.com/x.git", null));
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project, "-flag", "https://example.com/x.git", null));

    // The wrapper archetype has exactly one seam, and this is not it.
    seedBareOrigin("qits-adopt-wrapper");
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project,
                "qits-adopt-wrapper",
                "https://example.com/x.git",
                RepositoryArchetype.PROJECT));
  }
}
