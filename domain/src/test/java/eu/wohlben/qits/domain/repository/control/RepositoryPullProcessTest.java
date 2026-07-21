package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.RepoProcessLease;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.process.dto.TechnicalProcessFrame;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The streamed pull ({@code beginPullRepository}): registers a repository-scoped technical process,
 * runs the recursive submodule walk off-thread, and the process's replay tells the whole story —
 * one segment per repository visited, the diamond deduped to a single segment, cycles terminating
 * without reopening, a child failure settling {@code failed} while the walk continues (and {@code
 * done failed} overall), a cleanly-mergeable root divergence auto-merging while a conflicting one
 * parks the remote tip on the {@code merge/<branch>-origin-<branch>} branch (overwritten on retry),
 * and the fast-forward verdict + the {@code .qits-config.yml} re-ingestion line landing in the
 * segment. Pull runs host-side against the bare origins, so no docker is needed.
 */
@QuarkusTest
@TestProfile(RepositoryPullProcessTest.TestProfile.class)
public class RepositoryPullProcessTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-pull-process-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject TechnicalProcessRegistry registry;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  /** Records a terminal process's full replay (attach on a terminal process replays + done). */
  private static final class Replay implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();

    @Override
    public void onFrame(TechnicalProcessFrame frame) {
      frames.add(frame);
    }

    @Override
    public void onDone() {}

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  private String fixture(String name) throws Exception {
    return getClass().getResource("/fixtures/" + name).toURI().getPath();
  }

  /** The repositories in a project keyed by the trailing bare-repo name of their url. */
  private Map<String, Repository> reposByName(String projectId) {
    return repositoryRepository.find("project.id", projectId).list().stream()
        .collect(Collectors.toMap(r -> Path.of(r.url).getFileName().toString(), r -> r));
  }

  private Path originOf(String repoId) {
    return Path.of(dataDir, repoId, "origin");
  }

  private TechnicalProcess awaitTerminal(String processId) throws InterruptedException {
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "process did not reach done in time");
    return process;
  }

  private Replay replayOf(TechnicalProcess process) {
    Replay replay = new Replay();
    process.attach(replay);
    return replay;
  }

  /** The segment names in the order they were opened. */
  private static List<String> segmentOpens(Replay replay) {
    return replay.frames.stream()
        .filter(f -> "segment-open".equals(f.kind()))
        .map(TechnicalProcessFrame::segment)
        .toList();
  }

  private static String settledStatus(Replay replay, String segment) {
    return replay.frames.stream()
        .filter(f -> "segment-settled".equals(f.kind()) && segment.equals(f.segment()))
        .map(TechnicalProcessFrame::status)
        .findFirst()
        .orElseThrow(() -> new AssertionError("segment '" + segment + "' never settled"));
  }

  private static TechnicalProcessFrame doneFrame(Replay replay) {
    return replay.frames.stream()
        .filter(f -> "done".equals(f.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no done frame"));
  }

  private static boolean hasLineContaining(Replay replay, String needle) {
    return replay.frames.stream()
        .anyMatch(f -> "line".equals(f.kind()) && f.line() != null && f.line().contains(needle));
  }

  private static long countLinesContaining(Replay replay, String needle) {
    return replay.frames.stream()
        .filter(f -> "line".equals(f.kind()) && f.line() != null && f.line().contains(needle))
        .count();
  }

  @Test
  public void aSecondPullReusesTheLiveWalkWhileASyncConflicts() throws Exception {
    var project = projectService.create("Pull Single Flight", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project, false);

    // Simulate a live pull holding the repo's origin.
    TechnicalProcess active =
        ((RepoProcessLease.Fresh) registry.beginForRepository(repo.id, "pull")).process();
    try {
      // A second pull reuses the live process id — no second walk against the same bare origin.
      assertEquals(active.id(), repositoryService.beginPullRepository(repo.id));
      // A sync CANNOT ride the pull (it would skip the push) — it is rejected, not silently reused.
      assertThrows(BadRequestException.class, () -> repositoryService.beginSyncRepository(repo.id));
      // Still exactly one active process for the repository.
      assertEquals(active.id(), registry.activeForRepository(repo.id).orElseThrow());
    } finally {
      active.forceFinish();
    }
  }

  @Test
  public void pullStreamsOneSegmentPerRepoAndDedupsTheDiamond() throws Exception {
    var project = projectService.create("Pull Diamond", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);
    // Recurse one level so the full diamond exists: super -> {child-a, shared}, child-a -> {shared,
    // grandchild}. The shared child is reached by two edges.
    repositoryService.importDirectSubmodules(
        reposByName(project.id).get("submodule-child-a.git").id);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(superRepo.id)));

    List<String> opens = segmentOpens(replay);
    // One segment per repository, not per edge: super + child-a + grandchild + exactly one for the
    // diamond-shared child (5 without the visited dedup).
    assertEquals(4, opens.size(), "one segment per visited repository, diamond deduped: " + opens);
    assertEquals("pull:submodule-super.git", opens.get(0), "the root repo's segment opens first");
    assertTrue(opens.contains("pull:child-a"), opens.toString());
    assertTrue(opens.contains("pull:grandchild"), opens.toString());
    assertEquals(
        1,
        opens.stream().filter(s -> s.startsWith("pull:shared")).count(),
        "the shared child gets exactly one segment: " + opens);
    // Everything up to date -> every segment ok, done ok.
    for (String segment : opens) {
      assertEquals("ok", settledStatus(replay, segment), segment);
    }
    assertEquals("ok", doneFrame(replay).status());
  }

  @Test
  public void aCycleTerminatesWithoutReopeningASegment() throws Exception {
    var project = projectService.create("Pull Cycle", null);
    var cycleA =
        repositoryService.cloneRepository(fixture("submodule-cycle-a.git"), null, project, true);
    // Close the loop: import cycle-b's submodule (which points back at cycle-a, deduped to the
    // existing row) so the pull recursion actually walks a->b->a.
    repositoryService.importDirectSubmodules(
        reposByName(project.id).get("submodule-cycle-b.git").id);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(cycleA.id)));

    List<String> opens = segmentOpens(replay);
    assertEquals(2, opens.size(), "a->b, then b->a links back to the visited root: " + opens);
    assertEquals("pull:submodule-cycle-a.git", opens.get(0));
    assertEquals("pull:submodule-cycle-b", opens.get(1));
    assertEquals("ok", doneFrame(replay).status());
  }

  @Test
  public void aChildFailureSettlesFailedWhileLaterChildrenStillPull() throws Exception {
    var project = projectService.create("Pull Child Failure", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);
    Map<String, Repository> repos = reposByName(project.id);
    repositoryService.importDirectSubmodules(repos.get("submodule-child-a.git").id);

    // Break the shared child's local mirror so its own pull throws (fetch by URL still works, but
    // rev-parse of the now-missing local ref fails). It is reached by two edges — the visited dedup
    // means it fails once and is not retried under the other edge.
    Repository shared = reposByName(project.id).get("submodule-shared.git");
    git.exec(originOf(shared.id).toFile(), "git", "update-ref", "-d", "refs/heads/main");

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(superRepo.id)));

    List<String> opens = segmentOpens(replay);
    long failedSharedSegments =
        opens.stream()
            .filter(s -> s.startsWith("pull:shared"))
            .filter(s -> "failed".equals(settledStatus(replay, s)))
            .count();
    assertEquals(1, failedSharedSegments, "exactly the shared child's segment fails: " + opens);
    // The root and the other children still pull — the walk degrades loudly, never blocks.
    assertEquals("ok", settledStatus(replay, "pull:submodule-super.git"));
    assertEquals("ok", settledStatus(replay, "pull:child-a"));
    assertEquals("ok", settledStatus(replay, "pull:grandchild"), "a later child still pulls");
    // A failed child segment makes the overall outcome failed.
    assertEquals("failed", doneFrame(replay).status());
  }

  @Test
  public void aCleanlyMergeableDivergenceMergesTheRemoteIn() throws Exception {
    var project = projectService.create("Pull Root Divergence", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);
    // Point the local main branch at the divergent `feature` tip: neither is an ancestor of the
    // other, but the two sides touch different content — the pull now merges the remote in instead
    // of refusing.
    String masterSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim();
    String featureSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "feature").trim();
    git.exec(originOf(repo.id).toFile(), "git", "update-ref", "refs/heads/master", featureSha);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(repo.id)));

    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
    assertTrue(hasLineContaining(replay, "Merged remote into 'master'"), "the merge verdict lands");
    // The branch advanced to a real merge commit: local tip first parent, remote tip second.
    String parents =
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master^1", "master^2").trim();
    assertEquals(featureSha + "\n" + masterSha, parents);
    // A merge advance re-ingests .qits-config.yml like a fast-forward does.
    assertEquals(1, countLinesContaining(replay, "Re-ingested .qits-config.yml"));
  }

  /** Clones {@code bareRepo}, rewrites {@code file}, commits, and pushes back to its master. */
  private void pushCommit(Path bareRepo, String file, String content, String message)
      throws Exception {
    Path work = Files.createTempDirectory("qits-pull-work-clone");
    git.exec(null, "git", "clone", bareRepo.toString(), work.toString());
    Files.writeString(work.resolve(file), content);
    git.exec(
        work.toFile(),
        "git",
        "-c",
        "user.email=test@test",
        "-c",
        "user.name=test",
        "commit",
        "-am",
        message);
    git.exec(work.toFile(), "git", "push", "origin", "HEAD:master");
  }

  @Test
  public void aConflictingDivergenceParksTheRemoteTipAndOverwritesItOnRetry() throws Exception {
    var project = projectService.create("Pull Conflict", null);
    // A writable remote (the shared fixture stays immutable) that we diverge from with a REAL
    // conflict: both sides rewrite hello.txt differently.
    Path remoteParent = Files.createTempDirectory("qits-pull-conflict-remote");
    Path remote = remoteParent.resolve("testing-repo.git");
    git.exec(
        remoteParent.toFile(),
        "git",
        "clone",
        "--bare",
        fixture("testing-repo.git"),
        remote.toString());
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);
    pushCommit(remote, "hello.txt", "remote change\n", "remote change");
    pushCommit(originOf(repo.id), "hello.txt", "local change\n", "local change");
    String localSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim();
    String remoteSha = git.exec(remote.toFile(), "git", "rev-parse", "master").trim();

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(repo.id)));

    // The pull fails (the branch is NOT merged) but the remote tip is parked on the merge branch,
    // with the conflicting file and the resolution path named in the stream.
    assertEquals("failed", doneFrame(replay).status());
    assertTrue(hasLineContaining(replay, "merge/master-origin-master"), "the parked branch named");
    assertTrue(hasLineContaining(replay, "hello.txt"), "the conflicting file is named");
    assertEquals(
        localSha,
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim(),
        "the local branch stays untouched");
    assertEquals(
        remoteSha,
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "merge/master-origin-master")
            .trim(),
        "the merge branch holds the remote tip");

    // The remote moves on (still conflicting) and the user pulls again: the parked branch is
    // overwritten with the remote's NEW tip — never a second branch, never the stale tip.
    pushCommit(remote, "hello.txt", "remote change 2\n", "remote change 2");
    String remoteSha2 = git.exec(remote.toFile(), "git", "rev-parse", "master").trim();
    Replay retry = replayOf(awaitTerminal(repositoryService.beginPullRepository(repo.id)));
    assertEquals("failed", doneFrame(retry).status());
    assertEquals(
        remoteSha2,
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "merge/master-origin-master")
            .trim(),
        "a repeated conflict overwrites the parked branch with the new remote tip");
  }

  @Test
  public void aFastForwardStreamsTheVerdictAndConfigReingestionLine() throws Exception {
    var project = projectService.create("Pull Fast Forward", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);
    // Rewind the local main branch one commit so the remote is strictly ahead -> fast-forward.
    String parentSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master~1").trim();
    git.exec(originOf(repo.id).toFile(), "git", "update-ref", "refs/heads/master", parentSha);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPullRepository(repo.id)));

    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
    // The git fetch output now streams into the segment line by line (via the GitExecutor tap), so
    // the fetch's own "From <origin>" line is present alongside the post-hoc verdict/config lines —
    // and each appears exactly once (no double delivery now that streamLines was dropped here).
    assertTrue(
        hasLineContaining(replay, "From "), "the streamed git fetch output lands in the segment");
    assertEquals(
        1,
        countLinesContaining(replay, "Fast-forwarded to"),
        "the fast-forward verdict, exactly once");
    assertEquals(
        1,
        countLinesContaining(replay, "Re-ingested .qits-config.yml"),
        "a main-branch advance re-ingests .qits-config.yml and reports it once in the segment");
  }

  @Test
  public void anUnknownRepositoryFailsFastInRequest() {
    assertThrows(NotFoundException.class, () -> repositoryService.beginPullRepository("nope"));
  }

  @Test
  public void aFreshPullReturnsAProcessIdImmediately() throws Exception {
    var project = projectService.create("Pull Fresh", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);

    String processId = repositoryService.beginPullRepository(repo.id);
    assertNotNull(processId);
    Replay replay = replayOf(awaitTerminal(processId));
    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertTrue(hasLineContaining(replay, "Already up to date"));
    assertEquals("ok", doneFrame(replay).status());
  }
}
