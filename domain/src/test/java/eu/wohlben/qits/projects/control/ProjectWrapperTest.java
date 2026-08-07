package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceLifecycle;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The project wrapper repository: every project ends creation with exactly one {@code PROJECT}
 * repository named {@code <slug>-<slug>}, seeded with the project template skeleton when its main
 * branch has no commit.
 *
 * <p>Deliberately a plain {@code @QuarkusTest} with no {@code @TestProfile}, so it shares the
 * suite's single application rather than forcing its own (see {@code RepoDataDirReset}).
 */
@QuarkusTest
public class ProjectWrapperTest {

  /** Every path the project template commits, in git's sort order. */
  private static final List<String> SKELETON =
      List.of(
          ".gitignore",
          ".qits-config.yml",
          "AGENTS.md",
          "CLAUDE.md",
          "README.md",
          "cli/README.md",
          "daemons/README.md",
          "frontends/README.md",
          "images/README.md",
          "libs/README.md",
          "services/README.md");

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject RepositoryNameRepository repositoryNameRepository;
  @Inject RecordingWorkspaceLifecycle workspaceLifecycle;
  @Inject GitExecutor git;
  @Inject GitMirrorRegistry gitMirrors;
  @Inject FakeGitHostRepositories fakeGitHostRepositories;

  private Path originOf(String repoId) {
    return gitMirrors.of(repoId).gitDir();
  }

  private String gitIn(String repoId, String... args) throws Exception {
    return git.exec(originOf(repoId).toFile(), args).trim();
  }

  private String fixture(String name) throws Exception {
    return GitFixtures.path(name);
  }

