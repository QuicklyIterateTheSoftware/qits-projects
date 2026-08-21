package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
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
import java.util.UUID;
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

  @Inject GitExecutor git;

  @Inject RepositoryNameRepository repositoryNameRepository;

  /**
   * The two coordinates a creation path writes: an opaque minted id on the row (which is also the
   * storage id qits-githost holds the bare under) and the url basename as the addressable name,
   * resolvable through the alias table and nowhere else.
   */
  @Test
  public void testClone() throws Exception {
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Clone Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);

    assertNotEquals("testing-repo", repo.id, "the id is opaque, never the addressable name");
    assertEquals(
        repo.id, UUID.fromString(repo.id).toString(), "the id is a minted UUID");
    assertEquals(
        "testing-repo",
        repositoryNameRepository.nameFor(repo).orElseThrow(),
        "the url basename is registered as the addressable name, in the same transaction");
    assertEquals(
        repo.id,
        repositoryService.findByProjectAndName(project.id, "testing-repo").orElseThrow().id,
        "and the alias table is what resolves it");
  }

  /**
   * A name addresses exactly one repository <b>per project</b>. Twice in the same project is a real
   * collision and fails; the same name in another project is not a collision at all — the global
   * one was the defect the project-scoped alias removed.
   */
  @Test
  public void aTakenNameFailsTheCreateButOnlyWithinItsProject() throws Exception {
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Collision Project", null);
    var first = repositoryService.cloneRepository(fixtureUrl, null, project);

    var sameProject =
        assertThrows(
            BadRequestException.class,
            () -> repositoryService.cloneRepository(fixtureUrl, null, project));
    assertTrue(sameProject.getMessage().contains("testing-repo"), sameProject.getMessage());

    var otherProject = projectService.create("Other Collision Project", null);
    var second = repositoryService.cloneRepository(fixtureUrl, null, otherProject);

    assertNotEquals(first.id, second.id, "two rows, two minted ids");
    assertEquals(
        "testing-repo",
        repositoryNameRepository.nameFor(second).orElseThrow(),
        "and both answer to the same name, each within its own project");
    assertEquals(
        second.id,
        repositoryService.findByProjectAndName(otherProject.id, "testing-repo").orElseThrow().id);
    assertEquals(
        first.id,
        repositoryService.findByProjectAndName(project.id, "testing-repo").orElseThrow().id);
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
   * create, under whatever storage id the bootstrap's {@code git init --bare -b main} put it at.
   */
  private void seedGitHostOrigin(String repoId) {
    gitHostRepositories.ensure(repoId, "main");
  }

  /**
   * Adoption takes the two coordinates separately: the row keeps the <b>storage id</b> the host
   * already serves the bare under (so nothing that already refers to that bare is disturbed), and
   * the <b>name</b> becomes its alias — which is the only thing that resolves it.
   */
  @Test
  public void adoptingAnExistingOriginKeepsTheStorageIdAndRegistersTheName() throws Exception {
    String storageId = UUID.randomUUID().toString();
    seedGitHostOrigin(storageId);
    var project = projectService.create("Adoption Project", null);

    var repo =
        repositoryService.adoptExistingOrigin(
            project,
            storageId,
            "qits-adopt-me",
            "https://github.com/QuicklyIterateTheSoftware/qits-adopt-me.git",
            RepositoryArchetype.SERVICE);

    assertEquals(storageId, repo.id, "the row takes the id the host stores it under");
    assertEquals(
        "qits-adopt-me",
        repositoryNameRepository.nameFor(repo).orElseThrow(),
        "the addressable name is registered in the same transaction as the row");
    assertEquals(
        repo.id,
        repositoryService.findByProjectAndName(project.id, "qits-adopt-me").orElseThrow().id);
    assertEquals(
        "https://github.com/QuicklyIterateTheSoftware/qits-adopt-me.git",
        repo.url,
        "the forge repository backing it is declared");
    assertEquals(RepositoryArchetype.SERVICE, repo.archetype);
    assertEquals("main", repo.mainBranch, "read from what the git host reports, not assumed");
    assertEquals(project.id, repo.project.id);
  }

  /**
   * A host seeded before this service existed stores a bare under its name. That is still a valid
   * opaque id, so adoption takes it as one — the two arguments simply coincide.
   */
  @Test
  public void aNameKeyedHostIsAdoptedWithTheNameAsItsStorageId() throws Exception {
    seedGitHostOrigin("qits-legacy-keyed");
    var project = projectService.create("Legacy Keyed Project", null);

    var repo =
        repositoryService.adoptExistingOrigin(
            project,
            "qits-legacy-keyed",
            "qits-legacy-keyed",
            "https://example.com/qits-legacy-keyed.git",
            RepositoryArchetype.SERVICE);

    assertEquals("qits-legacy-keyed", repo.id);
    assertEquals(
        repo.id,
        repositoryService.findByProjectAndName(project.id, "qits-legacy-keyed").orElseThrow().id,
        "and it resolves through the alias table like every other row, not by reading the id");
  }

  @Test
  public void adoptingIsIdempotentAndNeverModifiesTheRowItFinds() throws Exception {
    String storageId = UUID.randomUUID().toString();
    seedGitHostOrigin(storageId);
    var project = projectService.create("Adoption Idempotence Project", null);
    var first =
        repositoryService.adoptExistingOrigin(
            project,
            storageId,
            "qits-adopt-twice",
            "https://example.com/first.git",
            RepositoryArchetype.LIBRARY);

    var second =
        repositoryService.adoptExistingOrigin(
            project,
            storageId,
            "qits-adopt-twice-again",
            "https://example.com/second.git",
            RepositoryArchetype.FRONTEND);

    assertEquals(first.id, second.id, "the same row, matched by the storage id");
    assertEquals(
        "https://example.com/first.git", second.url, "a re-run rewrites nothing it did not create");
    assertEquals(RepositoryArchetype.LIBRARY, second.archetype);
    assertTrue(
        repositoryService.findByProjectAndName(project.id, "qits-adopt-twice-again").isEmpty(),
        "and it registers no second alias either");
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
                project,
                "qits-never-seeded",
                "qits-never-seeded",
                "https://example.com/x.git",
                null));

    // Both coordinates are git-host route segments.
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project, "../escape", "escape", "https://example.com/x.git", null));
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project, "-flag", "flag", "https://example.com/x.git", null));
    String served = UUID.randomUUID().toString();
    seedGitHostOrigin(served);
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project, served, "../escape", "https://example.com/x.git", null));

    // The wrapper archetype has exactly one seam, and this is not it.
    seedGitHostOrigin("qits-adopt-wrapper");
    assertThrows(
        BadRequestException.class,
        () ->
            repositoryService.adoptExistingOrigin(
                project,
                "qits-adopt-wrapper",
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

  /**
   * projects-volume-decoupling-plan.md §3.7: a branch delete is a push, so it passes through the git
   * host's ref-protection hook — and the hook's refusal is a statement about the request, not a
   * fault here. It has to surface as a 4xx carrying the hook's own words, never as a 500.
   *
   * <p>Stood up with a plain {@code pre-receive} that refuses every update, which is as far as the
   * fake host goes: native git renders a shell hook's decline as {@code ! [remote rejected] <ref>
   * (pre-receive hook declined)} — its own fixed wording, not the script's stderr. A hook that puts
   * its own sentence in those parentheses is qits-githost's JGit {@code ProtectedRefHook}, and
   * proving that text is its test suite's job; what this pins is the mapping.
   */
  @Test
  public void aBranchDeleteTheHostRefusesSurfacesAsA4xx() throws Exception {
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Protected Delete Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);

    Path hook = Path.of(gitHost.fetchUrl(repo.id)).resolve("hooks/pre-receive");
    Files.writeString(hook, "#!/bin/sh\nexit 1\n");
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));

    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () -> repositoryService.deleteBranch(repo.id, repo.mainBranch));
    assertTrue(
        refused.getMessage().contains("pre-receive hook declined"),
        "the hook's own words reach the caller: " + refused.getMessage());
    assertTrue(refused.getMessage().contains(repo.mainBranch), refused.getMessage());
  }

  /**
   * projects-volume-decoupling-plan.md's regression: a pull's push to the git host can hit the same
   * {@code ProtectedRefHook} refusal a branch delete does, and with no token configured (the shipped
   * default — {@code qits.repositories.git.push-token} unset) that refusal must reach the caller as
   * a 4xx, never as a silently-logged 500 the streamed API's 200 then buries. Proved on the
   * synchronous {@code pullRepository} overload, which throws in-request; {@link
   * RepositoryPullProcessTest} proves the streamed variant surfaces the same message in its stream
   * rather than only in a log line.
   */
  @Test
  public void aTokenlessPullAgainstAProtectedDefaultBranchSurfacesTheRefusalNonSilently()
      throws Exception {
    String fixtureUrl = GitFixtures.path("testing-repo.git");
    var project = projectService.create("Protected Pull No Token", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    fakeGitHostRepositories.protectDefaultBranch(
        repo.id, "a-token-this-deployment-never-configures");

    // Rewind the HOST's branch so the pull must fast-forward the protected ref — the exact update
    // ProtectedRefHook refuses without a matching token.
    Path hostDir = Path.of(gitHost.fetchUrl(repo.id));
    String parentSha = git.exec(hostDir.toFile(), "git", "rev-parse", repo.mainBranch + "~1").trim();
    git.exec(hostDir.toFile(), "git", "update-ref", "refs/heads/" + repo.mainBranch, parentSha);

    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> repositoryService.pullRepository(repo.id));
    assertTrue(
        refused.getMessage().contains("the git host refused the push"),
        "the refusal is classified, not just re-thrown output: " + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("declined"),
        "the hook's own words (as far as a shell hook can carry them) reach the caller: "
            + refused.getMessage());
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
