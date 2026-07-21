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
import org.junit.jupiter.api.Test;

/**
 * The streamed push ({@code beginPushRepository}): a single {@code push:<basename>} segment — the
 * sync's push segment without the preceding pull walk. A clean push settles the segment {@code ok}
 * and overall {@code done ok}; a rejected push settles it {@code failed} with git's message in the
 * stream and overall {@code done failed}. Kind-aware single-flight: a second push reuses the live
 * process while a cross-kind pull/sync is rejected, and vice versa. Runs host-side against the bare
 * origins, so no docker is needed.
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
