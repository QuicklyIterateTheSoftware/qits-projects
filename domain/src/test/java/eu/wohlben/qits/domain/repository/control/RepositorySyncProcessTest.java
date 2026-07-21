package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.process.dto.TechnicalProcessFrame;
import eu.wohlben.qits.domain.project.control.ProjectService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The streamed sync ({@code beginSyncRepository}): the {@code beginPullRepository} walk (one {@code
 * pull:<repo>} segment per repository) followed by a single {@code push:<basename>} segment. The
 * process's replay tells the whole story — the push segment opens after the pull segments and
 * settles {@code ok} on a clean sync; a push failure settles only the push segment {@code failed}
 * while the pull segment stays green and overall is {@code done failed}; a cleanly-mergeable
 * divergence is merged and the merge commit pushed, while a conflicting one parks the remote tip on
 * the merge branch and fails the process before the push segment ever opens. Runs host-side against
 * the bare origins, so no docker is needed.
 */
@QuarkusTest
@TestProfile(RepositorySyncProcessTest.TestProfile.class)
public class RepositorySyncProcessTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-sync-process-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
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

  @Test
  public void syncStreamsThePullSegmentThenAnOkPushSegment() throws Exception {
    var project = projectService.create("Sync Happy", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginSyncRepository(repo.id)));

    // The pull segment opens first, then the push segment — one push after the whole walk.
    assertEquals(
        List.of("pull:testing-repo.git", "push:testing-repo.git"),
        segmentOpens(replay),
        "the pull segment precedes the push segment");
    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertEquals("ok", settledStatus(replay, "push:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
  }

  @Test
  public void aPushFailureSettlesThePushSegmentFailedWhileThePullStaysOk() throws Exception {
    var project = projectService.create("Sync Push Failure", null);
    // A writable bare remote we can rig to reject the push (the shared fixture stays immutable).
    Path remoteParent = Files.createTempDirectory("qits-sync-remote");
    Path remote = remoteParent.resolve("testing-repo.git");
    git.exec(
        remoteParent.toFile(),
        "git",
        "clone",
        "--bare",
        fixture("testing-repo.git"),
        remote.toString());
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);

    // Rewind the remote's master one commit so the local mirror is strictly AHEAD: the pull reports
    // "ahead" (ok) and the push then has a real ref update to send...
    git.exec(remote.toFile(), "git", "update-ref", "refs/heads/master", "master~1");
    // ...which a pre-receive hook rejects (fetch ignores receive hooks, so only the push fails).
    Path hook = remote.resolve("hooks/pre-receive");
    Files.writeString(hook, "#!/bin/sh\nexit 1\n");
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));

    Replay replay = replayOf(awaitTerminal(repositoryService.beginSyncRepository(repo.id)));

    // The pull segment stays green; only the push segment fails; a red segment makes overall
    // failed.
    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertEquals("failed", settledStatus(replay, "push:testing-repo.git"));
    assertTrue(hasLineContaining(replay, "push failed"), "the push error lands in the stream");
    assertEquals("failed", doneFrame(replay).status());
  }

  /** A writable bare clone of the shared testing-repo fixture (which itself stays immutable). */
  private Path writableRemote(String prefix) throws Exception {
    Path remoteParent = Files.createTempDirectory(prefix);
    Path remote = remoteParent.resolve("testing-repo.git");
    git.exec(
        remoteParent.toFile(),
        "git",
        "clone",
        "--bare",
        fixture("testing-repo.git"),
        remote.toString());
    return remote;
  }

  /** Clones {@code bareRepo}, rewrites {@code file}, commits, and pushes back to its master. */
  private void pushCommit(Path bareRepo, String file, String content, String message)
      throws Exception {
    Path work = Files.createTempDirectory("qits-sync-work-clone");
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
  public void aCleanlyMergeableDivergenceMergesThenPushesTheMergeCommit() throws Exception {
    var project = projectService.create("Sync Diverged", null);
    Path remote = writableRemote("qits-sync-diverged-remote");
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);
    // Point the local main branch at the divergent `feature` tip: neither is an ancestor of the
    // other, but the sides merge cleanly — the sync merges the remote in and pushes the merge.
    String featureSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "feature").trim();
    git.exec(originOf(repo.id).toFile(), "git", "update-ref", "refs/heads/master", featureSha);

    Replay replay = replayOf(awaitTerminal(repositoryService.beginSyncRepository(repo.id)));

    assertEquals("ok", settledStatus(replay, "pull:testing-repo.git"));
    assertEquals("ok", settledStatus(replay, "push:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
    assertTrue(hasLineContaining(replay, "Merged remote into 'master'"), "the merge verdict lands");
    // The merge commit reached the remote: both tips now agree.
    assertEquals(
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim(),
        git.exec(remote.toFile(), "git", "rev-parse", "master").trim());
  }

  @Test
  public void aConflictingDivergenceFailsBeforeThePushSegmentOpens() throws Exception {
    var project = projectService.create("Sync Conflict", null);
    Path remote = writableRemote("qits-sync-conflict-remote");
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);
    // A REAL conflict: both sides rewrite hello.txt differently.
    pushCommit(remote, "hello.txt", "remote change\n", "remote change");
    pushCommit(originOf(repo.id), "hello.txt", "local change\n", "local change");

    Replay replay = replayOf(awaitTerminal(repositoryService.beginSyncRepository(repo.id)));

    assertEquals("failed", doneFrame(replay).status());
    assertTrue(
        hasLineContaining(replay, "merge/master-origin-master"),
        "the parked merge branch is named in the stream");
    assertTrue(
        segmentOpens(replay).stream().noneMatch(s -> s.startsWith("push:")),
        "the push segment never opens when the pull fails: " + segmentOpens(replay));
    // The remote tip is parked on the merge branch for manual resolution.
    assertEquals(
        git.exec(remote.toFile(), "git", "rev-parse", "master").trim(),
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "merge/master-origin-master")
            .trim());
  }

  @Test
  public void anUnknownRepositoryFailsFastInRequest() {
    assertThrows(NotFoundException.class, () -> repositoryService.beginSyncRepository("nope"));
  }

  @Test
  public void aFreshSyncReturnsAProcessIdImmediately() throws Exception {
    var project = projectService.create("Sync Fresh", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);

    String processId = repositoryService.beginSyncRepository(repo.id);
    assertNotNull(processId);
    Replay replay = replayOf(awaitTerminal(processId));
    assertEquals("ok", doneFrame(replay).status());
  }
}
