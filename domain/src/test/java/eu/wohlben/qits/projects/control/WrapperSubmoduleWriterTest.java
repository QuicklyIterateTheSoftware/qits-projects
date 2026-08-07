package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The wrapper commit: a component added to (or cut from) its project's {@code .gitmodules} and its
 * {@code 160000} gitlink, in one commit, published by a push.
 *
 * <p>Everything here is asserted against the <b>git host's</b> bare rather than the mirror, because
 * that is the repository of record — a wrapper commit that only moved a mirror ref would be
 * invisible to every clone.
 */
@QuarkusTest
public class WrapperSubmoduleWriterTest {

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WrapperSubmoduleWriter writer;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;
  @Inject GitMirrorRegistry gitMirrors;

  /** The git host's bare — what a clone would see. */
  private Path hostOf(String repoId) {
    return Path.of(gitHost.fetchUrl(repoId));
  }

  private String inHost(String repoId, String... argv) throws Exception {
    return git.exec(hostOf(repoId).toFile(), argv).trim();
  }

  private Repository wrapperOf(Project project) {
    return projectService.findWrapper(project.id).orElseThrow();
  }

  /** A child repository under the project, already published to the host, and its main head. */
  private Repository child(Project project, String fixture) throws Exception {
    return repositoryService.cloneRepository(
        GitFixtures.path(fixture), RepositoryArchetype.SERVICE, project);
  }

  private String headOf(Repository repo) throws Exception {
    return inHost(repo.id, "git", "rev-parse", repo.mainBranch);
  }

  @Test
  public void addingAComponentCommitsBothTheEntryAndItsGitlink() throws Exception {
    var project = projectService.create("Wrapper Add", "wadd", null);
    var wrapper = wrapperOf(project);
    var child = child(project, "testing-repo.git");

    String path =
        writer.addToWrapper(wrapper, "testing-repo", RepositoryArchetype.SERVICE, headOf(child));

    assertEquals("services/testing-repo", path);
    String gitmodules = inHost(wrapper.id, "git", "show", "main:.gitmodules");
    assertTrue(gitmodules.contains("[submodule \"testing-repo\"]"), gitmodules);
    assertTrue(gitmodules.contains("path = services/testing-repo"), gitmodules);
    assertTrue(
        gitmodules.contains("url = ../testing-repo.git"),
        "the url is relative, which is what makes one wrapper resolve on both hosts: " + gitmodules);
    assertTrue(gitmodules.contains("branch = main"), gitmodules);
    assertTrue(gitmodules.contains("ignore = all"), gitmodules);
    assertTrue(gitmodules.contains("update = merge"), gitmodules);

    String entry = inHost(wrapper.id, "git", "ls-tree", "main", "services/testing-repo");
    assertTrue(entry.startsWith("160000 commit " + headOf(child)), "expected a gitlink, got: " + entry);
    // The skeleton is still there — a wrapper commit amends, it does not rewrite.
    assertEquals("AGENTS.md", inHost(wrapper.id, "git", "show", "main:CLAUDE.md"));
  }

  @Test
  public void addingTheSameComponentTwiceMakesNoSecondCommit() throws Exception {
    var project = projectService.create("Wrapper Idempotent", "widem", null);
    var wrapper = wrapperOf(project);
    var child = child(project, "testing-repo.git");

    writer.addToWrapper(wrapper, "testing-repo", RepositoryArchetype.SERVICE, headOf(child));
    String afterFirst = inHost(wrapper.id, "git", "rev-parse", "main");
    writer.addToWrapper(wrapper, "testing-repo", RepositoryArchetype.SERVICE, headOf(child));

    assertEquals(
        afterFirst,
        inHost(wrapper.id, "git", "rev-parse", "main"),
        "a retried request re-asserts the same tree and commits nothing");
  }

  @Test
  public void aSecondComponentJoinsTheFirstRatherThanReplacingIt() throws Exception {
    var project = projectService.create("Wrapper Two", "wtwo", null);
    var wrapper = wrapperOf(project);
    var first = child(project, "testing-repo.git");
    var second = child(project, "submodule-shared.git");

    writer.addToWrapper(wrapper, "testing-repo", RepositoryArchetype.SERVICE, headOf(first));
    writer.addToWrapper(wrapper, "submodule-shared", RepositoryArchetype.LIBRARY, headOf(second));

    String gitmodules = inHost(wrapper.id, "git", "show", "main:.gitmodules");
    assertTrue(gitmodules.contains("path = services/testing-repo"), gitmodules);
    assertTrue(gitmodules.contains("path = libs/submodule-shared"), gitmodules);
  }

