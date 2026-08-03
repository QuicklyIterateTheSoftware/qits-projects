package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceLifecycle;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class RepositoryServiceTest {

  @Inject RepositoryService repositoryService;

  @Inject CommitService commitService;

  @Inject ProjectService projectService;

  @Inject RecordingWorkspaceLifecycle workspaceLifecycle;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitHostAddress gitHost;

  @Inject GitHostRepositories gitHostRepositories;

  @Inject FakeGitHostRepositories fakeGitHostRepositories;

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

    Path mirrorDir = gitMirrors.of(repo.id).gitDir();
    assertTrue(Files.exists(mirrorDir), "mirror should exist before delete");

    // Delete via the aggregate root (project) — the path a seed reset takes.
    projectService.delete(project.id);

    assertFalse(Files.exists(mirrorDir), "the local mirror should be removed after delete (⚖2 — the"
        + " git host's own copy is untouched, there is just no delete verb for it to reach)");
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

  /**
   * projects-volume-decoupling-plan.md §2.4, §3.3 step 6, ⚖3: importing an upstream's whole history
   * publishes it in one push carrying {@code -o qits.no-ci}, so a pipeline config already committed
   * upstream does not fire one CI run per branch against history that predates the platform.
   */
  @Test
  public void cloneRepositoryPublishesTheImportWithNoCiSuppressed() throws Exception {
    var project = projectService.create("No CI Import", null);
    var repo = repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);

    assertTrue(
        fakeGitHostRepositories.lastPushOptions(repo.id).contains("qits.no-ci"),
        "the import path's publish push must carry -o qits.no-ci");
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

  /**
   * Stands in for the platform's own git host already holding a repository this service did not
   * create, keyed by an id chosen by hand rather than a fresh UUID — exactly what the bootstrap's
   * {@code git init --bare -b main} leaves for the platform's own repositories.
   */
  private void seedGitHostOrigin(String repoId) {
    gitHostRepositories.ensure(repoId, "main");
  }

  @Test
  public void adoptingAnExistingOriginKeysTheRowOnTheDirectoryName() throws Exception {
    // The whole point: CiRun.repoId, cd's applications and the git host route all carry the
    // directory name, so a row that attributes any of them must carry it as its id.
    seedGitHostOrigin("qits-adopt-me");
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
    assertEquals("main", repo.mainBranch, "read from what the git host reports, not assumed");
    assertEquals(project.id, repo.project.id);
  }

  @Test
  public void adoptingIsIdempotentAndNeverModifiesTheRowItFinds() throws Exception {
    seedGitHostOrigin("qits-adopt-twice");
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

    // Not on the git host: a row here would name a repository it answers 404 for.
    assertFalse(repositoryService.hasExistingOrigin("qits-never-seeded"));
    assertThrows(
        NotFoundException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project, "qits-never-seeded", "https://example.com/x.git", null));

    // The id becomes a git-host route segment.
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
    seedGitHostOrigin("qits-adopt-wrapper");
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project,
                "qits-adopt-wrapper",
                "https://example.com/x.git",
                RepositoryArchetype.PROJECT));
  }

  /**
   * projects-volume-decoupling-plan.md §3.5, BR: every read that used to assume a warm mirror now
   * goes through {@code requireMirror}, which clones a cold one from the git host before reading it
   * — deleting the on-disk mirror (as if it had been evicted, §4 item 8) must not surface as an
   * error, only as one extra clone.
   */
  @Test
  public void aColdMirrorIsClonedOnFirstRead() throws Exception {
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Cold Mirror Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);

    Path mirrorDir = gitMirrors.of(repo.id).gitDir();
    assertTrue(Files.isDirectory(mirrorDir), "the mirror is cloned as part of creation");
    deleteRecursively(mirrorDir);
    assertFalse(Files.exists(mirrorDir), "the mirror is now cold, as if evicted from disk");

    var log = commitService.listCommits(repo.id, repo.mainBranch);

    assertEquals(3, log.commits().size(), "the re-cloned mirror still holds the fixture's history");
    assertTrue(Files.isDirectory(mirrorDir), "the read re-cloned the mirror from the git host");
  }

  /**
   * The row check {@code requireMirror} opens with is unchanged: an id with no repository row never
   * reaches the git host at all.
   */
  @Test
  public void requireMirrorStill404sForAnUnknownRepository() {
    assertThrows(
        NotFoundException.class, () -> commitService.listCommits("does-not-exist", "main"));
    assertThrows(NotFoundException.class, () -> repositoryService.syncStatus("does-not-exist"));
  }

  /**
   * projects-volume-decoupling-plan.md §3.5: a refresh failure is ambiguous between "no such
   * repository on the host" and "the host is unreachable", so {@code requireMirror} asks {@link
   * GitHostRepositories#find} to tell them apart — present (the host answers, but a clone still
   * fails) is a 500, distinct from the absent case covered by {@code adoptionRegistersNothingItCannotServe}.
   *
   * <p>Simulated by revoking read access to the host bare's {@code objects/} directory: {@code git
   * symbolic-ref --short HEAD} (what {@code find} asks) reads only the sibling {@code HEAD} file and
   * still succeeds, while {@code git clone --mirror} needs the objects and fails.
   */
  @Test
  public void requireMirrorReports500WhenTheHostIsUnreachable() throws Exception {
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Unreachable Host Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    deleteRecursively(gitMirrors.of(repo.id).gitDir()); // cold, same as the test above

    Path objectsDir = Path.of(gitHost.fetchUrl(repo.id)).resolve("objects");
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(objectsDir);
    Files.setPosixFilePermissions(objectsDir, PosixFilePermissions.fromString("---------"));
    try {
      assertThrows(
          InternalServerErrorException.class,
          () -> commitService.listCommits(repo.id, repo.mainBranch));
      assertTrue(
          gitHostRepositories.find(repo.id).isPresent(),
          "the host still answers for this id — that is what makes the failure a 500, not a 404");
    } finally {
      Files.setPosixFilePermissions(objectsDir, original);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