  /** {@link #fixture} without the checked exception, for use inside lambdas. */
  private String fixtureUrl(String name) {
    try {
      return fixture(name);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Repository wrapperOf(Project project) {
    return projectService.findWrapper(project.id).orElseThrow();
  }

  // ---------------------------------------------------------------- greenfield

  @Test
  public void creationEndsWithAWrapperNamedSlugSlug() {
    var project = projectService.create("Wrapper Naming", "wrapper-naming", null);
    var wrapper = wrapperOf(project);

    assertEquals(RepositoryArchetype.PROJECT, wrapper.archetype);
    assertNull(wrapper.url, "a greenfield wrapper has no backup remote until one is attached");
    assertEquals("main", wrapper.mainBranch);
    assertEquals(
        "wrapper-naming-wrapper-naming",
        repositoryNameRepository.nameFor(wrapper).orElseThrow(),
        "the wrapper is addressable as <slug>-<slug>, which is what makes a committed relative"
            + " submodule url resolve the same locally and at the forge");
  }

  /** An unborn main branch would break a workspace container's clone — the skeleton prevents it. */
  @Test
  public void theWrapperIsImmediatelyWorkable() throws Exception {
    var project = projectService.create("Workable", "workable", null);
    var wrapper = wrapperOf(project);

    assertNotNull(gitIn(wrapper.id, "git", "rev-parse", "--verify", "refs/heads/main"));
    // SEAM (migration-plan.md §6): the `workspace` table is qits-workspaces'. Re-expressed against
    // the seam — this context asks for the wrapper's main workspace on creation.
    assertTrue(
        workspaceLifecycle.createdMainWorkspaceFor(wrapper.id),
        "the wrapper starts with a workspace on its main branch");
  }

  @Test
  public void theWrapperIsSeededWithTheProjectTemplateSkeleton() throws Exception {
    var project = projectService.create("Skeleton", "skeleton", null);
    var wrapper = wrapperOf(project);

    List<String> paths =
        gitIn(wrapper.id, "git", "ls-tree", "-r", "--name-only", "main").lines().sorted().toList();
    assertEquals(SKELETON, paths);
  }

  /**
   * {@code CLAUDE.md} must be a real git symlink, not a file containing the word. The explicit mode
   * is the whole reason the seeding path uses {@code update-index --cacheinfo}.
   */
  @Test
  public void claudeMdIsCommittedAsASymlinkToAgentsMd() throws Exception {
    var project = projectService.create("Symlink", "symlink", null);
    var wrapper = wrapperOf(project);

    String entry = gitIn(wrapper.id, "git", "ls-tree", "main", "CLAUDE.md");
    assertTrue(entry.startsWith("120000 blob"), "expected a symlink entry, got: " + entry);
    assertEquals(
        "AGENTS.md",
        gitIn(wrapper.id, "git", "show", "main:CLAUDE.md"),
        "a trailing newline here would make the link target dangling in every checkout");
  }

  /** The skeleton commit is a root commit — there is no history to attach it to. */
  @Test
  public void theSkeletonCommitIsARootCommit() throws Exception {
    var project = projectService.create("Root Commit", "root-commit", null);
    var wrapper = wrapperOf(project);

    assertEquals(
        "",
        gitIn(wrapper.id, "git", "rev-list", "--max-parents=0", "--skip=1", "main"),
        "exactly one commit with no parents");
  }

  @Test
  public void theWrapperCannotBeDeletedOnItsOwn() {
    var project = projectService.create("Undeletable", "undeletable", null);
    var wrapper = wrapperOf(project);

    var error = assertThrows(BadRequestException.class, () -> repositoryService.delete(wrapper.id));
    assertTrue(error.getMessage().contains("wrapper"), error.getMessage());
  }

  @Test
  public void aProjectHasAtMostOneWrapper() {
    var project = projectService.create("Only One", "only-one", null);

    assertThrows(
        BadRequestException.class,
        () ->
            projectService.createRepositoryUnderProject(
                project.id, "https://example.com/x.git", RepositoryArchetype.PROJECT),
        "PROJECT is rejected at the ordinary repositories path");
  }

  // ---------------------------------------------------------------- slugs

  @Test
  public void theSlugIsDerivedFromTheNameWhenNotSupplied() {
    assertEquals(
        "quarkus-angular-demo", projectService.create("Quarkus + Angular Demo", null).slug);
    assertEquals("demo-project", projectService.create("Demo Project", null).slug);
  }

  /** A name with nothing alphanumeric in it still has to produce a valid slug. */
  @Test
  public void anUnslugifiableNameFallsBackToTheProjectId() {
    var project = projectService.create("***", null);

    assertTrue(project.slug.startsWith("project-"), project.slug);
    assertEquals(project.slug, "project-" + project.id.substring(0, 8));
  }

  /**
   * Regression: the format allows 1-40 characters. An earlier draft of the pattern required at
   * least two, which rejected a slug {@code slugify} legitimately produces.
   */
  @Test
  public void aSingleCharacterSlugIsAccepted() {
    assertEquals("x", projectService.create("X", null).slug);
    assertEquals("q", projectService.create("Explicit", "q", null).slug);
  }

  @Test
  public void anInvalidExplicitSlugIsRejected() {
    for (String bad :
        List.of("Upper", "-leading", "trailing-", "has space", "dot.ted", "sl/ash", "wrap.git")) {
      assertThrows(
          BadRequestException.class,
          () -> projectService.create("Bad Slug", bad, null),
          "expected '" + bad + "' to be rejected");
    }
    assertThrows(
        BadRequestException.class, () -> projectService.create("Too Long", "a".repeat(41), null));
  }

  /** The slug is the wrapper's identity, so it is not editable — renaming leaves it alone. */
  @Test
  public void renamingAProjectLeavesTheSlugAlone() {
    var project = projectService.create("Before", "before", null);

    projectService.update(project.id, "After", null);

    assertEquals("before", projectService.get(project.id).slug);
    assertEquals(
        "before-before", repositoryNameRepository.nameFor(wrapperOf(project)).orElseThrow());
  }

  // ---------------------------------------------------------------- adopt

  @Test
  public void adoptingAnEmptyUpstreamSeedsTheSkeletonOnMain() throws Exception {
    var project = projectService.create("Empty Upstream", "empty", null, fixture("empty-empty.git"));
    var wrapper = wrapperOf(project);

    assertEquals(fixture("empty-empty.git"), wrapper.url);
    assertEquals("main", wrapper.mainBranch);
    assertEquals(
        SKELETON,
        gitIn(wrapper.id, "git", "ls-tree", "-r", "--name-only", "main").lines().sorted().toList());
  }

  @Test
  public void adoptingANonEmptyUpstreamLeavesItsHistoryUntouched() throws Exception {
    var project = projectService.create("Demo", "demo", null, fixture("demo-demo.git"));
    var wrapper = wrapperOf(project);

    List<String> paths =
        gitIn(wrapper.id, "git", "ls-tree", "-r", "--name-only", wrapper.mainBranch)
            .lines()
            .toList();
    assertTrue(paths.contains("hello.txt"), "the fixture's own content survives: " + paths);
    assertFalse(paths.contains("AGENTS.md"), "the skeleton must not be layered onto real history");
  }

  @Test
  public void adoptingAUrlWhoseBasenameIsNotSlugSlugIsRejected() throws Exception {
    var error =
        assertThrows(
            BadRequestException.class,
            () -> projectService.create("Mismatch", "qits", null, fixture("testing-repo.git")));
    assertTrue(error.getMessage().contains("testing-repo"), error.getMessage());
    assertTrue(error.getMessage().contains("qits-qits"), error.getMessage());
  }

  /** State 4: a project created greenfield later gains the upstream the manifest names. */
  @Test
  public void adoptAttachesTheBackupRemoteToAGreenfieldWrapper() throws Exception {
    var project = projectService.create("Attach", "qits", null);
    var wrapper = wrapperOf(project);
    assertNull(wrapper.url);

    var adopted = projectService.adoptWrapperRepository(project.id, fixture("qits-qits.git"));

    assertEquals(wrapper.id, adopted.id, "the same repository, not a second one");
    assertEquals(
        fixture("qits-qits.git"),
        adopted.url,
        "attaching a backup remote is a row write (§3.4) — every remote-touching operation takes"
            + " the url explicitly rather than reading a configured remote out of the bare");
  }

  /** State 3: the steady state on every later boot. */
  @Test
  public void adoptIsANoOpOnceTheWrapperAlreadyHasThatUrl() throws Exception {
    var project = projectService.create("Idempotent", "qits", null, fixture("qits-qits.git"));
    var first = wrapperOf(project);

    var second = projectService.adoptWrapperRepository(project.id, fixture("qits-qits.git"));

    assertEquals(first.id, second.id);
    assertEquals(1, projectService.getRepositories(project.id).size());
  }

  /**
   * State 2: someone registered the url by hand first; adoption promotes it rather than cloning.
   */
  @Test
  public void adoptPromotesARepositoryAlreadyRegisteredAtThatUrl() throws Exception {
    // A project whose slug matches the fixture basename, with the wrapper deleted so the url is
    // free to be registered as an ordinary repository first.
    var project = projectService.create("Promote", "demo", null);
    repositoryService.deleteInternal(wrapperOf(project).id);

    var plain =
        projectService.createRepositoryUnderProject(
            project.id, fixture("demo-demo.git"), RepositoryArchetype.SERVICE);

    var promoted = projectService.adoptWrapperRepository(project.id, fixture("demo-demo.git"));

    assertEquals(plain.id, promoted.id, "promoted in place — no second clone");
    assertEquals(RepositoryArchetype.PROJECT, promoted.archetype);
    assertEquals(1, projectService.getRepositories(project.id).size());
  }

  // ------------------------------------------------- no backup remote (url-less)

  /**
   * A wrapper with no backup remote is a normal state, not a broken one: the remote is only ever a
   * backup. Every verb that would talk to it reports that plainly instead of failing on a null url.
   */
  @Test
  public void theVerbsThatNeedARemoteReportItsAbsenceInsteadOfFailing() {
    var project = projectService.create("No Remote", "no-remote", null);
    var wrapper = wrapperOf(project);

    assertDoesNotThrow(
        () -> repositoryService.pullRepository(wrapper.id),
        "a pull with nothing to pull from settles ok — imported children may still have remotes");
    assertEquals(
        "No backup remote configured — nothing to push",
        repositoryService.pushRepository(wrapper.id));

    var status = repositoryService.syncStatus(wrapper.id);
    assertEquals("main", status.branch());
    assertNull(status.ahead(), "there is no remote branch to be ahead of");
  }

  // ------------------------------------------------- publish push options (⚖3)

  /**
   * projects-volume-decoupling-plan.md §2.4, §3.3, ⚖3: the skeleton carries no pipeline config
   * (qits-ci discards the run it fires for want of one), so its publish push carries no {@code
   * qits.no-ci} — unlike the import path's ({@link
   * RepositoryServiceTest#cloneRepositoryPublishesTheImportWithNoCiSuppressed}), which can be
   * publishing an upstream's real, possibly CI-configured history.
   */
  @Test
  public void theSkeletonPushCarriesNoCiSuppression() {
    var project = projectService.create("No Suppression", "no-suppression", null);
    var wrapper = wrapperOf(project);

    assertTrue(
        fakeGitHostRepositories.lastPushOptions(wrapper.id).isEmpty(),
        "the skeleton's publish push must carry no push options");
  }
}
