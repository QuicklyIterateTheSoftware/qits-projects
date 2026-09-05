package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>What an octopus fold brought in, read against a real repository.</b>
 *
 * <p>The whole claim is the base: the fold minus every released tag that does not contain it. On
 * this platform {@code main} only advances by merging released tags, so "already shipped" IS
 * "reachable from a release tag" — an answer that survives re-folds, first folds that
 * fast-forwarded, and the release itself reaching {@code main}, none of which {@code ^1..} or a
 * live read of {@code main} does.
 *
 * <p>Driven through real git rather than a stub, the idiom {@code RepositoryServiceTest} sets: a
 * fixture bare is cloned as a repository, the fold is made and pushed to the git host, and the
 * mirror is dropped so the read re-clones it — which is also how the commits under test get into the
 * mirror at all, since a mirror fetched seconds ago is trusted rather than refreshed.
 */
@QuarkusTest
public class MergeRangeCommitsTest {

  @Inject RepositoryService repositoryService;

  @Inject CommitService commitService;

  @Inject ProjectService projectService;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitHostAddress gitHost;

  @Inject GitExecutor git;

  /**
   * The shape a release request actually produces: two branches folded onto the default branch in
   * one octopus merge. What the range must contain is those two commits and nothing that was
   * already on the branch they were folded onto.
   */
  @Test
  public void theFoldsRangeIsWhatItsSourcesBroughtAndNeverWhatMainAlreadyHad() throws Exception {
    Repository repo = cloned("Merge Range Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;

    branchWithCommit(work, main, "lane-one", "one.txt", "Add one");
    branchWithCommit(work, main, "lane-two", "two.txt", "Add two");
    // The default branch moves on under the request, which is the ordinary case and also what makes
    // this a real octopus: with nothing of its own to contribute, git fast-forwards onto the first
    // head instead and the fold's first parent is a source rather than the target.
    git.exec(work.toFile(), "git", "checkout", "-q", main);
    commit(work, "later.txt", "Move main on");
    // The previous release: everything on main is shipped, and the tag is what says so.
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "release/fold", main);
    git.exec(
        work.toFile(), "git", "merge", "-q", "-m", "Release request: fold", "lane-one", "lane-two");
    String fold = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();
    git.exec(work.toFile(), "git", "push", "-q", "--tags", "origin", "release/fold");
    goCold(repo);

    CommitService.MergeRange range = commitService.listMergeRange(repo.id, fold);

    assertTrue(range.present(), "the fold was just pushed to the git host");
    List<String> subjects = range.commits().stream().map(commit -> commit.message()).toList();
    assertEquals(
        List.of("Add one", "Add two", "Release request: fold"),
        subjects.stream().sorted().toList(),
        "the fold and exactly what its two sources brought: " + subjects);
    assertFalse(
        subjects.contains("Move main on"),
        "the release tag holds main as it stood, so its history is outside the range");
    assertFalse(
        subjects.contains("Initial commit"), "and that includes everything main already had");
  }

  /**
   * <b>A re-folded request answers everything it merges, not only the last re-fold.</b> The first
   * fold fast-forwards here (the target had nothing of its own), so there is not even a fold commit
   * whose parents could be asked; the second fold's first parent is the first fold's head. A range
   * built from parents reports {@code wave-two} alone — the released tag base reports both waves
   * and still leaves out everything the previous release shipped.
   */
  @Test
  public void aRefoldedRequestAnswersEveryFoldPastThePreviousRelease() throws Exception {
    Repository repo = cloned("Refolded Range Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;

    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    branchWithCommit(work, main, "wave-one", "one.txt", "Add one");
    branchWithCommit(work, main, "wave-two", "two.txt", "Add two");
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "release/refold", main);
    git.exec(work.toFile(), "git", "merge", "-q", "-m", "Release request: first fold", "wave-one");
    git.exec(work.toFile(), "git", "merge", "-q", "-m", "Release request: second fold", "wave-two");
    String fold = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();
    git.exec(work.toFile(), "git", "push", "-q", "--tags", "origin", "release/refold");
    goCold(repo);

    CommitService.MergeRange range = commitService.listMergeRange(repo.id, fold);

    assertTrue(range.present());
    List<String> subjects = range.commits().stream().map(commit -> commit.message()).toList();
    assertTrue(
        subjects.containsAll(List.of("Add one", "Add two")),
        "both folds' contributions, not only the last re-fold's: " + subjects);
    assertFalse(
        subjects.contains("Initial commit"), "what the previous release shipped stays out");
  }

  /**
   * A fold nothing references any more — a withdrawn request's backing branch is deleted, and the
   * git host prunes what no ref reaches — is an <b>answer</b> about the repository, never a failure
   * of the read. The caller turns it into a sentence; anything that threw here would turn a page
   * about an old request into a 500.
   */
  @Test
  public void aFoldTheRepositoryNoLongerHoldsIsAnAnswerAndNotAFailure() throws Exception {
    Repository repo = cloned("Pruned Fold Project");

    CommitService.MergeRange range =
        commitService.listMergeRange(repo.id, "0123456789abcdef0123456789abcdef01234567");

    assertFalse(range.present());
    assertEquals(List.of(), range.commits());
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private Repository cloned(String projectName) throws Exception {
    var project = projectService.create(projectName + " " + UUID.randomUUID(), null);
    return repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
  }

  /** A working clone of the repository's bare on the git host, with an identity to commit under. */
  private Path checkout(Repository repo) throws Exception {
    Path parent = Files.createTempDirectory("merge-range");
    parent.toFile().deleteOnExit();
    git.exec(parent.toFile(), "git", "clone", "-q", gitHost.fetchUrl(repo.id), "work");
    Path work = parent.resolve("work");
    git.exec(work.toFile(), "git", "config", "user.email", "fixtures@qits.local");
    git.exec(work.toFile(), "git", "config", "user.name", "qits fixtures");
    return work;
  }

  private void branchWithCommit(Path work, String from, String branch, String file, String message)
      throws Exception {
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", branch, from);
    commit(work, file, message);
  }

  private void commit(Path work, String file, String message) throws Exception {
    Files.writeString(work.resolve(file), message + "\n", StandardCharsets.UTF_8);
    git.exec(work.toFile(), "git", "add", "-A");
    git.exec(work.toFile(), "git", "commit", "-q", "-m", message);
  }

  /**
   * Drop the mirror, so the next read clones it afresh. A mirror fetched inside {@code
   * qits.projects.git.mirror-freshness-ms} is trusted rather than refreshed, and everything this
   * test pushes happens well inside that window.
   */
  private void goCold(Repository repo) throws IOException {
    Path mirror = gitMirrors.of(repo.id).gitDir();
    if (!Files.exists(mirror)) {
      return;
    }
    try (var paths = Files.walk(mirror)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
