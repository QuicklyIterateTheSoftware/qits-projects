package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.RepoProcessLease;
import eu.wohlben.qits.domain.process.control.RepoReservation;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.project.control.ProjectService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The sign-in session registry: kind-aware single-flight against the repository's process guard (a
 * live pull refuses the terminal, a live sign-in rejects a push), attach-with-replay for a second
 * client, and guard release when the interactive push exits. The "interactive push" is a real
 * {@code git push} against a local bare remote whose pre-receive hook spin-waits on a flag file —
 * holding the session alive exactly as long as the test needs, no real https remote required.
 */
@QuarkusTest
@TestProfile(RemoteLoginSessionsTest.TestProfile.class)
public class RemoteLoginSessionsTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-remote-login-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject RemoteLoginSessions sessions;
  @Inject TechnicalProcessRegistry registry;
  @Inject GitExecutor git;

  /** Collects everything the session writes; always open. */
  private static final class CapturingSink implements CommandOutputSink {
    final StringBuilder received = new StringBuilder();

    @Override
    public synchronized void write(String data) {
      received.append(data);
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    synchronized String text() {
      return received.toString();
    }
  }

  private String fixture(String name) throws Exception {
    return getClass().getResource("/fixtures/" + name).toURI().getPath();
  }

  private static void await(String what, BooleanSupplier condition) throws Exception {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), what);
  }

  /**
   * Whether the repository's single-flight is held. The sign-in reservation is deliberately
   * invisible to {@code activeForRepository} (so a reload opens no empty process dialog), so probe
   * it the way a real operation would: a reservation attempt that conflicts means the slot is held;
   * one that acquires means it is free (release the probe immediately).
   */
  private boolean guardHeld(String repoId) {
    RepoReservation probe = registry.reserveRepository(repoId, "probe");
    if (probe instanceof RepoReservation.Acquired acquired) {
      registry.releaseRepository(repoId, acquired.token());
      return false;
    }
    return true;
  }

  @Test
  public void aLivePullRefusesTheSignInTerminal() throws Exception {
    var project = projectService.create("Login Vs Pull", null);
    var repo = repositoryService.cloneRepository(fixture("testing-repo.git"), null, project);
    TechnicalProcess active =
        ((RepoProcessLease.Fresh) registry.beginForRepository(repo.id, "pull")).process();
    try {
      RemoteLoginSessions.OpenResult result = sessions.open(repo.id, new CapturingSink(), c -> {});
      RemoteLoginSessions.OpenResult.Refused refused =
          assertInstanceOf(RemoteLoginSessions.OpenResult.Refused.class, result);
      assertEquals("pull", refused.runningKind());
    } finally {
      active.forceFinish();
    }
  }

  @Test
  public void theSessionGuardsTheRepositoryReplaysToASecondClientAndReleasesOnExit()
      throws Exception {
    var project = projectService.create("Login Lifecycle", null);
    // A writable bare remote with a ref update to send (local strictly ahead) and a pre-receive
    // hook that spin-waits on a flag file — the push stays live until the test says otherwise.
    Path remoteParent = Files.createTempDirectory("qits-remote-login-remote");
    Path remote = remoteParent.resolve("testing-repo.git");
    git.exec(
        remoteParent.toFile(),
        "git",
        "clone",
        "--bare",
        fixture("testing-repo.git"),
        remote.toString());
    var repo = repositoryService.cloneRepository(remote.toString(), null, project);
    git.exec(remote.toFile(), "git", "update-ref", "refs/heads/master", "master~1");
    Path flag = remoteParent.resolve("release-the-push");
    Path hook = remote.resolve("hooks/pre-receive");
    Files.writeString(hook, "#!/bin/sh\nwhile [ ! -f " + flag + " ]; do sleep 0.1; done\nexit 1\n");
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));

    CountDownLatch ended = new CountDownLatch(2);
    try {
      CapturingSink first = new CapturingSink();
      assertInstanceOf(
          RemoteLoginSessions.OpenResult.Opened.class,
          sessions.open(repo.id, first, c -> ended.countDown()));

      // The reservation holds the single-flight: the sync bar's push begin is rejected in-request.
      // (It stays invisible to active-process discovery, so a reload opens no empty dialog.)
      assertTrue(guardHeld(repo.id));
      assertTrue(registry.activeForRepository(repo.id).isEmpty(), "reservation opens no dialog");
      assertThrows(BadRequestException.class, () -> repositoryService.beginPushRepository(repo.id));

      // A second client attaches to the SAME session with full replay — the banner arrives even
      // though it was written before this client existed.
      CapturingSink second = new CapturingSink();
      assertInstanceOf(
          RemoteLoginSessions.OpenResult.Opened.class,
          sessions.open(repo.id, second, c -> ended.countDown()));
      await("banner replayed to the second client", () -> second.text().contains("Signing in to"));
    } finally {
      Files.createFile(flag);
    }

    // The hook releases, the push exits, every end listener fires, and the reservation clears.
    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS), "end listeners fired");
    await("reservation released", () -> !guardHeld(repo.id));
  }

  @Test
  public void anUnknownRepositoryThrowsAndReleasesTheReservation() {
    assertThrows(
        NotFoundException.class, () -> sessions.open("nope", new CapturingSink(), c -> {}));
    assertFalse(guardHeld("nope"), "the reservation did not leak on a failed spawn");
  }
}