  @Test
  public void removingAComponentTakesItsEntryAndItsGitlink() throws Exception {
    var project = projectService.create("Wrapper Remove", "wrem", null);
    var wrapper = wrapperOf(project);
    var child = child(project, "testing-repo.git");
    writer.addToWrapper(wrapper, "testing-repo", RepositoryArchetype.SERVICE, headOf(child));

    Optional<String> removed = writer.removeFromWrapper(wrapper, "testing-repo");

    assertEquals(Optional.of("services/testing-repo"), removed);
    assertEquals("", inHost(wrapper.id, "git", "show", "main:.gitmodules"));
    assertEquals("", inHost(wrapper.id, "git", "ls-tree", "main", "services/testing-repo"));
    assertEquals(
        "AGENTS.md",
        inHost(wrapper.id, "git", "show", "main:CLAUDE.md"),
        "removing a member leaves the rest of the wrapper alone");
  }

  @Test
  public void removingSomethingTheWrapperNeverCarriedIsANoOp() {
    var project = projectService.create("Wrapper Remove Absent", "wremabs", null);
    var wrapper = wrapperOf(project);

    assertEquals(Optional.empty(), writer.removeFromWrapper(wrapper, "never-added"));
  }

  @Test
  public void anUnplaceableArchetypeCannotBeAMember() {
    var project = projectService.create("Wrapper Unplaceable", "wunp", null);
    var wrapper = wrapperOf(project);

    assertThrows(
        BadRequestException.class,
        () -> writer.addToWrapper(wrapper, "x", RepositoryArchetype.FORK, "0".repeat(40)));
    assertThrows(
        BadRequestException.class,
        () -> writer.addToWrapper(wrapper, "x", RepositoryArchetype.PROJECT, "0".repeat(40)));
  }

  /**
   * Two creates racing for the wrapper's branch tip. The loser's push is refused as a
   * non-fast-forward, and the retry has to re-read — a retry that re-pushed the same commit would
   * either fail again or, worse, drop the winner's entry.
   *
   * <p>The race is made deterministic with a client-side {@code pre-push} hook in the mirror: it
   * advances the host's branch behind this push's back exactly once, then deletes itself.
   */
  @Test
  public void aLostFastForwardRaceIsRetriedAgainstTheWinnersCommit() throws Exception {
    var project = projectService.create("Wrapper Race", "wrace", null);
    var wrapper = wrapperOf(project);
    var child = child(project, "testing-repo.git");
    // Warm the mirror so the pre-push hook has somewhere to live.
    repositoryService.syncStatus(wrapper.id);

    // The interloper's commit, built in the host's own bare and not yet referenced by anything.
    Path host = hostOf(wrapper.id);
    String base = inHost(wrapper.id, "git", "rev-parse", "main");
    String tree = inHost(wrapper.id, "git", "rev-parse", "main^{tree}");
    String interloper =
        git.exec(
                host.toFile(),
                java.util.Map.of(
                    "GIT_AUTHOR_NAME", "other",
                    "GIT_AUTHOR_EMAIL", "other@local",
                    "GIT_COMMITTER_NAME", "other",
                    "GIT_COMMITTER_EMAIL", "other@local"),
                "git",
                "commit-tree",
                tree,
                "-p",
                base,
                "-m",
                "the other writer got there first")
            .trim();
    installOneShotPrePush(wrapper.id, host, interloper);

    writer.addToWrapper(wrapper, "testing-repo", RepositoryArchetype.SERVICE, headOf(child));

    String tip = inHost(wrapper.id, "git", "rev-parse", "main");
    assertNotEquals(interloper, tip, "the wrapper commit did land");
    assertEquals(
        interloper,
        inHost(wrapper.id, "git", "rev-parse", "main^1"),
        "the retry built on the winner's commit rather than re-pushing the stale one");
    assertTrue(
        inHost(wrapper.id, "git", "show", "main:.gitmodules").contains("services/testing-repo"));
    assertFalse(
        Files.exists(gitMirrors.of(wrapper.id).gitDir().resolve("hooks").resolve("pre-push")),
        "the one-shot hook removed itself, so it cannot leak into another test");
  }

  /**
   * A {@code pre-push} hook in the mirror that moves the host's {@code main} to {@code sha} and then
   * deletes itself — the client side of a race, staged rather than timed.
   */
  private void installOneShotPrePush(String repoId, Path host, String sha) throws Exception {
    Path hooks = gitMirrors.of(repoId).gitDir().resolve("hooks");
    Files.createDirectories(hooks);
    Path hook = hooks.resolve("pre-push");
    Files.writeString(
        hook,
        "#!/bin/sh\n"
            + "rm -f \"$0\"\n"
            + "git --git-dir='"
            + host.toAbsolutePath()
            + "' update-ref refs/heads/main "
            + sha
            + "\n"
            + "exit 0\n");
    if (!hook.toFile().setExecutable(true)) {
      throw new IllegalStateException("could not make the pre-push hook executable: " + hook);
    }
  }
}
