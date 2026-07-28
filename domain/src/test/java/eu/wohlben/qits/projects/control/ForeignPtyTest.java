package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Exercises the FFM pseudo-terminal that replaced pty4j, against a real one. These are the tests
 * that would have caught a wrong {@code struct winsize} field order or a bad {@code ioctl} variadic
 * declaration — neither of which shows up as a compile error, and both of which would reach a user
 * as a sign-in terminal that renders at the wrong size.
 *
 * <p>{@code isATtySoGitWillPrompt} is the one that matters most: the whole reason this session runs
 * on a terminal rather than a pipe pair is that {@code git push} opens {@code /dev/tty} to ask for
 * credentials, and fails non-interactively when it cannot. That is a property of the child's
 * <em>controlling</em> terminal, not merely of having a pts on fd 1, so it is asserted here rather
 * than assumed from the pts path.
 *
 * <p>Linux-only, like {@link ForeignPty} itself. No git, no docker, no network.
 */
@EnabledOnOs(OS.LINUX)
class ForeignPtyTest {

  @Test
  void allocatesASlaveDeviceUnderDevPts() throws Exception {
    try (ForeignPty pty = ForeignPty.open(80, 24)) {
      assertNotNull(pty.slavePath());
      assertTrue(
          pty.slavePath().startsWith("/dev/pts/"),
          "expected a /dev/pts slave, got " + pty.slavePath());
      assertTrue(Files.exists(Path.of(pty.slavePath())), "slave device should exist");
    }
  }

  @Test
  void theChildSeesATerminalOfTheSizeWeSet() throws Exception {
    // `stty size` prints "<rows> <cols>", which is the round-trip of the winsize struct: if the
    // field order were swapped this reports 100 40 instead of 40 100.
    assertEquals("40 100", runCapturingFirstLine(100, 40, "stty size"));
  }

  @Test
  void resizeIsVisibleToARunningChild() throws Exception {
    try (ForeignPty pty = ForeignPty.open(80, 24)) {
      Process process = spawnOnSlave(pty, "read -r ignored; stty size");
      pty.resize(132, 50);
      // Unblock the read only after the resize has landed, so the stty runs against the new size.
      pty.out().write("go\n".getBytes(StandardCharsets.UTF_8));
      pty.out().flush();
      String output = drain(pty.in(), process);
      assertTrue(output.contains("50 132"), "expected the resized dimensions, got: " + output);
    }
  }

  @Test
  void childOutputComesBackThroughTheMaster() throws Exception {
    assertEquals("hello-from-the-pty", runCapturingFirstLine(80, 24, "echo hello-from-the-pty"));
  }

  @Test
  void isATtySoGitWillPrompt() throws Exception {
    // /dev/tty is what git's terminal prompt opens. A child that cannot open it does not ask for a
    // username, it fails — which is the failure this whole session exists to avoid.
    assertEquals(
        "tty", runCapturingFirstLine(80, 24, "test -t 0 && test -w /dev/tty && echo tty || echo no"));
  }

  @Test
  void readReturnsEndOfStreamOnceTheChildIsGone() throws Exception {
    try (ForeignPty pty = ForeignPty.open(80, 24)) {
      Process process = spawnOnSlave(pty, "true");
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child should exit");
      // The master reports EIO once the last slave closes; the stream contract turns that into -1
      // so the session's reader loop terminates instead of spinning on an error.
      byte[] scratch = new byte[256];
      int result;
      do {
        result = pty.in().read(scratch, 0, scratch.length);
      } while (result > 0);
      assertEquals(-1, result);
    }
  }

  @Test
  void closingTheMasterHangsUpTheChild() throws Exception {
    // What RemoteLoginSession.terminate() relies on: closing the master is a terminal hangup, so a
    // git idling at its password prompt dies of SIGHUP rather than having to be hunted down.
    ForeignPty pty = ForeignPty.open(80, 24);
    Process process = spawnOnSlave(pty, "echo on-the-terminal; sleep 30");
    // Wait until the child has actually claimed the terminal before hanging up. Closing the master
    // while `setsid --ctty` is still running is a different failure (the ioctl fails and setsid
    // exits 1), and asserting on it would prove nothing about SIGHUP.
    assertTrue(pty.in().read(new byte[64], 0, 64) > 0, "child should have announced itself");
    pty.close();
    assertTrue(
        process.waitFor(10, TimeUnit.SECONDS),
        "the hangup should have ended it long before the 30s sleep");
    assertNotEquals(0, process.exitValue(), "a hung-up process exits non-zero");
  }

  /** Run one shell line on a fresh PTY of the given size and return its first line of output. */
  private static String runCapturingFirstLine(int cols, int rows, String script) throws Exception {
    try (ForeignPty pty = ForeignPty.open(cols, rows)) {
      Process process = spawnOnSlave(pty, script);
      String output = drain(pty.in(), process);
      return output.lines().findFirst().orElse("").trim();
    }
  }

  /**
   * Start a shell with the PTY's slave as its stdio and its own session, so the terminal is
   * genuinely the child's controlling one — the same {@code setsid --ctty} prefix {@link
   * RemoteLoginSessions} puts in front of git, and what makes {@code /dev/tty} resolve and SIGWINCH
   * arrive rather than merely having a pts on fd 1.
   */
  private static Process spawnOnSlave(ForeignPty pty, String script) throws IOException {
    Path slave = Path.of(pty.slavePath());
    return new ProcessBuilder("setsid", "--ctty", "sh", "-c", script)
        .redirectInput(slave.toFile())
        .redirectOutput(slave.toFile())
        .redirectError(slave.toFile())
        .start();
  }

  /** Read the master until end-of-stream, then reap the child. */
  private static String drain(InputStream in, Process process) throws Exception {
    StringBuilder collected = new StringBuilder();
    byte[] buffer = new byte[4096];
    int n;
    while ((n = in.read(buffer, 0, buffer.length)) > 0) {
      collected.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
    }
    process.waitFor(30, TimeUnit.SECONDS);
    return collected.toString();
  }
}
