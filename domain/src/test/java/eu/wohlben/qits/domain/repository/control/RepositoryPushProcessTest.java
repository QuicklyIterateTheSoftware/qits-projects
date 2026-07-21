package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.RepoProcessLease;
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
 * The streamed push ({@code beginPushRepository}): a single {@code push:<basename>} segment — the
 * sync's push segment without the preceding pull walk. A clean push settles the segment {@code ok}
 * and overall {@code done ok}; a hook-rejected push settles it {@code failed} with git's message in
 * the stream and overall {@code done failed}. A non-fast-forward rejection is reconciled instead of
 * failed: remote-ahead fast-forwards the mirror (nothing to push), a cleanly-mergeable divergence
 * merges the remote in and pushes the merge commit, and a conflicting one parks the remote tip on
 * the {@code merge/<branch>-origin-<branch>} branch. Kind-aware single-flight: a second push reuses
 * the live process while a cross-kind pull/sync is rejected, and vice versa. Runs host-side against
 * the bare origins, so no docker is needed.
 */
@QuarkusTest
@TestProfile(RepositoryPushProcessTest.TestProfile.class)
public class RepositoryPushProcessTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-push-process-test-repos");
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
    return settledFrame(replay, segment).status();
  }

  private static TechnicalProcessFrame settledFrame(Replay replay, String segment) {
    return replay.frames.stream()
        .filter(f -> "segment-settled".equals(f.kind()) && segment.equals(f.segment()))
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
  public void pushStreamsASinglePushSegmentSettlingOk() throws Exception {
    var project = projectService.create("Push Happy", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);

    String processId = repositoryService.beginPushRepository(repo.id);
    assertNotNull(processId);
    Replay replay = replayOf(awaitTerminal(processId));

    assertEquals(List.of("push:testing-repo.git"), segmentOpens(replay));
    assertEquals("ok", settledStatus(replay, "push:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
  }

  @Test
  public void aRejectedPushSettlesTheSegmentFailedAndOverallFailed() throws Exception {
    var project = projectService.create("Push Failure", null);
    // A writable bare remote we can rig to reject the push (the shared fixture stays immutable).
    Path remoteParent = Files.createTempDirectory("qits-push-remote");
    Path remote = remoteParent.resolve("testing-repo.git");
    git.exec(
        remoteParent.toFile(),
        "git",
        "clone",
        "--bare",
        fixture("testing-repo.git"),
        remote.toString());
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);

    // Rewind the remote's master one commit so the local mirror is strictly AHEAD and the push has
    // a real ref update to send — which a pre-receive hook rejects.
    git.exec(remote.toFile(), "git", "update-ref", "refs/heads/master", "master~1");
    Path hook = remote.resolve("hooks/pre-receive");
    Files.writeString(hook, "#!/bin/sh\nexit 1\n");
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPushRepository(repo.id)));

    assertEquals("failed", settledStatus(replay, "push:testing-repo.git"));
    assertTrue(hasLineContaining(replay, "push failed"), "the push error lands in the stream");
    assertEquals("failed", doneFrame(replay).status());
    // An ordinary rejection (hook declined) is NOT auth-classified — no hint on the settle.
    assertNull(settledFrame(replay, "push:testing-repo.git").hint());
  }

  @Test
  public void anAuthShapedPushFailureSettlesWithTheRemoteAuthHint() throws Exception {
    var project = projectService.create("Push Auth Failure", null);
    Path remoteParent = Files.createTempDirectory("qits-push-auth-remote");
    Path remote = remoteParent.resolve("testing-repo.git");
    git.exec(
        remoteParent.toFile(),
        "git",
        "clone",
        "--bare",
        fixture("testing-repo.git"),
        remote.toString());
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);

    // Rig a failing push whose output carries an auth signature: the hook's stderr reaches the
    // push output `remote:`-prefixed, and the classifier matches anywhere — the same shape a real
    // https remote produces (fatal: Authentication failed for '…') without needing a real one.
    git.exec(remote.toFile(), "git", "update-ref", "refs/heads/master", "master~1");
    Path hook = remote.resolve("hooks/pre-receive");
    Files.writeString(
        hook,
        "#!/bin/sh\necho \"fatal: Authentication failed for 'https://example.com/repo.git'\" >&2\n"
            + "exit 1\n");
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPushRepository(repo.id)));

    TechnicalProcessFrame settled = settledFrame(replay, "push:testing-repo.git");
    assertEquals("failed", settled.status());
    assertEquals(TechnicalProcessFrame.HINT_REMOTE_AUTH, settled.hint());
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
    Path work = Files.createTempDirectory("qits-push-work-clone");
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

  private Path originOf(String repoId) {
    return Path.of(dataDir, repoId, "origin");
  }

  @Test
  public void aPushBehindTheRemoteFastForwardsTheMirrorInsteadOfFailing() throws Exception {
    var project = projectService.create("Push Behind", null);
    Path remote = writableRemote("qits-push-behind-remote");
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);
    // The remote gains a commit the mirror doesn't have; the push's ref update is rejected as
    // non-fast-forward — the reconcile then fast-forwards the mirror instead of failing.
    pushCommit(remote, "hello.txt", "remote change\n", "remote change");

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPushRepository(repo.id)));

    assertEquals("ok", settledStatus(replay, "push:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
    assertTrue(hasLineContaining(replay, "nothing to push"), "the fast-forward verdict lands");
    assertEquals(
        git.exec(remote.toFile(), "git", "rev-parse", "master").trim(),
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim(),
        "the mirror caught up to the remote");
  }

  @Test
  public void aDivergedPushMergesTheRemoteInAndPushesTheMergeCommit() throws Exception {
    var project = projectService.create("Push Diverged", null);
    Path remote = writableRemote("qits-push-diverged-remote");
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);
    // Both sides gain a commit, touching different files — diverged but cleanly mergeable.
    pushCommit(remote, "hello.txt", "remote change\n", "remote change");
    pushCommit(originOf(repo.id), "README.md", "local change\n", "local change");
    String localSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim();
    String remoteSha = git.exec(remote.toFile(), "git", "rev-parse", "master").trim();

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPushRepository(repo.id)));

    assertEquals("ok", settledStatus(replay, "push:testing-repo.git"));
    assertEquals("ok", doneFrame(replay).status());
    assertTrue(hasLineContaining(replay, "Merged remote into 'master'"), "the merge verdict lands");
    // The mirror's branch became a merge commit (local tip first parent, remote tip second) and
    // the retried push landed it on the remote.
    String parents =
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master^1", "master^2").trim();
    assertEquals(localSha + "\n" + remoteSha, parents);
    assertEquals(
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim(),
        git.exec(remote.toFile(), "git", "rev-parse", "master").trim());
  }

  @Test
  public void aConflictingPushParksTheRemoteTipAndFails() throws Exception {
    var project = projectService.create("Push Conflict", null);
    Path remote = writableRemote("qits-push-conflict-remote");
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);
    // Both sides rewrite hello.txt differently — a REAL conflict.
    pushCommit(remote, "hello.txt", "remote change\n", "remote change");
    pushCommit(originOf(repo.id), "hello.txt", "local change\n", "local change");
    String localSha = git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim();
    String remoteSha = git.exec(remote.toFile(), "git", "rev-parse", "master").trim();

    Replay replay = replayOf(awaitTerminal(repositoryService.beginPushRepository(repo.id)));

    assertEquals("failed", settledStatus(replay, "push:testing-repo.git"));
    assertEquals("failed", doneFrame(replay).status());
    assertTrue(
        hasLineContaining(replay, "merge/master-origin-master"),
        "the parked merge branch is named in the stream");
    assertEquals(
        localSha,
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "master").trim(),
        "the local branch stays untouched");
    assertEquals(
        remoteSha,
        git.exec(originOf(repo.id).toFile(), "git", "rev-parse", "merge/master-origin-master")
            .trim(),
        "the merge branch holds the remote tip");
  }

  @Test
  public void aSecondPushReusesTheLiveProcessWhilePullAndSyncConflict() throws Exception {
    var project = projectService.create("Push Single Flight", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);
    TechnicalProcess active =
        ((RepoProcessLease.Fresh) registry.beginForRepository(repo.id, "push")).process();
    try {
      assertEquals(active.id(), repositoryService.beginPushRepository(repo.id));
      assertThrows(BadRequestException.class, () -> repositoryService.beginPullRepository(repo.id));
      assertThrows(BadRequestException.class, () -> repositoryService.beginSyncRepository(repo.id));
      assertEquals(active.id(), registry.activeForRepository(repo.id).orElseThrow());
    } finally {
      active.forceFinish();
    }
  }

  @Test
  public void aLivePullRejectsAPush() throws Exception {
    var project = projectService.create("Push Vs Pull", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);
    TechnicalProcess active =
        ((RepoProcessLease.Fresh) registry.beginForRepository(repo.id, "pull")).process();
    try {
      assertThrows(BadRequestException.class, () -> repositoryService.beginPushRepository(repo.id));
    } finally {
      active.forceFinish();
    }
  }

  @Test
  public void anUnknownRepositoryFailsFastInRequest() {
    assertThrows(NotFoundException.class, () -> repositoryService.beginPushRepository("nope"));
  }
}
