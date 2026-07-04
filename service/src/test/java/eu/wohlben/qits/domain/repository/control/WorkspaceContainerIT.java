package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-docker integration test for {@link DockerExecutor} — the container-lifecycle + exec +
 * process -group termination mechanics against an actual docker engine and the {@code
 * qits/workspace} image.
 *
 * <p>Part of the <strong>extended</strong> suite: skipped by every default build, run with {@code
 * ./mvnw verify -Pextended} (the parent pom's {@code extended} profile flips {@code skipITs}). It
 * self-skips when docker or the image is absent, so the profile is safe to run anywhere.
 * Deliberately a plain JUnit test constructing the real {@link DockerExecutor} (not
 * {@code @QuarkusTest}) so the {@code FakeContainerRuntime @Mock} that shadows it in unit tests
 * does not apply here.
 *
 * <p>Build the image first: {@code docker build -t qits/workspace docker/workspace} (override with
 * {@code -Dqits.workspace.image=…}).
 */
@Tag("extended")
public class WorkspaceContainerIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");

  private DockerExecutor executor() {
    DockerExecutor de = new DockerExecutor();
    de.runtime = RUNTIME;
    de.image = IMAGE;
    return de;
  }

  private boolean dockerAndImageAvailable(DockerExecutor de) {
    try {
      Process ping = new ProcessBuilder(RUNTIME, "image", "inspect", IMAGE).start();
      return ping.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  public void containerLifecycleExecAndProcessGroupTermination() throws Exception {
    DockerExecutor de = executor();
    assumeTrue(dockerAndImageAvailable(de), "docker + " + IMAGE + " required for this IT");

    String repoId = UUID.randomUUID().toString();
    String worktreeId = "it-wt";
    String container = de.containerName(worktreeId, repoId);
    de.rm(container); // clean any leftover from a prior run

    try {
      // run: the container comes up with the qits.* labels, read back through
      // listWorktreeContainers.
      String name = de.run(repoId, worktreeId, "it-branch", "main");
      assertEquals(container, name);
      assertTrue(de.exists(container), "container should be running");

      var infos = de.listWorktreeContainers(repoId);
      assertEquals(1, infos.size(), "exactly one container for the repo");
      ContainerRuntime.ContainerInfo info = infos.get(0);
      assertEquals(worktreeId, info.worktreeId());
      assertEquals("it-branch", info.branch());
      assertEquals("main", info.parent());

      // exec: the fat image carries the toolchain and /workspace is writable by the host uid.
      assertEquals(0, de.exec(container, "/workspace", Map.of(), "git", "--version").exitCode());
      ContainerRuntime.ExecResult touch =
          de.exec(container, "/workspace", Map.of(), "touch", "writable-check");
      assertEquals(0, touch.exitCode(), "workspace must be writable: " + touch.output());

      // /workspace is created root-owned at image build but the container runs as an arbitrary uid;
      // git must still operate on it (the image sets safe.directory '*'), else "dubious ownership"
      // would fail every container-side git verb qits runs (rev-parse, status, fetch, merge).
      assertEquals(0, de.exec(container, "/workspace", Map.of(), "git", "init", "-q").exitCode());
      ContainerRuntime.ExecResult status =
          de.exec(container, "/workspace", Map.of(), "git", "status", "--porcelain");
      assertEquals(
          0, status.exitCode(), "git must accept the root-owned /workspace: " + status.output());

      // Process-group termination, exactly as CommandSession does it: a setsid'd shell records its
      // pgid, and killing the group inside the container reaps the whole tree — killing the exec
      // client alone would orphan it. Liveness is checked with `kill -0 -<pgid>` (0 while any group
      // member exists, non-zero once the group is empty), independent of the container's own PID 1.
      de.exec(
          container,
          null,
          Map.of(),
          "bash",
          "-lc",
          "setsid bash -lc 'echo $$ > /tmp/qits-cmd-it.pid; sleep 300 & wait' >/dev/null 2>&1 &");
      Thread.sleep(1000);
      String pgid =
          de.exec(container, null, Map.of(), "cat", "/tmp/qits-cmd-it.pid").output().trim();
      assertTrue(pgid.matches("\\d+"), "pid file should hold a numeric pgid, was: " + pgid);
      assertTrue(groupAlive(de, container, pgid), "the launched process group should be running");

      assertEquals(
          0, de.exec(container, null, Map.of(), "sh", "-c", "kill -s TERM -- -" + pgid).exitCode());
      Thread.sleep(1000);
      assertTrue(
          !groupAlive(de, container, pgid), "the process group should be gone after the kill");
    } finally {
      de.rm(container);
      assertTrue(!de.exists(container), "container should be removed");
    }
  }

  /** Whether any process in {@code pgid}'s group is still alive inside the container. */
  private boolean groupAlive(DockerExecutor de, String container, String pgid) {
    return de.exec(container, null, Map.of(), "sh", "-c", "kill -0 -" + pgid).exitCode() == 0;
  }

  /**
   * The two Phase-3 additions that don't need a credential: the shared {@code ~/.claude} volume is
   * mounted read/write at the agent {@code HOME}, and the {@code claude} CLI is baked into the
   * image and on PATH for the arbitrary runtime uid. Uses a throwaway volume so a real login volume
   * is never touched; the full authenticated round-trip (login → {@code claude -p} writing a file
   * and pushing) is the manual verification step in the feature doc.
   */
  @Test
  public void sharedCredentialVolumeIsMountedAndClaudeIsOnPath() throws Exception {
    DockerExecutor de = executor();
    assumeTrue(dockerAndImageAvailable(de), "docker + " + IMAGE + " required for this IT");

    de.claudeVolume = "qits-it-dot-claude-" + UUID.randomUUID();
    de.claudeMount = "/claude-home";

    String repoId = UUID.randomUUID().toString();
    String worktreeId = "it-claude";
    String container = de.containerName(worktreeId, repoId);
    de.rm(container);
    try {
      de.ensureClaudeVolume(); // idempotent `docker volume create`
      de.run(repoId, worktreeId, "it-branch", "main");

      // The shared credential volume is mounted writable at the agent HOME.
      ContainerRuntime.ExecResult mount =
          de.exec(
              container,
              null,
              Map.of(),
              "sh",
              "-c",
              "touch /claude-home/.mount-check && echo MOUNT-OK");
      assertEquals(
          0,
          mount.exitCode(),
          "shared volume must be mounted writable at /claude-home: " + mount.output());
      assertTrue(mount.output().contains("MOUNT-OK"), mount.output());

      // The coding-agent CLI is on PATH (HOME points at the mounted credential volume).
      ContainerRuntime.ExecResult version =
          de.exec(container, "/workspace", Map.of("HOME", "/claude-home"), "claude", "--version");
      assertEquals(
          0, version.exitCode(), "claude must be baked in and on PATH: " + version.output());
    } finally {
      de.rm(container);
      try {
        new ProcessBuilder(RUNTIME, "volume", "rm", "-f", de.claudeVolume).start().waitFor();
      } catch (Exception ignored) {
        // best-effort cleanup of the throwaway volume
      }
    }
  }

  /**
   * The interactive PTY path, exactly as {@link
   * eu.wohlben.qits.domain.command.control.CommandRegistry} spawns terminal/daemon commands: a
   * {@code pty4j} outer PTY driving a {@code docker exec -it} client. Proves the inner TTY is
   * allocated ({@code test -t 1} succeeds inside the container), output streams back through the
   * PTY, resize propagates, and the inner exit code passes through — the one combination the
   * fake-based unit tests cannot exercise.
   */
  @Test
  public void pty4jDrivesDockerExecInteractiveTty() throws Exception {
    DockerExecutor de = executor();
    assumeTrue(dockerAndImageAvailable(de), "docker + " + IMAGE + " required for this IT");

    String repoId = UUID.randomUUID().toString();
    String worktreeId = "it-pty";
    String container = de.containerName(worktreeId, repoId);
    de.rm(container);
    try {
      de.run(repoId, worktreeId, "it-branch", "main");

      // docker exec -it … bash -lc '<script>' — the exact argv shape the registry builds.
      List<String> argv = new ArrayList<>(de.execArgv(container, true, "/workspace", Map.of()));
      argv.add("bash");
      argv.add("-lc");
      argv.add("test -t 1 && echo TTY-OK || echo NO-TTY; echo marker-line; exit 0");

      Map<String, String> env = new HashMap<>(System.getenv());
      env.put("TERM", "xterm-256color");
      PtyProcess process =
          new PtyProcessBuilder()
              .setCommand(argv.toArray(new String[0]))
              .setEnvironment(env)
              .setInitialColumns(80)
              .setInitialRows(24)
              .start();

      // Resize must not throw (the registry propagates client resizes this way).
      process.setWinSize(new WinSize(120, 40));

      StringBuilder out = new StringBuilder();
      byte[] buf = new byte[4096];
      try (InputStream in = process.getInputStream()) {
        int n;
        while ((n = in.read(buf)) != -1) {
          out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
      }
      int exit = process.waitFor();

      String text = out.toString();
      assertTrue(text.contains("TTY-OK"), "docker exec -it must allocate an inner TTY: " + text);
      assertTrue(text.contains("marker-line"), "PTY output must stream back: " + text);
      assertEquals(0, exit, "inner exit code must pass through the PTY client");
    } finally {
      de.rm(container);
    }
  }
}
