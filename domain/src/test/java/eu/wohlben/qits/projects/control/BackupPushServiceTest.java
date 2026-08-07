package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The automatic backup: every branch and tag the git host holds, mirrored onto the repository's
 * forge twin.
 *
 * <p>The twin is a real throwaway bare here, and every assertion is made against <em>it</em> — the
 * whole point of a backup is what the other side ends up holding, and a test that only checked this
 * service's own state would prove nothing about that.
 *
 * <p>The debounce is turned down to something a test can wait through, but not to zero: zero would
 * remove the very window the collapse happens in and the burst case would pass for the wrong reason.
 */
@QuarkusTest
@TestProfile(BackupPushServiceTest.TestProfile.class)
public class BackupPushServiceTest {

  /** Long enough for a burst to land inside it, short enough to wait out twice per test. */
  private static final long DEBOUNCE_MS = 400;

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.projects.backup.debounce-ms", String.valueOf(DEBOUNCE_MS));
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject BackupPushService backupPushService;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;
  @Inject GitHostRepositories gitHostRepositories;
  @Inject GitMirrorRegistry gitMirrors;

  private Path hostOf(String repoId) {
    return Path.of(gitHost.fetchUrl(repoId));
  }

  private String in(Path repo, String... argv) throws Exception {
    return git.exec(repo.toFile(), argv).trim();
  }

  /** A writable bare standing in for the forge twin, seeded from a committed fixture. */
  private Path twin(String name) throws Exception {
    Path parent = Files.createTempDirectory("qits-backup-twin");
    Path bare = parent.resolve(name + ".git");
    git.exec(
        parent.toFile(), "git", "clone", "--bare", GitFixtures.path("testing-repo.git"), bare.toString());
    return bare;
  }

  /** A repository whose backup twin is {@code twin} — the row's url IS the twin. */
  private Repository repositoryBackedUpTo(Project project, Path twin) {
    return repositoryService.cloneRepository(
        twin.toString(), RepositoryArchetype.SERVICE, project);
  }

  /** Moves a branch on the GIT HOST, which is what a backup is supposed to carry to the twin. */
  private void branchOnHost(Repository repo, String branch) throws Exception {
    String sha = in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch);
    git.exec(hostOf(repo.id).toFile(), "git", "update-ref", "refs/heads/" + branch, sha);
  }

  private void awaitRuns(String repoId, long atLeast) throws Exception {
    long deadline = System.currentTimeMillis() + 15_000;
    while (backupPushService.completedRuns(repoId) < atLeast
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
  }

  @Test
  public void aBackupCarriesEveryBranchTheHostHoldsOntoTheTwin() throws Exception {
    var project = projectService.create("Backup Basic", "backup-basic", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    branchOnHost(repo, "only-on-the-platform");

    backupPushService.backupNow(repo.id);

    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", "only-on-the-platform"),
        in(twin, "git", "rev-parse", "only-on-the-platform"),
        "the branch the platform gained is now on the twin");
  }

  /**
   * One {@code git push} of several branches fires several post-receives. Without the debounce that
   * is several backups of one repository, concurrently, against one mirror.
   */
  @Test
  public void aBurstOfPushEventsProducesOneBackupRun() throws Exception {
    var project = projectService.create("Backup Debounce", "backup-debounce", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    branchOnHost(repo, "one");
    branchOnHost(repo, "two");
    branchOnHost(repo, "three");
    long before = backupPushService.completedRuns(repo.id);

    backupPushService.onPush(repo.id);
    backupPushService.onPush(repo.id);
    backupPushService.onPush(repo.id);

    awaitRuns(repo.id, before + 1);
    Thread.sleep(DEBOUNCE_MS * 2); // long enough for a second run to have shown up
    assertEquals(before + 1, backupPushService.completedRuns(repo.id), "three events, one run");
    // And the one run carried all three branches, which is why collapsing them is safe.
    for (String branch : new String[] {"one", "two", "three"}) {
      assertEquals(
          in(hostOf(repo.id), "git", "rev-parse", branch),
          in(twin, "git", "rev-parse", branch),
          branch + " reached the twin");
    }
  }

  /** An event that arrives after a run has started gets a run of its own — it may have missed it. */
  @Test
  public void anEventAfterTheDebounceElapsedSchedulesAnotherRun() throws Exception {
    var project = projectService.create("Backup Second", "backup-second", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    long before = backupPushService.completedRuns(repo.id);

    backupPushService.onPush(repo.id);
    awaitRuns(repo.id, before + 1);
    branchOnHost(repo, "late");
    backupPushService.onPush(repo.id);
    awaitRuns(repo.id, before + 2);

    assertEquals(before + 2, backupPushService.completedRuns(repo.id));
    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", "late"), in(twin, "git", "rev-parse", "late"));
  }

  /**
   * An adopted repository has no mirror until something needs one — no clone, no pull, no push ever
   * ran for it. The backup is allowed to be that something: {@code requireMirror} clones from the
   * git host on first use, so a row that has never been touched still backs up.
   */
  @Test
  public void anAdoptedRepositoryWithNoMirrorYetIsClonedOnFirstBackup() throws Exception {
    var project = projectService.create("Backup Adopted", "backup-adopted", null);
    Path twin = twin("testing-repo");
    // The bootstrap's shape: a bare the host already serves, which this service did not create.
    gitHostRepositories.ensure("adopted-backup-target", "master");
    git.exec(
        hostOf("adopted-backup-target").toFile(),
        "git",
        "fetch",
        GitFixtures.path("testing-repo.git"),
        "+refs/heads/*:refs/heads/*");
    var adopted =
        repositoryService.adoptExistingOrigin(
            project, "adopted-backup-target", twin.toString(), RepositoryArchetype.SERVICE);
    assertTrue(
        !Files.isDirectory(gitMirrors.of(adopted.id).gitDir()),
        "an adopted repository starts with no mirror at all");

    backupPushService.backupNow(adopted.id);

    assertTrue(
        Files.isDirectory(gitMirrors.of(adopted.id).gitDir()),
        "the backup cloned the mirror it needed");
    assertEquals(
        in(hostOf(adopted.id), "git", "rev-parse", "feature"),
        in(twin, "git", "rev-parse", "feature"),
        "and carried the host's refs to the twin");
  }

  @Test
  public void aRepositoryWithNoTwinIsSkippedRatherThanFailing() {
    var project = projectService.create("Backup No Twin", "backup-no-twin", null);
    var wrapper = projectService.findWrapper(project.id).orElseThrow();
    long before = backupPushService.completedRuns(wrapper.id);

    // A greenfield wrapper has no forge twin. Backing it up is a no-op, not an error — and above
    // all not something that reaches the git hook that asked for it.
    assertDoesNotThrow(() -> backupPushService.backupNow(wrapper.id));
    assertEquals(
        before + 1,
        backupPushService.completedRuns(wrapper.id),
        "it ran and swallowed the refusal");
  }

  @Test
  public void anUnknownRepositoryIsSwallowedLikeEveryOtherFailure() {
    assertDoesNotThrow(() -> backupPushService.backupNow("no-such-repository"));
    assertDoesNotThrow(() -> backupPushService.onPush("no-such-repository"));
  }

  @Test
  public void theSweepBacksUpEveryRepositoryThatHasATwin() throws Exception {
    var project = projectService.create("Backup Sweep", "backup-sweep", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    branchOnHost(repo, "swept");
    assertNotEquals(
        "", in(hostOf(repo.id), "git", "rev-parse", "swept"), "the fixture really did branch");

    backupPushService.backupAll();

    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", "swept"), in(twin, "git", "rev-parse", "swept"));
  }
}
